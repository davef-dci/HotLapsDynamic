package com.hotlaps.dynamic.ui.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpSettingsScreen(
    onBack: () -> Unit
) {
    HelpScaffold(
        title = "Settings",
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Settings overview",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The Settings screen lets you tune how Apex Dynamics processes and displays your data. " +
                        "These options apply across the app, especially the live G-Force Map and Event Viewer.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            // Smoothing
            Text(
                text = "G-force smoothing",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Controls how much filtering is applied to the raw accelerometer data before it is " +
                        "shown on the G-Force Map and saved to events. Higher smoothing removes noise and " +
                        "makes traces easier to read, but can hide very quick spikes.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Presets:",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "• Off – No smoothing. Shows raw, very responsive data. Best for debugging or advanced analysis.\n" +
                        "• Low – Light filtering. Good starting point if your data looks a bit noisy.\n" +
                        "• Medium – Stronger filtering. Helps stabilize traces on bumpy tracks.\n" +
                        "• Heavy – Maximum smoothing. Best for very rough surfaces or when you mainly care about " +
                        "overall shape instead of tiny details.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(16.dp))

            // G-G trail window
            Text(
                text = "G-G trail window (seconds)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Sets how long the moving “trail” of points stays visible on the Dynamic G-Force Map. " +
                        "This is the time window, in seconds, of recent samples that are drawn behind the live dot.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "• Shorter trail (small number of seconds) – The dot feels more \"live\" and responsive, " +
                        "with just a small tail.\n" +
                        "• Longer trail (larger number of seconds) – You see more history in the circle, which " +
                        "can help visualize longer combined brake/turn/accelerate sequences.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(16.dp))

            // Trail braking threshold
            Text(
                text = "Trail braking threshold (G)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Controls when the app considers you to be “trail braking.” This value is a G-force " +
                        "threshold used when you are braking and turning at the same time.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "• Lower threshold – The app will detect more subtle trail-brake moments. Good if you brake gently.\n" +
                        "• Higher threshold – Only stronger braking while turning will be highlighted. Good if you only " +
                        "want to see the most committed trail-brake zones.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(16.dp))

            // G-G scale setting note (if still present anywhere)
            Text(
                text = "G-G scale behavior",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "The Dynamic G-Force Map can automatically scale based on observed peak Gs, or you can choose " +
                        "a fixed scale from the drop-down on the map itself. Auto-scale is usually best when you are " +
                        "learning a new track or car, and fixed scales are best when you want to compare different runs.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Tips",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "• If your plots look jittery or hard to read, try increasing smoothing one step at a time.\n" +
                        "• If the G-G circle feels too busy, shorten the trail window.\n" +
                        "• If you rarely see trail braking highlighted, lower the trail braking threshold slightly.\n" +
                        "• You can experiment with these settings in the paddock and they take effect immediately.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
