package com.hotlaps.dynamic.model

// A single captured data point during an event (we'll record these at ~10 Hz,
// but only while we're inside a corner's capture window).
data class EventSample(
    // Time since the *start* of this event, in milliseconds
    val tMs: Long,

    // Absolute UTC timestamp for this sample, in milliseconds since epoch
    val utcMs: Long,

    // Which corner we're currently in, if any.
    // null means "not in any corner's capture window right now".
    val cornerIndex: Int? = null,

    // Accelerations in units of G (1.0 = 1 g)
    val longAccelG: Float,     // Longitudinal accel (braking/accel)
    val latAccelG: Float,      // Lateral accel (cornering)
    val vertAccelG: Float,     // Vertical accel (bump/compression)
    val gSum: Float,           // Total accel magnitude (precomputed or derived)

    // "Last known" GPS information at this time.
    // These can be null when we haven't got a GPS fix recently.
    val lastGpsLat: Double? = null,
    val lastGpsLon: Double? = null,
    val lastSpeedMps: Float? = null // meters per second (we can display as mph/kmh)
)

// A single driving session / stint / event for a given track.
data class DrivingEvent(
    // Unique ID for this event (we'll decide how to generate it later)
    val id: Long,

    // Which track this event belongs to
    val trackId: Long,
    val trackName: String,      // duplicated so the file is self-describing

    // Absolute start time of the event in UTC, ms since epoch
    val startUtcMs: Long,

    // Optional metadata (handy for listing and comparing later)
    val driverName: String? = null,
    val carName: String? = null,
    val notes: String? = null,

    // All the samples we decided to keep for this event.
    // In our design, these will *only* be recorded when we are inside
    // a corner's capture window (before/after apex).
    val samples: List<EventSample>
)
