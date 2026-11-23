package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1-1 流程狀態機轉換測試（TEST_PLAN §2.1，F-01…F-14）。
 *
 * 覆蓋 STATE_DESIGN §2.1 的全部合法轉換與全部非法轉換。非法轉換以可注入的
 * [FlowStateLogger] 替身驗證有記警告，不得以 Logcat 斷言（F-13）。
 */
class AppFlowStateTest {

    private class RecordingLogger : FlowStateLogger {
        val messages = mutableListOf<String>()
        override fun warn(message: String) {
            messages.add(message)
        }
    }

    private fun machine(
        initial: AppFlowState = AppFlowState.NOT_CALIBRATED,
        logger: RecordingLogger? = null,
    ): AppFlowStateMachine = AppFlowStateMachine(initial, logger)

    // F-01：NOT_CALIBRATED＋校正開始 → CALIBRATING。
    @Test
    fun f01_notCalibrated_startCalibration_goesToCalibrating() {
        val m = machine()
        assertTrue(m.transition(FlowEvent.StartCalibration))
        assertEquals(AppFlowState.CALIBRATING, m.state)
    }

    // F-02：CALIBRATING＋9 點完成且存檔成功 → CALIBRATED。
    @Test
    fun f02_calibrating_calibrationSucceeded_goesToCalibrated() {
        val m = machine(AppFlowState.CALIBRATING)
        assertTrue(m.transition(FlowEvent.CalibrationSucceeded))
        assertEquals(AppFlowState.CALIBRATED, m.state)
    }

    // F-03：CALIBRATING＋取消 → NOT_CALIBRATED。
    @Test
    fun f03_calibrating_cancel_goesToNotCalibrated() {
        val m = machine(AppFlowState.CALIBRATING)
        assertTrue(m.transition(FlowEvent.CalibrationCancelled))
        assertEquals(AppFlowState.NOT_CALIBRATED, m.state)
    }

    // F-04：CALIBRATED＋前往系統設定 → WAITING_ACCESSIBILITY。
    @Test
    fun f04_calibrated_goToSettings_goesToWaitingAccessibility() {
        val m = machine(AppFlowState.CALIBRATED)
        assertTrue(m.transition(FlowEvent.GoToAccessibilitySettings))
        assertEquals(AppFlowState.WAITING_ACCESSIBILITY, m.state)
    }

    // F-05：WAITING_ACCESSIBILITY＋服務已開且驗證通過 → SERVICE_RUNNING。
    @Test
    fun f05_waiting_serviceVerified_goesToServiceRunning() {
        val m = machine(AppFlowState.WAITING_ACCESSIBILITY)
        assertTrue(m.transition(FlowEvent.ServiceVerified))
        assertEquals(AppFlowState.SERVICE_RUNNING, m.state)
    }

    // F-06：WAITING_ACCESSIBILITY＋服務未開而返回 → 停留本態（合法自迴圈）。
    @Test
    fun f06_waiting_returnedWithoutService_staysWaiting() {
        val logger = RecordingLogger()
        val m = machine(AppFlowState.WAITING_ACCESSIBILITY, logger)
        assertTrue(m.transition(FlowEvent.ReturnedWithoutService))
        assertEquals(AppFlowState.WAITING_ACCESSIBILITY, m.state)
        assertTrue(logger.messages.isEmpty())
    }

    // F-07：SERVICE_RUNNING＋停止眼控 → RECOVERY_MODE。
    @Test
    fun f07_serviceRunning_stopEyeControl_goesToRecoveryMode() {
        val m = machine(AppFlowState.SERVICE_RUNNING)
        assertTrue(m.transition(FlowEvent.StopEyeControl))
        assertEquals(AppFlowState.RECOVERY_MODE, m.state)
    }

    // F-08：RECOVERY_MODE＋重啟驗證全過 → SERVICE_RUNNING。
    @Test
    fun f08_recoveryMode_restartVerified_goesToServiceRunning() {
        val m = machine(AppFlowState.RECOVERY_MODE)
        assertTrue(m.transition(FlowEvent.RestartVerified))
        assertEquals(AppFlowState.SERVICE_RUNNING, m.state)
    }

    // F-09：RECOVERY_MODE＋完全停止 → STOPPED。
    @Test
    fun f09_recoveryMode_stopCompletely_goesToStopped() {
        val m = machine(AppFlowState.RECOVERY_MODE)
        assertTrue(m.transition(FlowEvent.StopCompletely))
        assertEquals(AppFlowState.STOPPED, m.state)
    }

