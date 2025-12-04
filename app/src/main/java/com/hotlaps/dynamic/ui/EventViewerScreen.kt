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
import androidx.compose.foundation.background
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
import com.hotlaps.dynamic.ui.ChartMode
import kotlin.math.ceil


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
            // NEW: toggle between G-G and G-vs-Time modes
            var chartMode by remember { mutableStateOf(ChartMode.GG) }

            // --- NEW: Load samples for the currently selected file (or empty if none selected) ---
            val samplesForSelected = remember(selectedFile) {
                selectedFile?.let { file ->
                    EventStorage.loadSamplesFromCsv(file)
                } ?: emptyList()
            }

// Collect one apex sample per (cornerIndex, visitNumber)
            val apexVisits = remember(samplesForSelected) {
                samplesForSelected
                    // Only keep samples marked as apex and tagged to a corner/visit
                    .filter { it.isApexSample && it.cornerIndex > 0 && it.visitNumber > 0 }
                    // Group by (cornerIndex, visitNumber) in case there are duplicates
                    .groupBy { it.cornerIndex to it.visitNumber }
                    .mapNotNull { (cornerVisitKey, samples) ->
                        val apexSample = samples.minByOrNull { it.intervalMs } ?: samples.firstOrNull()
                        if (apexSample == null) {
                            null
                        } else {
                            ApexVisit(
                                cornerIndex = cornerVisitKey.first,
                                visitNumber = cornerVisitKey.second,
                                cornerName = apexSample.cornerName.ifBlank { "Corner ${cornerVisitKey.first}" },
                                apexIntervalMs = apexSample.intervalMs,
                                apexUtcMs = apexSample.utcMs
                            )
                        }
                    }
                    // Sort nicely: by corner, then visit
                    .sortedWith(
                        compareBy<ApexVisit> { it.cornerIndex }.thenBy { it.visitNumber }
                    )
            }


            // --- NEW: Corner/visit groups are driven by apexVisits, not per-sample tags ---
            val cornerVisitGroups = remember(apexVisits) {
                apexVisits
                    .map { it.cornerIndex to it.visitNumber }
                    .distinct()
                    .sortedWith(
                        compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }
                    )
            }


            // --- NEW: Which (cornerIndex, visitNumber) groups are selected for plotting ---
            var selectedCornerVisits by remember(cornerVisitGroups) {
                // By default, select all corner/visit groups when they first appear
                mutableStateOf(cornerVisitGroups.toSet())
            }

