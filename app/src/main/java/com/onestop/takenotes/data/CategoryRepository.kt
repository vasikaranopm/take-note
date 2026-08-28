package com.onestop.takenotes.data

import kotlinx.coroutines.flow.Flow

class CategoryRepository(private val categoryDao: CategoryDao) {

    val allCategories: Flow<List<CategoryEntity>> = categoryDao.getAllCategories()

    suspend fun getAllCategoriesSync(): List<CategoryEntity> {
        return categoryDao.getAllCategoriesList()
    }

    suspend fun getAllCategoriesList(): List<CategoryEntity> {
        return categoryDao.getAllCategoriesList()
    }

    suspend fun insertCategories(categories: List<CategoryEntity>) {
        categoryDao.insertCategories(categories)
    }

    suspend fun deleteAllCategories() {
        categoryDao.deleteAllCategories()
    }

    suspend fun ensureDefaultCategories() {
        val count = categoryDao.getCategoryCount()
        if (count == 0) {
            val defaults = listOf(
                CategoryEntity(name = "Work", colorHex = "#005AC1", isDefault = true, orderIndex = 0),
                CategoryEntity(name = "Personal", colorHex = "#006A60", isDefault = true, orderIndex = 1),
                CategoryEntity(name = "Shopping", colorHex = "#9C4235", isDefault = true, orderIndex = 2),
                CategoryEntity(name = "Education", colorHex = "#6750A4", isDefault = true, orderIndex = 3),
                CategoryEntity(name = "News", colorHex = "#BA1A1A", isDefault = true, orderIndex = 4),
                CategoryEntity(name = "Archive", colorHex = "#575E71", isDefault = true, orderIndex = 5)
            )
            categoryDao.insertCategories(defaults)
        }
    }

    suspend fun addCategory(name: String, colorHex: String? = null): Long {
        val cleanName = name.trim()
        val existing = categoryDao.getCategoryByName(cleanName)
        if (existing != null) return existing.id

        val count = categoryDao.getCategoryCount()
        val newCategory = CategoryEntity(
            name = cleanName,
            colorHex = colorHex,
            isDefault = false,
            orderIndex = count
        )
        return categoryDao.insertCategory(newCategory)
    }

    suspend fun updateCategory(category: CategoryEntity) {
        categoryDao.updateCategory(category)
    }

    suspend fun deleteCategory(category: CategoryEntity) {
        categoryDao.deleteCategory(category)
    }

    suspend fun deleteCategoryById(id: Long) {
        categoryDao.deleteCategoryById(id)
    }
}
