package com.localzet.floortrace.data.nspd

import com.localzet.floortrace.data.model.GeoFeatureKind
import com.localzet.floortrace.data.model.GeoPoint
import com.localzet.floortrace.data.model.GeoPolygonFeature
import com.localzet.floortrace.geo.GeoMath
import com.localzet.floortrace.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Log

/**
 * Best-effort adapter to the public NSPD geoportal transport currently used by the web map.
 * This is intentionally isolated: it is not treated as a stable official mobile SDK.
 */
class NspdRepository(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    data class Result(val features: List<GeoPolygonFeature>, val error: String? = null)

    fun loadNearby(lat: Double, lon: Double, callback: (Result) -> Unit) {
        executor.execute {
            val result = runCatching { fetch(lat, lon) }
                .fold(
                    onSuccess = { Result(it) },
                    onFailure = {
                        Log.e(TAG, "Failed to load NSPD context", it)
                        Result(emptyList(), it.message ?: it.javaClass.simpleName)
                    },
                )
            callback(result)
        }
    }

    private fun fetch(lat: Double, lon: Double): List<GeoPolygonFeature> {
        val (x, y) = GeoMath.wgs84ToWebMercator(lat, lon)
        val radius = 35.0
        val ring = listOf(
            x - radius to y - radius,
            x + radius to y - radius,
            x + radius to y + radius,
            x - radius to y + radius,
            x - radius to y - radius,
        )
        val coordinates = JSONArray().apply {
            put(JSONArray().apply {
                ring.forEach { (rx, ry) -> put(JSONArray().put(rx).put(ry)) }
            })
        }
        val geometry = JSONObject()
            .put("crs", JSONObject().put("properties", JSONObject().put("name", "EPSG:3857")).put("type", "name"))
            .put("type", "Polygon")
            .put("coordinates", coordinates)

        val payload = JSONObject()
            .put("geom", JSONObject()
                .put("type", "FeatureCollection")
                .put("features", JSONArray().put(JSONObject()
                    .put("type", "Feature")
                    .put("geometry", geometry)
                    .put("properties", JSONObject()))))
            .put("categories", JSONArray()
                .put(JSONObject().put("id", 36368)) // land plot
                .put(JSONObject().put("id", 36369))) // building

        val bytes = Http.postJson(
            url = "https://nspd.gov.ru/api/geoportal/v1/intersects?typeIntersect=fullObject",
            json = payload.toString(),
            headers = mapOf(
                "Referer" to "https://nspd.gov.ru/map?thematic=PKK",
                "Origin" to "https://nspd.gov.ru",
            ),
            timeoutMs = 20_000,
        )
        return parse(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    private fun parse(root: JSONObject): List<GeoPolygonFeature> {
        val featureArray = findFeatures(root) ?: return emptyList()
        val result = ArrayList<GeoPolygonFeature>()
        for (i in 0 until featureArray.length()) {
            val feature = featureArray.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val type = geometry.optString("type")
            val rings = when (type) {
                "Polygon" -> listOfNotNull(geometry.optJSONArray("coordinates")?.optJSONArray(0))
                "MultiPolygon" -> {
                    val all = geometry.optJSONArray("coordinates") ?: continue
                    buildList {
                        for (p in 0 until all.length()) {
                            all.optJSONArray(p)?.optJSONArray(0)?.let(::add)
                        }
                    }
                }
                else -> emptyList()
            }
            if (rings.isEmpty()) continue

            val properties = feature.optJSONObject("properties")
            val options = properties?.optJSONObject("options")
            val category = options?.optInt("categoryId", -1)
                ?.takeIf { it > 0 }
                ?: properties?.optInt("categoryId", -1)
                ?: -1
            val kind = when (category) {
                36368 -> GeoFeatureKind.CADASTRAL_PARCEL
                36369, 36383, 36384 -> GeoFeatureKind.CADASTRAL_BUILDING
                else -> GeoFeatureKind.CADASTRAL_PARCEL
            }
            val cadNum = options?.optString("cad_num")?.takeIf { it.isNotBlank() }
                ?: properties?.optString("cad_num")?.takeIf { it.isNotBlank() }

            rings.forEachIndexed { index, ring ->
                val polygon = ArrayList<GeoPoint>()
                for (j in 0 until ring.length()) {
                    val pair = ring.optJSONArray(j) ?: continue
                    if (pair.length() < 2) continue
                    polygon += GeoMath.webMercatorToWgs84(pair.getDouble(0), pair.getDouble(1))
                }
                if (polygon.size >= 3) {
                    result += GeoPolygonFeature(
                        id = "nspd-${feature.optString("id", i.toString())}-$index",
                        kind = kind,
                        outer = polygon,
                        name = cadNum,
                        ref = cadNum,
                    )
                }
            }
        }
        return result
    }

    private fun findFeatures(root: JSONObject): JSONArray? {
        root.optJSONArray("features")?.let { return it }
        val dataObject = root.optJSONObject("data")
        dataObject?.optJSONArray("features")?.let { return it }
        val dataArray = root.optJSONArray("data")
        if (dataArray != null) return dataArray
        return null
    }

    companion object {
        private const val TAG = "FloorTraceNSPD"
    }
}
