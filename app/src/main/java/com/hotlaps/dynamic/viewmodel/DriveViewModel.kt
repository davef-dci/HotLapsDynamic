package com.hotlaps.dynamic.viewmodel

import androidx.lifecycle.ViewModel
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import com.hotlaps.dynamic.util.GeoUtils
import com.hotlaps.dynamic.model.Track

/**
 * Holds all Drive-mode state:
 *  - Current Event (if any)
 *  - Lap/corner tracking
 *  - Buffers of recent G-samples
 *  - GPS location
 *  - Logic for corner detection (later)
 *  - Logic for writing EventSamples (later)
 */
class DriveViewModel : ViewModel() {

    companion object {
        // Corner trigger radius in meters.
        // Easy to tweak as we learn more from real-world testing.
        private const val CORNER_TRIGGER_RADIUS_M = 30.0
    }

    // ------------------------
    // EVENT STATE
    // ------------------------

    // Active driving event (null if not recording)
    private val _currentEvent = MutableStateFlow<Event?>(null)
    val currentEvent: StateFlow<Event?> get() = _currentEvent

    fun startEvent(event: Event) {
        _currentEvent.value = event
    }

    fun stopEvent() {
        _currentEvent.value = null
    }

    // Placeholder for receiving new samples (later)
    fun addSample(sample: EventSample) {
        // Will save to storage later
    }

    // ------------------------
    // GPS STATE
    // ------------------------

    private val _gpsLat = MutableStateFlow(0.0)
    val gpsLat: StateFlow<Double> get() = _gpsLat

    private val _gpsLon = MutableStateFlow(0.0)
    val gpsLon: StateFlow<Double> get() = _gpsLon

    // Called when GGScreen receives a new GPS update
    fun updateGps(lat: Double, lon: Double) {
        _gpsLat.value = lat
        _gpsLon.value = lon
    }

    // ------------------------
    // G-FORCE STATE (lat / long / z)
    // ------------------------

    private val _latG = MutableStateFlow(0f)
    val latG: StateFlow<Float> get() = _latG

    private val _longG = MutableStateFlow(0f)
    val longG: StateFlow<Float> get() = _longG

    private val _zG = MutableStateFlow(0f)
    val zG: StateFlow<Float> get() = _zG

    // Called when GGScreen computes new smoothed G values
    fun updateGForces(lat: Float, long: Float, z: Float = 0f) {
        _latG.value = lat
        _longG.value = long
        _zG.value = z
    }

    /**
     * Distance in meters from the current GPS position
     * to an arbitrary lat/lon (e.g., a corner).
     *
     * If we don't yet have a meaningful GPS fix, this will
     * still return a number, but you may choose to ignore
     * it until speed / fix quality is good.
     */
    fun distanceToLatLonMeters(
        targetLatDeg: Double,
        targetLonDeg: Double
    ): Double {
        val currLat = gpsLat.value
        val currLon = gpsLon.value

        return GeoUtils.haversineMeters(
            currLat,
            currLon,
            targetLatDeg,
            targetLonDeg
        )
    }

    /**
     * Returns true if the given distance (in meters) is within
     * our corner trigger radius.
     */
    fun isWithinCornerTriggerRadius(distanceM: Double?): Boolean {
        if (distanceM == null) return false
        return distanceM <= CORNER_TRIGGER_RADIUS_M
    }

    /**
     * Given a track and the current GPS position, find the nearest corner.
     *
     * Returns:
     *   Pair(label, distanceMeters)  OR  null if we can't compute it.
     *
     * label = corner.officialNumber if present, otherwise the corner.index.
     */
    fun computeNearestCorner(
        track: Track?,
        gpsLatDeg: Double,
        gpsLonDeg: Double
    ): Pair<String, Double>? {
        // No track? Nothing to do.
        val corners = track?.corners ?: return null
        if (corners.isEmpty()) return null

        // If GPS hasn't locked yet, (0,0) is garbage -> bail out.
        if (gpsLatDeg == 0.0 && gpsLonDeg == 0.0) return null

        var bestLabel: String? = null
        var bestDistance = Double.MAX_VALUE

        for (corner in corners) {
            val d = GeoUtils.haversineMeters(
                gpsLatDeg,
                gpsLonDeg,
                corner.lat,
                corner.lon
            )

            if (d < bestDistance) {
                bestDistance = d
                // Prefer officialNumber, otherwise index
                bestLabel = corner.officialNumber?.toString() ?: corner.index.toString()
            }
        }

        return if (bestLabel != null) {
            bestLabel to bestDistance
        } else {
            null
        }
    }



}
