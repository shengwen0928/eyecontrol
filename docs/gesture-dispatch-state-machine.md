# EyeControl 手勢派發狀態機（GestureDispatcher 設計）

> 2026-11-23 增補（review 樹，未合併／未推送）：「五個不能動」中的 22 維特徵契約一項，已由授權變更為 25 維（`MODEL_VERSION` 2→3；新增連續光特徵 f[22..24]，舊模型強制重校）；其餘四項不動。詳見 `docs/light-robust-gaze-design.md`。

> 性質：設計文件。**只寫設計，不改任何程式碼。** 本文件不含程式碼修改、
> 不改版本號、不碰 `.github/workflows/`、不推送、不發版。
> 基線：`~/workspace/eyecontrol-p3`（v0.5.9 加未推送的 Phase 3 實作；
> `app/build.gradle.kts:29-30` 為 `versionCode = 13`／`versionName = "0.5.9"`）。
> 行號一律以本樹現況為準，皆為撰寫時親自 grep／讀檔查證。
> 上位依據：`~/workspace/eyecontrol-phase3/PLAN.md` §4、
> `~/workspace/eyecontrol-phase3/STATE_DESIGN.md` §3／§5、
> `~/workspace/eyecontrol-master-audit/docs/phase2/GAPS_AND_DECISIONS.md` §3.1 D-3、
> `~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md` §1 出入第 23、27 點。
>
> **編號警告（必讀）：** 任務書編號與 PLAN 表格編號的 D3-3／D3-4 互換
> （`IMPLEMENTATION_NOTES.md` §1 出入第 23 點：任務書 D3-3＝PLAN D3-4
> 三項檢查分離；任務書 D3-4＝PLAN D3-3 總開關＋分項開關）。
> 本文件所稱「PLAN D3-7」指 PLAN 表格的 D3-7（含 dispatch 部分）；
> 任務書的 D3-7 為「控制器 Cancelled 輸出」，兩者**不是同一回事**，
> 不得混為一談（同上出入第 23、27 點）。

## 0. 現況映射：全部 dispatchGesture 呼叫點

全樹 grep（`app/src/main/java`，`dispatchGesture`／`performGlobalAction`／
`GestureResultCallback`／`onCancelled`）的結果如下。**全樹只有 4 處
`dispatchGesture` 呼叫，全部在 `GazeAccessibilityService.kt` 內；**
`EdgeGestureController.kt` 內沒有任何 `dispatchGesture` 呼叫
（僅檔頭註解 `:47` 與 `EdgeDecision.Execute` 文件註解 `:671` 提及
「服務呼叫現有 `performEdgeAction`／dispatch」）；校正頁、訓練頁、
主畫面、設定頁皆無 `dispatchGesture` 呼叫。

### 0.1 四個 dispatch 包裝函式

| 呼叫點 | 函式（行號） | 筆畫（Stroke）現況值 | 回呼現況 | 回傳值處理 |
|---|---|---|---|---|
| D-1 | `performScroll(up)`，`GazeAccessibilityService.kt:1542-1560` | 單筆畫，`0, 500` ms（`:1553`） | 只有 `onCompleted`（`:1555-1557`，閃光）；**無 `onCancelled`** | 忽略（第三參數傳 `null`，回傳布林未賦值） |
| D-2 | `performTap(x, y)`，同檔 `:1561-1571` | 單筆畫，`0, 60` ms（`:1565`） | 只有 `onCompleted`（`:1567-1569`，閃光）；**無 `onCancelled`** | 忽略 |
| D-3 | `performLongPress(x, y)`，同檔 `:1580-1590` | 單筆畫，`0, 800` ms（`:1584`） | 只有 `onCompleted`（`:1586-1588`，閃光）；**無 `onCancelled`** | 忽略 |
| D-4 | `performSwipe(x1, y1, x2, y2)`，同檔 `:1592-1604` | 單筆畫，`0, 500` ms（`:1599`） | 只有 `onCompleted`（`:1601-1603`，閃光）；**無 `onCancelled`** | 忽略 |

全樹 `onCancelled` **零命中**（除 `AppFlowState.kt` 內「取消」一詞的
檔案註解外，無任何 `GestureResultCallback.onCancelled` 覆寫）。
此即 K-62（`PLAN.md` §1.7／`STATE_DESIGN.md` §3.1 `GESTURE_DISPATCHING` 列）。

### 0.2 雙擊：第二下是匿名排程，無法取消

`performDoubleTap(x, y)`（同檔 `:1573-1578`）：

```text
第 1 下：performTap(x, y)          // 立即，D-2
第 2 下：mainHandler.postDelayed({ performTap(x, y) }, 150)  // :1575-1577，匿名 lambda
```

- 第二下**沒有具名 `Runnable` 欄位持有**，全樹無任何針對它的
  `removeCallbacks`（`removeCallbacks` 命中只在 `CalibrationActivity.kt:318`
  的交接逾時與 `MainActivity.kt:290` 的狀態輪詢，與雙擊無關）。
- 因此模式切換、暫停、停止、提醒介入、`onDestroy` 皆**無法取消已排程的
  第二下**。此即 K-07（`PLAN.md` §1.7／§4 D3-7）。
- 第二下排程時不重查任何條件：不查 `pausedForCalibration`、
  `userStopped`、`inRecoveryMode`、`reminderActive`、冷卻、臉 present。
  150 ms 內狀態翻轉，第二下照樣發出。

### 0.3 派發的觸發來源（誰呼叫上述四函式）

