/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import kotlin.math.hypot
import kotlin.math.min

/**
 * 邊緣手勢控制器（Phase 3，D3-1…D3-8）。
 *
 * 純 Kotlin，不依賴任何 Android API；時間一律由呼叫端傳入（與 `DwellTracker`
 * 同一模式），可在純 JVM 單元測試中以虛擬時鐘驅動。
 *
 * 依據：`GAPS_AND_DECISIONS.md` §3.1 D-3 逐字決定（組合方案，不得只加長
 * 停留時間）、`PLAN.md` §4 D3-1…D3-8、`STATE_DESIGN.md` §3.2（`EDGE_CONFIRMING`）
 * 與 §5（提醒 preempt、單一有效手勢、冷卻、武裝）。
 *
 * 概念分離聲明（D-3 定稿，PLAN §4 末）：`Gaze Confidence`（樣本有效：
 * 臉 present、特徵有限、影像未降級、雙眼睜開、原始預測在合理範圍）、
 * `Gaze Stability`（序列穩定：連續 present、離散度）與 `Intent Confidence`
 * （使用者意圖：側邊方向一致性、頂／底部二階段確認）三者分開處理，
 * 不得合併為單一信心值。本控制器不得輸出任何名為 confidence／score 的
 * 數值對外顯示；檢查結果只供日誌／診斷（失敗原因），不顯示給使用者。
 * 在無驗證過的意圖模型時，用明確的第二次注視確認，不宣稱具備意圖分類能力。
 *
 * 控制器狀態機（PLAN D3-1／STATE_DESIGN §3.2）：
 * `IDLE → CANDIDATE → CONFIRMING → EXECUTED → COOLDOWN → IDLE`。
 * 其中 EXECUTED 為單幀判決（[EdgeDecision.Execute]），不保留為狀態；
 * [EdgeControllerState.COOLDOWN] 為重新觸發鎖定態：執行完成後，游標須先
 * 離開原觸發邊緣區，才重新武裝（D3-6／D3-7，沿用 `handleArmed` 武裝模式）。
 *
 * 門檻集中管理（D-3 觸發條件）：全部門檻集中為本檔案 `companion object`
 * 常數，不得散落服務各處。**全部量測門檻皆未校準**：標記為
 * `*_UNCALIBRATED` 的常數只定邏輯、不定已驗證數值，預設寬鬆／不阻擋
 * 正常手勢；實機量測校準前不得寫死為已驗證數字（見 `TEST_PLAN.md` §0.6
 * 與 §3.5，校準待辦 E-10）。邊緣帶幾何見 [edgeBandPx] 註解。
 *
 * 呼叫端責任（服務主路徑，D3-8）：
 * - 每幀餵同一幀的已算數據（濾波後座標、原始預測、臉／眼狀態），不得自建
 *   樣本來源、不得自建計時執行緒。
 * - 提醒 preempt 期間、RECOVERY_MODE 期間、PAUSED 期間、校正交接期間
 *   不得餵幀（提醒分支／RECOVERY 分支／暫停分支提早返回；校正交接期間
 *   樣本根本不進主路徑，天然隔離）；中斷時呼叫 [reset]，控制器回
 *   [EdgeDecision.Cancelled]，服務須清除進行中的手勢狀態、不執行動作、
 *   不留殘留提示。
 * - 臉遺失幀、方向改變時呼叫 [reset]（強制回 IDLE，D-3 防止重複觸發段）。
 *   [reset] 保留重新觸發鎖定（COOLDOWN 不受影響），只清除進行中的候選／
 *   確認進度；鎖定只由「游標離開原觸發帶」的一幀解除。
 * - 執行判決仍由服務呼叫現有 `performEdgeAction`／dispatch（動作對應：
 *   頂＝返回、底＝首頁、左＝上捲、右＝下捲，不得改變）；控制器只做判決。
 */
class EdgeGestureController {

    /** 控制器狀態（STATE_DESIGN §3.2 的控制器內部狀態）。 */
    var state: EdgeControllerState = EdgeControllerState.IDLE
        private set

    /** 最近一次檢查報告（每幀更新；失敗原因供日誌／診斷，不顯示給使用者）。 */
    var lastReport: EdgeCheckReport = EdgeCheckReport(
        gazeConfidence = EdgeCheckResult.Pass,
        stability = EdgeCheckResult.Pass,
        intentConfidence = EdgeCheckResult.Pass,
        maxDeviationPx = 0f,
    )
        private set

