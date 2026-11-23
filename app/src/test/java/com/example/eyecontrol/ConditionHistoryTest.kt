package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回歸測試：只測 `ConditionHistory` 的純函式
 * `interpolate`／`hasSignificantChange`／`conditionDistance`／`luxBucket`
 *（TEST_PLAN §2.4）。不碰 prefs／JSON：不呼叫任何讀寫與 `toJson`／`fromJson`。
 * 門檻引用受測類別的公開常數，不在測試內重寫數字。
 */
class ConditionHistoryTest {

    private fun condition(
        luxBucket: Int = 1,
        pitch: Double = 0.0,
        roll: Double = 0.0,
        iod: Double = Double.NaN,
        glassesHint: Double = Double.NaN,
    ): ConditionHistory.Condition =
        ConditionHistory.Condition(luxBucket, pitch, roll, iod, glassesHint)

    // ---- luxBucket 邊界（30／200／1500） ----

    @Test
    fun luxBucket_boundaries() {
        assertEquals(0, ConditionHistory.luxBucket(0f))
        assertEquals(0, ConditionHistory.luxBucket(29.9f))
        assertEquals(1, ConditionHistory.luxBucket(30f))
        assertEquals(1, ConditionHistory.luxBucket(199.9f))
        assertEquals(2, ConditionHistory.luxBucket(200f))
        assertEquals(2, ConditionHistory.luxBucket(1499.9f))
        assertEquals(3, ConditionHistory.luxBucket(1500f))
        assertEquals(3, ConditionHistory.luxBucket(10000f))
    }

    @Test
    fun luxBucket_unknown_returnsMinusOne() {
        assertEquals(-1, ConditionHistory.luxBucket(Float.NaN))
        assertEquals(-1, ConditionHistory.luxBucket(-5f))
        assertEquals(-1, ConditionHistory.luxBucket(Float.POSITIVE_INFINITY))
    }

    // ---- hasSignificantChange 各分量 ----

    @Test
    fun hasSignificantChange_luxBucketChange() {
        assertTrue(
            ConditionHistory.hasSignificantChange(condition(luxBucket = 1), condition(luxBucket = 2)),
        )
    }

    @Test
    fun hasSignificantChange_noChange_returnsFalse() {
        assertTrue(
            !ConditionHistory.hasSignificantChange(condition(), condition()),
        )
    }

    @Test
    fun hasSignificantChange_pitchAtThreshold() {
        val threshold = ConditionHistory.PITCH_ROLL_CHANGE_RAD
        assertTrue(
            ConditionHistory.hasSignificantChange(
                condition(pitch = 0.0),
                condition(pitch = threshold),
            ),
        )
        assertTrue(
            !ConditionHistory.hasSignificantChange(
                condition(pitch = 0.0),
                condition(pitch = threshold - 0.01),
            ),
        )
    }

    @Test
    fun hasSignificantChange_rollAtThreshold() {
        val threshold = ConditionHistory.PITCH_ROLL_CHANGE_RAD
        assertTrue(
            ConditionHistory.hasSignificantChange(
                condition(roll = 0.0),
                condition(roll = -threshold),
            ),
        )
    }

    @Test
    fun hasSignificantChange_iodRatioAtThreshold() {
        val ratio = ConditionHistory.IOD_CHANGE_RATIO
        // 浮點邊界不以恰等斷言：以門檻上下各留邊距驗方向（恰等值受二進位捨入影響）。
        assertTrue(
            ConditionHistory.hasSignificantChange(
                condition(iod = 0.10),
                condition(iod = 0.10 * (1.0 + ratio + 0.01)),
            ),
        )
        assertTrue(
            !ConditionHistory.hasSignificantChange(
                condition(iod = 0.10),
                condition(iod = 0.10 * (1.0 + ratio - 0.02)),
            ),
        )
    }

    @Test
    fun hasSignificantChange_glassesHintAtThreshold() {
        val threshold = ConditionHistory.GLASSES_HINT_CHANGE
        // 浮點邊界不以恰等斷言：以門檻上下各留邊距驗方向（恰等值受二進位捨入影響）。
        assertTrue(
            ConditionHistory.hasSignificantChange(
                condition(glassesHint = 0.1),
                condition(glassesHint = 0.1 + threshold + 0.01),
            ),
        )
        assertTrue(
            !ConditionHistory.hasSignificantChange(
                condition(glassesHint = 0.1),
                condition(glassesHint = 0.1 + threshold - 0.02),
            ),
        )
    }

    @Test
    fun hasSignificantChange_unknownComponentsAreIgnored() {
        // pitch 未知（NaN）時，即使差值再大也不參與判定；亮度桶皆未知亦同。
        assertTrue(
            !ConditionHistory.hasSignificantChange(
                condition(luxBucket = -1, pitch = Double.NaN),
                condition(luxBucket = -1, pitch = 1.0),
            ),
        )
    }

    // ---- conditionDistance ----

    @Test
    fun conditionDistance_identical_isZero() {
        assertEquals(0.0, ConditionHistory.conditionDistance(condition(), condition()), 0.0)
    }

