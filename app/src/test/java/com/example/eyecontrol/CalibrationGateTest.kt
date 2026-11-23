/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationGateTest {

    private fun sample(x: Double, y: Double): DoubleArray =
        doubleArrayOf(x, y, 0.5, 0.5, 0.0)

    private fun gridTargets(): List<Pair<Float, Float>> =
        listOf(0.08f, 0.5f, 0.92f).flatMap { x ->
            listOf(0.08f, 0.5f, 0.92f).map { y -> x to y }
        }

    @Test
    fun separatedClusters_pass() {
        // 九個目標各自聚在不同虹膜位置、抖動小：分離度遠大於雜訊，通過。
        val features = mutableListOf<DoubleArray>()
        val targets = mutableListOf<Pair<Float, Float>>()
        for ((i, t) in gridTargets().withIndex()) {
            val cx = (i % 3) * 10.0
            val cy = (i / 3) * 10.0
            repeat(5) { k ->
                features.add(sample(cx + k * 0.1, cy - k * 0.1))
                targets.add(t)
            }
        }
        val r = CalibrationGate.evaluate(features, targets)
        assertEquals(9, r.groupCount)
        assertTrue(r.passed)
        assertTrue(r.separationRatio > CalibrationGate.SEPARATION_RATIO_MIN)
    }

    @Test
    fun staringAtCenter_fails() {
        // 全部看中間：九個目標的樣本全擠在同一處，分離度約等於雜訊，不通過。
        val features = mutableListOf<DoubleArray>()
        val targets = mutableListOf<Pair<Float, Float>>()
        for (t in gridTargets()) {
            repeat(5) { k ->
                features.add(sample(5.0 + k * 0.1, 5.0 - k * 0.1))
                targets.add(t)
            }
        }
        val r = CalibrationGate.evaluate(features, targets)
        assertEquals(9, r.groupCount)
        assertFalse(r.passed)
        assertTrue(r.separationRatio < CalibrationGate.SEPARATION_RATIO_MIN)
    }

    @Test
    fun twoGroupsSeparated_passes() {
        val features = listOf(
            sample(0.0, 0.0), sample(0.1, 0.0), sample(0.0, 0.1),
            sample(10.0, 10.0), sample(10.1, 10.0), sample(10.0, 10.1),
        )
        val targets = listOf(0f to 0f, 0f to 0f, 0f to 0f, 1f to 1f, 1f to 1f, 1f to 1f)
        assertTrue(CalibrationGate.evaluate(features, targets).passed)
    }

    @Test
    fun singleGroup_fails() {
        val features = listOf(sample(0.0, 0.0), sample(0.1, 0.0))
        val targets = listOf(0f to 0f, 0f to 0f)
        val r = CalibrationGate.evaluate(features, targets)
        assertFalse(r.passed)
        assertEquals(1, r.groupCount)
    }

    @Test
    fun empty_fails() {
        val r = CalibrationGate.evaluate(emptyList(), emptyList())
        assertFalse(r.passed)
        assertEquals(0, r.groupCount)
    }

    @Test
    fun nonFiniteSamplesSkipped() {
        val features = listOf(
            sample(0.0, 0.0), sample(0.1, 0.0),
            doubleArrayOf(Double.NaN, 0.0, 0.5, 0.5, 0.0),
            sample(10.0, 10.0), sample(10.1, 10.0),
        )
        val targets = listOf(0f to 0f, 0f to 0f, 0f to 0f, 1f to 1f, 1f to 1f)
        val r = CalibrationGate.evaluate(features, targets)
        assertEquals(2, r.groupCount)
        assertTrue(r.passed)
    }

    @Test
    fun noisyButSeparated_failsWhenRatioBelowThreshold() {
        // 中心分離 10、組內樣本到中心平均約 5.7：比值約 1.8，低於門檻，不通過。
        val features = listOf(
            sample(0.0, 0.0), sample(8.0, 0.0), sample(0.0, 8.0), sample(8.0, 8.0),
            sample(10.0, 0.0), sample(18.0, 0.0), sample(10.0, 8.0), sample(18.0, 8.0),
        )
        val targets = listOf(0f to 0f, 0f to 0f, 0f to 0f, 0f to 0f, 1f to 0f, 1f to 0f, 1f to 0f, 1f to 0f)
        assertFalse(CalibrationGate.evaluate(features, targets).passed)
    }
}