| 觸發來源 | 呼叫點（行號） | 說明 |
|---|---|---|
| 一般 dwell 滿 | `triggerActionAt(anchorX, anchorY)`，`:953`（dwell 滿分支內） | 經 `screenDwell.update` 進度 ≥ 1f；冷卻期間不累積（`:930-932`）。觸發後設 `cooldownUntil`（`:958`）。 |
| 眨眼結算 | `triggerActionAt(lastValidX, lastValidY)`，`:571`（眨眼分支內） | 雙眼閉合 150–600 ms、`hasValidCursor`、過冷卻、非 PAUSED、非提醒期間。觸發後設 `cooldownUntil`（`:572`）。 |
| 邊緣執行判決 | `performEdgeAction(...)`，`:848-854`（`EdgeDecision.Execute` 分支） | 控制器判決後直接呼叫；頂／底經二階段確認、側邊經方向驗證後才到此。觸發後設 `cooldownUntil`（`:856`）。 |
| `triggerActionAt` 內部分派 | `:1479-1517` | `TAP → performTap`（`:1482`）；`DOUBLE_TAP → performDoubleTap` 並立即切回 TAP（`:1485`）；`LONG_PRESS → performLongPress` 並切回 TAP（`:1491`）；`SWIPE` 第一段只記起點（`:1499`），第二段 `performSwipe` 並切回 TAP（`:1506`）。 |

`triggerActionAt` 的模式切回 TAP 發生在**派發呼叫之後、回呼之前**：
雙擊／長按／滑動的「模式已回 TAP」不等於「手勢已完成」。
面板按鈕分支（`:751-794`）只切換 `currentMode`，本身不派發任何手勢。

### 0.4 performGlobalAction：邊緣返回／首頁沒有任何完成信號

`performEdgeAction(left, right, top, bottom)`（同檔 `:1519-1540`），
優先序沿用現況**頂＞底＞左＞右**：

| 分支 | 呼叫（行號） | 完成／取消信號 |
|---|---|---|
| 頂＝返回 | `performGlobalAction(GLOBAL_ACTION_BACK)`，`:1523` | **無**。`performGlobalAction` 沒有回呼參數，回傳值亦被忽略；隨後 `flashClick()`（`:1524`）在呼叫後立即排程，**不是**完成證明。 |
| 底＝首頁 | `performGlobalAction(GLOBAL_ACTION_HOME)`，`:1528` | **無**，同上（`:1529` 閃光亦非完成證明）。 |
| 左＝上捲 | `performScroll(up = true)`，`:1533` | 經 D-1，有 `onCompleted`，無 `onCancelled`。 |
| 右＝下捲 | `performScroll(up = false)`，`:1537` | 經 D-1，同上。 |

後果：返回／首頁的派發在設計上只能是「已請求、結果未知」
（fire-and-forget），**絕對不得標示為已完成／已驗證完成**。
§2 的 `COMPLETED` 態只適用於有 `onCompleted` 回呼的四個
`dispatchGesture` 函式；全域動作另見 §4.7。

### 0.5 現況缺口總表（本設計要補的洞）

| # | 缺口 | 證據（行號） |
|---|---|---|
| G-1 | `dispatchGesture` 回傳值（是否接受）全部忽略 | `:1554`、`:1566`、`:1585`、`:1600`（皆傳 `null` handler、未賦值） |
| G-2 | 無 `onCancelled`：手勢被系統拒絕時無狀態修正、無重試資格判斷 | 全樹 `onCancelled` 零命中（§0.1） |
| G-3 | 無「進行中」旗標／鎖定：派發未完成前可再派發第二個手勢（`STATE_DESIGN.md` §3.1 單一手勢規則為 TARGET，現況無守衛） | `triggerActionAt` 內無進行中檢查；冷卻只擋 dwell 累積，不擋已排程的雙擊第二下（§0.2） |
| G-4 | 冷卻設在觸發**之後**，且雙擊第二下繞過冷卻檢查 | `:572`、`:856`、`:958` 皆在觸發後設 `cooldownUntil`；第二下為 `postDelayed` 直接呼叫 |
| G-5 | 無逾時：回呼永不來時無任何狀態變化（等待無上限） | 四函式內無計時器、無逾時分支 |
| G-6 | 無唯一識別碼、無派發紀錄：無法把一次 `onCompleted` 對應回哪一次請求、哪個座標、哪種意圖 | 四函式皆為匿名回呼物件，無 id、無 Log |
| G-7 | 服務中斷無處理：`onInterrupt() = Unit`（`:1635`），不取消待執行手勢、不標示中斷 | `:1635`；`onDestroy`（`:1606-1633`）只停引擎／移除 overlay，**不**取消雙擊第二下（無欄位可取消） |
| G-8 | 暫停／模式切換不取消待執行手勢 | `pauseTracking()`（`:1823-1832`）只切模式＋展開面板；面板切換分支（`:776-791`）只切模式 |
| G-9 | 滑動起點殘留：`swipeStartPoint` 只在提醒上升沿（`:1082`）與重啟成功（`:1451`）清掉；臉遺失分支（`:515-545`）、影像降級分支（`:632-652`）、PAUSED 分支皆不清起點 | `swipeStartPoint` 賦值點僅 `:246`、`:1082`、`:1451`、`:1499`、`:1507` |
| G-10 | 提醒介入時只清邊緣手勢與 dwell，滑動起點的清除在 `showReminder` 內（`:1082`），但提醒**結束**後舊起點語義未重驗 | 見 G-9 行號；設計上起點必須有有效期／重驗（§4.6） |

## 1. GestureDispatcher：定位與不變量

### 1.1 定位

`GestureDispatcher`（名稱為本設計的提議名稱，實作時定稿）是
**唯一**可以呼叫 `dispatchGesture`／`performGlobalAction` 的中介。
現有四個 `perform*` 函式改為經 Dispatcher 發出（函式名與筆畫參數
**不變**：tap 60 ms、long-press 800 ms、swipe／scroll 500 ms、
雙擊間隔 150 ms，見 `STATE_DESIGN.md` §3.1；改動的只是「發出前檢查、
發出後追蹤、取消時清理」這一層，不得改筆畫本身）。

Dispatcher **不**做意圖判斷：dwell 是否滿、邊緣檢查是否通過、確認
是否完成，仍由 `DwellTracker`／`EdgeGestureController`／主路徑決定。
Dispatcher 只回答：「這一次派發請求，在當下是否允許發出；發出後處於
什麼狀態；結束時（完成／取消／失敗／逾時／中斷）如何清理、如何讓
下一次請求被正確受理。」

