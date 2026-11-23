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

import android.content.Context
import android.content.SharedPreferences

/**
 * 使用者可調參數：單一來源（SharedPreferences）。
 *
 * 預設值 = 改動前寫死在程式裡的行為，舊使用者升級後行為不變；
 * 校正頁與無障礙服務共用同一份設定（例如睜眼閾值兩邊一致）。
 * 讀取時一律經過 clamped() 範圍驗證，避免存檔被手動改壞或舊版本寫入離譜數值。
 */
data class GazeSettings(
    val dwellTimeMs: Long = DEFAULT_DWELL_TIME_MS,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val dwellRadiusPx: Float = DEFAULT_DWELL_RADIUS_PX,
    val blinkMinMs: Long = DEFAULT_BLINK_MIN_MS,
    val blinkMaxMs: Long = DEFAULT_BLINK_MAX_MS,
    val blinkThreshold: Double = DEFAULT_BLINK_THRESHOLD,
    /**
     * 眨眼點擊獨立開關（STEP 3，DCL-13 最小部分）：預設關閉。
     * 個人化意圖校正＋品質評估的啟用閘門尚未實作（待批 P-05），
     * 本開關開啟不等於該閘門已通過；閘門落地前，開啟即以現況
     * 時間窗判定，不得宣稱為已通過意圖校正。
     */
    val blinkClickEnabled: Boolean = false,
    val filterMinCutoff: Double = DEFAULT_FILTER_MIN_CUTOFF,
    val filterBeta: Double = DEFAULT_FILTER_BETA,
    /**
     * 游標靈敏度：預測點相對螢幕中心的偏移放大倍率。模型輸出偏保守、游標只聚在
     * 中間附近時調高（眼球小幅移動就能到達邊緣）；游標亂飄時調低。預設 1.0 = 不變。
     */
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    /**
     * 陀螺儀角度修正：裝置相對校正姿勢傾斜時，幾何修正預測點。
     * 預設關閉：特徵 7/8 是相機影像座標系的量測，分不清「頭動」與「手機動」，
     * kx/ky 頭部補償可能已經把裝置轉動算過一次，兩邊會重複計算（pitch 方向）。
     * 必須實機開／關對照、確認合併後誤差真的下降，才手動打開。
     */
    val gyroCompensation: Boolean = false,
    /** 臉距修正：臉相對校正時靠近／遠離時，繞螢幕中心等比縮放預測偏移。 */
    val distanceCompensation: Boolean = true,
    /**
     * 條件感知漂移修正：持續比對亮度／裝置角度／臉距與上次矯正時的條件，變化大時
     * 跳提醒做單點快速校正，並以歷史紀錄內插推算相近條件的偏移。
     * 預設開啟；關掉時不偵測、不提醒、不套用推算偏移（行為與舊版相同）。
     */
    val conditionCorrection: Boolean = true,
    /**
     * 診斷紀錄（內部調校用，正式上架前整段移除）：開啟後在本機記錄游標數據
     * （數字）與眼區驗證裁切（見 VerifyFrames），供分析游標不動／偏移的原因。
     * 預設開啟（調校期間）；關掉後停止本機擷取。每 120 秒自動關檔上傳並開新一段連續紀錄。
     */
    val diagnosticLogging: Boolean = true,
    /**
     * 遠端監控（內部調校用，正式上架前整段移除）：開啟後自動記錄游標數據與
     * 眼區驗證裁切並上傳到開發者的監控端點，供開發者直接監看分析，使用者
     * 不必自己看紀錄。預設開啟（調校期間）；關掉後停止上傳。
     * Play 商店版無網路權限，此開關不顯示且上傳不可能發生
     * （見 BuildConfig.REMOTE_MONITOR_ENABLED）。
     */
    val remoteMonitor: Boolean = true,
    /**
     * 虹膜影像精修（實驗功能）：用眼部影像的暗像素質心微調 landmark 虹膜位置，
     * 再餵給特徵計算。預設關閉；開啟後特徵分布改變，必須重新校正才生效
     * （舊模型配新特徵會失準）。影像只在裝置端即時量測，不儲存、不上傳。
     */
    val irisRefinement: Boolean = false,
    /**
     * 邊緣手勢總開關（Phase 3 D3-4，PLAN D3-3）：關閉時全部邊緣手勢停用。
     * 預設開啟——現況已有邊緣手勢，預設關閉會改變現況行為，不得擅改。
     */
    val edgeGesturesEnabled: Boolean = true,
    /**
     * 邊緣手勢分項開關（Phase 3 D3-4）：四邊各自獨立開關，預設全開
     * （現況四邊皆有動作）。總開關關閉時分項列停用、全部停用。
     */
    val edgeTopEnabled: Boolean = true,
    val edgeBottomEnabled: Boolean = true,
    val edgeLeftEnabled: Boolean = true,
    val edgeRightEnabled: Boolean = true,
) {

    fun clamped(): GazeSettings {
        // NaN 會穿透 coerceIn（比較皆 false），先換成預設值再夾範圍
        val safeRadius = dwellRadiusPx.takeIf { it.isFinite() } ?: DEFAULT_DWELL_RADIUS_PX
        val safeThreshold = blinkThreshold.takeIf { it.isFinite() } ?: DEFAULT_BLINK_THRESHOLD
        val safeCutoff = filterMinCutoff.takeIf { it.isFinite() } ?: DEFAULT_FILTER_MIN_CUTOFF
        val safeBeta = filterBeta.takeIf { it.isFinite() } ?: DEFAULT_FILTER_BETA
        val safeSensitivity = sensitivity.takeIf { it.isFinite() } ?: DEFAULT_SENSITIVITY
        val safeBlinkMin = blinkMinMs.coerceIn(MIN_BLINK_MIN_MS, MAX_BLINK_MIN_MS)
        // 眨眼上下限必須成有效區間：上限不得小於下限，否則眨眼點擊永遠觸發不了
        val safeBlinkMax = blinkMaxMs.coerceIn(MIN_BLINK_MAX_MS, MAX_BLINK_MAX_MS)
            .coerceAtLeast(safeBlinkMin)
        return copy(
            dwellTimeMs = dwellTimeMs.coerceIn(MIN_DWELL_TIME_MS, MAX_DWELL_TIME_MS),
            cooldownMs = cooldownMs.coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS),
            dwellRadiusPx = safeRadius.coerceIn(MIN_DWELL_RADIUS_PX, MAX_DWELL_RADIUS_PX),
            blinkMinMs = safeBlinkMin,
            blinkMaxMs = safeBlinkMax,
            blinkThreshold = safeThreshold.coerceIn(MIN_BLINK_THRESHOLD, MAX_BLINK_THRESHOLD),
            filterMinCutoff = safeCutoff.coerceIn(MIN_FILTER_MIN_CUTOFF, MAX_FILTER_MIN_CUTOFF),
            filterBeta = safeBeta.coerceIn(MIN_FILTER_BETA, MAX_FILTER_BETA),
            sensitivity = safeSensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
        )
    }

    fun save(context: Context) {
        prefs(context).edit().apply {
            putLong(KEY_DWELL_TIME, dwellTimeMs)
            putLong(KEY_COOLDOWN, cooldownMs)
            putFloat(KEY_DWELL_RADIUS, dwellRadiusPx)
            putLong(KEY_BLINK_MIN, blinkMinMs)
            putLong(KEY_BLINK_MAX, blinkMaxMs)
            putLong(KEY_BLINK_THRESHOLD, blinkThreshold.toRawBits())
            putBoolean(KEY_BLINK_CLICK_ENABLED, blinkClickEnabled)
            putLong(KEY_FILTER_CUTOFF, filterMinCutoff.toRawBits())
            putLong(KEY_FILTER_BETA, filterBeta.toRawBits())
            putFloat(KEY_SENSITIVITY, sensitivity)
            putBoolean(KEY_GYRO_COMPENSATION, gyroCompensation)
            putBoolean(KEY_DISTANCE_COMPENSATION, distanceCompensation)
            putBoolean(KEY_CONDITION_CORRECTION, conditionCorrection)
            putBoolean(KEY_DIAGNOSTIC_LOGGING, diagnosticLogging)
            putBoolean(KEY_REMOTE_MONITOR, remoteMonitor)
            putBoolean(KEY_IRIS_REFINEMENT, irisRefinement)
            putBoolean(KEY_EDGE_GESTURES_ENABLED, edgeGesturesEnabled)
            putBoolean(KEY_EDGE_TOP_ENABLED, edgeTopEnabled)
            putBoolean(KEY_EDGE_BOTTOM_ENABLED, edgeBottomEnabled)
            putBoolean(KEY_EDGE_LEFT_ENABLED, edgeLeftEnabled)
            putBoolean(KEY_EDGE_RIGHT_ENABLED, edgeRightEnabled)
        }.apply()
    }

    companion object {
        const val PREFS_NAME = "gaze_settings"

        // 預設值（改動前的寫死行為）
        const val DEFAULT_DWELL_TIME_MS = 1000L
        const val DEFAULT_COOLDOWN_MS = 1200L
        const val DEFAULT_DWELL_RADIUS_PX = 110f
        const val DEFAULT_BLINK_MIN_MS = 150L
        const val DEFAULT_BLINK_MAX_MS = 600L
        const val DEFAULT_BLINK_THRESHOLD = 0.08
        const val DEFAULT_FILTER_MIN_CUTOFF = 0.22
        const val DEFAULT_FILTER_BETA = 0.018
        const val DEFAULT_SENSITIVITY = 1.0f

        // 允許範圍（設定頁滑桿範圍與此一致）
        const val MIN_DWELL_TIME_MS = 400L
        const val MAX_DWELL_TIME_MS = 2500L
        const val MIN_COOLDOWN_MS = 500L
        const val MAX_COOLDOWN_MS = 3000L
        const val MIN_DWELL_RADIUS_PX = 60f
        const val MAX_DWELL_RADIUS_PX = 200f
        const val MIN_BLINK_MIN_MS = 80L
        const val MAX_BLINK_MIN_MS = 400L
        const val MIN_BLINK_MAX_MS = 300L
        const val MAX_BLINK_MAX_MS = 1500L
        const val MIN_BLINK_THRESHOLD = 0.04
        const val MAX_BLINK_THRESHOLD = 0.20
        const val MIN_FILTER_MIN_CUTOFF = 0.05
        const val MAX_FILTER_MIN_CUTOFF = 0.50
        const val MIN_FILTER_BETA = 0.005
        const val MAX_FILTER_BETA = 0.050
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 2.5f

        private const val KEY_DWELL_TIME = "dwell_time_ms"
        private const val KEY_COOLDOWN = "cooldown_ms"
        private const val KEY_DWELL_RADIUS = "dwell_radius_px"
        private const val KEY_BLINK_MIN = "blink_min_ms"
        private const val KEY_BLINK_MAX = "blink_max_ms"
        private const val KEY_BLINK_THRESHOLD = "blink_threshold"
        private const val KEY_BLINK_CLICK_ENABLED = "blink_click_enabled"
        private const val KEY_FILTER_CUTOFF = "filter_min_cutoff"
        private const val KEY_FILTER_BETA = "filter_beta"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_GYRO_COMPENSATION = "gyro_compensation"
        private const val KEY_DISTANCE_COMPENSATION = "distance_compensation"
        private const val KEY_CONDITION_CORRECTION = "condition_correction"
        private const val KEY_DIAGNOSTIC_LOGGING = "diagnostic_logging"
        private const val KEY_REMOTE_MONITOR = "remote_monitor"
        private const val KEY_IRIS_REFINEMENT = "iris_refinement"
        private const val KEY_EDGE_GESTURES_ENABLED = "edge_gestures_enabled"
        private const val KEY_EDGE_TOP_ENABLED = "edge_top_enabled"
        private const val KEY_EDGE_BOTTOM_ENABLED = "edge_bottom_enabled"
        private const val KEY_EDGE_LEFT_ENABLED = "edge_left_enabled"
        private const val KEY_EDGE_RIGHT_ENABLED = "edge_right_enabled"

        fun load(context: Context): GazeSettings {
            // 存檔損毀（型別不符等）時直接回預設值，不要讓例外沿 prefs 監聽炸開服務
            return try {
                val p = prefs(context)
                GazeSettings(
                    dwellTimeMs = p.getLong(KEY_DWELL_TIME, DEFAULT_DWELL_TIME_MS),
                    cooldownMs = p.getLong(KEY_COOLDOWN, DEFAULT_COOLDOWN_MS),
                    dwellRadiusPx = p.getFloat(KEY_DWELL_RADIUS, DEFAULT_DWELL_RADIUS_PX),
                    blinkMinMs = p.getLong(KEY_BLINK_MIN, DEFAULT_BLINK_MIN_MS),
                    blinkMaxMs = p.getLong(KEY_BLINK_MAX, DEFAULT_BLINK_MAX_MS),
                    blinkThreshold = Double.fromBits(
                        p.getLong(KEY_BLINK_THRESHOLD, DEFAULT_BLINK_THRESHOLD.toRawBits())
                    ),
                    // 眨眼點擊預設關閉（DCL-13）：從未存過該 key 時為關；
                    // 使用者先前若未存過此 key，升級後眨眼點擊即關閉，須
                    // 手動在設定頁開啟（現況行為改變為規格要求，非相容維持）。
                    blinkClickEnabled = p.getBoolean(KEY_BLINK_CLICK_ENABLED, false),
                    filterMinCutoff = Double.fromBits(
                        p.getLong(KEY_FILTER_CUTOFF, DEFAULT_FILTER_MIN_CUTOFF.toRawBits())
                    ),
                    filterBeta = Double.fromBits(
                        p.getLong(KEY_FILTER_BETA, DEFAULT_FILTER_BETA.toRawBits())
                    ),
                    sensitivity = p.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY),
                    gyroCompensation = p.getBoolean(KEY_GYRO_COMPENSATION, false),
                    distanceCompensation = p.getBoolean(KEY_DISTANCE_COMPENSATION, true),
                    conditionCorrection = p.getBoolean(KEY_CONDITION_CORRECTION, true),
                    // 調校期預設開啟：getBoolean 的預設值只在「從未存過該 key」
                    // 時生效；使用者先前明確關掉（存過 false）升級後仍是關，
                    // 不會被新預設值強制重開。
                    diagnosticLogging = p.getBoolean(KEY_DIAGNOSTIC_LOGGING, true),
                    remoteMonitor = p.getBoolean(KEY_REMOTE_MONITOR, true),
                    irisRefinement = p.getBoolean(KEY_IRIS_REFINEMENT, false),
                    // 邊緣手勢預設開啟（現況行為）：getBoolean 預設值只在
                    // 「從未存過該 key」時生效；使用者明確關掉後升級仍是關。
                    edgeGesturesEnabled = p.getBoolean(KEY_EDGE_GESTURES_ENABLED, true),
                    edgeTopEnabled = p.getBoolean(KEY_EDGE_TOP_ENABLED, true),
                    edgeBottomEnabled = p.getBoolean(KEY_EDGE_BOTTOM_ENABLED, true),
                    edgeLeftEnabled = p.getBoolean(KEY_EDGE_LEFT_ENABLED, true),
                    edgeRightEnabled = p.getBoolean(KEY_EDGE_RIGHT_ENABLED, true),
                ).clamped()
            } catch (e: Exception) {
                GazeSettings()
            }
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
