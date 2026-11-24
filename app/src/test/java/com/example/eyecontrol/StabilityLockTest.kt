/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StabilityLock] 行為測試：上鎖、解鎖、慢漂解鎖、抖動維持鎖定、重置。
 * 門檻用預設值（上鎖 60 px、解鎖 120 px、4 幀穩定、4 幀偏移解鎖）。
 */
class StabilityLockTest {

    @Test
    fun stablePoints_lockAfterStableFramesAndFreezeJitter() {
        val lock = StabilityLock()
        // 上鎖前原樣放行
        assertEquals(500f to 500f, lock.update(500f, 500f))
        assertEquals(505f to 498f, lock.update(505f, 498f))
        assertEquals(498f to 505f, lock.update(498f, 505f))
        // 第 4 幀上鎖：回傳 4 幀平均 (2006/4 = 501.5)
        assertEquals(501.5f to 501.5f, lock.update(503f, 503f))
        assertTrue(lock.isLocked)
        // 鎖定後小抖動一律回傳鎖定點
        assertEquals(501.5f to 501.5f, lock.update(520f, 480f))
        assertEquals(501.5f to 501.5f, lock.update(480f, 520f))
        assertTrue(lock.isLocked)
    }

    @Test
    fun largeJump_unlocksImmediately() {
        val lock = StabilityLock()
        repeat(4) { lock.update(500f, 500f) }
        assertTrue(lock.isLocked)
        assertEquals(800f to 500f, lock.update(800f, 500f))
        assertFalse(lock.isLocked)
    }

    @Test
    fun slowDrift_unlocksAfterExcursionFrames() {
        val lock = StabilityLock()
        repeat(4) { lock.update(500f, 500f) }
        assertTrue(lock.isLocked)
        // 80 px：超出上鎖半徑、未達解鎖半徑，前 3 幀維持鎖定
        assertEquals(500f to 500f, lock.update(580f, 500f))
        assertEquals(500f to 500f, lock.update(580f, 500f))
        assertEquals(500f to 500f, lock.update(580f, 500f))
        assertTrue(lock.isLocked)
        // 第 4 幀連續偏移：解鎖並放行當下座標
        assertEquals(580f to 500f, lock.update(580f, 500f))
        assertFalse(lock.isLocked)
    }

    @Test
    fun excursionInterruptedByReturn_staysLocked() {
        val lock = StabilityLock()
        repeat(4) { lock.update(500f, 500f) }
        // 偏移 2 幀後回到鎖定點附近：偏移計數歸零，不得解鎖
        lock.update(580f, 500f)
        lock.update(580f, 500f)
        assertEquals(500f to 500f, lock.update(505f, 500f))
        assertTrue(lock.isLocked)
        lock.update(580f, 500f)
        lock.update(580f, 500f)
        lock.update(580f, 500f)
        assertTrue(lock.isLocked)
    }

    @Test
    fun movingPoints_neverLock() {
        val lock = StabilityLock()
        // 每次跳 200 px：錨點不斷重設，永不上鎖、原樣放行
        assertEquals(100f to 100f, lock.update(100f, 100f))
        assertEquals(300f to 100f, lock.update(300f, 100f))
        assertEquals(100f to 300f, lock.update(100f, 300f))
        assertEquals(300f to 300f, lock.update(300f, 300f))
        assertEquals(100f to 100f, lock.update(100f, 100f))
        assertFalse(lock.isLocked)
    }

    @Test
    fun reset_clearsLockAndAnchor() {
        val lock = StabilityLock()
        repeat(4) { lock.update(500f, 500f) }
        assertTrue(lock.isLocked)
        lock.reset()
        assertFalse(lock.isLocked)
        assertEquals(520f to 480f, lock.update(520f, 480f))
        assertFalse(lock.isLocked)
    }

    @Test
    fun nonFiniteInput_resetsAndPassesThrough() {
        val lock = StabilityLock()
        repeat(4) { lock.update(500f, 500f) }
        assertTrue(lock.isLocked)
        val out = lock.update(Float.NaN, 500f)
        assertTrue(out.first.isNaN())
        assertFalse(lock.isLocked)
    }
}