### 1.2 不變量（任何實作都不得違反）

1. **同一時刻最多一個進行中的手勢工作**（含雙擊的第二下與滑動的
   第二段在內，皆屬同一個手勢工作）。進行中時，新的派發請求一律
   拒絕，不得排隊、不得覆蓋進行中的工作。
2. **完成只能由系統回呼證明。** `onCompleted` 是唯一可進入
   `COMPLETED` 的證據。固定延遲、`postDelayed` 到時、冷卻到期、
   閃光已顯示，**一律不是完成證明**，不得以此假裝完成。
3. **取消不得標示成功。** `onCancelled`、回傳 `false`、逾時、中斷，
   一律不得進入 `COMPLETED`，不得觸發 `flashClick` 成功回饋，
   不得把 `currentMode` 的切回 TAP（`triggerActionAt` 內已發生）
   解讀為「手勢成功」。
4. **零自動重試。** Dispatcher 永不自行重派發。重派發只能來自使用者的
   一次新意圖（重新 dwell 滿／重新眨眼結算／重新走完邊緣確認），
   且須先確認上一手勢已結束（§3 第 4 步）。
5. **不可重複執行。** 同一意圖只產生一次派發；返回／首頁／點擊不得
   因重試、重排程、狀態恢復而重複發出（§4.5）。
6. **主執行緒序列化。** 全部狀態轉換只在主執行緒發生（現況樣本處理、
   `mainHandler` 排程皆在主執行緒，見 `:484-491`）；回呼到達後先
   回到主執行緒再轉換狀態，不得在背景執行緒讀寫 Dispatcher 狀態。
7. **誠實紀錄，不顯示假數字。** 派發紀錄只寫 `Log`（供診斷），
   不向使用者顯示任何分數／成功率；`GazeLog` 的 CSV 欄位**不新增**
   （不得為派發狀態新增假數據欄位，見 `PLAN.md` §1.2 註）。

## 2. 狀態機

### 2.1 狀態定義

| 狀態 | 含義 | 進入方式 | 離開方式 |
|---|---|---|---|
| `IDLE` | 無進行中的手勢工作，可接受新請求 | 初始；任一終態清理完成後 | 接受請求 → `DISPATCHING` |
| `DISPATCHING` | 已發出 `dispatchGesture`（或已請求全域動作，見 §4.7），等待系統回呼或逾時／中斷 | §3 第 6 步發出成功後 | `onCompleted` → `COMPLETED`；`onCancelled` → `CANCELLED`；回傳 `false` → `FAILED`（立即清理，見 §4.1）；逾時 → `TIMED_OUT`；服務中斷 → `SERVICE_INTERRUPTED` |
| `COMPLETED` | 系統已回報完成（僅 `dispatchGesture` 四函式） | `onCompleted` | 清理後 → `IDLE`（終態為瞬時記錄態，不得停留等待下一請求） |
| `CANCELLED` | 手勢被取消（系統拒絕／呼叫端取消／狀態翻轉取消），**不是成功** | `onCancelled`；或 §4.2／§4.3 的呼叫端取消 | 清理後 → `IDLE` |
| `FAILED` | `dispatchGesture` 回傳 `false`（系統當下不接受），手勢未發出 | 回傳 `false`（§3 第 6 步） | 立即清理後 → `IDLE` |
| `TIMED_OUT` | 等待回呼超過逾時上限（§2.3），結果未知 | 逾時計時到時（§3 第 7 步的逾時分支） | 釋放鎖定、清理後 → `IDLE`；**不得**事後把遲到的 `onCompleted` 補記為成功（見 §2.3） |
| `SERVICE_INTERRUPTED` | 無障礙服務被中斷（`onInterrupt`）或銷毀（`onDestroy`）時尚有進行中工作，全部取消 | `onInterrupt`／`onDestroy` 時 `DISPATCHING` 中 | 清理全部待執行工作後 → 終態（服務已不存在，不再回 `IDLE` 接新請求） |

補充狀態關係（與既有設計的對照）：

- 本機的 `DISPATCHING` 對應 `STATE_DESIGN.md` §3.1 的互動層
  `GESTURE_DISPATCHING`；`COMPLETED`／`CANCELLED` 對應同表的
  `GESTURE_COMPLETED`／`CANCELLED`。兩層狀態**同步進入、同步離開**，
  不得一層已離開、另一層仍停留在派發中。
- `EdgeGestureController` 的 `EXECUTED` 是**單幀判決**（
  `EdgeGestureController.kt:671` 註解），不是 Dispatcher 狀態：
  控制器回 `Execute` 後，服務呼叫 `performEdgeAction`，Dispatcher
  才進入 `DISPATCHING`。控制器狀態機（`IDLE／CANDIDATE／CONFIRMING／
  COOLDOWN`）與 Dispatcher 狀態機是兩台機器，判決與派發的邊界
  在 `performEdgeAction` 呼叫點，不得互相冒充。
- `cooldownUntil`（冷卻）與 Dispatcher 鎖定是**兩道不同的閘**：
  冷卻擋「dwell 累積」，鎖定擋「派發發出」。冷卻到期**不等於**
  鎖定釋放；鎖定釋放**不等於**冷卻到期。兩者皆滿足，新請求才能發出。

### 2.2 每次派發唯一識別碼

每個手勢工作在建立時（§3 第 5 步）取得一個唯一識別碼
`gestureId`，終身不變，貫穿請求、發出、回呼、清理、紀錄：

- 產生方式（設計提議，實作時定稿）：主執行緒內的單調遞增計數器
 （`1, 2, 3, …`，行程內唯一）＋ 種類標籤（`TAP／DOUBLE_TAP／
  LONG_PRESS／SWIPE／SCROLL／GLOBAL_BACK／GLOBAL_HOME`）。
  不用 wall-clock 時間作 id（時間只作 `requestedAtMs`，取
  `SystemClock.elapsedRealtime()`，與主路徑現況一致）。
