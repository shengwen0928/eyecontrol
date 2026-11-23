/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.hypot

/**
 * 眼部影像度量：從已經存在的相機幀 Bitmap 上，對雙眼區域做即時量測。
 *
 * [measure] 只產出數字（平均亮度、對比、反光比例、以及實驗性的虹膜精修位置），
 * 影像本身不儲存、不上傳、不寫入檔案；Bitmap 只在記憶體裡讀，用完即棄。
 * 例外是 [cropEyes]：它只裁雙眼區、複製像素成獨立 Bitmap，專供內部調校的
 * 驗證影格留存／上傳（VerifyFrames）使用，正式上架前整段移除。
 * 呼叫端（FaceTrackerEngine）保證傳入的 Bitmap 在量測期間沒有被新幀覆寫
 * （世代計數驗證，見 FaceTrackerEngine 的幀槽表）；這裡只做純量測。
 *
 * 量測座標：landmark 是相對於餵給 MediaPipe 的同一張 Bitmap 的正規化座標，
 * 因此眼眶矩形與虹膜位置先以正規化座標表示，再乘上 Bitmap 尺寸換算成像素。
 */
object EyeImageMetrics {

    /** 一隻眼睛的幾何資訊（正規化座標）：眼眶外框與 landmark 虹膜中心。 */
    data class EyeGeometry(
        val rectNorm: RectF,
        val irisNorm: PointF,
    )

    /** 雙眼合併的度量。refined 虹膜位置為正規化座標，守衛失敗時為 null。 */
    data class EyeMetrics(
        val meanLum: Double,
        val contrast: Double,
        val specularRatio: Double,
        val refinedLeftIris: PointF?,
        val refinedRightIris: PointF?,
    )

    // ---- 幀降級門檻（啟發式、未經實機校準，只擋極端情況） ----
    /** 眼區平均亮度低於此值：幾乎全黑，landmark 本身已不可信。 */
    const val DEGRADED_MAX_MEAN_LUM = 12.0

    /** 眼區亮度標準差低於此值：畫面太平（過暗／過曝糊成一片），沒有可用紋理。 */
    const val DEGRADED_MIN_CONTRAST = 4.0

    /** 任一眼的過曝亮點像素比例高於此值：鏡片反光蓋住眼球。 */
    const val DEGRADED_MAX_SPECULAR_RATIO = 0.25

    /** 過曝亮點判定：亮度高於此值的取樣像素視為反光。 */
    private const val SPECULAR_LUM = 235.0

    /** 取樣步進（像素）：只讀每 4 個像素中的 1 個，量測成本與眼眶面積脫鉤。 */
    private const val SAMPLE_STRIDE = 4

    /** 虹膜精修：精修中心與 landmark 中心的距離上限（眼寬比例），超過就捨棄。 */
    private const val REFINE_MAX_DRIFT_FRACTION = 0.25

    /** 虹膜精修：精修結果的混合權重（landmark 佔 0.65、影像質心佔 0.35）。 */
    private const val REFINE_BLEND_WEIGHT = 0.35f

    /** 虹膜精修：暗像素佔比的有效範圍，太少（沒有瞳孔）或太多（整區都暗）都捨棄。 */
    private const val REFINE_MIN_DARK_FRACTION = 0.03
    private const val REFINE_MAX_DARK_FRACTION = 0.60

    /** 量測 ROI 上限（半寬／半高，像素）：以虹膜為中心裁切，暫存陣列有界。 */
    private const val MAX_ROI_HALF_WIDTH = 200
    private const val MAX_ROI_HALF_HEIGHT = 100

    /** 虹膜精修：對比低於此值視為無紋理（整區糊成一片），沒有可信的瞳孔訊號。 */
    private const val REFINE_MIN_CONTRAST = 1.0

