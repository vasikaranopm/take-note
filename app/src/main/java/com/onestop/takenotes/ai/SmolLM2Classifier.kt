package com.onestop.takenotes.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.onestop.takenotes.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelDownloadStatus {
    object Idle : ModelDownloadStatus
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadStatus
    data class Completed(val filePath: String, val sizeMb: Long) : ModelDownloadStatus
    data class Error(val error: String) : ModelDownloadStatus
}

data class ModelStatus(
    val isModelLoaded: Boolean,
    val modelName: String = BuildConfig.SMOLLM2_MODEL_FILENAME,
    val modelPath: String? = null,
    val details: String = "",
    val downloadStatus: ModelDownloadStatus = ModelDownloadStatus.Idle
)

object SmolLM2Classifier {

    private const val TAG = "SmolLM2Classifier"
    private const val ASSET_MODEL_DIR = "model"
    val MODEL_DOWNLOAD_URL: String = BuildConfig.SMOLLM2_MODEL_URL
    val TARGET_MODEL_FILENAME: String = BuildConfig.SMOLLM2_MODEL_FILENAME

    val VALID_CATEGORIES = listOf(
        "Work",
        "Personal",
        "Shopping",
        "Education",
        "News",
        "Archive"
    )

    private var llmInference: LlmInference? = null
    private var modelFileLoaded: Boolean = false
    private var loadedModelPath: String? = null
    private val initMutex = Mutex()
    private val downloadMutex = Mutex()

    private val _downloadStatus = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
    val downloadStatus: StateFlow<ModelDownloadStatus> = _downloadStatus.asStateFlow()

    private var isDownloading = false

    /**
     * Starts the background download of SmolLM2 GGUF model at application startup if not present.
     */
    fun startStartupDownload(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val targetDir = File(context.filesDir, "model")
            if (!targetDir.exists()) targetDir.mkdirs()

            val existingModel = targetDir.listFiles()?.firstOrNull {
                it.length() > 10 * 1024 * 1024 && (it.extension.lowercase() in listOf("gguf", "bin", "task", "tflite"))
            }

            if (existingModel != null && existingModel.exists()) {
                val sizeMb = existingModel.length() / (1024 * 1024)
                _downloadStatus.value = ModelDownloadStatus.Completed(existingModel.absolutePath, sizeMb)
                ensureInitialized(context)
                return@launch
            }

            // Check assets first
            try {
                val assetList = context.assets.list(ASSET_MODEL_DIR)
                val assetModel = assetList?.firstOrNull {
                    it.endsWith(".gguf", ignoreCase = true) ||
                    it.endsWith(".bin", ignoreCase = true) ||
                    it.endsWith(".task", ignoreCase = true)
                }
                if (assetModel != null) {
                    val targetFile = File(targetDir, assetModel)
                    if (!targetFile.exists() || targetFile.length() == 0L) {
                        context.assets.open("$ASSET_MODEL_DIR/$assetModel").use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (targetFile.exists() && targetFile.length() > 0) {
                        _downloadStatus.value = ModelDownloadStatus.Completed(targetFile.absolutePath, targetFile.length() / (1024 * 1024))
                        ensureInitialized(context)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Assets check skipped: ${e.message}")
            }

            // Initiate download from HuggingFace
            downloadModel(context)
        }
    }

    /**
     * Downloads the SmolLM2 GGUF model from HuggingFace.
     */
    suspend fun downloadModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (isDownloading) return@withLock false
            isDownloading = true

            val targetDir = File(context.filesDir, "model")
            if (!targetDir.exists()) targetDir.mkdirs()

            val targetFile = File(targetDir, TARGET_MODEL_FILENAME)
            val tempFile = File(targetDir, "$TARGET_MODEL_FILENAME.tmp")

            try {
                _downloadStatus.value = ModelDownloadStatus.Downloading(
                    progressPercent = 0,
                    downloadedBytes = 0,
                    totalBytes = -1
                )

                Log.i(TAG, "Starting download from $MODEL_DOWNLOAD_URL to ${tempFile.absolutePath}")
                downloadFileWithRedirects(MODEL_DOWNLOAD_URL, tempFile) { downloaded, total ->
                    val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    _downloadStatus.value = ModelDownloadStatus.Downloading(
                        progressPercent = progress.coerceIn(0, 100),
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }

                if (tempFile.exists() && tempFile.length() > 0) {
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (renamed || targetFile.exists()) {
                        val sizeMb = targetFile.length() / (1024 * 1024)
                        Log.i(TAG, "Model downloaded successfully: ${targetFile.absolutePath} ($sizeMb MB)")
                        _downloadStatus.value = ModelDownloadStatus.Completed(targetFile.absolutePath, sizeMb)
                        isDownloading = false
                        ensureInitialized(context)
                        return@withLock true
                    }
                }
                throw Exception("Failed to finalize downloaded model file")
            } catch (e: Throwable) {
                Log.e(TAG, "Download error: ${e.message}", e)
                tempFile.delete()
                _downloadStatus.value = ModelDownloadStatus.Error(e.localizedMessage ?: "Download failed")
                isDownloading = false
                return@withLock false
            }
        }
    }

    private fun downloadFileWithRedirects(
        urlStr: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        var currentUrl = urlStr
        var connection: HttpURLConnection? = null
        var redirectCount = 0
        val maxRedirects = 8

        while (redirectCount < maxRedirects) {
            val url = URL(currentUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TakeNotes-Android-App")
            }

            val responseCode = connection.responseCode
            if (responseCode in 300..399) {
                val newUrl = connection.getHeaderField("Location")
                if (newUrl.isNullOrBlank()) {
                    throw Exception("Redirect without Location header (HTTP $responseCode)")
                }
                currentUrl = if (newUrl.startsWith("http")) newUrl else URL(url, newUrl).toString()
                connection.disconnect()
                redirectCount++
                Log.d(TAG, "Following redirect #$redirectCount to: $currentUrl")
                continue
            } else if (responseCode == HttpURLConnection.HTTP_OK) {
                break
            } else {
                throw Exception("HTTP Error: $responseCode ${connection.responseMessage}")
            }
        }

        if (connection == null) {
            throw Exception("Failed to open connection")
        }

        val totalLength = connection.contentLengthLong
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            inputStream = connection.inputStream
            outputStream = FileOutputStream(destination)

            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            var totalDownloaded: Long = 0
            var lastUpdate = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalDownloaded += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdate > 100 || totalDownloaded == totalLength) {
                    onProgress(totalDownloaded, totalLength)
                    lastUpdate = now
                }
            }
            outputStream.flush()
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { connection.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Inspects if the SmolLM2 model (.gguf, .bin, or .task) is present in assets or internal storage.
     */
    suspend fun getModelStatus(context: Context): ModelStatus = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "model")
        if (targetDir.exists()) {
            val existingModel = targetDir.listFiles()?.firstOrNull { 
                it.length() > 0 && (it.extension.lowercase() in listOf("gguf", "bin", "task", "tflite"))
            }
            if (existingModel != null) {
                val sizeMb = existingModel.length() / (1024 * 1024)
                return@withContext ModelStatus(
                    isModelLoaded = true,
                    modelName = existingModel.name,
                    modelPath = existingModel.absolutePath,
                    details = "SmolLM2 GGUF active (${existingModel.name}, $sizeMb MB)",
                    downloadStatus = _downloadStatus.value
                )
            }
        }