    // F-10：STOPPED＋開始眼控（閘門全過）→ SERVICE_RUNNING。
    @Test
    fun f10_stopped_startTracking_goesToServiceRunning() {
        val m = machine(AppFlowState.STOPPED)
        assertTrue(m.transition(FlowEvent.StartTracking))
        assertEquals(AppFlowState.SERVICE_RUNNING, m.state)
    }

    // F-11：任一態＋失敗 → ERROR（含原因、記住失敗前一態）。
    @Test
    fun f11_failureFromEveryState_goesToErrorWithReasonAndPrevious() {
        val states = listOf(
            AppFlowState.NOT_CALIBRATED,
            AppFlowState.CALIBRATING,
            AppFlowState.CALIBRATED,
            AppFlowState.WAITING_ACCESSIBILITY,
            AppFlowState.SERVICE_RUNNING,
            AppFlowState.RECOVERY_MODE,
            AppFlowState.STOPPED,
        )
        for (from in states) {
            val m = machine(from)
            assertTrue(m.transition(FlowEvent.Failure("engine failed")))
            assertEquals(AppFlowState.ERROR, m.state)
            assertEquals("engine failed", m.errorReason)
            assertEquals(from, m.stateBeforeError)
        }
    }

    // F-11 補：已在 ERROR 時再失敗，更新原因、不覆蓋失敗前一態。
    @Test
    fun f11_failureWhileInError_updatesReasonKeepsPrevious() {
        val m = machine(AppFlowState.SERVICE_RUNNING)
        assertTrue(m.transition(FlowEvent.Failure("first")))
        assertTrue(m.transition(FlowEvent.Failure("second")))
        assertEquals(AppFlowState.ERROR, m.state)
        assertEquals("second", m.errorReason)
        assertEquals(AppFlowState.SERVICE_RUNNING, m.stateBeforeError)
    }

    // F-12：ERROR＋重試 → 回失敗前一態（不得跳過中間態），且原因／前一態清掉。
    @Test
    fun f12_error_retry_returnsToStateBeforeError() {
        val m = machine(AppFlowState.SERVICE_RUNNING)
        assertTrue(m.transition(FlowEvent.StopEyeControl))
        assertEquals(AppFlowState.RECOVERY_MODE, m.state)
        assertTrue(m.transition(FlowEvent.Failure("service disabled")))
        assertTrue(m.transition(FlowEvent.Retry))
        assertEquals(AppFlowState.RECOVERY_MODE, m.state)
        assertNull(m.errorReason)
        assertNull(m.stateBeforeError)
    }

    @Test
    fun f12_error_retryFromCalibrating_returnsToCalibrating() {
        val m = machine(AppFlowState.CALIBRATING)
        assertTrue(m.transition(FlowEvent.Failure("camera interrupted")))
        assertTrue(m.transition(FlowEvent.Retry))
        assertEquals(AppFlowState.CALIBRATING, m.state)
    }

    @Test
    fun f12_error_retryWithNoPrevious_isRejected() {
        val logger = RecordingLogger()
        val m = machine(AppFlowState.ERROR, logger)
        assertFalse(m.transition(FlowEvent.Retry))
        assertEquals(AppFlowState.ERROR, m.state)
        assertEquals(1, logger.messages.size)
    }

    @Test
    fun f12_retryAfterRetry_isRejected() {
        val m = machine(AppFlowState.STOPPED)
        assertTrue(m.transition(FlowEvent.Failure("engine failed")))
        assertTrue(m.transition(FlowEvent.Retry))
        assertEquals(AppFlowState.STOPPED, m.state)
        assertFalse(m.transition(FlowEvent.Retry))
        assertEquals(AppFlowState.STOPPED, m.state)
    }

    @Test
    fun f12_secondFailureAfterRetry_recordsNewPrevious() {
        val m = machine(AppFlowState.SERVICE_RUNNING)
        assertTrue(m.transition(FlowEvent.Failure("first")))
        assertTrue(m.transition(FlowEvent.Retry))
        assertTrue(m.transition(FlowEvent.StopEyeControl))
        assertTrue(m.transition(FlowEvent.Failure("second")))
        assertEquals(AppFlowState.RECOVERY_MODE, m.stateBeforeError)
        assertTrue(m.transition(FlowEvent.Retry))
        assertEquals(AppFlowState.RECOVERY_MODE, m.state)
    }

    // ERROR 的另一出路：重新校正 → CALIBRATING，原因清掉。
    @Test
    fun error_startCalibration_goesToCalibratingAndClearsReason() {
        val m = machine(AppFlowState.CALIBRATING)
        assertTrue(m.transition(FlowEvent.Failure("save failed")))
        assertTrue(m.transition(FlowEvent.StartCalibration))
        assertEquals(AppFlowState.CALIBRATING, m.state)
        assertNull(m.errorReason)
        assertNull(m.stateBeforeError)
    }

