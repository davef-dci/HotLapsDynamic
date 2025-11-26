package com.hotlaps.dynamic.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Top-level Help screen.
 *
 * Right now this just shows a list of topics.
 * In a later step, we'll make the rows navigate to detail pages.
 */
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    onQuickStartClick: () -> Unit = {},
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

            // Quick Start Guide row
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onQuickStartClick() },
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Quick Start Guide",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // Other topics listed normally
            items(
                listOf(
                    "Understanding Calibration",
                    "G-Force Map",
                    "Tracks",
                    "Events",
                    "Settings",
                    "Troubleshooting"
                )
            ) { topic ->
                HelpTopicRow(topic)
            }
        }
    }
}



@Composable
private fun HelpTopicRow(topic: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            // We'll enable clicks later when we have detail screens.
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
