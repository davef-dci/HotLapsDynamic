package com.hotlaps.dynamic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hotlaps.dynamic.data.EventStorage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import com.hotlaps.dynamic.model.EventSample
import java.io.File

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color // May or may not be used depending on theme
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material3.Checkbox


import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.max

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.max

import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas

import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventViewerScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {

            // --- NEW: Load event files from storage ---
            val context = LocalContext.current
            val eventFiles = remember { EventStorage.listEventFiles(context) }
            // --- NEW: which event file the user has selected (if any) ---
            var selectedFile by remember { mutableStateOf<File?>(null) }

            // --- NEW: Load samples for the currently selected file (or empty if none selected) ---
            val samplesForSelected = remember(selectedFile) {
                selectedFile?.let { file ->
                    EventStorage.loadSamplesFromCsv(file)
                } ?: emptyList()
            }
            // --- NEW: Detect distinct (cornerIndex, visitNumber) groups in the selected event ---
            val cornerVisitGroups = remember(samplesForSelected) {
                samplesForSelected
                    // Only keep samples that are actually tagged to a corner visit
                    .filter { it.cornerIndex > 0 && it.visitNumber > 0 }
                    // Group by (cornerIndex, visitNumber)
                    .groupBy { it.cornerIndex to it.visitNumber }
                    // We only need the unique keys (the groups themselves)
                    .keys
                    // Sort nicely: by corner, then by visit number
                    .sortedWith(
                        compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }
                    )
            }

            // --- NEW: Which (cornerIndex, visitNumber) groups are selected for plotting ---
            var selectedCornerVisits by remember(cornerVisitGroups) {
                // By default, select all corner/visit groups when they first appear
                mutableStateOf(cornerVisitGroups.toSet())
            }

            // --- NEW: Assign a distinct color to each selected (corner, visit) group ---
            // --- NEW: Assign a distinct color to each (corner, visit) group ---
// Palette based on the current MaterialTheme – this is fine directly in a composable
            val palette = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.error,
                MaterialTheme.colorScheme.inversePrimary
            )

// Small map; cheap to recompute on recomposition
            val cornerVisitColors = cornerVisitGroups
                .mapIndexed { index, groupKey ->
                    val baseColor = palette[index % palette.size]
                    groupKey to baseColor.copy(alpha = 0.9f)
                }
                .toMap()



            // --- NEW: Display simple status message ---
            Text("Found ${eventFiles.size} event file(s)")

            // --- NEW: Show how many samples are in the selected event (if any) ---
            Text(
                text = if (selectedFile == null) {
                    "No event selected"
                } else {
                    "Selected event has ${samplesForSelected.size} sample(s)"
                },
                style = MaterialTheme.typography.bodySmall
            )




            Spacer(Modifier.height(16.dp))

            // --- NEW: Show each event file name in a simple vertical list ---
            // --- FILE LIST ---