- 雙擊的兩下**共用同一個 `gestureId`**（同一意圖、同一工作）：
  第一筆畫與第二筆畫以 `strokeIndex 0／1` 區分；第二筆畫的回呼
  仍歸屬同一工作，不得另開一個工作、不得另取 id。
- 滑動的起點選取與終點派發**共用同一個 `gestureId`** 亦同理：
  起點只是「待完成的工作」，終點派發時沿用該 id；起點被取消時，
  該 id 即進入 `CANCELLED`，不得留一個無 id 的懸空起點。
- 回呼到達時必須以 `gestureId` 比對：回呼所屬 id 與當前進行中
  工作的 id 不符（遲到回呼、上一工作的殘留回呼），一律忽略，
  不得改變當前狀態（見 §2.3 逾時後的遲到回呼處理）。

手勢工作記錄（僅記憶體＋`Log`，不持久化、不顯示給使用者）：

```text
gestureId, kind, strokeIndex, 座標／路徑快照（發出當下的固定值，
  發出後不得再吃新樣本改變座標）, requestedAtMs, dispatchedAtMs,
  dispatchReturned（布林）, outcome（COMPLETED／CANCELLED／FAILED／
  TIMED_OUT／SERVICE_INTERRUPTED）, outcomeAtMs, cancelReason（§4.2 原因碼）
```

### 2.3 逾時：釋放鎖定，不等於完成

- 逾時計時只在 `DISPATCHING` 期間運行；計時到時 → `TIMED_OUT` →
  釋放鎖定 → `IDLE`。逾時**不是**完成、**不是**取消證明、
  **不是**失敗證明：結果未知，紀錄寫「結果未知」，不得寫「失敗」、
  不得寫「成功」。
- 逾時後遲到的 `onCompleted`／`onCancelled`：以 `gestureId` 比對後
  確認屬已結束的工作，**只記 Log，不轉換狀態，不觸發閃光**。
  不得把遲到完成補記為成功、不得因此重設冷卻。
- 逾時長度：**待決，不得預設。** 設計約束只有兩條：(a) 必須大於
  最長筆畫 800 ms（long-press，§0.1）加系統回呼延遲，否則正常
  長按尚未走完即被判逾時；(b) 不得以固定延遲假裝完成——逾時值
  再長，也只是「放棄等待」的界線，不是「推定完成」的界線。
  具體毫秒數由實作審查時定稿並寫入常數註解，**本文件不編數字**。
  列為待決項 T-D1（見 §7）。
- 全域動作（返回／首頁）沒有回呼，故**沒有逾時態**：發出後即視為
  「已請求、結果未知」，只受冷卻＋重新武裝約束（§4.7），
  不進 `DISPATCHING` 等待、不設逾時計時。不得為全域動作虛構
  一個回呼等待。

## 3. 十步派發流程

每一次派發請求（dwell／眨眼／邊緣執行判決）都必須完整走完以下
十步。**任一步拒絕，即不發出、不留進行中狀態、不改變模式**，
只記原因（`Log`）並回到可接受下一意圖的狀態。

| 步 | 檢查／動作 | 拒絕／失敗時的處理 |
|---|---|---|
| 1. 檢查服務可用 | 服務實例仍在（`instance == this`）；未在 `onDestroy`／`onInterrupt` 之後；引擎狀態不影響派發本身（派發是無障礙服務能力，不是引擎能力），故不以引擎為派發前提 | 拒絕：記原因，不發出 |
| 2. 檢查啟用狀態 | 未 `pausedForCalibration`、未 `userStopped`、未 `inRecoveryMode`；`currentMode != PAUSED`（PAUSED 只剩「重啟」鍵，見 `STATE_DESIGN.md` §3.4）；提醒未顯示（`reminderActive == false`） | 拒絕：記原因。**暫停時不得讓舊手勢恢復後執行**——見 §4.3 |
| 3. 檢查追蹤品質 | 派發當下臉 present（本幀有樣本）、雙眼睜開、特徵有限、影像未降級（與邊緣檢查同一組量，不得另發明品質分數；量測來源見 `edgeFrame()`，`GazeAccessibilityService.kt:1220-1246`）。**注意**：此步只擋「當下新請求」；已在 `DISPATCHING` 中的工作不因後續幀品質下降而被追溯取消——它的結束只由回呼／逾時／中斷決定（§4.2 的狀態翻轉取消除外） | 拒絕：記原因（只寫 Log，不顯示數字給使用者） |
| 4. 檢查未完成手勢 | Dispatcher 處於 `IDLE`（無進行中工作）；雙擊第二下／滑動第二段不走此步（它們屬同一工作，見 §4.4／§4.6） | 拒絕：新請求丟棄，不得排隊。記原因（`BUSY`），不得覆蓋進行中工作 |
| 5. 建立手勢工作 | 分配 `gestureId`；快照座標／路徑（發出後座標凍結）；記 `requestedAtMs`；狀態 → 預備態（未發出，仍屬 `IDLE` 的子步驟，不得被其他請求插隊——建立與發出在同一主執行緒臨界區內完成，中間不 post、不等待） | —（建立本身不失敗；建立後第 6 步失敗即進 `FAILED`，見下） |
| 6. 執行 dispatchGesture | 呼叫 `dispatchGesture(gesture, callback, null)`，callback 同時覆寫 `onCompleted` 與 `onCancelled`（見 §5）；**檢查回傳值**：`true` → 進 `DISPATCHING`、記 `dispatchedAtMs`；`false` → 立即進 `FAILED`、清理（§4.1）、回 `IDLE` | 回傳 `false`：**立即清理**，不留進行中狀態，不設逾時，不觸發閃光，可由使用者重新意圖再次嘗試（新 `gestureId`） |
| 7. 接收完成／取消回呼 | `onCompleted` → `COMPLETED`；`onCancelled` → `CANCELLED`；逾時計時到時 → `TIMED_OUT`。回呼先以 `gestureId` 比對（§2.2），不符者忽略 | 遲到回呼：只記 Log（§2.3）。回呼到達前狀態翻轉（暫停／停止／提醒／中斷）→ 呼叫端取消路徑（§4.2／§4.3），回呼事後到達仍以 id 比對為準，不得重複清理 |
| 8. 更新狀態 | 依回呼／逾時／中斷結果，同步 Dispatcher 狀態與互動層狀態（`GESTURE_COMPLETED`／`CANCELLED`，見 §2.1）；模式切回 TAP 的語義**不在此步**（`triggerActionAt` 已在派發前切回，見 §0.3；不得把切回 TAP 當成完成） | — |
| 9. 釋放鎖定 | 清進行中工作記錄（保留最後一筆紀錄供診斷查詢，不得留可被誤讀為「進行中」的殘留）；取消逾時計時；取消雙擊第二下排程（若屬同一工作且尚未發出，見 §4.4）；清滑動起點（若屬同一工作，見 §4.6） | 釋放必須冪等：重複釋放不得重複觸發閃光、不得重設冷卻兩次（沿用 `CANCEL_CONTRACT.md` 第 9 條的冪等模式：同一認領旗標守衛） |
| 10. 允許下一操作 | 回 `IDLE`；冷卻（`cooldownUntil`）與重新武裝（邊緣鎖定、面板武裝）**各自**按現況規則生效（見 §2.1 末段：兩道閘不得互相代替）。下一意圖須重新走完十步，不得沿用上一工作的檢查結果 | — |

