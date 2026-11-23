/*
 * Copyright (C) 2026 SuShengWen(ZhuZhu)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 設定預設值純邏輯測試（STEP 4，DCL-13）：只測不碰 Android API 的
 * 預設建構與 clamped()；SharedPreferences 的 load()/save() 走實機，
 * 不得以本測試冒充。眨眼點擊預設關閉，且 clamped() 不得翻轉該開關。
 */
class GazeSettingsDefaultsTest {

    @Test
    fun blinkClick_defaultIsOff() {
        assertFalse(GazeSettings().blinkClickEnabled)
    }

    @Test
    fun blinkClick_clampedPreservesValue() {
        assertTrue(GazeSettings(blinkClickEnabled = true).clamped().blinkClickEnabled)
        assertFalse(GazeSettings(blinkClickEnabled = false).clamped().blinkClickEnabled)
    }

    @Test
    fun blinkWindowDefaults_unchangedBySwitchAddition() {
        val s = GazeSettings()
        assertEquals(150L, s.blinkMinMs)
        assertEquals(600L, s.blinkMaxMs)
        assertEquals(0.08, s.blinkThreshold, 0.0)
    }

    @Test
    fun otherDefaults_unchanged() {
        val s = GazeSettings()
        assertEquals(1000L, s.dwellTimeMs)
        assertEquals(1200L, s.cooldownMs)
        assertTrue(s.edgeGesturesEnabled)
        assertTrue(s.diagnosticLogging)
        assertTrue(s.remoteMonitor)
        assertFalse(s.gyroCompensation)
    }
}
