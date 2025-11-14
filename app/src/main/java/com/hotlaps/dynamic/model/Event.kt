package com.hotlaps.dynamic.model

/**
 * High-level driving session.
 * One Event contains many EventSamples (10 Hz points).
 */
data class Event(
    val id: Long,            // unique event/session id
    val name: String,        // user-chosen name: "Road America Test – May 2025"
    val trackId: Long,       // matches Track.id
    val trackName: String,   // matches Track.name
    val createdUtcMs: Long,  // when event began
    val notes: String? = null
)

/**
 * One 10 Hz row captured during an Event.
 */
data class EventSample(
    val eventId: Long,       // links to Event.id
    val cornerIndex: Int,    // index of corner (1, 2, 3…) or 0 if unknown
    val lapNumber: Int,      // lap count
    val intervalMs: Long,    // milliseconds since event start
    val utcMs: Long,         // absolute timestamp for this sample
    val longG: Float,        // longitudinal accel (+ accel / - braking)
    val latG: Float,         // lateral accel (+ right / - left)
    val zG: Float,           // vertical accel (optional for now)
    val gSum: Float          // magnitude of combined G
)
