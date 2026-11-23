package com.example.eyecontrol

import kotlin.math.hypot

/**
 * 校正品質評估：純函數，不依賴 Android 框架，只對特徵向量與目標座標做計算。
 *
 * 兩種誤差，意義不同，必須分開看：
 * - 訓練集誤差（in-sample）：模型在自己訓練資料上的殘差，偏樂觀，只能當作
 *   「校正流程有沒有跑完、資料有沒有髒掉」的 sanity check。
 * - 留一校正點交叉驗證（leave-one-point-out）：每次留出一個校正點、用其餘
 *   8 點重新訓練，再預測被留出的點。被評估的點完全沒參與訓練，所以這個
 *   數字才是對「沒看過的位置預測得準不準」的誠實估計，也是校正完成時
 *   精度等級的依據。
 * - 留一輪交叉驗證（leave-one-pass-out，多光源校正專用）：每次留出一整輪
 *   （同一光線條件下的一次 9 點採樣）、用其餘輪重新訓練，再預測被留出的輪。
 *   被評估的輪完全沒參與訓練，是對「沒見過的光線條件下預測得準不準」的
 *   誠實估計；輪次少於 2 個時不計算。
 *
 * 一個校正點 = 一個目標座標；同座標的樣本（剔除離群值後每點約 14 幀）
 * 在分組與交叉驗證時視為同一組，絕不拆散到訓練集與測試集兩邊。
 */
object GazeEvaluator {

    /** 單一校正點的誤差統計。pointIndex 為該點在首次出現順序中的編號（0 起）。 */
    data class PointError(
        val pointIndex: Int,
        val target: Pair<Float, Float>,
        val meanErrorPx: Double,
        val sampleCount: Int,
    )

    /** 一次校正的完整評估報告（皆為訓練集內數字，見上方說明）。 */
    data class CalibrationReport(
        val inSampleMeanErrorPx: Double,
        val perPoint: List<PointError>,
        /** 誤差最大的校正點（通常是角落點，代表模型在該區域最不準）。 */
        val worstPoint: PointError,
    )

    /** 訓練集評估：整體與逐點平均誤差。 */
    fun evaluate(
        model: GazeModel,
        features: List<DoubleArray>,
        targets: List<Pair<Float, Float>>,
        screenW: Int,
        screenH: Int,
    ): CalibrationReport {
        if (features.isEmpty() || features.size != targets.size) {
            return CalibrationReport(
                Double.NaN, emptyList(),
                PointError(-1, 0f to 0f, Double.NaN, 0),
            )
        }
        val groups = groupByPoint(targets)
        val perPoint = groups.mapIndexed { idx, indices ->
            PointError(
                idx,
                targets[indices.first()],
                meanError(model, features, targets, indices, screenW, screenH),
                indices.size,
            )
        }
        val overall = meanError(model, features, targets, targets.indices.toList(), screenW, screenH)
        val worst = perPoint.maxByOrNull { it.meanErrorPx }
            ?: PointError(-1, 0f to 0f, Double.NaN, 0)
        return CalibrationReport(overall, perPoint, worst)
    }

    /**
     * 留一校正點交叉驗證：回傳所有被留出樣本的平均預測誤差（像素）。
     * 校正點少於 2 個時無法交叉驗證，回傳 NaN。
     */
    fun leaveOnePointOut(
        features: List<DoubleArray>,
        targets: List<Pair<Float, Float>>,
        screenW: Int,
        screenH: Int,
        lambda: Double = 5e-4,
    ): Double {
        if (features.isEmpty() || features.size != targets.size) return Double.NaN
        val groups = groupByPoint(targets)
        if (groups.size < 2) return Double.NaN
        var errSum = 0.0
        var count = 0
        for (heldOut in groups) {
            val heldSet = heldOut.toSet()
            val trainIdx = targets.indices.filter { it !in heldSet }
            val subModel = GazeModel.train(
                trainIdx.map { features[it] },
                trainIdx.map { targets[it] },
                lambda,
                screenW.toDouble(),
                screenH.toDouble(),
            )
            errSum += meanError(subModel, features, targets, heldOut, screenW, screenH) * heldOut.size
            count += heldOut.size
        }
        return errSum / count
    }

    /**
     * 留一輪交叉驗證：回傳所有被留出樣本的平均預測誤差（像素）。
     * 每輪 = 多光源校正的一次 9 點採樣（passIds 相同者為同一輪）。
     * 留出一整輪、用其餘輪重新訓練，再預測被留出的輪；被評估的輪
     * 完全沒參與訓練，是對「沒見過的光線條件下預測得準不準」的誠實估計。
     * 輪次少於 2 個、資料為空或長度不符時回傳 NaN。
     * lightLogLux 與 leaveOnePointOut 的 train 呼叫相同：傳入時子模型的光跨度
     * 由被留輪以外的樣本算出；不傳時由特徵 f[22] 還原。
     */
    fun leaveOnePassOut(
        features: List<DoubleArray>,
        targets: List<Pair<Float, Float>>,
        passIds: List<Int>,
        screenW: Int,
        screenH: Int,
        lambda: Double = 5e-4,
        lightLogLux: List<Double>? = null,
    ): Double {
        if (features.isEmpty() || features.size != targets.size ||
            passIds.size != features.size
        ) return Double.NaN
        val passes = passIds.toSet()
        if (passes.size < 2) return Double.NaN
        var errSum = 0.0
        var count = 0
        for (heldPass in passes) {
            val heldIdx = targets.indices.filter { passIds[it] == heldPass }
            val trainIdx = targets.indices.filter { passIds[it] != heldPass }
            val subModel = GazeModel.train(
                trainIdx.map { features[it] },
                trainIdx.map { targets[it] },
                lambda,
                screenW.toDouble(),
                screenH.toDouble(),
                lightLogLux = lightLogLux?.let { logs -> trainIdx.map { logs[it] } },
            )
            errSum += meanError(subModel, features, targets, heldIdx, screenW, screenH) * heldIdx.size
            count += heldIdx.size
        }
        return errSum / count
    }

    /** 依目標座標分組（同一座標 = 同一校正點），保留首次出現順序。 */
    private fun groupByPoint(targets: List<Pair<Float, Float>>): List<List<Int>> {
        val order = LinkedHashMap<Pair<Float, Float>, MutableList<Int>>()
        for (i in targets.indices) {
            order.getOrPut(targets[i]) { mutableListOf() }.add(i)
        }
        return order.values.toList()
    }

    private fun meanError(
        model: GazeModel,
        features: List<DoubleArray>,
        targets: List<Pair<Float, Float>>,
        indices: List<Int>,
        screenW: Int,
        screenH: Int,
    ): Double {
        if (indices.isEmpty()) return Double.NaN
        var sum = 0.0
        for (i in indices) {
            val (px, py) = model.predict(features[i], screenW, screenH)
            sum += hypot(px - targets[i].first, py - targets[i].second)
        }
        return sum / indices.size
    }
}
