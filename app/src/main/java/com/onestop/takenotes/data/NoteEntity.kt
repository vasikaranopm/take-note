package com.onestop.takenotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a saved note, link, or image.
 *
 * Fields:
 * - id: Primary key auto-generated
 * - contentType: "Link", "Image", or "Text"
 * - contentData: Raw URL string, Content URI string, or plain text
 * - title: Extracted or user-edited title
 * - description: Extracted description / EXIF metadata / note body
 * - category: "Work", "Personal", "Shopping", "Education", "News", "Archive"
 * - timestamp: Epoch millisecond creation time
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val contentType: String,
    val contentData: String,
    val title: String,
    val description: String,
    val category: String,
    val timestamp: Long = System.currentTimeMillis()
)
