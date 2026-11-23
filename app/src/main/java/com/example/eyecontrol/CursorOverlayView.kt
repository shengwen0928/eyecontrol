package com.example.eyecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 游標覆蓋層外觀樣式配置（設計系統淺色系：游標主體 Deep Sage、白描邊環，
 * 在亮色與深色 App 背景上都看得見；臉遺失灰圈、提醒 Warning 色環保留）。
 */
data class CursorOverlayConfig(
    val dotColor: Int = Color.argb(235, 82, 111, 92),
    val lostColor: Int = Color.argb(150, 161, 165, 159),
    val ringColor: Int = Color.WHITE,
    val flashColor: Int = Color.argb(170, 67, 134, 107),
    val strokeWidthRing: Float = 5f,
    val strokeWidthLost: Float = 5f,
    val strokeWidthFlash: Float = 8f,
    val dotRadius: Float = 14f,
    val lostRadius: Float = 16f,
    val ringRadius: Float = 26f,
    val flashRadius: Float = 40f,
    val flashDurationMs: Long = 250L,
    /** 條件變化提醒目標的顏色與外圈半徑：必須一眼認出與游標不同。 */
    val reminderColor: Int = Color.argb(230, 193, 138, 72),
    val reminderRingRadius: Float = 44f,
    /** RECOVERY_MODE 重啟目標的顏色與外圈半徑：深鼠尾草綠，與提醒的琥珀色區分。 */
    val recoveryColor: Int = Color.argb(235, 82, 111, 92),
    val recoveryRingRadius: Float = 44f,
    /**
     * 邊緣手勢二階段確認目標（淺色暖系設計系統）：暖象牙白填充盤＋鼠尾草綠
     * 環，與提醒的琥珀色、重啟目標的深鼠尾草綠實心點區分，一眼認得出。
     */
    val confirmColor: Int = Color.argb(235, 129, 155, 137),
    val confirmFillColor: Int = Color.argb(235, 247, 246, 242),
    val confirmRingRadius: Float = 44f
)

/**
 * 全螢幕透明覆蓋層：畫游標圓點與 dwell 進度環。
 */