    // F-13：全部非法轉換（除 Failure／Retry 外的全部事件 × 全部狀態）。
    // 合法對照表即 STATE_DESIGN §2.1；不在表中的一律拒絕、狀態不變、記警告。
    @Test
    fun f13_allIllegalTransitions_rejectedUnchangedAndLogged() {
        val allowed: Map<Pair<AppFlowState, FlowEvent>, AppFlowState> = mapOf(
            (AppFlowState.NOT_CALIBRATED to FlowEvent.StartCalibration) to AppFlowState.CALIBRATING,
            (AppFlowState.NOT_CALIBRATED to FlowEvent.ModelAppeared) to AppFlowState.CALIBRATED,
            (AppFlowState.CALIBRATING to FlowEvent.CalibrationSucceeded) to AppFlowState.CALIBRATED,
            (AppFlowState.CALIBRATING to FlowEvent.CalibrationCancelled) to AppFlowState.NOT_CALIBRATED,
            (AppFlowState.CALIBRATING to FlowEvent.TimeoutPromptExit) to AppFlowState.NOT_CALIBRATED,
            (AppFlowState.CALIBRATING to FlowEvent.TimeoutPromptContinue) to AppFlowState.CALIBRATING,
            (AppFlowState.CALIBRATED to FlowEvent.GoToAccessibilitySettings) to AppFlowState.WAITING_ACCESSIBILITY,
            (AppFlowState.CALIBRATED to FlowEvent.StartCalibration) to AppFlowState.CALIBRATING,
            (AppFlowState.CALIBRATED to FlowEvent.ModelInvalidated) to AppFlowState.NOT_CALIBRATED,
            (AppFlowState.WAITING_ACCESSIBILITY to FlowEvent.ServiceVerified) to AppFlowState.SERVICE_RUNNING,
            (AppFlowState.WAITING_ACCESSIBILITY to FlowEvent.ReturnedWithoutService) to AppFlowState.WAITING_ACCESSIBILITY,
            (AppFlowState.WAITING_ACCESSIBILITY to FlowEvent.BackToCalibration) to AppFlowState.CALIBRATED,
            (AppFlowState.SERVICE_RUNNING to FlowEvent.StopEyeControl) to AppFlowState.RECOVERY_MODE,
            (AppFlowState.SERVICE_RUNNING to FlowEvent.StopCompletely) to AppFlowState.STOPPED,
            (AppFlowState.RECOVERY_MODE to FlowEvent.RestartVerified) to AppFlowState.SERVICE_RUNNING,
            (AppFlowState.RECOVERY_MODE to FlowEvent.StopCompletely) to AppFlowState.STOPPED,
            (AppFlowState.STOPPED to FlowEvent.StartTracking) to AppFlowState.SERVICE_RUNNING,
            (AppFlowState.ERROR to FlowEvent.StartCalibration) to AppFlowState.CALIBRATING,
        )
        val events: List<FlowEvent> = listOf(
            FlowEvent.StartCalibration,
            FlowEvent.CalibrationSucceeded,
            FlowEvent.CalibrationCancelled,
            FlowEvent.ModelAppeared,
            FlowEvent.ModelInvalidated,
            FlowEvent.GoToAccessibilitySettings,
            FlowEvent.BackToCalibration,
            FlowEvent.ServiceVerified,
            FlowEvent.ReturnedWithoutService,
            FlowEvent.StopEyeControl,
            FlowEvent.RestartVerified,
            FlowEvent.StopCompletely,
            FlowEvent.StartTracking,
            FlowEvent.TimeoutPromptExit,
            FlowEvent.TimeoutPromptContinue,
        )
        for (from in AppFlowState.values()) {
            for (event in events) {
                val logger = RecordingLogger()
                val m = machine(from, logger)
                val expected = allowed[from to event]
                val result = m.transition(event)
                if (expected == null) {
                    assertFalse("expected rejection: $from + $event", result)
                    assertEquals(from, m.state)
                    assertEquals("expected one warning: $from + $event", 1, logger.messages.size)
                    assertTrue(
                        logger.messages[0].contains(from.name),
                    )
                } else {
                    assertTrue("expected acceptance: $from + $event", result)
                    assertEquals(expected, m.state)
                    assertTrue("no warning for legal transition: $from + $event", logger.messages.isEmpty())
                }
            }
        }
    }

