# EyeControl 工程報告（STEP 7 整理）

> 性質：STEP 7 整理文件。**只寫文件，不改程式碼、不改版本號、不改 `.github/workflows/`、
> 不推送、不建 PR、不發 Release、不上傳 APK、不碰 secrets／keystore。**
> 本報告不作發布判定（發布判定見 `docs/release-readiness.md`：**禁止發布**），
> 不作 DCL 重評（DCL 標籤以 `docs/dcl-mapping.md` 為準，本報告不擅自更改），
> 不新增 DCL 題目、不問新功能選擇題。
>
> 基準：工作樹 `~/workspace/eyecontrol-p3`（repo 複本，無 `.git/`）；遠端 `main` HEAD
> `c6b80964`（v0.5.9）；工作樹版本 `versionCode = 13`、`versionName = "0.5.9"`
> （`app/build.gradle.kts:29-30`，遠端同一檔案亦同，blob SHA 比對核實，
> 見 `docs/ci-branch-plan.md` §2）。
>
> 證據位階（全報告通用）：**已閱讀 ≠ 已提案 ≠ 已改碼 ≠ 靜態通過 ≠ 單元測試通過
> ≠ CI 通過 ≠ 實機通過 ≠ 發布通過**。凡無證據者寫「尚未驗證」，不得以靜態檢查、
> 他人記載或本報告本身冒充驗證通過。行號為本報告撰寫時點的工作樹行號；
> 若工作樹被同時寫入，須重新快照核對。
>
> 複用文件（不得重做已完成的分析，本報告只引用、摘要與指出漂移）：
> `docs/engineering-baseline.md`、`architecture-map.md`、`function-matrix.md`、
> `risk-register.md`、`dcl-mapping.md`、`step3-fix-log.md`、`privacy-data-flow.md`、
> `privacy-copy-audit.md`、`privacy-security-report.md`、`accuracy-baseline-plan.md`、
> `verification-ledger.md`、`device-diagnostic-evidence.md`、`ci-validation-report.md`、
> `device-acceptance-matrix.md`、`release-readiness.md`、`engineering-remediation-plan.md`，
> 以及 `~/workspace/eyecontrol-phase3/`（`PLAN.md`、`TEST_PLAN.md`、`IMPLEMENTATION_NOTES.md`）。
> 本報告撰寫日期：2026-11-23（台北時間）。

## 1. 範圍與方法

本階段（MASTER 工程建置與驗證規格 STEP 1～STEP 7）只做四件事：確認現況、
建立穩定基礎、建立準確度基準、依數據優化；智慧吸附／混合辨識（DCL-24～40）
只對照不實作；無實機數據不得更換演算法、改濾波／模型／預測參數、改吸附距離
或信心閾值（見 `dcl-mapping.md` §1 R-6 與任務書 STEP 6 規格）。

- STEP 1 盤點：`engineering-baseline.md`（基準）、`architecture-map.md`、
  `function-matrix.md`、`risk-register.md`、`engineering-task-matrix.md`（30 項）。
- STEP 2 對照：`dcl-mapping.md`（DCL-01～DCL-46，見 §2）。
- STEP 3 修復：`step3-fix-log.md`（F-1～F-8、V-1～V-3、提案清單，見 §3）。
- STEP 4 測試：harness 重跑（見 §6；本報告執行者於 2026-11-23 親自重跑）。
- STEP 5 CI 準備：`ci-branch-plan.md`（分支推送提案，未推送；見 §7）。
- STEP 6 準確度基準：`accuracy-baseline-plan.md`（計畫先行，量測未做；見 §9）。
- STEP 7 整理：本報告。

## 2. DCL 對照表（引用 `dcl-mapping.md` 數字，不重評）

### 2.1 統計（`dcl-mapping.md` §2，STEP 3 更新後）

| 狀態 | 數量 | 編號 |
|---|---|---|
| 已實作 | 2 | DCL-02、DCL-21 |
| 部分實作 | 16 | DCL-01、DCL-03、DCL-04、DCL-07、DCL-08、DCL-09、DCL-10、DCL-11、DCL-12、DCL-14、DCL-17、DCL-18、DCL-19、DCL-20、DCL-22、DCL-23 |
| 未實作 | 26 | DCL-06、DCL-15、DCL-16、DCL-24、DCL-25、DCL-26、DCL-27、DCL-28、DCL-29、DCL-30、DCL-31、DCL-32、DCL-33、DCL-34、DCL-35、DCL-36、DCL-37、DCL-38、DCL-39、DCL-40、DCL-41、DCL-42、DCL-43、DCL-44、DCL-45、DCL-46 |
| 實作不符合規格 | 2 | DCL-05、DCL-13 |
| 無法確認 | 0 | — |
| **合計** | **46** | — |

P-01（補 DCL-02／03／04 規格文字）**已解決**：擁有者已於 2026-11-23 補齊三項
正式規格文字（皆選 A、已正式確認，非新增需求），`dcl-mapping.md` 已按新文字
重新對照 DCL-02（已實作，實機驗收未做）、DCL-03（部分實作）、DCL-04（部分實作）。

### 2.2 逐項總表（狀態取自 `dcl-mapping.md` §3；一句話摘要取自同文件，行號見原文）

| DCL | 狀態 | 摘要 |
|---|---|---|
| DCL-01 完整校正流程可靠性 | 部分實作 | 九點單目標採樣等皆有；但無品質儲存門檻（差級照樣存檔），取消目標僅常數預留 |
| DCL-02 左右邊緣操作開關 | 已實作 | 左右獨立開關＋總開關五鍵獨立保存，總關全停、重開恢復原分項狀態；實機驗收未做 |
| DCL-03 K-12 隱私與資料上傳聲明 | 部分實作 | 資料流稽核文件已覆蓋 (1)～(5) 項；撤回機制部分完成、聲明未改、網路流量驗證未做 |
| DCL-04 D3-7 手勢派發取消與回呼管理 | 部分實作 | 集中派發守衛已在 STEP 3 工作樹實作接線（逾時排程未接線）；CI／實機驗證未做 |
| DCL-05 重新啟動恢復 | 實作不符合規格 | 現況行程重建後無條件自動恢復追蹤，旗標未持久化；與「可自動嘗試恢復＋限制條件」方向相反 |
| DCL-06 Camera Binding 錯誤 | 未實作 | 七態狀態機不存在；僅有 `closed` 旗標、幀槽 generation 片段與僅記 Log 的綁定失敗 |
| DCL-07 邊緣區域尺寸 | 部分實作 | 50dp 換算＋短邊 15% 上限已寫入控制器；但未讀 `densityDpi`／`xdpi`／`ydpi`、未處理 Insets／保護殼，實機量測未做 |
| DCL-08 校正期間 UI | 部分實作 | 進度／提示／報告／ERROR 頁與服務暫停守衛皆有；但採樣期無相機權限檢查，ASK／ERROR／REPORT 的注視選擇在校正成功前即使用舊模型判讀 |
| DCL-09 追蹤恢復穩定性 | 部分實作 | 引擎重建與 `startEngine` 內濾波重置皆有；但臉部重現時主路徑不重置濾波器、不清除舊手勢殘留、無穩定度門檻即解鎖 |
| DCL-10 左右邊緣寬度 | 部分實作 | 左右獨立開關＋總開關＋恢復預設值皆有；但邊緣「寬度」本身不可調、無安全最小／最大值 |
| DCL-11 追蹤中斷自動恢復 | 部分實作 | 臉遺失分支停止新操作、清 dwell／邊緣、灰圈提示皆有；但（STEP 3 前）未清眨眼狀態、滑動起點、濾波歷史，雙擊第二下未取消 |
| DCL-12 多指標追蹤品質 | 部分實作 | 各指標散見於邊緣檢查、影像降級、校正評估；但無統一品質狀態與三態，閾值皆未校準，且主路徑無「不得抵銷」規則 |
| DCL-13 眨眼點擊 | 實作不符合規格 | （STEP 3 前）眨眼點擊預設開啟、僅以 150～600ms 時間窗判定；無獨立開關、無個人化意圖校正、無 FP／FN 評估 |
| DCL-14 停留點擊重新觸發 | 部分實作 | 面板／把手有武裝重啟，`DwellTracker` 有遲滯式衰減；但無五態狀態機，螢幕停留重啟只靠冷卻時間，無明確離開確認 |
| DCL-15 眨眼與停留點擊仲裁 | 未實作 | 無 Click Arbiter；眨眼與停留兩路徑各自直接呼叫 `triggerActionAt`，僅共用冷卻時間戳 |
| DCL-16 統一手勢仲裁 | 未實作 | 無統一 GestureArbiter；僅邊緣控制器有局部狀態機，各 `perform*` 直接呼叫 `dispatchGesture()` |
| DCL-17 手勢衝突 | 部分實作 | 冷卻＋邊緣 COOLDOWN 鎖定有部分效果；但無進行中手勢鎖定，衝突意圖丟棄規則未建模，雙擊第二下以延遲排隊執行（STEP 3 前） |
| DCL-18 安全事件最高優先級 | 部分實作 | 暫停／臉遺失／停止／ERROR 各有分支處理；但（STEP 3 前）`onInterrupt()` 為空實作，`pauseTracking()` 不清除待執行意圖，恢復時無完整重查 |
| DCL-19 獨立緊急停止 | 部分實作 | 觸控停止入口（Dashboard 停止對話框）存在；但無音量鍵操作、無自訂、無獨立於一般暫停的緊急停止 |
| DCL-20 緊急停止後恢復 | 部分實作 | 允許自動恢復（DCL-20 覆蓋 DCL-19 原禁止）；但恢復驗證僅 3 項檢查，手點恢復繞過臉部／眼睛檢查 |
| DCL-21 自動恢復倒數 | 已實作 | 不需要額外倒數；其前提「所有安全條件」是否完備屬 DCL-20 的缺口，不在此項重複扣分 |
| DCL-22 一般暫停與緊急停止 | 部分實作 | 一般暫停路徑與誤觸防護部分存在；緊急停止語義區分未建立（詳見 `dcl-mapping.md` §4.2） |
| DCL-23 操作安全區域 | 部分實作 | 詳見 `dcl-mapping.md` §4.2（本報告不轉載細節，狀態引用同文件） |
| DCL-24～DCL-40（游標與智慧目標吸附，共 17 項） | 未實作（全部） | 程式中無任何智慧吸附／混合辨識機制；本階段只做對照（R-6） |
| DCL-41～DCL-46（敏感畫面與隱私，共 6 項） | 未實作（全部） | 程式中無任何視覺辨識／敏感畫面判斷機制；現有影像上傳管線與 DCL-41／43 的清除要求無對應控制點 |

