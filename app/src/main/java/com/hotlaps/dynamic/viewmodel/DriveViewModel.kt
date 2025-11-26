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

import kotlin.math.abs

import com.hotlaps.dynamic.viewmodel.TrackSelectionViewModel
import androidx.compose.runtime.collectAsState
import com.hotlaps.dynamic.viewmodel.DriveViewModel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.lifecycle.viewModelScope
import com.hotlaps.dynamic.data.SettingsRepo
import kotlinx.coroutines.launch



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
    private var settingsRepo: SettingsRepo? = null



    fun setAppContext(context: Context) {
        appContext = context.applicationContext

        // Lazily create SettingsRepo the first time we get a Context
        if (settingsRepo == null) {
            settingsRepo = SettingsRepo(appContext)

            // Collect corner trigger radius from DataStore
            viewModelScope.launch {
                settingsRepo!!.cornerTriggerRadiusM.collect { radius ->
                    // Convert Float from settings to Double for our StateFlow
                    _cornerTriggerRadiusM.value = radius.toDouble()
                }
            }
        }
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
        private const val DEFAULT_CORNER_TRIGGER_RADIUS_M = 30.0

        // Minimum gap between visits to the *same* corner in this Event.
        // This prevents multiple “laps” being detected while still in the radius.
        private const val MIN_CORNER_GAP_MS = 5_000L  // 5 seconds for now
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


    // Corner trigger radius in meters, loaded from Settings.
// Backed by a StateFlow so UI and logic can see changes.
    private val _cornerTriggerRadiusM =
        MutableStateFlow(DEFAULT_CORNER_TRIGGER_RADIUS_M)
    val cornerTriggerRadiusM: StateFlow<Double> get() = _cornerTriggerRadiusM



    // Track associated with the current event (if any)
    private var currentTrack: Track? = null

    // NEW: start an event even if no track is selected
    fun startManualEvent(context: Context, track: Track?) {

        // Remember which track this event is associated with
        currentTrack = track

        val baseName = track?.name ?: "Untitled"

        // Create a friendly timestamp like "2025-11-18 13:42"
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val formattedTime = formatter.format(Date(now))

        // Human-friendly name
        val eventName = "$baseName - $formattedTime"


        // Let EventStorage generate the ID and timestamps
        val event = EventStorage.createEvent(
            context = context,
            name = eventName,
            trackId = track?.id ?: 0L,
            trackName = track?.name ?: ""
        )

        _currentEvent.value = event
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
        currentTrack = null

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

    private val _rawLatG = MutableStateFlow(0f)
    val rawLatG: StateFlow<Float> get() = _rawLatG

    private val _rawLongG = MutableStateFlow(0f)
    val rawLongG: StateFlow<Float> get() = _rawLongG

    // Called when GGScreen computes new G values
//  - smoothedLat / smoothedLong are for UI plots
//  - rawLat / rawLong are pre-smoothing values for logging
    fun updateGForces(
        smoothedLat: Float,
        smoothedLong: Float,
        rawLat: Float,
        rawLong: Float,
        z: Float = 0f
    ) {
        _latG.value = smoothedLat
        _longG.value = smoothedLong
        _zG.value = z

        _rawLatG.value = rawLat
        _rawLongG.value = rawLong
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

// Decide corner name for this sample:
//  - If we're in a corner (cornerIndex > 0), try to use the track's corner name.
//  - Fall back to "Corner X" if the track's name is blank or missing.
//  - If we're not in a corner (cornerIndex == 0), leave it empty.
        // Determine the correct corner name for this sample
        val cornerNameForSample: String = if (cornerIndex > 0) {
            // Look up the matching corner from the currentTrack
            val trackCorner = currentTrack
                ?.corners
                ?.firstOrNull { it.index == cornerIndex }

            // Use the track's optional human-friendly name if present
            val nameFromTrack: String? = trackCorner?.name

            if (!nameFromTrack.isNullOrBlank()) {
                nameFromTrack
            } else {
                // Fallback if no name was provided
                "Corner $cornerIndex"
            }
        } else {
            ""
        }



        val long = _longG.value
        val lat = _latG.value
        val z   = _zG.value

        val gSum = kotlin.math.sqrt(
            (long * long) +
                    (lat * lat) +
                    (z * z)
        )

        val rawLong = _rawLongG.value
        val rawLat  = _rawLatG.value



        val eventNameForSample = event.displayName.ifBlank { event.name }

        // Nearest corner at this instant (using existing helper and currentTrack)
        val nearest = findNearestCornerIndex(currentTrack)
        val closestCornerIndex = nearest?.first ?: 0
        val distanceToClosestCornerM = nearest?.second ?: 0.0


        val sample = EventSample(
            eventId = event.id,
            cornerIndex = cornerIndex,
            visitNumber = visitNumber,
            cornerName = cornerNameForSample,
            intervalMs = intervalMs,
            utcMs = nowUtc,
            longG = long,
            latG = lat,
            zG = z,
            gSum = gSum,
            trackName = event.trackName,
            eventName = eventNameForSample,
            gpsLat = gpsLat.value,
            gpsLon = gpsLon.value,
            closestCornerIndex = closestCornerIndex,
            distanceToClosestCornerM = distanceToClosestCornerM,
            rawLatG = rawLat,
            rawLongG = rawLong
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
        return distanceM <= cornerTriggerRadiusM.value
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

            // Retroactively tag samples that occurred just before the apex
            backfillPreApexSamples(
                event = event,
                cornerIndex = cornerIndex,
                visitNumber = newVisitNumber,
                startUtcMs = startUtc,
                apexUtcMs = nowUtc
            )

            // Also prepare to backfill the CSV file for these pre-apex samples
            if (::appContext.isInitialized) {
                EventStorage.backfillCornerSamplesInCsv(
                    context = appContext,
                    eventId = event.id,
                    cornerIndex = cornerIndex,
                    visitNumber = newVisitNumber,
                    startUtcMs = startUtc,
                    apexUtcMs = nowUtc
                )
            }



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

    fun renameCurrentEvent(context: Context, newName: String) {
        val current = _currentEvent.value ?: return

        val updated = current.copy(
            name = newName,
            displayName = newName
        )

        _currentEvent.value = updated

        // Also update the CSV on disk
        EventStorage.updateEventNameInCsv(context, current.id, newName)

        // NEW: rename the actual .csv file
        EventStorage.renameEventFile(context, current.id, newName)
    }




    /**
     * Expose the corner trigger radius (in meters) so the UI
     * can display it for debugging / tuning.
     */
    fun getCornerTriggerRadiusMeters(): Double {
        return cornerTriggerRadiusM.value
    }
    /**
     * Later we'll use this to retroactively tag samples that happened
     * just BEFORE we detected the apex, so they get cornerIndex/visitNumber
     * assigned correctly.
     */
    private fun backfillPreApexSamples(
        event: Event,
        cornerIndex: Int,
        visitNumber: Int,
        startUtcMs: Long,
        apexUtcMs: Long
    ) {
        var taggedCount = 0

        // Walk backwards through _samples and tag any rows in [startUtcMs, apexUtcMs]
        // for THIS event that don't already belong to a corner.
        //
        // We go backwards so we can bail out early once we pass the startUtcMs.
        for (i in _samples.indices.reversed()) {
            val s = _samples[i]

            // Only touch samples from this event
            if (s.eventId != event.id) continue

            // If this sample is older than the start of the window, we can stop.
            if (s.utcMs < startUtcMs) break

            // Only interested in samples before (or at) apex
            if (s.utcMs <= apexUtcMs) {
                // Don't overwrite any sample that already has a corner tag
                if (s.cornerIndex == 0 && s.visitNumber == 0) {
                    _samples[i] = s.copy(
                        cornerIndex = cornerIndex,
                        visitNumber = visitNumber
                    )
                    taggedCount++
                }
            }
        }

        Log.d(
            "CornerPreApex",
            "Backfilled $taggedCount pre-apex samples for corner=$cornerIndex " +
                    "visit=$visitNumber, window=[$startUtcMs .. $apexUtcMs]"
        )
    }




}
