package com.onestop.takenotes.extraction

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class ExtractedContent(
    val contentType: String, // "Link", "Image", "Text"
    val contentData: String, // URL, URI string, or plain text
    val title: String,
    val description: String,
    val imageUrl: String? = null,
    val extraMetadata: Map<String, String> = emptyMap()
)

object MetadataExtractor {

    private val URL_REGEX = Pattern.compile(
        "\\b(https?://[a-zA-Z0-9+&@#/%?=~_|!:,.;]*[a-zA-Z0-9+&@#/%=~_|])",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Extracts metadata from shared plain text or URL.
     */
    suspend fun extractFromText(rawText: String): ExtractedContent = withContext(Dispatchers.IO) {
        val trimmed = rawText.trim()
        val matcher = URL_REGEX.matcher(trimmed)

        if (matcher.find()) {
            val url = matcher.group(1) ?: trimmed
            return@withContext extractFromUrl(url, trimmed)
        }

        // It's pure plain text
        val lines = trimmed.lines().filter { it.isNotBlank() }
        val title = when {
            lines.isNotEmpty() && lines[0].length <= 60 -> lines[0].trim()
            lines.isNotEmpty() -> lines[0].take(50).trim() + "..."
            else -> "Note Entry"
        }
        val description = if (lines.size > 1) {
            lines.drop(1).joinToString("\n").trim()
        } else {
            trimmed
        }

        ExtractedContent(
            contentType = "Text",
            contentData = trimmed,
            title = title.ifBlank { "Note Entry" },
            description = description.ifBlank { trimmed }
        )
    }

    /**
     * Scrapes <title>, <meta name="description">, og:tags from URL using Jsoup with offline fallback.
     */
    suspend fun extractFromUrl(url: String, originalText: String = url): ExtractedContent =
        withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .timeout(8000)
                    .followRedirects(true)
                    .get()

                // Extract Title
                var title = doc.title().trim()
                if (title.isBlank()) {
                    title = doc.select("meta[property=og:title]").attr("content").trim()
                }
                if (title.isBlank()) {
                    title = doc.select("meta[name=twitter:title]").attr("content").trim()
                }
                if (title.isBlank()) {
                    title = extractHost(url)
                }

                // Extract Description
                var description = doc.select("meta[name=description]").attr("content").trim()
                if (description.isBlank()) {
                    description = doc.select("meta[property=og:description]").attr("content").trim()
                }
                if (description.isBlank()) {
                    description = doc.select("meta[name=twitter:description]").attr("content").trim()
                }
                if (description.isBlank()) {
                    // Fallback to first meaningful paragraph or original text
                    val firstP = doc.select("p").first()?.text()?.trim().orEmpty()
                    description = firstP.ifBlank { "Shared Link from ${extractHost(url)}" }
                }

                // Extract OG preview image
                var ogImage = doc.select("meta[property=og:image]").attr("content").trim()
                if (ogImage.isBlank()) {
                    ogImage = doc.select("meta[name=twitter:image]").attr("content").trim()
                }

                ExtractedContent(
                    contentType = "Link",
                    contentData = url,
                    title = cleanText(title),
                    description = cleanText(description).take(400),
                    imageUrl = if (ogImage.isNotBlank()) ogImage else null,
                    extraMetadata = mapOf("domain" to extractHost(url))
                )
            } catch (e: Exception) {
                // Offline or scraping failure fallback
                val host = extractHost(url)
                ExtractedContent(
                    contentType = "Link",
                    contentData = url,
                    title = host.ifBlank { "Web Link" },
                    description = if (originalText != url) originalText else "Link: $url",
                    extraMetadata = mapOf("offline" to "true", "domain" to host)
                )
            }
        }

    /**
     * Extracts metadata from shared Image URI using ExifInterface.
     */
    suspend fun extractFromImage(context: Context, imageUri: Uri): ExtractedContent =
        withContext(Dispatchers.IO) {
            val fileName = queryFileName(context, imageUri) ?: "Shared Photo"
            val metadataMap = mutableMapOf<String, String>()
            val detailsList = mutableListOf<String>()

            try {
                context.contentResolver.openInputStream(imageUri)?.use { inputStream: InputStream ->
                    val exif = ExifInterface(inputStream)

                    // Date / Time
                    val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (!dateTime.isNullOrBlank()) {
                        metadataMap["Date"] = dateTime
                        detailsList.add("Date: $dateTime")
                    }

                    // Camera / Device
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                    val cameraInfo = listOfNotNull(make, model).joinToString(" ").trim()
                    if (cameraInfo.isNotBlank()) {
                        metadataMap["Camera"] = cameraInfo
                        detailsList.add("Camera: $cameraInfo")
                    }

                    // GPS Coordinates
                    val latLong = FloatArray(2)
                    if (exif.getLatLong(latLong)) {
                        val latStr = String.format(Locale.US, "%.4f", latLong[0])
                        val longStr = String.format(Locale.US, "%.4f", latLong[1])
                        val gpsInfo = "$latStr, $longStr"
                        metadataMap["GPS"] = gpsInfo
                        detailsList.add("Location: $gpsInfo")
                    }

                    // Description / Comment
                    val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                        ?: exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                    if (!userComment.isNullOrBlank()) {
                        metadataMap["Description"] = userComment
                        detailsList.add(userComment)
                    }

                    // Resolution
                    val width = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                    val height = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)
                    if (!width.isNullOrBlank() && !height.isNullOrBlank() && width != "0") {
                        metadataMap["Dimensions"] = "${width}x${height}px"
                        detailsList.add("Dimensions: ${width}x${height}px")
                    }
                }
            } catch (e: Exception) {
                // Ignore Exif read errors
            }

            val title = if (fileName.isNotBlank() && !fileName.startsWith("content:")) {
                fileName.substringBeforeLast(".")
            } else {
                "Captured Image ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())}"
            }

            val description = if (detailsList.isNotEmpty()) {
                detailsList.joinToString(" • ")
            } else {
                "Image captured and saved to TakeNotes"
            }

            ExtractedContent(
                contentType = "Image",
                contentData = imageUri.toString(),
                title = title,
                description = description,
                extraMetadata = metadataMap
            )
        }

    private fun extractHost(url: String): String {
        return try {
            val uri = URI(url)
            uri.host ?: url
        } catch (e: Exception) {
            url.substringAfter("://").substringBefore("/")
        }
    }

    private fun cleanText(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = it.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                // fallback
            }
        }
        if (result == null) {
            result = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return result
    }
}
