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
    onBack: () -> Unit,
    onAddNewTrack: () -> Unit,
    onSelectExistingTrack: () -> Unit,  // NEW
    onDeleteTrack: () -> Unit,   // NEW
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track Manager") },
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
                text = "Tracks",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))

            BigButton(
                text = "Select Existing Track",
                onClick = onSelectExistingTrack
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Add New Track",
                onClick = onAddNewTrack
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Edit Track",
                onClick = {},
                enabled = false
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Delete Track",
                onClick = onDeleteTrack
            )
        }
    }
}