    /**
     * 量測雙眼。任一眼矩形無效或 Bitmap 已回收時回 null（未知，呼叫端不得降級）。
     * [refineIris] 為 true 時才做虹膜影像精修（實驗功能，預設關）。
     */
    fun measure(
        bitmap: Bitmap,
        leftEye: EyeGeometry,
        rightEye: EyeGeometry,
        refineIris: Boolean,
    ): EyeMetrics? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val left = measureEye(bitmap, leftEye, refineIris) ?: return null
        val right = measureEye(bitmap, rightEye, refineIris) ?: return null
        return EyeMetrics(
            meanLum = (left.mean + right.mean) / 2.0,
            contrast = (left.std + right.std) / 2.0,
            specularRatio = maxOf(left.specular, right.specular),
            refinedLeftIris = left.refinedIris,
            refinedRightIris = right.refinedIris,
        )
    }

    /** 裁切聯集尺寸上限（像素）：垃圾 landmark 拉出的全圖矩形會被壓回雙眼中點周圍。 */
    private const val MAX_CROP_WIDTH = 640
    private const val MAX_CROP_HEIGHT = 320

    /**
     * 雙眼裁切（驗證影格用，VerifyFrames 呼叫）：取雙眼外框聯集矩形，
     * 把像素複製成一張獨立 Bitmap——與三緩衝的原始 Bitmap 脫鉤，
     * 原圖被新幀覆寫／回收也不影響裁切內容。只裁眼區（含兩眼與鼻樑），
     * 絕不含全臉。回傳的 Bitmap 由呼叫端擁有（用完須 recycle）；
     * Bitmap 已回收或矩形無效時回 null。
     */
    fun cropEyes(
        bitmap: Bitmap,
        leftEye: EyeGeometry,
        rightEye: EyeGeometry,
    ): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val w = bitmap.width
        val h = bitmap.height
        // 與 measureEye 相同的外擴 10% 與邊界夾取，兩眼算法一致。
        fun paddedPx(eye: EyeGeometry): RectF {
            val padX = eye.rectNorm.width() * 0.1f
            val padY = eye.rectNorm.height() * 0.1f
            return RectF(
                ((eye.rectNorm.left - padX) * w).coerceIn(0f, (w - 1).toFloat()),
                ((eye.rectNorm.top - padY) * h).coerceIn(0f, (h - 1).toFloat()),
                ((eye.rectNorm.right + padX) * w).coerceIn(0f, (w - 1).toFloat()),
                ((eye.rectNorm.bottom + padY) * h).coerceIn(0f, (h - 1).toFloat()),
            )
        }
        val l = paddedPx(leftEye)
        val r = paddedPx(rightEye)
        var x0 = minOf(l.left, r.left).toInt()
        var y0 = minOf(l.top, r.top).toInt()
        var x1 = maxOf(l.right, r.right).toInt()
        var y1 = maxOf(l.bottom, r.bottom).toInt()
        // 聯集過大時以雙眼中點為中心壓回上限，避免垃圾 landmark 拉出全圖裁切。
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        if (x1 - x0 + 1 > MAX_CROP_WIDTH) {
            x0 = (cx - MAX_CROP_WIDTH / 2).coerceIn(0, w - 1)
            x1 = (x0 + MAX_CROP_WIDTH - 1).coerceIn(0, w - 1)
        }
        if (y1 - y0 + 1 > MAX_CROP_HEIGHT) {
            y0 = (cy - MAX_CROP_HEIGHT / 2).coerceIn(0, h - 1)
            y1 = (y0 + MAX_CROP_HEIGHT - 1).coerceIn(0, h - 1)
        }
        val rw = x1 - x0 + 1
        val rh = y1 - y0 + 1
        if (rw < 8 || rh < 8) return null
        val out = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        // Rect 的 right/bottom 是 exclusive，故 +1；dst 與 src 同尺寸，1:1 複製像素。
        val drawn = runCatching {
            Canvas(out).drawBitmap(
                bitmap,
                Rect(x0, y0, x1 + 1, y1 + 1),
                RectF(0f, 0f, rw.toFloat(), rh.toFloat()),
                null,
            )
        }.isSuccess
        if (!drawn) {
            out.recycle()
            return null
        }
        return out
    }

    private data class SingleEye(
        val mean: Double,
        val std: Double,
        val specular: Double,
        val refinedIris: PointF?,
    )

    private fun measureEye(
        bitmap: Bitmap,
        eye: EyeGeometry,
        refineIris: Boolean,
    ): SingleEye? {
        val w = bitmap.width
        val h = bitmap.height
        // 眼眶外擴 10%（含眼瞼邊緣），夾在影像範圍內。
        val padX = eye.rectNorm.width() * 0.1f
        val padY = eye.rectNorm.height() * 0.1f
        val x0 = ((eye.rectNorm.left - padX) * w).toInt().coerceIn(0, w - 1)
        val x1 = ((eye.rectNorm.right + padX) * w).toInt().coerceIn(0, w - 1)
        val y0 = ((eye.rectNorm.top - padY) * h).toInt().coerceIn(0, h - 1)
        val y1 = ((eye.rectNorm.bottom + padY) * h).toInt().coerceIn(0, h - 1)
        // 垃圾 landmark 可能拉出全圖大的矩形：以虹膜為中心裁到 400×200 上限，
        // 量測用的暫存陣列有界（80k 像素），不會因一幀壞點就配置數 MB。
        val irisPxX = (eye.irisNorm.x * w).toInt().coerceIn(0, w - 1)
        val irisPxY = (eye.irisNorm.y * h).toInt().coerceIn(0, h - 1)
        val cx0 = maxOf(x0, irisPxX - MAX_ROI_HALF_WIDTH)
        val cx1 = minOf(x1, irisPxX + MAX_ROI_HALF_WIDTH)
        val cy0 = maxOf(y0, irisPxY - MAX_ROI_HALF_HEIGHT)
        val cy1 = minOf(y1, irisPxY + MAX_ROI_HALF_HEIGHT)
        val rw = cx1 - cx0 + 1
        val rh = cy1 - cy0 + 1
        if (rw < 4 || rh < 4) return null
        val pixels = IntArray(rw * rh)
        runCatching { bitmap.getPixels(pixels, 0, rw, cx0, cy0, rw, rh) }.onFailure { return null }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        var specular = 0
        for (yy in 0 until rh step SAMPLE_STRIDE) {
            val rowBase = yy * rw
            for (xx in 0 until rw step SAMPLE_STRIDE) {
                val c = pixels[rowBase + xx]
                val lum = 0.299 * ((c shr 16) and 0xFF) +
                    0.587 * ((c shr 8) and 0xFF) +
                    0.114 * (c and 0xFF)
                sum += lum
                sumSq += lum * lum
                count++
                if (lum > SPECULAR_LUM) specular++
            }
        }
        if (count == 0) return null
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        val std = kotlin.math.sqrt(variance)

        var refined: PointF? = null
        if (refineIris) {
            refined = refineIrisCenter(
                pixels, rw, rh, cx0, cy0, w, h, mean, std, eye,
            )
        }
        return SingleEye(
            mean = mean,
            std = std,
            specular = specular.toDouble() / count,
            refinedIris = refined,
        )
    }

    /**
     * 虹膜影像精修：以 ROI 平均亮度以下 0.5 個標準差為暗像素門檻，
     * 取暗像素質心；質心必須落在 landmark 虹膜中心附近（眼寬 25% 內），
     * 且暗像素佔比合理，否則回 null（沿用 landmark 位置）。
     * 回傳的是 landmark 與質心混合後的正規化座標。
     */
    private fun refineIrisCenter(
        pixels: IntArray,
        rw: Int,
        rh: Int,
        x0: Int,
        y0: Int,
        imgW: Int,
        imgH: Int,
        mean: Double,
        std: Double,
        eye: EyeGeometry,
    ): PointF? {
        // 無紋理（整區糊成一片）時沒有可信的瞳孔訊號：不精修，沿用 landmark。
        if (std < REFINE_MIN_CONTRAST) return null
        val threshold = mean - 0.5 * std
        var darkCount = 0
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        for (yy in 0 until rh step SAMPLE_STRIDE) {
            val rowBase = yy * rw
            for (xx in 0 until rw step SAMPLE_STRIDE) {
                val c = pixels[rowBase + xx]
                val lum = 0.299 * ((c shr 16) and 0xFF) +
                    0.587 * ((c shr 8) and 0xFF) +
                    0.114 * (c and 0xFF)
                count++
                if (lum < threshold) {
                    darkCount++
                    sumX += xx
                    sumY += yy
                }
            }
        }
        if (count == 0) return null
        val darkFraction = darkCount.toDouble() / count
        if (darkFraction < REFINE_MIN_DARK_FRACTION || darkFraction > REFINE_MAX_DARK_FRACTION) return null
        val centroidPxX = (x0 + sumX / darkCount).toFloat()
        val centroidPxY = (y0 + sumY / darkCount).toFloat()
        val centroidNormX = centroidPxX / imgW
        val centroidNormY = centroidPxY / imgH
        val eyeWidthNorm = eye.rectNorm.width()
        val drift = hypot(
            (centroidNormX - eye.irisNorm.x).toDouble(),
            (centroidNormY - eye.irisNorm.y).toDouble(),
        )
        if (drift > REFINE_MAX_DRIFT_FRACTION * eyeWidthNorm) return null
        // 混合：landmark 為主、影像質心為輔，避免單幀影像雜訊把虹膜位置拉偏。
        val bx = eye.irisNorm.x * (1f - REFINE_BLEND_WEIGHT) + centroidNormX * REFINE_BLEND_WEIGHT
        val by = eye.irisNorm.y * (1f - REFINE_BLEND_WEIGHT) + centroidNormY * REFINE_BLEND_WEIGHT
        return PointF(bx, by)
    }

    /**
     * 幀是否降級：眼區影像極端不可信時回 true，服務不得用該幀驅動停留累積。
     * 度量未知（NaN）時回 false——「不知道」不等於「不可信」，不降級。
     */
    fun isDegraded(sample: GazeSample): Boolean {
        if (sample.eyeMeanLum.isFinite() && sample.eyeMeanLum < DEGRADED_MAX_MEAN_LUM) return true
        if (sample.eyeContrast.isFinite() && sample.eyeContrast < DEGRADED_MIN_CONTRAST) return true
        if (sample.specularRatio.isFinite() && sample.specularRatio > DEGRADED_MAX_SPECULAR_RATIO) return true
        return false
    }
}