    private var lastScreenW = 0
    private var lastScreenH = 0
    private var lastDensity = 0f
    private var hasGeometry = false

    private var lastFrameAtMs = 0L
    private var hasLastFrame = false

    /** 連續 present 幀數（臉遺失幀即歸零；序列中斷語義，見 PLAN D3-4）。 */
    private var consecutivePresentFrames = 0

    /** 候選序列（濾波後座標）：方向一致性與離散度皆由此序列計算。 */
    private val sequencePoints = mutableListOf<Pair<Float, Float>>()
    private var sequenceEdge: Edge? = null
    private var candidateAccumMs = 0L

    private var confirmEdge: Edge? = null
    private var confirmAccumMs = 0L

    /**
     * 確認期間離開確認目標的累計時間（ms）：超過寬限才取消（見
     * [CONFIRM_ABANDON_GRACE_MS]），讓使用者有時間把視線從邊緣移往確認
     * 目標；寬限內回到目標即重設、不取消，進度凍結。
     */
    private var confirmAwayMs = 0L

    /** 重新觸發鎖定（D3-6）：最後一次執行的邊緣；非 null 時即 COOLDOWN。 */
    private var lockedEdge: Edge? = null

    /**
     * 邊緣帶寬（像素）：以 dp 為輸入單位再乘密度，短邊比例設上限。
     *
     * 換算（PLAN D3-2，不得新編數字）：現況 `edgeSize = 50f` 為 raw px，
     * 在基準密度 160 dpi 下 50 px 即 50 dp，故 dp 值取 50；再乘當下密度
     * 轉回 px，使物理寬度在不同密度下一致。上限比例先以現況行為為預設、
     * 留待實機量測（E-9）：高密度裝置上 50 dp 的像素寬可能超過小螢幕
     * 短邊的 15%，此時壓回短邊的 15%，避免邊緣帶吃掉過多可用區。
     * 注意：此換算改變高密度裝置上的帶寬（v0.5.9 的 50 px 在 SM-S9180
     * 上物理上遠窄於 50 dp），屬 PLAN D3-2 要求的幾何集中化，實機
     * E-9 須以 v0.5.9 為對照量測誤觸／漏觸，不得宣稱未測已測。
     */
    fun edgeBandPx(screenW: Int, screenH: Int, density: Float): Float {
        val shortSide = min(screenW, screenH).toFloat().coerceAtLeast(1f)
        val dpBand = EDGE_BAND_DP * density.coerceAtLeast(0f)
        return min(dpBand, shortSide * MAX_BAND_FRACTION_OF_SHORT_SIDE)
    }

    /** 濾波後座標落在哪個邊緣帶（角落優先序沿用現況：頂＞底＞左＞右）。 */
    fun zoneFor(
        x: Float,
        y: Float,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): EdgeZone {
        val band = edgeBandPx(screenW, screenH, density)
        return when {
            y < band -> EdgeZone.TOP
            y > screenH - band -> EdgeZone.BOTTOM
            x < band -> EdgeZone.LEFT
            x > screenW - band -> EdgeZone.RIGHT
            else -> EdgeZone.NONE
        }
    }

    /**
     * 二階段確認目標的幾何（中央偏下，STATE_DESIGN §3.2）：中心為螢幕
     * 寬度一半、高度 70% 處，半徑為短邊的 12%。版面選擇（非量測門檻），
     * 實機 E-3 驗證是否好注視、是否與提醒／重啟目標混淆。
     */
    fun confirmTarget(
        screenW: Int,
        screenH: Int,
        @Suppress("UNUSED_PARAMETER") density: Float,
    ): ConfirmTarget {
        val shortSide = min(screenW, screenH).toFloat().coerceAtLeast(1f)
        return ConfirmTarget(
            centerX = screenW / 2f,
            centerY = screenH * CONFIRM_TARGET_Y_FRACTION,
            radiusPx = shortSide * CONFIRM_TARGET_RADIUS_FRACTION_OF_SHORT_SIDE,
        )
    }

