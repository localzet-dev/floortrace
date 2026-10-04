package com.localzet.floortrace.positioning

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.localzet.floortrace.positioning.rtt.WifiRttEngine
import android.hardware.GeomagneticField
import kotlin.math.sqrt

class PositioningEngine(
    context: Context,
    private val listener: (PositionSnapshot) -> Unit,
) : SensorEventListener, LocationListener {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val preferences = appContext.getSharedPreferences("positioning", Context.MODE_PRIVATE)
    private val floorEstimator = FloorEstimator()

    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var snapshot = PositionSnapshot(
        floor = preferences.takeIf { it.contains(KEY_MANUAL_FLOOR) }?.getInt(KEY_MANUAL_FLOOR, 0),
        floorConfidence = if (preferences.contains(KEY_MANUAL_FLOOR)) 1f else 0f,
        floorCalibrated = preferences.contains(KEY_MANUAL_FLOOR),
        stepDetectorAvailable = stepSensor != null,
        pressureSensorAvailable = pressureSensor != null,
        rotationVectorAvailable = rotationVectorSensor != null,
    )
    private val positionFilter = RobustPositionFilter()
    private var stepCount = 0L
    private var started = false
    private var rejectedLocationCount = 0
    private var rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var magneticFieldMicroTesla: Float? = null
    private var magneticDeclinationDegrees = 0f
    private var filteredHeadingDegrees: Float? = null
    private var linearAccelerationPeak = 0f
    private val locationCandidates = linkedMapOf<String, PositionCandidate>()
    private val candidateFilters = mutableMapOf<String, RobustPositionFilter>()

    private val wifiRttEngine = WifiRttEngine(appContext, ::onRttState)

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var cn0 = 0.0
            var cn0Count = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                    val value = status.getCn0DbHz(i)
                    if (value > 0) {
                        cn0 += value
                        cn0Count++
                    }
                }
            }
            snapshot = snapshot.copy(
                satellitesVisible = status.satelliteCount,
                satellitesUsed = used,
                averageCn0DbHz = if (cn0Count > 0) (cn0 / cn0Count).toFloat() else null,
                timestampMillis = System.currentTimeMillis(),
            )
            emit()
        }
    }

    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            snapshot = snapshot.copy(
                rawMeasurementCount = eventArgs.measurements.size,
                timestampMillis = System.currentTimeMillis(),
            )
            emit()
        }
    }

    fun start() {
        if (started) return
        started = true

        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rotationVectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        stepSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        linearAccelerationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return

        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        }
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2_000L, 0f, this, Looper.getMainLooper())
            }
        }
        runCatching {
            if ("fused" in locationManager.allProviders && locationManager.isProviderEnabled("fused")) {
                if (Build.VERSION.SDK_INT >= 31) {
                    val request = LocationRequest.Builder(1_000L)
                        .setMinUpdateIntervalMillis(500L)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .build()
                    locationManager.requestLocationUpdates("fused", request, appContext.mainExecutor, this)
                } else {
                    locationManager.requestLocationUpdates("fused", 1_000L, 0f, this, Looper.getMainLooper())
                }
            }
        }
        runCatching { locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler) }
        runCatching { locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback, mainHandler) }
        wifiRttEngine.start()

        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { locationAgeMillis(it) <= RobustPositionFilter.MAX_LOCATION_AGE_MILLIS }
            .minByOrNull { it.accuracy }
            ?.let(::onLocationChanged)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager.removeUpdates(this) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssStatusCallback) }
        runCatching { locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback) }
        sensorManager.unregisterListener(this)
        wifiRttEngine.stop()
    }

    fun calibrateFloor(floor: Int = 0, floorHeightMeters: Double = 3.1) {
        if (snapshot.pressureHpa == null) {
            preferences.edit().putInt(KEY_MANUAL_FLOOR, floor).apply()
            snapshot = snapshot.copy(
                relativeAltitudeMeters = 0.0,
                floor = floor,
                floorConfidence = 1f,
                floorCalibrated = true,
            )
            emit()
            return
        }
        floorEstimator.calibrate(snapshot.pressureHpa, floor, floorHeightMeters)
        val estimate = floorEstimator.current()
        snapshot = snapshot.copy(
            relativeAltitudeMeters = estimate.relativeAltitudeMeters,
            floor = estimate.floor,
            floorConfidence = estimate.confidence,
            floorCalibrated = estimate.calibrated,
        )
        emit()
    }

    fun clearFloorCalibration() {
        floorEstimator.clear()
        preferences.edit().remove(KEY_MANUAL_FLOOR).apply()
        snapshot = snapshot.copy(
            relativeAltitudeMeters = null,
            floor = null,
            floorConfidence = 0f,
            floorCalibrated = false,
        )
        emit()
    }

    override fun onLocationChanged(location: Location) {
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        val ageMillis = locationAgeMillis(location)
        if (ageMillis > RobustPositionFilter.MAX_LOCATION_AGE_MILLIS) {
            rejectedLocationCount++
            snapshot = snapshot.copy(
                rejectedLocationCount = rejectedLocationCount,
                lastLocationRejection = "устарела ${ageMillis / 1_000}с",
            )
            Log.w(TAG, "Rejected stale ${location.provider} fix: age=${ageMillis}ms")
            emit()
            return
        }

        val source = (location.provider ?: "unknown").lowercase()
        val candidateResult = candidateFilters.getOrPut(source) { RobustPositionFilter() }.update(
            RobustPositionFilter.Measurement(
                location.latitude,
                location.longitude,
                location.accuracy,
                SystemClock.elapsedRealtime(),
                source,
            ),
        )
        candidateResult.point?.let { candidatePoint ->
            locationCandidates[source] = PositionCandidate(
                source = source,
                latitude = candidatePoint.lat,
                longitude = candidatePoint.lon,
                accuracyMeters = candidateResult.accuracyMeters ?: location.accuracy,
                timestampMillis = System.currentTimeMillis(),
            )
        }
        val freshCandidates = locationCandidates.values
            .filter { System.currentTimeMillis() - it.timestampMillis <= CANDIDATE_TTL_MILLIS }

        val result = positionFilter.update(
            RobustPositionFilter.Measurement(
                lat = location.latitude,
                lon = location.longitude,
                accuracyMeters = location.accuracy,
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                source = source,
            ),
        )
        if (!result.accepted) rejectedLocationCount++
        val point = result.point

        if (result.accepted) {
            magneticDeclinationDegrees = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                (if (location.hasAltitude()) location.altitude else 0.0).toFloat(),
                System.currentTimeMillis(),
            ).declination
        }

        snapshot = snapshot.copy(
            latitude = point?.lat,
            longitude = point?.lon,
            approximateLatitude = snapshot.approximateLatitude
                ?: location.latitude.takeIf { location.accuracy <= 200f && location.provider != LocationManager.GPS_PROVIDER },
            approximateLongitude = snapshot.approximateLongitude
                ?: location.longitude.takeIf { location.accuracy <= 200f && location.provider != LocationManager.GPS_PROVIDER },
            provider = if (result.accepted) location.provider ?: "unknown" else snapshot.provider,
            horizontalAccuracyMeters = result.accuracyMeters,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            verticalAccuracyMeters = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
            meanSeaLevelAltitudeMeters = if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) location.mslAltitudeMeters else snapshot.meanSeaLevelAltitudeMeters,
            meanSeaLevelAltitudeAccuracyMeters = if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitudeAccuracy()) location.mslAltitudeAccuracyMeters else snapshot.meanSeaLevelAltitudeAccuracyMeters,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
            bearingDegrees = if (location.hasBearing()) location.bearing else null,
            rejectedLocationCount = rejectedLocationCount,
            lastLocationRejection = result.rejectionReason,
            locationTimestampMillis = if (result.accepted) {
                location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
            } else snapshot.locationTimestampMillis,
            candidates = freshCandidates,
            timestampMillis = System.currentTimeMillis(),
        )
        Log.d(TAG, "${if (result.accepted) "Accepted" else "Rejected"} ${location.provider} " +
            "fix acc=${location.accuracy}m reason=${result.rejectionReason ?: "ok"} fusedAcc=${result.accuracyMeters}")
        emit()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values.firstOrNull() ?: return
                val estimate = floorEstimator.updatePressure(pressure)
                snapshot = snapshot.copy(
                    pressureHpa = pressure,
                    relativeAltitudeMeters = estimate.relativeAltitudeMeters,
                    floor = estimate.floor,
                    floorConfidence = estimate.confidence,
                    floorCalibrated = estimate.calibrated,
                    timestampMillis = System.currentTimeMillis(),
                )
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = FloatArray(9)
                val displayAdjustedRotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val (axisX, axisY) = when (windowManager.defaultDisplay.rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, axisX, axisY, displayAdjustedRotation)
                SensorManager.getOrientation(displayAdjustedRotation, orientation)
                rotationAccuracy = event.accuracy
                val trueAzimuth = ((Math.toDegrees(orientation[0].toDouble()) + magneticDeclinationDegrees + 360.0) % 360.0).toFloat()
                val azimuth = smoothHeading(filteredHeadingDegrees, trueAzimuth)
                filteredHeadingDegrees = azimuth
                snapshot = snapshot.copy(
                    headingDegrees = azimuth,
                    pitchDegrees = Math.toDegrees(orientation[1].toDouble()).toFloat(),
                    rollDegrees = Math.toDegrees(orientation[2].toDouble()).toFloat(),
                    timestampMillis = System.currentTimeMillis(),
                )
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                stepCount++
                val heading = snapshot.headingDegrees?.toDouble()
                val magneticReliable = magneticFieldMicroTesla?.let { it in 20f..80f } == true
                val headingReliable = rotationAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && magneticReliable
                if (heading != null && headingReliable) {
                    val strideMeters = (0.62 + linearAccelerationPeak.coerceIn(0f, 8f) * 0.025).coerceIn(0.62, 0.82)
                    positionFilter.moveByStep(strideMeters, heading)
                }
                linearAccelerationPeak = 0f
                val filtered = positionFilter.current()
                snapshot = snapshot.copy(
                    latitude = filtered.point?.lat ?: snapshot.latitude,
                    longitude = filtered.point?.lon ?: snapshot.longitude,
                    horizontalAccuracyMeters = filtered.accuracyMeters ?: snapshot.horizontalAccuracyMeters,
                    provider = if (headingReliable && filtered.point != null) "PDR+${snapshot.provider.removePrefix("PDR+")}" else snapshot.provider,
                    steps = stepCount,
                    timestampMillis = System.currentTimeMillis(),
                )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                magneticFieldMicroTesla = magnitude
                snapshot = snapshot.copy(magneticFieldMicroTesla = magnitude)
            }

            Sensor.TYPE_GYROSCOPE -> {
                val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                snapshot = snapshot.copy(gyroRateRadPerSec = magnitude)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                linearAccelerationPeak = maxOf(linearAccelerationPeak * 0.96f, magnitude)
                snapshot = snapshot.copy(linearAccelerationMetersPerSecondSquared = magnitude)
            }
        }
        emit()
    }


    private fun onRttState(state: WifiRttEngine.State) {
        val solution = state.solution
        if (solution != null) {
            locationCandidates["rtt"] = PositionCandidate(
                source = "rtt",
                latitude = solution.point.lat,
                longitude = solution.point.lon,
                accuracyMeters = solution.accuracyMeters,
                timestampMillis = System.currentTimeMillis(),
            )
        }
        val rttAccepted = if (solution != null) {
            positionFilter.update(RobustPositionFilter.Measurement(
                solution.point.lat,
                solution.point.lon,
                solution.accuracyMeters,
                SystemClock.elapsedRealtime(),
                "rtt",
            )).accepted
        } else false

        val filtered = positionFilter.current()

        snapshot = snapshot.copy(
            latitude = filtered.point?.lat ?: snapshot.latitude,
            longitude = filtered.point?.lon ?: snapshot.longitude,
            provider = if (rttAccepted) "RTT+${snapshot.provider.removePrefix("RTT+")}" else snapshot.provider,
            horizontalAccuracyMeters = filtered.accuracyMeters ?: snapshot.horizontalAccuracyMeters,
            rttSupported = state.supported,
            rttAvailable = state.available,
            rttConfiguredAnchors = state.configuredAnchors,
            rttRangedAnchors = state.rangedAnchors,
            rttAccuracyMeters = solution?.accuracyMeters,
            candidates = locationCandidates.values
                .filter { System.currentTimeMillis() - it.timestampMillis <= CANDIDATE_TTL_MILLIS },
            timestampMillis = System.currentTimeMillis(),
        )
        emit()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) rotationAccuracy = accuracy
    }
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated by Android framework")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun emit() {
        listener(snapshot)
    }

    private fun locationAgeMillis(location: Location): Long {
        if (location.elapsedRealtimeNanos <= 0L) return Long.MAX_VALUE
        return ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun smoothHeading(previous: Float?, next: Float): Float {
        if (previous == null) return next
        val delta = ((next - previous + 540f) % 360f) - 180f
        return (previous + delta * 0.18f + 360f) % 360f
    }

    companion object {
        private const val TAG = "FloorTracePosition"
        private const val CANDIDATE_TTL_MILLIS = 120_000L
        private const val KEY_MANUAL_FLOOR = "manual_floor"
    }
}
