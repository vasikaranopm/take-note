package com.onestop.takenotes.backup

import com.onestop.takenotes.data.CategoryEntity
import com.onestop.takenotes.data.NoteEntity

enum class RestoreMode {
    MERGE,   // Keep existing data, add non-duplicate items
    REPLACE  // Clear existing data, fully restore from backup
}

data class BackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val appName: String = "TakeNotes",
    val categories: List<CategoryEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList()
)

data class BackupSummary(
    val totalNotes: Int,
    val totalCategories: Int,
    val timestamp: Long,
    val sizeBytes: Long
)

data class RestoreResult(
    val restoredNotesCount: Int,
    val restoredCategoriesCount: Int,
    val skippedNotesCount: Int,
    val mode: RestoreMode
)