    @Test
    fun conditionDistance_luxBucketDifference_addsOne() {
        assertEquals(
            1.0,
            ConditionHistory.conditionDistance(
                condition(luxBucket = 1),
                condition(luxBucket = 2),
            ),
            1e-9,
        )
    }

    @Test
    fun conditionDistance_pitchThreshold_addsOne() {
        assertEquals(
            1.0,
            ConditionHistory.conditionDistance(
                condition(pitch = 0.0),
                condition(pitch = ConditionHistory.PITCH_ROLL_CHANGE_RAD),
            ),
            1e-9,
        )
    }

    @Test
    fun conditionDistance_componentsSum() {
        // 亮度換桶 1.0 ＋ pitch 一個閾值 1.0 ＋ roll 半個閾值 0.5。
        assertEquals(
            2.5,
            ConditionHistory.conditionDistance(
                condition(luxBucket = 1, pitch = 0.0, roll = 0.0),
                condition(
                    luxBucket = 2,
                    pitch = ConditionHistory.PITCH_ROLL_CHANGE_RAD,
                    roll = ConditionHistory.PITCH_ROLL_CHANGE_RAD / 2.0,
                ),
            ),
            1e-9,
        )
    }

    @Test
    fun conditionDistance_unknownComponentsAreIgnored() {
        assertEquals(
            0.0,
            ConditionHistory.conditionDistance(
                condition(pitch = Double.NaN),
                condition(pitch = 1.0),
            ),
            0.0,
        )
    }

    // ---- interpolate ----

    private fun record(
        cond: ConditionHistory.Condition,
        offsetX: Float,
        offsetY: Float,
    ): ConditionHistory.Record =
        ConditionHistory.Record(cond, offsetX, offsetY, 0L)

    @Test
    fun interpolate_empty_returnsNull() {
        assertNull(ConditionHistory.interpolate(emptyList(), condition()))
    }

    @Test
    fun interpolate_noNeighbor_returnsNull() {
        val far = record(
            condition(pitch = ConditionHistory.PITCH_ROLL_CHANGE_RAD * 2.0),
            100f,
            100f,
        )
        assertNull(ConditionHistory.interpolate(listOf(far), condition()))
    }

    @Test
    fun interpolate_atNeighborBoundary_returnsNull() {
        // 距離恰為 NEIGHBOR_MAX_DISTANCE（1.0）時不算鄰近（條件為 < 上限）。
        val boundary = record(
            condition(pitch = ConditionHistory.PITCH_ROLL_CHANGE_RAD),
            100f,
            100f,
        )
        assertNull(ConditionHistory.interpolate(listOf(boundary), condition()))
    }

    @Test
    fun interpolate_exactMatch_returnsRecordOffset() {
        val near = record(condition(), 40f, -30f)
        val result = ConditionHistory.interpolate(listOf(near), condition())
        assertEquals(40f, result?.first ?: Float.NaN, 0.001f)
        assertEquals(-30f, result?.second ?: Float.NaN, 0.001f)
    }

    @Test
    fun interpolate_offsetsClampedToMaxOffset() {
        val big = record(condition(), 1000f, -1000f)
        val result = ConditionHistory.interpolate(listOf(big), condition())
        assertEquals(ConditionHistory.MAX_OFFSET_PX, result?.first ?: Float.NaN, 0.001f)
        assertEquals(-ConditionHistory.MAX_OFFSET_PX, result?.second ?: Float.NaN, 0.001f)
    }

    @Test
    fun interpolate_nearerRecordDominatesWeight() {
        val near = record(condition(pitch = 0.0), 100f, 0f)
        val farther = record(
            condition(pitch = ConditionHistory.PITCH_ROLL_CHANGE_RAD / 2.0),
            0f,
            0f,
        )
        val result = ConditionHistory.interpolate(listOf(near, farther), condition())
        // 權重 1/(d+eps)：近者權重遠大於遠者，加權結果應偏向近者（>50）。
        assertTrue((result?.first ?: 0f) > 50f)
        assertTrue((result?.first ?: 0f) < 100f)
    }

    @Test
    fun interpolate_weightedAverage_matchesIdwFormula() {
        val near = record(condition(pitch = 0.0), 100f, 0f)
        val farther = record(
            condition(pitch = ConditionHistory.PITCH_ROLL_CHANGE_RAD / 2.0),
            0f,
            0f,
        )
        val eps = ConditionHistory.IDW_EPSILON
        val wNear = 1.0 / (0.0 + eps)
        val wFar = 1.0 / (0.5 + eps)
        val expected = (wNear * 100.0 / (wNear + wFar)).toFloat()
        val result = ConditionHistory.interpolate(listOf(near, farther), condition())
        assertEquals(expected, result?.first ?: Float.NaN, 1f)
    }

    @Test
    fun interpolate_atMostMaxNeighborsAreUsed() {
        // 6 筆同條件紀錄，前 5 筆偏移 0、第 6 筆 1000：只取最近 5 筆時結果為 0。
        val records = List(5) { record(condition(), 0f, 0f) } +
            record(condition(), 1000f, 0f)
        val result = ConditionHistory.interpolate(records, condition())
        assertEquals(0f, result?.first ?: Float.NaN, 0.001f)
    }
}
