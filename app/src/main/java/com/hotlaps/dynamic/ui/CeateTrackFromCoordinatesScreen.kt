package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll


// Local UI-only state holder for one corner row in the form.
// We keep everything as strings for now; we'll parse to numbers on Save.
private data class CornerFormState(
    val index: Int,           // 1, 2, 3, ...
    val name: String = "",
    val latText: String = "",
    val lonText: String = "",
    val beforeMsText: String = "2000",
    val afterMsText: String = "1000"
)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTrackFromCoordinatesScreen(
    onBack: () -> Unit
) {
    // --- Local UI state for this screen ---

    // Track-level
    var trackName by remember { mutableStateOf("") }

// List of corner form rows; start with Corner 1.
    val cornerForms = remember {
        mutableStateListOf(
            CornerFormState(index = 1)
        )
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create From Coordinates") },
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
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {

        Text(
                text = "Create Track From Coordinates",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(16.dp))

            // --- Track name entry ---
            OutlinedTextField(
                value = trackName,
                onValueChange = { trackName = it },
                label = { Text("Track Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            Spacer(Modifier.height(24.dp))

// --- Corner list ---
            cornerForms.forEachIndexed { idx, cornerForm ->
                val cornerIndex = cornerForm.index

                Text(
                    text = "Corner $cornerIndex (apex)",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(8.dp))

                // Corner name (optional)
                OutlinedTextField(
                    value = cornerForm.name,
                    onValueChange = { newName ->
                        cornerForms[idx] = cornerForm.copy(name = newName)
                    },
                    label = { Text("Corner Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Latitude
                OutlinedTextField(
                    value = cornerForm.latText,
                    onValueChange = { newLat ->
                        cornerForms[idx] = cornerForm.copy(latText = newLat)
                    },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Longitude
                OutlinedTextField(
                    value = cornerForm.lonText,
                    onValueChange = { newLon ->
                        cornerForms[idx] = cornerForm.copy(lonText = newLon)
                    },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Capture window BEFORE apex (ms)
                OutlinedTextField(
                    value = cornerForm.beforeMsText,
                    onValueChange = { newBefore ->
                        cornerForms[idx] = cornerForm.copy(beforeMsText = newBefore)
                    },
                    label = { Text("Capture Window Before Apex (ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Capture window AFTER apex (ms)
                OutlinedTextField(
                    value = cornerForm.afterMsText,
                    onValueChange = { newAfter ->
                        cornerForms[idx] = cornerForm.copy(afterMsText = newAfter)
                    },
                    label = { Text("Capture Window After Apex (ms)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))
            }

// --- Add Corner button ---
            Button(
                onClick = {
                    val nextIndex = cornerForms.size + 1
                    cornerForms.add(CornerFormState(index = nextIndex))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Corner")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Next: We'll add a Save button that turns all of these rows into real Corner objects and a Track.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        }
}
