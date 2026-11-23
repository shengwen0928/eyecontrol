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

/**
 * STEP 3 薄守衛的純決策（不依賴 Android 框架，時間／狀態皆由呼叫端傳入）。
 *
 * 只收斂「已在程式碼中證實存在」的兩個判定，不新增仲裁狀態機、不改任何
 * 門檻數值（門檻值一律由呼叫端傳入當下設定值）；檔案寫入、上傳、手勢
 * 注入等副作用一律留在原呼叫端，本檔只回答「該不該做」。
 */
object GuardDecisions {

    /**
     * 眨眼點擊是否觸發（DCL-13 最小部分）：獨立開關關閉時永不觸發。
     * 時間窗、游標有效性、冷卻、暫停、提醒守衛沿用現況語義原樣，
     * 本函式只把原本散在主路徑的條件收斂為可單元測試的純判定；
     * 個人化意圖校正＋品質評估閘門尚未實作（待批 P-05），不在此判定內。
     */
    fun shouldTriggerBlinkClick(
        blinkClickEnabled: Boolean,
        blinkDurationMs: Long,
        blinkMinMs: Long,
        blinkMaxMs: Long,
        hasValidCursor: Boolean,
        cooldownElapsed: Boolean,
        paused: Boolean,
        reminderActive: Boolean,
    ): Boolean =
        blinkClickEnabled &&
            blinkDurationMs in blinkMinMs..blinkMaxMs &&
            hasValidCursor &&
            cooldownElapsed &&
            !paused &&
            !reminderActive

    /**
     * 撤回遠端監控同意時，是否丟棄當下開啟中的診斷檔案而不上傳（R-1）。
     * 只有「開 → 關」的轉換才丟棄：開啟狀態下設定變更（例如只改邊緣
     * 開關）不得觸發關檔；關 → 開、關 → 關皆不丟棄（無當下待上傳檔案
     * 可言，或新檔案將按新設定重新累積）。丟棄本身由呼叫端執行
     * `GazeLog.close(upload = false)`（關檔＋重置 pendingUpload），
     * 本函式不碰檔案。
     */
    fun shouldDiscardCurrentFileOnRevoke(
        wasRemoteUploadEnabled: Boolean,
        isRemoteUploadEnabled: Boolean,
    ): Boolean = wasRemoteUploadEnabled && !isRemoteUploadEnabled
}
