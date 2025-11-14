package com.hotlaps.dynamic.util

import kotlin.math.*

/**
 * Geographic utilities for lat/lon.
 */
object GeoUtils {

    private const val EARTH_RADIUS_M = 6371000.0  // mean Earth radius in meters

    /**
     * Compute great-circle distance between two WGS84 points using the
     * Haversine formula.
     *
     * @param lat1Deg latitude of point 1 in degrees
     * @param lon1Deg longitude of point 1 in degrees
     * @param lat2Deg latitude of point 2 in degrees
     * @param lon2Deg longitude of point 2 in degrees
     * @return distance in meters
     */
    fun haversineMeters(
        lat1Deg: Double,
        lon1Deg: Double,
        lat2Deg: Double,
        lon2Deg: Double
    ): Double {
        val lat1 = Math.toRadians(lat1Deg)
        val lon1 = Math.toRadians(lon1Deg)
        val lat2 = Math.toRadians(lat2Deg)
        val lon2 = Math.toRadians(lon2Deg)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2).pow(2.0) +
                cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2.0)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_M * c
    }
}
