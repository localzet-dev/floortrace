package com.localzet.floortrace.data.osm

import com.localzet.floortrace.data.model.GeoFeatureKind
import com.localzet.floortrace.data.model.GeoPoint
import com.localzet.floortrace.data.model.GeoPolygonFeature
import com.localzet.floortrace.net.Http
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import android.util.Log
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OsmRepository(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    data class Result(val features: List<GeoPolygonFeature>, val error: String? = null)

    fun loadNearby(lat: Double, lon: Double, callback: (Result) -> Unit) {
        executor.execute {
            val result = runCatching { fetch(lat, lon) }
                .fold(
                    onSuccess = { Result(it) },
                    onFailure = {
                        Log.e(TAG, "Failed to load OSM context", it)
                        Result(emptyList(), it.message ?: it.javaClass.simpleName)
                    },
                )
            callback(result)
        }
    }

    private fun fetch(lat: Double, lon: Double): List<GeoPolygonFeature> {
        // The small official map extract is a dependable fallback for the live
        // 3D view when public Overpass instances are saturated or VPN-blocked.
        val latDelta = 0.0018
        val lonDelta = 0.0027
        val bbox = "${lon - lonDelta},${lat - latDelta},${lon + lonDelta},${lat + latDelta}"
        val direct = runCatching {
            Http.get("https://api.openstreetmap.org/api/0.6/map?bbox=$bbox", timeoutMs = 15_000)
        }.onFailure { Log.w(TAG, "Direct OSM extract failed", it) }
        direct.getOrNull()?.let { response ->
            val text = response.toString(Charsets.UTF_8)
            runCatching {
                if (text.trimStart().startsWith("{")) parse(JSONObject(text)) else parseOsmXml(response)
            }.onSuccess { return it }
                .onFailure { Log.w(TAG, "Direct OSM extract could not be parsed", it) }
        }

        val query = """
            [out:json][timeout:20];
            (
              way(around:180,$lat,$lon)[building];
              way(around:180,$lat,$lon)[indoor];
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val payload = Http.get("https://overpass-api.de/api/interpreter?data=$encoded", timeoutMs = 15_000)
        return parse(JSONObject(payload.toString(Charsets.UTF_8)))
    }

    private data class XmlWay(
        val id: Long,
        val nodes: MutableList<Long> = mutableListOf(),
        val tags: MutableMap<String, String> = linkedMapOf(),
    )

    private fun parseOsmXml(bytes: ByteArray): List<GeoPolygonFeature> {
        val nodes = HashMap<Long, GeoPoint>()
        val ways = ArrayList<XmlWay>()
        val parser = Xml.newPullParser()
        // A few user-entered OSM tags in this region contain a bare ampersand.
        // Preserve the text while making the extract acceptable to KXmlParser.
        val rawResponse = bytes.toString(Charsets.UTF_8)
        val documentEnd = rawResponse.indexOf("</osm>")
        val xmlDocument = if (documentEnd >= 0) rawResponse.substring(0, documentEnd + "</osm>".length) else rawResponse
        val sanitizedXml = xmlDocument.replace("&", "&amp;")
        parser.setInput(ByteArrayInputStream(sanitizedXml.toByteArray(Charsets.UTF_8)), "UTF-8")
        var currentWay: XmlWay? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "node" -> {
                        val id = parser.getAttributeValue(null, "id")?.toLongOrNull()
                        val nodeLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val nodeLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (id != null && nodeLat != null && nodeLon != null) nodes[id] = GeoPoint(nodeLat, nodeLon)
                    }
                    "way" -> currentWay = parser.getAttributeValue(null, "id")?.toLongOrNull()?.let(::XmlWay)
                    "nd" -> parser.getAttributeValue(null, "ref")?.toLongOrNull()?.let { currentWay?.nodes?.add(it) }
                    "tag" -> {
                        val key = parser.getAttributeValue(null, "k")
                        val value = parser.getAttributeValue(null, "v")
                        if (key != null && value != null) currentWay?.tags?.put(key, value)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "way") {
                    currentWay?.let(ways::add)
                    currentWay = null
                }
            }
            event = parser.next()
        }

        return ways.mapNotNull { way ->
            val kind = featureKind(way.tags) ?: return@mapNotNull null
            val polygon = way.nodes.mapNotNull(nodes::get)
            if (polygon.size < 3) return@mapNotNull null
            GeoPolygonFeature(
                id = "osm-way-${way.id}",
                kind = kind,
                outer = polygon,
                name = way.tags["name"],
                ref = way.tags["ref"],
                levels = parseLevels(way.tags["level"]),
                buildingLevels = way.tags["building:levels"]?.toDoubleOrNull()?.toInt(),
                minLevel = way.tags["min_level"]?.toDoubleOrNull()?.toInt(),
                tags = way.tags,
            )
        }
    }

    private fun parse(root: JSONObject): List<GeoPolygonFeature> {
        val elements = root.getJSONArray("elements")
        val nodes = HashMap<Long, GeoPoint>()
        val ways = ArrayList<JSONObject>()

        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            when (element.optString("type")) {
                "node" -> nodes[element.getLong("id")] = GeoPoint(element.getDouble("lat"), element.getDouble("lon"))
                "way" -> ways += element
            }
        }

        val features = ArrayList<GeoPolygonFeature>()
        for (way in ways) {
            val tagsObj = way.optJSONObject("tags") ?: continue
            val tags = buildMap {
                val keys = tagsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, tagsObj.optString(key))
                }
            }
            val nodeIds = way.optJSONArray("nodes") ?: continue
            val polygon = ArrayList<GeoPoint>(nodeIds.length())
            for (i in 0 until nodeIds.length()) {
                nodes[nodeIds.getLong(i)]?.let(polygon::add)
            }
            if (polygon.size < 3) continue

            val kind = featureKind(tags) ?: continue

            features += GeoPolygonFeature(
                id = "osm-way-${way.getLong("id")}",
                kind = kind,
                outer = polygon,
                name = tags["name"],
                ref = tags["ref"],
                levels = parseLevels(tags["level"]),
                buildingLevels = tags["building:levels"]?.toDoubleOrNull()?.toInt(),
                minLevel = tags["min_level"]?.toDoubleOrNull()?.toInt(),
                tags = tags,
            )
        }
        return features
    }

    private fun featureKind(tags: Map<String, String>): GeoFeatureKind? = when {
        tags.containsKey("indoor") -> when (tags["indoor"]) {
            "room" -> GeoFeatureKind.INDOOR_ROOM
            "corridor" -> GeoFeatureKind.INDOOR_CORRIDOR
            "area", "level" -> GeoFeatureKind.INDOOR_AREA
            else -> null
        }
        tags.containsKey("building") -> GeoFeatureKind.BUILDING
        else -> null
    }

    private fun parseLevels(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        val result = linkedSetOf<Int>()
        raw.split(';', ',').forEach { token ->
            val value = token.trim()
            value.toIntOrNull()?.let { result += it; return@forEach }
            val range = Regex("^(-?\\d+)\\s*-\\s*(-?\\d+)$").matchEntire(value)
            if (range != null) {
                val from = range.groupValues[1].toInt()
                val to = range.groupValues[2].toInt()
                if (from <= to) (from..to).forEach(result::add) else (from downTo to).forEach(result::add)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "FloorTraceOSM"
    }
}
