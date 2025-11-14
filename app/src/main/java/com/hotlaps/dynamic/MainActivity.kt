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



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val nav = rememberNavController()

                    // Create ONE shared ViewModel for the whole app
                    val trackSelectionViewModel: TrackSelectionViewModel = viewModel()

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
                            MainMenuDynamics(
                                onDrive = { nav.navigate("drive") },
                                onTrackManager = { nav.navigate("trackSetup") },
                                onEventManager = { nav.navigate("eventManager") },
                                onCalibrate = { nav.navigate("calib") },
                                onSettings = { nav.navigate("settings") }
                            )
                        }

                        composable("drive") {
                            GGScreen(
                                trackSelectionViewModel = trackSelectionViewModel
                            )
                        }



// Track menu
                        composable("trackSetup") {
                            TrackAndCornerSetupScreen(
                                onBack = { nav.popBackStack() },
                                onAddNewTrack = { nav.navigate("addTrack") },
                                onSelectExistingTrack = { nav.navigate("trackManager") },
                                onDeleteTrack = { nav.navigate("trackManager") }
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
                                    // (future work)
                                }
                            )
                        }



                    }
                }
            }
        }
    }
}
