package com.localzet.floortrace.positioning

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class FloorEstimator {
    private var baselinePressureHpa: Float? = null
    private var baselineFloor: Int = 0
    private var floorHeightMeters: Double = 3.1
    private var filteredPressureHpa: Double? = null

    data class Estimate(
        val relativeAltitudeMeters: Double? = null,
        val floor: Int? = null,
        val confidence: Float = 0f,
        val calibrated: Boolean = false,
    )

    fun updatePressure(pressureHpa: Float): Estimate {
        filteredPressureHpa = filteredPressureHpa?.let { it * 0.88 + pressureHpa * 0.12 } ?: pressureHpa.toDouble()
        return current()
    }

    fun calibrate(currentPressureHpa: Float?, floor: Int, floorHeightMeters: Double) {
        val p = currentPressureHpa ?: filteredPressureHpa?.toFloat() ?: return
        baselinePressureHpa = p
        filteredPressureHpa = p.toDouble()
        baselineFloor = floor
        this.floorHeightMeters = floorHeightMeters.coerceIn(2.2, 6.0)
    }

    fun clear() {
        baselinePressureHpa = null
    }

    fun current(): Estimate {
        val baseline = baselinePressureHpa ?: return Estimate(calibrated = false)
        val current = filteredPressureHpa ?: return Estimate(calibrated = true)
        val deltaAltitude = 44_330.0 * (1.0 - (current / baseline).pow(0.190294957))
        val rawFloors = deltaAltitude / floorHeightMeters
        val floor = baselineFloor + rawFloors.roundToInt()
        val residual = abs(rawFloors - rawFloors.roundToInt())
        val confidence = (1.0 - residual * 1.45).coerceIn(0.15, 0.95).toFloat()
        return Estimate(deltaAltitude, floor, confidence, calibrated = true)
    }
}
