package com.example.eyecontrol

/**
 * 集中式手勢派發守衛（DCL-04，STEP 3）。
 *
 * 純 Kotlin，不依賴任何 Android API；狀態轉換皆為呼叫端傳入的決策，
 * 可在純 JVM 單元測試中完整驅動。系統側的 `dispatchGesture` 仍由
 * `GazeAccessibilityService` 的四個 `perform*` 函式發出，本守衛只做
 * 「唯一 ID／世代管理＋過期回呼隔離＋單次清理＋不重播」的集中狀態機，
 * 不發起、不重發任何手勢。
 *
 * 狀態：[State.IDLE]（無進行中手勢）／[State.IN_FLIGHT]（ exactly 一個
 * 進行中手勢，由 [currentId] 識別）。任何終端轉換（完成／取消／拒絕／
 * 逾時／中斷／搶占）皆使守衛回到 IDLE，且同一手勢的清理只計一次
 * （[cleanupCount]）：終端轉換後該 ID 即失效，後到的舊回呼一律忽略，
 * 不得改變新手勢狀態、不得重播。
 *
 * 逾時（[onTimeout]）只有「決策」在此：逾時計時器的排程（須一個毫秒值）
 * 未在本輪接線（見 fix log 提案 P-04T），服務端尚未呼叫本函式；
 * 決策本身已實作並已測試，待排程接線後直接可用。
 *
 * 執行緒：只在服務主執行緒與手勢回呼路徑（既有 `dispatchGesture` 回呼
 * 的同一執行緒）上呼叫，與既有回呼程式碼相同，不新增鎖、不新增執行緒。
 */
class GestureDispatchGuard {

    /** 守衛狀態：只有「無進行中」與「恰一個進行中」兩態，無佇列。 */
    enum class State {
        IDLE,
        IN_FLIGHT,
    }

    /** 終端原因：完成／系統取消／派發被拒／逾時／中斷（暫停／停止／銷毀）／被新手勢搶占。 */
    enum class TerminalKind {
        COMPLETED,
        CANCELLED,
        REJECTED,
        TIMED_OUT,
        INTERRUPTED,
        SUPERSEDED,
    }

    var state: State = State.IDLE
        private set

    /** 進行中手勢的唯一 ID；IDLE 時為 null。ID 嚴格遞增，永不重用。 */
    var currentId: Long? = null
        private set

    /** 已執行的終端清理次數（每手勢恰一次；重複終端呼叫不重計）。 */
    var cleanupCount: Int = 0
        private set

    /** 最近一次終端轉換的原因；尚無終端轉換時為 null。 */
    var lastTerminalKind: TerminalKind? = null
        private set

    private var nextId: Long = 1L

    /**
     * 開始一次新手勢派發，回傳其唯一 ID。
     *
     * 若已有進行中手勢，舊手勢被搶占：舊 ID 立即失效並計一次清理
     * （[TerminalKind.SUPERSEDED]），舊手勢後到的回呼一律視為過期而忽略。
     * 呼叫端須以回傳 ID 配對後續回呼；取消後再派發必須是另一次 [begin]
     * 呼叫（新的使用者意圖），守衛本身永不重發。
     */
    fun begin(): Long {
        val superseded = currentId
        if (superseded != null) {
            finishTerminal(TerminalKind.SUPERSEDED)
        }
        val id = nextId++
        currentId = id
        state = State.IN_FLIGHT
        return id
    }

    /**
     * `dispatchGesture` 的回傳值處理。
     *
     * @return `true` iff 此 ID 為進行中手勢且 `accepted` 為 false（派發被拒，
     * 已終端清理）；過期 ID 或 `accepted` 為 true 時回傳 `false`（後者手勢
     * 仍在進行中，等待系統回呼）。
     */
    fun onDispatchReturned(id: Long, accepted: Boolean): Boolean {
        if (!isCurrent(id)) return false
        if (accepted) return false
        finishTerminal(TerminalKind.REJECTED)
        return true
    }

    /**
     * 系統 `onCompleted` 回呼。
     *
     * @return `true` iff 此 ID 為進行中手勢（呼叫端可據此執行完成副作用，
     * 如游標閃光）；過期回呼回傳 `false`，呼叫端不得執行任何副作用。
     */
    fun onCompleted(id: Long): Boolean {
        if (!isCurrent(id)) return false
        finishTerminal(TerminalKind.COMPLETED)
        return true
    }

    /** 系統 `onCancelled` 回呼：僅進行中手勢觸發終端清理，過期回呼忽略。 */
    fun onCancelled(id: Long): Boolean {
        if (!isCurrent(id)) return false
        finishTerminal(TerminalKind.CANCELLED)
        return true
    }

    /**
     * 逾時決策（計時器排程未接線，見檔頭說明）：僅進行中手勢觸發終端清理。
     */
    fun onTimeout(id: Long): Boolean {
        if (!isCurrent(id)) return false
        finishTerminal(TerminalKind.TIMED_OUT)
        return true
    }

    /**
     * 中斷（服務停止／使用者暫停／銷毀／校正交接）：進行中手勢終端清理，
     * 之後等待新的使用者意圖（下一次 [begin]）；IDLE 時為 no-op。
     */
    fun onInterrupted(): Boolean {
        if (currentId == null) return false
        finishTerminal(TerminalKind.INTERRUPTED)
        return true
    }

    private fun isCurrent(id: Long): Boolean = state == State.IN_FLIGHT && currentId == id

    /**
     * 唯一終端轉換點：清理只在此執行一次——狀態回 IDLE、[currentId] 清空、
     * [cleanupCount] 加一。轉換後該 ID 即失效，任何重複終端呼叫皆因
     * [isCurrent] 為 false 而被忽略，清理永不重複執行。
     */
    private fun finishTerminal(kind: TerminalKind) {
        currentId = null
        state = State.IDLE
        cleanupCount += 1
        lastTerminalKind = kind
    }
}
