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
 * EdgeGestureController 測試（Phase 3 步驟 6，D3-1…D3-8；TEST_PLAN §2.2
 * E-T01…E-T14 的純邏輯部分）。
 *
 * 以虛擬時鐘＋合成幀序列驅動（時間由呼叫端傳入，不得用系統時間寫期望值）；
 * 門檻常數一律引用受測類別的 companion 常數，不得在測試內重寫數字。
 * 螢幕 1080×2400、密度 1.0：邊緣帶為 50 px（EDGE_BAND_DP × 密度，未達
 * 短邊上限）。Android 框架接線（服務呼叫點、確認目標繪製、設定頁開關）
 * 不可純 JVM 測，走實機清單 E-1…E-10，不得冒充。
 */
class EdgeGestureControllerTest {

    private val screenW = 1080
    private val screenH = 2400
    private val density = 1.0f
    private val dwellMs = 1000L

    private val config = EdgeGestureConfig(dwellTimeMs = dwellMs)

    private val topPoint = 540f to 10f
    private val bottomPoint = 540f to 2390f
    private val leftPoint = 10f to 1200f
    private val rightPoint = 1070f to 1200f
    private val centerPoint = 540f to 1200f

    /** 虛擬時鐘驅動器：固定步進推進 nowMs，逐幀餵控制器並收集判決。 */
    private class Driver(
        val controller: EdgeGestureController,
        val config: EdgeGestureConfig,
    ) {
        var nowMs = 0L

        fun feed(
            point: Pair<Float, Float>,
            count: Int,
            stepMs: Long = 100L,
            facePresent: Boolean = true,
            eyesOpen: Boolean = true,
            featuresFinite: Boolean = true,
            imageDegraded: Boolean = false,
            raw: Pair<Double, Double>? = null,
            screenW: Int = 1080,
            screenH: Int = 2400,
            density: Float = 1.0f,
        ): List<EdgeDecision> {
            val out = mutableListOf<EdgeDecision>()
            repeat(count) {
                out.add(
                    controller.update(
                        EdgeGestureFrame(
                            filteredX = point.first,
                            filteredY = point.second,
                            rawX = raw?.first ?: point.first.toDouble(),
                            rawY = raw?.second ?: point.second.toDouble(),
                            facePresent = facePresent,
                            eyesOpen = eyesOpen,
                            featuresFinite = featuresFinite,
                            imageDegraded = imageDegraded,
                            nowMs = nowMs,
                            screenW = screenW,
                            screenH = screenH,
                            density = density,
                        ),
                        config,
                    ),
                )
                nowMs += stepMs
            }
            return out
        }

        fun feedPoints(
            points: List<Pair<Float, Float>>,
            stepMs: Long = 100L,
        ): List<EdgeDecision> {
            val out = mutableListOf<EdgeDecision>()
            for (point in points) {
                out.add(
                    controller.update(
                        EdgeGestureFrame(
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
                        ),
                        config,
                    ),
                )
                nowMs += stepMs
            }
            return out
        }

        /** 餵確認目標注視，直到執行或耗盡次數，回傳全部判決。 */
        fun feedConfirm(
            controller: EdgeGestureController,
            count: Int,
            stepMs: Long = 100L,
        ): List<EdgeDecision> {
            val target = controller.confirmTarget(1080, 2400, 1.0f)
            return feed(target.centerX to target.centerY, count, stepMs)
        }
    }

    private fun hasExecute(decisions: List<EdgeDecision>, edge: Edge? = null): Boolean =
        decisions.any {
            it is EdgeDecision.Execute && (edge == null || it.edge == edge)
        }

    // ---- D3-4（任務編號）／PLAN D3-3：總開關＋分項開關（E-T01／E-T02） ----

