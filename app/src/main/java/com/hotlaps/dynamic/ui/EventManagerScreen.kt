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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventManagerScreen(
    onBack: () -> Unit
) {

    val context = LocalContext.current
    var statusMessage by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Manager") },
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
                text = "Events",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))

            BigButton(
                text = "View Events",
                onClick = {},
                enabled = false
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Export Events to Download",
                onClick = {
                    val copied = EventStorage.exportAllEventsToPublicDownloads(context)
                    statusMessage = "Exported $copied event file(s) to Download/HotLapsDynamic/events"
                },
                enabled = true
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Delete Events",
                onClick = {
                    val deleted = EventStorage.deleteAllEvents(context)
                    statusMessage = "Deleted $deleted event file(s)"
                },
                enabled = true
            )


            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Compare Events",
                onClick = {},
                enabled = false
            )

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