**全域動作的流程差異**（返回／首頁）：只走第 1、2、4、5 步與
「發出」部分，第 6 步改為 `performGlobalAction`（無回傳檢查、
無回呼），發出後直接走第 9、10 步（釋放＋冷卻＋武裝），
**不進 `DISPATCHING`、不設逾時、不進 `COMPLETED`**（§4.7）。

## 4. 取消規則

### 4.1 dispatchGesture 回傳 false：立即清理

- 回傳 `false` 表示系統當下不接受該手勢（手勢未發出、未執行）。
  Dispatcher 立即：記 `FAILED`（附 `gestureId` 與種類）、清工作記錄、
  取消逾時計時（尚未啟動則不啟動）、回 `IDLE`。
- **不得**觸發閃光、**不得**標示成功、**不得**自動重試、
  **不得**把 `false` 當成「稍後會完成」而保留進行中狀態。
- 使用者若再次以同一意圖 dwell／眨眼／確認，視為**新的請求**、
  新的 `gestureId`，重新走十步。連續 `false` 不得升級為自動重試
  迴圈；連續失敗的診斷只寫 Log，不得向使用者顯示失敗計數。

### 4.2 onCancelled：不得標示成功

- `onCancelled` → `CANCELLED`：清工作記錄、取消逾時計時、
  取消同一工作的待發第二筆畫（§4.4）、清滑動起點（§4.6）、
  回 `IDLE`。**不得**觸發 `flashClick`、**不得**進 `COMPLETED`、
  **不得**留下「已提交」假象（`STATE_DESIGN.md` §3.1
  `GESTURE_DISPATCHING` 列的取消語義）。
- 取消後**可再次觸發**：冷卻與武裝按現況規則重新計算後，
  使用者的新意圖可再次派發（對應 `STATE_DIAGRAMS.md` G-T15
  的 TARGET 語義：「手勢被拒 → CANCELLED 且可再次觸發」）。
  「可再次觸發」指**新意圖**可觸發，不是自動重發舊手勢。
- `onCancelled` 的原因（系統未提供原因碼時記「系統取消，未附原因」，
  不得編造原因）只寫 Log，不顯示給使用者。

### 4.3 狀態翻轉取消：暫停／停止／提醒／中斷／銷毀

以下任一事件發生時，若有進行中的手勢工作（`DISPATCHING` 中，
或雙擊第二下已排程未發出、或滑動起點待完成），Dispatcher 必須
取消它（進 `CANCELLED`，或對尚未發出的第二筆畫直接丟棄並記
`CANCELLED`），且**取消後不得在狀態恢復時補發**：

| 事件 | 現況呼叫點（證據） | 取消要求 |
|---|---|---|
| 模式切換（面板選新模式） | 面板分支 `:776-791`（切 `currentMode`） | 取消進行中工作＋丟棄已排程第二下。切換本身不等待手勢完成。 |
| 暫停（PAUSED） | `pauseTracking()` `:1823-1832`；面板「暫停」鍵經同一分支 | 同上。**暫停時不得讓舊手勢在恢復後執行**：恢復（`resumeTracking()` `:1841-1868`）時只恢復模式與面板，絕不補發舊工作；舊工作 id 已終結，新意圖須新 id。 |
| 停止眼控（RECOVERY_MODE） | `stopTracking()` `:1886-1913`（已呼叫 `cancelEdgeGesture()`，見 `:1900`） | 同上，另加：邊緣控制器同步 `reset()`（現況已有）。 |
| 完全停止（STOPPED） | `stopCompletely()` `:1918-1966`（已呼叫 `cancelEdgeGesture()`，見 `:1944`） | 同上。背景停引擎執行緒回主執行緒後（`:1938-1960`）須再次確認：若期間有新工作被建立（不應發生，因第 2 步已擋），以取消為準。 |
| 提醒上升沿 | `showReminder`（`:1070` 已呼叫 `cancelEdgeGesture()`；`:1082` 清滑動起點） | 同上。提醒期間（`reminderActive`）第 2 步拒絕一切新請求。 |
| 校正交接 | `requestPauseForCalibration` 回呼內（`:1672` 已呼叫 `cancelEdgeGesture()`） | 同上。交接期間樣本不進主路徑（`:484-491`），第 2 步亦擋。 |
| 臉遺失／影像降級 | 臉遺失分支 `:515-545`；影像降級分支 `:632-652`（皆已呼叫 `cancelEdgeGesture()`） | 取消**進行中的邊緣判決**（現況已有）；對 `DISPATCHING` 中的工作，臉遺失本身**不**追溯取消（§3 第 3 步註）——但雙擊第二下尚未發出時，臉遺失必須丟棄它（第二下發出前須重查，見 §4.4）。 |
| 方向改變 | `onConfigurationChanged` `:348-363`（已呼叫 `cancelEdgeGesture()`，見 `:362`） | 取消進行中工作＋丟棄第二下（座標系已變，舊座標快照失效）。 |
| 服務中斷 | `onInterrupt()` `:1635`（現況 `= Unit`，**未做任何事**） | **待實作**：`onInterrupt` 時若有進行中工作，進 `SERVICE_INTERRUPTED`、取消全部待執行工作（含第二下排程、滑動起點）、清逾時計時。服務中斷後不得再接受新請求。 |
| 服務銷毀 | `onDestroy()` `:1606-1633`（現況不處理派發） | **待實作**：`onDestroy` 時同 `onInterrupt` 的取消；`instance` 清 null 後，遲到回呼以 id 比對忽略（§2.2），不得操作已移除的 overlay。 |