        // Check assets
        try {
            val assetList = context.assets.list(ASSET_MODEL_DIR)
            val assetModel = assetList?.firstOrNull { 
                it.endsWith(".gguf", ignoreCase = true) || 
                it.endsWith(".bin", ignoreCase = true) || 
                it.endsWith(".task", ignoreCase = true) ||
                it.endsWith(".tflite", ignoreCase = true)
            }
            if (assetModel != null) {
                return@withContext ModelStatus(
                    isModelLoaded = true,
                    modelName = assetModel,
                    modelPath = "assets/model/$assetModel",
                    details = "Model detected in assets/model/$assetModel",
                    downloadStatus = _downloadStatus.value
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Asset check failed: ${e.message}")
        }

        val currentDl = _downloadStatus.value
        val details = when (currentDl) {
            is ModelDownloadStatus.Downloading -> "Downloading SmolLM2 GGUF (${currentDl.progressPercent}%)..."
            is ModelDownloadStatus.Error -> "Download issue: ${currentDl.error}. Tap to retry."
            else -> "Downloading SmolLM2-135M GGUF from HuggingFace..."
        }

        ModelStatus(
            isModelLoaded = false,
            modelPath = null,
            details = details,
            downloadStatus = currentDl
        )
    }

    /**
     * Initializes MediaPipe LLM Inference if model binary is available.
     */
    private suspend fun ensureInitialized(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext true

        initMutex.withLock {
            if (llmInference != null) return@withLock true

            try {
                val targetDir = File(context.filesDir, "model")
                if (!targetDir.exists()) targetDir.mkdirs()

                var targetFile = targetDir.listFiles()?.firstOrNull { 
                    it.length() > 0 && (it.extension.lowercase() in listOf("gguf", "bin", "task", "tflite"))
                }

                if (targetFile == null || targetFile.length() == 0L) {
                    val assetList = context.assets.list(ASSET_MODEL_DIR)
                    val assetModelName = assetList?.firstOrNull { 
                        it.endsWith(".gguf", ignoreCase = true) || 
                        it.endsWith(".bin", ignoreCase = true) || 
                        it.endsWith(".task", ignoreCase = true) ||
                        it.endsWith(".tflite", ignoreCase = true)
                    }

                    if (assetModelName != null) {
                        targetFile = File(targetDir, assetModelName)
                        Log.i(TAG, "Copying model $assetModelName from assets to ${targetFile.absolutePath}...")
                        context.assets.open("$ASSET_MODEL_DIR/$assetModelName").use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                    Log.i(TAG, "Initializing MediaPipe LlmInference with ${targetFile.absolutePath}")
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(targetFile.absolutePath)
                        .setMaxTokens(32)
                        .setTopK(40)
                        .setTemperature(0.1f)
                        .setRandomSeed(42)
                        .build()

                    llmInference = LlmInference.createFromOptions(context, options)
                    modelFileLoaded = true
                    loadedModelPath = targetFile.absolutePath
                    Log.i(TAG, "MediaPipe LlmInference initialized successfully with ${targetFile.name}.")
                    return@withLock true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Could not initialize MediaPipe LlmInference with model: ${e.message}")
                llmInference = null
                modelFileLoaded = false
            }
            return@withLock false
        }
    }

    /**
     * Categorizes title & description using SmolLM2 with prompt template or fallback.
     */
    suspend fun categorize(
        context: Context,
        title: String,
        description: String,
        availableCategories: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val categories = if (availableCategories.isNotEmpty()) availableCategories else VALID_CATEGORIES
        val categoriesStr = categories.joinToString(", ")

        val contentText = buildString {
            if (title.isNotBlank()) append("Title: $title. ")
            if (description.isNotBlank()) append("Description: $description")
        }.trim()

        val prompt = "Content: $contentText. Task: Categorize this content into exactly one of these labels: $categoriesStr. Respond with only the exact label name."

        val initialized = ensureInitialized(context)
        if (initialized && llmInference != null) {
            try {
                val response = llmInference?.generateResponse(prompt)?.trim().orEmpty()
                Log.d(TAG, "SmolLM2 raw response: $response")
                val matchedCategory = extractCategoryFromResponse(response, categories)
                if (matchedCategory != null) {
                    return@withContext matchedCategory
                }
            } catch (e: Exception) {
                Log.e(TAG, "SmolLM2 inference failed: ${e.message}, falling back to heuristic engine")
            }
        }

        // High-precision local heuristic categorization fallback
        return@withContext heuristicCategorize(title, description, categories)
    }

    private fun extractCategoryFromResponse(response: String, categories: List<String>): String? {
        val clean = response.trim().replace(Regex("[^a-zA-Z0-9 ]"), " ")
        for (cat in categories) {
            if (clean.contains(cat, ignoreCase = true)) {
                return cat
            }
        }
        return null
    }

    /**
     * Local classification heuristic engine for offline or pre-model-upload state.
     */
    fun heuristicCategorize(
        title: String,
        description: String,
        categories: List<String> = VALID_CATEGORIES
    ): String {
        val combined = "$title $description".lowercase()

        // Check if any custom category name is directly mentioned in title or description
        for (cat in categories) {
            if (cat.length > 2 && combined.contains(cat.lowercase())) {
                return cat
            }
        }

        val shoppingKeywords = listOf(
            "buy", "price", "shop", "store", "cart", "amazon", "ebay", "deal", "discount",
            "sale", "order", "shipping", "coupon", "product", "cost", "dollar", "usd", "item", "purchase"
        )
        val workKeywords = listOf(
            "meeting", "project", "deadline", "task", "jira", "github", "gitlab", "slack", "email",
            "client", "report", "presentation", "slides", "budget", "invoice", "roadmap", "sync",
            "standup", "sprint", "management", "contract", "resume", "interview", "office", "code"
        )
        val educationKeywords = listOf(
            "learn", "tutorial", "course", "study", "university", "school", "lecture", "paper",
            "research", "algorithm", "documentation", "guide", "concept", "theory", "math",
            "physics", "science", "textbook", "exam", "lesson", "wikipedia", "how to"
        )
        val newsKeywords = listOf(
            "news", "breaking", "politics", "economy", "nytimes", "bbc", "cnn", "reuters", "wsj",
            "today", "report", "announced", "government", "president", "policy", "investigation",
            "headline", "journal", "daily", "weather alert", "election"
        )
        val archiveKeywords = listOf(
            "archive", "receipt", "backup", "old", "log", "statement", "tax", "history", "record",
            "saved", "reference", "legacy", "snapshot", "audit"
        )

        fun countMatches(keywords: List<String>): Int {
            return keywords.count { kw -> combined.contains(kw) }
        }

        val workScore = countMatches(workKeywords)
        val shoppingScore = countMatches(shoppingKeywords)
        val eduScore = countMatches(educationKeywords)
        val newsScore = countMatches(newsKeywords)
        val archiveScore = countMatches(archiveKeywords)

        val maxScore = maxOf(workScore, shoppingScore, eduScore, newsScore, archiveScore)
        if (maxScore > 0) {
            val candidate = when (maxScore) {
                shoppingScore -> "Shopping"
                workScore -> "Work"
                eduScore -> "Education"
                newsScore -> "News"
                archiveScore -> "Archive"
                else -> "Personal"
            }
            // Return candidate if available in categories, otherwise match closest
            val match = categories.firstOrNull { it.equals(candidate, ignoreCase = true) }
            if (match != null) return match
        }

        val personalMatch = categories.firstOrNull { it.equals("Personal", ignoreCase = true) }
        return personalMatch ?: categories.firstOrNull() ?: "Personal"
    }
}