    // 總開關關閉：任意邊緣序列全程 IDLE，無執行判決。
    @Test
    fun masterSwitchOff_neverExecutes() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config.copy(enabled = false))
        val decisions = driver.feed(topPoint, 15)
        assertFalse(hasExecute(decisions))
        assertTrue(decisions.all { it is EdgeDecision.NoAction })
        assertEquals(EdgeControllerState.IDLE, controller.state)
    }

    // 分項關閉頂部：頂部無執行；底部正常（經確認後執行 HOME）。
    @Test
    fun perEdgeSwitch_topDisabledBottomWorks() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config.copy(topEnabled = false))
        assertFalse(hasExecute(driver.feed(topPoint, 15), Edge.TOP))
        val toConfirm = driver.feed(bottomPoint, 12)
        assertTrue(toConfirm.any { it is EdgeDecision.EnterConfirm })
        assertTrue(hasExecute(driver.feedConfirm(controller, 12), Edge.BOTTOM))
    }

    // ---- D3-5：頂／底部二階段確認（E-T03／E-T04／E-T05） ----

    // 頂部候選滿 dwell → 進入確認 → 確認目標 dwell 滿 → 執行 BACK。
    @Test
    fun topTwoStage_executesBackAfterConfirm() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val toConfirm = driver.feed(topPoint, 12)
        assertFalse(hasExecute(toConfirm))
        assertTrue(toConfirm.any { it is EdgeDecision.EnterConfirm })
        assertEquals(EdgeControllerState.CONFIRMING, controller.state)
        // 確認目標注視 10 幀 × 100 ms 即滿 dwell（第 12 個頂部幀已在確認
        // 期間，不計入第二次 dwell）；執行後即為 COOLDOWN 鎖定態。
        val confirmed = driver.feedConfirm(controller, 10)
        assertTrue(hasExecute(confirmed, Edge.TOP))
        assertEquals(EdgeControllerState.COOLDOWN, controller.state)
    }

    // 底部同理：確認後執行 HOME（D-3 驗收 4 的正向）。
    @Test
    fun bottomTwoStage_executesHomeAfterConfirm() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        assertTrue(driver.feed(bottomPoint, 12).any { it is EdgeDecision.EnterConfirm })
        assertTrue(hasExecute(driver.feedConfirm(controller, 12), Edge.BOTTOM))
    }

    // 進入確認後移開視線（持續離開確認目標超過取消寬限）：回 IDLE，無執行；
    // 可重新開始候選。
    @Test
    fun topConfirmAbandoned_noExecuteAndCanRestart() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        assertTrue(driver.feed(topPoint, 12).any { it is EdgeDecision.EnterConfirm })
        // 寬限內（第 12 個頂部幀已計 100 ms，中心幀再 4 幀到 500 ms）只凍結、
        // 不取消；超過寬限（再 1 幀到 600 ms）才取消。
        val withinGrace = driver.feed(centerPoint, 4)
        assertFalse(withinGrace.any { it is EdgeDecision.Cancelled })
        val abandoned = driver.feed(centerPoint, 2)
        assertTrue(
            abandoned.any {
                it is EdgeDecision.Cancelled &&
                    it.reason == EdgeCancelReason.CONFIRM_ABANDONED
            },
        )
        assertFalse(hasExecute(abandoned))
        assertEquals(EdgeControllerState.IDLE, controller.state)
        assertTrue(driver.feed(topPoint, 12).any { it is EdgeDecision.EnterConfirm })
    }

    // 短暫停留（未滿 dwell）即離開：回 IDLE，無執行判決。
    @Test
    fun topShortDwell_noExecute() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val decisions = driver.feed(topPoint, 5)
        assertFalse(hasExecute(decisions))
        val candidate = decisions.filterIsInstance<EdgeDecision.Candidate>().last()
        assertTrue(candidate.progress < 1f)
        assertTrue(driver.feed(centerPoint, 2).any { it is EdgeDecision.Cancelled })
        assertEquals(EdgeControllerState.IDLE, controller.state)
    }

    // ---- D3-1／D3-6：側邊方向一致性＋連續性（E-T06／E-T07／E-T08） ----

    // 左緣向上移動（y 減小）＋連續多幀 → 執行上捲。
    @Test
    fun sideForward_leftMovingUpExecutes() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = (0 until 12).map { 10f to (1400f - it * 40f) }
        assertTrue(hasExecute(driver.feedPoints(points), Edge.LEFT))
    }

    // 右緣向下移動（y 增大）＋連續多幀 → 執行下捲。
    @Test
    fun sideForward_rightMovingDownExecutes() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = (0 until 12).map { 1070f to (1000f + it * 40f) }
        assertTrue(hasExecute(driver.feedPoints(points), Edge.RIGHT))
    }

    // 左緣反向移動（y 增大，與上捲方向相反）→ 取消、無執行。
    @Test
    fun sideReverse_leftMovingDownRejected() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = (0 until 12).map { 10f to (1000f + it * 40f) }
        val decisions = driver.feedPoints(points)
        assertFalse(hasExecute(decisions))
        assertTrue(
            decisions.any {
                it is EdgeDecision.Cancelled &&
                    it.reason == EdgeCancelReason.DIRECTION_MISMATCH
            },
        )
    }

    // 右緣反向移動（y 減小，與下捲方向相反）→ 取消、無執行。
    @Test
    fun sideReverse_rightMovingUpRejected() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = (0 until 12).map { 1070f to (1400f - it * 40f) }
        val decisions = driver.feedPoints(points)
        assertFalse(hasExecute(decisions))
        assertTrue(
            decisions.any {
                it is EdgeDecision.Cancelled &&
                    it.reason == EdgeCancelReason.DIRECTION_MISMATCH
            },
        )
    }

    // 單幀抖動拒絕：只有一幀在帶內，不得執行；單幀時間跳變亦不得灌滿 dwell
    //（dt 夾在 0–200 ms，沿用 DwellTracker 模式）。
    @Test
    fun singleFrameJitter_noExecute() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        assertFalse(hasExecute(driver.feed(leftPoint, 1)))
        driver.nowMs += 10_000L
        assertFalse(hasExecute(driver.feed(leftPoint, 1)))
        assertEquals(EdgeControllerState.CANDIDATE, controller.state)
    }

    // 側邊短暫經過（序列長度不足、未滿 dwell）→ 無執行判決。
    @Test
    fun sideBriefPass_noExecute() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = (0 until 3).map { 1070f to (1000f + it * 40f) }
        assertFalse(hasExecute(driver.feedPoints(points)))
    }

    // ---- D3-6：重新觸發鎖定（E-T09） ----

    // 執行完成後視線持續停留在原觸發帶：不重複執行；離開帶後再進入才可重觸。
    @Test
    fun relock_stayInZoneNoRetrigger_exitThenRetrigger() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = (0 until 12).map { 10f to (1400f - it * 40f) }
        assertTrue(hasExecute(driver.feedPoints(points), Edge.LEFT))
        val stayed = driver.feed(10f to 1000f, 10)
        assertFalse(hasExecute(stayed))
        assertTrue(stayed.all { it is EdgeDecision.NoAction })
        assertTrue(driver.feed(centerPoint, 2).all { it is EdgeDecision.NoAction })
        assertEquals(EdgeControllerState.IDLE, controller.state)
        val again = driver.feedPoints((0 until 12).map { 10f to (1400f - it * 40f) })
        assertTrue(hasExecute(again, Edge.LEFT))
    }

    // reset() 保留重新觸發鎖定：執行後中斷，仍在原帶內不得重觸；離開後可重觸。
    @Test
    fun resetPreservesRelock() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = (0 until 12).map { 10f to (1400f - it * 40f) }
        assertTrue(hasExecute(driver.feedPoints(points), Edge.LEFT))
        assertEquals(EdgeDecision.NoAction, controller.reset())
        assertFalse(hasExecute(driver.feed(10f to 1000f, 10)))
        driver.feed(centerPoint, 2)
        assertTrue(
            hasExecute(
                driver.feedPoints((0 until 12).map { 10f to (1400f - it * 40f) }),
                Edge.LEFT,
            ),
        )
    }

    // ---- D3-3（任務編號）／PLAN D3-4：三項檢查獨立（概念分離聲明） ----

    // 單獨失敗其一：閉眼幀 → 只有 Gaze Confidence 不通過，其餘兩項通過，
    // 且判決為取消（追蹤不可信時不執行舊手勢）。
    @Test
    fun checksIndependent_gazeFailsAlone() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val points = listOf(10f to 1400f, 10f to 1360f, 10f to 1320f)
        driver.feedPoints(points)
        val failed = driver.feed(10f to 1280f, 1, eyesOpen = false)
        val cancelled = failed.single() as EdgeDecision.Cancelled
        assertEquals(EdgeCancelReason.CHECKS_FAILED, cancelled.reason)
        assertTrue(cancelled.report.gazeConfidence is EdgeCheckResult.Fail)
        assertEquals(
            "EYES_CLOSED",
            (cancelled.report.gazeConfidence as EdgeCheckResult.Fail).reason,
        )
        assertTrue(cancelled.report.stability is EdgeCheckResult.Pass)
        assertTrue(cancelled.report.intentConfidence is EdgeCheckResult.Pass)
    }

    // 單獨失敗其二：臉遺失後的第一幀 → 只有 Stability 不通過（序列太短），
    // Gaze Confidence 通過（本幀臉已回來），判決為阻擋（不累積、不取消）。
    @Test
    fun checksIndependent_stabilityFailsAlone() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        driver.feed(centerPoint, 1, facePresent = false)
        val blocked = driver.feed(leftPoint, 1).single()
        assertTrue(blocked is EdgeDecision.Blocked)
        val report = (blocked as EdgeDecision.Blocked).report
        assertTrue(report.gazeConfidence is EdgeCheckResult.Pass)
        assertTrue(report.stability is EdgeCheckResult.Fail)
        assertEquals(
            "SEQUENCE_TOO_SHORT",
            (report.stability as EdgeCheckResult.Fail).reason,
        )
        assertTrue(report.intentConfidence is EdgeCheckResult.Pass)
    }

    // 單獨失敗其三：頂部候選階段 → 只有 Intent Confidence 不通過（須第二次
    // 確認），Gaze／Stability 通過，且不直接執行。
    @Test
    fun checksIndependent_intentFailsAloneBeforeConfirm() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val decisions = driver.feed(topPoint, 4)
        assertFalse(hasExecute(decisions))
        val report = controller.lastReport
        assertTrue(report.gazeConfidence is EdgeCheckResult.Pass)
        assertTrue(report.stability is EdgeCheckResult.Pass)
        assertTrue(report.intentConfidence is EdgeCheckResult.Fail)
        assertEquals(
            "AWAITING_SECOND_CONFIRMATION",
            (report.intentConfidence as EdgeCheckResult.Fail).reason,
        )
    }

    // 原始預測超出螢幕 ±15%：Gaze Confidence 不通過（RAW_PREDICTION_OUT_OF_RANGE），
    // 濾波後座標即使在帶內也不得累積成執行。
    @Test
    fun checksIndependent_rawOutOfRangeGazeFails() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val blocked = driver.feed(leftPoint, 1, raw = 5000.0 to 1200.0).single()
        assertTrue(blocked is EdgeDecision.Blocked)
        val report = (blocked as EdgeDecision.Blocked).report
        assertTrue(report.gazeConfidence is EdgeCheckResult.Fail)
        assertEquals(
            "RAW_PREDICTION_OUT_OF_RANGE",
            (report.gazeConfidence as EdgeCheckResult.Fail).reason,
        )
    }

    // 離散度門檻未校準：序列大幅跳動（在帶內）不得阻擋——預設不阻擋，
    // 候選仍可滿 dwell 進入確認並執行；量測值只記錄在報告中。
    @Test
    fun dispersionUncalibrated_doesNotBlock() {
        assertEquals(
            Float.MAX_VALUE,
            EdgeGestureController.DISPERSION_MAX_PX_UNCALIBRATED,
            0f,
        )
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        val jumpy = (0 until 12).map {
            if (it % 2 == 0) 10f to 10f else 45f to 40f
        }
        val toConfirm = driver.feedPoints(jumpy)
        val entered = toConfirm.filterIsInstance<EdgeDecision.EnterConfirm>().single()
        assertTrue(entered.report.maxDeviationPx > 30f)
        assertTrue(hasExecute(driver.feedConfirm(controller, 12), Edge.TOP))
    }

    // ---- D3-7（任務編號）：取消輸出處理（E-T10） ----

    // 候選期間臉遺失 → 取消；臉回來後須重新累積（未滿 dwell 不得執行），
    // 重新累積滿 dwell 後可再次進入確認。
    @Test
    fun faceLost_cancelsAndRequiresReaccumulation() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        driver.feed(topPoint, 6)
        val lost = driver.feed(topPoint, 1, facePresent = false).single()
        assertTrue(lost is EdgeDecision.Cancelled)
        assertEquals(
            EdgeCancelReason.CHECKS_FAILED,
            (lost as EdgeDecision.Cancelled).reason,
        )
        assertEquals(EdgeControllerState.IDLE, controller.state)
        // 重新累積：第一幀被穩定性擋下，6 幀仍未滿 dwell，不得執行。
        val restarted = driver.feed(topPoint, 6)
        assertFalse(hasExecute(restarted))
        assertTrue(restarted.first() is EdgeDecision.Blocked)
        assertTrue(driver.feed(topPoint, 6).any { it is EdgeDecision.EnterConfirm })
    }

    // reset() 在進行中手勢時回 Cancelled（RESET），服務據此清除狀態；
    // 閒置時回 NoAction，不無中生有。
    @Test
    fun reset_returnsCancelledOnlyWhenActive() {
        val controller = EdgeGestureController()
        assertEquals(EdgeDecision.NoAction, controller.reset())
        val driver = Driver(controller, config)
        driver.feed(topPoint, 4)
        val cancelled = controller.reset()
        assertTrue(cancelled is EdgeDecision.Cancelled)
        assertEquals(
            EdgeCancelReason.RESET,
            (cancelled as EdgeDecision.Cancelled).reason,
        )
        assertEquals(EdgeControllerState.IDLE, controller.state)
    }

    // ---- 幾何與優先序（D3-2／E-T13／E-T14） ----

    // 角落同時命中（頂＋左）：優先序頂勝出，執行 BACK 而非左捲。
    @Test
    fun cornerPriority_topWins() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        assertTrue(driver.feed(10f to 10f, 12).any { it is EdgeDecision.EnterConfirm })
        assertTrue(hasExecute(driver.feedConfirm(controller, 12), Edge.TOP))
    }

    // 方向改變（幾何變化）：進行中的候選取消；新幾何下可重新累積。
    @Test
    fun geometryChange_resetsCandidate() {
        val controller = EdgeGestureController()
        val driver = Driver(controller, config)
        driver.feed(topPoint, 6)
        val changed = driver.feed(topPoint, 1, screenW = 2400, screenH = 1080).single()
        assertTrue(changed is EdgeDecision.Cancelled)
        assertEquals(
            EdgeCancelReason.GEOMETRY_CHANGED,
            (changed as EdgeDecision.Cancelled).reason,
        )
        assertEquals(EdgeControllerState.IDLE, controller.state)
        val restarted = driver.feed(10f to 10f, 12, screenW = 2400, screenH = 1080)
        assertTrue(restarted.any { it is EdgeDecision.EnterConfirm })
    }

    // 邊緣帶以 dp 換算（密度 2 時帶寬加倍）、短邊上限保護小螢幕。
    @Test
    fun edgeBandScalesWithDensity() {
        val controller = EdgeGestureController()
        assertEquals(
            EdgeGestureController.EDGE_BAND_DP * 2f,
            controller.edgeBandPx(screenW, screenH, 2.0f),
            0.01f,
        )
        assertEquals(
            EdgeZone.LEFT,
            controller.zoneFor(90f, 1200f, screenW, screenH, 2.0f),
        )
        assertEquals(
            EdgeZone.NONE,
            controller.zoneFor(90f, 1200f, screenW, screenH, 1.0f),
        )
        assertEquals(
            EdgeZone.TOP,
            controller.zoneFor(540f, 10f, screenW, screenH, 1.0f),
        )
        assertEquals(
            EdgeZone.BOTTOM,
            controller.zoneFor(540f, 2390f, screenW, screenH, 1.0f),
        )
        assertEquals(
            EdgeZone.RIGHT,
            controller.zoneFor(1070f, 1200f, screenW, screenH, 1.0f),
        )
    }

    // 確認目標幾何：中央偏下（高度 70%），服務繪製與測試餵幀共用同一函式。
    @Test
    fun confirmTargetIsCenterLower() {
        val controller = EdgeGestureController()
        val target = controller.confirmTarget(screenW, screenH, density)
        assertEquals(540f, target.centerX, 0.01f)
        assertEquals(screenH * 0.70f, target.centerY, 0.01f)
        assertTrue(target.radiusPx > 0f)
    }
}
