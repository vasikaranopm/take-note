package com.onestop.takenotes

import android.app.Application
import com.onestop.takenotes.ai.SmolLM2Classifier
import com.onestop.takenotes.data.AppDatabase
import com.onestop.takenotes.data.CategoryRepository
import com.onestop.takenotes.data.NoteRepository

class TakeNotesApplication : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { NoteRepository(database.noteDao()) }
    val categoryRepository by lazy { CategoryRepository(database.categoryDao()) }

    override fun onCreate() {
        super.onCreate()
        SmolLM2Classifier.startStartupDownload(this)
    }
}