    /**
     * 推進一幀，回傳判決。呼叫端須保證：提醒／RECOVERY／PAUSED／校正交接
     * 期間不呼叫本函式（見檔頭呼叫端責任）；臉遺失時以
     * `facePresent = false` 的幀呼叫，或直接呼叫 [reset]。
     */
    fun update(frame: EdgeGestureFrame, config: EdgeGestureConfig): EdgeDecision {
        // 幾何改變（方向／尺寸／密度變化）：強制回 IDLE，進行中的候選／
        // 確認取消（鎖定保留，見 [reset]）；本幀不消費，下一幀重新累積。
        if (hasGeometry &&
            (frame.screenW != lastScreenW || frame.screenH != lastScreenH ||
                frame.density != lastDensity)
        ) {
            lastScreenW = frame.screenW
            lastScreenH = frame.screenH
            lastDensity = frame.density
            hasLastFrame = false
            return if (state == EdgeControllerState.CANDIDATE ||
                state == EdgeControllerState.CONFIRMING
            ) {
                clearActive()
                EdgeDecision.Cancelled(EdgeCancelReason.GEOMETRY_CHANGED, lastReport)
            } else {
                clearSequence()
                EdgeDecision.NoAction
            }
        }
        lastScreenW = frame.screenW
        lastScreenH = frame.screenH
        lastDensity = frame.density
        hasGeometry = true

        // 幀間隔夾在 0–200 ms（沿用 DwellTracker 的 dt 夾限）：背景卡住後
        // 的第一幀不得一次灌進數秒進度，單幀抖動不得直接完成 dwell。
        val dt = if (!hasLastFrame) 0L else (frame.nowMs - lastFrameAtMs).coerceIn(0L, 200L)
        lastFrameAtMs = frame.nowMs
        hasLastFrame = true

        // 總開關關閉：全部停用（D-3 設定段）。進行中的手勢取消，服務須
        // 收到 Cancelled 後清除殘留提示；鎖定態維持（重開後仍須先離開帶）。
        if (!config.enabled) {
            return if (state == EdgeControllerState.CANDIDATE ||
                state == EdgeControllerState.CONFIRMING
            ) {
                clearActive()
                EdgeDecision.Cancelled(EdgeCancelReason.DISABLED, lastReport)
            } else {
                EdgeDecision.NoAction
            }
        }

        // 確認階段（STATE_DESIGN §3.2 EDGE_CONFIRMING）：只接受確認目標注視，
        // 其餘一般輸入一律不處理（服務端在確認期間不跑一般 dwell／面板）。
        if (state == EdgeControllerState.CONFIRMING) {
            val edge = confirmEdge ?: return EdgeDecision.NoAction
            if (!config.edgeEnabled(edge)) {
                clearActive()
                return EdgeDecision.Cancelled(EdgeCancelReason.DISABLED, lastReport)
            }
            return updateConfirming(frame, config, edge, dt)
        }

        val zone = zoneFor(frame.filteredX, frame.filteredY, frame.screenW, frame.screenH, frame.density)
        if (zone == EdgeZone.NONE) {
            // 離開邊緣區：候選取消；鎖定解除（D3-6 解除條件）。
            return if (state == EdgeControllerState.CANDIDATE) {
                clearActive()
                EdgeDecision.Cancelled(EdgeCancelReason.LEFT_ZONE, lastReport)
            } else {
                lockedEdge = null
                if (state == EdgeControllerState.COOLDOWN) state = EdgeControllerState.IDLE
                clearSequence()
                EdgeDecision.NoAction
            }
        }
        val edge = zone.toEdge()

        // 分項開關關閉：該邊不執行（其餘邊不受影響，D-3 驗收 8）。
        if (!config.edgeEnabled(edge)) {
            return if (state == EdgeControllerState.CANDIDATE && sequenceEdge == edge) {
                clearActive()
                EdgeDecision.Cancelled(EdgeCancelReason.DISABLED, lastReport)
            } else {
                EdgeDecision.NoAction
            }
        }

        // 候選邊改變（跳到另一邊）：舊候選取消，本幀不消費。
        if (state == EdgeControllerState.CANDIDATE && sequenceEdge != null && sequenceEdge != edge) {
            clearActive()
            return EdgeDecision.Cancelled(EdgeCancelReason.LEFT_ZONE, lastReport)
        }

        // 重新觸發鎖定（D3-6）：仍在原觸發帶內不重觸；已離開（另一邊或
        // 無帶）才解除鎖定、重新累積。
        if (state == EdgeControllerState.COOLDOWN) {
            if (lockedEdge == edge) return EdgeDecision.NoAction
            lockedEdge = null
            state = EdgeControllerState.IDLE
            clearSequence()
            // 落下繼續以本幀開始新候選。
        }

        // 序列維護：新候選開始時清序列；連續 present 計數（臉遺失幀歸零，
        // 收不到幀即序列中斷，見 PLAN D3-4 註）。
        if (state != EdgeControllerState.CANDIDATE) {
            clearSequence()
            sequenceEdge = edge
            candidateAccumMs = 0L
            state = EdgeControllerState.CANDIDATE
        }
        if (frame.facePresent) consecutivePresentFrames += 1 else consecutivePresentFrames = 0
        sequencePoints.add(frame.filteredX to frame.filteredY)
        if (sequencePoints.size > MAX_SEQUENCE_POINTS) sequencePoints.removeAt(0)

        val report = evaluateChecks(frame, edge, isConfirming = false)
        lastReport = report

        // Gaze Confidence 失敗：進行中的候選取消（追蹤遺失後不執行舊手勢）；
        // 尚未開始累積時為阻擋（Blocked），不留候選狀態。
        if (report.gazeConfidence is EdgeCheckResult.Fail) {
            val wasActive = candidateAccumMs > 0L || sequencePoints.size > 1
            clearActive()
            return if (wasActive) {
                EdgeDecision.Cancelled(EdgeCancelReason.CHECKS_FAILED, report)
            } else {
                EdgeDecision.Blocked(edge, report)
            }
        }
        // Gaze Stability 未達：不累積、不取消，序列保留、下一幀繼續。
        if (report.stability is EdgeCheckResult.Fail) {
            return EdgeDecision.Blocked(edge, report)
        }
        // 側邊方向已明確相反：提早取消，不必等到 dwell 滿（單幀抖動／反向
        // 掃視不得累積成一次捲動）；死區內的微小位移不觸發此分支。
        if ((edge == Edge.LEFT || edge == Edge.RIGHT) &&
            report.intentConfidence is EdgeCheckResult.Fail &&
            (report.intentConfidence as EdgeCheckResult.Fail).reason == "DIRECTION_MISMATCH"
        ) {
            clearActive()
            return EdgeDecision.Cancelled(EdgeCancelReason.DIRECTION_MISMATCH, report)
        }

        candidateAccumMs += dt
        val progress = if (config.dwellTimeMs <= 0L) 0f
        else min(candidateAccumMs / config.dwellTimeMs.toFloat(), 1f)
        if (progress < 1f) return EdgeDecision.Candidate(edge, progress, report)

        // 候選滿 dwell：側邊驗方向後直接執行（D-3 定稿，側邊不做二階段
        // 確認）；頂／底部進入二階段確認（D3-5），不直接執行。
        return when (edge) {
            Edge.LEFT, Edge.RIGHT -> {
                if (report.intentConfidence is EdgeCheckResult.Fail) {
                    clearActive()
                    EdgeDecision.Cancelled(EdgeCancelReason.DIRECTION_MISMATCH, report)
                } else {
                    enterCooldown(edge)
                    EdgeDecision.Execute(edge, report)
                }
            }
            Edge.TOP, Edge.BOTTOM -> {
                state = EdgeControllerState.CONFIRMING
                confirmEdge = edge
                confirmAccumMs = 0L
                confirmAwayMs = 0L
                clearSequence()
                EdgeDecision.EnterConfirm(edge, report)
            }
        }
    }

