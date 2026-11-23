package com.example.eyecontrol

/**
 * RECOVERY_MODE 重啟驗證的純判斷部分（Phase 3，D2-3）。
 *
 * 依據：`PLAN.md` D2-3 與 `TEST_PLAN.md` §2.3。驗證依序檢查三項事實：
 * (a) 服務實例仍在、(b) 引擎仍在跑、(c) 校正模型有效（MODEL_VERSION
 * 驗證由 `GazeModel.fromJsonString` 內建，呼叫端以 loadModel != null 傳入）。
 *
 * 本檔案為純 Kotlin，不碰任何 Android API，因此可在純 JVM 單元測試中
 * 以替身依賴覆蓋 2×2×2 組合。實際的 `startEngine()` 呼叫與
 * `CalibrationStore.loadModel` 的 JSON 解析不在此處（前者在服務端執行、
 * 後者走 org.json stub，皆走實機 R-6，不得冒充）。
 *
 * 濾波器重置／錨點／進度／冷卻重設（D2-3 (d)）是「動作」不是「判斷」，
 * 由服務端在驗證通過後執行（沿用 `startEngine` 內的重置），不在此守衛內。
 *
 * 任一失敗即拒絕恢復；原因列出全部未通過的檢查（依 (a)(b)(c) 順序），
 * 呼叫端須將原因寫入流程態 ERROR，不得靜默回到 TRACKING。
 */
data class RecoveryGateInput(
    /** (a) 服務實例仍在（呼叫端以 `instance == this` 判定）。 */
    val serviceAlive: Boolean,
    /** (b) 引擎仍在跑（`engine != null`；為 false 時呼叫端補 `startEngine()`）。 */
    val engineRunning: Boolean,
    /** (c) 校正模型有效（`CalibrationStore.loadModel(activeProfile) != null`）。 */
    val modelValid: Boolean,
)

sealed class RecoveryGateDecision {
    /** 三項全過，允許恢復（呼叫端接著做 (d) 重置與狀態切換）。 */
    data object Allow : RecoveryGateDecision()

    /** 任一未過，拒絕恢復；[reason] 為全部未過檢查的原因文字。 */
    data class Deny(val reason: String) : RecoveryGateDecision()
}

object RecoveryGate {

    /**
     * 評估重啟驗證。三項全過才 [RecoveryGateDecision.Allow]，否則
     * [RecoveryGateDecision.Deny]，原因含每一項未過檢查的說明。
     */
    fun evaluate(input: RecoveryGateInput): RecoveryGateDecision {
        val failures = buildList {
            if (!input.serviceAlive) add("服務實例已不存在")
            if (!input.engineRunning) add("引擎未在運作")
            if (!input.modelValid) add("校正模型無效，請重新校正")
        }
        return if (failures.isEmpty()) {
            RecoveryGateDecision.Allow
        } else {
            RecoveryGateDecision.Deny(failures.joinToString("；"))
        }
    }
}
