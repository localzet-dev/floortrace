package com.localzet.floortrace.positioning.rtt

import com.localzet.floortrace.data.model.GeoPoint
import com.localzet.floortrace.geo.GeoMath
import kotlin.math.hypot
import kotlin.math.sqrt

data class RttAnchor(
    val bssid: String,
    val lat: Double,
    val lon: Double,
    val floor: Int? = null,
    val room: String? = null,
)

data class RttRange(
    val anchor: RttAnchor,
    val distanceMeters: Double,
    val stdDevMeters: Double = 1.0,
)

data class RttSolution(
    val point: GeoPoint,
    val accuracyMeters: Float,
    val anchorsUsed: Int,
)

object RttTrilateration {
    /**
     * Linearized least-squares trilateration in a local tangent plane.
     * Three or more surveyed AP positions are required.
     */
    fun solve(ranges: List<RttRange>): RttSolution? {
        val valid = ranges
            .filter { it.distanceMeters in 0.1..80.0 }
            .sortedBy { it.stdDevMeters }
            .take(12)
        if (valid.size < 3) return null

        val origin = valid.first().anchor
        data class Local(val x: Double, val y: Double, val r: Double, val weight: Double)
        val local = valid.map { sample ->
            val north = GeoMath.distanceMeters(origin.lat, origin.lon, sample.anchor.lat, origin.lon) *
                if (sample.anchor.lat >= origin.lat) 1.0 else -1.0
            val east = GeoMath.distanceMeters(origin.lat, origin.lon, origin.lat, sample.anchor.lon) *
                if (sample.anchor.lon >= origin.lon) 1.0 else -1.0
            Local(
                x = east,
                y = north,
                r = sample.distanceMeters,
                weight = 1.0 / (sample.stdDevMeters.coerceAtLeast(0.25) * sample.stdDevMeters.coerceAtLeast(0.25)),
            )
        }

        val p0 = local.first()
        var a11 = 0.0
        var a12 = 0.0
        var a22 = 0.0
        var c1 = 0.0
        var c2 = 0.0

        for (i in 1 until local.size) {
            val p = local[i]
            val ax = 2.0 * (p.x - p0.x)
            val ay = 2.0 * (p.y - p0.y)
            val b = p0.r * p0.r - p.r * p.r - p0.x * p0.x + p.x * p.x - p0.y * p0.y + p.y * p.y
            val w = p.weight
            a11 += w * ax * ax
            a12 += w * ax * ay
            a22 += w * ay * ay
            c1 += w * ax * b
            c2 += w * ay * b
        }

        val det = a11 * a22 - a12 * a12
        if (kotlin.math.abs(det) < 1e-7) return null
        val x = (c1 * a22 - c2 * a12) / det
        val y = (a11 * c2 - a12 * c1) / det

        var weightedResidual = 0.0
        var totalWeight = 0.0
        local.forEach { p ->
            val residual = hypot(x - p.x, y - p.y) - p.r
            weightedResidual += p.weight * residual * residual
            totalWeight += p.weight
        }
        val rms = sqrt(weightedResidual / totalWeight.coerceAtLeast(1e-9))
        val geometryPenalty = (3.0 / valid.size).coerceAtLeast(0.45)
        val accuracy = (rms + geometryPenalty).coerceIn(0.6, 15.0).toFloat()
        val point = GeoMath.offsetMeters(origin.lat, origin.lon, northMeters = y, eastMeters = x)
        return RttSolution(point, accuracy, valid.size)
    }
}