    /**
     * 確認階段推進（僅 [update] 在 CONFIRMING 態時呼叫）。
     * 離開確認目標／臉遺失／檢查失敗 → 取消，不執行、不保留進度；
     * 注視確認目標滿 dwellTimeMs → 執行（Intent Confidence 由第二次注視滿足）。
     */
    private fun updateConfirming(
        frame: EdgeGestureFrame,
        config: EdgeGestureConfig,
        edge: Edge,
        dt: Long,
    ): EdgeDecision {
        if (frame.facePresent) consecutivePresentFrames += 1 else consecutivePresentFrames = 0
        val report = evaluateChecks(frame, edge, isConfirming = true)
        lastReport = report
        if (report.gazeConfidence is EdgeCheckResult.Fail) {
            clearActive()
            return EdgeDecision.Cancelled(EdgeCancelReason.CHECKS_FAILED, report)
        }
        val target = confirmTarget(frame.screenW, frame.screenH, frame.density)
        val inTarget = hypot(
            (frame.filteredX - target.centerX).toDouble(),
            (frame.filteredY - target.centerY).toDouble(),
        ) <= target.radiusPx
        if (!inTarget) {
            // 離開確認目標：寬限內只凍結進度（使用者正在移往目標途中），
            // 持續離開超過寬限才取消（沿用提醒的移開寬限模式，見常數註解）。
            confirmAwayMs += dt
            if (confirmAwayMs > CONFIRM_ABANDON_GRACE_MS) {
                clearActive()
                return EdgeDecision.Cancelled(EdgeCancelReason.CONFIRM_ABANDONED, report)
            }
            return EdgeDecision.Confirming(edge, confirmProgress(config), report)
        }
        confirmAwayMs = 0L
        // 穩定性未達時凍結進度（不推進亦不取消），下一幀繼續。
        if (report.stability is EdgeCheckResult.Fail) {
            return EdgeDecision.Confirming(edge, confirmProgress(config), report)
        }
        confirmAccumMs += dt
        val progress = confirmProgress(config)
        if (progress < 1f) return EdgeDecision.Confirming(edge, progress, report)
        val doneReport = report.copy(intentConfidence = EdgeCheckResult.Pass)
        lastReport = doneReport
        enterCooldown(edge)
        return EdgeDecision.Execute(edge, doneReport)
    }

