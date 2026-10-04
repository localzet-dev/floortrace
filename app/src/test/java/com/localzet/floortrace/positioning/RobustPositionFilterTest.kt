package com.localzet.floortrace.positioning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustPositionFilterTest {
    private fun fix(lat: Double, lon: Double, accuracy: Float, time: Long) =
        RobustPositionFilter.Measurement(lat, lon, accuracy, time)

    @Test
    fun `does not initialize from a coarse indoor fix`() {
        val filter = RobustPositionFilter()
        val result = filter.update(fix(47.227583, 39.712851, 100f, 1_000))
        assertFalse(result.accepted)
        assertNull(result.point)
    }

    @Test
    fun `stable coarse fixes bootstrap without moving around`() {
        val filter = RobustPositionFilter()
        assertFalse(filter.update(fix(47.227583, 39.712851, 100f, 1_000)).accepted)
        val result = filter.update(fix(47.227584, 39.712852, 100f, 2_000))
        assertTrue(result.accepted)
        assertNotNull(result.point)
        assertTrue(result.accuracyMeters == 100f)
    }

    @Test
    fun `single jump to neighboring wing is rejected`() {
        val filter = RobustPositionFilter()
        assertTrue(filter.update(fix(47.227500, 39.712500, 7f, 1_000)).accepted)
        val jump = filter.update(fix(47.227500, 39.712900, 7f, 2_000))
        assertFalse(jump.accepted)
        assertNotNull(jump.point)
        assertTrue(jump.rejectionReason!!.startsWith("outlier"))
    }

    @Test
    fun `persistent displaced fixes eventually reanchor`() {
        val filter = RobustPositionFilter()
        filter.update(fix(47.227500, 39.712500, 7f, 1_000))
        var result: RobustPositionFilter.Result? = null
        repeat(5) { index ->
            result = filter.update(fix(47.227500, 39.712900, 7f, 2_000L + index * 1_000L))
        }
        assertTrue(result!!.accepted)
        assertTrue(result!!.point!!.lon > 39.7128)
    }

    @Test
    fun `normal fixes blend and improve uncertainty`() {
        val filter = RobustPositionFilter()
        val first = filter.update(fix(47.227500, 39.712500, 12f, 1_000))
        val second = filter.update(fix(47.227510, 39.712510, 6f, 2_000))
        assertTrue(second.accepted)
        assertTrue(second.accuracyMeters!! < first.accuracyMeters!!)
    }
}
