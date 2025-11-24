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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Divider
import androidx.compose.foundation.background
import com.hotlaps.dynamic.ui.DisclaimerScreen
import com.hotlaps.dynamic.ui.TeachCornersScreen
import com.hotlaps.dynamic.ui.HelpAboutScreen



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

                    val nav = rememberNavController()

                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            Column(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface)
                                    .statusBarsPadding()
                                    .padding(24.dp)
                            ) {

                                Text(
                                    text = "NAVIGATE",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                                )
                                Divider()
                                Spacer(modifier = Modifier.height(12.dp))


                                Text(
                                    text = "G-Force Map",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                nav.navigate("drive") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp)

                                )

                                Text(
                                    text = "Tracks",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                nav.navigate("trackSetup") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp)

                                )

                                Text(
                                    text = "Events",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                nav.navigate("eventManager") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp)

                                )
                                Text(
                                    text = "Calibrate accelerometers",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                nav.navigate("calib") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp)
                                )

                                // --- NEW DIVIDER ---
                                Divider(
                                    modifier = Modifier
                                        .padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )

                                Text(
                                    text = "Help & About",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                nav.navigate("helpAbout") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp)
                                )



                            }
                        }

                    ) {


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
                                        nav.navigate("disclaimer") {
                                            popUpTo("splash") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable("disclaimer") {
                                DisclaimerScreen(
                                    onAccept = {
                                        nav.navigate("drive") {
                                            popUpTo("disclaimer") { inclusive = true }
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
                                    onSelectTrack = { nav.navigate("trackManager") },
                                    onOpenDrawer = {
                                        scope.launch {
                                            drawerState.open()
                                        }
                                    },
                                    onOpenSettings = {
                                        scope.launch {
                                            drawerState.close()
                                            nav.navigate("settings") {
                                                launchSingleTop = true
                                            }
                                        }
                                    },

                                    onOpenCalibrate = {
                                        scope.launch {
                                            drawerState.close()
                                            nav.navigate("calib") {
                                                launchSingleTop = true
                                            }
                                        }
                                    }

                                )
                            }



// Track menu
                            composable("trackSetup") {
                                TrackAndCornerSetupScreen(
                                    onBack = { nav.popBackStack() },   // still here, even though we’re not using it now
                                    onAddNewTrack = { nav.navigate("addTrack") },
                                    onManageTracks = { nav.navigate("trackManager") },
                                    onOpenDrawer = {
                                        scope.launch {
                                            drawerState.open()
                                        }
                                    },
                                    onOpenSettings = {
                                        scope.launch {
                                            drawerState.close()
                                            nav.navigate("settings") {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                )
                            }




                            composable("addTrack") {
                                AddNewTrackScreen(
                                    onBack = { nav.popBackStack() },
                                    onCreateFromCoordinates = {
                                        // Navigate to our screen where we enter coords manually
                                        nav.navigate("createTrackFromCoordinates")
                                    },
                                    onTeachCorners = {
                                        // 🚗 New: navigate to our Teach Corners screen (to be created next)
                                        nav.navigate("teachCorners")
                                    }
                                )
                            }

                            composable("createTrackFromCoordinates") {
                                CreateTrackFromCoordinatesScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }

                            composable("teachCorners") {
                                TeachCornersScreen(
                                    onBack = { nav.popBackStack() },
                                    driveViewModel = driveViewModel
                                )
                            }




                            composable("eventManager") {
                                EventManagerScreen(
                                    onBack = { nav.popBackStack() },  // still here if we decide to use it later
                                    onViewEvents = { nav.navigate("eventViewer") },
                                    onOpenDrawer = {
                                        scope.launch {
                                            drawerState.open()
                                        }
                                    },
                                    onOpenSettings = {
                                        scope.launch {
                                            drawerState.close()
                                            nav.navigate("settings") {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
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
                                    trackSelectionViewModel = trackSelectionViewModel,
                                    onBack = { nav.popBackStack() },
                                    onUseTrack = { track: Track ->
                                        nav.navigate("drive")
                                    },
                                    onEditTrack = { track: Track ->
                                        trackSelectionViewModel.startEditingTrack(track)
                                        nav.navigate("editTrack")
                                    },
                                    onOpenDrawer = {
                                        scope.launch {
                                            drawerState.open()
                                        }
                                    },
                                    onOpenSettings = {
                                        scope.launch {
                                            drawerState.close()
                                            nav.navigate("settings") {
                                                launchSingleTop = true
                                            }
                                        }
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

                            composable("helpAbout") {
                                HelpAboutScreen()
                            }






                        }
                    }
                }
            }
        }
    }
}
