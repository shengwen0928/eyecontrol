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

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 邊緣開關恢復序列測試（DCL-02 第 (4)(5)(2) 項，STEP 3 補充驗證）。
 *
 * 驗證控制器層級的語義：總開關關閉時全部停用；以同一組分項狀態重開後，
 * 先前儲存的左右狀態恢復（左關右正常、右關左正常）。設定儲存／UI 開關列
 * 的保存路徑為 Android 程式碼，以靜態核對驗證（見 fix log F-9），不在此測。
 *
 * 驅動方式沿用 EdgeGestureControllerTest 的虛擬時鐘模式（時間由呼叫端傳入）；
 * 每幀可換 config，以模擬「總開關關閉 → 重開」的序列。螢幕 1080×2400、
 * 密度 1.0：邊緣帶 50 px；dwell 1000 ms，餵 12 幀 × 100 ms 即滿 dwell。
 * 左右 Execute 不需方向位移（淨位移小於死區時視為未反駁方向）。
 */
class EdgeSwitchRestoreTest {

    private val dwellMs = 1000L

    private fun frame(
        point: Pair<Float, Float>,
        nowMs: Long,
    ): EdgeGestureFrame = EdgeGestureFrame(
        filteredX = point.first,
        filteredY = point.second,
        rawX = point.first.toDouble(),
        rawY = point.second.toDouble(),
        facePresent = true,
        eyesOpen = true,
        featuresFinite = true,
        imageDegraded = false,
        nowMs = nowMs,
        screenW = 1080,
        screenH = 2400,
        density = 1.0f,
    )

    private fun feed(
        controller: EdgeGestureController,
        point: Pair<Float, Float>,
        config: EdgeGestureConfig,
        frames: Int,
        startMs: Long,
    ): List<EdgeDecision> {
        val out = mutableListOf<EdgeDecision>()
        var now = startMs
        repeat(frames) {
            out.add(controller.update(frame(point, now), config))
            now += 100L
        }
        return out
    }

    @Test
    fun globalOff_blocksAll_thenReopenRestoresLeftOffRightOn() {
        val controller = EdgeGestureController()
        val leftPoint = 10f to 1200f
        val rightPoint = 1070f to 1200f
        // (4) 總開關關閉：左右皆無動作（即使在邊緣帶內）。
        val stored = EdgeGestureConfig(
            enabled = true,
            leftEnabled = false,
            rightEnabled = true,
            dwellTimeMs = dwellMs,
        )
        val offDecisions = feed(
            controller, rightPoint,
            stored.copy(enabled = false), frames = 12, startMs = 0L,
        )
        assertTrue(offDecisions.all { it is EdgeDecision.NoAction })
        // (5) 總開關重開、同一組分項狀態：左關（無動作）、右開（執行）。
        val leftDecisions = feed(
            controller, leftPoint,
            stored, frames = 12, startMs = 1200L,
        )
        assertTrue(leftDecisions.all { it is EdgeDecision.NoAction })
        val rightDecisions = feed(
            controller, rightPoint,
            stored, frames = 12, startMs = 2400L,
        )
        assertTrue(rightDecisions.any { it is EdgeDecision.Execute })
    }

    @Test
    fun closingOneSide_doesNotAffectOtherSide() {
        val controller = EdgeGestureController()
        val leftPoint = 10f to 1200f
        val rightPoint = 1070f to 1200f
        // (2) 右關左開：左執行、右無動作。
        val config = EdgeGestureConfig(
            leftEnabled = true,
            rightEnabled = false,
            dwellTimeMs = dwellMs,
        )
        val leftDecisions = feed(controller, leftPoint, config, frames = 12, startMs = 0L)
        assertTrue(leftDecisions.any { it is EdgeDecision.Execute })
        val rightDecisions = feed(controller, rightPoint, config, frames = 12, startMs = 1200L)
        assertTrue(rightDecisions.all { it is EdgeDecision.NoAction })
    }

    @Test
    fun globalOff_midCandidate_cancels_thenReopenResumesFresh() {
        val controller = EdgeGestureController()
        val rightPoint = 1070f to 1200f
        val on = EdgeGestureConfig(rightEnabled = true, dwellTimeMs = dwellMs)
        // 先累積候選 5 幀，再關總開關：進行中候選取消。
        val before = feed(controller, rightPoint, on, frames = 5, startMs = 0L)
        assertTrue(before.any { it is EdgeDecision.Candidate })
        val off = feed(
            controller, rightPoint,
            on.copy(enabled = false), frames = 2, startMs = 500L,
        )
        assertTrue(off.any { it is EdgeDecision.Cancelled })
        // 重開後須重新累積滿 dwell 才執行（舊進度不沿用）。
        val after = feed(controller, rightPoint, on, frames = 12, startMs = 700L)
        assertTrue(after.any { it is EdgeDecision.Execute })
    }
}
