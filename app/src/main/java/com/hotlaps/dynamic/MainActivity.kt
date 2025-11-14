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



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val nav = rememberNavController()
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
                            GGScreen()
                        }



// Track menu
                        composable("trackSetup") {
                            TrackAndCornerSetupScreen(
                                onBack = { nav.popBackStack() },
                                onAddNewTrack = { nav.navigate("addTrack") },
                                onSelectExistingTrack = { nav.navigate("trackManager") }
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
                                onBack = { nav.popBackStack() },
                                onUseTrack = { track: Track ->
                                    // e.g. stash in ViewModel later
                                    // Log.d("TrackManager", "Selected track: ${track.name}")
                                },
                                onEditTrack = { track: Track ->
                                    // nav.navigate("editTrack/${...}") later
                                }
                            )
                        }


                    }
                }
            }
        }
    }
}
