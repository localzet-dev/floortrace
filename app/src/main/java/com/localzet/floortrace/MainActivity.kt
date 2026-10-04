package com.localzet.floortrace

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import com.localzet.floortrace.data.model.GeoFeatureKind
import com.localzet.floortrace.data.model.GeoPolygonFeature
import com.localzet.floortrace.data.nspd.NspdRepository
import com.localzet.floortrace.data.osm.OsmRepository
import com.localzet.floortrace.geo.GeoMath
import com.localzet.floortrace.positioning.PositionSnapshot
import com.localzet.floortrace.positioning.PositioningEngine
import com.localzet.floortrace.ui.SpatialMapView
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    companion object {
        private const val LOCATION_REQUEST = 41
    }

    private lateinit var mapView: SpatialMapView
    private lateinit var primaryText: TextView
    private lateinit var placeText: TextView
    private lateinit var floorText: TextView
    private lateinit var sensorText: TextView
    private lateinit var sourceText: TextView
    private lateinit var positioningEngine: PositioningEngine

    private val mainHandler = Handler(Looper.getMainLooper())
    private val osmRepository = OsmRepository()
    private val nspdRepository = NspdRepository()

    private var osmFeatures: List<GeoPolygonFeature> = emptyList()
    private var cadastralFeatures: List<GeoPolygonFeature> = emptyList()
    private var lastContextLat: Double? = null
    private var lastContextLon: Double? = null
    private var contextLoading = false
    private var currentBuildingId: String? = null
    private var lastSnapshot: PositionSnapshot? = null
    private var currentRoom: GeoPolygonFeature? = null
    private var currentRoomConfidence = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.argb(180, 10, 16, 24)
        window.navigationBarColor = Color.rgb(12, 17, 24)

        mapView = SpatialMapView(this)
        setContentView(buildUi())

        positioningEngine = PositioningEngine(this, ::onPosition)
        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) positioningEngine.start()
    }

    override fun onPause() {
        positioningEngine.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST && hasLocationPermission()) positioningEngine.start()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(Color.argb(220, 15, 23, 34), 18f)
            elevation = dp(8).toFloat()
        }
        val title = TextView(this).apply {
            text = "FLOORTRACE"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        sourceText = TextView(this).apply {
            text = "GNSS • PDR • BARO • OSM • NSPD"
            setTextColor(Color.rgb(94, 226, 255))
            textSize = 11f
        }
        topCard.addView(title)
        topCard.addView(sourceText)
        root.addView(topCard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(14); topMargin = dp(46)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        controls.addView(roundButton("◎") { mapView.recenter() })
        controls.addView(space(dp(8)))
        controls.addView(roundButton("3D") {
            Toast.makeText(this, mapView.toggle3d(), Toast.LENGTH_SHORT).show()
        })
        controls.addView(space(dp(8)))
        controls.addView(roundButton("▱") {
            Toast.makeText(this, mapView.cycleLayers(), Toast.LENGTH_SHORT).show()
        })
        root.addView(controls, FrameLayout.LayoutParams(dp(52), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(14)
        })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(16))
            background = roundedBackground(Color.argb(238, 14, 21, 31), 24f)
            elevation = dp(12).toFloat()
        }
        primaryText = metricText(17f, Color.WHITE, true)
        placeText = metricText(14f, Color.rgb(194, 211, 225), false)
        floorText = metricText(15f, Color.rgb(99, 227, 255), true)
        sensorText = metricText(12f, Color.rgb(148, 166, 183), false)
        panel.addView(primaryText)
        panel.addView(placeText, marginTop(dp(5)))
        panel.addView(floorText, marginTop(dp(7)))
        panel.addView(sensorText, marginTop(dp(6)))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(actionButton("Калибровать этаж") { showFloorCalibrationDialog() })
        buttons.addView(space(dp(8)))
        buttons.addView(actionButton("Обновить слои") {
            lastContextLat = null
            lastSnapshot?.let { snapshot ->
                if (snapshot.latitude != null && snapshot.longitude != null) loadSpatialContext(snapshot.latitude, snapshot.longitude)
            }
        })
        panel.addView(buttons, marginTop(dp(12)))

        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(14)
        })

        return root
    }

    private fun onPosition(snapshot: PositionSnapshot) {
        lastSnapshot = snapshot
        mainHandler.post {
            mapView.setUserPosition(snapshot)
            mapView.setCurrentFloor(snapshot.floor)
            updateSpatialInference(snapshot)
            updateText(snapshot)

            val lat = snapshot.latitude
            val lon = snapshot.longitude
            if (lat != null && lon != null && shouldReloadContext(lat, lon)) loadSpatialContext(lat, lon)
        }
    }

    private fun shouldReloadContext(lat: Double, lon: Double): Boolean {
        if (contextLoading) return false
        val oldLat = lastContextLat ?: return true
        val oldLon = lastContextLon ?: return true
        return GeoMath.distanceMeters(oldLat, oldLon, lat, lon) > 65.0
    }

    private fun loadSpatialContext(lat: Double, lon: Double) {
        contextLoading = true
        lastContextLat = lat
        lastContextLon = lon
        sourceText.text = "загрузка OSM / NSPD…"
        var osmDone = false
        var nspdDone = false
        var osmError: String? = null
        var nspdError: String? = null

        fun finishOne() {
            if (!osmDone || !nspdDone) return
            contextLoading = false
            mainHandler.post {
                mapView.setSpatialData(osmFeatures, cadastralFeatures)
                sourceText.text = buildString {
                    append("OSM ${osmFeatures.size}")
                    val indoorCount = osmFeatures.count { it.kind in setOf(GeoFeatureKind.INDOOR_ROOM, GeoFeatureKind.INDOOR_CORRIDOR, GeoFeatureKind.INDOOR_AREA) }
                    append(" • indoor $indoorCount")
                    append(" • NSPD ${cadastralFeatures.size}")
                    if (osmError != null) append(" • OSM!")
                    if (nspdError != null) append(" • NSPD!")
                }
                lastSnapshot?.let(::updateSpatialInference)
            }
        }

        osmRepository.loadNearby(lat, lon) { result ->
            osmFeatures = result.features
            osmError = result.error
            osmDone = true
            finishOne()
        }
        nspdRepository.loadNearby(lat, lon) { result ->
            cadastralFeatures = result.features
            nspdError = result.error
            nspdDone = true
            finishOne()
        }
    }

    private fun updateSpatialInference(snapshot: PositionSnapshot) {
        val lat = snapshot.latitude ?: return
        val lon = snapshot.longitude ?: return
        val accuracy = snapshot.horizontalAccuracyMeters ?: return
        if (accuracy > 25f) {
            currentRoom = null
            currentRoomConfidence = 0f
            return
        }
        val building = osmFeatures
            .asSequence()
            .filter { it.kind == GeoFeatureKind.BUILDING }
            .filter { it.contains(lat, lon) }
            .maxByOrNull { it.edgeDistanceMeters(lat, lon) }

        val previousBuilding = currentBuildingId
        currentBuildingId = building?.id
        if (building != null && previousBuilding == null && !snapshot.floorCalibrated && snapshot.pressureHpa != null) {
            positioningEngine.calibrateFloor(
                floor = building.minLevel ?: 0,
                floorHeightMeters = estimateFloorHeight(building),
            )
        }

        val floor = snapshot.floor
        val candidates = osmFeatures.asSequence()
            .filter { it.kind == GeoFeatureKind.INDOOR_ROOM }
            .filter { floor == null || it.levels.isEmpty() || floor in it.levels }
            .filter { it.contains(lat, lon) }
            .toList()
        currentRoom = candidates.maxByOrNull { it.edgeDistanceMeters(lat, lon) }
        currentRoomConfidence = currentRoom?.let { room ->
            val edge = room.edgeDistanceMeters(lat, lon)
            val uncertainty = max(1.5, snapshot.horizontalAccuracyMeters?.toDouble() ?: 25.0)
            (edge / uncertainty).coerceIn(0.0, 1.0).toFloat()
        } ?: 0f
    }

    private fun estimateFloorHeight(building: GeoPolygonFeature): Double {
        val height = building.tags["height"]?.removeSuffix(" m")?.toDoubleOrNull()
        val levels = building.buildingLevels
        if (height != null && levels != null && levels > 0) return (height / levels).coerceIn(2.4, 5.0)
        return 3.1
    }

    private fun updateText(snapshot: PositionSnapshot) {
        val lat = snapshot.latitude
        val lon = snapshot.longitude
        primaryText.text = if (lat != null && lon != null) {
            val acc = snapshot.horizontalAccuracyMeters?.let { "±${fmt(it.toDouble(), 1)} м" } ?: "точность —"
            "${fmt(lat, 6)}, ${fmt(lon, 6)}  •  $acc"
        } else {
            "Ожидаю точную позицию…"
        }

        val positionReliableForPlace = (snapshot.horizontalAccuracyMeters ?: Float.MAX_VALUE) <= 25f
        val building = if (lat != null && lon != null && positionReliableForPlace) osmFeatures.firstOrNull {
            it.kind == GeoFeatureKind.BUILDING && it.contains(lat, lon)
        } else null
        val cadastral = if (lat != null && lon != null && positionReliableForPlace) cadastralFeatures.firstOrNull {
            it.contains(lat, lon)
        } else null
        val placeParts = mutableListOf<String>()
        building?.name?.let(placeParts::add)
        cadastral?.ref?.let { placeParts += "кадастр $it" }
        currentRoom?.let { room ->
            val roomName = room.name ?: room.ref ?: "помещение"
            val qualifier = when {
                currentRoomConfidence >= 0.65f -> "комната $roomName"
                currentRoomConfidence >= 0.25f -> "вероятно $roomName"
                else -> null
            }
            if (qualifier != null) placeParts += qualifier
        }
        placeText.text = placeParts.joinToString("  •  ").ifBlank { "В открытых данных помещение пока не определено" }

        floorText.text = when {
            snapshot.floor != null -> {
                val confidence = (snapshot.floorConfidence * 100).toInt()
                val rel = snapshot.relativeAltitudeMeters?.let { "  Δh ${fmt(it, 1)} м" } ?: ""
                if (snapshot.pressureSensorAvailable) {
                    "Этаж: ${snapshot.floor}  •  baro $confidence%$rel"
                } else {
                    val msl = snapshot.meanSeaLevelAltitudeMeters?.let { " • ${fmt(it, 1)} м над морем" } ?: ""
                    "Этаж: ${snapshot.floor} • вручную$msl"
                }
            }
            snapshot.meanSeaLevelAltitudeMeters != null -> {
                val accuracy = snapshot.meanSeaLevelAltitudeAccuracyMeters?.let { " ±${fmt(it.toDouble(), 1)} м" } ?: ""
                "Высота: ${fmt(snapshot.meanSeaLevelAltitudeMeters, 1)} м над морем$accuracy • без барометра"
            }
            snapshot.altitudeMeters != null -> {
                val accuracy = snapshot.verticalAccuracyMeters?.let { " ±${fmt(it.toDouble(), 1)} м" } ?: ""
                "Высота GNSS: ${fmt(snapshot.altitudeMeters, 1)} м$accuracy • без барометра"
            }
            snapshot.pressureSensorAvailable -> "Этаж: нужен вход в здание или ручная калибровка"
            else -> "Высота: ожидаю GNSS • на устройстве нет барометра"
        }

        val cn0 = snapshot.averageCn0DbHz?.let { fmt(it.toDouble(), 1) } ?: "—"
        val pressure = snapshot.pressureHpa?.let { fmt(it.toDouble(), 1) } ?: "—"
        val heading = snapshot.headingDegrees?.let { fmt(it.toDouble(), 0) } ?: "—"
        val acceleration = snapshot.linearAccelerationMetersPerSecondSquared?.let { fmt(it.toDouble(), 1) } ?: "—"
        val rtt = when {
            !snapshot.rttSupported -> "RTT —"
            snapshot.rttAccuracyMeters != null -> "RTT ${snapshot.rttRangedAnchors}/${snapshot.rttConfiguredAnchors} ±${fmt(snapshot.rttAccuracyMeters.toDouble(), 1)}м"
            snapshot.rttAvailable -> "RTT ${snapshot.rttRangedAnchors}/${snapshot.rttConfiguredAnchors}"
            else -> "RTT off"
        }
        val rejected = if (snapshot.rejectedLocationCount > 0) " • фильтр −${snapshot.rejectedLocationCount}" else ""
        val candidates = snapshot.candidates.joinToString("/") { it.source.uppercase() }
        val candidateInfo = if (candidates.isNotEmpty()) " • точки $candidates" else ""
        sensorText.text = "${snapshot.provider.uppercase()} • SAT ${snapshot.satellitesUsed}/${snapshot.satellitesVisible} • C/N₀ $cn0 • raw ${snapshot.rawMeasurementCount} • $rtt • baro $pressure hPa • az $heading° • a $acceleration • шаги ${snapshot.steps}$candidateInfo$rejected"
    }

    private fun showFloorCalibrationDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val floorInput = EditText(this).apply {
            hint = "Текущий этаж"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(lastSnapshot?.floor?.toString() ?: "0")
        }
        val heightInput = EditText(this).apply {
            hint = "Высота этажа, м"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("3.1")
        }
        container.addView(floorInput)
        container.addView(heightInput)

        AlertDialog.Builder(this)
            .setTitle("Калибровка барометра")
            .setMessage(if (lastSnapshot?.pressureSensorAvailable == true) {
                "Лучше делать у входа/лифта на известном этаже. Дальше этаж считается по относительному изменению давления."
            } else {
                "На телефоне нет барометра. Этаж будет сохранён как ручная отметка; автоматически отслеживать переход между этажами устройство не сможет."
            })
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Сброс") { _, _ -> positioningEngine.clearFloorCalibration() }
            .setPositiveButton("Калибровать") { _, _ ->
                val floor = floorInput.text.toString().toIntOrNull() ?: 0
                val height = heightInput.text.toString().replace(',', '.').toDoubleOrNull() ?: 3.1
                positioningEngine.calibrateFloor(floor, height)
            }
            .show()
    }

    private fun requestRequiredPermissions() {
        if (hasLocationPermission()) return
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 29) permissions += Manifest.permission.ACTIVITY_RECOGNITION
        if (android.os.Build.VERSION.SDK_INT >= 33 && packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        requestPermissions(permissions.toTypedArray(), LOCATION_REQUEST)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun metricText(size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        background = roundedBackground(Color.argb(255, 28, 47, 63), 14f)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { click() }
    }

    private fun roundButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 24f
        setTextColor(Color.WHITE)
        background = roundedBackground(Color.argb(230, 15, 23, 34), 18f)
        elevation = dp(8).toFloat()
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
    }

    private fun marginTop(value: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = value
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), Color.argb(80, 104, 178, 214))
    }

    private fun space(size: Int) = Space(this).apply { layoutParams = LinearLayout.LayoutParams(size, size) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun fmt(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)
}
