/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

/**
 * 校正逐筆紀錄（暫時性診斷，內部調校用，正式上架前整段移除——見 REMOVAL_CHECKLIST.md）。
 *
 * 背景：留一輪交叉驗證誤差（2422 px，2026-11-23 實機）顯示三輪校正彼此對不起來，
 * 且手機稍微晃動就失準；但校正樣本只存在記憶體、存檔只留模型，事後無法分析是哪一輪、
 * 哪個點、在什麼光線與裝置角度下跑掉。本紀錄把每點「全部」原始樣本（含被離群值剔除
 * 的，kept 欄位標示）連同輪次、目標、相對時間、裝置角度與光線記下來，隨校正完成
 * 上傳到監控端點（id 首碼 `calib-`，摘要 JSON 為 `calib-...-sum`，rows=0），
 * 供分析跨輪不一致與手機晃動的影響。純 Kotlin，不碰 Android API，可走 harness 測試。
 *
 * 欄位：pass（輪次 0 起）、target（目標點 0 起，row-major）、idx（該點緩衝區序號）、
 * tMs（相對本次校正首筆樣本的毫秒）、targetX／targetY（目標像素座標）、kept（是否被
 * 留用進訓練集）、luxLog（log10(lux)，NaN 表無感測器資料）、devPitch／devRoll（裝置
 * 角度弧度，NaN 表無資料）、f0..f24（25 維特徵向量，與模型輸入同一份陣列）、
 * wallMs（絕對時間戳毫秒）、luxRaw（原始 lux）、leftOpen／rightOpen（睜眼程度）、
 * eyeMeanLum／eyeContrast／specularRatio（眼部影像度量）、rawPitch／rawRoll／rawYaw
 * （未平滑原始 orientation）、rvX／rvY／rvZ／rvW（旋轉向量原始分量）、predX／predY
 * （訓練完成後以新模型對該樣本的預測座標，與報告頁誤差同一算法，NaN 表未算出）。
 * 非有限值一律記為空欄，絕不以 0 假裝量到。
 */
object CalibLog {

    /** 一筆校正樣本。features 為餵給模型的特徵陣列（不複製，呼叫端不得事後修改）。predX／predY 由訓練完成後填入。 */
    data class Row(
        val pass: Int,
        val targetIndex: Int,
        val sampleIndex: Int,
        val tMs: Long,
        val targetX: Float,
        val targetY: Float,
        val kept: Boolean,
        val luxLog: Double,
        val devicePitch: Double,
        val deviceRoll: Double,
        val features: DoubleArray,
        val wallMs: Long = 0L,
        val luxRaw: Double = Double.NaN,
        val leftOpen: Double = Double.NaN,
        val rightOpen: Double = Double.NaN,
        val eyeMeanLum: Double = Double.NaN,
        val eyeContrast: Double = Double.NaN,
        val specularRatio: Double = Double.NaN,
        val rawPitch: Double = Double.NaN,
        val rawRoll: Double = Double.NaN,
        val rawYaw: Double = Double.NaN,
        val rvX: Double = Double.NaN,
        val rvY: Double = Double.NaN,
        val rvZ: Double = Double.NaN,
        val rvW: Double = Double.NaN,
        var predX: Double = Double.NaN,
        var predY: Double = Double.NaN,
    )

    /** CSV 表頭（f 欄數以 [GazeFeatureExtractor.DIM] 為準；f 欄之後為診斷擴充欄）。 */
    fun header(): String = buildString {
        append("pass,target,idx,tMs,targetX,targetY,kept,luxLog,devPitch,devRoll")
        for (i in 0 until GazeFeatureExtractor.DIM) append(",f$i")
        append(",wallMs,luxRaw,leftOpen,rightOpen,eyeMeanLum,eyeContrast,specularRatio")
        append(",rawPitch,rawRoll,rawYaw,rvX,rvY,rvZ,rvW,predX,predY")
    }

    /** 全部樣本列成一份 CSV（含表頭）。空列回傳只有表頭的字串。 */
    fun buildCsv(rows: List<Row>): String = buildString {
        append(header()).append('\n')
        for (r in rows) {
            append(r.pass).append(',')
            append(r.targetIndex).append(',')
            append(r.sampleIndex).append(',')
            append(r.tMs).append(',')
            append(fmt(r.targetX.toDouble())).append(',')
            append(fmt(r.targetY.toDouble())).append(',')
            append(if (r.kept) 1 else 0).append(',')
            append(fmt(r.luxLog)).append(',')
            append(fmt(r.devicePitch)).append(',')
            append(fmt(r.deviceRoll))
            for (i in 0 until GazeFeatureExtractor.DIM) {
                append(',').append(fmt(r.features.getOrNull(i)))
            }
            append(',').append(if (r.wallMs == 0L) "" else r.wallMs.toString())
            append(',').append(fmt(r.luxRaw))
            append(',').append(fmt(r.leftOpen))
            append(',').append(fmt(r.rightOpen))
            append(',').append(fmt(r.eyeMeanLum))
            append(',').append(fmt(r.eyeContrast))
            append(',').append(fmt(r.specularRatio))
            append(',').append(fmt(r.rawPitch))
            append(',').append(fmt(r.rawRoll))
            append(',').append(fmt(r.rawYaw))
            append(',').append(fmt(r.rvX))
            append(',').append(fmt(r.rvY))
            append(',').append(fmt(r.rvZ))
            append(',').append(fmt(r.rvW))
            append(',').append(fmt(r.predX))
            append(',').append(fmt(r.predY))
            append('\n')
        }
    }

