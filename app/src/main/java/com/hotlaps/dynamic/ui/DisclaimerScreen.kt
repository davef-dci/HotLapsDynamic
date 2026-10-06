package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Short enough that the checkbox and Continue fit on one screen even with a large system font
 * (still scrolls if it doesn't).
 */
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
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Safety Disclaimer",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Provided as is, with no warranty of accuracy or results. Use at your own risk.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Do not operate this app while driving. Set it up before you drive, or have a " +
                        "passenger or coach use it. Keep your attention on driving and obey all traffic laws.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { isChecked = it }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "I agree, and I will not operate this app while driving.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

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
