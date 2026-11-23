/* SPDX-License-Identifier: AGPL-3.0-or-later */

package com.example.eyecontrol

/**
 * 校正有效性檢查（DCL-01「品質門檻才存」）：眼睛沒有跟著目標移動時拒絕存檔。
 *
 * 背景（2026-11-23 使用者實機觀察）：全程只看正中間，九點校正也能「通過」——
 * 存檔是無條件的，等級只顯示不擋存檔；點內離群值剔除只看同一點內的樣本是否
 * 聚集，不檢查眼睛是否跟著目標移動。九點固定順序採樣，時間漂移也可能被模型
 * 當成視線訊號硬湊。
 *
 * 檢查統計量：以虹膜特徵前 4 維（f[0..3]）為準，算「組間分離度／組內雜訊」。
 * 九個目標各自的樣本中心（centroid）彼此離得遠、各自內部聚得緊，代表眼睛
 * 真的有跟著目標移動；全部看中間時九個中心擠在一起，比值趨近 1。只用留用
 * 進訓練集的樣本（與模型吃的同一批資料），被剔除的樣本不計。
 *
 * 門檻 [SEPARATION_RATIO_MIN] 為暫定值：以第一原理定（真實注視的組間移動
 * 應為注視抖動的數倍；看中間時比值約 1），必須以校正逐筆紀錄（calib- 上傳，
 * 含 refused 與 separationRatio 欄位）的真實數據複核、調整——見
 * docs/calibration-gate.md。門檻擋的是「退化資料」，不是「誤差大的真實校正」：
 * 留一法誤差再大，只要眼睛真的跟著目標跑，就放行（誤差由報告頁的等級明說）。
 *
 * 純 Kotlin，不碰 Android API，可走 harness 測試。
 */
object CalibrationGate {

    /** 分離度門檻（暫定，待 calib- 真實數據複核）：低於此值視為退化資料、拒存。 */
    const val SEPARATION_RATIO_MIN = 2.0

    /** 參與檢查的特徵維度：虹膜相對位置 f[0..3]。 */
    const val IRIS_DIM = 4

    data class Result(
        /** 是否通過（組數 >= 2 且分離度 >= 門檻）。 */
        val passed: Boolean,
        /** 組間分離度／組內雜訊；無有效組時為 NaN。 */
        val separationRatio: Double,
        /** 組間分離度（中心點間平均距離）；無有效組時為 NaN。 */
        val separation: Double,
        /** 組內雜訊（樣本到各自中心的平均距離）；無有效組時為 NaN。 */
        val noise: Double,
        /** 有效目標組數。 */
        val groupCount: Int,
    )

    /**
     * 評估留用樣本的目標間分離度。[features] 與 [targets] 一一對應。
     * 非有限特徵的樣本直接排除；有效組少於 2 組時不通過（無法證明眼睛移動過）。
     */
    fun evaluate(features: List<DoubleArray>, targets: List<Pair<Float, Float>>): Result {
        val groups = linkedMapOf<Pair<Float, Float>, MutableList<DoubleArray>>()
        for ((f, t) in features.zip(targets)) {
            if (f.size < IRIS_DIM) continue
            var finite = true
            for (i in 0 until IRIS_DIM) {
                if (!f[i].isFinite()) {
                    finite = false
                    break
                }
            }
            if (!finite) continue
            groups.getOrPut(t) { mutableListOf() }.add(f)
        }
        if (groups.size < 2) {
            return Result(
                passed = false,
                separationRatio = Double.NaN,
                separation = Double.NaN,
                noise = Double.NaN,
                groupCount = groups.size,
            )
        }
        val centroids = groups.values.map { samples ->
            DoubleArray(IRIS_DIM) { i -> samples.sumOf { it[i] } / samples.size }
        }
        var pairSum = 0.0
        var pairCount = 0
        for (a in centroids.indices) {
            for (b in a + 1 until centroids.size) {
                pairSum += distance(centroids[a], centroids[b])
                pairCount++
            }
        }
        val separation = pairSum / pairCount
        var noiseSum = 0.0
        var noiseCount = 0
        for ((samples, centroid) in groups.values.zip(centroids)) {
            for (f in samples) {
                noiseSum += distance(f, centroid, IRIS_DIM)
                noiseCount++
            }
        }
        val noise = noiseSum / noiseCount
        // 雜訊為 0 時（同一組樣本完全相同）比值以極大有限值計：中心有分開即通過。
        val ratio = separation / maxOf(noise, 1e-9)
        return Result(
            passed = ratio >= SEPARATION_RATIO_MIN,
            separationRatio = ratio,
            separation = separation,
            noise = noise,
            groupCount = groups.size,
        )
    }

    private fun distance(a: DoubleArray, b: DoubleArray, dim: Int = IRIS_DIM): Double {
        var sum = 0.0
        for (i in 0 until dim) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return kotlin.math.sqrt(sum)
    }
}
