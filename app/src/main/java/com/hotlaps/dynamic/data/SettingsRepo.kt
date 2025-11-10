// app/src/main/java/com/hotlaps/dynamic/data/SettingsRepo.kt
package com.hotlaps.dynamic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepo(private val context: Context) {

    // --- Keys
    private object K {
        // 1) Trail Brake Threshold (G)
        val TRAIL_BRAKE_G = floatPreferencesKey("trail_brake_g")

        // 2) G-G Max scale (G)
        val GG_MAX_G = floatPreferencesKey("gg_max_g")

        // 3) G-G trail window (seconds)
        val GG_TRAIL_WINDOW_S = floatPreferencesKey("gg_trail_window_s")

        // 4) G-G update rate (Hz)
        val GG_UPDATE_RATE_HZ = intPreferencesKey("gg_update_rate_hz")
    }

    // --- Defaults (tweak as you like)
    private object D {
        const val TRAIL_BRAKE_G = 0.30f   // typical light brake threshold
        const val GG_MAX_G = 1.25f        // race car default; street ~0.625
        const val GG_TRAIL_WINDOW_S = 3.0f
        const val GG_UPDATE_RATE_HZ = 10  // 2–50 Hz supported
    }

    // --- Flows
    val trailBrakeG: Flow<Float> =
        context.settingsDataStore.data.map { it[K.TRAIL_BRAKE_G] ?: D.TRAIL_BRAKE_G }

    val ggMaxG: Flow<Float> =
        context.settingsDataStore.data.map { it[K.GG_MAX_G] ?: D.GG_MAX_G }

    val ggTrailWindowS: Flow<Float> =
        context.settingsDataStore.data.map { it[K.GG_TRAIL_WINDOW_S] ?: D.GG_TRAIL_WINDOW_S }

    val ggUpdateRateHz: Flow<Int> =
        context.settingsDataStore.data.map { it[K.GG_UPDATE_RATE_HZ] ?: D.GG_UPDATE_RATE_HZ }

    // --- Updaters
    suspend fun updateTrailBrakeG(v: Float) {
        context.settingsDataStore.edit { it[K.TRAIL_BRAKE_G] = v.coerceIn(0f, 3f) }
    }

    suspend fun updateGgMaxG(v: Float) {
        context.settingsDataStore.edit { it[K.GG_MAX_G] = v.coerceIn(0.2f, 3f) }
    }

    suspend fun updateGgTrailWindowS(v: Float) {
        context.settingsDataStore.edit { it[K.GG_TRAIL_WINDOW_S] = v.coerceIn(0.2f, 30f) }
    }

    suspend fun updateGgUpdateRateHz(v: Int) {
        context.settingsDataStore.edit { it[K.GG_UPDATE_RATE_HZ] = v.coerceIn(2, 50) }
    }
}
