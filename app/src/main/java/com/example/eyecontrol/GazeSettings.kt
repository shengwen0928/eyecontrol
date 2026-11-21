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
    /** 陀螺儀角度修正：裝置相對校正姿勢傾斜時，幾何修正預測點。 */
    val gyroCompensation: Boolean = true,
    /** 臉距修正：臉相對校正時靠近／遠離時，繞螢幕中心等比縮放預測偏移。 */
    val distanceCompensation: Boolean = true,
) {

    fun clamped(): GazeSettings = copy(
        dwellTimeMs = dwellTimeMs.coerceIn(MIN_DWELL_TIME_MS, MAX_DWELL_TIME_MS),
        cooldownMs = cooldownMs.coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS),
        dwellRadiusPx = dwellRadiusPx.coerceIn(MIN_DWELL_RADIUS_PX, MAX_DWELL_RADIUS_PX),
        blinkMinMs = blinkMinMs.coerceIn(MIN_BLINK_MIN_MS, MAX_BLINK_MIN_MS),
        blinkMaxMs = blinkMaxMs.coerceIn(MIN_BLINK_MAX_MS, MAX_BLINK_MAX_MS),
        blinkThreshold = blinkThreshold.coerceIn(MIN_BLINK_THRESHOLD, MAX_BLINK_THRESHOLD),
        filterMinCutoff = filterMinCutoff.coerceIn(MIN_FILTER_MIN_CUTOFF, MAX_FILTER_MIN_CUTOFF),
        filterBeta = filterBeta.coerceIn(MIN_FILTER_BETA, MAX_FILTER_BETA),
    )

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
            putBoolean(KEY_GYRO_COMPENSATION, gyroCompensation)
            putBoolean(KEY_DISTANCE_COMPENSATION, distanceCompensation)
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

        private const val KEY_DWELL_TIME = "dwell_time_ms"
        private const val KEY_COOLDOWN = "cooldown_ms"
        private const val KEY_DWELL_RADIUS = "dwell_radius_px"
        private const val KEY_BLINK_MIN = "blink_min_ms"
        private const val KEY_BLINK_MAX = "blink_max_ms"
        private const val KEY_BLINK_THRESHOLD = "blink_threshold"
        private const val KEY_FILTER_CUTOFF = "filter_min_cutoff"
        private const val KEY_FILTER_BETA = "filter_beta"
        private const val KEY_GYRO_COMPENSATION = "gyro_compensation"
        private const val KEY_DISTANCE_COMPENSATION = "distance_compensation"

        fun load(context: Context): GazeSettings {
            val p = prefs(context)
            return GazeSettings(
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
                gyroCompensation = p.getBoolean(KEY_GYRO_COMPENSATION, true),
                distanceCompensation = p.getBoolean(KEY_DISTANCE_COMPENSATION, true),
            ).clamped()
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
