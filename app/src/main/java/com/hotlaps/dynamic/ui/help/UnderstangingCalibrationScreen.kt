package com.hotlaps.dynamic.ui.help

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UnderstandingCalibrationScreen(
    onBack: () -> Unit
) {
    HelpScaffold(
        title = "Understanding Calibration",
        onBack = onBack
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            item {
                Text(
                    text = "Understanding Calibration",
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            item {
                Text(
                    text = """
Why Calibration Matters
-----------------------
Your phone measures acceleration using tiny sensors inside the device. To turn that
raw sensor data into accurate G-force readings, the app needs to know:
• Which direction inside the phone points forward in the car
• How to separate true acceleration from gravity
• Whether your device provides the high-quality Linear Acceleration sensor or if we
  must fall back to the basic accelerometer

The calibration step teaches the app how your phone is mounted so your G-force map
and corner data are accurate during driving.
""".trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Text(
                    text = """
How Calibration Works
---------------------
When you press Start Calibration, the app collects a few hundred samples while you
accelerate smoothly in a straight line. Your phone then estimates a "forward
acceleration vector":

• This tells the app: “When the car accelerates forward, what direction does that
  show up inside the phone?”

At the same time, the app checks which motion sensors your device supports.
""".trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Text(
                    text = """
The Two Sensor Modes
--------------------
1) Linear Acceleration (Recommended)
   Many modern phones (including Google Pixel devices) provide a Linear Acceleration
   sensor. This fused sensor subtracts gravity automatically and gives extremely clean
   results.

2) Accelerometer Fallback
   If your device does not have Linear Acceleration, the app uses the raw
   accelerometer and estimates gravity using a slow filter. This still works reliably
   but may have a bit more noise or drift, especially on rough pavement.
""".trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Text(
                    text = """
What You Need to Do
-------------------
1) Mount the device securely in the orientation you will use while driving.
2) Find a safe, straight road or paddock area.
3) Press Start Calibration.
4) Accelerate smoothly for about 3–4 seconds.
5) When the status shows “Calibrated ✓”, press Use Calibration.

You only need to recalibrate if the mounting orientation changes.
""".trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Text(
                    text = """
If Calibration Fails
--------------------
Calibration can struggle if:
• The phone moves or wiggles during sampling
• The road surface is very rough
• The acceleration run is too short
• The device has very low-quality motion sensors

Try again with:
• A more secure mount
• A smoother section of road
• A slightly longer acceleration run
""".trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Text(
                    text = """
Which Phones Work Best?
-----------------------
All Google Pixel devices include excellent motion sensors and typically deliver
very accurate results. Most mid-range and flagship Android phones behave similarly.

Very low-end or older phones may:
• Lack the Linear Acceleration sensor
• Produce more noise
• Still work, but with slightly less smooth plots

The app automatically detects and adapts to the sensor quality.
""".trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Text(
                    text = """
After Calibration
-----------------
Once calibration is complete, you can:
• View a clean, accurate live G-G plot
• Log and review events by track and corner
• Export consistent CSV data for deeper analysis

Calibration does not need to be repeated frequently — only when the device mounting
changes significantly.
""".trimIndent(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
