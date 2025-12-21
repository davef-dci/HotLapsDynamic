package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.ui.components.BigButton


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CornerCaptureManagerScreen(
    onBack: () -> Unit,
    onTrackAndCornerSetup: () -> Unit,
    onCaptures: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Corner Capture Manager") },
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
            Spacer(Modifier.height(16.dp))
            Text(
                text = "What would you like to manage?",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(24.dp))
            BigButton(
                text = "Track & Corner Setup",
                onClick = onTrackAndCornerSetup
            )

            Spacer(Modifier.height(16.dp))
            BigButton(
                text = "Captures",
                onClick = onCaptures
            )
        }
    }
}
