package com.onestop.takenotes

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onestop.takenotes.ui.capture.CaptureScreen
import com.onestop.takenotes.ui.capture.CaptureViewModel
import com.onestop.takenotes.ui.theme.TakeNotesTheme

class CaptureActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIncomingIntent(intent)

        setContent {
            TakeNotesTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val categories by viewModel.categories.collectAsStateWithLifecycle()

                CaptureScreen(
                    state = state,
                    availableCategories = categories,
                    onTitleChange = { viewModel.updateTitle(it) },
                    onDescriptionChange = { viewModel.updateDescription(it) },
                    onCategorySelect = { viewModel.selectCategory(it) },
                    onAddCustomCategory = { viewModel.addCustomCategory(it) },
                    onSave = {
                        viewModel.saveToNotes {
                            Toast.makeText(this, "Saved to TakeNotes!", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    },
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("text/")) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                viewModel.processSharedContent(sharedText, null)
            } else if (type.startsWith("image/")) {
                val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data ?: intent.clipData?.getItemAt(0)?.uri

                viewModel.processSharedContent(null, imageUri)
            } else {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                viewModel.processSharedContent(sharedText, null)
            }
        } else {
            // Direct launch or test
            val testText = intent.getStringExtra("test_content") ?: "https://en.wikipedia.org/wiki/Artificial_intelligence"
            viewModel.processSharedContent(testText, null)
        }
    }
}
