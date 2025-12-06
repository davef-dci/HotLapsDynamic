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
fun HelpTracksScreen(
    onBack: () -> Unit
) {
    HelpScaffold(
        title = "Tracks",
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
                text = "Tracks & Corners",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = """
Tracks tell Apex Dynamics where you are driving and how to group data by corner. A Track is a named circuit or course (for example “Road America” or “Local Autocross”), and each track contains one or more Corners.

Defining tracks and corners is optional but strongly recommended. When a track is selected, the app can:
• Detect visits to each corner
• Align data around the apex of that corner
• Let you compare different laps at the same corner
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Track Setup Screen",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
Open the Track Setup screen from the navigation drawer or main menu.

From here you can:
• Select an existing track to manage
• Add a new track
• Edit or delete existing tracks
• Access tools like “Teach Corners” or “Create from Coordinates”
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Creating a New Track",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
When you choose “Add New Track”, you will:
1. Enter a track name (for example “Blackhawk Farms – CW”).
2. Optionally set basic details like notes or layout variants.
3. Define corners using one of two methods:

   • Teach Corners:
     – Drive the circuit with your phone running in the car.
     – Use the Teach Corners tool to mark each corner while you drive.
     – The app records GPS positions for each corner as you tap.

   • Create from Coordinates:
     – If you already know the latitude/longitude for each corner,
       enter them manually.
     – This method is useful if you build tracks from maps or data.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Corners & Visits",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
Each corner has:
• A name (for example “Turn 1”, “Carousel”, “Bus Stop”)
• A GPS location
• A “trigger radius” around that location

When you drive with a track selected, the app monitors your GPS position. Every time you pass through a corner’s trigger radius, it records a Visit to that corner. Later, in the Event Viewer, you can:
• See how many visits you made to each corner
• Compare G-force traces lap by lap
• Align visits around the apex to compare braking and throttle
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Selecting a Track for Driving",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
Before recording a session, open the Dynamic G-Force Map and tap the Track row.

• Choose a track from the Track Picker.
• The selected track is shown at the top of the G-Force Map.
• All recorded events will be tagged with this track and its corners.

If no track is selected, the app can still record raw G-force and GPS data, but it will not group samples into corners or visits.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Editing Tracks and Corners",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = """
From the Track Manager you can:
• Rename a track
• Add, rename, or delete corners
• Adjust corner order or positions if GPS was slightly off
• Remove old tracks you no longer use

Any changes to a track affect how future events interpret corner visits. Existing events keep their original data and can be recomputed if needed.
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
• Use clear names for tracks and corners so event review is easier.
• For complex circuits, you may want separate tracks for different layouts.
• After teaching corners once, you can reuse the same track for future events at that circuit.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
