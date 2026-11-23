package com.example.eyecontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STEP 3 薄守衛純決策測試：眨眼點擊判定與撤回同意的丟棄判定。
 * 只測純函式；檔案寫入、上傳、手勢注入等副作用留在呼叫端，
 * 走 CI 編譯＋實機驗證，不得以本測試冒充。
 */
class GuardDecisionsTest {

    // 眨眼點擊：開關關閉時永不觸發（DCL-13 預設關閉）。
    @Test
    fun blinkClick_disabled_neverTriggers() {
        assertFalse(
            GuardDecisions.shouldTriggerBlinkClick(
                blinkClickEnabled = false,
                blinkDurationMs = 300L,
                blinkMinMs = 150L,
                blinkMaxMs = 600L,
                hasValidCursor = true,
                cooldownElapsed = true,
                paused = false,
                reminderActive = false,
            ),
        )
    }

    // 眨眼點擊：開關開啟＋時間窗內＋其餘條件全過 → 觸發。
    @Test
    fun blinkClick_enabledInWindow_triggers() {
        assertTrue(
            GuardDecisions.shouldTriggerBlinkClick(
                blinkClickEnabled = true,
                blinkDurationMs = 300L,
                blinkMinMs = 150L,
                blinkMaxMs = 600L,
                hasValidCursor = true,
                cooldownElapsed = true,
                paused = false,
                reminderActive = false,
            ),
        )
    }

    // 時間窗邊界含端點：150 ms 與 600 ms 皆在窗內（沿用現況 `in` 語義）。
    @Test
    fun blinkClick_windowBoundsInclusive() {
        for (duration in listOf(150L, 600L)) {
            assertTrue(
                "duration=$duration",
                GuardDecisions.shouldTriggerBlinkClick(
                    blinkClickEnabled = true,
                    blinkDurationMs = duration,
                    blinkMinMs = 150L,
                    blinkMaxMs = 600L,
                    hasValidCursor = true,
                    cooldownElapsed = true,
                    paused = false,
                    reminderActive = false,
                ),
            )
        }
    }

    // 時間窗外（過短／過長）不觸發。
    @Test
    fun blinkClick_outsideWindow_doesNotTrigger() {
        for (duration in listOf(149L, 601L)) {
            assertFalse(
                "duration=$duration",
                GuardDecisions.shouldTriggerBlinkClick(
                    blinkClickEnabled = true,
                    blinkDurationMs = duration,
                    blinkMinMs = 150L,
                    blinkMaxMs = 600L,
                    hasValidCursor = true,
                    cooldownElapsed = true,
                    paused = false,
                    reminderActive = false,
                ),
            )
        }
    }

    // 其餘守衛任一未過即不觸發（一次只換一個條件）。
    @Test
    fun blinkClick_eachOtherGuardBlocks() {
        val base = GuardDecisions::shouldTriggerBlinkClick
        assertFalse(base(true, 300L, 150L, 600L, false, true, false, false))
        assertFalse(base(true, 300L, 150L, 600L, true, false, false, false))
        assertFalse(base(true, 300L, 150L, 600L, true, true, true, false))
        assertFalse(base(true, 300L, 150L, 600L, true, true, false, true))
    }

    // 撤回同意：只有「開 → 關」才丟棄當下檔案（R-1 真值表）。
    @Test
    fun revoke_onlyOnToOff_discardsCurrentFile() {
        assertTrue(GuardDecisions.shouldDiscardCurrentFileOnRevoke(true, false))
        assertFalse(GuardDecisions.shouldDiscardCurrentFileOnRevoke(true, true))
        assertFalse(GuardDecisions.shouldDiscardCurrentFileOnRevoke(false, false))
        assertFalse(GuardDecisions.shouldDiscardCurrentFileOnRevoke(false, true))
    }
}
