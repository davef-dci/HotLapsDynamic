package com.hotlaps.dynamic.model

/**
 * One driving session ("event"), similar to RaceCapture:
 * Automatically created when the driver enters Drive with a track selected.
 *
 * One Event will contain many EventSamples (10 Hz points) for that session.
 */
data class Event(
    val id: Long,            // unique event/session id
    val name: String,        // e.g. "Road America Test – Nov 2025"
    val trackId: Long,       // matches Track.id
    val trackName: String,   // matches Track.name
    val startTime: Long,
    val displayName: String = "",      // human-friendly name for the session
    val createdUtcMs: Long,  // when event began (UTC millis)
    val notes: String? = null
)

/**
 * One 10 Hz row captured during an Event.
 *
 * cornerIndex / visitNumber:
 *  - 0 when we are NOT inside any corner's capture window
 *  - >0 while we are in a corner window:
 *       cornerIndex = 1,2,3,... for that track's corners
 *       visitNumber = 1,2,3,... Nth time through that corner within this Event
 */
data class EventSample(
    val eventId: Long,

    // Corner annotation (0 means "not in a corner window")
    val cornerIndex: Int = 0,
    val visitNumber: Int = 0,

    // NEW: optional human-friendly name for this corner
    val cornerName: String = "",

    // Time
    val intervalMs: Long,
    val utcMs: Long,

    // Accelerations
    val longG: Float,
    val latG: Float,
    val zG: Float,
    val gSum: Float,

    // NEW: track name
    val trackName: String = "",
    val eventName: String = "",

    // NEW: where we were when this sample was taken
    val gpsLat: Double = 0.0,
    val gpsLon: Double = 0.0,

    // Nearest corner at this instant (by index and distance)
    val closestCornerIndex: Int = 0,
    val distanceToClosestCornerM: Double = 0.0


)



// ... Event and EventSample stay as you have them ...

/**
 * One pass ("visit") through a specific corner within a specific Event.
 *
 * This does NOT store samples itself. Instead, it records:
 *  - which event
 *  - which corner
 *  - which visit number
 *  - the time window that defines this corner attack
 *
 * Later we will:
 *  - use startUtcMs/endUtcMs to find the EventSamples for this visit
 *  - or match on (eventId, cornerIndex, visitNumber)
 */
data class CornerVisit(
    val eventId: Long,      // the Event this visit belongs to
    val cornerIndex: Int,   // 1, 2, 3... index of the corner on the track
    val visitNumber: Int,   // 1, 2, 3... Nth time through this corner in this Event

    val startUtcMs: Long,   // start of capture window for this visit (before apex)
    val apexUtcMs: Long,    // apex instant
    val endUtcMs: Long      // end of capture window (after apex)
)

