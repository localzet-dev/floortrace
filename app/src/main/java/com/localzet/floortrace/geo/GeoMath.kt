package com.localzet.floortrace.geo

import com.localzet.floortrace.data.model.GeoPoint
import kotlin.math.*

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_378_137.0
    private const val WEB_MERCATOR_ORIGIN = 20_037_508.342789244

    data class WorldPixel(val x: Double, val y: Double)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    fun offsetMeters(lat: Double, lon: Double, northMeters: Double, eastMeters: Double): GeoPoint {
        val dLat = northMeters / EARTH_RADIUS_METERS
        val dLon = eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        return GeoPoint(
            lat = lat + Math.toDegrees(dLat),
            lon = lon + Math.toDegrees(dLon),
        )
    }

    fun move(lat: Double, lon: Double, distanceMeters: Double, headingDegrees: Double): GeoPoint {
        val heading = Math.toRadians(headingDegrees)
        return offsetMeters(
            lat = lat,
            lon = lon,
            northMeters = cos(heading) * distanceMeters,
            eastMeters = sin(heading) * distanceMeters,
        )
    }

    fun blend(aLat: Double, aLon: Double, bLat: Double, bLon: Double, alphaToB: Double): GeoPoint {
        val alpha = alphaToB.coerceIn(0.0, 1.0)
        val north = Math.toRadians(bLat - aLat) * EARTH_RADIUS_METERS
        val east = Math.toRadians(bLon - aLon) * EARTH_RADIUS_METERS * cos(Math.toRadians((aLat + bLat) / 2.0))
        return offsetMeters(aLat, aLon, north * alpha, east * alpha)
    }

    fun pointInPolygon(lat: Double, lon: Double, polygon: List<GeoPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val xi = polygon[i].lon
            val yi = polygon[i].lat
            val xj = polygon[j].lon
            val yj = polygon[j].lat
            val intersects = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / ((yj - yi).takeUnless { abs(it) < 1e-12 } ?: 1e-12) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    fun distanceToPolygonEdgeMeters(lat: Double, lon: Double, polygon: List<GeoPoint>): Double {
        if (polygon.size < 2) return Double.POSITIVE_INFINITY
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = metersPerDegreeLat * cos(Math.toRadians(lat))
        var best = Double.POSITIVE_INFINITY
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val ax = (a.lon - lon) * metersPerDegreeLon
            val ay = (a.lat - lat) * metersPerDegreeLat
            val bx = (b.lon - lon) * metersPerDegreeLon
            val by = (b.lat - lat) * metersPerDegreeLat
            val abx = bx - ax
            val aby = by - ay
            val len2 = abx * abx + aby * aby
            val t = if (len2 < 1e-9) 0.0 else (-(ax * abx + ay * aby) / len2).coerceIn(0.0, 1.0)
            val px = ax + abx * t
            val py = ay + aby * t
            best = min(best, hypot(px, py))
        }
        return best
    }

    fun latLonToWorldPixel(lat: Double, lon: Double, zoom: Int, tileSize: Int = 256): WorldPixel {
        val sinLat = sin(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
        val worldSize = tileSize * 2.0.pow(zoom)
        val x = (lon + 180.0) / 360.0 * worldSize
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * worldSize
        return WorldPixel(x, y)
    }

    fun worldPixelToLatLon(x: Double, y: Double, zoom: Int, tileSize: Int = 256): GeoPoint {
        val worldSize = tileSize * 2.0.pow(zoom)
        val lon = x / worldSize * 360.0 - 180.0
        val n = Math.PI - 2.0 * Math.PI * y / worldSize
        val lat = Math.toDegrees(atan(sinh(n)))
        return GeoPoint(lat, lon)
    }

    fun wgs84ToWebMercator(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon * WEB_MERCATOR_ORIGIN / 180.0
        var y = ln(tan((90.0 + lat.coerceIn(-85.05112878, 85.05112878)) * Math.PI / 360.0)) / (Math.PI / 180.0)
        y *= WEB_MERCATOR_ORIGIN / 180.0
        return x to y
    }

    fun webMercatorToWgs84(x: Double, y: Double): GeoPoint {
        val lon = x / WEB_MERCATOR_ORIGIN * 180.0
        var lat = y / WEB_MERCATOR_ORIGIN * 180.0
        lat = 180.0 / Math.PI * (2.0 * atan(exp(lat * Math.PI / 180.0)) - Math.PI / 2.0)
        return GeoPoint(lat, lon)
    }
}
