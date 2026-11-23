# EyeControl STEP 3 最小安全穩定性修復紀錄（STEP 3＋STEP 4）

> 性質：STEP 3 實作紀錄＋STEP 4 測試紀錄。本任務有改程式碼（見 §1），
> 未推送、未 commit、未發版、未改版本號、未改 `.github/workflows/`、
> 未碰 secrets／keystore。
> 基線：工作樹 `~/workspace/eyecontrol-p3`（v0.5.9＋未推送 Phase 3 實作；
> 遠端 main HEAD `c6b80964`）。行號為本任務完成後的目前工作樹行號。
> 複用：`docs/dcl-mapping.md`（§5 T-01～T-14、§6 P-01～P-11）、
> `docs/architecture-map.md`、`docs/function-matrix.md`、
> `docs/risk-register.md`、`docs/privacy-security-report.md`、
> `docs/engineering-task-matrix.md`、
> `~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md`。
> **已改碼 ≠ 編譯通過 ≠ CI 通過 ≠ 實機通過。**本機 Gradle 因沙箱攔截
> JVM loopback 永遠跑不起來（已知限制，見 IMPLEMENTATION_NOTES §0），
> 全量編譯留待 CI；實機驗證一律未做，留待 SM-S9180 實機清單。

## 0. 動手門檻（五項同時符合才改碼）

只有同時符合以下五項才動手：(a) 問題已在程式碼中證實；(b) 變更最小；
(c) 符合 DCL 已確認文字；(d) 不需發明任何閾值／數值；
(e) 不需改變已確認規格。否則一律列為提案（§3），不得擅自實作。
「已閱讀 ≠ 已提案 ≠ 已改碼 ≠ 靜態通過 ≠ 單元測試通過 ≠ CI 通過 ≠ 實機通過」。

## 1. 已實作修復（F-1～F-8）

### F-1 — T-01 眨眼點擊：預設關閉＋獨立開關（DCL-13 最小部分）

- **原始問題**：眨眼點擊預設開啟、僅以 150～600ms 時間窗判定；全樹無
  `blinkEnabled`／眨眼開關（`GazeSettings.kt` 只有三個眨眼參數），
  使用者無法關閉眨眼點擊（dcl-mapping §4.1 DCL-13、§5 T-01）。
- **影響範圍**：僅眨眼「觸發」判定。睜眼／閉眼偵測（`blinkThreshold`）、
  邊緣檢查的 `eyesOpen`、校正／訓練頁的閉眼丟棄幀一律不動——
  這些路徑吃的是閾值，不是點擊開關。
- **修改檔案**：
  - `app/src/main/java/com/example/eyecontrol/GazeSettings.kt:26`：
    新增 `blinkClickEnabled: Boolean = false`；`:116` 存檔寫入
    `KEY_BLINK_CLICK_ENABLED = "blink_click_enabled"`；`:206` 讀取
    `getBoolean(KEY_BLINK_CLICK_ENABLED, false)`（從未存過 key 時為關）。
  - `app/src/main/java/com/example/eyecontrol/GuardDecisions.kt:18-35`（新增）：
    純判定 `shouldTriggerBlinkClick`，把現況條件原樣收斂（開關＋時間窗＋
    游標有效＋冷卻＋非暫停＋非提醒），不改任何條件語義。
  - `app/src/main/java/com/example/eyecontrol/GazeAccessibilityService.kt:618-631`：
    眨眼觸發條件改呼叫上述純判定（原內聯條件逐項對應，無增刪）。
  - `app/src/main/java/com/example/eyecontrol/SettingsActivity.kt:74,94,297,324`：
    開關初始值、列身點按、儲存、恢復預設值（四處皆沿用既有開關模式）。
  - `app/src/main/res/layout/activity_settings.xml:427,456`：
    新增 `rowBlinkClick`／`switchBlinkClick` 一列（沿用 `rowGyro` 版式），
    副標明示「預設關閉；開啟後以閉眼時間判定點擊，自然眨眼可能誤觸」，
    不宣稱意圖校正已通過。
- **理由**：DCL-13 文字明定「預設關閉、獨立開關」；開關結構（設定
  key＋設定頁開關列＋服務即時重載）現成存在，最小變更即可落地。
- **相容性影響**：有意的行為改變（規格要求，非相容維持）——從未存過
  此 key 的既有使用者升級後，眨眼點擊即關閉，須手動在設定頁開啟。
  已明確開啟過的使用者不受影響（無既有 key 可沿用，見下）。注意：
  由於此前根本沒有此 key，**所有**既有使用者升級後皆為關閉；
  這正是 DCL-13「預設關閉」的要求，不得以「舊行為保留」為由維持開啟。
  參數滑桿（時間窗／閾值）語義不變；開關關閉時參數仍可調整、只不觸發。
- **回歸風險**：低。觸發條件為純 AND 收斂，逐項與舊內聯條件對照一致；
  風險在於使用者升級後發現眨眼點擊「不見了」——屬規格要求的預設改變，
  須以發版說明告知，不得以靜默方式上線。設定頁新增一列的版式風險
  只在視覺（文字裁切），走 CI 編譯＋實機目視核對。
- **驗證方式**：harness `GuardDecisionsTest` 6 項（開關關閉永不觸發、
  窗內觸發、窗界含端點、窗外不觸發、其餘守衛各別阻擋）；
  `GazeSettingsDefaultsTest`（預設關、`clamped()` 不翻轉開關、既有預設值
  不變）。Android 側（開關顯示／儲存／即時生效）走 CI＋實機。

### F-2 — T-06 臉遺失殘留清除（DCL-11／DCL-09）

- **原始問題**：臉遺失分支（`GazeAccessibilityService.kt` 的 `sample == null`
  分支）只清 dwell／面板／把手／邊緣，未清 `isBlinking`／`blinkStartTime`、
  `swipeStartPoint`、濾波歷史（dcl-mapping §4.1 DCL-11、§5 T-06；
  risk-register R15）。中斷前開始的眨眼可在臉重現後以舊 `blinkStartTime`
  配對出窗內時長而誤觸；舊滑動起點可被新終點配對執行；舊濾波歷史
  污染臉重現後第一幀座標。
