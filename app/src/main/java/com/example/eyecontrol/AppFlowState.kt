/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

/**
 * 流程層狀態機（Phase 3，D1-1，影子模式）。
 *
 * 依據：`STATE_DESIGN.md` §2.1 的狀態表與合法轉換；最高依據為
 * `GAPS_AND_DECISIONS.md` §3.1（D-1、D-2 定稿）。
 *
 * 本檔案為純 Kotlin，不碰任何 Android API（不 import Android 套件、不用
 * Android 的 Log 類別），因此可在純 JVM 單元測試中直接驗證。
 *
 * 影子模式：本步只建狀態機本體，不接線。接線（由服務旗標推導狀態、
 * `trackingSnapshot()` 增加欄位）是 D1-2 的工作；本檔案不得被既有程式
 * 引用，行為亦不得改變 v0.5.9 的現況。
 *
 * 轉換規則：
 * - 只有 [AppFlowStateMachine.transition] 接受的事件才會改變狀態；
 *   非法轉換回傳 `false`、狀態不變，並經可注入的 [FlowStateLogger] 記一筆警告。
 * - [FlowEvent.Failure] 可由任一態進入 [AppFlowState.ERROR]，並記下原因與
 *   失敗前一態；已在 `ERROR` 時再發生失敗，只更新原因，不覆蓋失敗前一態。
 * - `ERROR` 的 [FlowEvent.Retry] 回到失敗前一態；[FlowEvent.StartCalibration]
 *   由 `ERROR` 重新校正（D-1 校正頁「重新校正」出路）。
 * - 取消語義只建模為狀態分支（[FlowEvent.CalibrationCancelled]）：取消目標的
 *   形式與注視秒數待 D-4 定稿，本檔案不定形式、不定秒數（D1-9 不在本步範圍）。
 *
 * 第 4 步遺留 (c) 的決定（維持推導和解，不補邊，證據如下）：
 * 服務端 syncFlowState 在交接／情境切換時會以事實推導結果（旗標＋模型存在）
 * 和解三對無邊的來源／目標——SERVICE_RUNNING→CALIBRATING（校正交接開始）、
 * CALIBRATING→SERVICE_RUNNING（交接結束、引擎重啟）、CALIBRATED→
 * SERVICE_RUNNING（重查後直接認定運作中）。刻意不把這三對寫成合法邊：
 * (1) STATE_DESIGN §2.2 把服務端流程態定義為事實推導的鏡像，不是旅程事件
 * 序列；(2) 既有事件無一語義相符——ServiceVerified 是引導返回驗證、
 * RestartVerified 是 RECOVERY_MODE 重啟、CalibrationSucceeded 是九點存檔成功，
 * 挪用任一者都會扭曲事件語義，而新增事件名在本步紅線之外；(3) 和解路徑已
 * 保證 flowState 永不與旗標脫節且留 Log.w 痕跡，補邊只會把同一事實改寫成
 * 兩種表達。D1-2 的 syncFlowState 行為與此決定一致，不得以「轉換表無邊」
 * 單獨判定為缺陷；F-13 的窮舉非法轉換表維持不變。
 */
enum class AppFlowState {
    NOT_CALIBRATED,
    CALIBRATING,
    CALIBRATED,
    WAITING_ACCESSIBILITY,
    SERVICE_RUNNING,
    RECOVERY_MODE,
    STOPPED,
    ERROR,
}

/**
 * 流程事件。事件一律以此 sealed class 的具名成員表示，不得用字串事件名。
 *
 * - [StartCalibration]：開始校正（含由 [AppFlowState.CALIBRATED] 重新校正、
 *   由 [AppFlowState.ERROR] 重新校正）。
 * - [CalibrationSucceeded]：9 點完成且存檔成功。
 * - [CalibrationCancelled]：校正取消（舊模型保留；狀態機只驗轉換）。
 * - [ModelAppeared]：重查時發現目前情境已有有效模型（例如外部存檔還原）。
 * - [ModelInvalidated]：重查時發現模型失效（與 [Failure] 區分：此事件是
 *   「重查後確認無有效模型」，走回 [AppFlowState.NOT_CALIBRATED]；[Failure]
 *   是「某步驟失敗」，走 [AppFlowState.ERROR]）。
 * - [GoToAccessibilitySettings]：使用者前往系統設定啟用無障礙服務。
 * - [BackToCalibration]：由等待服務回到校正（回到已校正的報告／校正狀態）。
 * - [ServiceVerified]：系統設定返回且服務已開、驗證通過。
 * - [ReturnedWithoutService]：返回但服務未開，停留等待態（合法自迴圈）。
 * - [StopEyeControl]：停止眼控（D-2 方案 A），進入 `RECOVERY_MODE`。
 * - [RestartVerified]：重啟驗證全過，回到眼控運作。
 * - [StopCompletely]：完全停止（相機關閉），進入 `STOPPED`。
 * - [StartTracking]：由 `STOPPED` 手點開始眼控（閘門全過，呼叫端判定）。
 * - [Failure]：任一步驟失敗，帶真實原因字串。
 * - [Retry]：重試對應步驟，回到失敗前一態。
 * - [TimeoutPromptExit]／[TimeoutPromptContinue]：單點逾時 3 次後詢問的
 *   「退出」／「繼續」兩個選擇（F-14）；「繼續」停留校正態（合法自迴圈）。
 */
