/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2-3 重啟驗證守衛測試（TEST_PLAN §2.3，RecoveryGateTest）。
 *
 * 覆蓋驗證三項事實的 2×2×2 組合：任一失敗 → 拒絕恢復（Deny，含原因）；
 * 全過 → 允許恢復（Allow）。不可測部分（實際 startEngine 呼叫、
 * loadModel 的 JSON 解析）走實機 R-6，不得冒充。
 */
class RecoveryGateTest {

    // 全過 → 允許恢復。
    @Test
    fun allPass_allowsRecovery() {
        val decision = RecoveryGate.evaluate(
            RecoveryGateInput(serviceAlive = true, engineRunning = true, modelValid = true),
        )
        assertEquals(RecoveryGateDecision.Allow, decision)
    }

    // (a) 服務實例不在 → 拒絕，原因指明服務實例。
    @Test
    fun serviceDead_deniesWithServiceReason() {
        val decision = RecoveryGate.evaluate(
            RecoveryGateInput(serviceAlive = false, engineRunning = true, modelValid = true),
        )
        assertTrue(decision is RecoveryGateDecision.Deny)
        assertTrue((decision as RecoveryGateDecision.Deny).reason.contains("服務實例"))
    }

    // (b) 引擎未跑 → 拒絕，原因指明引擎（呼叫端補 startEngine 後須重驗）。
    @Test
    fun engineStopped_deniesWithEngineReason() {
        val decision = RecoveryGate.evaluate(
            RecoveryGateInput(serviceAlive = true, engineRunning = false, modelValid = true),
        )
        assertTrue(decision is RecoveryGateDecision.Deny)
        assertTrue((decision as RecoveryGateDecision.Deny).reason.contains("引擎"))
    }

    // (c) 模型無效 → 拒絕，原因指明校正模型。
    @Test
    fun modelInvalid_deniesWithModelReason() {
        val decision = RecoveryGate.evaluate(
            RecoveryGateInput(serviceAlive = true, engineRunning = true, modelValid = false),
        )
        assertTrue(decision is RecoveryGateDecision.Deny)
        assertTrue((decision as RecoveryGateDecision.Deny).reason.contains("校正模型"))
    }

    // 2×2×2 窮舉：除全過外全部拒絕，且原因列出每一項未過的檢查、
    // 不含已通過的檢查；全過只允許。
    @Test
    fun allEightCombinations_onlyAllPassAllows() {
        for (serviceAlive in listOf(false, true)) {
            for (engineRunning in listOf(false, true)) {
                for (modelValid in listOf(false, true)) {
                    val input = RecoveryGateInput(serviceAlive, engineRunning, modelValid)
                    val decision = RecoveryGate.evaluate(input)
                    if (serviceAlive && engineRunning && modelValid) {
                        assertEquals(
                            "expected Allow: $input",
                            RecoveryGateDecision.Allow,
                            decision,
                        )
                    } else {
                        assertTrue("expected Deny: $input", decision is RecoveryGateDecision.Deny)
                        val reason = (decision as RecoveryGateDecision.Deny).reason
                        assertTrue("reason must not be empty: $input", reason.isNotEmpty())
                        if (!serviceAlive) assertTrue(reason.contains("服務實例"))
                        if (!engineRunning) assertTrue(reason.contains("引擎"))
                        if (!modelValid) assertTrue(reason.contains("校正模型"))
                        if (serviceAlive) assertTrue(!reason.contains("服務實例"))
                        if (engineRunning) assertTrue(!reason.contains("引擎"))
                        if (modelValid) assertTrue(!reason.contains("校正模型"))
                    }
                }
            }
        }
    }

    // 多項同時失敗：原因同時列出全部未過項（呼叫端寫入 ERROR 時原因完整）。
    @Test
    fun multipleFailures_reasonListsAllOfThem() {
        val decision = RecoveryGate.evaluate(
            RecoveryGateInput(serviceAlive = false, engineRunning = false, modelValid = false),
        )
        assertTrue(decision is RecoveryGateDecision.Deny)
        val reason = (decision as RecoveryGateDecision.Deny).reason
        assertTrue(reason.contains("服務實例"))
        assertTrue(reason.contains("引擎"))
        assertTrue(reason.contains("校正模型"))
    }
}