// Adjustable time window around the apex (in seconds)
            var beforeApexSeconds by remember { mutableStateOf(3f) }
            var afterApexSeconds  by remember { mutableStateOf(3f) }



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

            Text(
                text = if (selectedFile == null) {
                    ""
                } else {
                    "Detected ${apexVisits.size} apex sample(s) in this event"
                },
                style = MaterialTheme.typography.bodySmall
            )

            // --- NEW: Chart mode toggle ---
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { chartMode = ChartMode.GG },
                    enabled = chartMode != ChartMode.GG
                ) {
                    Text("G-G Plot")
                }

                Button(
                    onClick = { chartMode = ChartMode.G_VS_TIME },
                    enabled = chartMode != ChartMode.G_VS_TIME
                ) {
                    Text("G vs Time")
                }
            }

            Spacer(Modifier.height(12.dp))


            if (selectedFile != null && apexVisits.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Apex summary:",
                    style = MaterialTheme.typography.bodySmall
                )

                apexVisits.forEach { apex ->
                    val seconds = apex.apexIntervalMs / 1000f
                    Text(
                        text = "• Corner ${apex.cornerIndex} – Visit ${apex.visitNumber} " +
                                "(${apex.cornerName}) at ${"%.3f".format(seconds)} s",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }


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
// Map (cornerIndex, visitNumber) -> ApexVisit for quick lookup
            val apexByGroup = remember(apexVisits) {
                apexVisits.associateBy { it.cornerIndex to it.visitNumber }
            }

// Just the apex visits for corner/visits that are currently checked
            val selectedApexesForTime = remember(apexVisits, selectedCornerVisits) {
                apexVisits.filter { selectedCornerVisits.contains(it.cornerIndex to it.visitNumber) }
            }


            // --- Filter samples based on selected corner/visit groups AND apex window ---
// Now based purely on apex times, not on per-sample corner tags.
            val samplesForPlot =
                if (apexVisits.isNotEmpty() && selectedCornerVisits.isNotEmpty()) {
                    val beforeMs = (beforeApexSeconds * 1000f).toLong()
                    val afterMs  = (afterApexSeconds * 1000f).toLong()

                    samplesForSelected.filter { sample ->
                        // Include this sample if it falls within the [−before, +after] window
                        // of ANY *selected* apex visit.
                        apexVisits.any { apex ->
                            val key = apex.cornerIndex to apex.visitNumber
                            if (!selectedCornerVisits.contains(key)) {
                                return@any false
                            }

                            val dt = sample.intervalMs - apex.apexIntervalMs
                            dt >= -beforeMs && dt <= afterMs
                        }
                    }
                } else {
                    // No apexes or none selected → just show the whole event
                    samplesForSelected
                }



            // Samples with time centered on apex (intervalMs becomes "delta ms from apex")
// If we have no apex info, we just fall back to the original samplesForPlot.

// Samples with time centered on the nearest selected apex.
// We set intervalMs = (sample.intervalMs - apex.apexIntervalMs)
// for whichever apex is closest in time.
            val samplesForTimePlot: List<EventSample> =
                remember(samplesForPlot, selectedApexesForTime) {
                    if (selectedApexesForTime.isEmpty()) {
                        samplesForPlot
                    } else {
                        samplesForPlot.map { s ->
                            val nearestApex = selectedApexesForTime.minByOrNull { apex ->
                                kotlin.math.abs(s.intervalMs - apex.apexIntervalMs)
                            }

                            if (nearestApex != null) {
                                val dt = s.intervalMs - nearestApex.apexIntervalMs
                                s.copy(intervalMs = dt)
                            } else {
                                s
                            }
                        }
                    }
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


                if (selectedFile != null && samplesForPlot.isNotEmpty()) {
                    if (chartMode == ChartMode.GG) {
                        Text(
                            text = "G-G Plot (selected corner visits, color-coded):",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))

                        SimpleGGPlot(
                            samples = samplesForPlot,
                            colorForSample = { sample ->
                                val key = sample.cornerIndex to sample.visitNumber
                                cornerVisitColors[key] ?: neutralSampleColor
                            }
                        )
                    } else {
                        Text(
                            text = "G vs Time (longitudinal & lateral):",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))

                        GTimePlot(
                            samples = samplesForTimePlot
                        )

                    }
                }
                }



                // --- NEW: Before/After Apex window sliders (UI only, not wired yet) ---
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Apex window (time around apex):",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                Column {
                    Text(
                        text = "Before apex: ${"%.1f".format(beforeApexSeconds)} s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = beforeApexSeconds,
                        onValueChange = { beforeApexSeconds = it },
                        valueRange = 0f..7.5f,        // you can tweak this range
                        steps = 0                   // continuous
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "After apex: ${"%.1f".format(afterApexSeconds)} s",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = afterApexSeconds,
                        onValueChange = { afterApexSeconds = it },
                        valueRange = 0f..7.5f,
                        steps = 0
                    )
                }



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
                        // We DO have corner-tagged samples.
                        // Limit stats to ONLY the (corner, visit) pairs that are checked.
                        val samplesForSummary = samplesForSelected.filter { sample ->
                            selectedCornerVisits.contains(sample.cornerIndex to sample.visitNumber)
                        }

                        if (samplesForSummary.isEmpty()) {
                            Text(
                                text = "No corner visits selected – toggle checkboxes above to see stats.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                text = "Per selected corner visit:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))

                            MaxGSummaryHeaderRow()
                            Spacer(Modifier.height(4.dp))

                            // Group by (cornerIndex, visitNumber) so each checkbox pair gets its own row
                            val samplesByCornerVisit = samplesForSummary
                                .filter { it.cornerIndex > 0 && it.visitNumber > 0 }
                                .groupBy { it.cornerIndex to it.visitNumber }

                            val sortedKeys = samplesByCornerVisit.keys
                                .sortedWith(
                                    compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }
                                )

                            sortedKeys.forEach { (cornerIdx, visitNum) ->
                                val visitSamples = samplesByCornerVisit[cornerIdx to visitNum].orEmpty()
                                val summary = computeMaxGSummary(visitSamples)

                                MaxGSummaryRow(
                                    label = "Corner $cornerIdx – Visit $visitNum",
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
// Now we only connect points that belong to the SAME (cornerIndex, visitNumber)
                if (samples.isNotEmpty()) {
                    val scalePerG = (radius * 0.95f) / maxG
                    var lastPoint: Offset? = null
                    var lastKey: Pair<Int, Int>? = null   // (cornerIndex, visitNumber)

                    samples.forEach { sample ->
                        val lat = sample.latG
                        val lon = sample.longG

                        val px = cx + lat * scalePerG
                        val py = cy - lon * scalePerG
                        val clamped = clampToCircle(px, py)

                        val color = colorForSample(sample)
                        val key = sample.cornerIndex to sample.visitNumber

                        // Only draw a connecting line if we're still in the same visit
                        if (lastPoint != null && lastKey == key) {
                            drawLine(
                                color = color,
                                start = lastPoint!!,
                                end = clamped,
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // Dot at this sample
                        drawCircle(
                            color = color,
                            radius = 1.dp.toPx(),
                            center = clamped
                        )

                        lastPoint = clamped
                        lastKey = key
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

@Composable
fun GTimePlot(
    samples: List<EventSample>
) {
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val longColor = MaterialTheme.colorScheme.primary
    val latColor = MaterialTheme.colorScheme.tertiary

    // NEW: Paint setup for the "Apex" label
    val density = LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }
    val apexLabelColor = axisColor
    val apexLabelPaint = remember(labelTextSizePx, apexLabelColor) {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = labelTextSizePx
            color = apexLabelColor.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    val axisLabelPaint = remember(labelTextSizePx, axisColor) {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = labelTextSizePx
            color = axisColor.toArgb()
            textAlign = android.graphics.Paint.Align.LEFT    // we’ll change this as needed
        }
    }



    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (samples.isEmpty()) return@Canvas

            val w = size.width
            val h = size.height

// --- Time range based on actual sample times ---
            val minT = samples.minOf { it.intervalMs }.toFloat()
            val maxT = samples.maxOf { it.intervalMs }.toFloat()
            val spanT = (maxT - minT).coerceAtLeast(1f)





// --- G range (symmetric around 0) ---


// --- G range (symmetric around 0), auto-scaled in 0.25G steps ---
            val rawMaxG = samples.maxOf { max(abs(it.latG), abs(it.longG)) }
            val step = 0.25f

            val maxG = if (rawMaxG <= 0f) {
                step                    // fall back to ±0.25G if everything is basically zero
            } else {
                val steps = ceil(rawMaxG / step.toDouble()).toFloat()
                steps * step            // e.g. 0.15 -> 0.25, 0.62 -> 0.75, 0.91 -> 1.0
            }

            val midY = h / 2f
            val gBand = h * 0.45f // 90% of height (±0.45h)






            fun xFor(tMs: Long): Float {
                val t = tMs.toFloat()
                val frac = (t - minT) / spanT    // 0 at minT, 1 at maxT
                return frac * w
            }


            fun yFor(g: Float): Float {
                val norm = (g / maxG).coerceIn(-1f, 1f)
                return midY - norm * gBand
            }

            val stroke = 1.dp.toPx()


            val xLabelInset = 4.dp.toPx()
            val bottomInset = 2.dp.toPx()

// --- Horizontal Grid Lines Every 0.25G ---
            val stepG = 0.25f
            val numSteps = (maxG / stepG).toInt()

            for (i in -numSteps..numSteps) {
                val gVal = i * stepG
                val y = yFor(gVal)

                // Light horizontal line
                drawLine(
                    color = axisColor.copy(alpha = 0.2f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = stroke
                )
            }


            val nativeCanvas = drawContext.canvas.nativeCanvas

// --- NEW: Vertical line at apex (t = 0) with label ---
            if (minT <= 0f && maxT >= 0f) {
                val xApex = xFor(0L)

                // Draw the vertical apex line
                drawLine(
                    color = longColor, // use longitudinal color so it stands out
                    start = Offset(xApex, 0f),
                    end = Offset(xApex, h),
                    strokeWidth = (stroke * 1.5f)
                )

                // Draw "Apex" label near the top of the line
                val labelY = 12.dp.toPx()  // a bit below the top edge
                nativeCanvas.drawText(
                    "Apex",
                    xApex,
                    labelY,
                    apexLabelPaint
                )
            }


            // --- Axes: horizontal 0g line + border ---
            drawLine(
                color = axisColor,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = stroke
            )

            // Vertical edges
            drawLine(
                color = axisColor,
                start = Offset(0f, 0f),
                end = Offset(0f, h),
                strokeWidth = stroke
            )
            drawLine(
                color = axisColor,
                start = Offset(w, 0f),
                end = Offset(w, h),
                strokeWidth = stroke
            )

            // --- Y-axis labels at +maxG, 0, -maxG ---
            axisLabelPaint.textAlign = AndroidPaint.Align.LEFT

            val yMax = yFor(maxG)
            val yZero = yFor(0f)
            val yMin = yFor(-maxG)

// Light grid lines at ±maxG
            drawLine(
                color = axisColor.copy(alpha = 0.3f),
                start = Offset(0f, yMax),
                end = Offset(size.width, yMax),
                strokeWidth = stroke
            )
            drawLine(
                color = axisColor.copy(alpha = 0.3f),
                start = Offset(0f, yMin),
                end = Offset(size.width, yMin),
                strokeWidth = stroke
            )

// Numeric labels on the left
            nativeCanvas.drawText(
                String.format("%.2fG", maxG),
                xLabelInset,
                yMax - 2.dp.toPx(),
                axisLabelPaint
            )
            nativeCanvas.drawText(
                "0",
                xLabelInset,
                yZero - 2.dp.toPx(),
                axisLabelPaint
            )
            nativeCanvas.drawText(
                String.format("-%.2fG", maxG),
                xLabelInset,
                yMin - 2.dp.toPx(),
                axisLabelPaint
            )


            // --- X-axis labels at left, 0 (if in range), right ---
            axisLabelPaint.textAlign = AndroidPaint.Align.CENTER

            val minSec = minT / 1000f
            val maxSec = maxT / 1000f

// Left edge (start of window)
            val xMin = xFor(minT.toLong())
            nativeCanvas.drawText(
                String.format("%.1f", minSec),
                xMin,
                size.height - bottomInset,
                axisLabelPaint
            )

// 0s (apex) if it lies within the current window
            if (minT <= 0f && maxT >= 0f) {
                val xZero = xFor(0L)
                nativeCanvas.drawText(
                    "0",
                    xZero,
                    size.height - bottomInset,
                    axisLabelPaint
                )
            }

// Right edge (end of window)
            val xMax = xFor(maxT.toLong())
            nativeCanvas.drawText(
                String.format("%.1f", maxSec),
                xMax,
                size.height - bottomInset,
                axisLabelPaint
            )



            // Optional reference lines at ±1.0g
            if (maxG >= 1f) {
                val yPlus = yFor(1f)
                val yMinus = yFor(-1f)
                drawLine(
                    color = axisColor.copy(alpha = 0.3f),
                    start = Offset(0f, yPlus),
                    end = Offset(w, yPlus),
                    strokeWidth = stroke
                )
                drawLine(
                    color = axisColor.copy(alpha = 0.3f),
                    start = Offset(0f, yMinus),
                    end = Offset(w, yMinus),
                    strokeWidth = stroke
                )
            }

            // --- Helper to draw a polyline for a given G component ---
            fun drawSeries(selectG: (EventSample) -> Float, color: Color) {
                var lastPoint: Offset? = null

                samples.forEach { s ->
                    val x = xFor(s.intervalMs)
                    val y = yFor(selectG(s))
                    val p = Offset(x, y)

                    lastPoint?.let { prev ->
                        drawLine(
                            color = color,
                            start = prev,
                            end = p,
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    lastPoint = p
                }
            }

            // Longitudinal first, then lateral
            drawSeries(selectG = { it.longG }, color = longColor)
            drawSeries(selectG = { it.latG }, color = latColor)
        }

        // --- Tiny legend anchored to top-left (not scaled with canvas) ---
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp, 3.dp)
                    .background(longColor)
            )
            Spacer(Modifier.width(4.dp))
            Text("Longitudinal", style = MaterialTheme.typography.labelSmall)

            Spacer(Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(10.dp, 3.dp)
                    .background(latColor)
            )
            Spacer(Modifier.width(4.dp))
            Text("Lateral", style = MaterialTheme.typography.labelSmall)
        }
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
            text = String.format("%.2fG", summary.braking),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.2fG", summary.accel),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.2fG", summary.left),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format("%.2fG", summary.right),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

data class ApexVisit(
    val cornerIndex: Int,
    val visitNumber: Int,
    val cornerName: String,
    val apexIntervalMs: Long,
    val apexUtcMs: Long
)