- **影響範圍**：僅臉遺失分支內。RECOVERY 目標的「凍結不清零」語義不動
  （該分支另有 `updateRecoveryTarget` 臉遺失處理，見 §3 P-08 備註）；
  閉眼／降級分支不動。
- **修改檔案**：
  `app/src/main/java/com/example/eyecontrol/GazeAccessibilityService.kt:559-570`
  （臉遺失分支既有 `screenDwell.reset()`／`cancelEdgeGesture()` 之後新增）：
  `isBlinking = false`、`blinkStartTime = 0L`、`swipeStartPoint = null`、
  `controlView?.swipeState = 0`（滑動起點的顯示鏡像，一併清，沿用
  `attemptRecovery`／`showReminder` 的同一對寫法）＋`invalidate()`、
  `filterX.reset()`、`filterY.reset()`、待發雙擊第二下取消
  （`cancelPendingDoubleTap()`，機制見 F-3）。
- **理由**：三者皆為 DCL-11 點名的清除對象（dwell／blink／swipe／
  filter history），且程式碼已證實殘留；`OneEuroFilter.reset()` 經核實
  只清 `initialized` 旗標、不改 `minCutoff`／`beta` 參數
  （`OneEuroFilter.kt:75`），故不屬「改濾波參數」禁區。
- **相容性影響**：無行為新增，只有殘留清除。臉重現後第一幀須重新累積
  dwell／重新開始眨眼計時／重新選滑動起點——此即 DCL-11 要求。
  `hasValidCursor` **不清**（游標留原地變灰為有意顯示語義），見 §3 P-08。
- **回歸風險**：低。清除皆為冪等賦值／reset；唯一需實機確認的是
  「臉短暫遺失又回來時游標是否需重新平滑」（濾波重置使首幀直接回傳
  原值，`OneEuroFilter.filter` 未初始化分支），屬 DCL-09 恢復序列的
  實機觀察項，不得宣稱為已驗證改善。
- **驗證方式**：靜態核對（分支內逐項對照 DCL-11 清單）＋ CI 編譯；
  實機：臉移出／移入後，舊眨眼不觸發、舊滑動起點不配對、游標不跳回
  舊位置（TEST_PLAN 臉遺失項）。harness 無法覆蓋服務分支（Android 類別），
  不偽造測試。

### F-3 — T-05 薄守衛＋T-13 中斷／暫停清除待執行意圖（DCL-18／DCL-17）

- **原始問題**：(1) `performDoubleTap` 的第二下為匿名
  `mainHandler.postDelayed({ performTap(x, y) }, 150)`，無具名持有、無
  `removeCallbacks`（K-07／K-62，engineering-task-matrix D3-7(b) 未做）——
  模式切換／暫停／停止／提醒介入／臉遺失時待發點擊仍會排隊執行。
  (2) `onInterrupt()` 為空實作（`= Unit`），`pauseTracking()` 不清除
  進行中狀態（dcl-mapping §4.1 DCL-18、§5 T-13）。(3) `stopTracking()`／
  `stopCompletely()` 未清 `swipeStartPoint`／眨眼狀態／待發雙擊
  （DCL-20 證據）。
- **影響範圍**：只加「取消待執行意圖」路徑，不建 GestureArbiter、不改
  dispatch 本體（四個 `perform*` 仍各自建筆畫與呼叫 `dispatchGesture`；
  集中狀態機接線見 F-8）、
  不加進行中手勢鎖定（見下「未做」說明）、不停引擎、不關相機、不改流程態。
- **修改檔案**：
  - `GazeAccessibilityService.kt:266`：新增欄位
    `pendingDoubleTapSecond: Runnable?`（具名持有）。
  - `:1328-1346`：新增 `cancelPendingGesture()`（取消雙擊第二下＋清
    滑動起點／swipeState 鏡像＋清眨眼狀態＋`screenDwell.reset()`＋
    `currentProgress = 0f`＋`cancelEdgeGesture()`）與
    `cancelPendingDoubleTap()`（`removeCallbacks`＋置 null）。濾波歷史
    不在此 helper 內（見 F-2，臉遺失／恢復路徑專管）。
  - `:1657-1670`：`performDoubleTap` 改為先取消舊的第二下、再以具名
    Runnable 排隊（150 ms 沿用現況值；Runnable 執行時先將持有置 null
    再 `performTap`；150 ms 內的新雙擊以新的第二下為準）。
  - 呼叫點（皆主執行緒或 `mainHandler` 回呼）：`onInterrupt()`（`:1729-1734`，
    由空實作改為只清待執行意圖）；`pauseTracking()`（`:1924` 起的 post 塊內）；
    `stopTracking()`（RECOVERY 進入塊內）；`stopCompletely()`（背景停表後的
    post 塊內）；`requestPauseForCalibration` 的主執行緒回呼內
    （`cancelEdgeGesture()` 之後，協議本體——`===` 身分確認、無逾時、
    回呼順序——一字不動）；`onDestroy()` 內僅加 `cancelPendingDoubleTap()`。
  - 臉遺失分支的雙擊取消見 F-2（同一機制，不重複列）。
- **理由**：待發第二下「已證實殘留＋已有具名化即能消除」符合五項門檻；
  中斷／暫停清意圖為 DCL-18 明文（「立即取消目前操作並清除待執行意圖」），
  且清單皆為既有欄位／既有取消函式，無新語義。
- **相容性影響**：無中斷時行為不變（第二下仍在 150 ms 後執行；重複雙擊
  時舊第二下被新第二下取代，舊行為下舊第二下會先執行一次——此為
  150 ms 內的邊界情形，改為「單一待發第二下」是本修復的意圖，
  須實機確認無連點體驗倒退）。暫停／停止／中斷後不再重播舊意圖，
  屬規格要求。
- **回歸風險**：中。`cancelPendingGesture()` 在暫停路徑多清了
  `currentProgress`／dwell（暫停後面板「重啟」dwell 不吃 `screenDwell`，
  故不影響重啟）；在 `requestPauseForCalibration` 回呼內多清 dwell／
  swipe／blink（校正期間樣本本就被 `pausedForCalibration` 擋下，
  故不影響校正）。最大殘留風險：進行中的系統手勢（已 `dispatchGesture`
  且未完成者）仍無法由 App 取消（無取消 API，`onCancelled` 由系統驅動；
  狀態層隔離見 F-8），
  本修復不宣稱已解決系統級取消。