    /**
     * 校正摘要 JSON（與樣本 CSV 分開上傳，rows=0）。數字皆取自報告頁同一批計算，
     * 不重新評估；非有限值記為 null。perPointErrors 依 pointIndex 順序。
     * 被有效性檢查拒存時 refused 為 true，並附分離度、絕對分離度與兩項門檻
     * （見 CalibrationGate）。
     */
    fun buildSummaryJson(
        profileId: String,
        passCount: Int,
        screenW: Int,
        screenH: Int,
        featureDim: Int,
        inSampleErrorPx: Double,
        looErrorPx: Double,
        leavePassOutErrorPx: Double,
        worstPointIndex: Int,
        worstErrorPx: Double,
        perPointErrors: List<Double>,
        lightMinLux: Double,
        lightMaxLux: Double,
        lightMinLog: Double,
        lightMaxLog: Double,
        calibratedAtMs: Long,
        rowCount: Int,
        keptRowCount: Int,
        /** 是否被有效性檢查拒存（見 [CalibrationGate]）；預設 false（舊呼叫端相容）。 */
        refused: Boolean = false,
        /** 有效性檢查的分離度；NaN 表無法量測。 */
        separationRatio: Double = Double.NaN,
        /** 有效性檢查的比值門檻；NaN 表未執行檢查。 */
        separationThreshold: Double = Double.NaN,
        /** 有效性檢查的絕對分離度；NaN 表無法量測。 */
        separation: Double = Double.NaN,
        /** 有效性檢查的絕對分離度下限；NaN 表未執行檢查。 */
        separationMin: Double = Double.NaN,
        /** 每目標點採樣時因臉遺失丟棄的影格數（依 pointIndex 順序，長度 9；空表舊呼叫端相容）。 */
        droppedFaceLostPerPoint: List<Int> = emptyList(),
        /** 每目標點採樣時因眨眼／閉眼丟棄的影格數（依 pointIndex 順序，長度 9）。 */
        droppedBlinkPerPoint: List<Int> = emptyList(),
    ): String = buildString {
        append("{")
        append("\"profile\":").append(jsonStr(profileId)).append(',')
        append("\"passCount\":").append(passCount).append(',')
        append("\"screenW\":").append(screenW).append(',')
        append("\"screenH\":").append(screenH).append(',')
        append("\"featureDim\":").append(featureDim).append(',')
        append("\"inSampleErrorPx\":").append(num(inSampleErrorPx)).append(',')
        append("\"looErrorPx\":").append(num(looErrorPx)).append(',')
        append("\"leavePassOutErrorPx\":").append(num(leavePassOutErrorPx)).append(',')
        append("\"worstPointIndex\":").append(worstPointIndex).append(',')
        append("\"worstErrorPx\":").append(num(worstErrorPx)).append(',')
        append("\"perPointErrors\":[")
        perPointErrors.joinTo(this, separator = ",") { num(it) }
        append("],")
        append("\"lightMinLux\":").append(num(lightMinLux)).append(',')
        append("\"lightMaxLux\":").append(num(lightMaxLux)).append(',')
        append("\"lightMinLog\":").append(num(lightMinLog)).append(',')
        append("\"lightMaxLog\":").append(num(lightMaxLog)).append(',')
        append("\"calibratedAtMs\":").append(calibratedAtMs).append(',')
        append("\"rowCount\":").append(rowCount).append(',')
        append("\"keptRowCount\":").append(keptRowCount).append(',')
        append("\"refused\":").append(refused).append(',')
        append("\"separationRatio\":").append(num(separationRatio)).append(',')
        append("\"separationThreshold\":").append(num(separationThreshold)).append(',')
        append("\"separation\":").append(num(separation)).append(',')
        append("\"separationMin\":").append(num(separationMin)).append(',')
        append("\"droppedFaceLost\":[")
        droppedFaceLostPerPoint.joinTo(this, separator = ",") { it.toString() }
        append("],")
        append("\"droppedBlink\":[")
        droppedBlinkPerPoint.joinTo(this, separator = ",") { it.toString() }
        append("]")
        append("}")
    }

    private fun num(v: Double): String =
        if (v.isFinite()) "%.3f".format(java.util.Locale.US, v) else "null"

    private fun fmt(v: Double?): String =
        if (v == null || !v.isFinite()) "" else "%.3f".format(java.util.Locale.US, v)

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
