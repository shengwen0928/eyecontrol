/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

    /**
     * 收合狀態：平時只在右緣留一個小把手，注視把手才展開整列面板。
     * view 本身的尺寸維持 180×700 不變，收合時其餘部分透明不繪製；
     * 命中判定由服務端依同樣的幾何常數計算，不吃 view 邊界。
     */
    var collapsed = true
    private var handleProgress = 0f

    /**
     * 切換收合／展開，並重置注視暫存。函式名刻意避開 setCollapsed：
     * Kotlin 會為 var collapsed 自動產生同簽名的 setter，撞名無法編譯。
     */
    fun setPanelCollapsed(collapsed: Boolean) {
        this.collapsed = collapsed
        hoveredIndex = -1
        hoverProgress = 0f
        handleProgress = 0f
        invalidate()
    }

    /** 收合把手的注視展開進度（0–1），由服務端每幀更新。 */
    fun setHandleProgress(progress: Float) {
        handleProgress = progress.coerceIn(0f, 1f)
        invalidate()
    }

    companion object {
        /** 收合時看得見的把手尺寸。 */
        const val HANDLE_VISUAL_WIDTH = 36f
        const val HANDLE_VISUAL_HEIGHT = 160f

        /** 收合時注視展開的判定區：比看得見的把手寬，漂移的游標也進得去。 */
        const val HANDLE_HIT_WIDTH = 90f
        const val HANDLE_HIT_HEIGHT = 240f
    }

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
    private val handleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(82, 111, 92) // Deep Sage，與展開態的選中底色一致
    }
    private val handleProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 129, 155, 137) // Sage，注視進度由下往上填
    }
    private val handleChevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setHover(index: Int, progress: Float) {
        hoveredIndex = index
        hoverProgress = progress
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (collapsed) {
            drawHandleTab(canvas)
            return
        }
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

    /**
     * 收合把手：右緣置中的小圓角片，中央畫「‹」表示向左展開；
     * 注視進度由下往上填滿。裁切路徑沿用圓角，避免進度填色溢出圓角。
     * 線條 chevron 不依賴字體，任何裝置都畫得出來。
     */
    private fun drawHandleTab(canvas: Canvas) {
        val tabW = HANDLE_VISUAL_WIDTH
        val tabH = HANDLE_VISUAL_HEIGHT
        val left = width.toFloat() - tabW
        val top = (height.toFloat() - tabH) / 2f
        val tabRect = RectF(left, top, width.toFloat(), top + tabH)
        val clipPath = Path().apply {
            addRoundRect(tabRect, 18f, 18f, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(tabRect, handleBgPaint)
        if (handleProgress > 0f) {
            val fillTop = top + tabH * (1f - handleProgress)
            canvas.drawRect(RectF(left, fillTop, width.toFloat(), top + tabH), handleProgressPaint)
        }
        val cx = width.toFloat() - tabW / 2f
        val cy = top + tabH / 2f
        val chevron = Path().apply {
            moveTo(cx + 9f, cy - 15f)
            lineTo(cx - 7f, cy)
            lineTo(cx + 9f, cy + 15f)
        }
        canvas.drawPath(chevron, handleChevronPaint)
        canvas.restore()
    }
}