- **驗證方式**：靜態核對（全部呼叫點皆主執行緒；`removeCallbacks` 只針對
  具名持有）＋ CI 編譯；實機：雙擊後 150 ms 內暫停／停止／中斷，
  確認第二下不執行；`onInterrupt` 觸發路徑（系統中斷）實機誘發。
  harness 無法覆蓋（Handler／AccessibilityService），不偽造測試。
- **明確未做（仍屬 P-07）**：統一 GestureArbiter／Dispatcher、全部
  `dispatchGesture` 呼叫收斂至單一守衛、單一飛行中手勢鎖定（以
  `onCompleted` 計）、四個 dispatch 的 `onCancelled` 回呼。理由：收斂與
  鎖定皆非消除上述已證實殘留所必需，且鎖定語義涉及系統回呼時序
  （第二下在第一下 `onCompleted` 延遲時可能被誤丟），未經實機驗證
  不得擅加（見 §3）。（2026-11-23 補充指令確認 DCL-04 後，狀態機核心
  與四處接線已改列 F-8 實作；逾時排程與裝置驗證仍屬提案，見 §3。）

### F-4 — R-1 撤回同意：CSV 當下檔案丟棄（privacy-security-report §5.2）

- **原始問題**：`settingsListener`（舊 `:65-68`）只重載設定＋同步影格開關，
  不呼叫 `GazeLog.close()`、不重置 `pendingUpload`；關閉監控開關後，
  下一幀 `record(enabled=false)` 即以預設 `upload=true` 關檔，
  當下檔案（含撤回後寫入的幀）照常上傳（K-11／R-1）。
- **影響範圍**：僅「開 → 關」轉換。開啟狀態下的任何設定變更（改邊緣開關、
  改 dwell 等）不得觸發關檔；關 → 開、關 → 關亦不觸發。
- **修改檔案**：
  - `GazeAccessibilityService.kt:65-84`：listener 內先取
    `wasRemoteUploadEnabled = remoteUploadEnabled()`，重載設定後，
    以 `GuardDecisions.shouldDiscardCurrentFileOnRevoke`（`GuardDecisions.kt:43-49`）
    判定轉換，僅在開 → 關時呼叫 `GazeLog.close(upload = false)`。
    `close(upload=false)` 本身關檔且不提交上傳、並將 `pendingUpload`
    重置為 false（`GazeLog.kt:139-150` 既有語義，無新 API）。
  - 影格路徑**未動**：`setVerifyCapture` 既有語義（兩開關皆關才
    `endSession`）＋`storeAndUpload` 執行時重查 `uploadEnabled`
    （`VerifyFrames.kt:173-192`）已使「排隊中未執行影格不上傳」成立；
    部分關閉時不結束時段、不清空庫存為既有正確語義（本機留存面），
    不得擅改。已提交至 `uploadExecutor` 的任務無法撤回，見 §3 提案。
- **理由**：轉換判定為純布林邏輯、關檔函式為既有 API、行為改變只發生
  在撤回轉換當下，符合五項門檻；「開啟狀態下行為不得改變」以轉換守衛保證。
- **相容性影響**：撤回後當下檔案不再上傳（改為本機留存，檔名不刪；
  使用者仍可用設定頁「清除紀錄」刪檔，`GazeLog.clearAll` 不動）。
  120 秒輪轉的正常上傳（監控開啟期間）完全不變。已上傳的伺服端資料
  無刪除路徑，現況不變（見 §3）。
- **回歸風險**：低—中。`close(upload=false)` 只做 flush／close，不讀檔、
  不發請求；listener 執行緒與 `record()` 同為主執行緒路徑（設定頁在主執行緒
  儲存），無新增競態。殘留窗口：撤回發生在「已提交上傳任務、尚未執行」
  之間時，該任務仍會執行（`uploadExecutor` 無取消 handle），屬 §3 提案範圍。
- **驗證方式**：harness `GuardDecisionsTest` 真值表（4 組合）；靜態核對
  （listener 內舊值／新值取值順序、全部 `close()` 呼叫點的 `upload` 參數）；
  實機：監控開啟中關閉開關並按儲存，確認當下檔案不上傳、下一段新檔
  亦不上傳、監控開啟時輪轉上傳仍正常（須以 Supabase 端／本機檔案＋
  Logcat 對照，不得只憑 UI 宣稱）。

### F-5 — R-2 `uploadFile` 顯式 flavor 守衛（privacy-security-report §4.3）

- **原始問題**：`GazeLog.uploadFile` 入口只檢查端點是否空白、無 flavor 守衛；
  Play 版不上傳完全依賴兩個呼叫端閘門（`remoteUploadEnabled()`／
  `applyVerifyCaptureFlags`），且 Play 版 APK 仍內含上傳程式與寫死金鑰常數
  （K-45／S-12）。
- **影響範圍**：僅 `uploadFile` 入口。全部呼叫點已核實共 2 處、皆在本檔內：
  `close()`（`:157`，CSV）與 `uploadFrame()`（`:170`，影格）——守衛覆蓋兩路徑。
- **修改檔案**：`app/src/main/java/com/example/eyecontrol/GazeLog.kt:174-185`：
  在 `uploadFile` 頂部加 `if (!BuildConfig.REMOTE_MONITOR_ENABLED) return`
  （置於既有空白端點檢查之前）。`BuildConfig` 與本檔同包名，無需 import。
- **理由**：一行防禦縱深、無新數值、無規格改變；側載版
  `REMOTE_MONITOR_ENABLED=true` 時守衛恆通過，行為一字不變。
- **相容性影響**：側載版零影響。Play 版：即使未來某呼叫點繞過呼叫端閘門
  而提交上傳任務，`uploadFile` 亦直接返回、不發請求；本機寫檔面不變
  （Play 版仍寫 CSV／影格，見 privacy-security-report §4.2，現況維持）。