    private fun confirmProgress(config: EdgeGestureConfig): Float =
        if (config.dwellTimeMs <= 0L) 0f
        else min(confirmAccumMs / config.dwellTimeMs.toFloat(), 1f)

    /**
     * 三項檢查獨立評估（D3-3）：每項各有通過／不通過，失敗時原因獨立記錄。
     * 評估順序不互相掩蓋：三項皆以前一幀的序列狀態獨立計算，任一失敗不改
     * 變其餘項的結果（測試以單一失敗幀驗證各項獨立標示）。
     */
    private fun evaluateChecks(
        frame: EdgeGestureFrame,
        edge: Edge,
        isConfirming: Boolean,
    ): EdgeCheckReport {
        // Gaze Confidence（樣本有效）：臉、特徵、影像、雙眼、原始預測範圍。
        val gaze: EdgeCheckResult = when {
            !frame.facePresent -> EdgeCheckResult.Fail("FACE_NOT_PRESENT")
            !frame.featuresFinite -> EdgeCheckResult.Fail("FEATURES_NOT_FINITE")
            frame.imageDegraded -> EdgeCheckResult.Fail("IMAGE_DEGRADED")
            !frame.eyesOpen -> EdgeCheckResult.Fail("EYES_CLOSED")
            !rawWithinMargin(frame) -> EdgeCheckResult.Fail("RAW_PREDICTION_OUT_OF_RANGE")
            else -> EdgeCheckResult.Pass
        }
        // Gaze Stability（序列穩定）：連續 present 幀數與離散度。
        // 離散度以序列首點為錨點的最大偏離；門檻未校準（Float.MAX_VALUE
        // 佔位），預設永不阻擋，只記錄量測值供診斷（不得顯示給使用者）。
        val maxDeviation = sequencePoints.maxOfOrNull { (px, py) ->
            val first = sequencePoints.first()
            hypot((px - first.first).toDouble(), (py - first.second).toDouble()).toFloat()
        } ?: 0f
        val stability: EdgeCheckResult = when {
            consecutivePresentFrames < MIN_CONSECUTIVE_FRAMES_UNCALIBRATED ->
                EdgeCheckResult.Fail("SEQUENCE_TOO_SHORT")
            maxDeviation > DISPERSION_MAX_PX_UNCALIBRATED ->
                EdgeCheckResult.Fail("DISPERSION_TOO_HIGH")
            else -> EdgeCheckResult.Pass
        }
        // Intent Confidence（使用者意圖）：側邊看方向一致性，頂／底部看
        // 二階段確認是否完成。候選階段的頂／底部意圖尚未滿足（須第二次
        // 注視），故為不通過——這正是候選滿 dwell 後不直接執行的原因。
        val intent: EdgeCheckResult = when {
            isConfirming -> EdgeCheckResult.Fail("AWAITING_SECOND_CONFIRMATION")
            edge == Edge.LEFT || edge == Edge.RIGHT -> directionCheck(edge)
            else -> EdgeCheckResult.Fail("AWAITING_SECOND_CONFIRMATION")
        }
        return EdgeCheckReport(gaze, stability, intent, maxDeviation)
    }

