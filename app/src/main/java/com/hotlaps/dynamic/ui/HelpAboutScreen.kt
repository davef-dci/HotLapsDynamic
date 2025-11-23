package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpAboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // --- About section ---
        Text(
            text = "About HotLaps Dynamic",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "HotLaps Dynamic helps you understand your car's performance " +
                    "by plotting G-forces and organizing data by track and corner.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(24.dp))

        // --- Version info (for now just hard-coded text; we can improve later) ---
        Text(
            text = "Version: 0.1.0",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(32.dp))

        // --- Help section ---
        Text(
            text = "Quick Help",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "• Drive: Select a track, start recording, and watch the G-G plot update in real time.\n" +
                    "• Tracks: Define your tracks and corners so the app can group data by corner.\n" +
                    "• Events: After a session, review and export events as CSV for deeper analysis.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
