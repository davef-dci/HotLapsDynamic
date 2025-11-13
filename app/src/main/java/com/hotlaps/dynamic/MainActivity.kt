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
import com.hotlaps.dynamic.data.CalibRepo
import com.hotlaps.dynamic.data.PrefsRepo
import com.hotlaps.dynamic.ui.settings.SettingsScreen
import com.hotlaps.dynamic.ui.GGScreen
import com.hotlaps.dynamic.ui.CornerCaptureManagerScreen
import com.hotlaps.dynamic.ui.TrackAndCornerSetupScreen
import com.hotlaps.dynamic.ui.CaptureManagementScreen

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
                                onCalibrate       = { nav.navigate("calib") },
                                onSettings        = { nav.navigate("settings") },
                                onCornerManager   = { nav.navigate("cornerManager") },
                                onGo              = { nav.navigate("go") }
                            )
                        }

                        composable("calib") {
                            CalibrateScreen(onBack = { nav.popBackStack() })
                        }

                        composable("settings") {
                            SettingsScreen(onBack = { nav.popBackStack() })
                        }

                        composable("go") {
                            GGScreen()
                        }

                        composable("cornerManager") {
                            CornerCaptureManagerScreen(
                                onBack = { nav.popBackStack() },
                                onTrackAndCornerSetup = { nav.navigate("trackSetup") },
                                onCaptures = { nav.navigate("captures") }
                            )
                        }

                        composable("trackSetup") {
                            TrackAndCornerSetupScreen(
                                onBack = { nav.popBackStack() }
                            )
                        }

                        composable("captures") {
                            CaptureManagementScreen(
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
