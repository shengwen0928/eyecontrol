package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回歸測試：鎖定 v0.5.9 `DwellTracker` 的現況行為（TEST_PLAN §2.4）。
 *
 * `DwellTracker` 的門檻常數為 private，測試以檔案註解載明的行為值
 * （取消半徑 2.5×、速度上限 2000 px/s、衰減 2×、離開 300 ms 重置、
 * 幀間隔夾 0–200 ms、錨點跟隨 20%）驗證可觀察行為，不得修改受測類別
 * 來暴露常數。
 */
class DwellTrackerTest {

    @Test
    fun firstUpdate_anchorsAndReturnsZero() {
        val tracker = DwellTracker()
        assertEquals(0f, tracker.update(100f, 200f, 0L, 1000L, 50f), 0f)
        assertEquals(100f, tracker.anchorX, 0f)
        assertEquals(200f, tracker.anchorY, 0f)
    }

    @Test
    fun steadyDwell_accumulatesToOne() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        var progress = 0f
        for (t in 100L..1000L step 100L) {
            progress = tracker.update(100f, 100f, t, 1000L, 50f)
        }
        assertEquals(1f, progress, 0.001f)
    }

    @Test
    fun steadyDwell_progressIncreasesMonotonically() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        var previous = 0f
        for (t in 100L..500L step 100L) {
            val progress = tracker.update(100f, 100f, t, 1000L, 50f)
            assertTrue(progress >= previous)
            previous = progress
        }
        assertEquals(0.5f, previous, 0.001f)
    }

    @Test
    fun slowDrift_anchorFollowsWithoutResetting() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        // 緩慢漂移 10 px：速度 100 px/s，未超速度上限；錨點跟隨 20%。
        val progress = tracker.update(110f, 100f, 100L, 1000L, 50f)
        assertTrue(progress > 0f)
        assertTrue(tracker.anchorX > 100f)
        assertTrue(tracker.anchorX < 110f)
        assertEquals(102f, tracker.anchorX, 0.001f)
    }

    @Test
    fun jumpBeyondCancelRadius_resetsAndReanchors() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        tracker.update(100f, 100f, 100L, 1000L, 50f)
        // 取消半徑 = 50 × 2.5 = 125；跳 200 px 視為移開。
        val progress = tracker.update(300f, 100f, 200L, 1000L, 50f)
        assertEquals(0f, progress, 0f)
        assertEquals(300f, tracker.anchorX, 0f)
    }

    @Test
    fun fastJumpWithinRadius_decaysInsteadOfAccumulating() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        val before = tracker.update(100f, 100f, 100L, 1000L, 50f)
        assertEquals(0.1f, before, 0.001f)
        // 10 ms 內跳 40 px：速度 4000 px/s，超過 2000 上限；進度以 2 倍速衰減。
        val after = tracker.update(140f, 100f, 110L, 1000L, 50f)
        assertTrue(after < before)
        assertTrue(after > 0f)
        assertEquals(0.08f, after, 0.001f)
    }

    @Test
    fun outOfRadiusBeyondTimeout_resets() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        for (t in 100L..900L step 100L) {
            tracker.update(100f, 100f, t, 1000L, 50f)
        }
        // 移到半徑外 60 px，並讓目標每幀遠離 12 px（抵銷錨點 20% 跟隨），
        // 使每幀到錨點的距離維持 60 px、持續走衰減分支；離開超過 300 ms 後重置。
        var progress = tracker.update(160f, 100f, 1000L, 1000L, 50f)
        assertEquals(0.7f, progress, 0.001f)
        progress = tracker.update(172f, 100f, 1100L, 1000L, 50f)
        progress = tracker.update(184f, 100f, 1200L, 1000L, 50f)
        progress = tracker.update(196f, 100f, 1300L, 1000L, 50f)
        assertEquals(0.1f, progress, 0.001f)
        progress = tracker.update(208f, 100f, 1400L, 1000L, 50f)
        assertEquals(0f, progress, 0f)
        assertEquals(208f, tracker.anchorX, 0.001f)
    }

    @Test
    fun longStall_dtClampedTo200ms() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        // 停 5 秒後的第一幀：幀間隔夾在 200 ms，只累積 200 ms 進度。
        val progress = tracker.update(100f, 100f, 5000L, 1000L, 50f)
        assertEquals(0.2f, progress, 0.001f)
    }

    @Test
    fun negativeDt_clampedToZero() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 1000L, 1000L, 50f)
        val progress = tracker.update(100f, 100f, 900L, 1000L, 50f)
        assertEquals(0f, progress, 0f)
    }

    @Test
    fun reset_clearsProgressAndReanchors() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 1000L, 50f)
        tracker.update(100f, 100f, 500L, 1000L, 50f)
        tracker.reset()
        assertEquals(0f, tracker.update(300f, 300f, 600L, 1000L, 50f), 0f)
        assertEquals(300f, tracker.anchorX, 0f)
        assertEquals(300f, tracker.anchorY, 0f)
    }

    @Test
    fun zeroDwellTime_returnsZero() {
        val tracker = DwellTracker()
        tracker.update(100f, 100f, 0L, 0L, 50f)
        assertEquals(0f, tracker.update(100f, 100f, 100L, 0L, 50f), 0f)
    }
}