// Simple column because the outer layout is already scrollable
            Column {
                eventFiles.forEach { file ->
                    val isSelected = (file == selectedFile)

                    Text(
                        text = if (isSelected) "▶ ${file.name}" else file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Update which file is selected when tapped
                                selectedFile = file
                            }
                            .padding(vertical = 4.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }


            Spacer(Modifier.height(24.dp))

            // --- CORNER/VISIT SUMMARY FOR SELECTED EVENT ---
            if (selectedFile != null) {
                if (cornerVisitGroups.isEmpty()) {
                    Text(
                        text = "This event has no corner-tagged samples.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "Corner visits found (toggle to include in plot):",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(4.dp))

                    cornerVisitGroups.forEach { (cornerIdx, visitNum) ->
                        val groupKey = cornerIdx to visitNum
                        val isChecked = selectedCornerVisits.contains(groupKey)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Toggle selection when row is tapped
                                    selectedCornerVisits =
                                        if (isChecked) {
                                            selectedCornerVisits - groupKey
                                        } else {
                                            selectedCornerVisits + groupKey
                                        }
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    // Toggle selection when checkbox itself is tapped
                                    selectedCornerVisits =
                                        if (checked) {
                                            selectedCornerVisits + groupKey
                                        } else {
                                            selectedCornerVisits - groupKey
                                        }
                                }
                            )

                            Text(
                                text = "Corner $cornerIdx – Visit $visitNum",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }


            // --- NEW: Filter samples based on selected corner/visit groups ---
            val samplesForPlot =
                if (cornerVisitGroups.isNotEmpty() && selectedCornerVisits.isNotEmpty()) {
                    val filtered = samplesForSelected.filter { sample ->
                        // Only keep samples whose (cornerIndex, visitNumber) is selected
                        selectedCornerVisits.contains(sample.cornerIndex to sample.visitNumber)
                    }

                    // If filtering somehow yields nothing, fall back to all samples
                    if (filtered.isNotEmpty()) filtered else samplesForSelected
                } else {
                    // If there are no corner groups, or none selected, just plot everything
                    samplesForSelected
                }


// Neutral background color for non-corner samples
            val neutralSampleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)


// --- G-G PLOT FOR SELECTED EVENT ---
            // --- G-G PLOT FOR SELECTED EVENT ---
            if (selectedFile != null && samplesForPlot.isNotEmpty()) {
                Text(
                    text = "G-G Plot (selected corner visits, color-coded):",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))

                SimpleGGPlot(
                    samples = samplesForPlot,
                    colorForSample = { sample ->
                        val key = sample.cornerIndex to sample.visitNumber

                        // If this sample belongs to a corner visit group, use its color
                        cornerVisitColors[key]
                        // Otherwise, use a neutral faint color for "background"/non-corner samples
                            ?: neutralSampleColor
                    }
                )


                // --- MAX G SUMMARY SECTION ---
                Spacer(Modifier.height(24.dp))

                if (samplesForSelected.isNotEmpty()) {

                    Text(
                        text = "Max G summary",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    // If there are NO corner-tagged samples, show a single overall row
                    if (cornerVisitGroups.isEmpty()) {
                        val overallSummary = remember(samplesForSelected) {
                            computeMaxGSummary(samplesForSelected)
                        }

                        MaxGSummaryHeaderRow()
                        Spacer(Modifier.height(4.dp))
                        MaxGSummaryRow(label = "Event", summary = overallSummary)
                    } else {
                        // If we DO have corner-tagged samples, show one row per visit
                        Text(
                            text = "Per visit (across all corners):",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))

                        MaxGSummaryHeaderRow()
                        Spacer(Modifier.height(4.dp))

                        // Group tagged samples by visit number
                        val samplesByVisit = remember(samplesForSelected) {
                            samplesForSelected
                                .filter { it.cornerIndex > 0 && it.visitNumber > 0 }
                                .groupBy { it.visitNumber }
                                .toSortedMap()
                        }

                        samplesByVisit.forEach { (visitNum, visitSamples) ->
                            val summary = computeMaxGSummary(visitSamples)
                            MaxGSummaryRow(
                                label = "Visit $visitNum",
                                summary = summary
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }



            }






        }

    }
}

// --- Very simple GG plot placeholder ---
// Now takes a list of EventSample so we can use latG/longG soon.
// --- Very simple GG plot with axes ---
// Takes a list of EventSample so we can use their latG/longG later.
// --- Very simple GG plot with axes ---
// Takes a list of EventSample so we can use their latG/longG later.
// --- UPDATED: SimpleGGPlot now supports per-sample colors via a callback ---
@Composable
fun SimpleGGPlot(
    samples: List<EventSample>,
    // function that decides the color for each sample
    colorForSample: (EventSample) -> Color
) {
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    val density = LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }
    val labelGapPx = with(density) { 4.dp.toPx() }
    val androidAxisColor = axisColor.toArgb()

    // Paint used to draw numeric labels for the G rings
    val labelPaint = remember(labelTextSizePx, androidAxisColor) {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = labelTextSizePx
            color = androidAxisColor
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }


    // --- Auto-scale based on max |G| across both axes ---
    val rawMaxG = samples.maxOfOrNull { sample ->
        max(abs(sample.latG), abs(sample.longG))
    } ?: 0f

    val maxG = when {
        rawMaxG <= 0f -> 0.5f           // default if everything is zero
        rawMaxG < 0.5f -> 0.5f          // snap tiny values to at least ±0.5 G
        else -> rawMaxG * 1.1f          // small headroom
    }

    // --- Gesture state: zoom + pan ---
    var userScale by remember { mutableStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(8.dp)
            .pointerInput(Unit) {
                // Pinch to zoom + drag to pan
                detectTransformGestures { _, pan, zoom, _ ->
                    // Update scale
                    val newScale = (userScale * zoom).coerceIn(0.5f, 3f)
                    userScale = newScale

                    // Update pan
                    userOffset = userOffset + pan
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val center = Offset(cx, cy)
            val radius = size.minDimension * 0.48f

            // Helper: clamp a point to the circle rim if needed
            fun clampToCircle(xIn: Float, yIn: Float): Offset {
                var x = xIn
                var y = yIn
                val dx = x - cx
                val dy = y - cy
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist > radius && dist > 0f) {
                    val s = radius / dist
                    x = cx + dx * s
                    y = cy + dy * s
                }
                return Offset(x, y)
            }

            // Apply pan + zoom to everything we draw
            withTransform({
                // pan in screen space
                translate(userOffset.x, userOffset.y)
                // zoom around the center of the plot
                scale(userScale, userScale, pivot = center)
            }) {
                // --- Base circular grid (similar feel to GGUi) ---

                // Outer circle
                drawCircle(
                    color = axisColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Crosshair axes
                drawLine(
                    color = axisColor,
                    start = Offset(cx - radius, cy),
                    end = Offset(cx + radius, cy),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = axisColor,
                    start = Offset(cx, cy - radius),
                    end = Offset(cx, cy + radius),
                    strokeWidth = 1.dp.toPx()
                )

                // Tick rings every 0.5 G up to maxG, with labels like "0.5G", "1.0G", etc.
                val tickStep = 0.25f
                var tick = tickStep
                val nativeCanvas = drawContext.canvas.nativeCanvas

                while (tick < maxG + 1e-3f) {
                    val r = radius * (tick / maxG)

                    // Draw the ring
                    drawCircle(
                        color = axisColor.copy(alpha = 0.25f),
                        radius = r,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    // Draw the numeric label just outside the ring on the +X axis
                    val label = String.format("%.2fG", tick)
                    nativeCanvas.drawText(
                        label,
                        cx + r + labelGapPx,   // a little to the right of the ring
                        cy - labelGapPx,       // slightly above centerline
                        labelPaint
                    )

                    tick += tickStep
                }


                // --- Plot samples as connected path + dots ---
                if (samples.isNotEmpty()) {
                    val scalePerG = (radius * 0.95f) / maxG
                    var last: Offset? = null

                    samples.forEach { sample ->
                        val lat = sample.latG
                        val lon = sample.longG

                        val px = cx + lat * scalePerG
                        val py = cy - lon * scalePerG
                        val clamped = clampToCircle(px, py)

                        val color = colorForSample(sample)

                        // Line from previous sample
                        last?.let { prev ->
                            drawLine(
                                color = color,
                                start = prev,
                                end = clamped,
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // Dot
                        drawCircle(
                            color = color,
                            radius = 1.dp.toPx(),
                            center = clamped
                        )

                        last = clamped
                    }
                }
            }
        }

        // --- Axis labels (do NOT zoom/pan; stay anchored to edges) ---
        Text(
            text = "Accel",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
        )
        Text(
            text = "Brake",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
        )
        Text(
            text = "Left",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 2.dp)
        )
        Text(
            text = "Right",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
        )
    }
}

private data class MaxGSummary(
    val braking: Float,
    val accel: Float,
    val left: Float,
    val right: Float
)

/**
 * Compute max braking/accel/left/right for a set of samples.
 * - Braking  = max(-longG, 0)
 * - Accel    = max(longG, 0)
 * - Left     = max(latG, 0)
 * - Right    = max(-latG, 0)
 */
private fun computeMaxGSummary(samples: List<EventSample>): MaxGSummary {
    var maxBrake = 0f
    var maxAccel = 0f
    var maxLeft = 0f
    var maxRight = 0f

    for (s in samples) {
        // Longitudinal
        if (s.longG < 0f) {
            val brake = -s.longG
            if (brake > maxBrake) maxBrake = brake
        } else {
            if (s.longG > maxAccel) maxAccel = s.longG
        }

        // Lateral
        if (s.latG < 0f) {
            val right = -s.latG
            if (right > maxRight) maxRight = right
        } else {
            if (s.latG > maxLeft) maxLeft = s.latG
        }
    }

    return MaxGSummary(
        braking = maxBrake,
        accel = maxAccel,
        left = maxLeft,
        right = maxRight
    )
}


@Composable
private fun MaxGSummaryHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1.2f)
        )
        Text(
            text = "Braking",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Accel",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Left",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Right",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MaxGSummaryRow(
    label: String,
    summary: MaxGSummary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.2f)
        )
        Text(
            text = String.format("%.1fG", summary.braking),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.1fG", summary.accel),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.1fG", summary.left),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.1fG", summary.right),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

