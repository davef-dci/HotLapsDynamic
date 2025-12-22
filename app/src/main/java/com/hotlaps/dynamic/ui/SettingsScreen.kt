// app/src/main/java/com/hotlaps/dynamic/ui/settings/SettingsScreen.kt
package com.hotlaps.dynamic.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import com.hotlaps.dynamic.data.SettingsRepo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.Alignment
import androidx.navigation.NavController
import com.hotlaps.dynamic.data.SmoothingLevel
import com.hotlaps.dynamic.BuildConfig

import com.google.firebase.crashlytics.FirebaseCrashlytics



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, navController: NavController)
 {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepo(context) }
    val scope = rememberCoroutineScope()

    // Read the current value from DataStore
    val trailBrakeG by repo.trailBrakeG.collectAsState(initial = 0.30f)

    // Local editable text for the field
    var trailText by remember(trailBrakeG) { mutableStateOf(trailBrakeG.toString()) }
    var status by remember { mutableStateOf(" ") }

    // G-G trail window (seconds)
    val ggTrailWindowS by repo.ggTrailWindowS.collectAsState(initial = 3.0f)
    var ggTrailText by remember(ggTrailWindowS) { mutableStateOf("%.1f".format(ggTrailWindowS)) }

    // Corner capture trigger radius (meters)
    val cornerRadiusM by repo.cornerTriggerRadiusM.collectAsState(initial = 30f)
    var cornerRadiusText by remember(cornerRadiusM) { mutableStateOf("%.0f".format(cornerRadiusM)) }

// Smoothing level (0 = Off, 1 = Low, 2 = Medium, 3 = Heavy)
    val smoothingIndex by repo.smoothingLevel.collectAsState(initial = 1)
    var smoothingLevel by remember(smoothingIndex) {
        mutableStateOf(SmoothingLevel.entries[smoothingIndex])
    }

     // Tire grip limit (Breakaway G)
     val breakawayG by repo.breakawayG.collectAsState(initial = 1.00f)
     var breakawayText by remember(breakawayG) { mutableStateOf("%.2f".format(breakawayG)) }

     var devTapCount by remember { mutableStateOf(0) }
     var showDevToggle by remember { mutableStateOf(false) }

     val showDebugTools by repo.showDebugTools.collectAsState(initial = false)


     Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Control 1: Trail Brake Threshold (G)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trail Brake Threshold (G)")

                Spacer(Modifier.width(6.dp))

                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "What is Trail Braking Threshold?",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            navController.navigate("helpSettings_trailBrake")
                        }
                )
            }

            OutlinedTextField(
                value = trailText,
                onValueChange = { trailText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Typical 0.20 – 0.60 G") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val v = trailText.toFloatOrNull()
                    if (v == null) {
                        status = "Please enter a valid number"
                    } else {
                        scope.launch {
                            repo.updateTrailBrakeG(v)
                            status = "Saved ✓  (current = ${"%.3f".format(v)} G)"
                        }
                    }
                }) { Text("Save") }

                OutlinedButton(onClick = {
                    // Reset the text field from the persisted value
                    trailText = trailBrakeG.toString()
                    status = "Reverted to saved value"
                }) { Text("Revert") }
            }

            Divider()
// --- Control 2: Tire Grip Limit (Breakaway G)
            Text("Tire Grip Limit (Breakaway G)")

            OutlinedTextField(
                value = breakawayText,
                onValueChange = { breakawayText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Approximate peak G before the car slides (e.g., 0.90 – 1.20)") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val v = breakawayText.toFloatOrNull()
                    if (v == null) {
                        status = "Please enter a valid number"
                    } else {
                        scope.launch {
                            repo.updateBreakawayG(v)
                            status = "Saved ✓  (Breakaway = ${"%.2f".format(v)} G)"
                        }
                    }
                }) { Text("Save") }

                OutlinedButton(onClick = {
                    breakawayText = "%.2f".format(breakawayG)
                    status = "Reverted to saved Breakaway G"
                }) { Text("Revert") }
            }

            Divider()




            Text("G-G trail window (seconds)")
            OutlinedTextField(
                value = ggTrailText,
                onValueChange = { ggTrailText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Enter 1.0 to 600.0 seconds (e.g., 3.0, 90.0)") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val v = ggTrailText.toFloatOrNull()
                    if (v == null) {
                        status = "Please enter a valid number"
                    } else {
                        // clamp to a sensible wide range: 1s .. 600s (10 minutes)
                        val clamped = v.coerceIn(1.0f, 600.0f)
                        // normalize text to one decimal
                        ggTrailText = "%.1f".format(clamped)
                        scope.launch {
                            repo.updateGgTrailWindowS(clamped)
                            status = "Saved ✓  (Trail window = ${"%.1f".format(clamped)} s)"
                        }
                    }
                }) { Text("Save") }

                OutlinedButton(onClick = {
                    ggTrailText = "%.1f".format(ggTrailWindowS)
                    status = "Reverted to saved value"
                }) { Text("Revert") }
            }

            Divider()

            Text("Corner capture trigger radius (m)")
            OutlinedTextField(
                value = cornerRadiusText,
                onValueChange = { cornerRadiusText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Enter 5 to 100 meters (e.g., 30)") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val v = cornerRadiusText.toFloatOrNull()
                    if (v == null) {
                        status = "Please enter a valid number"
                    } else {
                        val clamped = v.coerceIn(5f, 100f)
                        cornerRadiusText = "%.0f".format(clamped)
                        scope.launch {
                            repo.updateCornerTriggerRadiusM(clamped)
                            status = "Saved ✓  (Corner radius = ${"%.0f".format(clamped)} m)"
                        }
                    }
                }) { Text("Save") }

                OutlinedButton(onClick = {
                    cornerRadiusText = "%.0f".format(cornerRadiusM)
                    status = "Reverted to saved value"
                }) { Text("Revert") }
            }


            Divider()

            Text("Data Smoothing")

// Dropdown selector
            var expanded by remember { mutableStateOf(false) }

            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(smoothingLevel.name)
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    SmoothingLevel.entries.forEachIndexed { idx, level ->
                        DropdownMenuItem(
                            text = { Text(level.name) },
                            onClick = {
                                smoothingLevel = level
                                expanded = false

                                // Save to DataStore
                                scope.launch {
                                    repo.updateSmoothingLevel(level.ordinal)
                                    status = "Saved ✓  (Smoothing = ${level.name})"
                                }
                            }
                        )
                    }
                }
            }


            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
            }

            Divider()

            Text(
                text = "App Version ${BuildConfig.VERSION_NAME}",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        devTapCount++
                        if (devTapCount >= 7) {
                            showDevToggle = true
                            devTapCount = 0
                        }
                    }
                    .padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (showDevToggle || showDebugTools) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Developer Tools")

                    Switch(
                        checked = showDebugTools,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repo.updateShowDebugTools(enabled)
                            }
                        }
                    )
                }
            }



        }
    }
}
