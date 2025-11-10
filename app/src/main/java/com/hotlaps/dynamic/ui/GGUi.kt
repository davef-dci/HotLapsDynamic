// GGUi.kt — Step 3: single file that holds the screen AND the drawing.
// For now, we use placeholders. Next steps will:
//  • Hook to Settings values
//  • Wire “Go!” to navigate here
//  • Feed real sensor data + trail

package com.hotlaps.dynamic.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// For reading Settings the same way SettingsScreen does
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.hotlaps.dynamic.data.SettingsRepo


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GGScreen( // <- this is the screen you’ll navigate to from “Go!”
    modifier: Modifier = Modifier
) {
    // === Read Settings (same pattern as SettingsScreen) ===
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepo(context) }

    // Collect the three settings we need
    val ggMaxG        by repo.ggMaxG.collectAsStateWithLifecycle(initialValue = 1.25f)
    val ggTrailWindow by repo.ggTrailWindowS.collectAsStateWithLifecycle(initialValue = 3.0f)
    val trailBrakeG   by repo.trailBrakeG.collectAsStateWithLifecycle(initialValue = 0.30f)

    // === Sensor placeholders for now (next step we’ll wire real accel) ===
    val latG = 0.0f
    val longG = 0.0f
    val ticks = 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("G-G") }
            )
        }
    ) { inner ->
        Column(
            modifier = modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {

            // --- Debug: show the settings that GGPlot is using ---
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("ggMaxG: ${"%.2f".format(ggMaxG)}")
                    Text("ggTrailWindow: ${"%.1f".format(ggTrailWindow)} s")
                    Text("trailBrakeG: ${"%.2f".format(trailBrakeG)}")
                }
            }


            GGPlot(
                maxAbsG = ggMaxG,
                latG = latG,
                longG = longG,
                trailSeconds = ggTrailWindow,
                ticks = ticks,
                brakeThreshG = trailBrakeG,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

        }
    }
}

/**
 * GGPlot = the pure drawing widget.
 * Lives in the same file for simplicity, but has no nav knowledge.
 */
@Composable
private fun GGPlot(
    maxAbsG: Float,
    latG: Float,
    longG: Float,
    trailSeconds: Float,
    ticks: Long,
    brakeThreshG: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f) // square
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.48f

        // Outer circle
        drawCircle(
            color = Color(0xFFB0B0B0),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 3f)
        )

        // Crosshair axes
        drawLine(
            color = Color(0xFFB0B0B0),
            start = Offset(cx - radius, cy),
            end = Offset(cx + radius, cy),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFFB0B0B0),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius),
            strokeWidth = 2f
        )
    }
}
