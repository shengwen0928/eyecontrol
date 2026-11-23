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
