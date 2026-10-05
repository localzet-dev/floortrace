// SPDX-FileCopyrightText: 2026 Ivan Zorin <creator@localzet.com> (Localzet contributions)
// SPDX-License-Identifier: Apache-2.0
package com.localzet.floortrace.positioning.rtt

import com.localzet.floortrace.geo.GeoMath
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RttTrilaterationTest {
    private fun ranges(): List<RttRange> = listOf(
        -5.0 to -5.0, 5.0 to -5.0, -5.0 to 5.0, 5.0 to 5.0,
    ).mapIndexed { index, (east, north) ->
        val point = GeoMath.offsetMeters(47.0, 39.0, north, east)
        RttRange(RttAnchor("anchor-$index", point.lat, point.lon), hypot(east, north))
    }

    @Test
    fun `solves a surveyed four anchor layout`() {
        val solution = RttTrilateration.solve(ranges())
        assertNotNull(solution)
        assertTrue(GeoMath.distanceMeters(47.0, 39.0, solution!!.point.lat, solution.point.lon) < 0.1)
        assertTrue(solution.accuracyMeters.isFinite())
    }

    @Test
    fun `rejects invalid anchors and uncertainties`() {
        val samples = ranges()
        assertNull(RttTrilateration.solve(samples.map { it.copy(stdDevMeters = Double.NaN) }))
        assertNull(RttTrilateration.solve(samples.map { it.copy(stdDevMeters = -1.0) }))
        assertNull(RttTrilateration.solve(samples.map { it.copy(anchor = it.anchor.copy(lat = Double.NaN)) }))
    }

    @Test
    fun `repeated access point does not count as several anchors`() {
        val sample = ranges().first()
        assertNull(RttTrilateration.solve(List(4) { sample }))
    }
}
