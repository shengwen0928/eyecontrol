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

import android.graphics.PointF
import android.graphics.RectF
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.hypot

/**
 * 一幀的視線觀測值。
 * features：餵給回歸模型的特徵向量（虹膜相對眼眶位置 + 頭部位置/距離 + 偏置項）。
 * leftOpen / rightOpen：眼睛睜開程度（眼高/眼寬），用來偵測眨眼、閉眼時凍結游標。
 * devicePitch / deviceRoll：同一時刻的裝置姿勢（弧度，旋轉向量）；NaN = 當時沒有感測器資料。
 * ambientLux：同一時刻的環境光（lux，光感測器）；NaN = 當時沒有感測器資料。
 * eyeMeanLum / eyeContrast / specularRatio：同一時刻的眼部影像度量（裝置端即時量測的數字，
 * 不含影像）；NaN = 當時沒有影像度量（例如幀槽已被新幀覆寫）。只做信心判斷與診斷記錄，
 * 不進 22 維特徵（特徵契約不變）。
 * 預測時只用「與校正時的變化量」，不拿絕對角度當特徵（見 GazeModel）。
 */
data class GazeSample(
    val features: DoubleArray,
    val leftOpen: Double,
    val rightOpen: Double,
    val timestampMs: Long,
    val devicePitch: Double = Double.NaN,
    val deviceRoll: Double = Double.NaN,
    val ambientLux: Float = Float.NaN,
    val eyeMeanLum: Double = Double.NaN,
    val eyeContrast: Double = Double.NaN,
    val specularRatio: Double = Double.NaN,
)

/** 虹膜影像精修結果（正規化座標）：landmark 位置與影像質心混合後的位置。 */
data class RefinedIris(
    val leftX: Double,
    val leftY: Double,
    val rightX: Double,
    val rightY: Double,
)

/** 臉轉向：以特徵 7（pitchRot）、8（yawRot）相對校正基準的「符號」判定方向。 */
enum class FaceTurn {
    CENTER, LEFT, RIGHT, UP, DOWN, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT,
}

/**
 * 從 MediaPipe Face Landmarker（478 點，含虹膜）萃取視線特徵。
 *
 * 核心想法：虹膜中心在「眼眶座標系」內的相對位置，隨視線方向線性變化；
 * 再加上鼻尖位置與兩眼間距（頭部平移/遠近的代理變數），
 * 交給校正階段學出的線性模型去對應螢幕座標。
 */
object GazeFeatureExtractor {
    // MediaPipe FaceMesh 標準索引
    private const val L_IRIS = 468
    private const val R_IRIS = 473
    private const val L_EYE_OUTER = 33
    private const val L_EYE_INNER = 133
    private const val L_EYE_TOP = 159
    private const val L_EYE_BOTTOM = 145
    private const val R_EYE_INNER = 362
    private const val R_EYE_OUTER = 263
    private const val R_EYE_TOP = 386
    private const val R_EYE_BOTTOM = 374
    private const val NOSE_TIP = 1

    /** 特徵維度（含偏置項），校正與推論兩端共用。 */
    const val DIM = 22