### 2.3 技術矛盾（`dcl-mapping.md` §5，T-01～T-14）

T-01 眨眼預設開啟與規格牴觸；T-02 重啟無條件恢復方向相反；T-03 無品質儲存門檻；
T-04 九態設計與七態規格不一致（七態為準）；T-05 無統一仲裁＋雙擊排隊不可取消；
T-06 臉遺失未清三者＋無穩定門檻；T-07 無音量鍵緊急停止＋手點恢復繞過檢查；
T-08 50dp 未經實機量測；T-09 全部品質閾值未校準；T-10 DCL-24～46 未實作但上傳管線
無對應控制點；T-11 首次校正注視選擇無法判讀；T-12 邊緣寬度不可調；
T-13 `onInterrupt` 空實作＋暫停不清意圖；T-14 點擊路徑無敏感欄位守衛。
各項的最小方案與待批編號見 `dcl-mapping.md` §5～§6；STEP 3 已處理的部分見 §3
（F-1 對 T-01 最小部分、F-2 對 T-06、F-3 對 T-05／T-13、F-7 對 T-02 最小部分、
F-8 對 T-05 派發守衛部分），其餘仍待批（見 §4）。

### 2.4 文件漂移聲明（審查者必讀）

`dcl-mapping.md` 的 §2 統計與 DCL-02／03／04 的詳述已按 STEP 3 更新，
但 DCL-05／11／13／17／18 等項的**細節證據段仍描述 STEP 3 前的程式狀態**
（例如 DCL-13 細節段仍寫「全樹 grep `blinkEnabled` 零命中」，而 STEP 3 已新增
`blinkClickEnabled`，見 §3 F-1；DCL-17 細節段仍寫雙擊第二下「匿名 postDelayed」，
而 STEP 3 已具名化，見 §3 F-3）。**本報告不因此擅自更改任何 DCL 標籤**：
F-1～F-8 皆為「最小部分」修復，DCL-13 的個人化意圖校正／FP／FN 評估、
DCL-05 的持久化與完整重查等核心子項仍未落地，標籤維持「不符合」是正確的；
但審查程式時須以 `step3-fix-log.md` 與程式碼現況為準，不得以 `dcl-mapping.md`
的舊細節段判定 STEP 3 程式「不存在」。後續 DCL 重評須另立任務、逐子項重查，
不得在本報告中順帶完成。

## 3. 修復清單（STEP 3，F-1～F-8＋V-1～V-3）

全部修復詳見 `docs/step3-fix-log.md`（行號為該文件完成時的工作樹行號；
本報告抽驗的關鍵行號見 §3.9）。**已改碼 ≠ 編譯通過 ≠ CI 通過 ≠ 實機通過。**

### 3.1 F-1 — 眨眼點擊：預設關閉＋獨立開關（DCL-13 最小部分，T-01）

- 內容：`GazeSettings.kt:26` 新增 `blinkClickEnabled: Boolean = false`
  （`:116` 存檔、`：206` 讀取，預設關）；新增純判定 `GuardDecisions.kt:18-35`
  `shouldTriggerBlinkClick`（開關＋時間窗＋游標有效＋冷卻＋非暫停＋非提醒，
  原內聯條件逐項收斂，無增刪）；`GazeAccessibilityService.kt:618-631` 改呼叫純判定；
  `SettingsActivity.kt` 四處開關接線＋`activity_settings.xml` 新增開關列
  （副標明示「預設關閉；開啟後以閉眼時間判定點擊，自然眨眼可能誤觸」）。
- 未做：個人化意圖校正、品質評估、FP／FN 評估、啟用閘門（提案 P-05）。
- 相容性：此前根本無此 key，**所有**既有使用者升級後眨眼點擊皆為關閉，
  須以發版說明告知（見 `step3-fix-log.md` F-1）。
- 驗證方式：harness `GuardDecisionsTest` 6 項＋`GazeSettingsDefaultsTest`
  （見 §6）；Android 側（開關顯示／儲存／即時生效）走 CI＋實機，**尚未驗證**。

### 3.2 F-2 — 臉遺失殘留清除（DCL-11／DCL-09，T-06）

- 內容：臉遺失分支（`GazeAccessibilityService.kt:559-570`）在既有清除之後新增
  `isBlinking = false`、`blinkStartTime = 0L`、`swipeStartPoint = null`、
  `swipeState` 鏡像清零＋`invalidate()`、`filterX.reset()`、`filterY.reset()`、
  待發雙擊第二下取消。`hasValidCursor` 不清（游標留原地變灰為有意顯示語義）。
- 驗證方式：靜態核對＋CI 編譯＋實機（臉移出／移入後舊意圖不觸發等）；
  harness 無法覆蓋服務分支，不偽造測試。**尚未驗證**（CI／實機）。

### 3.3 F-3 — 雙擊具名化＋中斷／暫停清除待執行意圖（DCL-18／DCL-17，T-05／T-13）

- 內容：`pendingDoubleTapSecond: Runnable?` 具名持有（`:266`）；
  `cancelPendingGesture()`（`:1328-1346`）＋`cancelPendingDoubleTap()`；
  `performDoubleTap`（`:1657-1670`）先取消舊第二下再以具名 Runnable 排隊
  （150 ms 沿用現況值）；`onInterrupt()` 由空實作改為只清待執行意圖
  （`:1729-1734`）；`pauseTracking()`／`stopTracking()`／`stopCompletely()`／
  `requestPauseForCalibration` 回呼／`onDestroy()` 皆接清除。
