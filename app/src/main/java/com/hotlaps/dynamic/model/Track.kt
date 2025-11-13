package com.hotlaps.dynamic.model

// Represents a single corner on a track
data class Corner(
    // Our internal corner index (0, 1, 2... or 1, 2, 3...)
    val index: Int,

    // Official corner number used by the track (if they number corners)
    val officialNumber: Int? = null,

    // Optional human name, e.g., "Carousel", "Canada Corner"
    val name: String? = null,

    // GPS location of the *apex* of the corner
    val lat: Double,
    val lon: Double,

    // Capture window around the apex, in milliseconds
    val captureBeforeMs: Int,
    val captureAfterMs: Int
)

// Represents an entire track (Road America, Blackhawk, etc.)
data class Track(
    // Unique ID for this track (we'll decide how to generate it later)
    val id: Long,

    // Track name as the user will see it
    val name: String,

    // Optional "official" name if different from the display name
    val officialName: String? = null,

    // Optional description/location, e.g. "Elkhart Lake, WI"
    val location: String? = null,

    // All of the corners that belong to this track
    val corners: List<Corner>
)