- **回歸風險**：低。唯一風險為 `BuildConfig` 欄位在某 variant 缺失——
  兩 flavor 皆定義該欄位（`app/build.gradle.kts:43,49`），CI 兩 variant
  編譯即驗證。金鑰常數仍在 APK 內（程式碼共用），本修復不宣稱已移除。
- **驗證方式**：靜態核對（呼叫點窮舉 grep `uploadFile`／`uploadFrame`、
  兩 flavor 欄位定義）；CI：`assembleSideloadDebug`＋`bundlePlayRelease`
  皆須通過；實機／merged manifest：Play 版確認無 INTERNET 權限且無上傳請求
  （網路抓包，見 §4 未驗證清單）。

### F-6 — R-3 備份排除規則：僅三類診斷檔案（privacy-security-report §3.16）

- **原始問題**：`allowBackup="true"` 且全樹無備份排除規則；6 份 prefs 與
  三類診斷檔案原則上皆在預設備份範圍內，診斷 CSV／眼區影格／閃退報告
  可能隨系統備份離開裝置並還原（K-20／S-17）。
- **影響範圍**：僅備份範圍。`allowBackup` 維持 `true`；
  SharedPreferences（校正模型、條件歷史、使用者設定）一律不排除，
  備份還原後校正與設定維持現況行為。
- **修改檔案**：
  - 新增 `app/src/main/res/xml/backup_rules.xml`（`<full-backup-content>`）：
    `<exclude domain="file" path="gaze-logs" />`、
    `<exclude domain="file" path="verify-frames" />`、
    `<exclude domain="file" path="crash-reports" />`。目錄名與程式常數
    逐一對照：`GazeLog.DIR = "gaze-logs"`（`GazeLog.kt:27`）、
    `VerifyFrames.FRAMES_DIR = "verify-frames"`（`VerifyFrames.kt:289`）、
    `CrashReporter.DIR = "crash-reports"`（`CrashReporter.kt:17`），皆為
    `filesDir` 下子目錄（`domain="file"`）。
  - 新增 `app/src/main/res/xml/data_extraction_rules.xml`（Android 12+）：
    `cloud-backup` 與 `device-transfer` 皆排除同一三目錄（同一範圍，
    不得只排雲端備份而漏裝置間傳輸）。
  - `app/src/main/AndroidManifest.xml:15-17`：`<application>` 加
    `android:fullBackupContent="@xml/backup_rules"` 與
    `android:dataExtractionRules="@xml/data_extraction_rules"` 兩個屬性，
    其餘宣告一字不動。
- **理由**：排除範圍已逐項核實僅限三類診斷檔案、不碰校正／設定，
  符合任務書 R-3 的動手條件；manifest 編輯為任務書明示允許。
- **相容性影響**：備份還原後，三類診斷檔案不再隨備份帶到新裝置
  （舊備份副本不受新規則影響，屬平台行為）；校正／設定備份行為不變。
  條件歷史（`condition_history` prefs）**未排除**——任務書 R-3 限定範圍
  僅三類診斷目錄，條件歷史是否排除屬另案決定（privacy-security-report
  §9 R-3 曾建議一併排除，本任務不擅自擴大，見 §3）。
- **回歸風險**：中。XML 屬性／規則語法錯誤會在編譯期暴露（CI 驗證）；
  真正的備份／還原行為（哪些檔案實際被取走、還原後開關狀態）只能
  實機驗證，本任務不宣稱已驗證。`domain="file"` 的目錄排除語義以
  Android 備份規則文件為準，merged manifest 檢查為 CI 後必做項。
- **驗證方式**：本任務已做 XML 良好性解析（4 檔皆 parse 通過）＋
  目錄名常數對照；待做：CI 編譯、merged manifest 檢查、實機備份／還原
  測試（備份前後 `filesDir` 內容與還原後 prefs 對照）。

### F-7 — T-02 重啟恢復重查（DCL-05 最小部分，無持久化格式）

- **原始問題**：`onServiceConnected` 不檢查任何舊旗標，一律
  `deriveInitialFlowState()` 後 `startEngine()`；`startEngine` 守衛只看
  記憶體旗標，不重查相機權限／服務啟用狀態（dcl-mapping §4.1 DCL-05）。
  行程重建後即無條件自動恢復，與「自動嘗試恢復＋重查＋說明原因」方向相反。
- **影響範圍**：僅行程重建／服務重綁定的 `onServiceConnected` 路徑。
  `resumeAfterCalibration`（校正交接恢復）、`resumeTracking`（手點恢復）、
  `attemptRecovery`（RECOVERY 驗證）皆不動。
- **修改檔案**：
  `app/src/main/java/com/example/eyecontrol/GazeAccessibilityService.kt:340-381`
  （`onServiceConnected`）：`deriveInitialFlowState()` 之後、`startEngine()`
  之前，加重查分支——`isServiceEnabled(this)`（既有精確比對，
  `:1776-1785`，按 `packageName` 精確比對、非子字串）與相機權限
  `checkSelfPermission(android.Manifest.permission.CAMERA)`；
  任一未通過即不啟動引擎，`Log.w`＋`syncFlowState(Failure(原因), ERROR)`，
  原因文字明示「請確認相機權限已授予且凝視服務已開啟」。
  新增 import 僅一行：`android.content.pm.PackageManager`。
- **理由**：重查只用既有判斷函式與平台權限查詢，無持久化格式、無新
  prefs key、無新數值；未通過時進 ERROR 與 `startEngine` 同步失敗路徑
  同一語義，Dashboard 閘門（`canStartTracking` 以非 ERROR 判定）可直接沿用。
- **相容性影響**：權限已授予＋服務已開啟的正常重啟，行為一字不變
  （直接 `startEngine()`）。權限被收回後重啟：舊行為為無條件啟動引擎
  （綁定失敗只記 Log、無可觀測信號），新行為為 ERROR＋原因、使用者
  須手點恢復——此為 DCL-05 要求的方向改變，不得視為倒退。
  `userStopped`／`inRecoveryMode` 仍未持久化（K-30 維持現況，見 §3 P-03）。
