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

import com.hotlaps.dynamic.util.MovingAverage2D
import kotlin.math.abs

import com.hotlaps.dynamic.viewmodel.TrackSelectionViewModel
import androidx.compose.runtime.collectAsState
import com.hotlaps.dynamic.viewmodel.DriveViewModel




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

    private lateinit var appContext: Context

    fun setAppContext(context: Context) {
        appContext = context.applicationContext
    }


    private enum class CornerCaptureState {
        Idle,
        Capturing
    }


    // NEW: high-level recording state for the whole event
    enum class RecordingState {
        Idle,       // no event, not recording
        Recording,  // actively writing samples
        Paused      // event exists but samples are not being written
    }

    // Per-corner state for the corner detector
    private data class CornerState(
        var wasInsideRadius: Boolean = false,
        var lastVisitEndUtcMs: Long = 0L
    )



    companion object {
        // Corner trigger radius in meters.
        // Easy to tweak as we learn more from real-world testing.
        private const val CORNER_TRIGGER_RADIUS_M = 30.0

        // Minimum gap between visits to the *same* corner in this Event.
        // This prevents multiple “laps” being detected while still in the radius.
        private const val MIN_CORNER_GAP_MS = 20_000L  // 20 seconds for now
    }


    // ------------------------
    // EVENT STATE
    // ------------------------

    // Active driving event (null if not recording)
    private val _currentEvent = MutableStateFlow<Event?>(null)
    val currentEvent: StateFlow<Event?> get() = _currentEvent

    // NEW: high-level recording state
    private val _recordingState = MutableStateFlow(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> get() = _recordingState

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

// Per-corner state machine (inside/outside + last visit end)
private val perCornerState = mutableMapOf<Int, CornerState>()

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

        // NEW: we are now actively recording
        _recordingState.value = RecordingState.Recording

        cornerCaptureState = CornerCaptureState.Idle
        activeCornerIndex = null
        activeVisitNumber = 0
        cornerVisitCounts.clear()
        cornerVisits.clear()
        perCornerState.clear()   // <-- add this

        Log.d("DriveViewModel", "Event started: id=${event.id}, trackId=${event.trackId}, name=${event.name}")

    }


    // NEW: start an event even if no track is selected
    fun startManualEvent(context: Context, track: Track?) {
        val baseName = track?.name ?: "Untitled"
        val eventName = "$baseName – ${System.currentTimeMillis()}"

        val event = EventStorage.createEvent(
            context = context,
            name = eventName,
            trackId = track?.id ?: 0L,      // 0 when no track
            trackName = track?.name ?: ""   // blank when no track
        )

        _currentEvent.value = event

        // NEW: we are now actively recording
        _recordingState.value = RecordingState.Recording

        // Reset any corner-related state
        cornerCaptureState = CornerCaptureState.Idle
        activeCornerIndex = null
        activeVisitNumber = 0
        cornerVisitCounts.clear()
        cornerVisits.clear()
        perCornerState.clear()
    }



    fun stopEvent() {
    _currentEvent.value = null
    cornerCaptureState = CornerCaptureState.Idle
    // NEW: not recording anymore
    _recordingState.value = RecordingState.Idle
    activeCornerIndex = null
    activeVisitNumber = 0
    activeVisitStartUtcMs = 0L
    activeVisitEndUtcMs = 0L
    perCornerState.clear()
}

    fun pauseRecording() {
        if (_recordingState.value == RecordingState.Recording) {
            _recordingState.value = RecordingState.Paused
        }
    }

    fun resumeRecording() {
        if (_recordingState.value == RecordingState.Paused) {
            _recordingState.value = RecordingState.Recording
        }
    }




    // Placeholder for receiving new samples (later)
    fun addSample(sample: EventSample) {
        // Only record if we actually have an active Event
        if (_currentEvent.value == null) return

        // NEW: only save when actively Recording (not Idle/Paused)
        if (_recordingState.value != RecordingState.Recording) return

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
            trackName = event.trackName,   // NEW: propagate track name into each row
            cornerIndex = cornerIndex,
            visitNumber = visitNumber,
            intervalMs = intervalMs,
            utcMs = nowUtc,
            longG = long,
            latG = lat,
            zG = z,
            gSum = gSum
        )

        if (::appContext.isInitialized) {
            EventStorage.appendSample(appContext, sample)
        } else {
            Log.w("DriveViewModel", "appendSample: appContext not initialized yet")
        }

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
/**
 * Update the corner capture state machine based on the current GPS position
 * and the given track.
 *
 * New behavior:
 *  - A visit starts only when we ENTER a corner's trigger radius
 *    (outside -> inside transition for that corner).
 *  - After a visit ends, we require MIN_CORNER_GAP_MS before that corner
 *    can trigger again. This works even for tracks with a single corner.
 */
