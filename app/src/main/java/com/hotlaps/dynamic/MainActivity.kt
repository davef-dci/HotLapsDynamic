package com.hotlaps.dynamic

import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.ui.AddNewTrackScreen
import com.hotlaps.dynamic.ui.CreateTrackFromCoordinatesScreen
import com.hotlaps.dynamic.ui.DisclaimerScreen
import com.hotlaps.dynamic.ui.EditTrackScreen
import com.hotlaps.dynamic.ui.EventManagerScreen
import com.hotlaps.dynamic.ui.EventViewerScreen
import com.hotlaps.dynamic.ui.GGScreen
import com.hotlaps.dynamic.ui.HelpAboutScreen
import com.hotlaps.dynamic.ui.MainMenuDynamics
import com.hotlaps.dynamic.ui.SplashDynamics
import com.hotlaps.dynamic.ui.TeachCornersScreen
import com.hotlaps.dynamic.ui.TrackAndCornerSetupScreen
import com.hotlaps.dynamic.ui.TrackManagerScreen
import com.hotlaps.dynamic.ui.calibration.CalibrateScreen
import com.hotlaps.dynamic.ui.help.HelpQuickStartScreen
import com.hotlaps.dynamic.ui.help.HelpScreen
import com.hotlaps.dynamic.ui.help.UnderstandingCalibrationScreen
import com.hotlaps.dynamic.ui.settings.SettingsScreen
import com.hotlaps.dynamic.viewmodel.DriveViewModel
import com.hotlaps.dynamic.viewmodel.TrackSelectionViewModel
import kotlinx.coroutines.launch
import com.hotlaps.dynamic.ui.TrackPickerScreen
import com.hotlaps.dynamic.ui.help.HelpGForceMapScreen
import com.hotlaps.dynamic.ui.help.HelpTracksScreen
import com.hotlaps.dynamic.ui.help.HelpEventsScreen
import com.hotlaps.dynamic.ui.help.HelpSettingsScreen


// ---------------------------------------------------------------------
// Reusable drawer navigation item with bigger hit area & larger text
// ---------------------------------------------------------------------
@Composable
fun DrawerNavItem(
    label: String,
    iconResId: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 4.dp),  // bigger tap target
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconResId),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp)   // bigger icon for in-car use
        )

        Spacer(Modifier.width(20.dp))

        Text(
            text = label,
            fontSize = 22.sp,                // larger text for readability
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for GPS permission if not granted
        if (
            ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
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
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    val nav = rememberNavController()
                    val context = LocalContext.current


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

                                // G-Force Map
                                DrawerNavItem(
                                    label = "G-Force Map",
                                    iconResId = R.drawable.ggmap
                                ) {
                                    scope.launch {
                                        drawerState.close()
                                        nav.navigate("drive") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                                // Tracks
                                DrawerNavItem(
                                    label = "Tracks",
                                    iconResId = R.drawable.track
                                ) {
                                    scope.launch {
                                        drawerState.close()
                                        nav.navigate("trackSetup") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                                // Events
                                DrawerNavItem(
                                    label = "Events",
                                    iconResId = R.drawable.event
                                ) {
                                    scope.launch {
                                        drawerState.close()
                                        nav.navigate("eventManager") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                                // Calibrate (shortened label, bigger tap target)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                nav.navigate("calib") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 14.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Calibrate",
                                        fontSize = 22.sp,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // Divider between primary nav and help
                                Divider(
                                    modifier = Modifier
                                        .padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )

                                // Help (also larger)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                nav.navigate("help") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                        .padding(vertical = 14.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Help and About",
                                        fontSize = 22.sp,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                // Send Feedback
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                drawerState.close()
                                                // Launch email feedback
                                                context.sendFeedbackEmail(currentScreen = "Main Drawer")
                                            }
                                        }
                                        .padding(vertical = 14.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Send Feedback",
                                        fontSize = 22.sp,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }



                            }
                        }
                    ) {
                        // Create ONE shared ViewModel for the whole app
                        val trackSelectionViewModel: TrackSelectionViewModel = viewModel()

                        // ViewModel that manages event recording & GPS/corner logic
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
                                MainMenuDynamics(
                                    onOpenDrawer = {
                                        scope.launch {
                                            drawerState.open()
                                        }
                                    },
                                    onDrive = { nav.navigate("drive") },
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
                                    onSelectTrack = { nav.navigate("pickTrack") },
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
                                    onBack = { nav.popBackStack() },
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
                                        nav.navigate("createTrackFromCoordinates")
                                    },
                                    onTeachCorners = {
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
                                    onBack = { nav.popBackStack() },
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
                                SettingsScreen(
                                    onBack = { nav.popBackStack() },
                                    navController = nav
                                )
                            }


                            composable("trackManager") {
                                TrackManagerScreen(
                                    trackSelectionViewModel = trackSelectionViewModel,
                                    onBack = { nav.popBackStack() },
                                    onUseTrack = { _: Track ->
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

                            composable("pickTrack") {
                                TrackPickerScreen(
                                    trackSelectionViewModel = trackSelectionViewModel,
                                    onBack = { nav.popBackStack() },
                                    onTrackChosen = {
                                        // After selecting a track, just go back to GGScreen
                                        nav.popBackStack()
                                    }
                                )
                            }


                            composable("editTrack") {
                                val trackToEdit = trackSelectionViewModel.trackBeingEdited

                                if (trackToEdit == null) {
                                    Text("No track selected for editing.")
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
                                HelpAboutScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }

                            composable("helpTracks") {
                                HelpTracksScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }

                            composable("helpEvents") {
                                HelpEventsScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }

                            composable("helpSettings") {
                                HelpSettingsScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }




                            composable("help") {
                                HelpScreen(
                                    onBack = { nav.popBackStack() },

                                    onQuickStartClick = {
                                        nav.navigate("helpQuickStart") {
                                            launchSingleTop = true
                                        }
                                    },

                                    onCalibrationClick = {
                                        nav.navigate("helpCalibration") {
                                            launchSingleTop = true
                                        }
                                    },




                                    onAboutClick = {
                                        nav.navigate("helpAbout") {
                                            launchSingleTop = true
                                        }
                                    },

                                    onGForceMapClick = {
                                        nav.navigate("helpGForceMap") {
                                            launchSingleTop = true
                                        }
                                    },

                                            onTracksClick = {
                                        nav.navigate("helpTracks") {
                                            launchSingleTop = true
                                        }
                                    },

                                    onEventsClick = {
                                        nav.navigate("helpEvents") {
                                            launchSingleTop = true
                                        }
                                    },

                                    onSettingsClick = {
                                        nav.navigate("helpSettings") {
                                            launchSingleTop = true
                                        }
                                    }

                                )
                            }




                            composable("helpQuickStart") {
                                HelpQuickStartScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }

                            composable("helpCalibration") {
                                UnderstandingCalibrationScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }

                            composable("helpGForceMap") {
                                HelpGForceMapScreen(
                                    onBack = { nav.popBackStack() }
                                )
                            }

                            composable("helpSettings_trailBrake") {
                                HelpSettingsScreen(
                                    onBack = { nav.popBackStack() },
                                    highlight = "trailBrake"
                                )
                            }




                        }
                    }
                }
            }
        }
    }
}
