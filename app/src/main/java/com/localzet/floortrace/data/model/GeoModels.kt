package com.localzet.floortrace.data.model

import com.localzet.floortrace.geo.GeoMath

data class GeoPoint(
    val lat: Double,
    val lon: Double,
)

enum class GeoFeatureKind {
    BUILDING,
    INDOOR_ROOM,
    INDOOR_CORRIDOR,
    INDOOR_AREA,
    CADASTRAL_PARCEL,
    CADASTRAL_BUILDING,
}

data class GeoPolygonFeature(
    val id: String,
    val kind: GeoFeatureKind,
    val outer: List<GeoPoint>,
    val name: String? = null,
    val ref: String? = null,
    val levels: Set<Int> = emptySet(),
    val buildingLevels: Int? = null,
    val minLevel: Int? = null,
    val tags: Map<String, String> = emptyMap(),
) {
    fun contains(lat: Double, lon: Double): Boolean = GeoMath.pointInPolygon(lat, lon, outer)

    fun edgeDistanceMeters(lat: Double, lon: Double): Double =
        GeoMath.distanceToPolygonEdgeMeters(lat, lon, outer)

    fun displayName(): String = name ?: ref ?: when (kind) {
        GeoFeatureKind.BUILDING -> "здание"
        GeoFeatureKind.INDOOR_ROOM -> "помещение"
        GeoFeatureKind.INDOOR_CORRIDOR -> "коридор"
        GeoFeatureKind.INDOOR_AREA -> "внутренняя зона"
        GeoFeatureKind.CADASTRAL_PARCEL -> "земельный участок"
        GeoFeatureKind.CADASTRAL_BUILDING -> "объект кадастра"
    }
}

data class SpatialContext(
    val osmFeatures: List<GeoPolygonFeature> = emptyList(),
    val cadastralFeatures: List<GeoPolygonFeature> = emptyList(),
    val osmError: String? = null,
    val cadastralError: String? = null,
)