- **回歸風險**：中。`isServiceEnabled` 在 `onServiceConnected` 時點理論上
  恆真（系統剛依啟用設定綁定服務），但若某裝置／系統版本在綁定時點
  尚未寫入設定字串，將誤判為 ERROR——使用者仍可手點「開始眼控」恢復
  （`resumeTracking` 不走此重查），且 ERROR 原因文字明示排查方向。
  此誤判可能性須列入實機驗收（多裝置／重啟後狀態對照），不得宣稱已排除。
- **驗證方式**：靜態核對（重查函式既有實作、Failure 事件可由任一態進
  ERROR 的 `syncFlowState` 語義）＋ CI 編譯；實機：權限授予／收回兩種
  狀態下重啟服務（行程重建），對照 Dashboard 狀態與是否自動恢復；
  裝置重啟後的平台綁定行為仍待實機驗證（dcl-mapping §7 待定位）。

### F-8 — DCL-04 集中派發守衛：唯一 ID／過期隔離／單次清理／不重播（2026-11-23 補充指令，選 A 已確認）

- **原始問題**：四處 `dispatchGesture`（`performScroll`／`performTap`／
  `performLongPress`／`performSwipe`）皆只有 `onCompleted`、無 `onCancelled`
  覆寫、回傳值忽略（全樹 `onCancelled` 零命中，K-62；設計文件
  `gesture-dispatch-state-machine.md` §0 親自 grep 記錄）。舊回呼可在
  新手勢開始後改變狀態（例如閃光）、取消／失敗後無統一清理、被拒派發
  無人知曉。
- **影響範圍**：僅派發狀態機層。筆畫內容／時長（0／60／500／800 ms）
  一字不動；`dispatchGesture` 呼叫本身不動；不新增逾時計時器（見下）；
  不新增佇列／重試／重發。雙擊第一下的閃光在第二下搶占後不再顯示
  （舊回呼被隔離的直接後果，純視覺回饋，見回歸風險）。
- **修改檔案**：
  - 新增 `app/src/main/java/com/example/eyecontrol/GestureDispatchGuard.kt`
    （純 Kotlin，零 Android 依賴）：兩態狀態機（IDLE／IN_FLIGHT）、
    嚴格遞增永不重用的 `Long` ID、唯一終端轉換點 `finishTerminal`
    （狀態回 IDLE、ID 清空、`cleanupCount` 加一，清理結構上只執行一次）、
    `begin()`（搶占舊手勢，舊 ID 失效並計一次 `SUPERSEDED` 清理）、
    `onDispatchReturned`（拒絕即終端）、`onCompleted`／`onCancelled`／
    `onTimeout`（僅進行中 ID 生效，過期一律忽略）、`onInterrupted()`
    （中斷後回 IDLE，等待新 `begin()`，永不重發）。逾時只有決策函式，
    計時器排程未接線（須一個毫秒值，不得發明，見 §3 P-04T）。
  - `GazeAccessibilityService.kt:270`：單一欄位 `dispatchGuard`。
  - 四處呼叫點（`:1646,1667,1703,1727`）：`begin()` 取 ID → 包裝回呼
    （`onCompleted` 只在守衛回傳 true 時閃光；新增 `onCancelled` 覆寫
    轉交守衛）→ `onDispatchReturned(token, accepted)`。回呼在
    `dispatchGesture` 內同步觸發的邊界情形亦安全（終端後
    `onDispatchReturned` 視為過期，無害）。
  - `cancelPendingGesture()`（`:1336`）：首行加 `dispatchGuard.onInterrupted()`，
    覆蓋暫停／停止／中斷／校正交接全部呼叫點；`onDestroy()`（`:1747`）
    亦加 `onInterrupted()`（服務停止情境）。
- **理由**：DCL-04 十項皆為已確認文字；守衛為純狀態機、無新數值
  （ID 為計數器、逾時值未引入）、無規格改變；接線每處皆為同一模板，
  無行為分支新增。五項門檻中 (d) 項以「逾時排程不接線」為界，
  排程值待批，不得以任意毫秒數充數。
- **相容性影響**：正常單一手勢流程不變（完成即閃光、清理一次）。
  改變只在多手勢／取消／拒絕邊界：被搶占的舊手勢不再閃光；被拒派發
  立即終端清理（舊行為：回傳值被忽略、狀態殘留至下一次回呼）。
  逾時情境行為不變（無排程，舊行為維持）。
- **回歸風險**：低—中。狀態機本身無鎖、無執行緒，與既有回呼同一執行緒
  路徑；最大風險為回呼時序假設（系統回呼是否皆在主執行緒）——既有
  程式碼已在 `onCompleted` 內直接碰 `cursorView`（僅 `post` 閃光），
  守衛呼叫不新增執行緒假設。雙擊第二下搶占第一下的閃光抑制，須實機
  目視確認無體驗倒退。系統側進行中的手勢本身無法由 App 取消
  （`dispatchGesture` 無取消 API），守衛只做狀態層隔離，不得宣稱為
  系統級取消。
- **驗證方式**：harness `GestureDispatchGuardTest` 11 項（回傳 false、
  onCancelled、onCompleted、逾時決策、Service 停止、暫停、新手勢搶占、
  過期回呼、清理單次性、不重播，見 §4）；靜態窮舉 `dispatchGesture`
  呼叫點（仍為四處，無旁路）；CI 編譯＋實機（取消／搶占／拒絕情境）
  待做。

## 2. 已核實、未改碼（V-1～V-3）

### V-2 — DCL-02 邊緣開關八項：程式行為已核實，未改碼（2026-11-23 補充指令，選 A 已確認）

- **核實結果（逐項，皆親自讀碼）**：(1) 左右獨立：五鍵獨立
  （`GazeSettings.kt:73,78-81`，讀取 `:225-229`）；(2) 關一側不影響另一側：
  控制器按邊檢查，全樹無改寫他邊鍵的程式；(3) 總開關存在；
  (4) 總關全停：控制器 `!config.enabled` 分支＋設定頁分項列停用；
  (5) **總開關重開恢復先前分項狀態（補充指令特別指定核查）**：
  `refreshEdgeGestureRows()` 只改 `isEnabled`／`alpha`、永不改 `isChecked`
  （`SettingsActivity.kt:215-228`），儲存讀 `isChecked`（`:298-302`），
  總關不寫入分項鍵，服務每幀重組 config（`GazeAccessibilityService.kt:1272-1275`）
  ——**缺口不存在**，不需修碼；(6) 單側錯誤不關另一側：檢查失敗只取消
  當前候選，無任何錯誤路徑寫開關旗標；(7) 安全條件：三項檢查每幀獨立
  評估＋幾何改變取消＋離開帶解除鎖定，服務端各分支在邊緣更新
  （`:901`）前提早返回並 `cancelEdgeGesture()`；(8) 獨立保存重啟不遺失：
  五鍵各自存取，無遷移重置。實作涵蓋四邊，為「左右」要求的超集。