    /** 原始預測在螢幕 ±15% 內（現況守衛沿用，升級為控制器常數，不得改值）。 */
    private fun rawWithinMargin(frame: EdgeGestureFrame): Boolean {
        val marginX = frame.screenW * RAW_MARGIN_FRACTION
        val marginY = frame.screenH * RAW_MARGIN_FRACTION
        return frame.rawX >= -marginX && frame.rawX <= frame.screenW + marginX &&
            frame.rawY >= -marginY && frame.rawY <= frame.screenH + marginY
    }

    /**
     * 側邊方向一致性（D3-6／PLAN D3-6）：序列首尾 y 差值的符號須與該側
     * 捲動方向一致（左緣＝向上、y 減小；右緣＝向下、y 增大），只用 y 分量
     * 首尾差、不用單幀跳變，x 抖動不影響。淨位移小於死區（待校準、預設
     * 寬鬆）時視為未反駁方向，不阻擋靜止注視；符號相反且超出死區時拒絕。
     */
    private fun directionCheck(edge: Edge): EdgeCheckResult {
        if (sequencePoints.size < 2) return EdgeCheckResult.Pass
        val first = sequencePoints.first()
        val last = sequencePoints.last()
        val dy = last.second - first.second
        if (kotlin.math.abs(dy) < DIRECTION_DEAD_ZONE_PX_UNCALIBRATED) return EdgeCheckResult.Pass
        val consistent = when (edge) {
            Edge.LEFT -> dy < 0f
            Edge.RIGHT -> dy > 0f
            else -> true
        }
        return if (consistent) EdgeCheckResult.Pass
        else EdgeCheckResult.Fail("DIRECTION_MISMATCH")
    }

    private fun enterCooldown(edge: Edge) {
        lockedEdge = edge
        state = EdgeControllerState.COOLDOWN
        clearActive()
        // clearActive 不碰 lockedEdge／state：COOLDOWN 即鎖定態本身。
        state = EdgeControllerState.COOLDOWN
    }

    /**
     * 中斷／重置（D3-7／D3-8）：追蹤遺失、相機中斷、App 切換、方向改變、
     * 提醒介入、進入 RECOVERY／PAUSED 時，服務呼叫本函式強制回 IDLE。
     * 進行中的候選／確認回 [EdgeDecision.Cancelled]，服務須清除進行中狀態、
     * 不執行動作、不留殘留提示。重新觸發鎖定保留：COOLDOWN 態不受影響，
     * 鎖定只由離開原觸發帶的一幀解除（見 [update] 的 NONE 分支）。
     */
    fun reset(): EdgeDecision {
        return if (state == EdgeControllerState.CANDIDATE ||
            state == EdgeControllerState.CONFIRMING
        ) {
            clearActive()
            EdgeDecision.Cancelled(EdgeCancelReason.RESET, lastReport)
        } else {
            EdgeDecision.NoAction
        }
    }

    private fun clearActive() {
        clearSequence()
        confirmEdge = null
        confirmAccumMs = 0L
        confirmAwayMs = 0L
        // 鎖定（lockedEdge）與 COOLDOWN 態由呼叫端／enterCooldown 專管，
        // 此處不清除：reset() 不得解除重新觸發鎖定。
        if (state == EdgeControllerState.CANDIDATE ||
            state == EdgeControllerState.CONFIRMING
        ) {
            state = EdgeControllerState.IDLE
        }
    }

    private fun clearSequence() {
        sequencePoints.clear()
        sequenceEdge = null
        candidateAccumMs = 0L
    }

