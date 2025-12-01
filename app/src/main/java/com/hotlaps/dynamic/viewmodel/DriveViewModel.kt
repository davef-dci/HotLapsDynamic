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
import java.io.File
import com.hotlaps.dynamic.data.FileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay


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
    private var isDeskSimulationRunning: Boolean = false


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


    // For apex detection: one distance sample at a moment in time
    private data class CornerDistanceSample(
        val utcMs: Long,
        val distanceToCornerM: Double
    )


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


    // Distance-to-corner samples captured during the *current* visit
    // (we'll use these later to fit a curve and find the apex time)
    private val activeCornerDistanceSamples = mutableListOf<CornerDistanceSample>()


    // Last distance value we recorded for apex detection
    // (used to avoid logging duplicate accel-only samples where distance doesn't change)
    private var lastDistanceSampledM: Double? = null

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


    fun recordCurrentSample(
        utcMsOverride: Long? = null,
        intervalMsOverride: Long? = null
    ) {
        // If we're in desk sim mode and this call didn't come from the sim
        // (no override), ignore it so we don't mix real-time ticks with sim data.
        if (isDeskSimulationRunning && intervalMsOverride == null) {
            return
        }


        val event = _currentEvent.value ?: return   // no active event -> do nothing

        // Use override if provided, else wall-clock
        val nowUtc = utcMsOverride ?: System.currentTimeMillis()
        // If simulation passes an override, use that; otherwise use wall-clock.
        val intervalMs = intervalMsOverride ?: (nowUtc - event.createdUtcMs)

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

        // If we're currently capturing a corner, store *only GPS-change* distance samples
        // for apex detection. That means: only log a new sample when the distance changes.
        if (cornerCaptureState == CornerCaptureState.Capturing &&
            activeCornerIndex != null &&
            activeVisitNumber > 0 &&
            distanceToClosestCornerM > 0.0
        ) {
            val last = lastDistanceSampledM

            // Only add when the distance actually changes (i.e., new GPS position),
            // not for every accelerometer-only tick where distance is identical.
            if (last == null ||
                kotlin.math.abs(distanceToClosestCornerM - last) > 1e-6
            ) {
                activeCornerDistanceSamples.add(
                    CornerDistanceSample(
                        utcMs = nowUtc,
                        distanceToCornerM = distanceToClosestCornerM
                    )
                )
                lastDistanceSampledM = distanceToClosestCornerM

                // NEW: log this GPS-distance sample for offline debugging
                appendApexDebugSample(
                    event = event,
                    cornerIndex = activeCornerIndex!!,
                    visitNumber = activeVisitNumber,
                    utcMs = nowUtc,
                    distanceToCornerM = distanceToClosestCornerM
                )
            }

        }






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
            rawLongG = rawLong,
                    // New fields – for now all samples start as non-apex, no relative time
            isApexSample = false,
            timeFromApexMs = null
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
fun updateCornerCaptureState(
    track: Track?,
    utcMsOverride: Long? = null
)

{
    // If there's no active event, we don't capture anything
    val event = _currentEvent.value ?: return
    val nowUtc = utcMsOverride ?: System.currentTimeMillis()

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

            // Clear any leftover distance samples from a previous visit.
            // We'll refill this during the new visit.
            activeCornerDistanceSamples.clear()
            lastDistanceSampledM = null


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

            // NEW: starting a fresh corner visit -> reset stored distance samples
            activeCornerDistanceSamples.clear()

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

            // TODO: we'll re-enable these using the *distance-based* apex time
            // once we have it at the end of the visit.

            /*
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
            */




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

// NEW: Try to compute a candidate apex time from our distance samples
                val apexUtcFromDistances = findApexTimeUsingCubicFit(activeCornerDistanceSamples)
                // If we got a valid apex time, re-tag this visit using an apex-centered window
                if (apexUtcFromDistances != null) {

                    // Look up this corner's captureBefore/captureAfter settings
                    val cornerConfig = currentTrack
                        ?.corners
                        ?.firstOrNull { it.index == activeCorner }

                    // Fallbacks in case something is missing
                    val beforeMs = (cornerConfig?.captureBeforeMs ?: 3000).toLong()
                    val afterMs  = (cornerConfig?.captureAfterMs ?: 3000).toLong()

                    // Define the final, apex-centered window
                    val windowStartUtcMs = apexUtcFromDistances - beforeMs
                    val windowEndUtcMs   = apexUtcFromDistances + afterMs

                    // Keep these around in case other logic wants the refined window later
                    activeVisitStartUtcMs = windowStartUtcMs
                    activeVisitEndUtcMs = windowEndUtcMs

                    // 1) In-memory samples: apply the apex-centered window
                    updateCornerSamplesAroundApexWindow(
                        event = event,
                        cornerIndex = activeCorner,
                        visitNumber = activeVisit,
                        apexUtcMs = apexUtcFromDistances,
                        windowStartUtcMs = windowStartUtcMs,
                        windowEndUtcMs = windowEndUtcMs
                    )

                    // 2) CSV on disk: we'll mirror the same logic in EventStorage
                    if (::appContext.isInitialized) {
                        EventStorage.backfillCornerSamplesInCsv(
                            context = appContext,
                            eventId = event.id,
                            cornerIndex = activeCorner,
                            visitNumber = activeVisit,
                            windowStartUtcMs = windowStartUtcMs,
                            apexUtcMs = apexUtcFromDistances,
                            windowEndUtcMs = windowEndUtcMs
                        )
                    } else {
                        Log.w(
                            "ApexDetect",
                            "appContext not initialized; cannot backfill CSV for corner=$activeCorner visit=$activeVisit"
                        )
                    }
                }






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

                // NEW: done with this visit; discard its temporary distance samples
                activeCornerDistanceSamples.clear()
                lastDistanceSampledM = null

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
     * Very first pass at apex detection:
     *  - Take all distance samples for the current visit
     *  - Pick the 4 smallest distances
     *  - Sort those 4 by time (utcMs)
     *  - Return the time of the minimum-distance sample
     *
     * (We'll upgrade this later to a proper cubic curve fit.)
     */
    private fun computeApexUtcFromDistanceSamples(): Long? {
        if (activeCornerDistanceSamples.size < 4) {
            Log.d(
                "ApexDetect",
                "Not enough distance samples (${activeCornerDistanceSamples.size}) to compute apex"
            )
            return null
        }

        // Work on a sorted copy so we don't mutate the original list
        val fourClosest = activeCornerDistanceSamples
            .sortedBy { it.distanceToCornerM }   // smallest distances first
            .take(4)
            .sortedBy { it.utcMs }               // ensure chronological order

        val minSample = fourClosest.minByOrNull { it.distanceToCornerM } ?: return null

        Log.d(
            "ApexDetect",
            "Selected ${fourClosest.size} samples for apex; " +
                    "minSampleUtc=${minSample.utcMs}, " +
                    "minSampleDist=${minSample.distanceToCornerM}"
        )

        return minSample.utcMs
    }
    /**
     * Estimate apex time (utcMs) by fitting a *quadratic* to the 4 closest
     * distance samples (least-squares) and using the vertex as the minimum.
     *
     * We:
     *  - Take the 4 smallest distanceToCornerM samples for this visit
     *  - Sort them by time
     *  - Work in seconds relative to the first sample (t0 = 0)
     *  - Fit y(t) = a t^2 + b t + c by least-squares over the 4 points
     *  - Vertex is at t* = -b / (2a)
     *  - Only accept t* if:
     *      * a > 0 (true minimum)
     *      * t* is inside [t0, t3]
     *    otherwise we fall back to the discrete minimum-distance sample.
     */
    /**
     * Estimate apex time (utcMs) by fitting a *quadratic* to a local window
     * of time-adjacent distance samples around the minimum-distance point.
     *
     * Steps:
     *  - Sort all samples by time
     *  - Find the index of the global minimum distance
     *  - Build a 4-sample window: (minIndex-1, minIndex, minIndex+1, minIndex+2),
     *    clamped/expanded so we stay in-bounds and still get 4 points
     *  - Fit y(t) = a t^2 + b t + c by least-squares over those 4 points
     *  - Vertex is at t* = -b / (2a)
     *  - Only accept t* if:
     *      * a > 0 (true minimum)
     *      * t* is inside [t0, tLast] for the selected window
     *    otherwise we fall back to the discrete minimum-distance sample.
     */
    private fun findApexTimeUsingCubicFit(
        samples: List<CornerDistanceSample>
    ): Long? {
        if (samples.size < 4) {
            Log.d("ApexDetect", "Not enough samples (${samples.size}) for quadratic fit")
            return null
        }

        // 1) Sort all samples by time (utcMs)
        val byTime = samples.sortedBy { it.utcMs }

        // 2) Find the index of the smallest distance *in time order*
        val minIndex = byTime.indices.minByOrNull { idx ->
            byTime[idx].distanceToCornerM
        } ?: return null

        // 3) Build a local 4-point window around minIndex: (min-1, min, min+1, min+2)
        val lastIndex = byTime.lastIndex
        var startIdx = minIndex - 1
        var endIdx = minIndex + 2

        // Clamp into [0, lastIndex]
        if (startIdx < 0) startIdx = 0
        if (endIdx > lastIndex) endIdx = lastIndex

        // Expand as needed to ensure we have 4 points, staying in-bounds
        while ((endIdx - startIdx + 1) < 4 && (startIdx > 0 || endIdx < lastIndex)) {
            if (startIdx > 0) {
                startIdx--
            } else if (endIdx < lastIndex) {
                endIdx++
            } else {
                break
            }
        }

        val windowSize = endIdx - startIdx + 1
        if (windowSize < 4) {
            // Super-short visits / edge cases: fall back to global min sample
            val fallbackMin = byTime.minByOrNull { it.distanceToCornerM }
            Log.d(
                "ApexDetect",
                "Could not build 4-point window (size=$windowSize); " +
                        "falling back to discrete min at utc=${fallbackMin?.utcMs}"
            )
            return fallbackMin?.utcMs
        }

        val localSamples = byTime.subList(startIdx, endIdx + 1)

        // Base time so our times are small and numerically stable.
        val baseTimeMs = localSamples.first().utcMs.toDouble()
        val n = localSamples.size

        // Work in seconds relative to the first sample in the window
        val t = DoubleArray(n) { i ->
            (localSamples[i].utcMs.toDouble() - baseTimeMs) / 1000.0
        }
        val y = DoubleArray(n) { i ->
            localSamples[i].distanceToCornerM
        }

        // --- Build normal equations for least-squares quadratic fit ---
        // y(t) = a t^2 + b t + c
        //
        // Sum over i:
        //  [ Σ t^4   Σ t^3   Σ t^2 ] [a] = [Σ t^2 y]
        //  [ Σ t^3   Σ t^2   Σ t   ] [b]   [Σ t y  ]
        //  [ Σ t^2   Σ t     n     ] [c]   [Σ y    ]

        var sT  = 0.0
        var sT2 = 0.0
        var sT3 = 0.0
        var sT4 = 0.0
        var sY  = 0.0
        var sTY = 0.0
        var sT2Y = 0.0

        val nDouble = n.toDouble()

        for (i in t.indices) {
            val ti = t[i]
            val yi = y[i]
            val ti2 = ti * ti
            val ti3 = ti2 * ti
            val ti4 = ti2 * ti2

            sT  += ti
            sT2 += ti2
            sT3 += ti3
            sT4 += ti4

            sY  += yi
            sTY += ti * yi
            sT2Y += ti2 * yi
        }

        // Augmented matrix [A|b] for the 3x3 system
        val mat = arrayOf(
            doubleArrayOf(sT4, sT3, sT2, sT2Y),
            doubleArrayOf(sT3, sT2, sT,  sTY),
            doubleArrayOf(sT2, sT,  nDouble,   sY)
        )

        // Simple Gaussian elimination to solve for a, b, c.
        fun solve3x3Augmented(m: Array<DoubleArray>): Triple<Double, Double, Double>? {
            val size = 3

            // Forward elimination
            for (col in 0 until size) {
                // Pivot row
                var pivotRow = col
                for (r in col + 1 until size) {
                    if (kotlin.math.abs(m[r][col]) >
                        kotlin.math.abs(m[pivotRow][col])
                    ) {
                        pivotRow = r
                    }
                }

                val pivot = m[pivotRow][col]
                if (kotlin.math.abs(pivot) < 1e-12) {
                    // Singular / ill-conditioned -> bail
                    return null
                }

                // Swap rows if needed
                if (pivotRow != col) {
                    val tmp = m[col]
                    m[col] = m[pivotRow]
                    m[pivotRow] = tmp
                }

                // Normalize pivot row
                for (c in col until size + 1) {
                    m[col][c] /= pivot
                }

                // Eliminate this column in other rows
                for (r in 0 until size) {
                    if (r == col) continue
                    val factor = m[r][col]
                    for (c in col until size + 1) {
                        m[r][c] -= factor * m[col][c]
                    }
                }
            }

            // Now matrix is in reduced row echelon form
            val a = m[0][3]
            val b = m[1][3]
            val c = m[2][3]
            return Triple(a, b, c)
        }

        val coeffs = solve3x3Augmented(mat)
        if (coeffs == null) {
            val fallback = localSamples.minByOrNull { it.distanceToCornerM }
            Log.d("ApexDetect", "Quadratic fit failed, falling back to min sample in local window")
            return fallback?.utcMs
        }

        val (a, b, c) = coeffs
        val eps = 1e-12

        // If a ≈ 0, the curve is basically linear -> no well-defined vertex
        if (kotlin.math.abs(a) < eps) {
            val fallback = localSamples.minByOrNull { it.distanceToCornerM }
            Log.d("ApexDetect", "Quadratic fit nearly linear; falling back to min sample in local window")
            return fallback?.utcMs
        }

        // Vertex: y'(t) = 2 a t + b -> t* = -b / (2a)
        val tStar = -b / (2.0 * a)

        // We only trust a *minimum* if the parabola opens upward.
        if (a <= 0.0) {
            val fallback = localSamples.minByOrNull { it.distanceToCornerM }
            Log.d("ApexDetect", "Quadratic fit opens downward; falling back to min sample in local window")
            return fallback?.utcMs
        }

        // Require t* to lie within the span of the selected window
        val tMin = t.first()
        val tMax = t.last()
        if (tStar < tMin - 1e-6 || tStar > tMax + 1e-6) {
            val fallback = localSamples.minByOrNull { it.distanceToCornerM }
            Log.d(
                "ApexDetect",
                "Quadratic apex t*=$tStar outside [${tMin}, ${tMax}]s; falling back to min sample in local window"
            )
            return fallback?.utcMs
        }

        val apexUtcMs = baseTimeMs + tStar * 1000.0
        Log.d(
            "ApexDetect",
            "Quadratic apex (local window $startIdx..$endIdx, minIndex=$minIndex): " +
                    "a=$a b=$b c=$c, t*=$tStar s, apexUtcMs=$apexUtcMs"
        )

        return apexUtcMs.toLong()
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


    /**
     * Given an apex time (UTC) for a specific corner visit, find the sample in
     * _samples for (eventId, cornerIndex, visitNumber) whose intervalMs is
     * closest to that apex time, and:
     *
     *  - Mark exactly one sample as isApexSample = true, timeFromApexMs = 0
     *  - For all other samples in that visit, set timeFromApexMs relative to
     *    the same apexIntervalMs and isApexSample = false.
     */
    private fun markApexSampleForVisit(
        event: Event,
        cornerIndex: Int,
        visitNumber: Int,
        apexUtcMs: Long
    ) {
        val apexIntervalMs = apexUtcMs - event.createdUtcMs

        // 1) Find the sample in this visit whose intervalMs is closest to apexIntervalMs.
        var bestIndex = -1
        var bestError = Long.MAX_VALUE

        for (i in _samples.indices) {
            val s = _samples[i]
            if (s.eventId != event.id) continue
            if (s.cornerIndex != cornerIndex || s.visitNumber != visitNumber) continue

            val err = kotlin.math.abs(s.intervalMs - apexIntervalMs)
            if (err < bestError) {
                bestError = err
                bestIndex = i
            }
        }

        if (bestIndex == -1) {
            Log.w(
                "ApexDetect",
                "markApexSampleForVisit: no samples found for event=${event.id} " +
                        "corner=$cornerIndex visit=$visitNumber"
            )
            return
        }

        // 2) Second pass: update ALL samples in this visit with timeFromApexMs
        //    and flag exactly one as the apex sample.
        for (i in _samples.indices) {
            val s = _samples[i]
            if (s.eventId != event.id) continue
            if (s.cornerIndex != cornerIndex || s.visitNumber != visitNumber) continue

            val timeFromApex = s.intervalMs - apexIntervalMs
            val isApex = (i == bestIndex)

            _samples[i] = s.copy(
                isApexSample = isApex,
                timeFromApexMs = timeFromApex
            )
        }

        Log.d(
            "ApexDetect",
            "Marked apex sample index=$bestIndex for corner=$cornerIndex " +
                    "visit=$visitNumber, apexIntervalMs=$apexIntervalMs ms"
        )
    }


    /**
     * New apex-centered tagging:
     *
     * For a given (eventId, cornerIndex, visitNumber) and a given apexUtcMs:
     *
     *  - Define the window [windowStartUtcMs, windowEndUtcMs].
     *  - Any sample from this event whose utcMs lies inside that window
     *    gets:
     *       cornerIndex = cornerIndex
     *       visitNumber = visitNumber
     *       timeFromApexMs = sample.utcMs - apexUtcMs
     *    and exactly one of them is flagged isApexSample = true (the one
     *    whose utcMs is closest to apexUtcMs).
     *
     *  - Any sample that was previously tagged with this corner/visit but
     *    lies OUTSIDE the window has its corner tags cleared and
     *    apex fields reset.
     */
    private fun updateCornerSamplesAroundApexWindow(
        event: Event,
        cornerIndex: Int,
        visitNumber: Int,
        apexUtcMs: Long,
        windowStartUtcMs: Long,
        windowEndUtcMs: Long
    ) {
        if (windowEndUtcMs <= windowStartUtcMs) {
            Log.w(
                "ApexWindow",
                "updateCornerSamplesAroundApexWindow: invalid window [$windowStartUtcMs .. $windowEndUtcMs]"
            )
            return
        }

        // ---- First pass: find which sample in this window is the apex sample ----
        var bestIndex = -1
        var bestError = Long.MAX_VALUE

        for (i in _samples.indices) {
            val s = _samples[i]
            if (s.eventId != event.id) continue

            val t = s.utcMs
            if (t < windowStartUtcMs || t > windowEndUtcMs) continue

            val error = kotlin.math.abs(t - apexUtcMs)
            if (error < bestError) {
                bestError = error
                bestIndex = i
            }
        }

        if (bestIndex == -1) {
            Log.w(
                "ApexWindow",
                "No samples found in apex-centered window for " +
                        "corner=$cornerIndex visit=$visitNumber"
            )
            return
        }

        val apexSampleUtc = _samples[bestIndex].utcMs

        // ---- Second pass: write tags for samples in this window, and clear outside ones ----
        var updatedCount = 0
        for (i in _samples.indices) {
            val s = _samples[i]
            if (s.eventId != event.id) continue

            val t = s.utcMs
            val inWindow = (t in windowStartUtcMs..windowEndUtcMs)

            if (inWindow) {
                val timeFromApexMs = t - apexSampleUtc
                val isApex = (i == bestIndex)

                _samples[i] = s.copy(
                    cornerIndex = cornerIndex,
                    visitNumber = visitNumber,
                    isApexSample = isApex,
                    timeFromApexMs = timeFromApexMs
                )
                updatedCount++
            } else if (s.cornerIndex == cornerIndex && s.visitNumber == visitNumber) {
                // This sample used to belong to this visit, but is now
                // outside the apex-centered window. Clear its tags.
                _samples[i] = s.copy(
                    cornerIndex = 0,
                    visitNumber = 0,
                    isApexSample = false,
                    timeFromApexMs = null
                )
                updatedCount++
            }
        }

        Log.d(
            "ApexWindow",
            "Applied apex-centered window [$windowStartUtcMs .. $windowEndUtcMs] " +
                    "for corner=$cornerIndex visit=$visitNumber; updated $updatedCount samples"
        )
    }


    // --- Apex debug logging ----------------------------------------------------
// Writes one line per GPS-distance sample used for apex detection.
// File lives in: Android/data/com.hotlaps.dynamic/files/apex_debug/
    private fun appendApexDebugSample(
        event: Event,
        cornerIndex: Int,
        visitNumber: Int,
        utcMs: Long,
        distanceToCornerM: Double
    ) {
        // If we somehow haven't been given a Context yet, just skip logging.
        if (!::appContext.isInitialized) return

        try {
            // Put logs in an app-private external files dir so we can grab them later
            val dir = appContext.getExternalFilesDir("apex_debug")
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                // One file per event so they don't mix together
                val file = File(dir, "apex_${event.id}.log")

                val lat = _gpsLat.value
                val lon = _gpsLon.value

                // Simple, parseable CSV-ish line
                val line = buildString {
                    append("SAMPLE")
                    append(", utcMs="); append(utcMs)
                    append(", eventId="); append(event.id)
                    append(", cornerIndex="); append(cornerIndex)
                    append(", visitNumber="); append(visitNumber)
                    append(", lat="); append(lat)
                    append(", lon="); append(lon)
                    append(", distanceM="); append(distanceToCornerM)
                }

                file.appendText(line + "\n")
            }
        } catch (e: Exception) {
            Log.e("ApexDebugWriter", "Failed to write apex debug sample", e)
        }
    }
    /**
     * Debug-only: replay simulation.csv which has a truncated schema:
     *
     *   intervalMs,gpsLat,gpsLon,rawLatG,rawLongG
     *
     * This will:
     *  - create a new simulated event (if none exists)
     *  - feed GPS + Gs through the normal pipeline
     *  - let recordCurrentSample() write out a normal event CSV
     */
    fun startSimulationFromTruncatedCsv(
        context: Context,
        track: Track?,
        playbackSpeed: Double = 1.0,
        emaTauMs: Float? = null,
        maWindowSize: Int = 1
    )
 {
        val currentTrack = track
        if (currentTrack == null) {
            Log.w("DebugSim", "startSimulationFromTruncatedCsv called with null track")
            return
        }

        // Tell the VM we're in desk simulation mode (suppress 10 Hz samples)
        isDeskSimulationRunning = true

        // Start a new simulated event
        startManualEvent(context, currentTrack)

        val event = _currentEvent.value
        if (event == null) {
            Log.w("DebugSim", "No current event after startManualEvent; aborting truncated simulation")
            isDeskSimulationRunning = false
            return
        }

     // Use the same smoothing behaviour as the live app
     val simTauMs = emaTauMs
     val simMaWindow = maWindowSize.coerceAtLeast(1)
     val simSmoother = com.hotlaps.dynamic.util.GForceSmoother(
         tauMs = simTauMs,
         maWindowSize = simMaWindow
     )



     viewModelScope.launch(Dispatchers.IO) {
            try {
                val eventsDir = FileHelper.appEventsDir(context)
                if (eventsDir == null || !eventsDir.exists()) {
                    Log.w("DebugSim", "appEventsDir not available; cannot load simulation.csv")
                    return@launch
                }

                val simFile = java.io.File(eventsDir, "simulation.csv")
                if (!simFile.exists()) {
                    Log.w("DebugSim", "simulation.csv not found at ${simFile.absolutePath}")
                    return@launch
                }

                val allLines = try {
                    simFile.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                } catch (e: Exception) {
                    Log.e("DebugSim", "Error reading simulation.csv", e)
                    return@launch
                }

                if (allLines.isEmpty()) {
                    Log.w("DebugSim", "simulation.csv is empty")
                    return@launch
                }

                val dataLines = if (allLines.first().startsWith("intervalMs", ignoreCase = true)) {
                    allLines.drop(1)
                } else {
                    allLines
                }

                if (dataLines.isEmpty()) {
                    Log.w("DebugSim", "simulation.csv has no data rows")
                    return@launch
                }

                Log.d(
                    "DebugSim",
                    "Starting truncated simulation from simulation.csv with ${dataLines.size} rows " +
                            "into eventId=${event.id}, track=${currentTrack.name}, playbackSpeed=$playbackSpeed"
                )

                var lastIntervalMs: Long? = null

                for (line in dataLines) {
                    val parts = line.split(',')
                    if (parts.size < 5) {
                        Log.w("DebugSim", "Skipping malformed line in simulation.csv: '$line'")
                        continue
                    }

                    val intervalMs = parts[0].toLongOrNull()
                    val gpsLat = parts[1].toDoubleOrNull()
                    val gpsLon = parts[2].toDoubleOrNull()
                    val rawLat = parts[3].toFloatOrNull()
                    val rawLong = parts[4].toFloatOrNull()

                    if (intervalMs == null || gpsLat == null || gpsLon == null ||
                        rawLat == null || rawLong == null
                    ) {
                        Log.w("DebugSim", "Skipping line with parse error: '$line'")
                        continue
                    }

                    // Delay based on delta intervalMs (optional for playbackSpeed)
                    val delayMs: Long = if (playbackSpeed <= 0.0) {
                        0L
                    } else {
                        lastIntervalMs?.let { last ->
                            val delta = intervalMs - last
                            val scaled = (delta / playbackSpeed).toLong()
                            scaled.coerceAtLeast(1L)
                        } ?: 0L
                    }

                    if (delayMs > 0L) {
                        delay(delayMs)
                    }
                    lastIntervalMs = intervalMs

                    // Simulated UTC time for this sample (same base as your intervalMs fix)
                    val simUtc = event.createdUtcMs + intervalMs

// Run raw Gs through the same EMA + MA pipeline as live driving
                    val smoothedSample = simSmoother.addSample(
                        rawLatG = rawLat,
                        rawLongG = rawLong,
                        sampleTimeMs = simUtc
                    )

// Feed GPS into VM
                    updateGps(
                        lat = gpsLat,
                        lon = gpsLon
                    )

// Feed smoothed + raw G-forces into VM
                    updateGForces(
                        smoothedLat = smoothedSample.latG,
                        smoothedLong = smoothedSample.longG,
                        rawLat = smoothedSample.rawLatG,
                        rawLong = smoothedSample.rawLongG,
                        z = 0f
                    )


// Simulated UTC timeline: event start + intervalMs from CSV


                    // Corner FSM using simulated time
                    updateCornerCaptureState(
                        track = currentTrack,
                        utcMsOverride = simUtc
                    )

                    // Sample logging using simulated time + interval
                    recordCurrentSample(
                        utcMsOverride = simUtc,
                        intervalMsOverride = intervalMs
                    )
                }

                Log.d(
                    "DebugSim",
                    "Finished truncated simulation from simulation.csv into eventId=${event.id}"
                )

                stopEvent()
            } finally {
                // Always clear the flag even if something fails
                isDeskSimulationRunning = false
            }
        }
    }


}