取消的「全部待執行」清單（每次取消都須逐項確認，不得漏項）：
進行中 `dispatchGesture` 工作、雙擊第二下排程、滑動待完成起點、
逾時計時、確認目標提示殘留（邊緣確認由控制器 `reset()` 收掉，
現況 `cancelEdgeGesture()` 已做，見 `:1250-1264`）。

### 4.4 雙擊第二下：具名化＋發出前重查

- 第二下必須改為**具名 `Runnable` 欄位持有**（名稱待實作時定），
  發出前（150 ms 到時）重新檢查：第 2 步的啟用狀態（未暫停／未停止／
  未提醒／未交接）、臉 present、Dispatcher 仍屬同一工作且未被取消、
  座標系未改變（方向未變）。任一不通過 → 丟棄第二下，工作進
  `CANCELLED`（不是 `COMPLETED`：雙擊只完成了一半，不得標示成功；
  第一下的 `onCompleted` 只證明第一下完成，不得冒充雙擊完成）。
- 取消點（皆須 `removeCallbacks(具名 runnable)`）：模式切換、暫停、
  停止／完全停止、提醒上升沿、校正交接、方向改變、服務中斷、
  服務銷毀。**現況以上各點皆無 `removeCallbacks`**（§0.2），
  全部列為待實作（§6）。
- 第二下的 150 ms 間隔**維持現況值**（`:1575`），不得改動；
  間隔不是完成證明、不是逾時值（§2.3 的逾時另計，以工作為單位，
  不是以筆畫為單位）。

### 4.5 逾時釋放鎖定；重派發前確認上一手勢已結束

- 逾時 → `TIMED_OUT` → 釋放鎖定（§2.3）。釋放後，**新請求**可受理；
  但新請求的第 4 步檢查的是「當前無進行中工作」，逾時工作已終結，
  故可通過——此為「上一手勢已結束」的唯一合法含義。
- **禁止**以下重派發形態：逾時後自動重發同一工作；回呼未到、
  鎖定未釋放時以「冷卻已過」為由發出新工作；雙擊第二下以
  「第一下已完成」為由繞過第 4 步檢查。
- 返回／首頁／點擊的不可重複執行，由三道閘共同保證：
  (a) 單一進行中工作（§1.2-1）；(b) 冷卻＋重新武裝（現況機制，
  見 §2.1）；(c) 同一意圖單一 `gestureId`（§2.2）。
  任一道閘缺失，不得宣稱「不會重複執行」。

### 4.6 滑動起點：待完成的工作，不是永久狀態

- 起點選取後（`SWIPE` 第一段，`:1497-1505`），工作處於
  「待終點」態：起點座標＋`gestureId`＋選取時間一併記錄。
- 起點必須在以下事件時失效（進 `CANCELLED`，不得留待下次終點注視
  誤觸發）：提醒上升沿（現況 `:1082` 已清）、臉遺失、影像降級、
  暫停／停止／交接、方向改變、模式切換（切離 SWIPE 模式）、
  服務中斷／銷毀。**現況臉遺失／影像降級／模式切換不清起點**
  （§0.5 G-9），列為待實作。
- 起點有效期：**待決，不得預設。** 設計約束：不得無限期保留
  （舊起點＋新終點的組合不是使用者當下的意圖）；有效期毫秒數
  與「起點是否須重新武裝」的判定，由實作審查時定稿（待決項 T-D2，
  見 §7）。在定稿前，起點失效事件表（上一段）為硬性要求，
  有效期數字不得擅自寫入。

### 4.7 全域動作（返回／首頁）：只能記「已請求」

- `performGlobalAction` 無回呼、回傳值現況被忽略。Dispatcher 對
  全域動作只做：十步中的第 1、2、4、5 步檢查 → 發出 → 記
  「已請求、結果未知」（附 `gestureId`、種類、發出時間）→
  冷卻＋重新武裝（邊緣鎖定由控制器負責，見
  `EdgeGestureController.kt:468-475` `enterCooldown`）。
- **不得**為全域動作編造 `COMPLETED`、**不得**觸發成功閃光作為
  完成證明（現況 `:1524`／`:1529` 的閃光在發出後立即排程，
  實作時須改為「請求回饋」語義或移除，不得保留為完成回饋——
  具體形式待 UI 審查定，見 §7 待決項 T-D3）。
- 連續返回／首頁的防重複，**只**靠冷卻＋邊緣重新武裝（視線須先
  離開原觸發帶，`EdgeGestureController.kt:209-226` 的 `NONE` 分支
  解除鎖定）＋第 4 步的單一工作檢查。冷卻值取現況
  `settings.cooldownMs`（預設 1200 ms，`GazeSettings.kt:150`），
  不得為全域動作另立冷卻值。

## 5. 回呼與執行緒契約

