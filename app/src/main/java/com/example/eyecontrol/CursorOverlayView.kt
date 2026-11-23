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
    val reminderRingRadius: Float = 44f
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
