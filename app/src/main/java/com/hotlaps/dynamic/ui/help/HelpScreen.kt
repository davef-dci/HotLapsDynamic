package com.hotlaps.dynamic.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(
    onBack: () -> Unit,
    onQuickStartClick: () -> Unit = {},
    onCalibrationClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    onGForceMapClick: () -> Unit = {},
    onTracksClick: () -> Unit = {},
    onEventsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},

    modifier: Modifier = Modifier
) {
    HelpScaffold(
        title = "Help",
        onBack = onBack
    ) { innerPadding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {

            // About Apex Dynamics
            item {
                HelpCardRow(
                    title = "About Apex Dynamics",
                    onClick = onAboutClick
                )
            }

            // Quick Start Guide
            item {
                HelpCardRow(
                    title = "Quick Start Guide",
                    onClick = onQuickStartClick
                )
            }

            // Understanding Calibration
            item {
                HelpCardRow(
                    title = "Understanding Calibration",
                    onClick = onCalibrationClick
                )
            }

            // G-Force Map
            item {
                HelpCardRow(
                    title = "G-Force Map",
                    onClick = onGForceMapClick
                )
            }

            // Tracks
            item {
                HelpCardRow(
                    title = "Tracks",
                    onClick = onTracksClick
                )
            }

            // Events
            item {
                HelpCardRow(
                    title = "Events",
                    onClick = onEventsClick
                )
            }

            // Settings
            item {
                HelpCardRow(
                    title = "Settings",
                    onClick = onSettingsClick
                )
            }
        }
    }
}

@Composable
private fun HelpCardRow(
    title: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun HelpTopicRow(topic: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = false) { },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = topic,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
