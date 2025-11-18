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
    // NEW: function that decides the color for each sample
    colorForSample: (EventSample) -> Color
) {
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    // Auto-scale based on max |G|
    val rawMaxG = samples.maxOfOrNull { sample ->
        max(
            abs(sample.latG),
            abs(sample.longG)
        )
    } ?: 0f

    val maxG = if (rawMaxG <= 0f) {
        0.1f
    } else {
        rawMaxG * 1.1f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val cx = width / 2f
            val cy = height / 2f

            // Axes stay neutral
            drawLine(
                color = axisColor,
                start = Offset(0f, cy),
                end = Offset(width, cy),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = axisColor,
                start = Offset(cx, 0f),
                end = Offset(cx, height),
                strokeWidth = 1.dp.toPx()
            )

            val halfWidth = width / 2f
            val halfHeight = height / 2f
            val marginFactor = 0.9f

            var lastPoint: Offset? = null

            samples.forEach { sample ->
                val lat = sample.latG
                val lon = sample.longG

                val x = cx + (lat / maxG) * halfWidth * marginFactor
                val y = cy - (lon / maxG) * halfHeight * marginFactor
                val current = Offset(x, y)

                // NEW: get color for this sample
                val pointColor = colorForSample(sample)

                // Connect consecutive points with a line in the same color
                lastPoint?.let { prev ->
                    drawLine(
                        color = pointColor,
                        start = prev,
                        end = current,
                        strokeWidth = 1.dp.toPx()
                    )
                }

                drawCircle(
                    color = pointColor,
                    radius = 2.dp.toPx(),
                    center = current
                )

                lastPoint = current
            }
        }
    }
}

