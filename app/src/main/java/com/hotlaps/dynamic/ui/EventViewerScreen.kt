package com.hotlaps.dynamic.ui

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotlaps.dynamic.data.EventStorage
import com.hotlaps.dynamic.model.EventSample
import com.hotlaps.dynamic.ui.ChartMode
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

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
            val context = LocalContext.current

            // All events on disk
            val eventFiles = remember { EventStorage.listEventFiles(context) }

            // Which event is selected
            var selectedFile by remember { mutableStateOf<File?>(null) }

            // Chart mode toggle
            var chartMode by remember { mutableStateOf(ChartMode.GG) }

            // Samples for selected event
            val samplesForSelected = remember(selectedFile) {
                selectedFile?.let { file ->
                    EventStorage.loadSamplesFromCsv(file)
                } ?: emptyList()
            }

            // Apex detection: one apex per (cornerIndex, visitNumber)
            val apexVisits = remember(samplesForSelected) {
                samplesForSelected
                    .filter { it.isApexSample && it.cornerIndex > 0 && it.visitNumber > 0 }
                    .groupBy { it.cornerIndex to it.visitNumber }
                    .mapNotNull { (cornerVisitKey, samples) ->
                        val apexSample =
                            samples.minByOrNull { it.intervalMs } ?: samples.firstOrNull()
                        if (apexSample == null) {
                            null
                        } else {
                            ApexVisit(
                                cornerIndex = cornerVisitKey.first,
                                visitNumber = cornerVisitKey.second,
                                cornerName = apexSample.cornerName.ifBlank {
                                    "Corner ${cornerVisitKey.first}"
                                },
                                apexIntervalMs = apexSample.intervalMs,
                                apexUtcMs = apexSample.utcMs
                            )
                        }
                    }
                    .sortedWith(
                        compareBy<ApexVisit> { it.cornerIndex }.thenBy { it.visitNumber }
                    )
            }

            // Unique (cornerIndex, visitNumber) combinations
            val cornerVisitGroups = remember(apexVisits) {
                apexVisits
                    .map { it.cornerIndex to it.visitNumber }
                    .distinct()
                    .sortedWith(
                        compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }
                    )
            }

            // Which corner/visit groups are selected for plotting
            var selectedCornerVisits by remember(cornerVisitGroups) {
                mutableStateOf(cornerVisitGroups.toSet())
            }

            // Apex window around apex (seconds)
            var beforeApexSeconds by remember { mutableStateOf(3f) }
            var afterApexSeconds by remember { mutableStateOf(3f) }

            // Color palette per (corner, visit)
            val palette = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.error,
                MaterialTheme.colorScheme.inversePrimary
            )

            val cornerVisitColors = cornerVisitGroups
                .mapIndexed { index, groupKey ->
                    val baseColor = palette[index % palette.size]
                    groupKey to baseColor.copy(alpha = 0.9f)
                }
                .toMap()

            // Simple status
            Text("Found ${eventFiles.size} event file(s)")

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

            Spacer(Modifier.height(12.dp))

            // Chart mode buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(Modifier.height(16.dp))

            // -----------------------------
            // EVENT LIST (scrollable window)
            // -----------------------------
            Text("Events:", style = MaterialTheme.typography.titleSmall)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp) // adjust as needed so the plot is still visible
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(eventFiles) { file ->
                        val isSelected = (file == selectedFile)

                        Text(
                            text = if (isSelected) "▶ ${file.name}" else file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFile = file }
                                .padding(6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // -------------------------------------
            // CORNER VISIT LIST (scrollable window)
            // -------------------------------------
            if (selectedFile != null) {
                if (cornerVisitGroups.isEmpty()) {
                    Text(
                        text = "This event has no corner-tagged samples.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "Corner visits (toggle to include in plot):",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp) // adjust as needed
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(cornerVisitGroups) { (cornerIdx, visitNum) ->
                                val groupKey = cornerIdx to visitNum
                                val isChecked =
                                    selectedCornerVisits.contains(groupKey)

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedCornerVisits =
                                                if (isChecked) {
                                                    selectedCornerVisits - groupKey
                                                } else {
                                                    selectedCornerVisits + groupKey
                                                }
                                        }
                                        .padding(vertical = 2.dp, horizontal = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
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
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // Map corner/visit -> apex
            val apexByGroup = remember(apexVisits) {
                apexVisits.associateBy { it.cornerIndex to it.visitNumber }
            }

            // Apex visits that are currently selected
            val selectedApexesForTime = remember(apexVisits, selectedCornerVisits) {
                apexVisits.filter {
                    selectedCornerVisits.contains(it.cornerIndex to it.visitNumber)
                }
            }

            // Filter samples based on selected corner/visit and apex windows
            val samplesForPlot =
                if (apexVisits.isNotEmpty() && selectedCornerVisits.isNotEmpty()) {
                    val beforeMs = (beforeApexSeconds * 1000f).toLong()
                    val afterMs = (afterApexSeconds * 1000f).toLong()

                    samplesForSelected.filter { sample ->
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
                    samplesForSelected
                }

            // Assign nearest apex corner/visit to each sample so plotting code knows which visit it belongs to
            val samplesForPlotGrouped: List<EventSample> =
                remember(samplesForPlot, selectedApexesForTime) {
                    if (selectedApexesForTime.isEmpty()) {
                        samplesForPlot
                    } else {
                        samplesForPlot.map { s ->
                            val nearestApex =
                                selectedApexesForTime.minByOrNull { apex ->
                                    abs(s.intervalMs - apex.apexIntervalMs)
                                }

                            if (nearestApex != null) {
                                s.copy(
                                    cornerIndex = nearestApex.cornerIndex,
                                    visitNumber = nearestApex.visitNumber,
                                    cornerName = nearestApex.cornerName.ifBlank {
                                        "Corner ${nearestApex.cornerIndex}"
                                    }
                                )
                            } else {
                                s
                            }
                        }
                    }
                }

            // Time-centered samples for G vs Time (intervalMs becomes delta-from-apex)
            val samplesForTimePlot: List<EventSample> =
                remember(samplesForPlotGrouped, apexByGroup) {
                    if (apexByGroup.isEmpty()) {
                        samplesForPlotGrouped
                    } else {
                        samplesForPlotGrouped.map { s ->
                            val key = s.cornerIndex to s.visitNumber
                            val apex = apexByGroup[key]

                            if (apex != null) {
                                val dt = s.intervalMs - apex.apexIntervalMs
                                s.copy(intervalMs = dt)
                            } else {
                                s
                            }
                        }
                    }
                }

            val neutralSampleColor =
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

            // -------------------------
            // CHART AREA (GG / Time)
            // -------------------------
            if (selectedFile != null && samplesForPlot.isNotEmpty()) {
                val title =
                    if (chartMode == ChartMode.GG) "G-G Plot (selected corner visits)"
                    else "G vs Time (selected corner visits)"

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))

                if (chartMode == ChartMode.GG) {
                    SimpleGGPlot(
                        samples = samplesForPlotGrouped,
                        colorForSample = { sample ->
                            val key = sample.cornerIndex to sample.visitNumber
                            cornerVisitColors[key] ?: neutralSampleColor
                        }
                    )
                } else {
                    GTimePlot(
                        samples = samplesForTimePlot
                    )
                }
            }

            // -------------------------
            // Apex window sliders
            // -------------------------
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
                    valueRange = 0f..7.5f,
                    steps = 0
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

            // -------------------------
            // MAX G summary section
            // -------------------------
            Spacer(Modifier.height(24.dp))

            if (samplesForSelected.isNotEmpty()) {
                Text(
                    text = "Max G summary",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(8.dp))

                if (cornerVisitGroups.isEmpty()) {
                    val overallSummary = remember(samplesForSelected) {
                        computeMaxGSummary(samplesForSelected)
                    }

                    MaxGSummaryHeaderRow()
                    Spacer(Modifier.height(4.dp))
                    MaxGSummaryRow(label = "Event", summary = overallSummary)
                } else {
                    val samplesForSummary = samplesForSelected.filter { sample ->
                        selectedCornerVisits.contains(
                            sample.cornerIndex to sample.visitNumber
                        )
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

                        val samplesByCornerVisit = samplesForSummary
                            .filter { it.cornerIndex > 0 && it.visitNumber > 0 }
                            .groupBy { it.cornerIndex to it.visitNumber }

                        val sortedKeys = samplesByCornerVisit.keys
                            .sortedWith(
                                compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }
                            )

                        sortedKeys.forEach { (cornerIdx, visitNum) ->
                            val visitSamples =
                                samplesByCornerVisit[cornerIdx to visitNum].orEmpty()
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

// --- Simple GG Plot with per-sample colors ---
@Composable
fun SimpleGGPlot(
    samples: List<EventSample>,
    colorForSample: (EventSample) -> Color
) {
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    val density = LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }
    val labelGapPx = with(density) { 4.dp.toPx() }
    val androidAxisColor = axisColor.toArgb()

    val labelPaint = remember(labelTextSizePx, androidAxisColor) {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = labelTextSizePx
            color = androidAxisColor
            textAlign = AndroidPaint.Align.LEFT
        }
    }

    val rawMaxG = samples.maxOfOrNull { sample ->
        max(abs(sample.latG), abs(sample.longG))
    } ?: 0f

    val maxG = when {
        rawMaxG <= 0f -> 0.5f
        rawMaxG < 0.5f -> 0.5f
        else -> rawMaxG * 1.1f
    }

    var userScale by remember { mutableStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(8.dp)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (userScale * zoom).coerceIn(0.5f, 3f)
                    userScale = newScale
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

            withTransform({
                translate(userOffset.x, userOffset.y)
                scale(userScale, userScale, pivot = center)
            }) {
                drawCircle(
                    color = axisColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

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

                val tickStep = 0.25f
                var tick = tickStep
                val nativeCanvas = drawContext.canvas.nativeCanvas

                while (tick < maxG + 1e-3f) {
                    val r = radius * (tick / maxG)
                    drawCircle(
                        color = axisColor.copy(alpha = 0.25f),
                        radius = r,
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    val label = String.format("%.2fG", tick)
                    nativeCanvas.drawText(
                        label,
                        cx + r + labelGapPx,
                        cy - labelGapPx,
                        labelPaint
                    )

                    tick += tickStep
                }

                if (samples.isNotEmpty()) {
                    val scalePerG = (radius * 0.95f) / maxG
                    var lastPoint: Offset? = null
                    var lastKey: Pair<Int, Int>? = null

                    samples.forEach { sample ->
                        val lat = sample.latG
                        val lon = sample.longG
                        val px = cx + lat * scalePerG
                        val py = cy - lon * scalePerG
                        val clamped = clampToCircle(px, py)

                        val color = colorForSample(sample)
                        val key = sample.cornerIndex to sample.visitNumber

                        if (lastPoint != null && lastKey == key) {
                            drawLine(
                                color = color,
                                start = lastPoint!!,
                                end = clamped,
                                strokeWidth = 1.dp.toPx()
                            )
                        }

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

    val density = LocalDensity.current
    val labelTextSizePx = with(density) { 10.sp.toPx() }
    val apexLabelColor = axisColor
    val apexLabelPaint = remember(labelTextSizePx, apexLabelColor) {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = labelTextSizePx
            color = apexLabelColor.toArgb()
            textAlign = AndroidPaint.Align.CENTER
        }
    }

    val axisLabelPaint = remember(labelTextSizePx, axisColor) {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = labelTextSizePx
            color = axisColor.toArgb()
            textAlign = AndroidPaint.Align.LEFT
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

            val minT = samples.minOf { it.intervalMs }.toFloat()
            val maxT = samples.maxOf { it.intervalMs }.toFloat()
            val spanT = (maxT - minT).coerceAtLeast(1f)

            val rawMaxG = samples.maxOf { max(abs(it.latG), abs(it.longG)) }
            val step = 0.25f
            val maxG = if (rawMaxG <= 0f) {
                step
            } else {
                val steps = ceil(rawMaxG / step.toDouble()).toFloat()
                steps * step
            }

            val midY = h / 2f
            val gBand = h * 0.45f

            fun xFor(tMs: Long): Float {
                val t = tMs.toFloat()
                val frac = (t - minT) / spanT
                return frac * w
            }

            fun yFor(g: Float): Float {
                val norm = (g / maxG).coerceIn(-1f, 1f)
                return midY - norm * gBand
            }

            val stroke = 1.dp.toPx()
            val xLabelInset = 4.dp.toPx()
            val bottomInset = 2.dp.toPx()

            val stepG = 0.25f
            val numSteps = (maxG / stepG).toInt()

            for (i in -numSteps..numSteps) {
                val gVal = i * stepG
                val y = yFor(gVal)

                drawLine(
                    color = axisColor.copy(alpha = 0.2f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = stroke
                )
            }

            val nativeCanvas = drawContext.canvas.nativeCanvas

            if (minT <= 0f && maxT >= 0f) {
                val xApex = xFor(0L)

                drawLine(
                    color = longColor,
                    start = Offset(xApex, 0f),
                    end = Offset(xApex, h),
                    strokeWidth = (stroke * 1.5f)
                )

                val labelY = 12.dp.toPx()
                nativeCanvas.drawText(
                    "Apex",
                    xApex,
                    labelY,
                    apexLabelPaint
                )
            }

            drawLine(
                color = axisColor,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = stroke
            )

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

            axisLabelPaint.textAlign = AndroidPaint.Align.LEFT

            val yMax = yFor(maxG)
            val yZero = yFor(0f)
            val yMin = yFor(-maxG)

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

            axisLabelPaint.textAlign = AndroidPaint.Align.CENTER

            val minSec = minT / 1000f
            val maxSec = maxT / 1000f

            val xMin = xFor(minT.toLong())
            nativeCanvas.drawText(
                String.format("%.1f", minSec),
                xMin,
                size.height - bottomInset,
                axisLabelPaint
            )

            if (minT <= 0f && maxT >= 0f) {
                val xZero = xFor(0L)
                nativeCanvas.drawText(
                    "0",
                    xZero,
                    size.height - bottomInset,
                    axisLabelPaint
                )
            }

            val xMax = xFor(maxT.toLong())
            nativeCanvas.drawText(
                String.format("%.1f", maxSec),
                xMax,
                size.height - bottomInset,
                axisLabelPaint
            )

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

            fun drawSeries(selectG: (EventSample) -> Float, color: Color) {
                var lastPoint: Offset? = null
                var lastKey: Pair<Int, Int>? = null

                samples.forEach { s ->
                    val x = xFor(s.intervalMs)
                    val y = yFor(selectG(s))
                    val p = Offset(x, y)
                    val key = s.cornerIndex to s.visitNumber

                    if (lastPoint != null && lastKey == key) {
                        drawLine(
                            color = color,
                            start = lastPoint!!,
                            end = p,
                            strokeWidth = 2.dp.toPx()
                        )
                    }

                    lastPoint = p
                    lastKey = key
                }
            }

            drawSeries(selectG = { it.longG }, color = longColor)
            drawSeries(selectG = { it.latG }, color = latColor)
        }

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

private fun computeMaxGSummary(samples: List<EventSample>): MaxGSummary {
    var maxBrake = 0f
    var maxAccel = 0f
    var maxLeft = 0f
    var maxRight = 0f

    for (s in samples) {
        if (s.longG < 0f) {
            val brake = -s.longG
            if (brake > maxBrake) maxBrake = brake
        } else {
            if (s.longG > maxAccel) maxAccel = s.longG
        }

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
