package com.onestop.takenotes.extraction

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.chimbori.crux.Crux
import com.chimbori.crux.api.Fields
import com.chimbori.crux.api.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ExtractedContent(
    val contentType: String, // "Link", "Image", "Text", "Video"
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

    private val YOUTUBE_REGEX = Pattern.compile(
        "(?:https?:\\/\\/)?(?:www\\.|m\\.)?(?:youtube\\.com\\/(?:watch\\?.*?v=|embed\\/|v\\/|shorts\\/)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})",
        Pattern.CASE_INSENSITIVE
    )

    private val crux = Crux()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
     * Scrapes and extracts rich metadata from URL using Chimbori Crux, oEmbed (YouTube/Vimeo),
     * and Jsoup with offline fallback.
     */
    suspend fun extractFromUrl(url: String, originalText: String = url): ExtractedContent =
        withContext(Dispatchers.IO) {
            // 1. Check for YouTube URLs first
            val youtubeMatcher = YOUTUBE_REGEX.matcher(url)
            if (youtubeMatcher.find()) {
                val videoId = youtubeMatcher.group(1)
                val ytExtracted = extractYouTubeMetadata(url, videoId)
                if (ytExtracted != null) {
                    return@withContext ytExtracted
                }
            }

            // 2. Check for Vimeo URLs
            if (url.contains("vimeo.com")) {
                val vimeoExtracted = extractVimeoMetadata(url)
                if (vimeoExtracted != null) {
                    return@withContext vimeoExtracted
                }
            }

            // 3. Generic Web Page extraction using Chimbori Crux + Jsoup
            try {
                val host = extractHost(url)
                val httpUrl = url.toHttpUrlOrNull()

                // Connect and retrieve document with realistic browser user-agent
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .timeout(8000)
                    .followRedirects(true)
                    .get()

                // Execute Chimbori Crux extraction pipeline
                var cruxResource: Resource? = null
                if (httpUrl != null) {
                    try {
                        cruxResource = crux.extractFrom(originalUrl = httpUrl, parsedDoc = doc)
                    } catch (e: Exception) {
                        // Crux extraction graceful fallback
                    }
                }

                // Extract Title: Crux -> OpenGraph -> Twitter -> Document title -> Host
                var title = cruxResource?.get(Fields.TITLE)?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    title = doc.select("meta[property=og:title]").attr("content").trim()
                }
                if (title.isBlank()) {
                    title = doc.select("meta[name=twitter:title]").attr("content").trim()
                }
                if (title.isBlank()) {
                    title = doc.title().trim()
                }
                if (title.isBlank()) {
                    title = host
                }

                // Extract Description: Crux -> Meta description -> OpenGraph -> Twitter -> Lead paragraph
                var description = cruxResource?.get(Fields.DESCRIPTION)?.toString()?.trim().orEmpty()
                if (description.isBlank()) {
                    description = doc.select("meta[name=description]").attr("content").trim()
                }
                if (description.isBlank()) {
                    description = doc.select("meta[property=og:description]").attr("content").trim()
                }
                if (description.isBlank()) {
                    description = doc.select("meta[name=twitter:description]").attr("content").trim()
                }
                if (description.isBlank()) {
                    val firstP = doc.select("p").firstOrNull { it.text().isNotBlank() }?.text()?.trim().orEmpty()
                    description = firstP.ifBlank { "Shared Link from $host" }
                }

                // Extract Banner / Preview Image: Crux -> OpenGraph -> Twitter -> First article image
                var imageUrl = cruxResource?.get(Fields.BANNER_IMAGE_URL)?.toString()?.trim().orEmpty()
                if (imageUrl.isBlank()) {
                    imageUrl = doc.select("meta[property=og:image]").attr("content").trim()
                }
                if (imageUrl.isBlank()) {
                    imageUrl = doc.select("meta[name=twitter:image]").attr("content").trim()
                }
                if (imageUrl.isBlank()) {
                    imageUrl = doc.select("link[rel=image_src]").attr("href").trim()
                }
                if (imageUrl.isNotBlank() && imageUrl.startsWith("/")) {
                    // Resolve relative URL
                    try {
                        val baseUri = URI(url)
                        imageUrl = baseUri.resolve(imageUrl).toString()
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                // Extract Additional Metadata: Site Name, Favicon, Estimated Reading Time
                val extraMetadata = mutableMapOf<String, String>()
                extraMetadata["domain"] = host

                val siteName = cruxResource?.get(Fields.SITE_NAME)?.toString()?.trim().orEmpty().ifBlank {
                    doc.select("meta[property=og:site_name]").attr("content").trim()
                }
                if (siteName.isNotBlank()) {
                    extraMetadata["site"] = siteName
                }

                val faviconUrl = cruxResource?.get(Fields.FAVICON_URL)?.toString()?.trim().orEmpty().ifBlank {
                    doc.select("link[rel~=(?i)^(shortcut|apple-touch-)?icon]").attr("abs:href").trim()
                }
                if (faviconUrl.isNotBlank()) {
                    extraMetadata["favicon"] = faviconUrl
                }

                val rawDuration = cruxResource?.get(Fields.DURATION_MS)
                val durationMs = when (rawDuration) {
                    is Number -> rawDuration.toLong()
                    is String -> rawDuration.toLongOrNull()
                    else -> null
                }
                if (durationMs != null && durationMs > 0) {
                    val minutes = durationMs / 60000
                    if (minutes > 0) {
                        extraMetadata["read_time"] = "$minutes min read"
                    }
                }

                ExtractedContent(
                    contentType = "Link",
                    contentData = url,
                    title = cleanText(title),
                    description = cleanText(description).take(500),
                    imageUrl = if (imageUrl.isNotBlank()) imageUrl else null,
                    extraMetadata = extraMetadata
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
     * Extracts YouTube video metadata via YouTube oEmbed API, HTML scraping (Schema.org LD+JSON, OpenGraph, meta description),
     * and thumbnail services.
     */
    private suspend fun extractYouTubeMetadata(url: String, videoId: String?): ExtractedContent? {
        val vid = videoId ?: return null
        val canonicalYtUrl = "https://www.youtube.com/watch?v=$vid"
        val highResThumbnail = "https://img.youtube.com/vi/$vid/hqdefault.jpg"

        var title: String = ""
        var authorName: String = ""
        var authorUrl: String = ""
        var thumbnailUrl: String = highResThumbnail
        var extractedDescription: String = ""
        val extraMetadata = mutableMapOf(
            "platform" to "YouTube",
            "video_id" to vid,
            "domain" to "youtube.com"
        )

        // 1. Fetch official oEmbed metadata (Title, Author name, Author URL, Thumbnail)
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=$canonicalYtUrl&format=json"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "TakeNotesApp/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string().orEmpty()
                if (bodyString.isNotBlank()) {
                    val json = JSONObject(bodyString)
                    title = json.optString("title").trim()
                    authorName = json.optString("author_name").trim()
                    authorUrl = json.optString("author_url").trim()
                    val thumb = json.optString("thumbnail_url").trim()
                    if (thumb.isNotBlank()) {
                        thumbnailUrl = thumb
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore oEmbed failure and continue to HTML scraping
        }

        // 2. Fetch HTML page to extract the actual video description and richer tags
        try {
            val doc = Jsoup.connect(canonicalYtUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(8000)
                .followRedirects(true)
                .get()

            // Try to extract from Schema.org JSON-LD first
            val scriptTags = doc.select("script[type=application/ld+json]")
            for (script in scriptTags) {
                try {
                    val jsonStr = script.data().trim()
                    if (jsonStr.isNotBlank()) {
                        val json = JSONObject(jsonStr)
                        if (json.has("description")) {
                            val d = json.optString("description").trim()
                            if (d.isNotBlank() && !isGenericYouTubeSlogan(d)) {
                                extractedDescription = d
                            }
                        }
                        if (title.isBlank() && json.has("name")) {
                            title = json.optString("name").trim()
                        }
                        if (authorName.isBlank() && json.has("author")) {
                            authorName = json.optString("author").trim()
                        }
                        val uploadDate = json.optString("uploadDate").trim()
                        if (uploadDate.isNotBlank()) {
                            extraMetadata["upload_date"] = uploadDate
                        }
                        val genre = json.optString("genre").trim()
                        if (genre.isNotBlank()) {
                            extraMetadata["genre"] = genre
                        }
                    }
                } catch (e: Exception) {
                    // Ignore JSON parsing errors for this script tag
                }
                if (extractedDescription.isNotBlank()) break
            }

            // If JSON-LD didn't yield description, try meta tags
            if (extractedDescription.isBlank()) {
                val metaDesc = doc.select("meta[name=description]").attr("content").trim()
                val ogDesc = doc.select("meta[property=og:description]").attr("content").trim()
                val twitterDesc = doc.select("meta[name=twitter:description]").attr("content").trim()
                val itempropDesc = doc.select("meta[itemprop=description]").attr("content").trim()

                extractedDescription = listOf(metaDesc, ogDesc, twitterDesc, itempropDesc)
                    .firstOrNull { it.isNotBlank() && !isGenericYouTubeSlogan(it) }
                    .orEmpty()
            }

            // Fallback for title if still blank
            if (title.isBlank()) {
                val ogTitle = doc.select("meta[property=og:title]").attr("content").trim()
                val docTitle = doc.title().removeSuffix(" - YouTube").trim()
                title = ogTitle.ifBlank { docTitle }
            }

            // Fallback for image if still default
            if (thumbnailUrl == highResThumbnail) {
                val ogImage = doc.select("meta[property=og:image]").attr("content").trim()
                if (ogImage.isNotBlank()) {
                    thumbnailUrl = ogImage
                }
            }
        } catch (e: Exception) {
            // Ignore HTML parsing failure
        }

        if (authorName.isNotBlank()) extraMetadata["channel"] = authorName
        if (authorUrl.isNotBlank()) extraMetadata["channel_url"] = authorUrl

        // Format final description
        val finalDescription = when {
            extractedDescription.isNotBlank() -> {
                extractedDescription
            }
            authorName.isNotBlank() -> {
                "YouTube video by $authorName\n$canonicalYtUrl"
            }
            else -> {
                "YouTube Video: $canonicalYtUrl"
            }
        }

        val finalTitle = title.ifBlank { "YouTube Video ($vid)" }

        return ExtractedContent(
            contentType = "Link",
            contentData = canonicalYtUrl,
            title = cleanText(finalTitle),
            description = cleanText(finalDescription),
            imageUrl = thumbnailUrl,
            extraMetadata = extraMetadata
        )
    }

    private fun isGenericYouTubeSlogan(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("enjoy the videos and music you love") ||
               lower.contains("upload original content") ||
               lower.contains("share it all with friends, family, and the world")
    }

    /**
     * Extracts Vimeo video metadata via Vimeo oEmbed API.
     */
    private suspend fun extractVimeoMetadata(url: String): ExtractedContent? {
        return try {
            val oembedUrl = "https://vimeo.com/api/oembed.json?url=$url"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", "TakeNotesApp/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string().orEmpty()
                if (bodyString.isNotBlank()) {
                    val json = JSONObject(bodyString)
                    val title = json.optString("title").ifBlank { "Vimeo Video" }
                    val authorName = json.optString("author_name")
                    val thumbnailUrl = json.optString("thumbnail_url")
                    val durationSeconds = json.optInt("duration", 0)

                    val description = if (authorName.isNotBlank()) {
                        "Vimeo video by $authorName"
                    } else {
                        "Vimeo Video"
                    }

                    val metadata = mutableMapOf(
                        "platform" to "Vimeo",
                        "domain" to "vimeo.com"
                    )
                    if (authorName.isNotBlank()) metadata["creator"] = authorName
                    if (durationSeconds > 0) metadata["duration"] = "${durationSeconds / 60}m ${durationSeconds % 60}s"

                    return ExtractedContent(
                        contentType = "Link",
                        contentData = url,
                        title = cleanText(title),
                        description = description,
                        imageUrl = if (thumbnailUrl.isNotBlank()) thumbnailUrl else null,
                        extraMetadata = metadata
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
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
