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

        // 5) Corner capture trigger radius (meters)
        val CORNER_TRIGGER_RADIUS_M = floatPreferencesKey("corner_trigger_radius_m")

        val SMOOTHING_LEVEL = intPreferencesKey("smoothing_level")

        // 6) Tire grip limit / Breakaway G
        val BREAKAWAY_G = floatPreferencesKey("breakaway_g")
    }

    // --- Defaults (tweak as you like)
    private object D {
        const val TRAIL_BRAKE_G = 0.10f   // typical light brake threshold
        const val GG_MAX_G = 1.25f        // race car default; street ~0.625
        const val GG_TRAIL_WINDOW_S = 3.0f
        const val GG_UPDATE_RATE_HZ = 10  // 2–50 Hz supported
        const val CORNER_TRIGGER_RADIUS_M = 30f
        const val SMOOTHING_LEVEL = 2  // 0=Off, 1=Low, 2=Medium, 3=Heavy
        const val BREAKAWAY_G = 1.00f

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

    val cornerTriggerRadiusM: Flow<Float> =
        context.settingsDataStore.data.map {
            it[K.CORNER_TRIGGER_RADIUS_M] ?: D.CORNER_TRIGGER_RADIUS_M
        }


    val smoothingLevel: Flow<Int> =
        context.settingsDataStore.data.map {
            it[K.SMOOTHING_LEVEL] ?: D.SMOOTHING_LEVEL
        }

    val breakawayG: Flow<Float> =
        context.settingsDataStore.data.map {
            it[K.BREAKAWAY_G] ?: D.BREAKAWAY_G
        }

    // --- Updaters
    suspend fun updateTrailBrakeG(v: Float) {
        context.settingsDataStore.edit { it[K.TRAIL_BRAKE_G] = v.coerceIn(0f, 3f) }
    }

    suspend fun updateGgMaxG(v: Float) {
        context.settingsDataStore.edit { it[K.GG_MAX_G] = v.coerceIn(0.1f, 3f) }
    }

    suspend fun updateGgTrailWindowS(v: Float) {
        context.settingsDataStore.edit { it[K.GG_TRAIL_WINDOW_S] = v.coerceIn(0.2f, 30f) }
    }

    suspend fun updateGgUpdateRateHz(v: Int) {
        context.settingsDataStore.edit { it[K.GG_UPDATE_RATE_HZ] = v.coerceIn(2, 50) }
    }

    suspend fun updateCornerTriggerRadiusM(v: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[K.CORNER_TRIGGER_RADIUS_M] = v.coerceIn(5f, 100f)
        }
    }

    suspend fun updateSmoothingLevel(level: Int) {
        context.settingsDataStore.edit { prefs ->
            // 0 = Off, 1 = Low, 2 = Medium, 3 = Heavy
            prefs[K.SMOOTHING_LEVEL] = level.coerceIn(0, 3)
        }
    }


    suspend fun updateBreakawayG(v: Float) {
        context.settingsDataStore.edit { prefs ->
            // Clamp to a sensible range, e.g. 0.2G–3.0G
            val clamped = v.coerceIn(0.2f, 3.0f)
            prefs[K.BREAKAWAY_G] = clamped
        }
    }

}
