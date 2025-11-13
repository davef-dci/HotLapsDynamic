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
fun EventManagerScreen(
    onBack: () -> Unit
) {
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
                onClick = { /* TODO: list events */ }
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Share Events",
                onClick = { /* TODO: share CSV or similar */ }
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Delete Events",
                onClick = { /* TODO: delete flow */ }
            )

            Spacer(Modifier.height(12.dp))

            BigButton(
                text = "Compare Events",
                onClick = { /* TODO: compare later */ }
            )
        }
    }
}