    fun extract(
        lm: List<NormalizedLandmark>,
        timestampMs: Long,
        refinedIris: RefinedIris? = null,
    ): GazeSample? {
        if (lm.size <= R_IRIS) return null

        fun x(i: Int) = lm[i].x().toDouble()
        fun y(i: Int) = lm[i].y().toDouble()
        fun dist(a: Int, b: Int) = hypot(x(a) - x(b), y(a) - y(b))

        // 左眼：虹膜在眼眶內的相對位置（以眼寬為尺度做正規化，對距離變化不敏感）
        val lW = dist(L_EYE_OUTER, L_EYE_INNER)
        val rW = dist(R_EYE_INNER, R_EYE_OUTER)
        if (lW < 1e-6 || rW < 1e-6) return null

        val lCx = (x(L_EYE_OUTER) + x(L_EYE_INNER)) / 2.0
        val lCy = (y(L_EYE_OUTER) + y(L_EYE_INNER)) / 2.0
        val rCx = (x(R_EYE_INNER) + x(R_EYE_OUTER)) / 2.0
        val rCy = (y(R_EYE_INNER) + y(R_EYE_OUTER)) / 2.0

        // 兩眼連線的中點與距離 (Interocular Distance)
        val eyeMidX = (lCx + rCx) / 2.0
        val eyeMidY = (lCy + rCy) / 2.0
        val iod = hypot(rCx - lCx, rCy - lCy)
        if (iod < 1e-6) return null

        val lIrisX = ((refinedIris?.leftX ?: x(L_IRIS)) - lCx) / lW
        val lIrisY = ((refinedIris?.leftY ?: y(L_IRIS)) - lCy) / lW
        val rIrisX = ((refinedIris?.rightX ?: x(R_IRIS)) - rCx) / rW
        val rIrisY = ((refinedIris?.rightY ?: y(R_IRIS)) - rCy) / rW

        // 頭部姿態代理（以 iod 正規化，對距離不敏感）：
        //   pitch（抬頭/低頭）：鼻尖相對兩眼中線的「垂直」偏移——頭抬起來時鼻尖靠近眼睛
        //   yaw（左右轉頭）  ：鼻尖相對兩眼中線的「水平」偏移
        //   roll（歪頭）     ：兩眼連線的斜率
        val pitch = (y(NOSE_TIP) - eyeMidY) / iod
        val yaw = (x(NOSE_TIP) - eyeMidX) / iod
        val roll = (rCy - lCy) / iod

        // 計算旋轉餘弦和正弦，將特徵旋轉回頭部座標系中，實現旋轉不變性 (Rotation Invariance)
        val cosRoll = (rCx - lCx) / iod
        val sinRoll = (rCy - lCy) / iod

        val lIrisXRot = lIrisX * cosRoll + lIrisY * sinRoll
        val lIrisYRot = -lIrisX * sinRoll + lIrisY * cosRoll
        val rIrisXRot = rIrisX * cosRoll + rIrisY * sinRoll
        val rIrisYRot = -rIrisX * sinRoll + rIrisY * cosRoll

        val pitchRot = -yaw * sinRoll + pitch * cosRoll
        val yawRot = yaw * cosRoll + pitch * sinRoll

        // 眼睛與頭部姿態之交互作用項：捕捉頭部轉動時，眼球生理轉動特徵的非線性響應
        val eyeHeadXRot = lIrisXRot * yawRot
        val eyeHeadYRot = lIrisYRot * pitchRot

        // 二階項：更精準描述頭部姿態的非線性變化（如 pitch^2, yaw^2, pitch * yaw 等）
        val pitchRotSq = pitchRot * pitchRot
        val yawRotSq = yawRot * yawRot
        val pitchYawRot = pitchRot * yawRot

        // 額外優化特徵：雙眼平均、雙眼差分（輻輳）、以及姿態/距離的額外二次項，全面抑制噪聲
        val avgIrisXRot = (lIrisXRot + rIrisXRot) / 2.0
        val avgIrisYRot = (lIrisYRot + rIrisYRot) / 2.0
        val irisDiffXRot = rIrisXRot - lIrisXRot
        val irisDiffYRot = rIrisYRot - lIrisYRot
        val iodSq = iod * iod
        val rollSq = roll * roll

        val leftOpen = dist(L_EYE_TOP, L_EYE_BOTTOM) / lW
        val rightOpen = dist(R_EYE_TOP, R_EYE_BOTTOM) / rW

        val features = doubleArrayOf(
            lIrisXRot, lIrisYRot, rIrisXRot, rIrisYRot,
            eyeHeadXRot, eyeHeadYRot, iod,
            pitchRot, yawRot, roll,
            pitchRotSq, yawRotSq, pitchYawRot,
            avgIrisXRot, avgIrisYRot,
            irisDiffXRot, irisDiffYRot,
            iodSq, rollSq,
            leftOpen, rightOpen,
            1.0, // 偏置項
        )

        return GazeSample(features, leftOpen, rightOpen, timestampMs)
    }

