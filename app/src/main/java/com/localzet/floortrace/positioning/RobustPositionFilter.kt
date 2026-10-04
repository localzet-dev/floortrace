package com.localzet.floortrace.positioning

import com.localzet.floortrace.data.model.GeoPoint
import com.localzet.floortrace.geo.GeoMath
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Lightweight horizontal position filter for pedestrian use.
 *
 * Android's reported accuracy is not an outlier detector: an indoor GNSS fix can
 * move between building wings while still reporting a deceptively small radius.
 * This filter rejects unusable fixes and requires a displaced cluster to persist
 * before it is allowed to re-anchor the pedestrian track.
 */
class RobustPositionFilter {
    data class Measurement(
        val lat: Double,
        val lon: Double,
        val accuracyMeters: Float,
        val elapsedRealtimeMillis: Long,
        val source: String = "unknown",
    )

    data class Result(
        val point: GeoPoint?,
        val accuracyMeters: Float?,
        val accepted: Boolean,
        val rejectionReason: String? = null,
    )

    private data class PendingCluster(
        val lat: Double,
        val lon: Double,
        val accuracyMeters: Float,
        val count: Int,
    )

    private var point: GeoPoint? = null
    private var accuracyMeters: Float? = null
    private var lastUpdateElapsedMillis: Long? = null
    private var lastUncertaintyElapsedMillis: Long? = null
    private var pending: PendingCluster? = null
    private val bootstrapClusters = mutableMapOf<String, PendingCluster>()

    fun update(measurement: Measurement): Result {
        if (!measurement.lat.isFinite() || !measurement.lon.isFinite() ||
            measurement.lat !in -90.0..90.0 || measurement.lon !in -180.0..180.0 ||
            measurement.accuracyMeters.isNaN() || measurement.accuracyMeters <= 0f
        ) {
            return current(false, "invalid")
        }
        val measured = GeoPoint(measurement.lat, measurement.lon)
        val current = point
        if (current == null) {
            if (measurement.accuracyMeters > MAX_BOOTSTRAP_ACCURACY_METERS) {
                return Result(null, null, false, "initial accuracy")
            }
            if (measurement.accuracyMeters > MAX_INITIAL_ACCURACY_METERS) {
                val cluster = accumulateBootstrapCluster(measurement.source, measured, measurement.accuracyMeters)
                if (cluster.count < REQUIRED_INITIAL_CONFIRMATIONS) {
                    return Result(null, null, false, "стабилизация ${cluster.count}/$REQUIRED_INITIAL_CONFIRMATIONS")
                }
                point = GeoPoint(cluster.lat, cluster.lon)
                accuracyMeters = cluster.accuracyMeters
                lastUpdateElapsedMillis = measurement.elapsedRealtimeMillis
                lastUncertaintyElapsedMillis = measurement.elapsedRealtimeMillis
                pending = null
                bootstrapClusters.clear()
                return current(true)
            }
            point = measured
            accuracyMeters = measurement.accuracyMeters.coerceAtLeast(MIN_ACCURACY_METERS)
            lastUpdateElapsedMillis = measurement.elapsedRealtimeMillis
            lastUncertaintyElapsedMillis = measurement.elapsedRealtimeMillis
            pending = null
            bootstrapClusters.clear()
            return current(true)
        }

        growUncertainty(measurement.elapsedRealtimeMillis)
        val currentAccuracy = accuracyMeters ?: MAX_INITIAL_ACCURACY_METERS
        val distance = GeoMath.distanceMeters(current.lat, current.lon, measured.lat, measured.lon)

        // Once a coarse but stable bootstrap exists, equally coarse measurements
        // may confirm it but are never allowed to drag the marker around.
        if (measurement.accuracyMeters > MAX_MEASUREMENT_ACCURACY_METERS) {
            if (distance <= COARSE_CONFIRMATION_RADIUS_METERS) {
                accuracyMeters = minOf(currentAccuracy, measurement.accuracyMeters)
                lastUpdateElapsedMillis = measurement.elapsedRealtimeMillis
                lastUncertaintyElapsedMillis = measurement.elapsedRealtimeMillis
                return current(true)
            }
            return current(false, "accuracy")
        }

        val gate = maxOf(
            MIN_OUTLIER_GATE_METERS,
            hypot(minOf(currentAccuracy, 30f).toDouble(), measurement.accuracyMeters.toDouble()) * OUTLIER_SIGMA,
        )
        if (distance > gate) {
            val cluster = accumulateCluster(
                measured,
                measurement.accuracyMeters,
                maxOf(MIN_CLUSTER_RADIUS_METERS, measurement.accuracyMeters.toDouble()),
            )
            if (cluster.count < REQUIRED_OUTLIER_CONFIRMATIONS) {
                return current(false, "outlier ${cluster.count}/$REQUIRED_OUTLIER_CONFIRMATIONS")
            }
            point = GeoPoint(cluster.lat, cluster.lon)
            accuracyMeters = cluster.accuracyMeters.coerceAtLeast(MIN_ACCURACY_METERS)
            lastUpdateElapsedMillis = measurement.elapsedRealtimeMillis
            lastUncertaintyElapsedMillis = measurement.elapsedRealtimeMillis
            pending = null
            return current(true)
        }

        pending = null
        val stateVariance = currentAccuracy * currentAccuracy
        val measurementVariance = measurement.accuracyMeters * measurement.accuracyMeters
        val gain = (stateVariance / (stateVariance + measurementVariance)).coerceIn(0.08f, 0.85f)
        val deadband = maxOf(MIN_POSITION_DEADBAND_METERS, minOf(4.0, currentAccuracy * 0.08))
        val maxCorrection = if (measurement.accuracyMeters <= VERY_ACCURATE_FIX_METERS) 10.0 else 4.0
        val effectiveGain = when {
            distance <= deadband -> 0.0
            else -> minOf(gain.toDouble(), maxCorrection / distance)
        }
        point = GeoMath.blend(current.lat, current.lon, measured.lat, measured.lon, effectiveGain)
        accuracyMeters = sqrt((1f - gain) * stateVariance).coerceAtLeast(MIN_ACCURACY_METERS)
        lastUpdateElapsedMillis = measurement.elapsedRealtimeMillis
        lastUncertaintyElapsedMillis = measurement.elapsedRealtimeMillis
        return current(true)
    }