- 明確未做（F-8 之前）：統一 Arbiter、全部 dispatch 收斂、單一飛行中鎖定、
  四 dispatch 的 `onCancelled`——其中狀態機核心與四處接線已改列 F-8，
  逾時排程與裝置驗證仍屬提案（P-07／P-04T）。
- 驗證方式：靜態核對＋CI 編譯＋實機（150 ms 內暫停／停止／中斷，第二下不執行）。
  **尚未驗證**（CI／實機）；harness 無法覆蓋，不偽造測試。

### 3.4 F-4 — 撤回同意：CSV 當下檔案丟棄（隱私 R-1 部分，見 §11）

- 內容：`settingsListener`（`GazeAccessibilityService.kt:65-84`）先取舊值、重載後
  以 `GuardDecisions.shouldDiscardCurrentFileOnRevoke`（`:43-49`）判定，
  僅「開 → 關」轉換時呼叫 `GazeLog.close(upload = false)`（既有語義，
  `GazeLog.kt:139-150`，關檔不提交上傳、重置 `pendingUpload`）。
  影格路徑未動（既有執行時重查已使排隊中未執行影格不上傳）。
- 未做：已提交至 `uploadExecutor` 的任務取消、已上傳伺服端資料刪除（P-R1b）。
- 驗證方式：harness 真值表（4 組合，`GuardDecisionsTest` 內）＋靜態核對＋實機
  （Supabase 端／本機檔案＋Logcat 對照）。**尚未驗證**（CI／實機）。

### 3.5 F-5 — `uploadFile` 顯式 flavor 守衛（隱私 R-2 部分，見 §11）

- 內容：`GazeLog.kt:174-185`，`uploadFile` 頂部加
  `if (!BuildConfig.REMOTE_MONITOR_ENABLED) return`（置於既有空白端點檢查之前）。
  呼叫點已窮舉共 2 處（`close()`、 `uploadFrame()`），守衛覆蓋兩路徑。
  側載版（`REMOTE_MONITOR_ENABLED=true`）行為一字不變；Play 版即使呼叫端閘門
  被繞過亦直接返回、不發請求。
- 未做／未宣稱：金鑰常數仍在 APK 內（程式碼共用），未宣稱已移除；
  source set 隔離未做。
- 驗證方式：靜態核對＋CI（兩 variant 編譯）＋實機／merged manifest
  （Play 版無 INTERNET 權限且無上傳請求，網路抓包）。**尚未驗證**。

### 3.6 F-6 — 備份排除規則：僅三類診斷目錄（隱私 R-3 部分，見 §11）

- 內容：新增 `backup_rules.xml`（`full-backup-content`）與
  `data_extraction_rules.xml`（Android 12+，`cloud-backup` 與 `device-transfer`
  同範圍），皆排除 `gaze-logs`／`verify-frames`／`crash-reports`
  （目錄名與程式常數逐一對照）；`AndroidManifest.xml:15-17` 加兩個屬性；
  `allowBackup` 維持 `true`，prefs（校正模型、條件歷史、使用者設定）一律不排除。
- 未做：條件歷史是否排除（P-R3b，任務書 R-3 限定範圍僅三類診斷目錄，
  不擅自擴大）；舊備份副本不受新規則影響（平台行為）。
- 驗證方式：本任務已做 XML 良好性解析（4 檔 parse 通過）＋目錄名常數對照；
  待做 CI 編譯、merged manifest 檢查、實機備份／還原測試。**尚未驗證**（後三項）。

### 3.7 F-7 — 重啟恢復重查（DCL-05 最小部分，無持久化格式，T-02）

- 內容：`onServiceConnected`（`GazeAccessibilityService.kt:340-381`）在
  `deriveInitialFlowState()` 之後、`startEngine()` 之前加重查分支：
  `isServiceEnabled(this)`（既有精確比對）與相機權限檢查；任一未通過即不啟動引擎，
  `Log.w`＋`syncFlowState(Failure(原因), ERROR)`，原因文字明示排查方向。
  僅新增一行 import（`PackageManager`）。
- 未做：`userStopped`／`inRecoveryMode` 持久化（K-30 維持現況，P-03）；
  `restart-recovery-policy.md` 十步流程落地。
- 回歸風險：`isServiceEnabled` 在綁定時點理論上恆真，但若某裝置／系統版本
  尚未寫入設定字串，將誤判 ERROR（使用者仍可手點恢復；須列入實機驗收）。
- 驗證方式：靜態核對＋CI 編譯＋實機（權限授予／收回兩種狀態下重啟服務）。
  **尚未驗證**（CI／實機）。

### 3.8 F-8 — 集中派發守衛（DCL-04，T-05 派發部分）

- 內容：新增純 Kotlin `GestureDispatchGuard.kt`（零 Android 依賴）：
  IDLE／IN_FLIGHT 兩態、嚴格遞增永不重用 `Long` ID、唯一終端轉換點
  `finishTerminal`（清理結構上只執行一次）、`begin()` 搶占舊手勢
  （舊 ID 失效並計一次 `SUPERSEDED` 清理）、`onDispatchReturned`（拒絕即終端）、
  `onCompleted`／`onCancelled`／`onTimeout`（僅進行中 ID 生效，過期一律忽略）、
  `onInterrupted()`（回 IDLE、等待新 `begin()`、永不重發）。
  服務端單一欄位 `dispatchGuard`（`:270`）；四處 `dispatchGesture` 呼叫點
  （`:1646,1667,1703,1727`）同一模板接線（`begin()` 取 ID → 包裝回呼，
  `onCompleted` 只在守衛回傳 true 時閃光、新增 `onCancelled` 覆寫 →
  `onDispatchReturned(token, accepted)`）；`cancelPendingGesture()` 首行與
  `onDestroy()` 加 `onInterrupted()`。
- 邊界：筆畫內容／時長（0／60／500／800 ms）一字不動；不新增逾時計時器
  （`onTimeout` 僅決策函式，排程未接線，P-04T）；不新增佇列／重試／重發。
  系統側進行中的手勢本身無法由 App 取消（`dispatchGesture` 無取消 API），
  守衛只做狀態層隔離，**不得宣稱為系統級取消**。
- 驗證方式：harness `GestureDispatchGuardTest` 11 項（見 §6）＋靜態窮舉
  （`dispatchGesture` 仍為四處，無旁路）＋CI 編譯＋實機（取消／搶占／拒絕情境）。
  **CI／實機尚未驗證**。

### 3.9 V-1～V-3（已核實、未改碼）與抽驗

- V-1（T-11）：首次校正詢問／重試頁觸控路徑已可用（真實 `MaterialButton`），
  無舊模型時 `onChoiceSample` 直接返回、不做注視判定；不新增 UI。
  首次校正純眼控可達性缺口仍在，屬 P-02／D-4 範圍。
- V-2（DCL-02 八項）：程式行為已核實符合，未改碼；新增純測試
  `EdgeSwitchRestoreTest` 3 項（見 §6）。六項驗收情境的實機驗證未做。
- V-3（DCL-03 稽核覆蓋）：稽核文件已覆蓋 (1)～(5) 項，唯一缺口為伺服端
  保留期限無法由本樹驗證；不改任何聲明文案；網路流量驗證未做。
- 本報告抽驗（2026-11-23，工作樹現況）：`blinkClickEnabled` 預設 `false`
  （`GazeSettings.kt:26`）；`CANCEL_TARGET_RESERVED*` 4 個常數在
  `CalibrationActivity.kt:1117-1123`，除定義與註解提及外零功能引用；
  `dispatchGuard` 欄位在 `GazeAccessibilityService.kt:270`，四處接線與
  `onCancelled` 覆寫存在（全檔 `onCancelled` 8 命中，含守衛呼叫與覆寫）；
  `DIM = 22`（`GazeFeatureExtractor.kt:67`）；`buildConfig = true`（`:111`）、
  `noCompress += "task"`（`:115`）皆在 `app/build.gradle.kts`。
  抽驗只證明靜態存在，不證明行為正確。

## 4. 未完成清單

### 4.1 提案待批（STEP 3 未實作，須批准／決定後才動手；詳見 `step3-fix-log.md` §3 與 `dcl-mapping.md` §6）