    companion object {
        /** 邊緣帶寬（dp）：現況 50 px 在基準密度 160 dpi 下的等值，見 [edgeBandPx]。 */
        const val EDGE_BAND_DP = 50f

        /** 邊緣帶上限（短邊比例）：待實機量測（E-9），先以現況行為為預設。 */
        const val MAX_BAND_FRACTION_OF_SHORT_SIDE = 0.15f

        /** 原始預測容忍邊界（螢幕比例）：現況值沿用，不得改值。 */
        const val RAW_MARGIN_FRACTION = 0.15f

        /**
         * 連續 present 最少幀數——待校準（UNCALIBRATED）。
         * 預設寬鬆（2 幀即通過，不阻擋正常手勢）；實機量測後才定稿數字。
         */
        const val MIN_CONSECUTIVE_FRAMES_UNCALIBRATED = 2

        /**
         * 離散度上限（px）——待校準（UNCALIBRATED）。
         * 以不阻擋值佔位（永不阻擋），只記錄量測值供診斷；實機量測
         * （E-10）後才定稿數字，不得以未驗證數字假裝已檢查。
         */
        const val DISPERSION_MAX_PX_UNCALIBRATED = Float.MAX_VALUE

        /**
         * 方向死區（px）——待校準（UNCALIBRATED）。淨位移小於此值時視為
         * 未反駁方向（預設寬鬆，不阻擋靜止注視）；實機量測後才定稿。
         */
        const val DIRECTION_DEAD_ZONE_PX_UNCALIBRATED = 2f

        /** 確認目標中心高度比例（螢幕高度的 70%，中央偏下）。版面選擇，E-3 驗證。 */
        const val CONFIRM_TARGET_Y_FRACTION = 0.70f

        /**
         * 確認期間離開確認目標的取消寬限（ms）：取現況提醒的移開寬限
         * `REMINDER_AWAY_MS = 500` 同值（服務端既有常數，非新編數字），
         * 讓視線有時間從邊緣移往確認目標；實機 E-3 驗證是否足夠。
         */
        const val CONFIRM_ABANDON_GRACE_MS = 500L

        /** 確認目標半徑（短邊比例）。版面選擇，E-3 驗證。 */
        const val CONFIRM_TARGET_RADIUS_FRACTION_OF_SHORT_SIDE = 0.12f

        /** 候選序列保留上限（幀）：防無界增長；實務上 dwell 內遠不及此數。 */
        const val MAX_SEQUENCE_POINTS = 600
    }
}

/** 邊緣帶判定結果（角落優先序：頂＞底＞左＞右，沿用現況）。 */
enum class EdgeZone {
    NONE, TOP, BOTTOM, LEFT, RIGHT;

    fun toEdge(): Edge = when (this) {
        TOP -> Edge.TOP
        BOTTOM -> Edge.BOTTOM
        LEFT -> Edge.LEFT
        RIGHT -> Edge.RIGHT
        NONE -> throw IllegalStateException("NONE 沒有對應邊緣")
    }
}

/** 四邊緣（動作對應：頂＝返回、底＝首頁、左＝上捲、右＝下捲，不得改變）。 */
enum class Edge {
    TOP, BOTTOM, LEFT, RIGHT
}

/** 控制器內部狀態（STATE_DESIGN §3.2；EXECUTED 為單幀判決，不列為狀態）。 */
enum class EdgeControllerState {
    IDLE, CANDIDATE, CONFIRMING, COOLDOWN
}

/** 單項檢查結果：通過／不通過（附原因）。三項檢查結果彼此獨立。 */
sealed class EdgeCheckResult {
    data object Pass : EdgeCheckResult()
    data class Fail(val reason: String) : EdgeCheckResult()
}

/**
 * 三項檢查報告（D3-3）：Gaze Confidence、Gaze Stability、Intent Confidence
 * 各自獨立的通過／不通過；[maxDeviationPx] 僅供日誌／診斷，不得顯示給使用者。
 */
data class EdgeCheckReport(
    val gazeConfidence: EdgeCheckResult,
    val stability: EdgeCheckResult,
    val intentConfidence: EdgeCheckResult,
    val maxDeviationPx: Float,
) {
    fun allPass(): Boolean =
        gazeConfidence is EdgeCheckResult.Pass &&
            stability is EdgeCheckResult.Pass &&
            intentConfidence is EdgeCheckResult.Pass
}

