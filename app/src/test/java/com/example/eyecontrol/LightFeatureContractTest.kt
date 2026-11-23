/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光特徵純函數契約測試：只測 GazeFeatureExtractor 的光換算（不碰
 * MediaPipe landmark 型別、不碰感測器）。數值皆為合成輸入，只驗契約，
 * 不宣稱任何實機精度。
 */
class LightFeatureContractTest {

    @Test
    fun lightLogLux_validLux_log10() {
        assertEquals(2.0, GazeFeatureExtractor.lightLogLux(100f), 1e-9)
        assertEquals(0.0, GazeFeatureExtractor.lightLogLux(1f), 1e-9)
        assertEquals(3.0, GazeFeatureExtractor.lightLogLux(1000f), 1e-9)
    }

    @Test
    fun lightLogLux_invalid_returnsNaN() {
        assertTrue(GazeFeatureExtractor.lightLogLux(Float.NaN).isNaN())
        assertTrue(GazeFeatureExtractor.lightLogLux(Float.POSITIVE_INFINITY).isNaN())
        assertTrue(GazeFeatureExtractor.lightLogLux(-5f).isNaN())
    }

    @Test
    fun lightLogLux_zeroLux_floorNotNegativeInfinity() {
        assertEquals(0.0, GazeFeatureExtractor.lightLogLux(0f), 0.0)
    }

    @Test
    fun lightCentered_centerIsZero() {
        assertEquals(0.0, GazeFeatureExtractor.lightCentered(2.0), 0.0)
        assertEquals(-2.0, GazeFeatureExtractor.lightCentered(0.0), 0.0)
        assertEquals(3.0, GazeFeatureExtractor.lightCentered(5.0), 0.0)
    }

    @Test
    fun lightCentered_clampedToAbsoluteRange() {
        assertEquals(-2.0, GazeFeatureExtractor.lightCentered(-3.0), 0.0)
        assertEquals(3.0, GazeFeatureExtractor.lightCentered(9.0), 0.0)
    }

    @Test
    fun lightCentered_invalid_returnsCenter() {
        assertEquals(0.0, GazeFeatureExtractor.lightCentered(Double.NaN), 0.0)
        assertEquals(0.0, GazeFeatureExtractor.lightCentered(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun lightFeatures_mainAndInteractions() {
        val light = GazeFeatureExtractor.lightFeatures(3.0, 0.2, -0.1)
        assertEquals(3, light.size)
        assertEquals(1.0, light[0], 1e-9)
        assertEquals(0.2, light[1], 1e-9)
        assertEquals(-0.1, light[2], 1e-9)
    }

    @Test
    fun lightFeatures_unknownLux_allZero() {
        val light = GazeFeatureExtractor.lightFeatures(Double.NaN, 0.2, -0.1)
        assertEquals(0.0, light[0], 0.0)
        assertEquals(0.0, light[1], 0.0)
        assertEquals(0.0, light[2], 0.0)
    }
}
