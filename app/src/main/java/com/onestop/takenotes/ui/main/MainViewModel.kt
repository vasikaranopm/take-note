package com.onestop.takenotes.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onestop.takenotes.TakeNotesApplication
import com.onestop.takenotes.ai.ModelStatus
import com.onestop.takenotes.ai.SmolLM2Classifier
import com.onestop.takenotes.data.NoteEntity
import com.onestop.takenotes.extraction.MetadataExtractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as TakeNotesApplication).repository
    private val categoryRepository = (application as TakeNotesApplication).categoryRepository

    val categories: StateFlow<List<com.onestop.takenotes.data.CategoryEntity>> = categoryRepository.allCategories
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _modelStatus = MutableStateFlow(
        ModelStatus(isModelLoaded = false, details = "Checking SmolLM2 model...")
    )
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private var recentlyDeletedNote: NoteEntity? = null

    // Observe all notes to calculate counts for category chips
    val allNotes: StateFlow<List<NoteEntity>> = repository.allNotes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val displayedNotes: StateFlow<List<NoteEntity>> = combine(
        _selectedCategory,
        _searchQuery
    ) { category, query ->
        Pair(category, query)
    }.flatMapLatest { (category, query) ->
        if (query.isNotBlank()) {
            repository.searchNotes(query)
        } else {
            repository.getNotesByCategory(category)
        }
    }.combine(_selectedCategory) { notes, category ->
        // When searching, filter by category if not 'All'
        if (_searchQuery.value.isNotBlank() && category != "All") {
            notes.filter { it.category.equals(category, ignoreCase = true) }
        } else {
            notes
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        initCategoriesAndDefaults()
        refreshModelStatus()
        observeDownloadStatus()
    }

    private fun initCategoriesAndDefaults() {
        viewModelScope.launch {
            categoryRepository.ensureDefaultCategories()
        }
    }

    fun addCategory(name: String, colorHex: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            categoryRepository.addCategory(name.trim(), colorHex)
        }
    }

    fun updateCategory(category: com.onestop.takenotes.data.CategoryEntity) {
        viewModelScope.launch {
            categoryRepository.updateCategory(category)
        }
    }

    fun deleteCategory(category: com.onestop.takenotes.data.CategoryEntity) {
        viewModelScope.launch {
            if (_selectedCategory.value.equals(category.name, ignoreCase = true)) {
                _selectedCategory.value = "All"
            }
            categoryRepository.deleteCategory(category)
        }
    }

    private fun observeDownloadStatus() {
        viewModelScope.launch {
            SmolLM2Classifier.downloadStatus.collect {
                refreshModelStatus()
            }
        }
    }

    fun retryModelDownload() {
        viewModelScope.launch {
            SmolLM2Classifier.downloadModel(getApplication())
            refreshModelStatus()
        }
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            _modelStatus.value = SmolLM2Classifier.getModelStatus(getApplication())
        }
    }

    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            recentlyDeletedNote = note
            repository.deleteNote(note)
        }
    }

    fun undoDelete() {
        val noteToRestore = recentlyDeletedNote ?: return
        viewModelScope.launch {
            repository.insertNote(noteToRestore)
            recentlyDeletedNote = null
        }
    }

    fun updateNoteCategory(note: NoteEntity, newCategory: String) {
        viewModelScope.launch {
            repository.updateNote(note.copy(category = newCategory))
        }
    }

    fun updateNote(note: NoteEntity) {
        viewModelScope.launch {
            repository.updateNote(note)
        }
    }

    fun addManualNote(
        rawContent: String,
        customCategory: String? = null,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val extracted = MetadataExtractor.extractFromText(rawContent)
            val currentCategoryNames = categories.value.map { it.name }
            val category = customCategory ?: SmolLM2Classifier.categorize(
                getApplication(),
                extracted.title,
                extracted.description,
                currentCategoryNames
            )

            val newNote = NoteEntity(
                contentType = extracted.contentType,
                contentData = extracted.contentData,
                title = extracted.title,
                description = extracted.description,
                category = category,
                timestamp = System.currentTimeMillis()
            )
            repository.insertNote(newNote)
            onComplete()
        }
    }
}