fun updateCornerCaptureState(track: Track?) {
    // If there's no active event, we don't capture anything
    val event = _currentEvent.value ?: return
    val nowUtc = System.currentTimeMillis()

    when (cornerCaptureState) {

        CornerCaptureState.Idle -> {
            // Find nearest corner (index + distance)
            val nearest = findNearestCornerIndex(track) ?: run {
                // No valid GPS / no corners: mark all as "outside"
                perCornerState.values.forEach { it.wasInsideRadius = false }
                return
            }

            val (cornerIndex, distanceM) = nearest
            val insideNow = isWithinCornerTriggerRadius(distanceM)

            // Update / create state for this corner
            val state = perCornerState.getOrPut(cornerIndex) { CornerState() }

            // True only on OUTSIDE -> INSIDE transition
            val enteringNow = insideNow && !state.wasInsideRadius

            // Enough time since the last visit to this corner?
            val enoughGap =
                (nowUtc - state.lastVisitEndUtcMs) >= MIN_CORNER_GAP_MS

            // Remember current inside/outside state for next tick
            state.wasInsideRadius = insideNow

            if (!enteringNow || !enoughGap) {
                // Either we're not entering, or it's too soon after the last visit
                return
            }

            // --- We are ENTERING the radius for this corner, after a sufficient gap. ---
            val visitsSoFar = cornerVisitCounts[cornerIndex] ?: 0
            val newVisitNumber = visitsSoFar + 1
            cornerVisitCounts[cornerIndex] = newVisitNumber

            activeCornerIndex = cornerIndex
            activeVisitNumber = newVisitNumber
            cornerCaptureState = CornerCaptureState.Capturing

            Log.d(
                "CornerFSM",
                "Started capturing corner=$cornerIndex visit=$newVisitNumber, " +
                        "distanceM=${"%.1f".format(distanceM)}, nowUtc=$nowUtc"
            )

            // Use this corner's captureBefore / captureAfter to define the window
            val corners = track?.corners ?: return
            val corner = corners.firstOrNull { it.index == cornerIndex } ?: return

            val beforeMs = corner.captureBeforeMs.toLong()
            val afterMs = corner.captureAfterMs.toLong()

            val startUtc = nowUtc - beforeMs
            val endUtc = nowUtc + afterMs

            activeVisitStartUtcMs = startUtc
            activeVisitEndUtcMs = endUtc

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
            val activeCorner = activeCornerIndex ?: return
            val activeVisit = activeVisitNumber

            // Time-based stop: once we're past the end of this visit's window, stop capturing.
            if (nowUtc > activeVisitEndUtcMs) {

                val samplesForVisit = _samples.count { sample ->
                    sample.eventId == event.id &&
                            sample.cornerIndex == activeCorner &&
                            sample.visitNumber == activeVisit
                }

                Log.d(
                    "CornerFSM",
                    "Stopped capturing corner=$activeCorner visit=$activeVisit at nowUtc=$nowUtc " +
                            "(window end=$activeVisitEndUtcMs), samplesForVisit=$samplesForVisit"
                )

                // Mark the end time for this corner so we enforce the gap
                val state = perCornerState.getOrPut(activeCorner) { CornerState() }
                state.lastVisitEndUtcMs = nowUtc
                state.wasInsideRadius = false

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
