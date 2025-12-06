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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun HelpGForceMapScreen(
    onBack: () -> Unit
) {
    HelpScaffold(
        title = "G-Force Map",
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
                text = "Dynamic G-Force Map",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(12.dp))

            val body = """
                The Dynamic G-Force Map shows how your car is using grip in real time. It plots lateral (left/right) and longitudinal (accel/brake) G-forces on a circular graph.

                AXES
                • Vertical axis: Up = acceleration, Down = braking
                • Horizontal axis: Right = turning right, Left = turning left

                MOVING DOT & TRAIL
                • The large dot shows your current combined G-force.
                • The fading trail shows the last few seconds of G history.
                • Green segments indicate acceleration, red segments indicate braking.
                • The trail length is controlled by the G-Force Trail Window setting.

                WEDGE HIGHLIGHTS
                • Green-cyan wedges: throttle steering (accelerating while turning).
                • Blue wedges: trail braking (braking while turning).
                • Narrow wedges highlight “pure” directions like straight braking or pure left/right cornering.

                PEAK MARKERS
                • Short ticks show the recent peak G-forces:
                  – Green: max acceleration
                  – Red: max braking
                  – Orange: max right cornering
                  – Violet: max left cornering

                SCALE CONTROL
                • Auto scale: the circle grows to fit the highest G-forces reached.
                • Fixed scales (0.25G–2.0G): useful when comparing sessions or cars.

                TRACK & EVENT
                • Select a track so the app can group data by corner.
                • Use Record / Pause / Stop to log sessions as Events for later review.

                CALIBRATION
                • If the phone is not calibrated, an overlay prompts you to run Calibration.
                • Calibration aligns “forward” and “brake” with the car’s direction of travel.

                In short, the G-Force Map is your real-time view into braking, corner entry, trail braking, throttle pickup, and overall grip usage.
            """.trimIndent()

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
