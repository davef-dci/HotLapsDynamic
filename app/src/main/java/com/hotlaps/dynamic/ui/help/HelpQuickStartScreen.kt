package com.hotlaps.dynamic.ui.help

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
fun HelpQuickStartScreen(
    onBack: () -> Unit
) {
    HelpScaffold(
        title = "Quick Start Guide",
        onBack = onBack
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {

            Text(
                text = "Quick Start Guide",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(12.dp))

            val quickStartText = """
                1. Calibrate:
                   • From the drawer, open Calibrate.
                   • Follow the on-screen steps to set a clean zero for G-forces.

                2. Track (optional if you want to record and review data for corners):
                   • From the main menu or drawer, open Track Setup.
                   • Choose an existing track or create a new one.

                3. Record (optional if you want to log a specific driving event):
                   • Press the “Record” button from the G-Force Map.
                   • Dynamic G-Force Map will automatically create an Event.
                   • Press “Stop” to stop recording and save Event data.

                4. Start Driving:
                   • Go to Drive (G-Force map) and begin your session.
                   • The G-G plot will update live while data is recorded.

                5. Review Events:
                   • Open Event Manager to view recorded events.
                   • You can inspect individual sessions and see the G-G plot.

                6. Export Data:
                   • From Event Manager, export events to CSV for analysis in Excel or other tools.

                Caution: For safety, the app is intended for use by a passenger or coach,
                not the driver.
            """.trimIndent()

            Text(
                text = quickStartText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
