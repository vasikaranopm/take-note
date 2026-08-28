package com.onestop.takenotes.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.onestop.takenotes.backup.BackupPayload
import com.onestop.takenotes.backup.BackupRestoreManager
import com.onestop.takenotes.backup.RestoreMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreDialog(
    totalNotesCount: Int,
    totalCategoriesCount: Int,
    onDismiss: () -> Unit,
    viewModel: MainViewModel,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    var pendingRestorePayload by remember { mutableStateOf<BackupPayload?>(null) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }

    // Launcher for creating/saving a backup file via Storage Access Framework
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            viewModel.exportBackup(uri) { result ->
                isProcessing = false
                result.fold(
                    onSuccess = { summary ->
                        onShowMessage("Backup saved! Exported ${summary.totalNotes} notes & ${summary.totalCategories} categories.")
                        onDismiss()
                    },
                    onFailure = { err ->
                        onShowMessage("Backup failed: ${err.localizedMessage ?: "Unknown error"}")
                    }
                )
            }
        }
    }

    // Launcher for opening/picking an existing backup JSON file
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            viewModel.parseBackupFile(uri) { result ->
                isProcessing = false
                result.fold(
                    onSuccess = { payload ->
                        pendingRestorePayload = payload
                        showRestoreConfirmDialog = true
                    },
                    onFailure = { err ->
                        onShowMessage("Failed to read backup: ${err.localizedMessage ?: "Invalid file"}")
                    }
                )
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                // Title Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Backup & Restore",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Manage your local data archives",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Status / Overview Banner
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$totalNotesCount",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Saved Notes",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Divider(
                            modifier = Modifier
                                .height(32.dp)
                                .width(1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$totalCategoriesCount",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Categories",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Divider(
                            modifier = Modifier
                                .height(32.dp)
                                .width(1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "JSON",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "Format",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Section 1: Backup / Export
                Text(
                    text = "BACKUP DATA",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val fileName = BackupRestoreManager.generateBackupFileName()
                        createDocumentLauncher.launch(fileName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_backup_button"),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export & Save Backup File")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        isProcessing = true
                        viewModel.getBackupJsonForSharing { json ->
                            isProcessing = false
                            val shareIntent = BackupRestoreManager.createShareBackupIntent(json)
                            context.startActivity(Intent.createChooser(shareIntent, "Share TakeNotes Backup"))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("share_backup_button"),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Backup Content Directly")
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Section 2: Restore / Import
                Text(
                    text = "RESTORE DATA",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                FilledTonalButton(
                    onClick = {
                        openDocumentLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("restore_backup_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import & Restore from File")
                }

                if (isProcessing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Processing database archive...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Confirmation & Mode selection dialog when a valid backup payload is loaded
    if (showRestoreConfirmDialog && pendingRestorePayload != null) {
        val payload = pendingRestorePayload!!
        val dateFormatted = try {
            val df = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            df.format(Date(payload.exportedAt))
        } catch (e: Exception) {
            "Unknown date"
        }

        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose Restore Mode")
                }
            },
            text = {
                Column {
                    Text(
                        text = "Backup archive details:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Notes found: ${payload.notes.size}\n• Categories found: ${payload.categories.size}\n• Created: $dateFormatted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "How would you like to restore this data?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Option 1: MERGE (Safe)
                    Button(
                        onClick = {
                            showRestoreConfirmDialog = false
                            isProcessing = true
                            viewModel.restoreBackupData(payload, RestoreMode.MERGE) { result ->
                                isProcessing = false
                                result.fold(
                                    onSuccess = { res ->
                                        onShowMessage("Merged successfully! Added ${res.restoredNotesCount} notes & ${res.restoredCategoriesCount} categories.")
                                        onDismiss()
                                    },
                                    onFailure = { err ->
                                        onShowMessage("Restore failed: ${err.localizedMessage ?: "Unknown error"}")
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Merge (Keep Existing Data)")
                    }

                    // Option 2: REPLACE (Clean state)
                    OutlinedButton(
                        onClick = {
                            showRestoreConfirmDialog = false
                            isProcessing = true
                            viewModel.restoreBackupData(payload, RestoreMode.REPLACE) { result ->
                                isProcessing = false
                                result.fold(
                                    onSuccess = { res ->
                                        onShowMessage("Restored cleanly! ${res.restoredNotesCount} notes & ${res.restoredCategoriesCount} categories.")
                                        onDismiss()
                                    },
                                    onFailure = { err ->
                                        onShowMessage("Restore failed: ${err.localizedMessage ?: "Unknown error"}")
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Replace (Overwrite All Current)")
                    }

                    TextButton(
                        onClick = { showRestoreConfirmDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }
            },
            dismissButton = null
        )
    }
}
