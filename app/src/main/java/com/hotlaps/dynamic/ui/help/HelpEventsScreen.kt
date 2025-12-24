package com.hotlaps.dynamic.ui.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpEventsScreen(
    onBack: () -> Unit
) {
    HelpScaffold(
        title = "Events",
        onBack = onBack
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = "Events Overview",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = """
An Event is a recorded driving session: a block of G-force, GPS, speed, and corner data captured while you are on track. Each event is stored as a CSV file on your device and can be analyzed inside the app or exported for coaching and deeper offline analysis.

Apex Dynamics gives you two main tools for working with events:
• Event Manager – manage, share, and delete event files.
• Event Viewer – analyze G-G plots, G vs Time, lap corners, and max G summaries for a selected event.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Event Manager",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
Open the Event Manager from the navigation drawer or main menu. The Event Manager lets you:
• Analyze events
• Share event CSV files
• Delete old event files

The main buttons are:
• Analyze Events – opens the Event Viewer, where you can choose an event and view its plots and summary.
• Share Event CSV – lets you pick one or more event files and share them using email, cloud storage, or other apps.
• Delete Events – lets you select and delete individual events, or delete all events at once.

Below these buttons, the Event Manager shows status messages (for example “Deleted 3 selected event file(s)”) after you delete or share files.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Event Viewer: Choosing an Event",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
The Event Viewer is where you analyze a single event in detail.

At the top, you’ll see:
• The number of event files detected on disk.
• A message indicating whether an event is selected.
• The number of samples and detected apexes in the selected event.

Below that is a scrollable list of event files. Tap a file name to select it. The selected event is marked and becomes the source for all plots and summaries.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Corner Laps & Selection",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
If the event has corner data, the Event Viewer builds a list of corner laps (corner index + visit number).

• Each row shows something like “Corner 1 – Lap 3”.
• Checkboxes let you toggle which laps are included in the plots.
• Only selected corner laps are drawn in color; unselected corner laps are ignored.

If there are no corner-tagged samples, the viewer tells you the event has no corner laps and plots the full event as a single sequence instead.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Chart Modes: G-G Plot and G vs Time",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
The Event Viewer has a mode selector with two options:
• G-G Plot – a lateral vs longitudinal G-force plot (similar to the live G-Force Map).
• G vs Time – two stacked plots showing longitudinal G and lateral G over time around the apex.

In both modes:
• Each selected lap corner is assigned a unique color.
• A slider at the bottom controls a “replay position” from 0–100% of the visible window.
• A moving marker shows the current sample at that replay position:
  – A bright dot on the G-G plot
  – A vertical line on the G vs Time plot
• The legend below the plot shows which color belongs to which corner/lap and displays the current G values for the replay sample.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "G vs Time Plot (Details)",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
The G vs Time mode is designed to help you understand what you’re doing before and after the apex.

Layout:
• The top graph shows longitudinal G (accel/brake) vs time from the apex.
  – Above zero = acceleration
  – Below zero = braking
• The bottom graph shows lateral G (cornering) vs time from the apex.
  – Positive = right-hand cornering
  – Negative = left-hand cornering

Key points:
• Time is shown as “time from apex”, with 0 at the detected apex.
  – Negative time = before the apex (braking and turn-in).
  – Positive time = after the apex (throttle pickup and exit).
• Each selected lap corner is drawn in its own color on both plots.
• The vertical cursor line moves as you drag the replay slider, so you can see exactly:
  – How hard you were braking at a given moment
  – When you started releasing the brake
  – When you began adding throttle
  – How lateral G built up and decayed through the corner

Using this, you can compare different corners and answer questions like:
• Did I brake later on this lap?
• Did I release the brake more smoothly?
• Did I get back to throttle earlier or more aggressively?
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Apex-Centered Windows",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
Each corner has a detected apex. The Event Viewer lets you define a time window around each apex using the dual sliders at the bottom:

• Before – how many seconds before the apex to include.
• After – how many seconds after the apex to include.

The app filters samples to only those within this time window around each selected apex. This allows you to compare:
• Braking and turn-in before the apex
• Throttle pickup and exit after the apex

Time in the G vs Time plot is always relative to the apex, so adjustments to these sliders change how much of the approach and exit you see.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "CSV Export & Apex Flags",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
Every event is saved as a CSV file with one row per sample.

Typical columns include:
• Time information
• Track and event name
• Corner index and lap number
• Lateral and longitudinal G (both raw and smoothed)
• GPS latitude and longitude
• Distance to the closest corner
• Speed (interpolated between GPS points)
• Apex flag indicating whether this sample is the detected apex for a lap corner

Apex flags:
• The app runs a post-processing algorithm to find the apex for each lap, based on the nearest GPS points and curve shape.
• The resulting apex is marked in the CSV so you can:
  – Verify the detected apex locations in your own tools
  – Slice the data into “before apex” and “after apex” windows outside the app
  – Align laps in external analysis software using the same apex definition

Use Event Manager’s “Share Event CSV” button to send these files to a coach, load them into spreadsheets, or import them into other analysis tools you like.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Max G Summary",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
At the bottom, the Event Viewer shows a Max G Summary table.

If there are no lap corners, the summary shows:
• A single row labeled “Event” – the maximum braking, acceleration, left G, and right G across the full event.

If lap corners are present, the summary can show:
• One row per selected corner/lap (for example “Corner 1 – Lap 3”).
• Columns for max braking, acceleration, left G, and right G in that visit’s apex window.

This makes it easy to see which laps had the hardest braking, strongest acceleration, or highest cornering G in each direction.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Tips",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
• Start with a single event and one corner to understand how the viewer behaves, then add more visits.
• Use a short apex window (for example 2–3 seconds before and after) when you want to focus just on the braking zone and exit.
• Use the replay slider and legend together to connect exact G values with what you see on the plots.
• Use Event Manager’s “Share Event CSV” to send files to a coach or to analyze in external tools.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
