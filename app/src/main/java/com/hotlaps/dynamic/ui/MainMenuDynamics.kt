// ui/MainMenuDynamics.kt
package com.hotlaps.dynamic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotlaps.dynamic.data.CalibRepo
import com.hotlaps.dynamic.data.PrefsRepo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotlaps.dynamic.BuildConfig
import androidx.compose.ui.platform.LocalContext

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    subText: String? = null,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (subText != null) {
                Spacer(Modifier.height(4.dp))
                Text(subText, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun MainMenuDynamics(
    onDrive: () -> Unit,
    onTrackManager: () -> Unit,
    onEventManager: () -> Unit,
    onCalibrate: () -> Unit,
    onSettings: () -> Unit
) {

    val context = LocalContext.current
    val calibRepo = remember(context) { CalibRepo(context) }
    val prefsRepo = remember { PrefsRepo() }

    val calibState = calibRepo.state.collectAsStateWithLifecycle(initialValue = null).value
    val isCalibrated = calibState?.vec != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hotlaps Dynamics",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isCalibrated) "Calibration: Saved ✓" else "Calibration: Not set",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCalibrated) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(24.dp))

        // Primary action: Drive
        BigButton(
            text = if (isCalibrated) "Drive" else "Drive (needs calibration)",
            enabled = isCalibrated,
            onClick = onDrive
        )

        Spacer(Modifier.height(16.dp))

        // Track Manager
        BigButton(
            text = "Track Manager",
            onClick = onTrackManager
        )

        Spacer(Modifier.height(16.dp))

        // Event Manager
        BigButton(
            text = "Event Manager",
            onClick = onEventManager,
            enabled = true
        )


        Spacer(Modifier.height(16.dp))

        // Calibrate + Settings at the bottom of the stack
        BigButton(
            text = "Calibrate Accelerometers",
            onClick = onCalibrate
        )

        Spacer(Modifier.height(16.dp))

        BigButton(
            text = "Settings",
            onClick = onSettings
        )

        Spacer(Modifier.weight(1f))

        Text(
            text = "Rev ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • ${BuildConfig.GIT_SHA} • ${BuildConfig.BUILD_TIME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            textAlign = TextAlign.Center
        )
    }
}
