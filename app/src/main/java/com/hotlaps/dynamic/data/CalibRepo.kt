// app/src/main/java/com/hotlaps/dynamic/data/CalibRepo.kt
package com.hotlaps.dynamic.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.calibDataStore by preferencesDataStore(name = "calibration")

data class CalibState(
    val vec: FloatArray?,     // forward axis in the phone frame; null = not calibrated
    val savedAtEpochMs: Long?, // when it was saved
    /** CalibrationMath.SOURCE_PRESET / SOURCE_MEASURED; null for calibrations saved before this existed. */
    val source: String? = null,
    /** Preset label, or null for a measured calibration. */
    val label: String? = null,
    /** Gravity direction (phone frame) when saved: lets the app notice the phone was moved. */
    val gravity: FloatArray? = null,
    /** User asked to recalibrate on the next drive. */
    val armedManually: Boolean = false
) {
    /** The preset in use, also for calibrations saved before labels existed. */
    val preset: CalibrationMath.Preset? get() = CalibrationMath.matchPreset(vec)

    /** One line for status displays, e.g. "Preset: Back of phone faces forward". */
    val summary: String
        get() = when {
            vec == null -> "Not calibrated"
            source == CalibrationMath.SOURCE_AUTO -> "Automatic (straight-line pull, old method)"
            preset != null -> "Set by hand: ${preset!!.label}"
            else -> "Measured (straight-line pull)"
        }
}

class CalibRepo(private val context: Context) {
    private object K {
        val X = floatPreferencesKey("calib_x")
        val Y = floatPreferencesKey("calib_y")
        val Z = floatPreferencesKey("calib_z")
        val T = longPreferencesKey("calib_saved_at")
        val SOURCE = stringPreferencesKey("calib_source")
        val LABEL = stringPreferencesKey("calib_label")
        val GX = floatPreferencesKey("calib_gravity_x")
        val GY = floatPreferencesKey("calib_gravity_y")
        val GZ = floatPreferencesKey("calib_gravity_z")
        val ARMED = booleanPreferencesKey("calib_armed")
    }

    val state: Flow<CalibState> = context.calibDataStore.data.map { p ->
        val has = p[K.X] != null && p[K.Y] != null && p[K.Z] != null
        val hasGravity = p[K.GX] != null && p[K.GY] != null && p[K.GZ] != null
        CalibState(
            vec = if (has) floatArrayOf(p[K.X]!!, p[K.Y]!!, p[K.Z]!!) else null,
            savedAtEpochMs = p[K.T],
            source = p[K.SOURCE],
            label = p[K.LABEL],
            gravity = if (hasGravity) floatArrayOf(p[K.GX]!!, p[K.GY]!!, p[K.GZ]!!) else null,
            armedManually = p[K.ARMED] == true
        )
    }

    /**
     * @param source CalibrationMath.SOURCE_PRESET or SOURCE_MEASURED
     * @param gravity gravity direction at save time (phone frame), for mount-change detection
     */
    suspend fun save(
        vec: FloatArray,
        source: String,
        label: String? = null,
        gravity: FloatArray? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        require(vec.size == 3)
        context.calibDataStore.edit { p ->
            p[K.X] = vec[0]; p[K.Y] = vec[1]; p[K.Z] = vec[2]
            p[K.T] = nowMs
            p[K.SOURCE] = source
            p.remove(K.ARMED)
            if (label != null) p[K.LABEL] = label else p.remove(K.LABEL)
            if (gravity != null && gravity.size == 3) {
                p[K.GX] = gravity[0]; p[K.GY] = gravity[1]; p[K.GZ] = gravity[2]
            } else {
                p.remove(K.GX); p.remove(K.GY); p.remove(K.GZ)
            }
        }
    }

    suspend fun clear() {
        context.calibDataStore.edit { it.clear() }
    }
}