sealed class FlowEvent {
    object StartCalibration : FlowEvent()
    object CalibrationSucceeded : FlowEvent()
    object CalibrationCancelled : FlowEvent()
    object ModelAppeared : FlowEvent()
    object ModelInvalidated : FlowEvent()
    object GoToAccessibilitySettings : FlowEvent()
    object BackToCalibration : FlowEvent()
    object ServiceVerified : FlowEvent()
    object ReturnedWithoutService : FlowEvent()
    object StopEyeControl : FlowEvent()
    object RestartVerified : FlowEvent()
    object StopCompletely : FlowEvent()
    object StartTracking : FlowEvent()
    data class Failure(val reason: String) : FlowEvent()
    object Retry : FlowEvent()
    object TimeoutPromptExit : FlowEvent()
    object TimeoutPromptContinue : FlowEvent()
}

/**
 * 狀態機的警告記錄介面（可注入）。實作端自行決定寫到哪裡（例如 Logcat）；
 * 本檔案不得直接使用 Android Log。測試以此介面的替身驗證非法轉換有記警告，
 * 不得以 Logcat 斷言。
 */
fun interface FlowStateLogger {
    fun warn(message: String)
}

/**
 * 流程狀態機本體（單一持有者）。
 *
 * 呼叫端擁有執行緒：狀態寫入應由同一執行緒（D1-2 接線時為主執行緒）進行，
 * 本類別不做執行緒同步。
 *
 * @param initialState 初始流程態，預設 [AppFlowState.NOT_CALIBRATED]。
 *   以 [AppFlowState.ERROR] 為初始態時，[errorReason] 與 [stateBeforeError]
 *   皆為空，[FlowEvent.Retry] 會被拒絕，直到一次 [FlowEvent.Failure] 記下
 *   失敗前一態為止。
 * @param logger 非法轉換的警告記錄；為 null 時不記錄、不 crash。
 */
class AppFlowStateMachine(
    initialState: AppFlowState = AppFlowState.NOT_CALIBRATED,
    private val logger: FlowStateLogger? = null,
) {
    var state: AppFlowState = initialState
        private set

    /** 目前 `ERROR` 的原因；非 `ERROR` 態時為 null。 */
    var errorReason: String? = null
        private set

    /** 進入 `ERROR` 前的狀態；非 `ERROR` 態時為 null。重試時回到此態。 */
    var stateBeforeError: AppFlowState? = null
        private set

    /**
     * 嘗試套用一個事件。合法轉換回傳 `true` 並改變 [state]（自迴圈事件回傳
     * `true`、狀態不變）；非法轉換回傳 `false`、狀態不變、記一筆警告。
     */
    fun transition(event: FlowEvent): Boolean {
        // 失敗可由任一態進入 ERROR（含已在 ERROR 時更新原因）。
        if (event is FlowEvent.Failure) {
            if (state != AppFlowState.ERROR) {
                stateBeforeError = state
            }
            state = AppFlowState.ERROR
            errorReason = event.reason
            return true
        }

        if (state == AppFlowState.ERROR) {
            return when (event) {
                FlowEvent.Retry -> {
                    val previous = stateBeforeError
                    if (previous == null) {
                        warn(event)
                        false
                    } else {
                        state = previous
                        errorReason = null
                        stateBeforeError = null
                        true
                    }
                }
                FlowEvent.StartCalibration -> {
                    state = AppFlowState.CALIBRATING
                    errorReason = null
                    stateBeforeError = null
                    true
                }
                else -> {
                    warn(event)
                    false
                }
            }
        }

        val target = when (state) {
            AppFlowState.NOT_CALIBRATED -> when (event) {
                FlowEvent.StartCalibration -> AppFlowState.CALIBRATING
                FlowEvent.ModelAppeared -> AppFlowState.CALIBRATED
                else -> null
            }
            AppFlowState.CALIBRATING -> when (event) {
                FlowEvent.CalibrationSucceeded -> AppFlowState.CALIBRATED
                FlowEvent.CalibrationCancelled -> AppFlowState.NOT_CALIBRATED
                FlowEvent.TimeoutPromptExit -> AppFlowState.NOT_CALIBRATED
                FlowEvent.TimeoutPromptContinue -> AppFlowState.CALIBRATING
                else -> null
            }
            AppFlowState.CALIBRATED -> when (event) {
                FlowEvent.GoToAccessibilitySettings -> AppFlowState.WAITING_ACCESSIBILITY
                FlowEvent.StartCalibration -> AppFlowState.CALIBRATING
                FlowEvent.ModelInvalidated -> AppFlowState.NOT_CALIBRATED
                else -> null
            }
            AppFlowState.WAITING_ACCESSIBILITY -> when (event) {
                FlowEvent.ServiceVerified -> AppFlowState.SERVICE_RUNNING
                FlowEvent.ReturnedWithoutService -> AppFlowState.WAITING_ACCESSIBILITY
                FlowEvent.BackToCalibration -> AppFlowState.CALIBRATED
                else -> null
            }
            AppFlowState.SERVICE_RUNNING -> when (event) {
                FlowEvent.StopEyeControl -> AppFlowState.RECOVERY_MODE
                FlowEvent.StopCompletely -> AppFlowState.STOPPED
                else -> null
            }
            AppFlowState.RECOVERY_MODE -> when (event) {
                FlowEvent.RestartVerified -> AppFlowState.SERVICE_RUNNING
                FlowEvent.StopCompletely -> AppFlowState.STOPPED
                else -> null
            }
            AppFlowState.STOPPED -> when (event) {
                FlowEvent.StartTracking -> AppFlowState.SERVICE_RUNNING
                else -> null
            }
            AppFlowState.ERROR -> null // 已在上方處理，不會走到這裡。
        }

        if (target == null) {
            warn(event)
            return false
        }
        state = target
        return true
    }

    private fun warn(event: FlowEvent) {
        val eventName = event::class.simpleName ?: event.toString()
        logger?.warn("Illegal flow transition: $state + $eventName ignored")
    }
}
