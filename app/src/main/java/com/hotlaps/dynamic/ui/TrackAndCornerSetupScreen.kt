package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackAndCornerSetupScreen(
    onBack: () -> Unit,
    onAddNewTrack: () -> Unit,
    onManageTracks: () -> Unit,   // <- rename / consolidate
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

            BigButton(
                text = "Manage Tracks (Select / Edit / Delete)",
                onClick = onManageTracks
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Add New Track",
                onClick = onAddNewTrack
            )
        }
    }
}