1. 四個 `dispatchGesture` 呼叫的 `GestureResultCallback` 必須同時覆寫
   `onCompleted` **與** `onCancelled`（現況只有前者，見 §0.1）。
   兩者皆先以 `gestureId` 比對，再轉換狀態（§2.2）。
2. `onCompleted` 的現況行為（`cursorView?.post { flashClick() }`）
   保留，但**只**在 `onCompleted` 且 id 比對通過時觸發；
   `onCancelled`／逾時／中斷皆不得觸發。
3. 回呼執行緒：以主執行緒為準進行狀態轉換；若系統回呼不在主執行緒，
   先 `mainHandler.post` 回主執行緒再處理（現況 `mainHandler` 見
   `:242`）。實作時須以實機驗證確認回呼執行緒假設，**不得假設**
   回呼一定在主執行緒——本文件只定「轉換必須在主執行緒序列化」，
   不宣稱系統回呼的執行緒。
4. `dispatchGesture` 的發出呼叫本身亦在主執行緒（現況四函式皆由
   主路徑呼叫，見 §0.3）。不得在背景執行緒發出派發、不得在背景
   執行緒讀寫 Dispatcher 狀態。
5. 派發紀錄：每次狀態轉換記一筆 `Log.d`（`gestureId`、種類、
   轉換前後狀態、原因碼）。**不**寫入 `GazeLog` CSV（欄位不新增，
   見 §1.2-7），**不**向使用者顯示。

## 6. 待實作清單（PLAN D3-7 dispatch 部分，目前未做）

依據 `IMPLEMENTATION_NOTES.md` §1 出入第 27 點與 §3 已知缺口，
以下項目**目前未做**，現況 dispatch 本體原樣保留。
本設計文件不代替實作；以下每一項實作完成前，不得宣稱 D3-7 已完成。

| # | 待實作項 | 對應現況位置 |
|---|---|---|
| P-1 | 四個 `dispatchGesture` 加 `onCancelled` 覆寫（K-62） | `:1554`、`:1566`、`:1585`、`:1600` |
| P-2 | `dispatchGesture` 回傳值檢查：`false` 立即清理進 `FAILED`（§4.1） | 同上四處 |
| P-3 | `performDoubleTap` 第二下具名 `Runnable` 持有＋全部取消點 `removeCallbacks`（K-07，§4.4） | `:1573-1578`；取消點見 §4.3 表 |
| P-4 | 進行中鎖定（單一手勢規則）：第 4 步檢查＋`DISPATCHING` 態（§1.2-1、§3） | `triggerActionAt` `:1479-1517`（現況無檢查） |
| P-5 | 逾時計時與 `TIMED_OUT` 態（逾時值待決 T-D1，§2.3） | 四函式（現況無計時器） |
| P-6 | 唯一識別碼 `gestureId` 與派發紀錄（§2.2、§5-5） | 四函式（現況匿名回呼） |
| P-7 | `onInterrupt`／`onDestroy` 取消全部待執行工作，進 `SERVICE_INTERRUPTED`（§4.3） | `:1635`、`:1606-1633` |
| P-8 | 滑動起點失效事件表補齊（臉遺失／影像降級／模式切換／方向改變，§4.6） | `swipeStartPoint` 賦值點（§0.5 G-9） |
| P-9 | 全域動作「已請求」語義：閃光不得冒充完成回饋（§4.7，形式待決 T-D3） | `:1523-1529` |

**已完成、不得與上表混淆者**：控制器層面的 `Cancelled` 判決輸出
（`EdgeDecision.Cancelled`，`EdgeGestureController.kt` 內
`reset()`／檢查失敗／離開帶／方向相反／確認放棄分支）與
`cancelEdgeGesture()` 的接線（呼叫點見 §4.3 表）——此為任務書 D3-7
（控制器 Cancelled 輸出），已在 Phase 3 第 6 步落地
（`IMPLEMENTATION_NOTES.md` §1 出入第 23、27 點）。
控制器取消 ≠ dispatch 取消：前者是「不發出」，後者是
「已發出後的追蹤與清理」。

## 7. 手勢安全測試：12 項（§7.4 清單）

> 來源說明：任務書稱「§7.4 清單」，但本組可讀的先前文件中
> **未定位到**該 §7.4 的原文（`USER_JOURNEY.md` §7.4 為「失敗模式
> 與恢復路徑」，非手勢安全測試清單；`TEST_PLAN.md`／`STATE_DESIGN.md`
> 內亦無 §7.4 手勢測試清單）——**標「待定位」**。
> 以下 12 項為本設計依任務書要求編列，編號 `G-D01…G-D12`，
> 每項皆註明對應的既有測試編號（`TEST_PLAN.md` §2／§3、
> `STATE_DIAGRAMS.md` §5 G-T 項），不得與既有編號混淆。
> 自動化部分以「可注入派發函式的 Dispatcher 替身」為前提
> （`dispatchGesture` 為 Android 框架 API，依 `TEST_PLAN.md` §1.2
> 分界**不可**純 JVM 測；替身設計見 §7 末段）；
> 框架接線部分一律走實機驗證，不得以單元測試冒充。

