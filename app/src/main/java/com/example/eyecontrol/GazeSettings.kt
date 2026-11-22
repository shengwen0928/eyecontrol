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
     * 診斷紀錄（暫時除錯用）：開啟後在本機記錄游標數據（數字 only，不含影像、
     * 不上傳），供分析游標不動／偏移的原因。預設關閉；記錄最多 120 秒自動停止。
     */
    val diagnosticLogging: Boolean = false,
    /**
     * 遠端監控（暫時除錯用，調適完移除）：開啟後自動記錄游標數據並上傳到開發者
     * 的監控端點（只含游標數字與臉部特徵數值，不含影像），供開發者直接監看分析，
     * 使用者不必自己看紀錄。預設關閉；與診斷紀錄共用同一份本機檔案與 120 秒上限。
     * Play 商店版無網路權限，此開關不顯示（見 BuildConfig.REMOTE_MONITOR_ENABLED）。
     */
    val remoteMonitor: Boolean = false,
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
            putLong(KEY_FILTER_CUTOFF, filterMinCutoff.toRawBits())
            putLong(KEY_FILTER_BETA, filterBeta.toRawBits())
            putFloat(KEY_SENSITIVITY, sensitivity)
            putBoolean(KEY_GYRO_COMPENSATION, gyroCompensation)
            putBoolean(KEY_DISTANCE_COMPENSATION, distanceCompensation)
            putBoolean(KEY_CONDITION_CORRECTION, conditionCorrection)
            putBoolean(KEY_DIAGNOSTIC_LOGGING, diagnosticLogging)
            putBoolean(KEY_REMOTE_MONITOR, remoteMonitor)
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
        private const val KEY_FILTER_CUTOFF = "filter_min_cutoff"
        private const val KEY_FILTER_BETA = "filter_beta"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_GYRO_COMPENSATION = "gyro_compensation"
        private const val KEY_DISTANCE_COMPENSATION = "distance_compensation"
        private const val KEY_CONDITION_CORRECTION = "condition_correction"
        private const val KEY_DIAGNOSTIC_LOGGING = "diagnostic_logging"
        private const val KEY_REMOTE_MONITOR = "remote_monitor"

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
                    diagnosticLogging = p.getBoolean(KEY_DIAGNOSTIC_LOGGING, false),
                    remoteMonitor = p.getBoolean(KEY_REMOTE_MONITOR, false),
                ).clamped()
            } catch (e: Exception) {
                GazeSettings()
            }
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
