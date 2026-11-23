/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回歸測試：鎖定 `OneEuroFilter` 的現況行為（TEST_PLAN §2.4）。
 * 首幀直通、突波限幅、死區平滑、`reset()` 後首幀直通。
 */
class OneEuroFilterTest {

    @Test
    fun firstFrame_passesThrough() {
        val filter = OneEuroFilter()
        assertEquals(100.0, filter.filter(100.0, 0.0), 0.0)
    }

    @Test
    fun stepInput_outputMovesTowardTargetWithoutOvershoot() {
        val filter = OneEuroFilter()
        filter.filter(0.0, 0.0)
        var previous = 0.0
        var output = 0.0
        for (i in 1..60) {
            output = filter.filter(100.0, i / 60.0)
            assertTrue(output >= previous)
            assertTrue(output <= 100.0)
            previous = output
        }
        assertTrue(output > 0.0)
        assertTrue(output < 100.0)
    }

    @Test
    fun spikeInput_isVelocityClamped() {
        val filter = OneEuroFilter()
        filter.filter(0.0, 0.0)
        // 10 ms 內跳 10000：原始速度遠超最大速度上限，目標先被限幅再平滑。
        val output = filter.filter(10000.0, 0.01)
        assertTrue(output < 100.0)
        assertTrue(output <= 35.0 + 1e-9)
        assertTrue(output > 0.0)
    }

    @Test
    fun smallJitterWithinDeadBand_movesSlowly() {
        val filter = OneEuroFilter()
        filter.filter(100.0, 0.0)
        var output = 100.0
        for (i in 1..10) {
            output = filter.filter(102.0, i / 60.0)
        }
        assertTrue(output > 100.0)
        assertTrue(output < 101.0)
    }

    @Test
    fun steadyInput_outputStaysFiniteAndNearInput() {
        val filter = OneEuroFilter()
        var output = 0.0
        for (i in 0..60) {
            output = filter.filter(50.0, i / 60.0)
            assertTrue(output.isFinite())
        }
        assertEquals(50.0, output, 0.001)
    }

    @Test
    fun reset_firstFrameAfterResetPassesThrough() {
        val filter = OneEuroFilter()
        filter.filter(0.0, 0.0)
        filter.filter(100.0, 1.0)
        filter.reset()
        assertEquals(42.0, filter.filter(42.0, 2.0), 0.0)
    }

    @Test
    fun updateParams_doesNotBreakFiltering() {
        val filter = OneEuroFilter()
        filter.filter(0.0, 0.0)
        filter.updateParams(0.5, 0.02)
        val output = filter.filter(100.0, 1.0 / 60.0)
        assertTrue(output.isFinite())
        assertTrue(output > 0.0)
        assertTrue(output < 100.0)
    }
}
