package com.hotlaps.dynamic.data

import com.hotlaps.dynamic.model.EventSample

/**
 * Event CSV schema — single source of truth for the recorder AND the desktop
 * ApexDynamicsAnalyzer (which parses these columns by index).
 *
 * NOTE: If you add/reorder columns, update the header, the indices, and the Analyzer's
 * TelemetryCsvLoader.
 */
object EventCsvFormat {

    const val HEADER =
        "timestampMs,deltaMs,localTime,trackName,eventName," +
                "gpsLat,gpsLon,closestCornerIndex,distanceToClosestCornerM," +
                "rawLatG,rawLongG,latG,longG,gSum," +
                "speed," +
                "cornerIndex,cornerName,visitNumber,Apex\n"

    const val IDX_TIMESTAMP_MS = 0
    const val IDX_DELTA_MS = 1
    // IDX_LOCAL_TIME = 2 (stored but not parsed)
    const val IDX_TRACK_NAME = 3
    const val IDX_EVENT_NAME = 4
    const val IDX_GPS_LAT = 5
    const val IDX_GPS_LON = 6
    const val IDX_CLOSEST_CORNER_INDEX = 7
    const val IDX_DIST_TO_CLOSEST_CORNER_M = 8
    const val IDX_RAW_LAT_G = 9
    const val IDX_RAW_LONG_G = 10
    const val IDX_LAT_G = 11
    const val IDX_LONG_G = 12
    const val IDX_GSUM = 13
    const val IDX_SPEED = 14
    const val IDX_CORNER_INDEX = 15
    const val IDX_CORNER_NAME = 16
    const val IDX_VISIT_NUMBER = 17
    const val IDX_APEX = 18

    const val EXPECTED_COLS = 19

    /**
     * Free-text fields are written unquoted and both apps split on ',', so a comma
     * (or line break) in a track/event/corner name would shift every later column.
     */
    fun sanitizeField(value: String): String =
        value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ')

    /** One data row (with trailing newline). Apex is written as "1"/"0". */
    fun formatRow(sample: EventSample, localTime: String): String = buildString {
        append(sample.utcMs); append(',')
        append(sample.intervalMs); append(',')
        append(localTime); append(',')
        append(sanitizeField(sample.trackName)); append(',')
        append(sanitizeField(sample.eventName)); append(',')
        append(sample.gpsLat); append(',')
        append(sample.gpsLon); append(',')
        append(sample.closestCornerIndex); append(',')
        append(sample.distanceToClosestCornerM); append(',')
        append(sample.rawLatG); append(',')
        append(sample.rawLongG); append(',')
        append(sample.latG); append(',')
        append(sample.longG); append(',')
        append(sample.gSum); append(',')
        append(sample.speedMps?.toString() ?: ""); append(',')
        append(sample.cornerIndex); append(',')
        append(sanitizeField(sample.cornerName)); append(',')
        append(sample.visitNumber); append(',')
        append(if (sample.isApexSample) "1" else "0")
        append('\n')
    }
}
