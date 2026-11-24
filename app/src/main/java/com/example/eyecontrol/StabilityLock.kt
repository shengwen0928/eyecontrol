/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

import kotlin.math.hypot

/**
 * 游標靜止鎖定：預測點在小範圍內抖動時把游標凍住，視線真的移開時立刻解鎖。
 *
 * 存在理由（實機數據，2026-11-24 09:43 三輪校正，51 欄逐筆紀錄）：
 * 盯著固定目標、眼睛未動、臉一張沒丟、光線 167–218 lux 時，模型對同一目標的
 * 預測仍在 14 筆樣本內上下跳，predY 範圍最大 494 px、逐軸標準差常見 20–60 px、
 * 最差一輪達 172 px。OneEuroFilter 只平滑、不鎖定，游標就跟著跳，停留點擊抓不住
 * 按鈕。本鎖定位在濾波之後、游標／停留／邊緣／面板共用座標之前，凍住的是顯示與
 * 輸入共用的注視點；原始預測仍完整記在診斷紀錄的 raw 欄位，抖動證據不遺失。
 *
 * 門檻取法（暫定，待更多實機數據複核）：
 * - 上鎖半徑 60 px：約等於一般輪次逐軸抖動標準差的 1–3 倍，小於一顆按鈕
 *   （48dp 在此機上約 170 px），鎖住時游標仍停在按鈕範圍內。
 * - 解鎖半徑 120 px（上鎖的 2 倍，遲滯）：掃視是數百 px 的一步到位，必定解鎖；
 *   慢速漂到鄰近目標時，連續 [EXCURSION_FRAMES] 幀超出上鎖半徑亦解鎖，
 *   游標不會被黏在舊位置。
 * - 連續 [STABLE_FRAMES] 幀穩定才上鎖：掃視途中不會誤鎖，約 0.13 秒即完成，
 *   遠快於預設 1 秒的停留點擊。
 *
 * 本類別不是智慧吸附（DCL-24～40）：它不知道畫面上有什麼目標，只凍結抖動；
 * 目標吸附仍維持「只對照、不實作」。狀態只活在服務行程內，臉遺失、旋轉、
 * 引擎重啟、重啟驗證成功時由呼叫端 [reset]，舊座標系的鎖定不得沿用。
 */
class StabilityLock(
    private val lockRadiusPx: Float = LOCK_RADIUS_PX,
    private val unlockRadiusPx: Float = UNLOCK_RADIUS_PX,
    private val stableFrames: Int = STABLE_FRAMES,
    private val excursionFrames: Int = EXCURSION_FRAMES,
) {
    private var lockedX: Float? = null
    private var lockedY: Float? = null
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorSumX = 0f
    private var anchorSumY = 0f
    private var anchorCount = 0
    private var excursionCount = 0

    val isLocked: Boolean get() = lockedX != null

    /**
     * 輸入濾波後座標，回傳顯示用座標：未上鎖時原樣回傳，上鎖後回傳鎖定點。
     * 非有限輸入直接放行並清狀態，不得把 NaN 鎖進游標。
     */
    fun update(x: Float, y: Float): Pair<Float, Float> {
        if (!x.isFinite() || !y.isFinite()) {
            reset()
            return x to y
        }
        val lx = lockedX
        val ly = lockedY
        if (lx != null && ly != null) {
            val dist = hypot(x - lx, y - ly)
            if (dist > unlockRadiusPx) {
                reset()
                anchorX = x
                anchorY = y
                anchorSumX = x
                anchorSumY = y
                anchorCount = 1
                return x to y
            }
            if (dist > lockRadiusPx) {
                excursionCount++
                if (excursionCount >= excursionFrames) {
                    reset()
                    anchorX = x
                    anchorY = y
                    anchorSumX = x
                    anchorSumY = y
                    anchorCount = 1
                    return x to y
                }
            } else {
                excursionCount = 0
            }
            return lx to ly
        }
        if (anchorCount == 0 || hypot(x - anchorX, y - anchorY) > lockRadiusPx) {
            anchorX = x
            anchorY = y
            anchorSumX = x
            anchorSumY = y
            anchorCount = 1
            return x to y
        }
        anchorSumX += x
        anchorSumY += y
        anchorCount++
        if (anchorCount >= stableFrames) {
            lockedX = anchorSumX / anchorCount
            lockedY = anchorSumY / anchorCount
            excursionCount = 0
            return lockedX!! to lockedY!!
        }
        return x to y
    }

    /** 清掉鎖定與累積狀態：臉遺失、旋轉、引擎重啟、重啟驗證成功時呼叫。 */
    fun reset() {
        lockedX = null
        lockedY = null
        anchorCount = 0
        anchorSumX = 0f
        anchorSumY = 0f
        excursionCount = 0
    }

    companion object {
        const val LOCK_RADIUS_PX = 60f
        const val UNLOCK_RADIUS_PX = 120f
        const val STABLE_FRAMES = 4
        const val EXCURSION_FRAMES = 4
    }
}