| # | 提案 | 未動手原因（摘要） |
|---|---|---|
| P-02 | D-4 剩餘決定（形式／秒數／位置／判定機制）與 DCL-01／08 相容方式；D1-9 維持僅常數預留 | 決策未定；`d4-decision-record.md` 23 項未決、已決定 0 項 |
| P-03 | 重啟恢復持久化格式與寫入時機（D-8）；`restart-recovery-policy.md` 十步流程按 DCL-05 改寫後落地 | 持久化格式未決，不得發明 |
| P-04 | 校正品質儲存門檻（是否取 60／120／200 px、是否另立儲存門檻） | 需決定門檻值本身；D-12 未決 |
| P-04T | 手勢派發逾時計時器排程（逾時毫秒值、排程位置；逾時只清理、不重發） | 須決定毫秒值與排程機制；守衛 `onTimeout` 決策已實作並已測試 |
| P-05 | 眨眼點擊個人化意圖校正方法、品質評估方法、FP／FN 評估協議與啟用門檻 | 需發明方法與門檻數值；F-1 的開關開啟不等於閘門通過 |
| P-06 | DCL-06 七態相機狀態機設計與實作（取代九態提案） | 設計待批；本輪未動相機管線 |
| P-07 | 統一手勢仲裁／Dispatcher 剩餘部分：逾時計時器排程、進行中鎖定語義的裝置驗證、`onInterrupt` 完整生命週期處理 | 逾時排程須毫秒值；鎖定語義須實機驗證 |
| P-08 | 臉遺失／恢復清除範圍（是否清 `hasValidCursor`）與穩定門檻量測值 | 顯示語義須一併決定、門檻值須量測 |
| P-09a | 音量鍵緊急停止方案（是否全域攔截、裝置允許範圍、自訂方式） | 需先確認 Android／裝置允許範圍 |
| P-09b | 手點恢復是否須與眼控 dwell 共用臉部／眼睛凍結語義；點擊路徑敏感欄位守衛 | 前置敏感判斷（DCL-44）不存在 |
| P-10a | 邊緣帶寬（50dp）與品質門檻的實機量測計畫（E-9／E-10）、正式預設值 | 量測未做，不得定正式預設 |
| P-10b | 邊緣「寬度」可調設計（粒度、範圍、安全最小／最大值、預設） | 新設定項＋UI＋範圍值皆須設計與批准 |
| P-11 | DCL-24～46 未來實作整體設計（吸附／混合辨識／敏感辨識／清除／降級／恢復） | 本輪不實作（R-6）；辨識管線須與診斷／監控管線分離 |
| P-DEV-1 | 濾波前極端原始預測守衛（主路徑） | 範圍值、拒絕後行為與量測方法皆須決定；**不得改預測演算法、不得改濾波參數**；證據為裝置診斷解讀，未經程式交叉驗證（見 §8.3） |
| P-R1b | 已提交上傳任務取消 handle、已上傳伺服端資料刪除路徑、影格本機刪除 UI | 需新增取消／刪除機制設計（非最小變更） |
| P-R3b | 條件歷史（`condition_history` prefs）是否納入備份排除；備份還原實測 | 擴大範圍須另行決定 |

另：`dcl-mapping.md` §6 的 P-01 已解決（見 §2.1）；P-07 在 `dcl-mapping.md`
的編號含 Dispatcher 實作整體（含已落地的 F-8 部分），與 `step3-fix-log.md`
的 P-07（剩餘部分）範圍表述不同，實質一致：守衛狀態機與四處接線已落地，
逾時排程與裝置驗證仍待批。審查時以兩文件的「已落地／待批」分界為準，
不得以 P-07 的存在否定 F-8，也不得以 F-8 宣稱 P-07 已完成。

### 4.2 功能層未完成（程式不存在或未接通）

- D1-9 校正取消目標：僅 4 個常數預留、零引用、無事件、無視覺元素、
  九點座標未引用（`CalibrationActivity.kt:1117-1123`）；D-4 未決，
  取消功能不存在（見 §3.9 抽驗）。
- D3-7 dispatch 部分的逾時排程：未接線（P-04T）。
- DCL-06 七態相機狀態機、DCL-15／16 統一仲裁、DCL-24～40 智慧吸附／混合辨識、
  DCL-41～46 敏感畫面判斷：皆未實作（見 §2.2）。
- 品質儲存門檻、眨眼啟用閘門、音量鍵緊急停止、邊緣寬度可調：皆未實作。
- K-30（`userStopped`／`inRecoveryMode` 持久化）：未修，維持現況。
- 文案：K-12 絕對表述三處（設定頁對話框、主畫面頁尾、`strings.xml`
  `accessibility_desc`）未改；`STEP_CAMERA`／歡迎頁殘餘仍在。
  文案改寫須 DCL-11 批准後（`dcl-mapping.md` 寫法）／後續批次，
  本階段未改任何聲明（V-3）。

### 4.3 五個不能動維持（STEP 3 結束時點核對）

九點資料流（`SETTLE_MS`／`SAMPLES_PER_POINT`／`KEEP_RATIO` 常數與順序）、
22 維特徵契約（`DIM = 22`）、`requestPauseForCalibration` 協議
（簽名與 `===` 身分確認、回呼順序；僅回呼內加清意圖一行，協議本體一字不動）、
`buildConfig = true`、`noCompress += "task"`——皆維持。
STEP 3 期間 `CalibrationActivity.kt` 全檔未動（相對 Phase 3 工作樹）；
相對遠端 HEAD，該檔的差異全部來自 Phase 3（見 `ci-branch-plan.md` §2.3，
+741／−43 行量級）。D1-9 絕未動：4 個預留常數維持僅常數預留。
版本維持 `versionCode = 13`／`versionName = "0.5.9"`；`.github/workflows/` 未動；
未讀取、未寫入任何 secrets／keystore。

## 5. 功能完成度總覽（回答 §13 Q1～Q3 的依據）

- Phase 3 實作：29 項（D1-1…D1-8、D1-10…D1-16、D2-1…D2-6、D3-1…D3-8）
  皆已寫程式，一律「已實作但尚未驗證」（全量 Android 編譯未驗證、留待 CI；
  實機驗證全未做）。D1-9 不在 29 項內（僅預留）。D3-7 的 dispatch 部分
  在 Phase 3 時未做，STEP 3 以 F-3／F-8 補了守衛層與接線（逾時排程除外），
  其狀態見 §3.3／§3.8——`release-readiness.md` 理由 5（「D3-7 dispatch 未做」）
  撰寫於 STEP 3 之前，審查時須以 STEP 3 現況為準，但不得以守衛層宣稱
  dispatch 取消已「實機驗證」（CI／實機皆未做）。
- 核心功能（`function-matrix.md`，STEP 1 盤點時點）：CameraX／1280×720 偏好策略／
  MediaPipe Landmarker／22 維特徵／Ridge 模型／OneEuroFilter／九點校正／
  品質評估／三情境設定檔／臉距補償／方向補償／停留點擊／眨眼點擊／
  AccessibilityService／手勢派發／浮動面板／邊緣操作／前景服務／追蹤中斷提示／
  設定介面／診斷紀錄／更新檢查／雙 flavor／交接協議，皆為「原始碼存在＋實際呼叫」
  （靜態），實機驗證全部「尚未驗證」。STEP 3 後，眨眼點擊多了獨立開關、
  手勢派發多了守衛層、診斷／監控多了撤回與 flavor 守衛與備份排除，
  其餘核心功能無 STEP 3 改動。
- 只有 UI／設計文件、未接通者（不得寫成已完成）：
  (1) D1-9 取消目標（僅常數）；(2) 智慧目標辨識（不存在，全樹 grep 零命中）；
  (3) 追蹤品質引擎（無具名模組；品質判定分散於各分支，無統一狀態／分數）；
  (4) 首次校正注視選擇（無舊模型時無法判讀，只剩手點／系統返回）；
  (5) 設計文件 TARGET：九態相機狀態機、統一 GestureDispatcher 七態、
  品質層七態、`restart-recovery-policy.md` 十步流程——皆為設計，**未實作**，
  不得以文件存在認定程式已實作（`dcl-mapping.md` 判定規則 2）。
