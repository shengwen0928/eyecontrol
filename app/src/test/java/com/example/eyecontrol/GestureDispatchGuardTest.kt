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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GestureDispatchGuard 測試（DCL-04，STEP 3）。
 *
 * 覆蓋補充指令要求的十項情境：回傳 false、onCancelled、onCompleted、逾時、
 * Service 停止、暫停、新手勢搶占、過期回呼，另加清理單次性與不重播斷言。
 * 逾時計時器排程未接線（提案 P-04T），此處只測逾時「決策」本身。
 */
class GestureDispatchGuardTest {

    @Test
    fun begin_returnsIncreasingUniqueIds() {
        val guard = GestureDispatchGuard()
        val first = guard.begin()
        guard.onCompleted(first)
        val second = guard.begin()
        assertNotEquals(first, second)
        assertTrue(second > first)
        assertEquals(GestureDispatchGuard.State.IN_FLIGHT, guard.state)
        assertEquals(second, guard.currentId)
    }

    @Test
    fun dispatchReturned_false_cleansUpOnce_andLaterCallbacksIgnored() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        assertTrue(guard.onDispatchReturned(id, accepted = false))
        assertEquals(GestureDispatchGuard.State.IDLE, guard.state)
        assertNull(guard.currentId)
        assertEquals(1, guard.cleanupCount)
        assertEquals(GestureDispatchGuard.TerminalKind.REJECTED, guard.lastTerminalKind)
        // 後到的完成／取消回呼皆為過期，不得再清理、不得改變狀態。
        assertFalse(guard.onCompleted(id))
        assertFalse(guard.onCancelled(id))
        assertEquals(1, guard.cleanupCount)
    }

    @Test
    fun dispatchReturned_true_keepsInFlight_thenCompleted() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        assertFalse(guard.onDispatchReturned(id, accepted = true))
        assertEquals(GestureDispatchGuard.State.IN_FLIGHT, guard.state)
        assertEquals(0, guard.cleanupCount)
        assertTrue(guard.onCompleted(id))
        assertEquals(1, guard.cleanupCount)
        assertEquals(GestureDispatchGuard.TerminalKind.COMPLETED, guard.lastTerminalKind)
    }

    @Test
    fun onCancelled_cleansUpOnce() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        assertTrue(guard.onCancelled(id))
        assertEquals(GestureDispatchGuard.State.IDLE, guard.state)
        assertEquals(1, guard.cleanupCount)
        assertEquals(GestureDispatchGuard.TerminalKind.CANCELLED, guard.lastTerminalKind)
        // 重複取消同一 ID：已清理，不得重計。
        assertFalse(guard.onCancelled(id))
        assertEquals(1, guard.cleanupCount)
    }

    @Test
    fun onTimeout_cleansUpOnce_staleTimeoutIgnored() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        assertTrue(guard.onTimeout(id))
        assertEquals(1, guard.cleanupCount)
        assertEquals(GestureDispatchGuard.TerminalKind.TIMED_OUT, guard.lastTerminalKind)
        assertFalse(guard.onTimeout(id))
        assertEquals(1, guard.cleanupCount)
        // 舊 ID 的逾時不得影響新手勢。
        val fresh = guard.begin()
        assertFalse(guard.onTimeout(id))
        assertEquals(fresh, guard.currentId)
        assertEquals(1, guard.cleanupCount)
    }

    @Test
    fun interrupt_serviceStopped_cleansUpOnce_andIdleIsNoop() {
        val guard = GestureDispatchGuard()
        assertFalse(guard.onInterrupted())
        assertEquals(0, guard.cleanupCount)
        val id = guard.begin()
        assertTrue(guard.onInterrupted())
        assertEquals(1, guard.cleanupCount)
        assertEquals(GestureDispatchGuard.TerminalKind.INTERRUPTED, guard.lastTerminalKind)
        assertFalse(guard.onCompleted(id))
        assertFalse(guard.onCancelled(id))
        assertEquals(1, guard.cleanupCount)
    }

    @Test
    fun interrupt_pause_thenFreshBegin_isNewIntent() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        guard.onInterrupted()
        val fresh = guard.begin()
        assertNotEquals(id, fresh)
        assertTrue(guard.onCompleted(fresh))
        assertEquals(2, guard.cleanupCount)
    }

    @Test
    fun preemption_supersedesOld_oldCallbacksIgnored() {
        val guard = GestureDispatchGuard()
        val old = guard.begin()
        val fresh = guard.begin()
        // 舊手勢被搶占：計一次清理；舊回呼一律過期。
        assertEquals(1, guard.cleanupCount)
        assertEquals(GestureDispatchGuard.TerminalKind.SUPERSEDED, guard.lastTerminalKind)
        assertFalse(guard.onCompleted(old))
        assertFalse(guard.onCancelled(old))
        assertFalse(guard.onTimeout(old))
        assertEquals(1, guard.cleanupCount)
        // 新手勢正常完成，再計一次。
        assertTrue(guard.onCompleted(fresh))
        assertEquals(2, guard.cleanupCount)
    }

    @Test
    fun staleCallbackAfterCompletion_ignored_noDoubleCleanup() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        assertTrue(guard.onCompleted(id))
        assertFalse(guard.onCompleted(id))
        assertFalse(guard.onCancelled(id))
        assertFalse(guard.onDispatchReturned(id, accepted = false))
        assertEquals(1, guard.cleanupCount)
    }

    @Test
    fun repeatedTerminalCalls_cleanupExactlyOnce() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        guard.onCompleted(id)
        guard.onCancelled(id)
        guard.onTimeout(id)
        guard.onInterrupted()
        guard.onDispatchReturned(id, accepted = false)
        assertEquals(1, guard.cleanupCount)
        assertEquals(GestureDispatchGuard.State.IDLE, guard.state)
    }

    @Test
    fun noReplay_afterCancel_cancelledIdNeverResurrects() {
        val guard = GestureDispatchGuard()
        val id = guard.begin()
        guard.onCancelled(id)
        // 守衛永不重發：取消後狀態為 IDLE、無進行中 ID；下一次派發必須是
        // 新的 begin（新意圖），且取得全新 ID；舊 ID 的任何呼叫皆無效。
        assertEquals(GestureDispatchGuard.State.IDLE, guard.state)
        assertNull(guard.currentId)
        assertFalse(guard.onCompleted(id))
        val fresh = guard.begin()
        assertNotEquals(id, fresh)
        assertFalse(guard.onCompleted(id))
        assertTrue(guard.onCompleted(fresh))
    }
}
