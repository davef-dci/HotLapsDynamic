// ui/SplashDynamics.kt
package com.hotlaps.dynamic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.hotlaps.dynamic.R

@Composable
fun SplashDynamics(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        // Show the logo "full size for several seconds"
        delay(3_000) // tweak 3–4s as you like
        onFinished()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_apex_dynamics),
            contentDescription = "Apex Dynamics",
            // Fill width to feel “full size” on tablets too
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp)
        )
    }
}