    fun moveByStep(distanceMeters: Double, headingDegrees: Double): Result {
        val current = point ?: return current(false, "no anchor")
        point = GeoMath.move(current.lat, current.lon, distanceMeters, headingDegrees)
        accuracyMeters = hypot(
            (accuracyMeters ?: MAX_INITIAL_ACCURACY_METERS).toDouble(),
            STEP_UNCERTAINTY_METERS,
        ).toFloat().coerceAtMost(MAX_TRACK_ACCURACY_METERS)
        return current(true)
    }

    fun current(): Result = current(accepted = false)

    private fun current(accepted: Boolean, reason: String? = null) =
        Result(point, accuracyMeters, accepted, reason)

    private fun accumulateCluster(measured: GeoPoint, accuracy: Float, radiusMeters: Double): PendingCluster {
        val old = pending
        val sameCluster = old != null && GeoMath.distanceMeters(old.lat, old.lon, measured.lat, measured.lon) <= radiusMeters
        pending = if (sameCluster) {
            val n = old!!.count + 1
            PendingCluster(
                lat = (old.lat * old.count + measured.lat) / n,
                lon = (old.lon * old.count + measured.lon) / n,
                accuracyMeters = minOf(old.accuracyMeters, accuracy),
                count = n,
            )
        } else {
            PendingCluster(measured.lat, measured.lon, accuracy, 1)
        }
        return pending!!
    }

    private fun accumulateBootstrapCluster(source: String, measured: GeoPoint, accuracy: Float): PendingCluster {
        val old = bootstrapClusters[source]
        val sameCluster = old != null && GeoMath.distanceMeters(old.lat, old.lon, measured.lat, measured.lon) <= INITIAL_CLUSTER_RADIUS_METERS
        val updated = if (sameCluster) {
            val n = old!!.count + 1
            PendingCluster(
                lat = (old.lat * old.count + measured.lat) / n,
                lon = (old.lon * old.count + measured.lon) / n,
                accuracyMeters = minOf(old.accuracyMeters, accuracy),
                count = n,
            )
        } else {
            PendingCluster(measured.lat, measured.lon, accuracy, 1)
        }
        bootstrapClusters[source] = updated
        return updated
    }

    private fun growUncertainty(nowElapsedMillis: Long) {
        val previous = lastUncertaintyElapsedMillis ?: lastUpdateElapsedMillis ?: return
        val seconds = ((nowElapsedMillis - previous).coerceAtLeast(0L) / 1_000.0).coerceAtMost(30.0)
        val old = accuracyMeters?.toDouble() ?: return
        accuracyMeters = hypot(old, seconds * PASSIVE_DRIFT_METERS_PER_SECOND).toFloat()
            .coerceAtMost(MAX_TRACK_ACCURACY_METERS)
        lastUncertaintyElapsedMillis = nowElapsedMillis
    }

    companion object {
        const val MAX_LOCATION_AGE_MILLIS = 30_000L
        private const val MAX_MEASUREMENT_ACCURACY_METERS = 75f
        private const val MAX_INITIAL_ACCURACY_METERS = 35f
        private const val MAX_BOOTSTRAP_ACCURACY_METERS = 200f
        private const val MAX_TRACK_ACCURACY_METERS = 100f
        private const val MIN_ACCURACY_METERS = 2f
        private const val VERY_ACCURATE_FIX_METERS = 8f
        private const val MIN_OUTLIER_GATE_METERS = 10.0
        private const val MIN_CLUSTER_RADIUS_METERS = 8.0
        private const val INITIAL_CLUSTER_RADIUS_METERS = 30.0
        private const val COARSE_CONFIRMATION_RADIUS_METERS = 20.0
        private const val MIN_POSITION_DEADBAND_METERS = 1.8
        private const val OUTLIER_SIGMA = 1.25
        private const val REQUIRED_INITIAL_CONFIRMATIONS = 2
        private const val REQUIRED_OUTLIER_CONFIRMATIONS = 5
        private const val STEP_UNCERTAINTY_METERS = 0.55
        private const val PASSIVE_DRIFT_METERS_PER_SECOND = 0.08
    }
}