- 僅 UI 存在但行為缺口者：校正報告頁等級（門檻為經驗值、差級仍存檔）；
  邊緣確認目標（幾何與寬限為版面選擇、門檻未校準）；提醒目標（preempt 優先序
  已接通，但提醒期間的全部拒絕路徑須實機對照）。

## 6. 測試結果（本報告執行者親自重跑，2026-11-23）

### 6.1 執行方式與範圍

本機 Gradle 因沙箱攔截 JVM loopback 永遠跑不起來（已實證，見
`~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md` §0），故本機 gate 走
kotlinc＋JUnitCore harness（`~/workspace/eyecontrol-toolchain/harness/run-pure-tests.sh`），
只驗純 Kotlin 類別（不碰 Android API 的檔案）與其單元測試。
Android 框架類別（服務／Activity／引擎／overlay／`GazeLog` 檔案寫入與上傳／
備份規則）不在 harness 範圍；`GazeModel` JSON 往返與 prefs 讀寫在純 JVM 下為
stub，不在自動化範圍，須走實機。

### 6.2 本報告執行者的重跑結果（證據，非引用）

全量命令（2026-11-23，台北時間 12:33 前後執行）：

```bash
~/workspace/eyecontrol-toolchain/harness/run-pure-tests.sh \
  com.example.eyecontrol.AppFlowStateTest \
  com.example.eyecontrol.ConditionHistoryTest \
  com.example.eyecontrol.DwellTrackerTest \
  com.example.eyecontrol.EdgeGestureControllerTest \
  com.example.eyecontrol.EdgeSwitchRestoreTest \
  com.example.eyecontrol.GazeEvaluatorTest \
  com.example.eyecontrol.GazeModelMathTest \
  com.example.eyecontrol.GazeSettingsDefaultsTest \
  com.example.eyecontrol.GestureDispatchGuardTest \
  com.example.eyecontrol.GuardDecisionsTest \
  com.example.eyecontrol.OneEuroFilterTest \
  com.example.eyecontrol.RecoveryGateTest
```

輸出：

```
COMPILE_OK
JUnit version 4.13.2
OK (141 tests)
```

結束碼 0。另逐類單獨重跑（同一 harness、同一工作樹、同一日），每類皆
`COMPILE_OK`，計數如下（合計 141，與全量一致）：

| 測試類別 | 項數 | 測試類別 | 項數 |
|---|---|---|---|
| `AppFlowStateTest` | 29 | `GazeSettingsDefaultsTest` | 4 |
| `ConditionHistoryTest` | 22 | `GestureDispatchGuardTest` | 11 |
| `DwellTrackerTest` | 11 | `GuardDecisionsTest` | 6 |
| `EdgeGestureControllerTest` | 25 | `OneEuroFilterTest` | 7 |
| `EdgeSwitchRestoreTest` | 3 | `RecoveryGateTest` | 6 |
| `GazeEvaluatorTest` | 7 | `GazeModelMathTest` | 10 |
| **合計** | | **141，全過** | |

計數沿革（見 `step3-fix-log.md` §4）：基線 110 項（7 類）→ F-1～F-7 後
127 項（10 類，新增 17 項）→ F-8＋V-2 後 141 項（12 類，再新增 14 項）。
既有測試在各階段零修改、零失敗（中途 1 項失敗為測試 bound 發明不當，
已修正為不斷言 px 門檻、只斷言 LOO 語義，見同文件 §4「中途失敗與處理」；
該失敗不構成程式缺陷證據）。

### 6.3 測試結果的邊界（不得超譯）

- Harness 通過只證明 12 個純 Kotlin 類別的邏輯在本機 harness 下通過；
  **不證明**全量編譯通過（`gradle testSideloadDebugUnitTest`／
  `assembleSideloadDebug`／`bundlePlayRelease` 皆未在本機執行）、
  不證明 Android 框架接線正確、不證明 CI 通過、不證明實機行為正確。
- Harness 以 JUnitCore 文字輸出計數，不用 Gradle 的 XML 測試報告；
  CI 上的 XML 計數（failures／errors）待推送後以 CI 報告為準，
  不得以本節數字冒充 CI 結果。
- `verification-ledger.md` 的行狀態升級：本報告的重跑證據只支持
  「純邏輯部分」的行升級為「單元測試完成」（見 `docs/verification-ledger.md`
  本次更新，§12 末）；凡測試命令含實機對照的行、CI／APK／實機行，
  一律維持「尚未驗證」。

## 7. CI 結果：尚未執行

- 全部 CI 檢查項目（`ci-validation-report.md` §2，CI-01～CI-10）狀態皆為
  「尚未執行」：未推送、未觸發 workflow、未查執行紀錄。
- 現行 workflow 只有建置步驟、無測試步驟、無 Lint 步驟（見 §1／
  `ci-branch-plan.md` §1）；在 workflow 更新前，CI 永遠不會跑單元測試。
- STEP 5 產物為 `docs/ci-branch-plan.md`：專用分支提案
  `phase3-step3-review`、61 檔變更清單（blob SHA 比對：新增 45、異動 16、
  刪除 0，含 STEP 5／STEP 7 文件）、commit 草稿、測試程式清單、CI 設定 diff
  提案（只寫提案，須走瀏覽器）、CI 工作與預期產物、風險評估、回滾方法。
  授權狀態：**未授權**。CI 通過≠實機通過，CI 通過後最多為「待實機驗證」，
  仍禁止發布。

## 8. 實機驗證結果：尚未驗證

### 8.1 範圍

- `TEST_PLAN.md` §3 的全部實機清單（F／G／R／E 系列）、
  `device-acceptance-matrix.md` 的全部矩陣項（M-01～M-29 段，含第一次啟動、
  校正、D3-7 dispatch、邊緣開關與幾何、K-12／相機綁定／K-30、追蹤中斷等）、
  `verification-ledger.md` §3 的 22 項特殊情境（C01～C22），**全部尚未執行、
  全部尚未驗證**。實機一律 SM-S9180、Android 17、側載版；模擬器不得代替實機。
- 未產生真實 APK：CI 未執行，故無 CI 產物 APK；本機 Gradle 跑不起來，
  故無本機編出的 APK。任何「APK 已產生」的說法皆無證據。
- 未實機安裝：無 APK 即無安裝；圖示與 v0.5.4 校正閃退修正的確認，
  只能由使用者本人在 SM-S9180 上回報，任何編譯通過／APK 檢查／重啟／
  清快取的建議皆不構成確認（見 `verification-ledger.md` B14）。

### 8.2 實機驗收矩陣的執行門檻

`device-acceptance-matrix.md` §1 通用測試條件（裝置資訊、版本核對、
螢幕密度實機讀取等）須在每項測試開始前填寫並核對；M-10（校正取消）
與 M-12（D-4 決策紀錄）因 D-4 未決而**不可執行**，不得以其他測試代替。
50dp 對照（M-19／M-20）須以 v0.5.9 為對照；誤觸率／漏觸率／操作時間
量測（M-21／E-10）未執行、不設通過門檻。

### 8.3 裝置診斷證據的定位（不得誤讀為缺陷結論）

`device-diagnostic-evidence.md`（監看排程 2026-11-23 12:18 CST 回報，
Supabase `gaze_logs`，台北時間 11:48–12:17，15 段、12,172 筆）：
人臉出現率 12.2%（1,484／12,172），6 段為 0%；`sens` 恆 1.00、
`offX`／`offY` 全 0.0；原始預測經常超出螢幕（X −12,474～10,211、
Y −18,531～11,663）；游標箝制在邊緣，有臉樣本 52.4% 落在 X=0。
監看診斷標籤：(d) 人臉遺失為主因、(b) 濾波／箝制吃掉移動、(c) 左／下偏移、
(e) 修正未啟動而非過度。**以上皆為裝置數據解讀、未經程式碼交叉驗證，
不得寫成已證實的程式缺陷；單批數據不得推論全體使用者行為。**
程式側僅核對到「主路徑無濾波前極端值拒絕」這一機制事實
（`GazeAccessibilityService.kt:654-721`，見 `step3-fix-log.md` §6），
不證明本批數據的游標箝制由極端預測造成。處置為提案 P-DEV-1（見 §4.1），
不得擅自實作；建議方向 (1) 人臉遺失恢復機制部分已由 F-2 清除殘留狀態，
RECOVERY 恢復流程本身未動，不宣稱已修復。