- **決定**：不改程式碼（行為已符合）。新增純測試 `EdgeSwitchRestoreTest`
  3 項（總關→重開恢復、關一側不影響另一側、總關中斷候選後重開重新累積），
  見 §4。六項驗收情境的**實機驗證未做**，留待 STEP 6。
- **對照更新**：`docs/dcl-mapping.md` DCL-02 已由「無法確認」改為
  「已實作（實機驗收未做）」，P-01 已解決。

### V-3 — DCL-03 稽核覆蓋確認：未改任何聲明文案（2026-11-23 補充指令，選 A 已確認）

- **核實結果**：稽核文件已覆蓋 DCL-03 (1)～(5) 項——`privacy-data-flow.md`
  §1／§2（11 種資料流、3 個 HTTPS 端點、逐流內容／用途／觸發／目的地／
  本機保留期限）、§3／§4（Play／Sideload 分開稽核）、§2.1～§2.11（含眼區
  影格／追蹤資料／座標特徵／診斷／Crash／Supabase／背景佇列／更新檢查／
  第三方 SDK 零命中）與生命週期（產生→儲存→排隊→傳輸→刪除，含已載明的
  刪除缺口）；`privacy-copy-audit.md` 已定位三處絕對聲明；
  `privacy-security-report.md` §3.1／§4／§10（R-14／T-6 流量驗證待做）。
  唯一稽核缺口：**伺服端保留期限無法由本樹驗證**（`privacy-data-flow.md:364`），
  已在對照中明載，不得宣稱為已稽核。
- **決定**：不改任何聲明文案（(7)(8) 要求稽核證據齊備後才改，且須另批）。
  撤回機制缺口（R-1）已在 F-4（CSV 未提交部分）與提案 P-R1b（已提交任務／
  已上傳資料）範圍內。**網路流量驗證未做**，記錄為未驗證，不得宣稱
  「完全不會上傳」或以靜態稽核冒充流量證據。
- **對照更新**：`docs/dcl-mapping.md` DCL-03 已由「無法確認」改為「部分實作」。

### V-1 — T-11 首次校正詢問／重試頁：觸控路徑已可用，不新增 UI

- **核實結果**：選擇階段使用真實 `MaterialButton`
  （`CalibrationActivity.kt:601-638` `showChoices`，`setOnClickListener`、
  `contentDescription = label`），可觸控點按；無舊模型時
  `onChoiceSample`（`:519-564`）在 `choiceModel == null` 時直接返回、
  不做注視判定，`gazeHintLine()`（`:507-515`）明示「可用手指點按鈕，
  或按系統返回離開」。採樣期（`Phase.SAMPLING`）自繪 View 無按鈕、
  取消只能靠系統返回——此為 D-4 待決範圍（D1-9），本任務不新增 UI、
  不新增觸控落點。
- **決定**：不改碼。首次校正純眼控可達性的缺口（R6）仍在，屬 P-02／D-4
  決定範圍，不得以觸控可用宣稱為眼控可達。

## 3. 提案待批清單（本輪未實作，須批准／決定後才動手）

| # | 提案 | 來源 | 未動手原因（五項門檻哪一項未過） |
|---|---|---|---|
| P-05 | 眨眼點擊個人化意圖校正方法、品質評估方法、FP／FN 評估協議與啟用門檻；開關開啟須以「完成校正＋通過評估」為閘門 | T-01 | 需發明方法與門檻數值（d 項未過）；F-1 的開關開啟不等於閘門通過，已在程式註解與設定頁副標明示 |
| P-04 | 校正品質儲存門檻（是否取 60／120／200 px、是否另立儲存門檻）；`finishCalibrationInternal` 未達門檻不存檔 | T-03 | 需決定門檻值本身（d 項未過）；D-12 未決 |
| P-09a | 音量鍵緊急停止方案（是否全域攔截、裝置允許範圍、自訂方式） | T-07 | 需先確認 Android／裝置允許範圍，靜態無法確認（c 項未過）；不得假設全域攔截 |
| P-09b | 手點恢復（`resumeFromRecovery`）是否須與眼控 dwell 共用臉部／眼睛凍結語義；點擊路徑敏感欄位守衛 | T-07／T-14 | 前置的敏感判斷（DCL-44）不存在；守衛設計待批 |
| P-07（部分已落地，餘項待批） | 統一手勢仲裁／Dispatcher 剩餘部分：逾時計時器排程、進行中鎖定語義的裝置驗證、`onInterrupt` 完整生命週期處理 | T-05／T-13、DCL-04(3)(8) | 守衛狀態機與四處接線已於 STEP 3 落地（F-8）；逾時排程須一個毫秒值（d 項未過）；鎖定語義與系統級取消限制須實機驗證。設計文件 `gesture-dispatch-state-machine.md` 其餘內容仍為設計，不得冒充實作。 |
| P-04T | 手勢派發逾時計時器排程（逾時毫秒值、排程位置；逾時只清理、不重發，DCL-04(9)） | DCL-04(3) | 須決定毫秒值與排程機制（d 項未過）；守衛 `onTimeout` 決策已實作並已測試，接線後直接可用。 |
| P-DEV-1 | 濾波前極端原始預測守衛（主路徑）：拒絕／凍結超出合理範圍的原始預測，不讓極端值進入 OneEuroFilter | 裝置診斷證據（見 §6，未經程式碼交叉驗證） | 程式證據：主路徑無濾波前拒絕（見 §6）；但守衛的範圍值、拒絕後行為（凍結／重置濾波器／丟幀）與量測方法皆須決定批准後才實作（d 項未過）。**不得改預測演算法、不得改濾波參數。** |
| P-10a | 邊緣帶寬（50dp）與品質門檻的實機量測計畫（E-9／E-10）、正式預設值 | T-08／T-09 | 量測未做，不得定正式預設（d 項未過）；本輪程式一字未改 |
| P-10b | 邊緣「寬度」可調設計（粒度、範圍、安全最小／最大值、預設） | T-12 | 現況只有開／關開關，無寬度設定機制可接線；新設定項＋UI＋範圍值皆須設計與批准（d／e 項未過） |
| P-03 | 重啟恢復持久化格式與寫入時機（D-8）：`userStopped`／`inRecoveryMode` 是否持久化、新 prefs 檔或現有 key、損毀回退；`restart-recovery-policy.md` 十步流程按 DCL-05 改寫後落地 | T-02 | 持久化格式未決，不得發明（d／e 項未過）；F-7 只做無格式重查 |
| P-08 | 臉遺失／恢復清除範圍：是否清 `hasValidCursor`（現況游標留原地變灰為有意顯示語義）；臉重現穩定門檻（連續幀數／dispersion）量測值 | T-06 | 顯示語義須一併決定、門檻值須量測（d 項未過） |
| P-06 | DCL-06 七態相機狀態機設計與實作（取代九態提案） | T-04 | 設計待批；本輪未動相機管線 |
| P-11 | DCL-24～46 未來實作整體設計（吸附／混合辨識／敏感辨識／清除／降級／恢復），辨識資料管線與診斷／監控管線分離 | T-10 | 本輪不實作（R-6）；敏感畫面判斷程式落點為零，不得以現有管線冒充 |
| P-02 | D-4 剩餘決定（形式／秒數／位置／判定機制）與 DCL-01／08 相容方式；D1-9 維持僅常數預留 | R-1／T-11 | 決策未定；D1-9 本輪絕未動（見 §5） |
| P-R1b | 已提交至 `uploadExecutor` 的上傳任務取消 handle（撤回後「提交後、執行前」窗口）；已上傳伺服端資料刪除路徑；影格本機刪除 UI | R-1／R-2 | 需新增取消／刪除機制設計（非最小變更）；`close(upload=false)` 只覆蓋未提交部分，已在 F-4 明示 |
| P-R3b | 條件歷史（`condition_history` prefs）是否納入備份排除；備份還原實測 | R-3 | 任務書 R-3 限定範圍僅三類診斷目錄，擴大範圍須另行決定 |

