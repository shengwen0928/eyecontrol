package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 回歸測試：只測 `GazeModel.train`／`predict` 的數學（TEST_PLAN §2.4）。
 *
 * 不碰 JSON 往返（`org.json` 在純 JVM 測試中為 stub）。測試餵入的向量一律
 * 以 [GazeFeatureExtractor.DIM] 常數建構為 25 維，不得簡化維度。
 * 光特徵測試所用資料皆為合成資料（標示 synthetic），只驗數學契約，
 * 不宣稱任何實機精度。
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
    fun dimContract_isTwentyFive() {
        assertEquals(25, GazeFeatureExtractor.DIM)
        assertEquals(22, GazeFeatureExtractor.LIGHT_FEATURE_START)
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

    @Test
    fun predict_headPoseCompensationOff_returnsRidgeOnlyOutput() {
        val wx = DoubleArray(GazeFeatureExtractor.DIM)
        wx[0] = 800.0
        wx[21] = 540.0
        val wy = DoubleArray(GazeFeatureExtractor.DIM)
        wy[1] = 800.0
        wy[21] = 1200.0
        val model = GazeModel(wx, wy, basePitch = 0.0, baseYaw = 0.0, kx = 1.6, ky = 1.3)
        val f = featureVector(0.1, 0.2)
        f[7] = 0.15
        f[8] = -0.1
        val (xOff, yOff) = model.predict(
            f, headPoseCompensation = false,
            gyroCompensation = false, distanceCompensation = false,
        )
        assertEquals(540.0 + 800.0 * 0.1, xOff, 1e-6)
        assertEquals(1200.0 + 800.0 * 0.2, yOff, 1e-6)
        val (xOn, yOn) = model.predict(
            f, headPoseCompensation = true,
            gyroCompensation = false, distanceCompensation = false,
        )
        assertEquals(xOff + -0.1 * 1080 * 1.6, xOn, 1e-6)
        assertEquals(yOff - 0.15 * 2400 * 1.3, yOn, 1e-6)
        // 預設值維持補償開啟：現有呼叫端行為不變。
        val (xDef, yDef) = model.predict(
            f, gyroCompensation = false, distanceCompensation = false,
        )
        assertEquals(xOn, xDef, 0.0)
        assertEquals(yOn, yDef, 0.0)
    }

    // ---- 連續光特徵（v3 契約）：以下皆為合成資料，只驗數學契約 ----

    private fun lightFeatureVector(
        u: Double = 0.0,
        v: Double = 0.0,
        lightCentered: Double = 0.0,
    ): DoubleArray {
        val f = featureVector(u, v)
        f[13] = u
        f[14] = v
        f[22] = lightCentered
        f[23] = lightCentered * u
        f[24] = lightCentered * v
        return f
    }

    @Test
    fun train_lightSpan_recordedFromParallelLuxLogs() {
        val features = listOf(
            lightFeatureVector(0.1, 0.2, -1.0),
            lightFeatureVector(-0.1, 0.1, 1.0),
        )
        val model = GazeModel.train(
            features, listOf(100f to 200f, 200f to 300f),
            screenW = 1080.0, screenH = 2400.0,
            lightLogLux = listOf(1.0, 3.0),
        )
        assertEquals(1.0, model.lightMinLog, 0.0)
        assertEquals(3.0, model.lightMaxLog, 0.0)
    }

    @Test
    fun train_lightSpan_fallbackFromFeatureColumn() {
        // 不傳平行 lux 時由 f[22] 還原：f[22] = logLux − 2.0。
        val features = listOf(
            lightFeatureVector(0.1, 0.2, -0.5),
            lightFeatureVector(-0.1, 0.1, 0.5),
        )
        val model = GazeModel.train(
            features, listOf(100f to 200f, 200f to 300f),
            screenW = 1080.0, screenH = 2400.0,
        )
        assertEquals(1.5, model.lightMinLog, 1e-9)
        assertEquals(2.5, model.lightMaxLog, 1e-9)
    }

    @Test
    fun train_lightSpan_allUnknownLux_degenerateSpan() {
        val features = listOf(lightFeatureVector(), lightFeatureVector(0.1, 0.1))
        val model = GazeModel.train(
            features, listOf(100f to 200f, 200f to 300f),
            screenW = 1080.0, screenH = 2400.0,
            lightLogLux = listOf(Double.NaN, Double.NaN),
        )
        // 全程無感測器資料：f[22] 皆 0 → 跨度退化為中心值單點，行為一致。
        assertEquals(2.0, model.lightMinLog, 0.0)
        assertEquals(2.0, model.lightMaxLog, 0.0)
    }

    @Test
    fun isCompatibleVersion_v3AndDimOnly() {
        assertTrue(GazeModel.isCompatibleVersion(3, 25))
        assertTrue(!GazeModel.isCompatibleVersion(2, 22)) // v2 舊模型：重校
        assertTrue(!GazeModel.isCompatibleVersion(3, 22))
        assertTrue(!GazeModel.isCompatibleVersion(2, 25))
        assertTrue(!GazeModel.isCompatibleVersion(0, 25))
    }

    @Test
    fun predict_logLux_clampedToTrainingSpan() {
        val wx = DoubleArray(GazeFeatureExtractor.DIM)
        wx[21] = 540.0
        wx[22] = 100.0
        val wy = DoubleArray(GazeFeatureExtractor.DIM)
        wy[21] = 1200.0
        wy[22] = -50.0
        val model = GazeModel(wx, wy, lightMinLog = 1.0, lightMaxLog = 3.0)
        val f = lightFeatureVector(0.0, 0.0, 0.0)
        // 範圍外取邊界效應：logLux 5.0 與跨度上限 3.0 同值；0.0 與下限 1.0 同值。
        val (xHi, yHi) = model.predict(
            f, logLux = 5.0, gyroCompensation = false, distanceCompensation = false,
        )
        val (xEdge, yEdge) = model.predict(
            f, logLux = 3.0, gyroCompensation = false, distanceCompensation = false,
        )
        assertEquals(xEdge, xHi, 0.0)
        assertEquals(yEdge, yHi, 0.0)
        val (xLo, yLo) = model.predict(
            f, logLux = 0.0, gyroCompensation = false, distanceCompensation = false,
        )
        val (xLoEdge, yLoEdge) = model.predict(
            f, logLux = 1.0, gyroCompensation = false, distanceCompensation = false,
        )
        assertEquals(xLoEdge, xLo, 0.0)
        assertEquals(yLoEdge, yLo, 0.0)
        // 邊界效應確實存在（不是把光特徵丟掉）：上限與下限預測不同。
        assertTrue(xHi != xLo)
    }

    @Test
    fun predict_logLuxNaN_usesStoredFeatures() {
        val wx = DoubleArray(GazeFeatureExtractor.DIM)
        wx[21] = 540.0
        wx[22] = 100.0
        val model = GazeModel(wx, DoubleArray(GazeFeatureExtractor.DIM))
        val f = lightFeatureVector(0.0, 0.0, 0.5)
        val (x, _) = model.predict(f, gyroCompensation = false, distanceCompensation = false)
        assertEquals(540.0 + 100.0 * 0.5, x, 1e-9)
    }

    @Test
    fun predict_doesNotMutateInputFeatures() {
        val wx = DoubleArray(GazeFeatureExtractor.DIM)
        wx[21] = 540.0
        wx[22] = 100.0
        val model = GazeModel(
            wx, DoubleArray(GazeFeatureExtractor.DIM),
            lightMinLog = 1.0, lightMaxLog = 3.0,
        )
        val f = lightFeatureVector(0.0, 0.0, 0.0)
        val before = f.copyOf()
        model.predict(
            f, logLux = 2.5, gyroCompensation = false, distanceCompensation = false,
        )
        assertTrue(f.contentEquals(before))
    }

    @Test
    fun train_predictRecoversLightDependentMapping_synthetic() {
        // 合成資料：目標同時依賴視線 (u, v) 與光線 c（c = logLux − 2）。
        val features = mutableListOf<DoubleArray>()
        val targets = mutableListOf<Pair<Float, Float>>()
        val luxLogs = mutableListOf<Double>()
        for (u in listOf(-0.4, -0.2, 0.0, 0.2, 0.4)) {
            for (v in listOf(-0.4, -0.2, 0.0, 0.2, 0.4)) {
                for (c in listOf(-1.0, -0.5, 0.0, 0.5, 1.0)) {
                    features.add(lightFeatureVector(u, v, c))
                    targets.add(
                        (540.0 + 800.0 * u + 150.0 * c).toFloat() to
                            (1200.0 + 800.0 * v - 100.0 * c).toFloat(),
                    )
                    luxLogs.add(c + 2.0)
                }
            }
        }
        val model = GazeModel.train(
            features, targets, screenW = 1080.0, screenH = 2400.0,
            lightLogLux = luxLogs,
        )
        assertEquals(1.0, model.lightMinLog, 1e-9)
        assertEquals(3.0, model.lightMaxLog, 1e-9)
        for (c in listOf(-1.0, 0.0, 1.0)) {
            val (x, y) = model.predict(lightFeatureVector(0.13, -0.21, c), logLux = c + 2.0)
            assertEquals(540.0 + 800.0 * 0.13 + 150.0 * c, x, 30.0)
            assertEquals(1200.0 + 800.0 * -0.21 - 100.0 * c, y, 30.0)
        }
    }
}