## 9. 準確度報告：尚未驗證（引用 `accuracy-baseline-plan.md`）

- 全部 29 項指標（定位準確度 A1–A7、操作可靠性 R1–R7、時間效能 L1–L6、
  穩定性 S-T1–S-T9）與全部 12 情境（S01 正常坐姿、S02 躺姿、S03 平放、
  S04 臉部距離、S05 頭部角度、S06 光線、S07 眼鏡及反光、S08 不同使用者、
  S09 螢幕尺寸、S10 Android 裝置、S11 長時間使用、S12 追蹤中斷及恢復）
  **全部「尚未驗證」**。本報告不填入任何量測數字。
- 執行門檻（G-ACC-1～G-ACC-5）**全部未滿足**：可安裝 APK 無、實體裝置測試數據無、
  ground-truth 紀錄方法未確認（P-ACC-1）、延遲計時方法未確認（P-ACC-2）、
  訓練／測試分離未執行。門檻未滿足時，準確度計畫文件的唯一合法用途是「計畫」本身。
- 指標定義（方法，非結果）：MAE／中位誤差／RMSE／P95／逐 trial 像素誤差／
  正規化誤差／目標命中率（A1–A7）；點擊成功率／誤觸率／漏觸率／雙擊／長按／
  滑動／邊緣操作成功率（R1–R7，分邊統計，不得只報合計）；六項延遲 L1–L6
  （見 §10）；追蹤可用度／遺失頻率／恢復時間／游標抖動／長時間穩定度／
  記憶體／CPU／GPU／電池／溫度（S-T1–S-T9）。**通過門檻：無**——本次量測只記錄數字，
  門檻須待基準數字出來、經所有權人定稿後才能寫入。
- 關鍵限制：訓練集誤差與留一法誤差不得填入準確度表（須另表並標註「非獨立測試」）；
  `GazeLog` CSV 單獨算不出 MAE／命中率／誤觸率（無 ground-truth、無 trial 邊界、
  無意圖紀錄，見 `accuracy-baseline-plan.md` §6.3）；臉遺失行不得計入準確度分母；
  `offX`／`offY` 全 0 不等於修正未生效（須對照開關狀態）；10 Hz 節流會漏掉幀間抖動細節；
  120 秒輪轉＋只留 3 份，長時間 run 須及時取回段落檔案。
- R4（雙擊辨識正確率）的現況限制註記：量測前須先確認當時版本的 dispatch 行為
  並記錄版本，不得以控制器層取消冒充 dispatch 層取消——STEP 3 後此註記的
  「現況」已部分改變（F-8 守衛層已接線），量測時須以當時版本重查，不得沿用舊註記。

## 10. 效能報告：尚未驗證（無量測數據，只寫方法與待測項目）

**本節無任何量測數據。以下全部為「待測項目與方法」，不得讀為結果。**

- 時間效能（`accuracy-baseline-plan.md` §3.3，L1–L6，全部尚未驗證）：
  L1 相機幀延遲、L2 臉部特徵點延遲、L3 視線估計延遲、L4 游標更新延遲、
  L5 手勢派發延遲、L6 端到端延遲（須分開記錄 dwell 設定值與 dwell 外延遲，
  不得把 dwell 時間當作系統延遲）。每項報告樣本數、平均數、中位數、P90、最大值。
- 前置條件 P-ACC-2（未滿足）：現況程式未見分段延遲計時；計時方法
  （程式內時間戳／外部量測）在執行前須書面決定並記錄方法版本；
  方法未定前，六項皆不得以 `GazeLog` 的 10 Hz 節流時間戳、樣本新鮮度或
  主觀秒表感覺冒充。`GazeLog` 的 `t` 欄為段內相對毫秒、10 Hz 節流，
  只能反映「紀錄間隔」，不能拆分管線各段延遲。
- 穩定性中的資源項目（§3.4，全部尚未驗證）：S-T6 記憶體（PSS 或同等指標，
  起始／峰值／結束／增長量，註明取樣間隔與工具）；S-T7 CPU／GPU
  （平均與峰值，分開列）；S-T8 電池（電量變化與耗電速率，註明亮度設定）；
  S-T9 裝置溫度（起始／峰值／結束，註明感測器來源；高溫中斷須計入
  S-T2／S-T3，不得剪掉高溫時段）。取樣工具在執行前須書面決定；
  現況程式未見記憶體／CPU／溫度量測輸出，不得以「感覺不燙」「感覺不卡」代替數字。
- 長時間項目：S-T5 長時間追蹤穩定度（等長時窗逐窗報告，不得只報全程平均
  掩蓋後段衰退）；S11 長時間持續使用情境；C20 記憶體不足、C21 CPU 過載、
  C22 溫度過高特殊情境（見 `verification-ledger.md` §3）。
- 已知但未量測的效能相關事實（靜態記錄，非量測結果）：影像偏好策略
  1280×720（實際幀尺寸／FPS 未量測）；`GazeLog` 10 Hz 節流；提醒評估每 500 ms；
  服務本體約 1982 行（STEP 1 盤點時點行數，現況行數見工作樹，複雜度集中風險
  見 `risk-register.md` R17）。以上皆不得當作效能結論。

## 11. 隱私安全報告（摘要 `privacy-security-report.md`＋F-4／F-5／F-6＋殘餘風險）

### 11.1 審查結論摘要

- 網路請求總覽：只有三個 HTTPS 目的地，無其他網路呼叫（`§3.1`）；
  資料流 11 種（見 `privacy-data-flow.md` §1／§2），含 Supabase 上傳
  （診斷 CSV 至 `gaze_logs` 表、驗證影格 `frame-` 列，共用端點與表）、
  更新檢查、Play 商店意圖、使用者主動複製、系統備份。
- 兩版本差異：側載版診斷／監控預設開啟（`diagnosticLogging=true`、
  `remoteMonitor=true`）；Play 版不上傳只靠呼叫端閘門（`§4.3`，結構性弱點，
  見 R-2）；Play 版仍本機寫檔（`§4.2`），不得以「不上傳」概括為「不記錄」。
- 相機影像與 Landmarker 輸出只在裝置內（`§3.8`）；22 維中僅 6 個值進 CSV
  （`§3.9`）；校正資料／設定／條件歷史為 6 份明文 SharedPreferences，
  無加密、無刪除 UI（`§3.10`）；無 DataStore／SQLite／Room（`§3.11`）；
  Analytics 不存在（`§3.6`）；無 `FLAG_SECURE`（`§6.3`）。
- 文案矛盾（K-12）仍在：三處絕對表述（設定頁對話框、主畫面頁尾、
  `strings.xml:3` 的 `accessibility_desc` 稱「影像只在本機即時處理，
  不儲存、不上傳」）與實際上傳行為矛盾；`SetupWizardActivity`「關閉即停止」句亦在。
  STEP 3 未改任何聲明文案（V-3），改寫須稽核證據齊備後另批。
- 伺服端 RLS「僅 INSERT」主張無法由本樹驗證（`privacy-data-flow.md:364`；
  `privacy-security-report.md` §10）；傳遞依賴執行期網路行為、合併後清單、
  伺服端紀錄／備份實際內容皆未驗證（R-14）。

### 11.2 STEP 3 修復對 R-1／R-2／R-3 的狀態（部分修復，殘餘仍在）