## 4. STEP 4 測試結果

- **執行方式**：現有 kotlinc＋JUnit harness
  （`~/workspace/eyecontrol-toolchain/harness/run-pure-tests.sh`；本機 Gradle
  跑不起來為已知限制，不得假裝編譯）。新增純來源檔已追加至
  `harness/extra-pure-sources.txt`：`GuardDecisions.kt`、`GazeEvaluator.kt`、
  `GazeSettings.kt`、`GestureDispatchGuard.kt`（4 行，harness 設定檔，不在 repo 內）。
- **基線（改碼前親自重跑）**：`OK (110 tests)`（7 個測試類別）。
- **本輪第一階段（F-1～F-7，改碼後親自重跑，全過）**：`OK (127 tests)`（10 個測試類別）。
  新增 17 項：`GuardDecisionsTest` 6 項（眨眼判定 5＋撤回真值表 1 函式
  4 組合）、`GazeEvaluatorTest` 7 項（空輸入／尺寸不符／訓練網格誤差與
  計數／按目標分組／單組 LOO 為 NaN／空或不符 LOO 為 NaN／LOO 相對關係）、
  `GazeSettingsDefaultsTest` 4 項（預設關／`clamped()` 保留／眨眼窗預設值
  不變／其他預設值不變）。既有 110 項零修改、零失敗。
- **本輪最終（F-8＋V-2 後親自重跑，全過）**：`OK (141 tests)`（12 個測試類別）。
  再新增 14 項：`GestureDispatchGuardTest` 11 項（DCL-04 十項情境：回傳 false、
  onCancelled、onCompleted、逾時決策、Service 停止、暫停、新手勢搶占、
  過期回呼，另加清理單次性與不重播斷言）、`EdgeSwitchRestoreTest` 3 項
  （DCL-02 總關→重開恢復、關一側不影響另一側、總關中斷候選後重開重新累積）。
  127 項既有測試零修改、零失敗。
- **中途失敗與處理（誠實記錄）**：首跑 127 項中 1 項失敗——
  `leaveOnePointOut_trainedGrid_finiteSmallError`，因測試自編 `< 50 px`
   bound；而合成網格實測 LOO 誤差為 55.36 px（10 個不同目標點、
  訓練集內誤差僅 0.02 px）。此為測試 bound 發明不當、非評估程式錯誤：
  已將該測試改為不斷言任何 px 門檻、只斷言「LOO 有限且比訓練集內
  誤差更悲觀」（評估語義本身），重跑後全過。實測數值記錄於此，
  不得讀為品質門檻（門檻待 P-04／P-10）。
- **覆蓋邊界（未測＝未宣稱）**：
  - 服務本體／Activity／引擎／overlay／`dispatchGesture` 系統回呼／`GazeLog` 檔案
    寫入與上傳／備份規則：皆為 Android 類別或平台行為，harness 不可測；
    只做靜態核對＋CI 編譯＋實機驗證（見各 F 項驗證方式）。雙擊第二下
    的取消時序、`onInterrupt` 誘發、重啟重查誤判可能性、守衛接線後的
    系統回呼時序，皆列實機項。守衛「狀態機」本身已純測 11 項，
    不得與「接線已實機驗證」混淆。
  - Camera Lifecycle 現有守衛（綁定失敗只記 Log、無 generation 保護、
    `stop()` 釋放順序）：無純邏輯可抽取、不改引擎，harness 無新增測試；
    現況守衛只以靜態閱讀確認（見 dcl-mapping DCL-06），CI＋實機驗證待做。
  - Tracking Recovery：`RecoveryGate` 既有 6 項已窮舉 2×2×2，本輪未重複
    加測；服務端重啟鏈（`attemptRecovery`／`updateRecoveryTarget`）不可純測。
  - Dwell：既有 `DwellTrackerTest` 11 項已覆蓋錨點／速度／衰減／重置，
    本輪未重複加測；面板／把手線性 dwell 不可純測。
  - Sensitive Screen（DCL-41～46）：程式中無任何敏感畫面判斷落點
    （grep `FLAG_SECURE`／`MediaProjection`／`sensitive`／`password` 零命中），
    無可測功能，不偽造測試；屬 P-11。
  - **Android 全量編譯未驗證**：`gradle testSideloadDebugUnitTest`／
    `assembleSideloadDebug`／`bundlePlayRelease` 皆未在本機執行（沙箱限制），
    留待 CI；本紀錄不宣稱編譯通過。