    /**
     * 雙眼幾何資訊（正規化座標）：眼眶外框（四個眼角點圍出的矩形）與虹膜中心，
     * 供眼部影像度量（EyeImageMetrics）換算像素座標用。landmark 不足時回 null。
     * 注意：精修後的虹膜位置不在這裡產生——影像度量在引擎端以此幾何資訊量測後，
     * 才把精修位置以 [RefinedIris] 傳回 [extract]。
     */
    fun eyeGeometry(
        lm: List<NormalizedLandmark>,
    ): Pair<EyeImageMetrics.EyeGeometry, EyeImageMetrics.EyeGeometry>? {
        if (lm.size <= R_IRIS) return null
        fun x(i: Int) = lm[i].x()
        fun y(i: Int) = lm[i].y()
        fun eyeRect(corners: IntArray): RectF {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in corners) {
                minX = minOf(minX, x(i))
                maxX = maxOf(maxX, x(i))
                minY = minOf(minY, y(i))
                maxY = maxOf(maxY, y(i))
            }
            return RectF(minX, minY, maxX, maxY)
        }
        val left = EyeImageMetrics.EyeGeometry(
            rectNorm = eyeRect(intArrayOf(L_EYE_OUTER, L_EYE_INNER, L_EYE_TOP, L_EYE_BOTTOM)),
            irisNorm = PointF(x(L_IRIS), y(L_IRIS)),
        )
        val right = EyeImageMetrics.EyeGeometry(
            rectNorm = eyeRect(intArrayOf(R_EYE_INNER, R_EYE_OUTER, R_EYE_TOP, R_EYE_BOTTOM)),
            irisNorm = PointF(x(R_IRIS), y(R_IRIS)),
        )
        if (left.rectNorm.width() <= 0f || left.rectNorm.height() <= 0f ||
            right.rectNorm.width() <= 0f || right.rectNorm.height() <= 0f
        ) return null
        return left to right
    }

    /**
     * 臉轉向辨識：pitchRot / yawRot 相對校正基準（basePitch / baseYaw）的帶符號差值，
     * 決定臉往哪個方向轉。死區 0.02（約 2% 眼距）蓋過量測雜訊；只做方向分類與記錄，
     * 不另設門檻干預控制——真正的方向補償在 GazeModel 的 kx / ky 補償項裡（同樣帶符號）。
     * 左右以相機影像座標為準（前鏡頭映象可能相反，實機驗證後再定）。
     */
    fun faceTurnDirection(f: DoubleArray, basePitch: Double, baseYaw: Double): FaceTurn {
        val dPitch = f[7] - basePitch
        val dYaw = f[8] - baseYaw
        val vertical = when {
            dPitch > FACE_TURN_DEAD_ZONE -> FaceTurn.DOWN
            dPitch < -FACE_TURN_DEAD_ZONE -> FaceTurn.UP
            else -> null
        }
        val horizontal = when {
            dYaw > FACE_TURN_DEAD_ZONE -> FaceTurn.LEFT
            dYaw < -FACE_TURN_DEAD_ZONE -> FaceTurn.RIGHT
            else -> null
        }
        return when {
            vertical == FaceTurn.UP && horizontal == FaceTurn.LEFT -> FaceTurn.UP_LEFT
            vertical == FaceTurn.UP && horizontal == FaceTurn.RIGHT -> FaceTurn.UP_RIGHT
            vertical == FaceTurn.DOWN && horizontal == FaceTurn.LEFT -> FaceTurn.DOWN_LEFT
            vertical == FaceTurn.DOWN && horizontal == FaceTurn.RIGHT -> FaceTurn.DOWN_RIGHT
            vertical != null -> vertical
            horizontal != null -> horizontal
            else -> FaceTurn.CENTER
        }
    }

    private const val FACE_TURN_DEAD_ZONE = 0.02
}