class CursorOverlayView(
    context: Context,
    val config: CursorOverlayConfig = CursorOverlayConfig()
) : View(context) {

    private var cursor: Triple<Float, Float, Float>? = null // x, y, dwellProgress
    private var faceLost = false
    private var flashUntil = 0L

    /** 條件變化提醒目標（null = 未顯示）。與游標各自獨立繪製，互不影響。 */
    private var reminderTarget: Pair<Float, Float>? = null
    private var reminderHint: String = ""

    /**
     * RECOVERY_MODE 重啟目標（null = 未顯示）。與提醒目標各自獨立的繪製
     * 狀態、獨立開關，不得共用（提醒 preempt 語義不得污染重啟目標）。
     * [recoveryProgress] 為獨立線性 dwell 進度（0f–1f）；[recoveryFaceLost]
     * 為真時文案切為「看不到臉」，進度凍結顯示（不推進亦不取消）。
     */
    private var recoveryTarget: Pair<Float, Float>? = null
    private var recoveryProgress = 0f
    private var recoveryFaceLost = false

    /**
     * 邊緣手勢二階段確認目標（null = 未顯示）。與提醒目標、重啟目標各自
     * 獨立的繪製狀態、獨立開關，不得共用；服務保證同一時間只顯示一種
     * 目標（優先序：提醒 ＞ 確認 ＞ 面板／把手，PLAN D3-5）。[confirmProgress]
     * 為第二次 dwell 進度（0f–1f）；[confirmHint] 為確認文字（返回／回首頁）。
     */
    private var confirmTarget: Pair<Float, Float>? = null
    private var confirmProgress = 0f
    private var confirmHint: String = ""

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.dotColor
    }
    private val lostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.lostColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthLost
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.ringColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthRing
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.flashColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthFlash
    }
    private val reminderRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.reminderColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthRing
    }
    private val reminderDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.reminderColor
    }
    private val reminderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = 44f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 3f, Color.argb(200, 0, 0, 0))
    }
    private val recoveryRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.recoveryColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthRing
    }
    private val recoveryDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.recoveryColor
    }
    private val recoveryLostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.lostColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthRing
    }
    private val recoveryLostDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.lostColor
    }
    private val recoveryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = 44f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 3f, Color.argb(200, 0, 0, 0))
    }
    private val recoverySubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = 32f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 3f, Color.argb(200, 0, 0, 0))
    }
    private val confirmFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.confirmFillColor
    }
    private val confirmRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.confirmColor
        style = Paint.Style.STROKE
        strokeWidth = config.strokeWidthRing
    }
    private val confirmDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.confirmColor
    }
    private val confirmTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = 44f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 3f, Color.argb(200, 0, 0, 0))
    }

    fun setCursor(c: Triple<Float, Float, Float>?) {
        cursor = c
        faceLost = false
        invalidate()
    }

    /** 偵測不到臉：游標留在最後位置，改畫灰色空心圈。 */
    fun setFaceLost() {
        faceLost = true
        invalidate()
    }

    fun flashClick() {
        flashUntil = SystemClock.elapsedRealtime() + config.flashDurationMs
        invalidate()
    }

    /** 顯示條件變化提醒目標（螢幕中央的琥珀色環＋實心點＋一行提示文字）。 */
    fun showReminderTarget(x: Float, y: Float, hint: String) {
        reminderTarget = x to y
        reminderHint = hint
        invalidate()
    }

    /** 隱藏提醒目標（快速校正完成、略過或逾時時呼叫）。 */
    fun hideReminderTarget() {
        if (reminderTarget != null) {
            reminderTarget = null
            reminderHint = ""
            invalidate()
        }
    }

    /**
     * 顯示／更新 RECOVERY_MODE 重啟目標（螢幕中央的大目標＋進度環＋文字，
     * 複用提醒目標的繪製模式、狀態獨立）。每幀由服務呼叫，目標固定在
     * 傳入的中央座標；臉遺失時 [faceLost] 為真，文案切為「看不到臉」、
     * 進度凍結顯示。副文字為隱私明示（相機仍在運作），必須同時顯示。
     */
    fun updateRecoveryTarget(x: Float, y: Float, progress: Float, faceLost: Boolean) {
        recoveryTarget = x to y
        recoveryProgress = progress.coerceIn(0f, 1f)
        recoveryFaceLost = faceLost
        invalidate()
    }

    /** 隱藏重啟目標（重啟成功／驗證失敗／完全停止／校正交接時呼叫）。 */
    fun hideRecoveryTarget() {
        if (recoveryTarget != null) {
            recoveryTarget = null
            recoveryProgress = 0f
            recoveryFaceLost = false
            invalidate()
        }
    }

    /**
     * 顯示／更新邊緣手勢二階段確認目標（螢幕中央偏下的暖象牙白盤＋鼠尾草綠
     * 環＋進度弧＋確認文字，複用提醒／重啟目標的繪製模式、狀態獨立）。
     * 每幀由服務在確認期間呼叫；[hint] 為「注視這裡確認返回／回首頁」。
     */
    fun updateConfirmTarget(x: Float, y: Float, progress: Float, hint: String) {
        confirmTarget = x to y
        confirmProgress = progress.coerceIn(0f, 1f)
        confirmHint = hint
        invalidate()
    }

    /** 隱藏確認目標（確認完成／取消／中斷／提醒介入時呼叫，不留殘留提示）。 */
    fun hideConfirmTarget() {
        if (confirmTarget != null) {
            confirmTarget = null
            confirmProgress = 0f
            confirmHint = ""
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 提醒目標先畫：游標為 null 時（例如剛啟動）也要看得到目標
        reminderTarget?.let { (tx, ty) ->
            val r = config.reminderRingRadius
            canvas.drawCircle(tx, ty, r, reminderRingPaint)
            canvas.drawCircle(tx, ty, config.dotRadius, reminderDotPaint)
            if (reminderHint.isNotEmpty()) {
                canvas.drawText(reminderHint, tx, ty + r + 64f, reminderTextPaint)
            }
        }
        // 重啟目標：與提醒目標同為中央大目標＋進度環＋文字，狀態獨立。
        recoveryTarget?.let { (tx, ty) ->
            val r = config.recoveryRingRadius
            if (recoveryFaceLost) {
                canvas.drawCircle(tx, ty, r, recoveryLostPaint)
                canvas.drawCircle(tx, ty, config.dotRadius, recoveryLostDotPaint)
                // 凍結的進度照實顯示（灰色弧線），不推進亦不清除。
                if (recoveryProgress > 0f) {
                    canvas.drawArc(
                        tx - r, ty - r, tx + r, ty + r,
                        -90f, 360f * recoveryProgress, false, recoveryLostPaint,
                    )
                }
            } else {
                canvas.drawCircle(tx, ty, r, recoveryRingPaint)
                canvas.drawCircle(tx, ty, config.dotRadius, recoveryDotPaint)
                if (recoveryProgress > 0f) {
                    canvas.drawArc(
                        tx - r, ty - r, tx + r, ty + r,
                        -90f, 360f * recoveryProgress, false, recoveryRingPaint,
                    )
                }
            }
            val mainText = if (recoveryFaceLost) "看不到臉，無法重啟" else "注視這裡重新開始眼控"
            canvas.drawText(mainText, tx, ty + r + 64f, recoveryTextPaint)
            canvas.drawText(
                "相機仍在運作，只用來等你注視",
                tx, ty + r + 112f, recoverySubTextPaint,
            )
        }
        // 確認目標：與提醒／重啟目標同為大目標＋進度環＋文字，狀態獨立。
        // 服務保證同一時間只顯示一種目標；此處只負責繪製，不做優先序裁決。
        confirmTarget?.let { (tx, ty) ->
            val r = config.confirmRingRadius
            canvas.drawCircle(tx, ty, r, confirmFillPaint)
            canvas.drawCircle(tx, ty, r, confirmRingPaint)
            canvas.drawCircle(tx, ty, config.dotRadius, confirmDotPaint)
            if (confirmProgress > 0f) {
                canvas.drawArc(
                    tx - r, ty - r, tx + r, ty + r,
                    -90f, 360f * confirmProgress, false, confirmRingPaint,
                )
            }
            if (confirmHint.isNotEmpty()) {
                canvas.drawText(confirmHint, tx, ty + r + 64f, confirmTextPaint)
            }
        }
        val c = cursor ?: return
        val (x, y, progress) = c
        if (faceLost) {
            canvas.drawCircle(x, y, config.lostRadius, lostPaint)
            return
        }
        canvas.drawCircle(x, y, config.dotRadius, dotPaint)
        if (progress > 0f) {
            val r = config.ringRadius
            canvas.drawArc(x - r, y - r, x + r, y + r, -90f, 360f * progress, false, ringPaint)
        }
        if (SystemClock.elapsedRealtime() < flashUntil) {
            canvas.drawCircle(x, y, config.flashRadius, flashPaint)
            postInvalidateDelayed(50)
        }
    }
}