| # | 測試 | 通過標準 | 對應既有項 |
|---|---|---|---|
| G-D01 | `dispatchGesture` 回傳 `false` 立即清理 | 回傳 `false` 後 Dispatcher 回 `IDLE`、無進行中記錄、無閃光、無逾時計時殘留；使用者重新意圖可再次派發（新 id） | PLAN D3-7；G-T15 類比（被拒後可再次觸發） |
| G-D02 | `onCancelled` 不得標示成功 | 收到 `onCancelled` 後狀態為 `CANCELLED`、無閃光、無 `COMPLETED` 紀錄；冷卻／武裝重置後新意圖可再次觸發，舊工作不自動重發 | K-62；`STATE_DESIGN.md` §3.1 `GESTURE_DISPATCHING` 列；G-T15 |
| G-D03 | 服務中斷取消全部待執行 | `onInterrupt`／`onDestroy` 時進行中工作＋已排程第二下＋滑動起點＋逾時計時全部取消，進 `SERVICE_INTERRUPTED`；遲到回呼不改變狀態、不操作已移除 overlay | §4.3；`STATE_DESIGN.md` §5 單一手勢規則 |
| G-D04 | 暫停時舊手勢不得恢復後執行 | 派發進行中（或第二下已排程）時暫停，恢復後舊工作不補發、不閃光；恢復後的新意圖走新 id、新十步 | §4.3；K-07 類比 |
| G-D05 | 逾時釋放鎖定 | 回呼永不來時，逾時後 Dispatcher 回 `IDLE`、新請求可受理；逾時紀錄寫「結果未知」，不寫成功／失敗；逾時後遲到的 `onCompleted` 只記 Log、不轉狀態、不閃光 | §2.3；待決 T-D1（逾時值） |
| G-D06 | 重派發前確認上一手勢已結束 | 進行中時的新請求被拒絕（不排隊、不覆蓋）；上一工作 `COMPLETED／CANCELLED／FAILED／TIMED_OUT` 後，新請求才可發出；同一意圖只產生一個 `gestureId` | §3 第 4 步；單一手勢規則 |
| G-D07 | 雙擊第二下可取消 | 模式切換／暫停／停止／提醒介入／交接／方向改變／銷毀任一事件在 150 ms 內發生，第二下不發出，工作進 `CANCELLED`（不標示雙擊成功）；第一下完成不冒充雙擊完成 | K-07；G-T14（模式切換取消待派發的雙擊第二下） |
| G-D08 | 返回／首頁／點擊不重複執行 | 一次意圖只發出一次 BACK／HOME／點擊；逾時、取消、狀態恢復、冷卻到期皆不觸發重發；連續兩次獨立意圖（離開帶再進入／重新 dwell）各發出一次，不多不少 | §4.5；D-3 驗收 5（E-5） |
| G-D09 | 不得以固定延遲假裝完成 | 測試替身「只回呼一半工作、另一半永不回呼」：永不回呼者不得在任何固定延遲後被記為完成；`flashClick` 只在真實 `onCompleted` 後出現 | §1.2-2；紅線 |
| G-D10 | 滑動起點失效 | 起點選取後，臉遺失／影像降級／模式切換／提醒／方向改變任一發生，起點清除；之後的終點注視不觸發滑動，須重新選起點 | §4.6；G-9 |
| G-D11 | 單一進行中工作 | dwell 觸發派發的同時，眨眼結算與邊緣執行判決同時到達：只有第一個請求發出，其餘被拒絕並記原因；三者皆不排隊 | §3 第 4 步；`STATE_DESIGN.md` §5 |
| G-D12 | 全域動作不標示完成 | BACK／HOME 發出後，Dispatcher 不進 `COMPLETED`、紀錄寫「已請求、結果未知」；冷卻＋重新武裝未滿足前，同一意圖不得再次發出 | §4.7；D-3 驗收 3（E-3，未完成確認不返回／不回首頁）的派發層對應 |

**測試替身設計（自動化前提）：** Dispatcher 的派發函式以可注入
介面傳入（真實服務傳 `dispatchGesture` 包裝、測試傳替身：
可控回傳值、可控回呼時機、可記錄發出次數與座標）。
替身測試只驗 Dispatcher 的狀態機邏輯（§2／§3／§4），**不驗**
Android 框架的真實派發行為；真實派發的端到端行為（手勢是否
真的落到目標 App）只走實機驗證（`TEST_PLAN.md` §3.2 E-1…E-10
與 §3.1 R-1…R-8 的相關項），不得互相代替。
在替身介面落地前，G-D01…G-D12 中標「自動化」者為**待整合**，
不得宣稱為已有測試。

**與既有測試的關係：** `EdgeGestureControllerTest` 現有 25 項
（`EdgeGestureControllerTest.kt`，含 `edgeBandScalesWithDensity`、
`relock_stayInZoneNoRetrigger_exitThenRetrigger` 等）驗的是
**控制器判決**，不是 Dispatcher；兩套測試不得互相代替。
`TEST_PLAN.md` §2.2 E-T01…E-T14 亦同。

## 8. 待決項與未定位來源

| # | 項目 | 狀態 |
|---|---|---|
| T-D1 | 逾時長度（毫秒數）。約束見 §2.3，不得預設固定值 | 待決 |
| T-D2 | 滑動起點有效期（毫秒數）與起點重新武裝判定（§4.6） | 待決 |
| T-D3 | 全域動作發出後的回饋形式（現況閃光不得冒充完成，改為何種形式） | 待決（UI 審查） |
| T-D4 | Dispatcher 的實作落點：新增獨立檔案、或收斂進服務內部——本文件只定行為契約，不定檔案落點 | 待決（實作審查） |
| — | 任務書 §7.4 清單原文 | **待定位**（見 §7 來源說明） |
| — | 逾時／中斷後的使用者可見提示文字（如需） | 待決（不得編造提示文字；無提示亦為合法設計——取消本身不產生假象即已滿足） |

## 9. 紅線重申

- 本文件只新增設計文件，**未改任何現有檔案**；版本號維持
  `versionCode = 13`／`versionName = "0.5.9"`；不改
  `.github/workflows/`；不推送、不 commit、不發版。
- 五個不能動維持（`PLAN.md` §0.2）：九點資料流、22 維特徵契約、
  `requestPauseForCalibration` 協議、`buildConfig = true`、
  `noCompress += "task"`。本設計不碰其中任何一項。
- 禁假數據、禁假功能：無回呼即無完成；無量測即無品質分數；
  門檻與毫秒數未決者一律標待決，不編數字。
- PLAN D3-7 dispatch 部分目前未做（§6），本文件不得被引用為
  「已實作」的證據；實作完成須另經單元測試（替身）＋實機驗證
  （G-D01…G-D12）兩道門檻。
