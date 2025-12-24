package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.sendFeedbackEmail
import com.hotlaps.dynamic.ui.help.HelpScaffold
import com.hotlaps.dynamic.BuildConfig


@Composable
fun HelpAboutScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    HelpScaffold(
        title = "About Apex Dynamics",

        onBack = onBack


    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "About Apex Dynamics",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(12.dp))


            Spacer(Modifier.height(12.dp))

            Text(
                text = """
Apex Dynamics is a precision motorsports telemetry tool that helps drivers understand performance using G-force, GPS, and corner-based analysis. It is designed for autocross, HPDE, track days, karting, and club racing.

KEY FEATURES
• Dynamic G-Force Map with live G-circle visualization
• Lateral & longitudinal G-force vs. time charts
• Automatic corner detection
• Apex alignment for lap-to-lap comparison
• Track creation & corner management
• CSV export for coaching or advanced analysis

Apex Dynamics provides clear, data-driven insight into braking, corner entry, trail braking, acceleration, and apex execution—helping drivers improve consistency and speed.

No external hardware required. For safety, do not interact with the app while driving.
""".trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    context.sendFeedbackEmail(currentScreen = "Help & About")
                }
            ) {
                Text("Send Feedback")
            }
        }
    }
}
