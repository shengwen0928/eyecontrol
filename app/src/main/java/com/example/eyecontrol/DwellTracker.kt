package com.example.eyecontrol

import kotlin.math.hypot
import kotlin.math.min

/**
 * 自適應停留判定：純 Kotlin 狀態機，不依賴 Android API（時間由呼叫端傳入）。
 *
 * 解決固定錨點＋瞬間重置的兩個痛點：
 *  1. 游標緩慢漂移時，舊邏輯一出半徑就歸零，永遠點不到 → 錨點以 EMA 跟著緩漂，
 *     進度不因小幅漂移歸零；
 *  2. 視線靜止不動（例如閱讀）時舊邏輯照樣累積滿 → 進度只在「低速移動的幀」
 *     累積，快速跳動的幀不計入；短暫離開半徑時進度衰減而非歸零，
 *     離開取消半徑或逾時才真正重置。
 *
 * 呼叫端責任：臉遺失／閉眼／冷卻期間不得呼叫 update（或呼叫 reset），
 * 否則凍結期間的幀會污染進度；面板／提醒等接管流程時同樣先 reset。
 */
class DwellTracker {

    /** 目前錨點（跟著緩漂移動）；呼叫端可用它決定點擊落點與游標顯示。 */
    var anchorX = 0f
        private set

    /** 目前錨點 Y。 */
    var anchorY = 0f
        private set

    private var progressMs = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastUpdateMs = 0L
    private var outSinceMs = 0L
    private var started = false

    /** 清掉全部狀態：下次 update 會以當下注視點重新錨定。 */
    fun reset() {
        progressMs = 0f
        lastUpdateMs = 0L
        outSinceMs = 0L
        started = false
    }

    /**
     * 推進一幀，回傳停留進度 0..1。
     * [dwellTimeMs]、[radiusPx] 由呼叫端每幀傳入當下設定值（設定頁可即時改）。
     */
    fun update(
        x: Float,
        y: Float,
        nowMs: Long,
        dwellTimeMs: Long,
        radiusPx: Float,
    ): Float {
        if (!started) {
            anchorX = x
            anchorY = y
            lastX = x
            lastY = y
            lastUpdateMs = nowMs
            started = true
            return 0f
        }
        // 幀間隔夾在 0–200ms：背景執行緒卡住後的第一幀不得一次灌進數秒進度。
        val dt = (nowMs - lastUpdateMs).coerceIn(0L, 200L)
        lastUpdateMs = nowMs
        val frameDist = hypot((x - lastX).toDouble(), (y - lastY).toDouble()).toFloat()
        lastX = x
        lastY = y
        val speedPxPerSec = if (dt > 0L) frameDist / dt * 1000f else 0f

        val distToAnchor = hypot((x - anchorX).toDouble(), (y - anchorY).toDouble()).toFloat()
        if (distToAnchor > radiusPx * CANCEL_RADIUS_FACTOR) {
            // 跳到遠處：視為移開，重新錨定、進度歸零（取消語意保留）。
            anchorX = x
            anchorY = y
            progressMs = 0f
            outSinceMs = 0L
            return 0f
        }

        // 錨點跟著緩漂：每幀向當下注視點移動 20%，慢漂不會把進度拉掉，
        // 大跳動則因上一分支的取消半徑／速度門檻擋下，不會把錨點拖走。
        anchorX += (x - anchorX) * ANCHOR_FOLLOW_ALPHA
        anchorY += (y - anchorY) * ANCHOR_FOLLOW_ALPHA

        if (distToAnchor <= radiusPx && speedPxPerSec <= SPEED_CAP_PX_PER_SEC) {
            outSinceMs = 0L
            progressMs += dt
        } else {
            // 短暫離開或快速跳動：進度衰減（2 倍速扣），不是歸零；
            // 離開超過逾時才重置並重新錨定。
            if (outSinceMs == 0L) outSinceMs = nowMs
            progressMs = maxOf(0f, progressMs - dt * DECAY_RATE)
            if (nowMs - outSinceMs > OUT_TIMEOUT_MS) {
                progressMs = 0f
                outSinceMs = 0L
                anchorX = x
                anchorY = y
            }
        }
        if (dwellTimeMs <= 0L) return 0f
        return min(progressMs / dwellTimeMs, 1f)
    }

    companion object {
        /** 取消半徑倍率：超過半徑 2.5 倍才視為移開。 */
        private const val CANCEL_RADIUS_FACTOR = 2.5f

        /** 錨點跟隨係數（每幀）。 */
        private const val ANCHOR_FOLLOW_ALPHA = 0.2f

        /** 速度上限（px/s）：超過此速度的幀不累積進度（跳動／掃視不算停留）。 */
        private const val SPEED_CAP_PX_PER_SEC = 2000f

        /** 離開時的進度衰減倍率。 */
        private const val DECAY_RATE = 2f

        /** 離開多久後重置（ms）。 */
        private const val OUT_TIMEOUT_MS = 300L
    }
}
