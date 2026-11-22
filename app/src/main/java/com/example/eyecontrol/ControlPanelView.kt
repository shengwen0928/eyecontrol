package com.example.eyecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 控制面板按鈕定義
 */
data class ControlPanelButton(
    val mode: GazeAccessibilityService.ActionMode,
    val label: String,
    val iconText: String
)

/**
 * 控制面板外觀樣式配置（設計系統淺色系：暖白底、淺色分隔線、Deep Sage 選中態）。
 * 只改外觀常數；尺寸、字級、按鍵邏輯一律不動。
 */
data class ControlPanelConfig(
    val items: List<ControlPanelButton> = listOf(
        ControlPanelButton(GazeAccessibilityService.ActionMode.TAP, "單擊", "👆"),
        ControlPanelButton(GazeAccessibilityService.ActionMode.DOUBLE_TAP, "雙擊", "✌️"),
        ControlPanelButton(GazeAccessibilityService.ActionMode.LONG_PRESS, "長按", "⏱️"),
        ControlPanelButton(GazeAccessibilityService.ActionMode.SWIPE, "滑動", "↔️"),
        ControlPanelButton(GazeAccessibilityService.ActionMode.PAUSED, "暫停", "⏸️")
    ),
    val backgroundColor: Int = Color.argb(245, 255, 255, 255),
    val borderColor: Int = Color.argb(255, 238, 237, 232),
    val activeBgColor: Int = Color.argb(255, 82, 111, 92),
    val hoverBgColor: Int = Color.argb(140, 231, 238, 232),
    val textColor: Int = Color.rgb(32, 37, 34),
    val activeTextColor: Int = Color.WHITE,
    val textSize: Float = 28f,
    val iconSize: Float = 44f
)

/**
 * 控制面板：提供單擊、雙擊、長按、滑動與暫停切換。
 */
class ControlPanelView(
    context: Context,
    val config: ControlPanelConfig = ControlPanelConfig()
) : View(context) {

    val items: List<ControlPanelButton> get() = config.items

    var activeMode = GazeAccessibilityService.ActionMode.TAP
    var swipeState = 0 // 0: 未開始, 1: 已選起點

    private var hoveredIndex = -1
    private var hoverProgress = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.backgroundColor
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.borderColor
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val activeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.activeBgColor
    }
    private val hoverBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.hoverBgColor
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.textColor
        textSize = config.textSize
        textAlign = Paint.Align.CENTER
    }
    private val activeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.activeTextColor
        textSize = config.textSize
        textAlign = Paint.Align.CENTER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.textColor
        textSize = config.iconSize
        textAlign = Paint.Align.CENTER
    }
    private val activeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = config.activeTextColor
        textSize = config.iconSize
        textAlign = Paint.Align.CENTER
    }

    fun setHover(index: Int, progress: Float) {
        hoveredIndex = index
        hoverProgress = progress
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 28f, 28f, bgPaint)
        canvas.drawRoundRect(rect, 28f, 28f, borderPaint)

        val btnHeight = height.toFloat() / items.size
        for (i in items.indices) {
            val item = items[i]
            val btnTop = i * btnHeight
            val btnBottom = btnTop + btnHeight
            val btnRect = RectF(6f, btnTop + 6f, width.toFloat() - 6f, btnBottom - 6f)

            val isActive = if (activeMode == GazeAccessibilityService.ActionMode.PAUSED) {
                item.mode == GazeAccessibilityService.ActionMode.PAUSED
            } else {
                item.mode == activeMode
            }

            if (isActive) {
                canvas.drawRoundRect(btnRect, 20f, 20f, activeBgPaint)
            }

            if (i == hoveredIndex && hoverProgress > 0f) {
                val hpRect = RectF(btnRect.left, btnRect.top, btnRect.left + btnRect.width() * hoverProgress, btnRect.bottom)
                canvas.drawRoundRect(hpRect, 20f, 20f, hoverBgPaint)
            }

            val iconY = btnTop + btnHeight * 0.42f
            val labelY = btnTop + btnHeight * 0.78f

            // 選中態用白字（Deep Sage 底），未選中用深灰字（暖白底），兩種狀態都有文字。
            canvas.drawText(item.iconText, width / 2f, iconY, if (isActive) activeIconPaint else iconPaint)

            val text = when {
                activeMode == GazeAccessibilityService.ActionMode.PAUSED && item.mode == GazeAccessibilityService.ActionMode.PAUSED -> "重啟"
                item.mode == GazeAccessibilityService.ActionMode.SWIPE && swipeState == 1 -> "終點"
                else -> item.label
            }
            canvas.drawText(text, width / 2f, labelY, if (isActive) activeTextPaint else textPaint)

            if (i < items.size - 1) {
                canvas.drawLine(15f, btnBottom, width.toFloat() - 15f, btnBottom, borderPaint)
            }
        }
    }
}
