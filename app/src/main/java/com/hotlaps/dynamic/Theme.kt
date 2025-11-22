package com.hotlaps.dynamic

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color



private val DarkColorScheme = darkColorScheme(
    // Main accent
    primary = AccentLime,
    onPrimary = Color.Black,

    // Background + text
    background = DarkBackground,
    onBackground = Color.White,

    // Surfaces (cards, panels, etc.)
    surface = DarkSurface,
    onSurface = Color.White,

    // Record / error color
    error = RecordRed,
    onError = Color.White,

    // Still keep these in case something references them
    secondary = PurpleGrey80,
    tertiary = Pink80,
)


private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
    /* ... */
)



@Composable
fun HotLapsDynamicTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme =
        if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
