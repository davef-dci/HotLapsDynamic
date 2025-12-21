package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.ui.components.BigButton


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackAndCornerSetupScreen(
    onBack: () -> Unit,
    onAddNewTrack: () -> Unit,
    onManageTracks: () -> Unit,   // For now this will be used for both Edit + Delete flows
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track Manager") },
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

            // 1) Add new track
            BigButton(
                text = "Add New Track",
                onClick = onAddNewTrack
            )

            Spacer(Modifier.height(12.dp))

            // 2) Edit existing track
            // For now this still goes to the existing TrackManagerScreen,
            // which shows the list of tracks with Edit/Delete per row.
            BigButton(
                text = "Edit Existing Track",
                onClick = onManageTracks
            )

            Spacer(Modifier.height(12.dp))

            // 3) Delete track
            // At the moment this shares the same destination as Edit Existing Track.
            // Later, we can give Edit and Delete distinct flows if you want.
            BigButton(
                text = "Delete Track",
                onClick = onManageTracks
            )
        }
    }
}
