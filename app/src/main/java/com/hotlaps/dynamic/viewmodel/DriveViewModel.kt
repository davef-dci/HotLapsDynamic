package com.hotlaps.dynamic.viewmodel

import androidx.lifecycle.ViewModel
import com.hotlaps.dynamic.model.Event
import com.hotlaps.dynamic.model.EventSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import com.hotlaps.dynamic.util.GeoUtils
import com.hotlaps.dynamic.model.Track
import com.hotlaps.dynamic.data.EventStorage

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

    // NEW: Distance-based visit tracking per corner, independent of the old FSM.
    private val geoVisitStates: MutableMap<Int, GeoVisitState> = mutableMapOf()



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

    // NEW: State for distance-based "geo visits" defined purely by insideCornerTrigger (distance <= radius).
    private data class GeoVisitState(
        var isInsideRadius: Boolean = false,
        var visitCount: Int = 0,
        var lastDistanceM: Double = -1.0,
        val currentSamples: MutableList<CornerDistanceSample> = mutableListOf()
    )


    // Finalize a distance-based "geo visit":
    //  - Use ALL GPS-change-only samples while inside the radius
    //  - Find an apex time from the 4 closest distances
    //  - Use that apex to define the capture window
    //  - Retag in-memory samples and the CSV around that apex
    private fun finalizeGeoVisitForCorner(
        event: Event,
        cornerIndex: Int,
        geoVisitState: GeoVisitState
    ) {
        if (!geoVisitState.isInsideRadius) return

        if (geoVisitState.currentSamples.isEmpty()) {
            // Nothing useful for this visit; just reset state.
            geoVisitState.isInsideRadius = false
            geoVisitState.lastDistanceM = -1.0
            return
        }

        // Visit number for this corner based on the geo visit counter.
        val visitNumber = geoVisitState.visitCount

        // 1) Try to compute an apex time from ALL distance samples for this geo visit.
        val apexUtcFromDistances =
            findApexTimeUsingFourClosestSamples(geoVisitState.currentSamples)

        if (apexUtcFromDistances != null) {
            // Tag exactly ONE apex sample in memory for this corner/visit
            markApexSampleForVisit(
                event = event,
                cornerIndex = cornerIndex,
                visitNumber = visitNumber,
                apexUtcMs = apexUtcFromDistances
            )
        } else {
            Log.d(
                "ApexDetect",
                "finalizeGeoVisitForCorner: no apex time for " +
                        "event=${event.id}, corner=$cornerIndex, visit=$visitNumber"
            )
        }



        // 2) Keep the four-closest debug summary for this visit (same samples).
        logFourClosestDistanceSamplesForVisit(
            event = event,
            cornerIndex = cornerIndex,
            visitNumber = visitNumber,
            samples = geoVisitState.currentSamples
        )

        // 3) Reset geo visit state for this corner.
        geoVisitState.isInsideRadius = false
        geoVisitState.lastDistanceM = -1.0
        geoVisitState.currentSamples.clear()
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
        perCornerState.clear()

        // NEW: reset distance-based apex state as well
        geoVisitStates.clear()
        activeCornerDistanceSamples.clear()
        lastDistanceSampledM = null
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

        // NEW: also clear geo/apex state on stop, just to be safe
        geoVisitStates.clear()
        activeCornerDistanceSamples.clear()
        lastDistanceSampledM = null

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

    // Forward speed in m/s (smoothed)
    private val _speedMps = MutableStateFlow(0.0)
    val speedMps: StateFlow<Double> get() = _speedMps

    // Optionally keep the most recent raw GPS speed (for debugging if you like)
    // private val _rawSpeedMps = MutableStateFlow<Double?>(null)


    // Called when GGScreen receives a new GPS update
    fun updateGps(lat: Double, lon: Double, speedMps: Double? = null) {
        _gpsLat.value = lat
        _gpsLon.value = lon

        if (speedMps != null && speedMps >= 0.0) {
            val alpha = 0.4  // EMA smoothing factor
            val prev = _speedMps.value
            val smoothed =
                if (prev <= 0.0) speedMps
                else alpha * speedMps + (1.0 - alpha) * prev

            _speedMps.value = smoothed
        }
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


// We no longer tag every sample with corner/visit; only the apex sample
// will get cornerIndex/visitNumber/cornerName later.
        val cornerIndex = 0
        val visitNumber = 0
        val cornerNameForSample = ""



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

        // NEW: Distance-based insideCornerTrigger check (must match EventStorage.appendSample logic).
        val cornerTriggerRadiusM = _cornerTriggerRadiusM.value
        val insideByRadius =
            closestCornerIndex > 0 &&
                    distanceToClosestCornerM > 0.0 &&
                    distanceToClosestCornerM <= cornerTriggerRadiusM

// Distance-based "geo visit" tracking: each contiguous stretch where insideByRadius is true
// for a given corner is treated as its own visit.
//
// This is independent of the old FSM; we will *only* use it to find the four closest
// GPS-distance samples for each "insideCornerTrigger == Yes" window.
//
// Note: we only add a sample to the visit when the distance actually changes
// (one entry per GPS update), not for every accelerometer tick.
        if (closestCornerIndex > 0) {
            val geoState = geoVisitStates.getOrPut(closestCornerIndex) { GeoVisitState() }

            if (insideByRadius) {
                // Entering a new visit for this corner?
                if (!geoState.isInsideRadius) {
                    geoState.isInsideRadius = true
                    geoState.visitCount += 1
                    geoState.currentSamples.clear()
                    geoState.lastDistanceM = -1.0
                }

                // Only store a sample when distance changes (GPS-change-only).
                if (geoState.lastDistanceM < 0.0 ||
                    kotlin.math.abs(distanceToClosestCornerM - geoState.lastDistanceM) > 1e-6
                ) {
                    geoState.currentSamples.add(
                        CornerDistanceSample(
                            utcMs = nowUtc,
                            distanceToCornerM = distanceToClosestCornerM
                        )
                    )
                    geoState.lastDistanceM = distanceToClosestCornerM
                }
            } else {
                // We are outside the radius for this corner; if we were inside, we need to finalize that visit.
                if (geoState.isInsideRadius) {
                    val event = _currentEvent.value
                    if (event != null) {
                        finalizeGeoVisitForCorner(event, closestCornerIndex, geoState)
                    } else {
                        // No event? Just reset the state.
                        geoState.isInsideRadius = false
                        geoState.lastDistanceM = -1.0
                        geoState.currentSamples.clear()
                    }
                }
            }
        }


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
            speedMps = _speedMps.value,
            closestCornerIndex = closestCornerIndex,
            distanceToClosestCornerM = distanceToClosestCornerM,
            rawLatG = rawLat,
            rawLongG = rawLong,
            isApexSample = false
        )



        if (::appContext.isInitialized) {
            EventStorage.appendSample(
                context = appContext,
                sample = sample,
                cornerTriggerRadiusM = _cornerTriggerRadiusM.value
            )
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


            activeCornerDistanceSamples.clear()



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

    // NEW: Use exactly the 4 closest distance samples (by distance),
// then fit the quadratic using those 4 in chronological order.
    private fun findApexTimeUsingFourClosestSamples(
        samples: List<CornerDistanceSample>
    ): Long? {
        if (samples.size < 4) {
            Log.d(
                "ApexDetect",
                "findApexTimeUsingFourClosestSamples: not enough samples (${samples.size})"
            )
            return null
        }

        // 1) Take the 4 smallest distances
        val fourClosestByDistance = samples
            .sortedBy { it.distanceToCornerM }
            .take(4)

        // 2) Sort those 4 by time (chronological order)
        val fourClosestChronological = fourClosestByDistance
            .sortedBy { it.utcMs }

        // 3) Run the existing quadratic fit on JUST these 4 points
        return findApexTimeUsingCubicFit(fourClosestChronological)
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
    /**
     * Given an apex time (UTC) for a specific corner visit:
     *
     *  - Find the sample in this EVENT whose utcMs is closest to the apexUtcMs
     *  - On that ONE sample:
     *      * set cornerIndex / visitNumber
     *      * set cornerName
     *      * set isApexSample = true
     *
     *  - All other samples are left untouched (cornerIndex/visitNumber remain 0).
     */
    private fun markApexSampleForVisit(
        event: Event,
        cornerIndex: Int,
        visitNumber: Int,
        apexUtcMs: Long
    ) {
        // 1) Find the sample in this event whose utcMs is closest to apexUtcMs
        var bestIndex = -1
        var bestError = Long.MAX_VALUE

        for (i in _samples.indices) {
            val s = _samples[i]
            if (s.eventId != event.id) continue

            val err = kotlin.math.abs(s.utcMs - apexUtcMs)
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

        val original = _samples[bestIndex]

        // Compute a human-friendly corner name
        val cornerName = currentTrack
            ?.corners
            ?.firstOrNull { it.index == cornerIndex }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: "Corner $cornerIndex"

        // 2) Update only this one sample
        _samples[bestIndex] = original.copy(
            cornerIndex = cornerIndex,
            visitNumber = visitNumber,
            cornerName = cornerName,
            isApexSample = true
        )

        // 3) Also tag the corresponding row in the CSV on disk
        if (::appContext.isInitialized) {
            EventStorage.tagApexSampleInCsv(
                context = appContext,
                eventId = event.id,
                cornerIndex = cornerIndex,
                visitNumber = visitNumber,
                apexUtcMs = apexUtcMs,
                cornerName = cornerName
            )
        }

        Log.d(
            "ApexDetect",
            "Marked apex sample index=$bestIndex for corner=$cornerIndex " +
                    "visit=$visitNumber, apexUtcMs=$apexUtcMs (error=${bestError}ms)"
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


    // NEW: Log the 4 closest GPS-distance samples for a visit (by distance).
//  - Uses the *unique* GPS-change-only samples in activeCornerDistanceSamples.
//  - Writes a single summary line into apex_<eventId>.log and also to Logcat.
    private fun logFourClosestDistanceSamplesForVisit(
        event: Event,
        cornerIndex: Int,
        visitNumber: Int,
        samples: List<CornerDistanceSample>
    ) {
        if (samples.isEmpty()) {
            Log.d(
                "ApexFourClosest",
                "No distance samples for event=${event.id}, corner=$cornerIndex, visit=$visitNumber"
            )
            return
        }

        // Sort all samples by distance (ascending) and take up to 4
        val fourClosest = samples
            .sortedBy { it.distanceToCornerM }
            .take(4)

        // Log to Logcat
        val debugString = buildString {
            append("Four closest distances for event="); append(event.id)
            append(", corner="); append(cornerIndex)
            append(", visit="); append(visitNumber)
            append(" -> ")

            fourClosest.forEachIndexed { index, s ->
                if (index > 0) append(" | ")
                append("#"); append(index + 1)
                append(": utcMs="); append(s.utcMs)
                append(", distM="); append(String.format("%.2f", s.distanceToCornerM))
            }
        }

        Log.d("ApexFourClosest", debugString)

        // Also dump a single summary line into the same apex_debug log directory
        if (!::appContext.isInitialized) return

        try {
            val dir = appContext.getExternalFilesDir("apex_debug")
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                val file = File(dir, "apex_${event.id}.log")

                val line = buildString {
                    append("FOUR_CLOSEST")
                    append(", eventId="); append(event.id)
                    append(", cornerIndex="); append(cornerIndex)
                    append(", visitNumber="); append(visitNumber)

                    fourClosest.forEachIndexed { index, s ->
                        append(", sample"); append(index + 1)
                        append("_utcMs="); append(s.utcMs)
                        append(", sample"); append(index + 1)
                        append("_distanceM="); append(s.distanceToCornerM)
                    }
                }

                file.appendText(line + "\n")
            }
        } catch (e: Exception) {
            Log.e("ApexDebugWriter", "Failed to write FOUR_CLOSEST summary", e)
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