| 風險 | STEP 3 前狀態 | STEP 3 修復 | 殘餘（仍未解決） |
|---|---|---|---|
| R-1（高）撤回同意後當下 CSV 仍上傳 | `settingsListener` 不呼叫 `close()`、不重置 `pendingUpload`；關閉後寫入的幀亦一併上傳 | F-4：開→關轉換時 `close(upload=false)` 丟棄當下檔案（未提交部分） | 已提交至 `uploadExecutor` 的任務無法撤回；已上傳的伺服端資料無刪除路徑（P-R1b）；影格本機刪除 UI 無 |
| R-2（高，結構性）Play 版不上傳只靠呼叫端閘門；`uploadFile` 無守衛；APK 內含上傳程式與寫死金鑰 | 呼叫端閘門為唯一防護；`uploadFile` 只檢查端點空白 | F-5：`uploadFile` 入口加 `BuildConfig.REMOTE_MONITOR_ENABLED` 顯式守衛，覆蓋 CSV 與影格兩路徑 | 金鑰常數仍在 APK 內；source set 隔離未做；Play 版 merged manifest（INTERNET 移除）與無上傳請求須網路抓包驗證，尚未驗證 |
| R-3（高）`allowBackup=true` 無排除規則 | 診斷 CSV、眼區影格、閃退報告、校正模型與開關值可能隨備份離開裝置並還原 | F-6：僅三類診斷目錄排除（雲端備份＋裝置間傳輸同範圍） | 條件歷史 prefs 是否排除未決（P-R3b）；備份／還原實測未做；舊備份副本不受新規則影響 |

### 11.3 其餘殘餘風險（`privacy-security-report.md` §9，STEP 3 未處理）

R-10（中）無障礙宣告過度（`canRetrieveWindowContent=true`＋`typeAllMask`，
程式零讀取）；R-11（低）Crash Report 檔名秒級覆寫、旗標不清、文案過寬；
R-12（低）無 `FLAG_SECURE`（截圖／近期任務縮圖）；R-13（中，法務／上架）
第三方授權全部待定位、無 NOTICE／SBOM；R-14（中）傳遞依賴／合併清單／
伺服端實際內容未驗證；R-15（定性提醒）眼區裁切屬高敏感生物特徵影像，
上架前整段移除的承諾須含「程式＋開關＋伺服端專案＋金鑰輪換」四項。
另 `risk-register.md` 的 R1（診斷／監控預設開啟與文案矛盾）為最高風險之一，
其根本處置（上架前整段移除診斷／監控程式與 Supabase 專案）為既定決定，
不在本階段驗證範圍；調校期內預設維持開啟，不得擅改。

## 12. 後續風險（`risk-register.md` R1～R20＋`release-readiness.md` 9 項理由）

### 12.1 最高風險前五項（`risk-register.md` §0）

R1 診斷／監控預設開啟上傳與文案矛盾（高）；R2 全部功能未經本任務驗證
（高；STEP 3 後修正為：純邏輯 harness 已重跑 141 項，編譯／CI／實機仍零驗證）；
R3 停止後自動恢復（K-30）＋RECOVERY 相機持續運作（高；F-7 只加無格式重查，
持久化仍未決）；R4 相機綁定失敗無可觀測信號＋引擎啟停競態（高）；
R5 邊緣手勢幾何改動未經實機對照＋門檻未校準＋無取消（高；
F-8 已補狀態層取消語義，逾時排程與實機對照仍未做，門檻仍未校準）。

### 12.2 其餘風險（R6～R20，摘要）

R6 首次校正注視選擇無法判讀；R7 校正取消僅預留（待 D-4）；
R8 Supabase anon key 內嵌於 App 程式碼；R9 CI 無測試、無 gradlew、本地建置鏈脆弱；
R10 更新下載無完整性校驗；R11 品質門檻皆為經驗值／未校準；
R12 文案絕對表述殘餘（K-12）；R13 模型與設定明文儲存、備份未定；
R14 無障礙服務權限範圍大；R15 臉遺失時濾波器不重置（F-2 已補臉遺失分支重置，
恢復序列仍未完整實作）；R16 陀螺儀修正可能重複計算（預設關為緩解）；
R17 單一協調者複雜度集中（服務約 1982 行，盤點時點）；
R18 授權切換未定（MIT 現況 vs AGPL 目標，切換前須相容性稽核）；
R19 第三方授權清單缺失；R20 推送／發行邊界（流程風險）。
詳情與證據見 `risk-register.md` §1～§3；本報告不重複展開。

### 12.3 禁止發布的 9 項理由（`release-readiness.md` §1，STEP 3 前撰寫）

理由 1 未驗證編譯；理由 2 未實機驗證；理由 3 50dp 未量測；理由 4 D-4 未決；
理由 5 D3-7 dispatch 未做（STEP 3 後：守衛層與接線已落地，逾時排程與 CI／實機
仍未做，理由未消除）；理由 6 K-12 殘餘；理由 7 相機綁定無觀測信號；
理由 8 K-30 未修（F-7 只做重查，持久化未決，理由未消除）；理由 9 門檻未校準。
**9 項中無任何一項因 STEP 3 而消除**；發布狀態維持**禁止發布**。
升級到「待 CI」／「待實機驗證」／「可進入下一階段」的證據清單見同文件 §2～§4，
本報告不轉載、不預填。

## 13. 最終回答：16 個問題（無證據者明確寫「尚未驗證」）

> 以下 16 問的編號與順序依任務書 §13。每一問皆只寫本階段的證據狀態；
> 「已寫程式」不等於「已驗證」，「有測試檔」不等於「測試已通過」，
> 「CI 通過」與「實機通過」皆須以實際執行紀錄為準，本階段皆無。

**Q1. 實際完成多少功能？**
已寫程式且接通（靜態）的功能見 §5：Phase 3 的 29 項＋核心管線功能
（`function-matrix.md` 25 項核心＋其他主要功能）皆已寫程式；STEP 3 的
F-1～F-8 皆已改碼。但「完成」（含編譯＋測試＋實機三重證據）的功能為
**0 項**：全量編譯、CI、實機驗證皆未做，發布狀態為禁止發布。
唯一取得「單元測試完成」級別證據的是 12 個純 Kotlin 類別的邏輯
（harness 141 項，見 §6），且僅限純邏輯部分，不等於功能完成。

**Q2. 哪些只是 UI，未接通？**
D1-9 取消目標（僅 4 個常數預留、零引用、無事件、無視覺元素）；
智慧目標辨識（不存在）；追蹤品質引擎（無具名模組）；首次校正注視選擇
（無舊模型時無法判讀）；設計文件 TARGET（九態相機狀態機、統一 Dispatcher、
品質層七態、十步恢復流程）——皆只有文件／常數／UI 殼，未接通。
詳見 §5。另：校正報告頁、邊緣確認目標、提醒目標雖已接通繪製與判定，
其門檻／幾何未校準，行為正確性尚未驗證。

**Q3. 哪些已寫程式？**
Phase 3 29 項（D1-1…D1-8、D1-10…D1-16、D2-1…D2-6、D3-1…D3-8）；
STEP 3 F-1～F-8（`GuardDecisions.kt`、`GestureDispatchGuard.kt` 新增，
`GazeAccessibilityService.kt`、`GazeSettings.kt`、`GazeLog.kt`、
`SettingsActivity.kt`、`activity_settings.xml`、`AndroidManifest.xml`、
`backup_rules.xml`、`data_extraction_rules.xml` 改動）；
核心管線與其他主要功能（見 §5）。
未寫程式者：D1-9 互動功能、DCL-06 七態、DCL-15／16 仲裁本體、
DCL-24～46（吸附／辨識／敏感判斷）、品質儲存門檻、眨眼啟用閘門、
音量鍵緊急停止、邊緣寬度可調、K-30 持久化、逾時計時器排程。

**Q4. 哪些有自動化測試？**
12 個測試類別、141 項 `@Test`（見 §6.2 表）：`AppFlowStateTest`、
`ConditionHistoryTest`、`DwellTrackerTest`、`EdgeGestureControllerTest`、
`EdgeSwitchRestoreTest`、`GazeEvaluatorTest`、`GazeModelMathTest`、
`GazeSettingsDefaultsTest`、`GestureDispatchGuardTest`、`GuardDecisionsTest`、
`OneEuroFilterTest`、`RecoveryGateTest`。
無自動化測試者：服務本體／Activity／引擎／overlay／`dispatchGesture` 系統回呼／
`GazeLog` 檔案寫入與上傳／備份規則／`GazeModel` JSON 往返／prefs 讀寫／
flavor gating／更新檢查的 `isNewer` 等（見 `function-matrix.md` 與 §6.3）。
全樹無 instrumented test。

