package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DisclaimerScreen(
    onAccept: () -> Unit
) {
    var isChecked by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: title + text
            Column {
                Text(
                    text = "Safety Disclaimer",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "This app is provided as is with no warranty or " +
                            "guarantee of performance, accuracy, or results. " +
                            "Use at your own risk."
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "This app is NOT intended to be used by the person " +
                            "driving the vehicle. It should only be used by a " +
                            "coach or passenger. Do not operate or interact with " +
                            "this app while driving. Always keep your full " +
                            "attention on driving and obey all traffic laws."
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "By checking the box below and tapping “Continue,” " +
                            "you acknowledge that you understand and accept " +
                            "these terms."
                )
            }

            // Bottom: checkbox + button
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { isChecked = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "I understand and agree to the disclaimer above,\n" +
                                "and I will not use this app while driving."
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onAccept,
                    enabled = isChecked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue")
                }
            }
        }
    }
}
