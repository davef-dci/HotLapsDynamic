package com.hotlaps.dynamic.viewmodel

import androidx.lifecycle.ViewModel
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import com.hotlaps.dynamic.util.GeoUtils
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.data.EventStorage
import com.hotlaps.dynamic.model.CornerVisit

import android.content.Context
import android.util.Log





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

    private enum class CornerCaptureState {
        Idle,
        Capturing
    }


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

    // In-memory buffer of samples for the current Event.
// (We'll later stream these to disk / export.)
    private val _samples = mutableListOf<EventSample>()
    val samples: List<EventSample> get() = _samples

    // Corner capture state machine
    private var cornerCaptureState: CornerCaptureState = CornerCaptureState.Idle

    // Which corner we are currently capturing (track cornerIndex), or null if none
    private var activeCornerIndex: Int? = null

    // Nth visit to this corner within the current Event (1,2,3…)
    private var activeVisitNumber: Int = 0

    // Time window (UTC millis) for the currently active corner visit
    private var activeVisitStartUtcMs: Long = 0L
    private var activeVisitEndUtcMs: Long = 0L


    // Per-corner visit counters within this Event: cornerIndex -> visits so far
    private val cornerVisitCounts = mutableMapOf<Int, Int>()

    // List of all corner visits (metadata only; we’ll fill this later)
    private val cornerVisits = mutableListOf<CornerVisit>()



    fun startEvent(context: Context, track: Track) {
        val eventName = "${track.name} – ${System.currentTimeMillis()}"
        val event = EventStorage.createEvent(
            context = context,
            name = eventName,
            trackId = track.id,
            trackName = track.name
        )
        _currentEvent.value = event
        cornerCaptureState = CornerCaptureState.Idle
        activeCornerIndex = null
        activeVisitNumber = 0
        cornerVisitCounts.clear()
        cornerVisits.clear()

        Log.d("DriveViewModel", "Event started: id=${event.id}, trackId=${event.trackId}, name=${event.name}")

    }


    fun stopEvent() {
        _currentEvent.value = null
    }

    // Placeholder for receiving new samples (later)
    fun addSample(sample: EventSample) {
        // Only record if we actually have an active Event
        if (_currentEvent.value == null) return

        _samples.add(sample)
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

    fun recordCurrentSample() {
        val event = _currentEvent.value ?: return   // no active event -> do nothing

        val nowUtc = System.currentTimeMillis()
        val intervalMs = nowUtc - event.createdUtcMs

        // Default: not in any corner window
        var cornerIndex = 0
        var visitNumber = 0

        // If we're currently capturing a corner, tag this sample with that info
        if (cornerCaptureState == CornerCaptureState.Capturing &&
            activeCornerIndex != null &&
            activeVisitNumber > 0
        ) {
            cornerIndex = activeCornerIndex!!
            visitNumber = activeVisitNumber
        }

        val long = _longG.value
        val lat = _latG.value
        val z   = _zG.value

        val gSum = kotlin.math.sqrt(
            (long * long) +
                    (lat * lat) +
                    (z * z)
        )

        val sample = EventSample(
            eventId = event.id,
            cornerIndex = cornerIndex,
            visitNumber = visitNumber,
            intervalMs = intervalMs,
            utcMs = nowUtc,
            longG = long,
            latG = lat,
            zG = z,
            gSum = gSum
        )

        addSample(sample)
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




    /**
     * Given the current GPS position and a track, find the nearest corner
     * by its index (1,2,3,...).
     *
     * Returns:
     *   Pair(cornerIndex, distanceMeters)
     * or null if no track / corners / invalid GPS.
     */
    fun findNearestCornerIndex(track: Track?): Pair<Int, Double>? {
        val corners = track?.corners ?: return null
        if (corners.isEmpty()) return null

        val lat = gpsLat.value
        val lon = gpsLon.value

        // Ignore obviously-bogus GPS (still 0,0)
        if (lat == 0.0 && lon == 0.0) return null

        var bestCornerIndex: Int? = null
        var bestDistance = Double.MAX_VALUE

        for (corner in corners) {
            val d = GeoUtils.haversineMeters(
                lat,
                lon,
                corner.lat,
                corner.lon
            )

            if (d < bestDistance) {
                bestDistance = d
                bestCornerIndex = corner.index
            }
        }

        return if (bestCornerIndex != null) {
            bestCornerIndex to bestDistance
        } else {
            null
        }
    }

    /**
     * Update the corner capture state machine based on the current GPS position
     * and the given track.
     *
     * For now this only handles:
     *   - Idle  -> Capturing  when we enter a corner trigger radius
     *   - Capturing stays Capturing (we'll add exit logic later)
     */
    fun updateCornerCaptureState(track: Track?) {
        // Debug: prove this function is actually being called
        Log.d(
            "CornerFSM",
            "tick: track=${track?.name}, eventId=${_currentEvent.value?.id}, gps=(${gpsLat.value}, ${gpsLon.value})"
        )

        // If there's no active event, we don't capture anything
        val event = _currentEvent.value ?: return

        when (cornerCaptureState) {

            CornerCaptureState.Idle -> {
                // Find the nearest corner by index + distance
                val nearest = findNearestCornerIndex(track) ?: return
                val (cornerIndex, distanceM) = nearest

                // Only react if we're within the trigger radius
                if (!isWithinCornerTriggerRadius(distanceM)) {
                    return
                }

                // We've just "hit" a corner: increment visit count
                val visitsSoFar = cornerVisitCounts[cornerIndex] ?: 0
                val newVisitNumber = visitsSoFar + 1
                cornerVisitCounts[cornerIndex] = newVisitNumber

                activeCornerIndex = cornerIndex
                activeVisitNumber = newVisitNumber
                cornerCaptureState = CornerCaptureState.Capturing

                Log.d(
                    "CornerFSM",
                    "Started capturing corner=$cornerIndex visit=$newVisitNumber, distanceM=${"%.1f".format(distanceM)}"
                )

                // --- NEW: use the corner's captureBefore/captureAfter to define the window ---
                val corners = track?.corners ?: return
                val corner = corners.firstOrNull { it.index == cornerIndex } ?: return

                val nowUtc = System.currentTimeMillis()
                val beforeMs = corner.captureBeforeMs.toLong()
                val afterMs = corner.captureAfterMs.toLong()

                val startUtc = nowUtc - beforeMs
                val endUtc   = nowUtc + afterMs

                activeVisitStartUtcMs = startUtc
                activeVisitEndUtcMs   = endUtc

                val visit = CornerVisit(
                    eventId = event.id,
                    cornerIndex = cornerIndex,
                    visitNumber = newVisitNumber,
                    startUtcMs = startUtc,
                    apexUtcMs = nowUtc,
                    endUtcMs = endUtc
                )

                cornerVisits.add(visit)
            }


            CornerCaptureState.Capturing -> {
                val event = _currentEvent.value ?: return
                val activeCorner = activeCornerIndex ?: return
                val activeVisit = activeVisitNumber
                val nowUtc = System.currentTimeMillis()

                // Time-based stop: once we're past the end of this visit's window, stop capturing.
                if (nowUtc > activeVisitEndUtcMs) {

                    // Count how many samples ended up tagged for this visit (for debug only)
                    val samplesForVisit = _samples.count { sample ->
                        sample.eventId == event.id &&
                                sample.cornerIndex == activeCorner &&
                                sample.visitNumber == activeVisit
                    }

                    Log.d(
                        "CornerFSM",
                        "Stopped capturing corner=$activeCorner " +
                                "visit=$activeVisit at nowUtc=$nowUtc " +
                                "(time window end=$activeVisitEndUtcMs), " +
                                "samplesForVisit=$samplesForVisit"
                    )

                    // Reset state back to Idle
                    cornerCaptureState = CornerCaptureState.Idle
                    activeCornerIndex = null
                    activeVisitNumber = 0
                    activeVisitStartUtcMs = 0L
                    activeVisitEndUtcMs = 0L
                }
            }

        }
    }



    /**
     * Expose the corner trigger radius (in meters) so the UI
     * can display it for debugging / tuning.
     */
    fun getCornerTriggerRadiusMeters(): Double {
        return CORNER_TRIGGER_RADIUS_M
    }



}
