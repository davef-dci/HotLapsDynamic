package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackAndCornerSetupScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track & Corner Setup") },
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
            // Tracks section
            Text(
                text = "Tracks",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))

            BigButton("Select Track", onClick = { /* TODO: hook up later */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Add New Track", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Import Track File", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Create From Coordinates", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Teach Corners on Track", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Edit Track", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Delete Track", onClick = { /* TODO */ })

            Spacer(Modifier.height(32.dp))

            // Corners section
            Text(
                text = "Corners (for selected track)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))

            BigButton("Add Corner", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Edit Corner", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Delete Corner", onClick = { /* TODO */ })
            Spacer(Modifier.height(8.dp))
            BigButton("Re-teach Corner (while driving)", onClick = { /* TODO */ })
        }
    }
}
