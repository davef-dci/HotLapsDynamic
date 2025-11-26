package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.hotlaps.dynamic.data.EventStorage
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventManagerScreen(
    onBack: () -> Unit,
    onViewEvents: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {


    val context = LocalContext.current
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var eventFiles by remember {
        mutableStateOf(EventStorage.listEventFiles(context))
    }

    var showDeletePanel by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }

    var showSharePanel by remember { mutableStateOf(false) }
    var selectedShareFiles by remember { mutableStateOf(setOf<File>()) }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Manager") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open menu"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(innerPadding)

                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Events",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))

            BigButton(
                text = "View Events",
                onClick = onViewEvents,
                enabled = true
            )


            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Share Event CSV",
                onClick = {
                    showDeletePanel = false
                    showSharePanel = !showSharePanel

                    eventFiles = EventStorage.listEventFiles(context)
                    selectedShareFiles = emptySet()
                    statusMessage = null
                },
                enabled = true
            )


            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Delete Events",
                onClick = {
                    // Toggle the delete panel and refresh file list
                    showDeletePanel = !showDeletePanel
                    eventFiles = EventStorage.listEventFiles(context)
                    selectedFiles = emptySet()
                    statusMessage = null
                },
                enabled = true
            )

            if (showDeletePanel) {
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Select events to delete:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(8.dp))

                if (eventFiles.isEmpty()) {
                    Text(
                        text = "No event files found.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    // Simple list of checkboxes for each event file
                    Column {
                        eventFiles.forEach { file ->
                            val isChecked = selectedFiles.contains(file)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedFiles =
                                            if (checked) {
                                                selectedFiles + file
                                            } else {
                                                selectedFiles - file
                                            }
                                    }
                                )
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Delete Selected
                        Button(
                            onClick = {
                                val toDelete = selectedFiles.toList()
                                val deleted = EventStorage.deleteEvents(context, toDelete)
                                eventFiles = EventStorage.listEventFiles(context)
                                selectedFiles = emptySet()
                                statusMessage = "Deleted $deleted selected event file(s)"
                            },
                            enabled = selectedFiles.isNotEmpty()
                        ) {
                            Text("Delete Selected")
                        }

                        // Delete All
                        OutlinedButton(
                            onClick = {
                                val deleted = EventStorage.deleteAllEvents(context)
                                eventFiles = emptyList()
                                selectedFiles = emptySet()
                                statusMessage = "Deleted $deleted event file(s)"
                            },
                            enabled = eventFiles.isNotEmpty()
                        ) {
                            Text("Delete All")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }


            if (showSharePanel) {
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Select an event to share:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(8.dp))

                if (eventFiles.isEmpty()) {
                    Text(
                        text = "No event files found.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    // Single-selection list (like radio buttons, but using checkboxes)
                    Column {
                        eventFiles.forEach { file ->
                            val isSelected = selectedShareFiles.contains(file)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedShareFiles =
                                            if (checked) {
                                                selectedShareFiles + file
                                            } else {
                                                selectedShareFiles - file
                                            }
                                    }
                                )
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Share Selected
                        Button(
                            onClick = {
                                if (selectedShareFiles.isNotEmpty()) {
                                    EventStorage.shareMultipleEventCsv(context, selectedShareFiles.toList())
                                    statusMessage = "Sharing ${selectedShareFiles.size} event file(s)"
                                    showSharePanel = false
                                }
                            },
                            enabled = selectedShareFiles.isNotEmpty()
                        ) {
                            Text("Share Selected")
                        }


                        // Cancel
                        OutlinedButton(
                            onClick = {
                                selectedShareFiles = emptySet()
                                showSharePanel = false
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }




            Spacer(Modifier.height(12.dp))

            /*
            BigButton(
                text = "Compare Events",
                onClick = {},
                enabled = false
            )
         */
            Spacer(Modifier.height(24.dp))

            if (statusMessage != null) {
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodySmall
                )
            }



        }
    }
}
