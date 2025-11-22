package com.hotlaps.dynamic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hotlaps.dynamic.ui.MainMenuDynamics
import com.hotlaps.dynamic.ui.SplashDynamics
import com.hotlaps.dynamic.ui.calibration.CalibrateScreen
import com.hotlaps.dynamic.ui.settings.SettingsScreen
import com.hotlaps.dynamic.ui.GGScreen
import com.hotlaps.dynamic.ui.TrackAndCornerSetupScreen
import com.hotlaps.dynamic.ui.AddNewTrackScreen
import com.hotlaps.dynamic.ui.EventManagerScreen
import com.hotlaps.dynamic.ui.CreateTrackFromCoordinatesScreen
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.ui.TrackManagerScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hotlaps.dynamic.viewmodel.TrackSelectionViewModel
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import com.hotlaps.dynamic.viewmodel.DriveViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import com.hotlaps.dynamic.ui.EventViewerScreen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hotlaps.dynamic.ui.EditTrackScreen
import com.hotlaps.dynamic.HotLapsDynamicTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding




class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

// Ask for GPS permission if not granted
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }


        setContent {
            HotLapsDynamicTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    // STEP 1: simple drawer state (we won't open it yet)
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val scope = rememberCoroutineScope()

                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            Column(
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(24.dp)
                            ) {

                                Text(
                                    text = "Navigate",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable { /* we will fill in the action next */ }
                                        .padding(vertical = 8.dp)
                                )

                                Text(
                                    text = "Drive",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable { /* we will fill in the action next */ }
                                        .padding(vertical = 8.dp)
                                )

                                Text(
                                    text = "Track Manager",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable { /* we will fill in the action next */ }
                                        .padding(vertical = 8.dp)
                                )

                                Text(
                                    text = "Event Manager",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable { /* we will fill in the action next */ }
                                        .padding(vertical = 8.dp)
                                )

                                Text(
                                    text = "Settings",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable { /* we will fill in the action next */ }
                                        .padding(vertical = 8.dp)
                                )
                            }
                        }

                    ) {
                        val nav = rememberNavController()

                        // Create ONE shared ViewModel for the whole app
                        val trackSelectionViewModel: TrackSelectionViewModel = viewModel()

                        // ViewModel that manages event recording & GPS/corner logic (later)
                        val driveViewModel: DriveViewModel = viewModel()

                        val context = LocalContext.current

                        LaunchedEffect(Unit) {
                            driveViewModel.setAppContext(context)
                        }


                        NavHost(navController = nav, startDestination = "splash") {

                            composable("splash") {
                                SplashDynamics(
                                    onFinished = {
                                        nav.navigate("menu") {
                                            popUpTo("splash") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable("menu") {

                                val context = LocalContext.current

                                MainMenuDynamics(
                                    onOpenDrawer = {
                                        scope.launch {
                                            drawerState.open()
                                        }
                                    },
                                    onDrive = {
                                        nav.navigate("drive")
                                    },
                                    onTrackManager = { nav.navigate("trackSetup") },
                                    onEventManager = { nav.navigate("eventManager") },
                                    onCalibrate = { nav.navigate("calib") },
                                    onSettings = { nav.navigate("settings") }
                                )
                            }



                            composable("drive") {
                                GGScreen(
                                    trackSelectionViewModel = trackSelectionViewModel,
                                    driveViewModel = driveViewModel,
                                    onSelectTrack = { nav.navigate("trackManager") }
                                )
                            }


// Track menu
                            composable("trackSetup") {
                                TrackAndCornerSetupScreen(
                                    onBack = { nav.popBackStack() },
                                    onAddNewTrack = { nav.navigate("addTrack") },
                                    onManageTracks = { nav.navigate("trackManager") }  // <- was onSelectExistingTrack / onDeleteTrack
                                )
                            }



                            composable("addTrack") {
                                AddNewTrackScreen(
                                    onBack = { nav.popBackStack() },
                                    onCreateFromCoordinates = {
                                        // Navigate to our new screen where we’ll actually enter coords
                                        nav.navigate("createTrackFromCoordinates")
                                    }
                                )
                            }

                            composable("createTrackFromCoordinates") {
                                CreateTrackFromCoordinatesScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }


                            composable("eventManager") {
                                EventManagerScreen(
                                    onBack = { nav.popBackStack() },
                                    onViewEvents = { nav.navigate("eventViewer") }
                                )
                            }


                            composable("eventViewer") {
                                EventViewerScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }



                            composable("calib") {
                                CalibrateScreen(onBack = { nav.popBackStack() })
                            }

                            composable("settings") {
                                SettingsScreen(onBack = { nav.popBackStack() })
                            }

                            composable("trackManager") {
                                TrackManagerScreen(
                                    trackSelectionViewModel = trackSelectionViewModel,   // ← NEW
                                    onBack = { nav.popBackStack() },
                                    onUseTrack = { track: Track ->
                                        nav.navigate("drive")
                                    },
                                    onEditTrack = { track: Track ->
                                        trackSelectionViewModel.startEditingTrack(track)
                                        nav.navigate("editTrack")
                                    }
                                )
                            }

                            composable("editTrack") {
                                val trackToEdit = trackSelectionViewModel.trackBeingEdited

                                if (trackToEdit == null) {
                                    // Failsafe: shouldn’t normally happen, but don’t pop again
                                    androidx.compose.material3.Text("No track selected for editing.")
                                } else {
                                    EditTrackScreen(
                                        track = trackToEdit,
                                        onBack = {
                                            trackSelectionViewModel.clearEditingTrack()
                                            nav.popBackStack()
                                        }
                                    )
                                }
                            }


                        }
                    }
                }
            }
        }
    }
}
