package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 回歸測試：只測 `GazeModel.train`／`predict` 的數學（TEST_PLAN §2.4）。
 *
 * 不碰 JSON 往返（`org.json` 在純 JVM 測試中為 stub）。測試餵入的向量一律
 * 以 [GazeFeatureExtractor.DIM] 常數建構為 22 維，不得簡化維度。
 */
class GazeModelMathTest {

    private fun featureVector(
        u: Double = 0.0,
        v: Double = 0.0,
        iod: Double = 0.1,
    ): DoubleArray {
        val f = DoubleArray(GazeFeatureExtractor.DIM)
        f[0] = u
        f[1] = v
        f[6] = iod
        f[21] = 1.0 // 偏置項（特徵定義的最後一維）
        return f
    }

    private fun trainedModel(): GazeModel {
        val features = mutableListOf<DoubleArray>()
        val targets = mutableListOf<Pair<Float, Float>>()
        // 確定性網格：u、v 各取 10 檔，目標為同一線性關係。
        for (i in 0 until 100) {
            val u = ((i * 37) % 10 - 5) / 10.0 + 0.05
            val v = ((i * 53) % 10 - 5) / 10.0 - 0.03
            features.add(featureVector(u, v))
            targets.add((540.0 + 800.0 * u).toFloat() to (1200.0 + 800.0 * v).toFloat())
        }
        return GazeModel.train(features, targets, screenW = 1080.0, screenH = 2400.0)
    }

    @Test
    fun dimContract_isTwentyTwo() {
        assertEquals(22, GazeFeatureExtractor.DIM)
    }

    @Test
    fun train_learnedCompensationCoefficientsWithinClamp() {
        val model = trainedModel()
        assertTrue(model.kx in -3.0..3.0)
        assertTrue(model.ky in -3.0..3.0)
    }

    @Test
    fun train_predictRecoversLinearMapping() {
        val model = trainedModel()
        val (x, y) = model.predict(featureVector(0.13, -0.21))
        assertEquals(540.0 + 800.0 * 0.13, x, 20.0)
        assertEquals(1200.0 + 800.0 * -0.21, y, 20.0)
    }

    @Test
    fun train_predictMeanErrorOnGridIsSmall() {
        val model = trainedModel()
        var totalError = 0.0
        var count = 0
        for (u in listOf(-0.4, -0.2, 0.0, 0.2, 0.4)) {
            for (v in listOf(-0.4, -0.2, 0.0, 0.2, 0.4)) {
                val (x, y) = model.predict(featureVector(u, v))
                totalError += kotlin.math.abs(x - (540.0 + 800.0 * u)) +
                    kotlin.math.abs(y - (1200.0 + 800.0 * v))
                count++
            }
        }
        assertTrue(totalError / count < 20.0)
    }

    @Test
    fun predict_finiteInput_finiteOutput() {
        val model = trainedModel()
        val (x, y) = model.predict(featureVector(0.2, 0.1))
        assertTrue(x.isFinite())
        assertTrue(y.isFinite())
    }

    @Test
    fun predict_sameInput_deterministic() {
        val model = trainedModel()
        val first = model.predict(featureVector(0.2, -0.1))
        val second = model.predict(featureVector(0.2, -0.1))
        assertEquals(first, second)
    }

    @Test
    fun predict_zeroWeights_returnsZeroWithoutCompensation() {
        val zero = GazeModel(
            DoubleArray(GazeFeatureExtractor.DIM),
            DoubleArray(GazeFeatureExtractor.DIM),
        )
        val (x, y) = zero.predict(
            featureVector(0.0, 0.0),
            gyroCompensation = false,
            distanceCompensation = false,
        )
        assertEquals(0.0, x, 0.0)
        assertEquals(0.0, y, 0.0)
    }

    @Test
    fun predict_tooFewFeatures_rejected() {
        val model = trainedModel()
        try {
            model.predict(DoubleArray(8))
            fail("expected IllegalArgumentException for fewer than 9 features")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("9") == true)
        }
    }

    @Test
    fun correctForDeviceTilt_noDelta_returnsSamePoint() {
        val result = GazeModel.correctForDeviceTilt(
            640.0, 1400.0, 1080, 2400, 0.1, 0.0, 0.1, 0.0,
        )
        assertEquals(640.0 to 1400.0, result)
    }

    @Test
    fun correctForDeviceTilt_pitchDelta_scalesVerticalOffset() {
        val result = GazeModel.correctForDeviceTilt(
            640.0, 1400.0, 1080, 2400, 0.1, 0.0, 0.0, 0.0,
        )
        val expectedY = 1200.0 + 200.0 / kotlin.math.cos(0.1)
        assertEquals(640.0, result.first, 1e-9)
        assertEquals(expectedY, result.second, 1e-9)
    }
}