**Q5. 哪些通過 CI？**
**尚未驗證——無任何一項通過 CI。** CI 尚未執行（見 §7）；
現行 workflow 亦無測試步驟，即使執行也只驗編譯。
本機 harness 的 141 項全過不得冒充 CI 通過。

**Q6. 是否產生真實 APK？**
**尚未驗證——未產生。** CI 未執行、本機 Gradle 跑不起來，
故無 CI 產物 APK、亦無本機編出的 APK。`face_landmarker.task` 等既有
asset 檔案存在，不等於 APK 已產生。

**Q7. 是否實機安裝？**
**尚未驗證——未安裝。** 無 APK 即無安裝；亦無任何實機安裝／啟動檢查紀錄。

**Q8. 定位誤差（MAE／RMSE／P90／P95）？**
**尚未驗證。** 準確度量測未執行（門檻 G-ACC-1～G-ACC-5 皆未滿足，
見 §9）。不得引用訓練集誤差、留一法誤差、`GazeEvaluator` 的歷史值、
或 STEP 3 測試過程中出現的合成網格 LOO 數值（55.36 px，見
`step3-fix-log.md` §4）作為定位誤差——該數值為合成測試數據的診斷值，
不是實機量測結果，且測試已改為不斷言任何 px 門檻。

**Q9. 命中率？**
**尚未驗證。** 目標命中率（A7）與點擊成功率（R1）皆未量測；
ground-truth 紀錄方法未定（P-ACC-1），無目標／意圖紀錄即無法計算。
不得以 `GazeLog` CSV 單獨推算。

**Q10. 誤觸率？**
**尚未驗證。** 誤觸率（R2）與漏觸率（R3）皆未量測（E-10 未執行）；
邊緣帶 50dp 的改動對誤觸／漏觸的影響未知（M-19／M-20／M-21 未執行）。
dispersion 檢查目前永不阻擋（`Float.MAX_VALUE`），不得宣稱誤觸已受控。

**Q11. 平均延遲？**
**尚未驗證。** 六項延遲 L1–L6 皆未量測；分段計時方法未定（P-ACC-2），
現況程式未見分段延遲計時。不得以 `GazeLog` 10 Hz 時間戳、樣本新鮮度
或主觀感覺冒充延遲數字。

**Q12. 姿勢差異（坐／躺／平放、角度、距離、光線）？**
**尚未驗證。** 情境 S01–S06、S08–S10 皆未量測；條件歷史與條件修正
（亮度／角度／臉距）的純函式部分有單元測試（`ConditionHistoryTest` 22 項），
但條件提醒、內插修正的實機效果、不同姿勢下的準確度差異皆未驗證。
`offX`／`offY` 全 0 的既往觀測不得單獨下「修正未生效」的結論（見 §9）。

**Q13. 長時間穩定性？**
**尚未驗證。** S-T1～S-T5、S11 情境、特殊情境 C08／C09／C20／C21／C22
皆未執行；長時間漂移、追蹤遺失頻率、恢復時間、游標抖動皆無數據。
裝置診斷證據的臉率 12.2% 為單批未驗證解讀，不得當作穩定性結論（見 §8.3）。

**Q14. 記憶體／電池問題？**
**尚未驗證。** S-T6 記憶體、S-T7 CPU／GPU、S-T8 電池、S-T9 溫度皆未量測；
取樣工具未定；現況程式未見相關量測輸出。無任何記憶體洩漏、耗電異常
或過熱的證實結論，亦無「無問題」的證據——兩者皆不得宣稱。
（背景事實：GCE e2-micro 的記憶體壓力屬另一系統，與本 App 的實機資源
用量無關，不得混用。）

**Q15. 隱私外傳風險？**
**已識別、部分緩解、殘餘風險仍在——不得寫「無風險」。**
外傳面：診斷 CSV 與眼區影格預設上傳至 Supabase（側載版預設開啟）、
更新檢查（GitHub releases）、系統備份、使用者主動複製。
STEP 3 緩解：F-4（撤回後未提交 CSV 丟棄）、F-5（`uploadFile` flavor 守衛）、
F-6（備份排除三類診斷目錄）。殘餘風險見 §11.2／§11.3：
R-1 已提交任務不可撤回＋已上傳資料無刪除路徑；R-2 金鑰仍在 APK 內＋
merged manifest／抓包未驗證；R-3 條件歷史排除未決＋備份實測未做；
R-8 anon key 內嵌；R-14 伺服端 RLS／傳遞依賴／合併清單未驗證；
文案矛盾（K-12）未改。**網路流量驗證未做**，不得宣稱「完全不會上傳」
或以靜態稽核冒充流量證據。上架前整段移除診斷／監控的既定決定未執行。

**Q16. 哪些無法驗證？**
以下皆為「本階段方法無法驗證」或「證據不存在」，須以其他方法／後續階段處理，
不得以現有證據冒充：
(1) 全量 Android 編譯（本機 Gradle 跑不起來，只能走 CI）；(2) CI 執行結果
（未推送）；(3) 實機行為全部（無 APK、無裝置執行）；(4) `GazeModel` JSON 往返
與 prefs 讀寫（純 JVM stub，須實機）；(5) 伺服端 RLS／資料表／保留期限／
刪除流程（無法由本樹驗證）；(6) 傳遞依賴執行期網路行為與合併後清單
（須實機抓包＋清單檢查）；(7) 備份／還原實際行為（須實機備份測試）；
(8) 裝置重啟後的平台綁定行為、音量鍵全域攔截允許範圍、系統畫面實際可控範圍、
保護殼對邊緣帶的影響（靜態無法確認，見 `dcl-mapping.md` §7）；
(9) MediaPipe 實際回傳點數／實際相機解析度／FPS（程式未斷言、未量測）；
(10) 第三方授權與模型 bundle 雜湊（未比對）；(11) 圖示／校正閃退修正的
使用者確認（只能由使用者本人回報）；(12) 門檻校準值本身（須實機量測數據，
現況全部 UNCALIBRATED／經驗值）；(13) 裝置診斷證據的因果歸因
（單批數據、成因未區分）；(14) D-4 決策內容（未決，23 項未決、已決定 0 項）。

## 14. 引用證據清單與待定位

- DCL：`docs/dcl-mapping.md` §1–§8（統計 §2、總表 §3、詳述 §4、矛盾 §5、
  待批 §6、待定位 §7）。
- 修復：`docs/step3-fix-log.md` §0–§6（F-1～F-8、V-1～V-3、提案、STEP 4、
  禁區核對、診斷證據處理）。
- 測試：本報告 §6（親自重跑命令與輸出，2026-11-23）；
  `~/workspace/eyecontrol-phase3/TEST_RESULTS.md`（沿革）、`TEST_PLAN.md`（命令與實機清單）、
  `IMPLEMENTATION_NOTES.md` §0（Gradle 限制）與 §1 出入點。
- CI：`docs/ci-validation-report.md`、`docs/ci-branch-plan.md`（STEP 5）。
- 實機：`docs/device-acceptance-matrix.md`、`docs/verification-ledger.md`、
  `docs/device-diagnostic-evidence.md`。
- 準確度／效能：`docs/accuracy-baseline-plan.md` §0–§8。
- 隱私：`docs/privacy-security-report.md`（§1–§10、附錄）、
  `docs/privacy-data-flow.md`、`docs/privacy-copy-audit.md`。
- 風險／發布：`docs/risk-register.md`、`docs/release-readiness.md`、
  `docs/engineering-remediation-plan.md`、`docs/engineering-task-matrix.md`、
  `docs/engineering-baseline.md`。
- 待定位（找不到來源，不得編造）：`dcl-mapping.md` §7 五項；
  `function-matrix.md` §3 十項（含實際解析度、landmark 點數、回呼執行緒、
  品質即時量測、`canRetrieveWindowContent` 實際使用、全部文案一致性、
  第三方授權、`face_landmarker.task` 雜湊、Supabase 伺服端設定、CI／圖示／閃退實機結果）；
  `privacy-security-report.md` §10 待定位／待驗證清單；
  `accuracy-baseline-plan.md` §6.4（P-ACC-1）與 §3.3（P-ACC-2）的方法未定。