## 5. 絕對禁區核對（本任務結束時點）

- 未更換演算法、未改濾波參數（`OneEuroFilter.reset()` 只清狀態）、未改
  校正模型、未改預測演算法、未改智慧吸附距離（無此功能）、未改信心閾值、
  未加複雜 AI 模型、未發明任何閾值／數值（150 ms 雙擊間隔、120 秒輪轉等
  皆沿用現況值；測試 bound 除已修正的 LOO 一項外，皆為既有行為值）。
- 五個不能動維持：九點資料流（`SETTLE_MS`／`SAMPLES_PER_POINT`／
  `KEEP_RATIO` 常數與順序未動，`CalibrationActivity.kt` 全檔未動）、
  22 維特徵契約（`GazeFeatureExtractor.DIM = 22` 未動）、
  `requestPauseForCalibration` 協議（簽名與 `===` 身分確認、回呼順序未動，
  僅回呼內加清意圖一行）、`buildConfig = true`
  （`app/build.gradle.kts:111`）、`noCompress += "task"`（`:115`）。
- D1-9 絕未動：`CalibrationActivity.kt` 全檔未修改；4 個預留常數
  （`CANCEL_TARGET_RESERVED*`，`:1117-1123`）維持僅常數預留、零引用
  （註解提及除外）、無事件、無視覺元素。
- 版本維持 `versionCode = 13`／`versionName = "0.5.9"`；
  `.github/workflows/` 未動；未讀取、未寫入任何 secrets／keystore。
- 修改檔案總覽（僅此 14 個 repo 內檔案＋1 個 harness 設定檔）：
  `GazeAccessibilityService.kt`、`GazeSettings.kt`、`GazeLog.kt`、
  `SettingsActivity.kt`、`GuardDecisions.kt`（新增）、
  `GestureDispatchGuard.kt`（新增，F-8）、
  `activity_settings.xml`、`AndroidManifest.xml`、
  `backup_rules.xml`（新增）、`data_extraction_rules.xml`（新增）、
  `GuardDecisionsTest.kt`（新增）、`GazeEvaluatorTest.kt`（新增）、
  `GazeSettingsDefaultsTest.kt`（新增）、
  `GestureDispatchGuardTest.kt`（新增，F-8）、
  `EdgeSwitchRestoreTest.kt`（新增，V-2）、本文件
  `docs/step3-fix-log.md`（新增）、`docs/dcl-mapping.md`（僅 DCL-02／03／04
  三項＋統計＋§7 登記＋P-01，見 V-2／V-3 與 §4.1 對照更新）；
  另 `extra-pure-sources.txt`（toolchain）加 4 行。
  `docs/device-diagnostic-evidence.md` 為擁有者（parent）寫入的裝置證據檔，
  本任務未修改其內容（見 §6）。

## 6. 附錄：實機診斷證據的處理（裝置數據解讀、未經程式碼交叉驗證）

> 本節引用的數據來自 `docs/device-diagnostic-evidence.md`（監看排程
> 2026-11-23 12:18 CST 回報，Supabase `gaze_logs`，台北時間 11:48–12:17，
> 15 段、12,172 筆）。**以下皆為裝置數據解讀、未經程式碼交叉驗證，
> 不得寫成已證實的程式缺陷。** 單批數據不得推論全體使用者行為；
> 臉率低的成因（裝置位置／光線／管線停滯）未區分，不得單一下結論。

- **數據摘要**：人臉出現率 12.2%（1,484／12,172），6 段為 0%；
  `sens` 恆 1.00、`offX`／`offY` 全 0.0；原始預測經常超出螢幕
  （X −12,474～10,211、Y −18,531～11,663）；游標箝制在邊緣，
  有臉樣本 52.4% 落在 X=0。監看診斷標籤：(d) 人臉遺失為主因、
  (b) 濾波／箝制吃掉移動、(c) 左／下偏移、(e) 修正未啟動而非過度。
- **程式碼側交叉核對（僅核對機制存在與否，不判定因果）**：
  主路徑以 `m.predict` 取原始預測，經條件偏移與靈敏度放大後**直接**
  餵入 `OneEuroFilter`，濾波後才箝制在螢幕內
  （`GazeAccessibilityService.kt:654-721`）；主路徑**無濾波前極端值拒絕**，
  僅有影像降級幀凍結（`isDegraded` 分支）與特徵有限檢查；極端值守衛
  只存在於邊緣控制器（`rawWithinMargin`，螢幕 ±15%，僅 gate 邊緣判決）。
  此核對只證明「主路徑無此守衛」這一程式事實，**不證明**本批數據的
  游標箝制由極端預測造成（濾波器狀態、模型、裝置姿態皆未在本批數據中
  區分，`sens` 恆 1.00 與 `offX`／`offY` 全 0 亦有多種成因）。
- **處置**：
  1. 建議方向 (1)「修人臉遺失後的恢復機制」：本輪 F-2 已清臉遺失殘留
     狀態；RECOVERY 恢復流程本身未動，不宣稱已修復，後續以程式碼證據
     與 STEP 6 實機數據判定。
  2. 建議方向 (2)「濾波前拒絕極端原始預測」：列為提案 P-DEV-1（見 §3），
     附驗證方式，不得擅自改預測演算法或濾波參數。
  3. 建議方向 (3)「臉率恢復前暫緩重校」：未列入 STEP 3 提案（屬重校政策
     決定，須擁有者另行決定），僅記錄於此，不得擅自實作。
