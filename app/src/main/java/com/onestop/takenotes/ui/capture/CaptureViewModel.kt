package com.onestop.takenotes.ui.capture

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onestop.takenotes.TakeNotesApplication
import com.onestop.takenotes.ai.SmolLM2Classifier
import com.onestop.takenotes.data.CategoryEntity
import com.onestop.takenotes.data.NoteEntity
import com.onestop.takenotes.extraction.MetadataExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CaptureUiState {
    object Idle : CaptureUiState
    data class Loading(val stageMessage: String = "Extracting metadata...") : CaptureUiState
    data class Success(
        val contentType: String,
        val contentData: String,
        val title: String,
        val description: String,
        val imageUrl: String? = null,
        val imageUri: Uri? = null,
        val aiSuggestedCategory: String,
        val selectedCategory: String,
        val extraMetadata: Map<String, String> = emptyMap(),
        val isSaved: Boolean = false
    ) : CaptureUiState
    data class Error(val message: String) : CaptureUiState
}

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as TakeNotesApplication).repository
    private val categoryRepository = (application as TakeNotesApplication).categoryRepository

    val categories: StateFlow<List<com.onestop.takenotes.data.CategoryEntity>> = categoryRepository.allCategories
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Idle)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            categoryRepository.ensureDefaultCategories()
        }
    }

    fun processSharedContent(text: String?, imageUri: Uri?) {
        viewModelScope.launch {
            _uiState.value = CaptureUiState.Loading("Extracting content metadata...")
            categoryRepository.ensureDefaultCategories()

            try {
                val availableCategoryNames = categories.value.map { it.name }
                if (imageUri != null) {
                    val extracted = MetadataExtractor.extractFromImage(getApplication(), imageUri)
                    _uiState.value = CaptureUiState.Loading("Running AI categorization...")
                    val aiCategory = SmolLM2Classifier.categorize(
                        getApplication(),
                        extracted.title,
                        extracted.description,
                        availableCategoryNames
                    )

                    _uiState.value = CaptureUiState.Success(
                        contentType = extracted.contentType,
                        contentData = extracted.contentData,
                        title = extracted.title,
                        description = extracted.description,
                        imageUri = imageUri,
                        aiSuggestedCategory = aiCategory,
                        selectedCategory = aiCategory,
                        extraMetadata = extracted.extraMetadata
                    )
                } else if (!text.isNullOrBlank()) {
                    val extracted = MetadataExtractor.extractFromText(text)
                    _uiState.value = CaptureUiState.Loading("Running AI categorization...")
                    val aiCategory = SmolLM2Classifier.categorize(
                        getApplication(),
                        extracted.title,
                        extracted.description,
                        availableCategoryNames
                    )

                    _uiState.value = CaptureUiState.Success(
                        contentType = extracted.contentType,
                        contentData = extracted.contentData,
                        title = extracted.title,
                        description = extracted.description,
                        imageUrl = extracted.imageUrl,
                        aiSuggestedCategory = aiCategory,
                        selectedCategory = aiCategory,
                        extraMetadata = extracted.extraMetadata
                    )
                } else {
                    _uiState.value = CaptureUiState.Error("No content was shared to TakeNotes.")
                }
            } catch (e: Exception) {
                _uiState.value = CaptureUiState.Error("Error processing shared item: ${e.localizedMessage}")
            }
        }
    }

    fun addCustomCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val clean = name.trim()
            categoryRepository.addCategory(clean)
            selectCategory(clean)
        }
    }

    fun updateTitle(newTitle: String) {
        _uiState.update { current ->
            if (current is CaptureUiState.Success) {
                current.copy(title = newTitle)
            } else current
        }
    }

    fun updateDescription(newDescription: String) {
        _uiState.update { current ->
            if (current is CaptureUiState.Success) {
                current.copy(description = newDescription)
            } else current
        }
    }

    fun selectCategory(category: String) {
        _uiState.update { current ->
            if (current is CaptureUiState.Success) {
                current.copy(selectedCategory = category)
            } else current
        }
    }

    fun saveToNotes(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state !is CaptureUiState.Success) return

        viewModelScope.launch {
            val note = NoteEntity(
                contentType = state.contentType,
                contentData = state.contentData,
                title = state.title.ifBlank { "Untitled ${state.contentType}" },
                description = state.description,
                category = state.selectedCategory,
                timestamp = System.currentTimeMillis()
            )
            repository.insertNote(note)
            _uiState.update {
                if (it is CaptureUiState.Success) it.copy(isSaved = true) else it
            }
            onSuccess()
        }
    }
}