/** 每幀輸入（呼叫端傳入已算數據；時間由呼叫端傳入，不得用系統時間）。 */
data class EdgeGestureFrame(
    /** 濾波後座標（已箝制在螢幕內，與現況邊緣判定同一座標）。 */
    val filteredX: Float,
    val filteredY: Float,
    /** 原始預測座標（未箝制，供 ±15% 外推守衛用）。 */
    val rawX: Double,
    val rawY: Double,
    /** 臉 present（本幀有樣本）。 */
    val facePresent: Boolean,
    /** 雙眼皆睜開（以現況 blinkThreshold 判定，呼叫端傳入）。 */
    val eyesOpen: Boolean,
    /** 特徵全部有限且維度符合契約（呼叫端以樣本特徵判定）。 */
    val featuresFinite: Boolean,
    /** 眼部影像降級（呼叫端以 EyeImageMetrics.isDegraded 判定）。 */
    val imageDegraded: Boolean,
    /** 幀時間戳（elapsedRealtime，呼叫端傳入）。 */
    val nowMs: Long,
    val screenW: Int,
    val screenH: Int,
    /** 螢幕密度倍率（displayMetrics.density）。 */
    val density: Float,
)

/** 開關與 dwell 設定（服務每幀由 GazeSettings 組裝傳入；純資料，無 Android 依賴）。 */
data class EdgeGestureConfig(
    /** 總開關（預設開啟＝現況行為，D3-4）。 */
    val enabled: Boolean = true,
    val topEnabled: Boolean = true,
    val bottomEnabled: Boolean = true,
    val leftEnabled: Boolean = true,
    val rightEnabled: Boolean = true,
    /** 停留時間（取現況 settings.dwellTimeMs，不得新編數字）。 */
    val dwellTimeMs: Long = 1000L,
) {
    fun edgeEnabled(edge: Edge): Boolean = when (edge) {
        Edge.TOP -> topEnabled
        Edge.BOTTOM -> bottomEnabled
        Edge.LEFT -> leftEnabled
        Edge.RIGHT -> rightEnabled
    }
}

/** 二階段確認目標的幾何（中心＋半徑，像素）。 */
data class ConfirmTarget(
    val centerX: Float,
    val centerY: Float,
    val radiusPx: Float,
)

/** 手勢已取消／已中斷的原因（D3-7：明確輸出，服務須正確處理）。 */
enum class EdgeCancelReason {
    /** 游標離開邊緣區／候選邊改變。 */
    LEFT_ZONE,

    /** 檢查失敗（臉遺失／特徵無效／影像降級／閉眼／原始預測超範圍）。 */
    CHECKS_FAILED,

    /** 側邊方向與捲動方向相反。 */
    DIRECTION_MISMATCH,

    /** 確認期間離開確認目標（未完成第二次確認）。 */
    CONFIRM_ABANDONED,

    /** 開關被關閉（總開關或該邊分項開關）。 */
    DISABLED,

    /** 服務呼叫 reset() 中斷（追蹤遺失／提醒介入／RECOVERY／PAUSED／方向改變）。 */
    RESET,

    /** 螢幕幾何改變（方向／尺寸／密度變化）。 */
    GEOMETRY_CHANGED,
}

/** 控制器每幀判決（服務據此執行／顯示／清除，不得自行另做判決）。 */
sealed class EdgeDecision {
    /** 無動作（不在邊緣區、或鎖定期仍在原帶內、或開關關閉且無進行中手勢）。 */
    data object NoAction : EdgeDecision()

    /** 候選累積中（邊緣帶內、檢查通過、未滿 dwell）；[progress] 為 0..1。 */
    data class Candidate(val edge: Edge, val progress: Float, val report: EdgeCheckReport) : EdgeDecision()

    /** 在邊緣帶內但檢查未達（不累積、不取消）；服務應記錄原因、不動作。 */
    data class Blocked(val edge: Edge, val report: EdgeCheckReport) : EdgeDecision()

    /** 頂／底部候選滿 dwell，進入二階段確認（服務顯示確認目標）。 */
    data class EnterConfirm(val edge: Edge, val report: EdgeCheckReport) : EdgeDecision()

    /** 確認目標注視累積中；[progress] 為第二次 dwell 的 0..1。 */
    data class Confirming(val edge: Edge, val progress: Float, val report: EdgeCheckReport) : EdgeDecision()

    /** 執行該邊動作（服務呼叫現有 performEdgeAction；隨後進 COOLDOWN 鎖定）。 */
    data class Execute(val edge: Edge, val report: EdgeCheckReport) : EdgeDecision()

    /** 手勢已取消／已中斷（D3-7）：服務須清除進行中狀態、不執行動作、不留殘留提示。 */
    data class Cancelled(val reason: EdgeCancelReason, val report: EdgeCheckReport) : EdgeDecision()
}
