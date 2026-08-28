package com.onestop.takenotes.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.onestop.takenotes.data.CategoryEntity
import com.onestop.takenotes.data.CategoryRepository
import com.onestop.takenotes.data.NoteEntity
import com.onestop.takenotes.data.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupRestoreManager {

    private const val BACKUP_VERSION = 1
    private const val APP_IDENTIFIER = "TakeNotes"

    /**
     * Generates a default timestamped filename for the backup.
     */
    fun generateBackupFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "takenotes_backup_${dateFormat.format(Date())}.json"
    }

    /**
     * Converts notes and categories into a formatted JSON string.
     */
    fun createBackupJson(
        notes: List<NoteEntity>,
        categories: List<CategoryEntity>
    ): String {
        val root = JSONObject()
        root.put("version", BACKUP_VERSION)
        root.put("appName", APP_IDENTIFIER)
        root.put("exportedAt", System.currentTimeMillis())
        val isoFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        root.put("exportedAtFormatted", isoFormat.format(Date()))
        root.put("totalNotes", notes.size)
        root.put("totalCategories", categories.size)

        val categoriesArray = JSONArray()
        for (cat in categories) {
            val catObj = JSONObject()
            catObj.put("name", cat.name)
            if (cat.colorHex != null) {
                catObj.put("colorHex", cat.colorHex)
            }
            catObj.put("isDefault", cat.isDefault)
            catObj.put("orderIndex", cat.orderIndex)
            categoriesArray.put(catObj)
        }
        root.put("categories", categoriesArray)

        val notesArray = JSONArray()
        for (note in notes) {
            val noteObj = JSONObject()
            noteObj.put("contentType", note.contentType)
            noteObj.put("contentData", note.contentData)
            noteObj.put("title", note.title)
            noteObj.put("description", note.description)
            noteObj.put("category", note.category)
            noteObj.put("timestamp", note.timestamp)
            notesArray.put(noteObj)
        }
        root.put("notes", notesArray)

        return root.toString(2)
    }

    /**
     * Exports the backup JSON directly to a user-chosen Document URI via SAF.
     */
    suspend fun exportBackupToUri(
        context: Context,
        uri: Uri,
        notes: List<NoteEntity>,
        categories: List<CategoryEntity>
    ): Result<BackupSummary> = withContext(Dispatchers.IO) {
        try {
            val jsonString = createBackupJson(notes, categories)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonString)
                    writer.flush()
                }
            } ?: return@withContext Result.failure(Exception("Failed to open destination file"))

            val summary = BackupSummary(
                totalNotes = notes.size,
                totalCategories = categories.size,
                timestamp = System.currentTimeMillis(),
                sizeBytes = jsonString.toByteArray(Charsets.UTF_8).size.toLong()
            )
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parses a backup file from a given Uri.
     */
    suspend fun parseBackupFromUri(
        context: Context,
        uri: Uri
    ): Result<BackupPayload> = withContext(Dispatchers.IO) {
        try {
            val contentBuilder = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        contentBuilder.append(line).append("\n")
                        line = reader.readLine()
                    }
                }
            } ?: return@withContext Result.failure(Exception("Could not read backup file"))

            val jsonStr = contentBuilder.toString().trim()
            if (jsonStr.isBlank()) {
                return@withContext Result.failure(Exception("Backup file is empty"))
            }

            val json = JSONObject(jsonStr)
            val version = json.optInt("version", 1)
            val appName = json.optString("appName", APP_IDENTIFIER)
            val exportedAt = json.optLong("exportedAt", System.currentTimeMillis())

            val categoriesList = mutableListOf<CategoryEntity>()
            if (json.has("categories")) {
                val catsArray = json.getJSONArray("categories")
                for (i in 0 until catsArray.length()) {
                    val catObj = catsArray.getJSONObject(i)
                    val name = catObj.optString("name").trim()
                    if (name.isNotBlank()) {
                        val colorHex = if (catObj.has("colorHex") && !catObj.isNull("colorHex")) {
                            catObj.getString("colorHex")
                        } else null
                        val isDefault = catObj.optBoolean("isDefault", false)
                        val orderIndex = catObj.optInt("orderIndex", i)
                        categoriesList.add(
                            CategoryEntity(
                                name = name,
                                colorHex = colorHex,
                                isDefault = isDefault,
                                orderIndex = orderIndex
                            )
                        )
                    }
                }
            }

            val notesList = mutableListOf<NoteEntity>()
            if (json.has("notes")) {
                val notesArray = json.getJSONArray("notes")
                for (i in 0 until notesArray.length()) {
                    val noteObj = notesArray.getJSONObject(i)
                    val contentData = noteObj.optString("contentData")
                    val title = noteObj.optString("title")
                    val description = noteObj.optString("description")
                    val contentType = noteObj.optString("contentType", "Text")
                    val category = noteObj.optString("category", "Personal")
                    val timestamp = noteObj.optLong("timestamp", System.currentTimeMillis())

                    if (contentData.isNotBlank() || title.isNotBlank() || description.isNotBlank()) {
                        notesList.add(
                            NoteEntity(
                                contentType = contentType,
                                contentData = contentData,
                                title = title,
                                description = description,
                                category = category,
                                timestamp = timestamp
                            )
                        )
                    }
                }
            }

            val payload = BackupPayload(
                version = version,
                exportedAt = exportedAt,
                appName = appName,
                categories = categoriesList,
                notes = notesList
            )

            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(Exception("Invalid or corrupted backup JSON: ${e.localizedMessage}", e))
        }
    }

    /**
     * Executes the restore operation on the database (Merge or Replace).
     */
    suspend fun restoreDatabase(
        payload: BackupPayload,
        mode: RestoreMode,
        noteRepository: NoteRepository,
        categoryRepository: CategoryRepository
    ): Result<RestoreResult> = withContext(Dispatchers.IO) {
        try {
            var restoredNotesCount = 0
            var restoredCategoriesCount = 0
            var skippedNotesCount = 0

            when (mode) {
                RestoreMode.REPLACE -> {
                    // 1. Clear existing database tables
                    noteRepository.deleteAllNotes()
                    categoryRepository.deleteAllCategories()

                    // 2. Restore categories
                    if (payload.categories.isNotEmpty()) {
                        categoryRepository.insertCategories(payload.categories)
                        restoredCategoriesCount = payload.categories.size
                    } else {
                        categoryRepository.ensureDefaultCategories()
                    }

                    // 3. Restore notes
                    if (payload.notes.isNotEmpty()) {
                        noteRepository.insertNotes(payload.notes)
                        restoredNotesCount = payload.notes.size
                    }
                }

                RestoreMode.MERGE -> {
                    // 1. Merge Categories (Add non-existing)
                    val existingCategories = categoryRepository.getAllCategoriesList()
                    val existingCatNames = existingCategories.map { it.name.lowercase().trim() }.toSet()

                    val newCategoriesToInsert = mutableListOf<CategoryEntity>()
                    for (cat in payload.categories) {
                        if (!existingCatNames.contains(cat.name.lowercase().trim())) {
                            newCategoriesToInsert.add(cat.copy(id = 0))
                        }
                    }

                    if (newCategoriesToInsert.isNotEmpty()) {
                        categoryRepository.insertCategories(newCategoriesToInsert)
                        restoredCategoriesCount = newCategoriesToInsert.size
                    }

                    // 2. Merge Notes (Deduplicate by contentData + title or timestamp)
                    val existingNotes = noteRepository.getAllNotesList()
                    val existingSignatures = existingNotes.map {
                        "${it.contentType}|${it.contentData.trim()}|${it.title.trim()}"
                    }.toSet()

                    val newNotesToInsert = mutableListOf<NoteEntity>()
                    for (note in payload.notes) {
                        val sig = "${note.contentType}|${note.contentData.trim()}|${note.title.trim()}"
                        if (!existingSignatures.contains(sig)) {
                            newNotesToInsert.add(note.copy(id = 0))
                        } else {
                            skippedNotesCount++
                        }
                    }

                    if (newNotesToInsert.isNotEmpty()) {
                        noteRepository.insertNotes(newNotesToInsert)
                        restoredNotesCount = newNotesToInsert.size
                    }
                }
            }

            Result.success(
                RestoreResult(
                    restoredNotesCount = restoredNotesCount,
                    restoredCategoriesCount = restoredCategoriesCount,
                    skippedNotesCount = skippedNotesCount,
                    mode = mode
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates an Intent to share the backup content text/file.
     */
    fun createShareBackupIntent(jsonString: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "TakeNotes Backup (${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())})")
            putExtra(Intent.EXTRA_TEXT, jsonString)
        }
    }
}
