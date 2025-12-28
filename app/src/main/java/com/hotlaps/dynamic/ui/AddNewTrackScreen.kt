package com.hotlaps.dynamic.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.data.TrackStorage
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNewTrackScreen(
    onBack: () -> Unit,
    onCreateFromCoordinates: () -> Unit,
    onTeachCorners: () -> Unit
) {




    val context = LocalContext.current
    val importResultState = remember { mutableStateOf<TrackStorage.ImportResult?>(null) }

    val exportResultState = remember { mutableStateOf<String?>(null) }

    val exportCsvTemplateLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv")
        ) { uri: Uri? ->
            if (uri != null) {
                val ok = TrackStorage.exportTrackCsvTemplateToUri(context, uri)
                exportResultState.value =
                    if (ok) "CSV template exported"
                    else "CSV export failed"
            } else {
                exportResultState.value = "Export cancelled"
            }
        }



    val importTrackLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                importResultState.value = TrackStorage.importSingleTrackFromUri(context, uri)
            } else {
                importResultState.value = TrackStorage.ImportResult(
                    imported = 0,
                    skipped = 0,
                    errors = 0,
                    messages = listOf("Import cancelled")
                )
            }
        }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add New Track") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Choose how to create a new track:",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(24.dp))

            BigButton(
                text = "Create From Coordinates",
                onClick = { onCreateFromCoordinates() }
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Teach Corners While Driving",
                onClick = onTeachCorners,
                enabled = true
            )

            Spacer(Modifier.height(36.dp))

            BigButton(
                text = "Email/Share Track Template…(CSV)",
                onClick = {
                    val uri = TrackStorage.buildShareableCsvTemplateUri(context)
                    if (uri == null) {
                        Toast.makeText(context, "Unable to prepare CSV template", Toast.LENGTH_SHORT).show()
                        return@BigButton
                    }

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "Apex Dynamics Track CSV Template")
                        putExtra(Intent.EXTRA_TEXT, "Track CSV template attached.")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    context.startActivity(Intent.createChooser(intent, "Share CSV Template"))
                }
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Import Track File",
                onClick = {
                    // JSON for now. Later we can allow CSV too.
                    importTrackLauncher.launch(arrayOf("application/json", "text/csv", "text/*", "*/*"))

                },
                enabled = true
            )

            importResultState.value?.let { r ->
                Spacer(Modifier.height(12.dp))
                Text("Imported: ${r.imported}  Errors: ${r.errors}")
                r.messages.take(3).forEach { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(12.dp))


        }
    }





}


