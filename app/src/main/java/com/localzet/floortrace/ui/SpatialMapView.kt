package com.localzet.floortrace.ui

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.localzet.floortrace.data.model.GeoFeatureKind
import com.localzet.floortrace.data.model.GeoPoint
import com.localzet.floortrace.data.model.GeoPolygonFeature
import com.localzet.floortrace.geo.GeoMath
import com.localzet.floortrace.positioning.PositionSnapshot
import com.localzet.floortrace.positioning.PositionCandidate
import kotlin.math.*

class SpatialMapView(context: Context) : View(context) {
    private val tileSize = 256
    private val tileCache = TileCache(context)
    private var center = GeoPoint(55.751244, 37.618423)
    private var zoom = 18f
    private var userPosition: PositionSnapshot? = null
    private var osmFeatures: List<GeoPolygonFeature> = emptyList()
    private var cadastralFeatures: List<GeoPolygonFeature> = emptyList()
    private var currentFloor: Int? = null
    private var follow = true
    private var initialCenterSet = false
    private var cameraPitchDegrees = 35f
    private var cameraBearingDegrees = 0f
    private var orbitStartAngle = 0f
    private var orbitStartY = 0f
    private var orbitStartPitch = 0f
    private var orbitStartBearing = 0f
    private var orbitGesture = false
    var showIndoor = true
    var showCadastre = true
    private var show3d = true

    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val buildingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 36, 82, 110); style = Paint.Style.FILL }
    private val buildingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 75, 196, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val buildingRoof3dPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 44, 105, 136); style = Paint.Style.FILL }
    private val buildingWall3dPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 22, 55, 76); style = Paint.Style.FILL }
    private val parcelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(25, 0, 229, 255); style = Paint.Style.FILL }
    private val parcelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 0, 229, 255); style = Paint.Style.STROKE; strokeWidth = 2.2f }
    private val indoorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 190, 89, 255); style = Paint.Style.FILL }
    private val indoorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 211, 146, 255); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(35, 0, 229, 255); style = Paint.Style.FILL }
    private val accuracyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 229, 255); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); style = Paint.Style.FILL; setShadowLayer(10f, 0f, 2f, Color.BLACK) }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; setShadowLayer(6f, 0f, 1f, Color.BLACK) }
    private val candidateStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val candidateLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(5f, 0f, 1f, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * resources.displayMetrics.scaledDensity; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 255, 255, 255); textSize = 10f * resources.displayMetrics.scaledDensity }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom + ln(detector.scaleFactor.toDouble()) / ln(2.0)).toFloat().coerceIn(3f, 20f)
            follow = false
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount > 1 || orbitGesture) return true
            follow = false
            val z = zoom.roundToInt().coerceIn(3, 20)
            val world = GeoMath.latLonToWorldPixel(center.lat, center.lon, z, tileSize)
            val pitchScale = activePitchScale()
            val adjustedY = distanceY / pitchScale
            val bearing = Math.toRadians(activeBearingDegrees().toDouble())
            val mapX = distanceX * cos(bearing) - adjustedY * sin(bearing)
            val mapY = distanceX * sin(bearing) + adjustedY * cos(bearing)
            center = GeoMath.worldPixelToLatLon(world.x + mapX, world.y + mapY, z, tileSize)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoom = (zoom + 1f).coerceAtMost(20f)
            invalidate()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    fun setSpatialData(osm: List<GeoPolygonFeature>, cadastre: List<GeoPolygonFeature>) {
        osmFeatures = osm
        cadastralFeatures = cadastre
        invalidate()
    }

    fun setCurrentFloor(floor: Int?) {
        currentFloor = floor
        invalidate()
    }

    fun setUserPosition(position: PositionSnapshot) {
        userPosition = position
        val lat = position.latitude ?: position.approximateLatitude
        val lon = position.longitude ?: position.approximateLongitude
        if (follow && lat != null && lon != null) {
            val distance = GeoMath.distanceMeters(center.lat, center.lon, lat, lon)
            if (!initialCenterSet || (position.latitude != null && distance >= CAMERA_DEADBAND_METERS)) {
                center = GeoPoint(lat, lon)
                initialCenterSet = true
            }
        }
        invalidate()
    }

    fun recenter() {
        val p = userPosition
        val lat = p?.latitude ?: p?.approximateLatitude
        val lon = p?.longitude ?: p?.approximateLongitude
        if (lat != null && lon != null) center = GeoPoint(lat, lon)
        follow = true
        invalidate()
    }

    fun cycleLayers(): String {
        when {
            showCadastre && showIndoor -> showCadastre = false
            !showCadastre && showIndoor -> { showIndoor = false; showCadastre = true }
            showCadastre && !showIndoor -> { showCadastre = false; showIndoor = false }
            else -> { showCadastre = true; showIndoor = true }
        }
        invalidate()
        return when {
            showCadastre && showIndoor -> "OSM + indoor + кадастр"
            showCadastre -> "OSM + кадастр"
            showIndoor -> "OSM + indoor"
            else -> "только карта"
        }
    }

    fun toggle3d(): String {
        show3d = !show3d
        invalidate()
        return if (show3d) "3D: два пальца — наклон и поворот" else "Плоская карта"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (show3d && event.pointerCount >= 2) {
                orbitGesture = true
                orbitStartAngle = pointerAngle(event)
                orbitStartY = pointerCenterY(event)
                orbitStartPitch = cameraPitchDegrees
                orbitStartBearing = cameraBearingDegrees
                follow = false
            }
            MotionEvent.ACTION_MOVE -> if (orbitGesture && event.pointerCount >= 2) {
                val angleDelta = normalizeAngle(pointerAngle(event) - orbitStartAngle)
                val verticalDelta = pointerCenterY(event) - orbitStartY
                cameraBearingDegrees = (orbitStartBearing - angleDelta + 360f) % 360f
                cameraPitchDegrees = (orbitStartPitch - verticalDelta * 0.16f).coerceIn(0f, 68f)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 2) orbitGesture = false
            }
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(16, 21, 30))
        val z = zoom.roundToInt().coerceIn(3, 20)
        canvas.save()
        canvas.rotate(-activeBearingDegrees(), width / 2f, height / 2f)
        canvas.scale(1f, activePitchScale(), width / 2f, height / 2f)
        drawTiles(canvas, z)
        drawFeatures(canvas, z)
        drawCandidates(canvas, z)
        drawUser(canvas, z)
        canvas.restore()
        canvas.drawText("© OpenStreetMap contributors", 12f, height - 14f, attributionPaint)
    }

    private fun drawTiles(canvas: Canvas, z: Int) {
        val centerWorld = GeoMath.latLonToWorldPixel(center.lat, center.lon, z, tileSize)
        val left = centerWorld.x - width / 2.0
        val virtualHeight = height / activePitchScale()
        val top = centerWorld.y - virtualHeight / 2.0
        val minTileX = floor(left / tileSize).toInt() - 1
        val maxTileX = floor((left + width) / tileSize).toInt() + 1
        val minTileY = floor(top / tileSize).toInt() - 1
        val maxTileY = floor((top + virtualHeight) / tileSize).toInt() + 1

        for (tx in minTileX..maxTileX) {
            for (ty in minTileY..maxTileY) {
                val x = (tx * tileSize - left).toFloat()
                val y = (ty * tileSize - top).toFloat()
                val bitmap = tileCache.get(z, tx, ty) { postInvalidateOnAnimation() }
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, RectF(x, y, x + tileSize, y + tileSize), tilePaint)
                }
            }
        }
        canvas.drawColor(Color.argb(20, 8, 17, 25))
    }

    private fun drawFeatures(canvas: Canvas, z: Int) {
        val buildings = osmFeatures.filter { it.kind == GeoFeatureKind.BUILDING }
        val position = userPosition
        val labeledBuildingIds = if (show3d && position?.latitude != null && position.longitude != null) {
            buildings.asSequence()
                .filter { it.tags["height"] != null || it.buildingLevels != null }
                .sortedBy { it.edgeDistanceMeters(position.latitude, position.longitude) }
                .take(8)
                .map { it.id }
                .toSet()
        } else emptySet()
        buildings.forEach {
            if (show3d) drawBuilding3d(canvas, it, z, it.id in labeledBuildingIds)
            else drawPolygon(canvas, it, z, buildingPaint, buildingStroke, false)
        }
        if (showCadastre) {
            cadastralFeatures.forEach { feature ->
                drawPolygon(canvas, feature, z, parcelPaint, parcelStroke, feature.name != null)
            }
        }
        if (showIndoor) {
            osmFeatures.filter { it.kind in setOf(GeoFeatureKind.INDOOR_ROOM, GeoFeatureKind.INDOOR_CORRIDOR, GeoFeatureKind.INDOOR_AREA) }
                .filter { it.levels.isEmpty() || currentFloor == null || currentFloor in it.levels }
                .forEach { drawPolygon(canvas, it, z, indoorPaint, indoorStroke, true) }
        }
    }

    private fun drawPolygon(
        canvas: Canvas,
        feature: GeoPolygonFeature,
        z: Int,
        fill: Paint,
        stroke: Paint,
        label: Boolean,
    ) {
        if (feature.outer.size < 3) return
        val path = Path()
        feature.outer.forEachIndexed { index, point ->
            val screen = toScreen(point, z)
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        if (label) {
            val name = feature.name ?: feature.ref ?: return
            val centerPoint = feature.outer[feature.outer.size / 2]
            val p = toScreen(centerPoint, z)
            canvas.drawText(name.take(24), p.x + 4f, p.y - 4f, labelPaint)
        }
    }

    private fun drawBuilding3d(canvas: Canvas, feature: GeoPolygonFeature, z: Int, showHeightLabel: Boolean) {
        if (feature.outer.size < 3) return
        val heightMeters = feature.tags["height"]?.removeSuffix(" m")?.toFloatOrNull()
            ?: feature.buildingLevels?.times(3.1f)
            ?: 10f
        val metersPerPixel = cos(Math.toRadians(feature.outer.first().lat)) * 2 * Math.PI * 6_378_137.0 / (tileSize * 2.0.pow(z))
        val rise = ((heightMeters / metersPerPixel * 0.48) / activePitchScale()).toFloat().coerceIn(5f, 140f)
        val shiftX = rise * 0.28f
        val shiftY = -rise
        val base = feature.outer.map { toScreen(it, z) }
        val top = base.map { PointF(it.x + shiftX, it.y + shiftY) }

        for (i in base.indices) {
            val next = (i + 1) % base.size
            val side = Path().apply {
                moveTo(base[i].x, base[i].y)
                lineTo(base[next].x, base[next].y)
                lineTo(top[next].x, top[next].y)
                lineTo(top[i].x, top[i].y)
                close()
            }
            canvas.drawPath(side, buildingWall3dPaint)
            canvas.drawPath(side, buildingStroke)
        }
        val roof = Path().apply {
            top.forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        canvas.drawPath(roof, buildingRoof3dPaint)
        canvas.drawPath(roof, buildingStroke)

        if (zoom >= 17f && showHeightLabel) {
            val anchor = top[top.size / 2]
            val levels = feature.buildingLevels?.let { " • $it эт." } ?: ""
            canvas.drawText("${heightMeters.toInt()} м$levels", anchor.x + 4f, anchor.y - 4f, labelPaint)
        }
    }

    private fun drawUser(canvas: Canvas, z: Int) {
        val p = userPosition ?: return
        val lat = p.latitude ?: return
        val lon = p.longitude ?: return
        val screen = toScreen(GeoPoint(lat, lon), z)
        val accuracy = p.horizontalAccuracyMeters ?: 0f
        if (accuracy > 0f) {
            val metersPerPixel = cos(Math.toRadians(lat)) * 2 * Math.PI * 6_378_137.0 / (tileSize * 2.0.pow(z))
            val radius = (accuracy / metersPerPixel).toFloat().coerceAtMost(max(width, height).toFloat())
            canvas.drawCircle(screen.x, screen.y, radius, accuracyPaint)
            canvas.drawCircle(screen.x, screen.y, radius, accuracyStroke)
        }
        val heading = p.headingDegrees ?: p.bearingDegrees ?: 0f
        canvas.save()
        canvas.rotate(heading, screen.x, screen.y)
        val marker = Path().apply {
            moveTo(screen.x, screen.y - 22f)
            lineTo(screen.x - 12f, screen.y + 14f)
            lineTo(screen.x, screen.y + 9f)
            lineTo(screen.x + 12f, screen.y + 14f)
            close()
        }
        canvas.drawPath(marker, markerPaint)
        canvas.drawCircle(screen.x, screen.y, 5f, markerInnerPaint)
        canvas.restore()
    }

    private fun drawCandidates(canvas: Canvas, z: Int) {
        val candidates = userPosition?.candidates ?: return
        candidates.forEach { candidate ->
            val screen = toScreen(GeoPoint(candidate.latitude, candidate.longitude), z)
            if (screen.x !in -80f..width + 80f || screen.y !in -80f..height + 80f) return@forEach
            val color = candidateColor(candidate)
            candidatePaint.color = color
            candidateStrokePaint.color = Color.argb(210, Color.red(color), Color.green(color), Color.blue(color))
            when (candidate.source.lowercase()) {
                "network" -> {
                    canvas.drawRect(screen.x - 9f, screen.y - 9f, screen.x + 9f, screen.y + 9f, candidatePaint)
                    canvas.drawRect(screen.x - 12f, screen.y - 12f, screen.x + 12f, screen.y + 12f, candidateStrokePaint)
                }
                "fused" -> {
                    val diamond = Path().apply {
                        moveTo(screen.x, screen.y - 14f); lineTo(screen.x + 14f, screen.y)
                        lineTo(screen.x, screen.y + 14f); lineTo(screen.x - 14f, screen.y); close()
                    }
                    canvas.drawPath(diamond, candidatePaint)
                    canvas.drawPath(diamond, candidateStrokePaint)
                }
                else -> {
                    canvas.drawCircle(screen.x, screen.y, 10f, candidatePaint)
                    canvas.drawCircle(screen.x, screen.y, 13f, candidateStrokePaint)
                }
            }
            val label = "${candidate.source.uppercase()} ±${candidate.accuracyMeters.toInt()}м"
            candidateLabelPaint.color = color
            val labelOffset = when (candidate.source.lowercase()) {
                "gps" -> -30f
                "network" -> -12f
                "fused" -> 18f
                "rtt" -> 36f
                else -> 0f
            }
            canvas.drawText(label, screen.x + 17f, screen.y + labelOffset, candidateLabelPaint)
        }
    }

    private fun candidateColor(candidate: PositionCandidate): Int = when (candidate.source.lowercase()) {
        "gps" -> Color.rgb(76, 220, 120)
        "network" -> Color.rgb(255, 171, 64)
        "fused" -> Color.rgb(255, 82, 170)
        "rtt" -> Color.rgb(181, 109, 255)
        else -> Color.rgb(255, 230, 92)
    }

    private fun cameraPitchScale(): Float = cos(Math.toRadians(cameraPitchDegrees.toDouble())).toFloat().coerceAtLeast(0.36f)

    private fun activePitchScale(): Float = if (show3d) cameraPitchScale() else 1f

    private fun activeBearingDegrees(): Float = if (show3d) cameraBearingDegrees else 0f

    private fun pointerAngle(event: MotionEvent): Float = Math.toDegrees(
        atan2(
            (event.getY(1) - event.getY(0)).toDouble(),
            (event.getX(1) - event.getX(0)).toDouble(),
        ),
    ).toFloat()

    private fun pointerCenterY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f

    private fun normalizeAngle(value: Float): Float = ((value + 540f) % 360f) - 180f

    private fun toScreen(point: GeoPoint, z: Int): PointF {
        val centerWorld = GeoMath.latLonToWorldPixel(center.lat, center.lon, z, tileSize)
        val pointWorld = GeoMath.latLonToWorldPixel(point.lat, point.lon, z, tileSize)
        return PointF(
            (width / 2.0 + pointWorld.x - centerWorld.x).toFloat(),
            (height / 2.0 + pointWorld.y - centerWorld.y).toFloat(),
        )
    }

    companion object {
        private const val CAMERA_DEADBAND_METERS = 3.0
    }
}
