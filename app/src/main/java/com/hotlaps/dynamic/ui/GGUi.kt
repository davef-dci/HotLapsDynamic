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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GGScreen( // <- this is the screen you’ll navigate to from “Go!”
    modifier: Modifier = Modifier
) {
    // Temporary placeholders. We’ll replace with Settings + sensors soon.
    val maxAbsG = 1.0f
    val latG = 0.0f
    val longG = 0.0f
    val trailSeconds = 3.0f
    val ticks = 0L
    val brakeThreshG = 0.15f

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
            GGPlot(
                maxAbsG = maxAbsG,
                latG = latG,
                longG = longG,
                trailSeconds = trailSeconds,
                ticks = ticks,
                brakeThreshG = brakeThreshG,
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
