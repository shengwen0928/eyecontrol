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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校正品質評估純邏輯測試（STEP 4）：GazeEvaluator 的分組、空輸入與
 * 留一交叉驗證。合成資料沿用 GazeModelMathTest 的同一線性關係與
 * 25 維建構方式（f[0]=u、f[1]=v、f[6]=iod、f[21]=偏置、f[22..24]=光特徵），不改模型
 * 演算法、不發明門檻；等級門檻 60／120／200 px 不在本測試判定範圍。
 * 留一輪測試所用資料皆為合成資料（標示 synthetic），只驗數學契約。
 */
class GazeEvaluatorTest {

    private fun featureVector(u: Double = 0.0, v: Double = 0.0): DoubleArray {
        val f = DoubleArray(GazeFeatureExtractor.DIM)
        f[0] = u
        f[1] = v
        f[6] = 0.1
        f[21] = 1.0
        return f
    }

    private fun gridData(): Pair<List<DoubleArray>, List<Pair<Float, Float>>> {
        val features = mutableListOf<DoubleArray>()
        val targets = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until 100) {
            val u = ((i * 37) % 10 - 5) / 10.0 + 0.05
            val v = ((i * 53) % 10 - 5) / 10.0 - 0.03
            features.add(featureVector(u, v))
            targets.add((540.0 + 800.0 * u).toFloat() to (1200.0 + 800.0 * v).toFloat())
        }
        return features to targets
    }

    @Test
    fun evaluate_empty_returnsNaNReport() {
        val model = GazeModel.train(
            listOf(featureVector()), listOf(0f to 0f),
            screenW = 1080.0, screenH = 2400.0,
        )
        val report = GazeEvaluator.evaluate(model, emptyList(), emptyList(), 1080, 2400)
        assertTrue(report.inSampleMeanErrorPx.isNaN())
        assertTrue(report.perPoint.isEmpty())
        assertEquals(-1, report.worstPoint.pointIndex)
    }

    @Test
    fun evaluate_mismatchedSizes_returnsNaNReport() {
        val model = GazeModel.train(
            listOf(featureVector()), listOf(0f to 0f),
            screenW = 1080.0, screenH = 2400.0,
        )
        val report = GazeEvaluator.evaluate(
            model, listOf(featureVector()), listOf(0f to 0f, 1f to 1f), 1080, 2400,
        )
        assertTrue(report.inSampleMeanErrorPx.isNaN())
        assertTrue(report.perPoint.isEmpty())
    }

    @Test
    fun evaluate_trainedGrid_finiteErrorAndFullCounts() {
        val (features, targets) = gridData()
        val model = GazeModel.train(features, targets, screenW = 1080.0, screenH = 2400.0)
        val report = GazeEvaluator.evaluate(model, features, targets, 1080, 2400)
        assertTrue(report.inSampleMeanErrorPx.isFinite())
        assertTrue(report.inSampleMeanErrorPx < 50.0)
        assertEquals(100, report.perPoint.sumOf { it.sampleCount })
        assertTrue(report.worstPoint.meanErrorPx.isFinite())
        assertEquals(
            report.perPoint.maxOf { it.meanErrorPx },
            report.worstPoint.meanErrorPx,
            0.0,
        )
    }

    @Test
    fun evaluate_groupsByTarget_inFirstAppearanceOrder() {
        val targetA = 100f to 200f
        val targetB = 900f to 2000f
        val features = listOf(
            featureVector(-0.4, -0.4), featureVector(-0.2, -0.4), featureVector(0.0, -0.4),
            featureVector(0.2, 0.4), featureVector(0.4, 0.4), featureVector(0.0, 0.4),
        )
        val targets = listOf(targetA, targetA, targetA, targetB, targetB, targetB)
        val model = GazeModel.train(features, targets, screenW = 1080.0, screenH = 2400.0)
        val report = GazeEvaluator.evaluate(model, features, targets, 1080, 2400)
        assertEquals(2, report.perPoint.size)
        assertEquals(listOf(0, 1), report.perPoint.map { it.pointIndex })
        assertEquals(listOf(targetA, targetB), report.perPoint.map { it.target })
        assertEquals(listOf(3, 3), report.perPoint.map { it.sampleCount })
    }

    @Test
    fun leaveOnePointOut_singleGroup_returnsNaN() {
        val features = listOf(featureVector(0.0, 0.0), featureVector(0.1, 0.0))
        val targets = listOf(100f to 200f, 100f to 200f)
        assertTrue(GazeEvaluator.leaveOnePointOut(features, targets, 1080, 2400).isNaN())
    }

    @Test
    fun leaveOnePointOut_emptyOrMismatched_returnsNaN() {
        assertTrue(GazeEvaluator.leaveOnePointOut(emptyList(), emptyList(), 1080, 2400).isNaN())
        assertTrue(
            GazeEvaluator.leaveOnePointOut(
                listOf(featureVector()), listOf(0f to 0f, 1f to 1f), 1080, 2400,
            ).isNaN(),
        )
    }

    // 留一交叉驗證是「誠實估計」：被留出的點完全沒參與訓練，誤差
    // 應有限且比訓練集內誤差更悲觀（本測試只斷言此相對關係與有限性，
    // 不發明任何 px 門檻；合成網格實測值見 fix log）。
    @Test
    fun leaveOnePointOut_trainedGrid_finiteAndMorePessimisticThanInSample() {
        val (features, targets) = gridData()
        val model = GazeModel.train(features, targets, screenW = 1080.0, screenH = 2400.0)
        val inSample = GazeEvaluator.evaluate(model, features, targets, 1080, 2400)
            .inSampleMeanErrorPx
        val err = GazeEvaluator.leaveOnePointOut(features, targets, 1080, 2400)
        assertTrue(err.isFinite())
        assertTrue(inSample.isFinite())
        assertTrue(err > inSample)
    }

    // ---- 留一輪交叉驗證（多光源校正）：以下皆為合成資料，只驗數學契約 ----

    private fun twoPassData(): Triple<List<DoubleArray>, List<Pair<Float, Float>>, List<Int>> {
        // 兩輪：每輪 9 點（3×3 目標網格），光線相同（c=0），目標為同一線性關係。
        val features = mutableListOf<DoubleArray>()
        val targets = mutableListOf<Pair<Float, Float>>()
        val passIds = mutableListOf<Int>()
        for (pass in 0..1) {
            for (i in 0 until 9) {
                val u = (i % 3 - 1) / 2.0
                val v = (i / 3 - 1) / 2.0
                val f = featureVector(u, v)
                f[22] = 0.0
                f[23] = 0.0
                f[24] = 0.0
                features.add(f)
                targets.add((540.0 + 800.0 * u).toFloat() to (1200.0 + 800.0 * v).toFloat())
                passIds.add(pass)
            }
        }
        return Triple(features, targets, passIds)
    }

    @Test
    fun leaveOnePassOut_singlePass_returnsNaN() {
        val (features, targets, _) = twoPassData()
        val singlePass = List(9) { 0 }
        assertTrue(
            GazeEvaluator.leaveOnePassOut(
                features.take(9), targets.take(9), singlePass, 1080, 2400,
            ).isNaN(),
        )
    }

    @Test
    fun leaveOnePassOut_emptyOrMismatched_returnsNaN() {
        assertTrue(
            GazeEvaluator.leaveOnePassOut(emptyList(), emptyList(), emptyList(), 1080, 2400)
                .isNaN(),
        )
        val (features, targets, passIds) = twoPassData()
        assertTrue(
            GazeEvaluator.leaveOnePassOut(features, targets, passIds.drop(1), 1080, 2400)
                .isNaN(),
        )
    }

    @Test
    fun leaveOnePassOut_twoPassesSameLight_finite() {
        val (features, targets, passIds) = twoPassData()
        val err = GazeEvaluator.leaveOnePassOut(features, targets, passIds, 1080, 2400)
        assertTrue(err.isFinite())
        assertTrue(err >= 0.0)
    }

    @Test
    fun leaveOnePassOut_unseenLight_morePessimisticThanInSample_synthetic() {
        // 合成資料：目標依賴光線 c；兩輪光線不同（c=-1 與 c=+1）。
        // 留一輪時被留出的光線完全沒參與訓練，誤差應比訓練集內誤差更悲觀
        // （相對關係斷言，不發明 px 門檻）。
        val features = mutableListOf<DoubleArray>()
        val targets = mutableListOf<Pair<Float, Float>>()
        val passIds = mutableListOf<Int>()
        val luxLogs = mutableListOf<Double>()
        for ((pass, c) in listOf(0 to -1.0, 1 to 1.0)) {
            for (i in 0 until 9) {
                val u = (i % 3 - 1) / 2.0
                val v = (i / 3 - 1) / 2.0
                val f = featureVector(u, v)
                f[13] = u
                f[14] = v
                f[22] = c
                f[23] = c * u
                f[24] = c * v
                features.add(f)
                targets.add(
                    (540.0 + 800.0 * u + 150.0 * c).toFloat() to
                        (1200.0 + 800.0 * v).toFloat(),
                )
                passIds.add(pass)
                luxLogs.add(c + 2.0)
            }
        }
        val model = GazeModel.train(
            features, targets, screenW = 1080.0, screenH = 2400.0,
            lightLogLux = luxLogs,
        )
        val inSample = GazeEvaluator.evaluate(model, features, targets, 1080, 2400)
            .inSampleMeanErrorPx
        val err = GazeEvaluator.leaveOnePassOut(
            features, targets, passIds, 1080, 2400, lightLogLux = luxLogs,
        )
        assertTrue(err.isFinite())
        assertTrue(inSample.isFinite())
        assertTrue(err > inSample)
    }
}