    // F-13 例示：NOT_CALIBRATED 直接 → SERVICE_RUNNING、STOPPED 直接 → CALIBRATING。
    @Test
    fun f13_examples_fromSpec_areRejected() {
        val logger = RecordingLogger()
        val m1 = machine(AppFlowState.NOT_CALIBRATED, logger)
        assertFalse(m1.transition(FlowEvent.ServiceVerified))
        assertEquals(AppFlowState.NOT_CALIBRATED, m1.state)
        val m2 = machine(AppFlowState.STOPPED, logger)
        assertFalse(m2.transition(FlowEvent.StartCalibration))
        assertEquals(AppFlowState.STOPPED, m2.state)
        assertEquals(2, logger.messages.size)
    }

    // F-13 補：無 logger 時非法轉換不 crash。
    @Test
    fun f13_illegalTransitionWithoutLogger_doesNotCrash() {
        val m = machine(AppFlowState.NOT_CALIBRATED, null)
        assertFalse(m.transition(FlowEvent.ServiceVerified))
        assertEquals(AppFlowState.NOT_CALIBRATED, m.state)
    }

    // F-14：CALIBRATING 單點逾時詢問，選「退出」→ NOT_CALIBRATED。
    @Test
    fun f14_timeoutPromptExit_goesToNotCalibrated() {
        val m = machine(AppFlowState.CALIBRATING)
        assertTrue(m.transition(FlowEvent.TimeoutPromptExit))
        assertEquals(AppFlowState.NOT_CALIBRATED, m.state)
    }

    // F-14：選「繼續」→ 停留 CALIBRATING。
    @Test
    fun f14_timeoutPromptContinue_staysCalibrating() {
        val logger = RecordingLogger()
        val m = machine(AppFlowState.CALIBRATING, logger)
        assertTrue(m.transition(FlowEvent.TimeoutPromptContinue))
        assertEquals(AppFlowState.CALIBRATING, m.state)
        assertTrue(logger.messages.isEmpty())
        // 繼續後仍可完成校正。
        assertTrue(m.transition(FlowEvent.CalibrationSucceeded))
        assertEquals(AppFlowState.CALIBRATED, m.state)
    }

    @Test
    fun f14_timeoutPromptEvents_outsideCalibrating_areRejected() {
        for (from in AppFlowState.values()) {
            if (from == AppFlowState.CALIBRATING) continue
            val m = machine(from)
            assertFalse(m.transition(FlowEvent.TimeoutPromptExit))
            assertFalse(m.transition(FlowEvent.TimeoutPromptContinue))
            assertEquals(from, m.state)
        }
    }

    // §2.1 額外合法邊：模型出現／失效、回校正、由運作中完全停止。
    @Test
    fun extra_modelAppearedAndInvalidated() {
        val m1 = machine(AppFlowState.NOT_CALIBRATED)
        assertTrue(m1.transition(FlowEvent.ModelAppeared))
        assertEquals(AppFlowState.CALIBRATED, m1.state)

        val m2 = machine(AppFlowState.CALIBRATED)
        assertTrue(m2.transition(FlowEvent.ModelInvalidated))
        assertEquals(AppFlowState.NOT_CALIBRATED, m2.state)
    }

    @Test
    fun extra_waiting_backToCalibration_goesToCalibrated() {
        val m = machine(AppFlowState.WAITING_ACCESSIBILITY)
        assertTrue(m.transition(FlowEvent.BackToCalibration))
        assertEquals(AppFlowState.CALIBRATED, m.state)
    }

    @Test
    fun extra_serviceRunning_stopCompletely_goesToStopped() {
        val m = machine(AppFlowState.SERVICE_RUNNING)
        assertTrue(m.transition(FlowEvent.StopCompletely))
        assertEquals(AppFlowState.STOPPED, m.state)
    }

    // 全程 happy path：校正 → 確認 → 啟用 → 驗證 → 運作 → 恢復 → 完全停止 → 重開。
    @Test
    fun happyPath_fullChain() {
        val m = machine()
        assertTrue(m.transition(FlowEvent.StartCalibration))
        assertTrue(m.transition(FlowEvent.CalibrationSucceeded))
        assertTrue(m.transition(FlowEvent.GoToAccessibilitySettings))
        assertTrue(m.transition(FlowEvent.ServiceVerified))
        assertTrue(m.transition(FlowEvent.StopEyeControl))
        assertTrue(m.transition(FlowEvent.RestartVerified))
        assertTrue(m.transition(FlowEvent.StopCompletely))
        assertTrue(m.transition(FlowEvent.StartTracking))
        assertEquals(AppFlowState.SERVICE_RUNNING, m.state)
        assertNull(m.errorReason)
        assertNull(m.stateBeforeError)
    }

    @Test
    fun initialState_defaultsToNotCalibrated() {
        val m = AppFlowStateMachine()
        assertEquals(AppFlowState.NOT_CALIBRATED, m.state)
        assertNull(m.errorReason)
        assertNull(m.stateBeforeError)
    }
}
