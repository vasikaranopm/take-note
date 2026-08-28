package com.onestop.takenotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity representing a configurable user category.
 *
 * Fields:
 * - id: Primary key auto-generated
 * - name: Display name of the category (e.g. "Work", "Personal", "Research")
 * - colorHex: Hex color code (e.g. "#005AC1") or null for auto-theme palette
 * - isDefault: If true, this is one of the initial system starter categories
 * - orderIndex: Sorting order for UI chips
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val colorHex: String? = null,
    val isDefault: Boolean = false,
    val orderIndex: Int = 0
)
