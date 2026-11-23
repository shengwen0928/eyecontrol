# EyeControl 重啟自動恢復政策（PHASE 7 K-30）

> **覆蓋聲明（DCL-05，擁有者正式確認，STEP 2 加註）**：本文件 §3.3／§4.2 的預設「重啟後恢復設定、不自動恢復眼控工作階段」，**已被 DCL-05 覆蓋**。DCL-05 要求：先前已啟用眼控時，系統**可自動嘗試恢復**；須遵守 Android 背景服務、攝影機及權限限制；不得恢復未完成校正、不得恢復舊手勢／點擊／滑動；須重新檢查權限及服務；無法恢復時須說明原因；不得宣稱 Android 保證所有重啟情境都能自動恢復。本文件的十步流程（§4）為按舊預設撰寫的政策設計，**尚未實作**；落地時須按 DCL-05 語義改寫（「嘗試恢復＋限制條件＋重查＋說明原因」，取代「不自動恢復工作階段」預設），並經批准（見 `dcl-mapping.md` §5 矛盾 T-02、§6 待批 P-03）。本聲明只加註、不改本文件其餘結論與內容。

> 性質：唯讀政策文件，只新增本文件，不改任何程式碼。
> 基線：`~/workspace/eyecontrol-p3`（v0.5.9 加未推送的 Phase 3 實作；遠端 main HEAD `c6b80964`）。
> 行號以 p3 樹為準，皆為撰寫時親自查證的現況行號。
> 本文件 §4 的十步流程與 §5 的測試清單為**政策設計（尚未實作）**，不得宣稱為現況；
> 現況描述（§1–§3）與政策設計在每節以「現況」／「政策」嚴格區分。
> 先前文件複用：`KNOWN_ISSUES.md` K-30（P1）、`PLAN.md` §1.1／§1.2／§3 D2-6、
> `STATE_DESIGN.md` §2.2 註（行程重啟語義明示為已知缺口）、
> `IMPLEMENTATION_NOTES.md` §3 已知缺口（K-30 維持現況，待 D-8 決定）、
> `CANCEL_CONTRACT.md`（取消路徑的釋放與返回語義，作對照）。
> 任務書提及的 §11.2／§11.3／§11.4 條文未見於本次收到的先前文件，來源標「待定位」；
> 本文件的 A／B 分類、十步流程、九項測試，依任務書給出的 §11.2 分類原則、
> §11.3 十步要點、§11.4 清單要求自行推導撰寫，**不得宣稱為 §11 條文原文**。
> 預設定義（任務書定稿，見 §4.2）與 K-30 現況的關係見 §3.3。

## 0. 問題一句話（K-30 現況）

`userStopped`、`inRecoveryMode`、`pausedForCalibration`、`currentMode` 皆為記憶體欄位，
行程重啟即丟失；`onServiceConnected` 不檢查舊旗標，一律呼叫 `startEngine()`，
**無論使用者之前是暫停、停止眼控、還是完全停止，皆自動恢復追蹤**——與使用者最後的
操作意圖相反。Phase 3 實作維持此現況並明示為已知缺口（見 §3.3），本政策為其
後續修復的設計依據，不得宣稱 K-30 已修復。

## 1. 重啟自動恢復的全部觸發路徑（現況映射）

### 1.1 觸發路徑總表

| # | 觸發路徑 | 觸發者 | 程式入口（現況行號） | 是否自動恢復追蹤（現況） |
|---|---|---|---|---|
| R1 | 使用者在系統設定中啟用無障礙服務（首次啟用） | 使用者＋系統綁定 | `onServiceConnected()`（`GazeAccessibilityService.kt:320` 起）→ `deriveInitialFlowState()`（`:343`）→ `startEngine()`（`:344`） | 是（首次啟用，恢復即啟動，符合預期） |
| R2 | 系統重建 Service（行程被殺／低記憶體回收後，系統重新綁定已啟用的服務） | 系統 | 同 R1（新服務實例，`instance` 重新賦值，`:323`） | **是，無條件**（見 §1.2） |
| R3 | 行程重建後，`Application.onCreate` 執行 | 系統（行程重建） | `EyeControlApp.onCreate()`（`EyeControlApp.kt:9-11`）：只安裝 `CrashReporter`，**不啟動引擎、不恢復任何旗標** | 否（本路徑本身不恢復；恢復發生在隨後的 R2） |
| R4 | 裝置重啟後，系統重新綁定已啟用的無障礙服務 | 系統（開機後綁定） | 同 R1（經系統綁定；本 App 無 `BOOT_COMPLETED` receiver，見 §1.3） | **是，無條件**（平台行為，待實機驗證，見 §1.3） |
| R5 | 使用者在系統設定中停用後重新啟用服務 | 使用者＋系統 | `onDestroy()`（`:1607` 起，停引擎、關 `GazeLog`、移除 overlay）→ 新實例 `onServiceConnected()`（R1） | 是（重新啟用，恢復即啟動，符合預期；但「停用前是暫停／停止」的意圖已丟失，見 §3） |
| R6 | 服務 `onCreate` 單獨執行（未緊接 `onServiceConnected`） | 系統 | `onCreate()`（`:315-318`）：只設 `LifecycleRegistry` 為 `CREATED`，**不建 overlay、不載設定、不啟動引擎** | 否（本路徑本身不恢復） |
| R7 | 校正／訓練頁結束後的服務接回（`resumeAfterCalibration`） | Activity 銷毀 | `onDestroy()`（校正頁 `CalibrationActivity.kt:865-873`；訓練頁 `TrainingActivity.kt:231-239`）→ `resumeAfterCalibration()`（`GazeAccessibilityService.kt:1688` 起）→ `startEngine()`（`:1699`） | 是（接回語義，非重啟語義；但若行程在校正期間重建，此路徑與 R2 交錯，見 §1.4） |
| R8 | `stopCompletely` 背景執行緒的晚到重啟檢查 | 程式內部（非系統重啟） | `stopCompletely` 的背景 `Thread` 結尾（`:1938-1955`）：若期間 `userStopped` 已被清且引擎為 null，在此 `startEngine()` | 是（競態補償，K-14 結構殘留；非重啟路徑，但與重啟語義交錯，列入邊界） |
| R9 | 螢幕方向改變（`onConfigurationChanged`） | 系統 | `GazeAccessibilityService.kt:348` 起：更新螢幕尺寸、重置濾波器、重置條件狀態、中斷邊緣手勢；**不重啟引擎、不重建 overlay** | 否（**不是**重啟路徑，列入以免混淆） |
| R10 | Activity 重建（旋轉／系統回收 Activity） | 系統 | 校正頁 `onCreate`（`CalibrationActivity.kt:158` 起，從第 1 點重採，見 §1.4）；訓練頁 `onResume` 重綁（`TrainingActivity.kt:213` 起）；主畫面 `onCreate` 只查更新、不啟動服務（`MainActivity.kt:240`） | 否（Activity 重建不恢復服務追蹤；校正頁重建只重採，不恢復舊進度） |

### 1.2 R1／R2 的現況程式序列（關鍵證據）

`onServiceConnected()`（`GazeAccessibilityService.kt:320-345`）：

1. `lifecycleRegistry.currentState = RESUMED`（`:322`）、`instance = this`（`:323`）。
2. `startForegroundService()`（`:326`，前台通知）。
3. `settings = GazeSettings.load(this)`（`:329`），註冊兩個 prefs 監聽
   （`settingsListener`、 `profileListener`，`:330-334`）。
4. 建游標 overlay 與控制面板（`addCursorOverlay`／`addControlPanel`，`:340-341`）。
5. `deriveInitialFlowState()`（`:343`）：以 `deriveFlowState(hasModel = CalibrationStore.exists(...))`
   推導初始態（`GazeAccessibilityService.kt:197-204`）。由於新實例的
   `userStopped=false`、`inRecoveryMode=false`、`pausedForCalibration=false`
   （欄位初始值，`:113-130`），推導結果只取決於「有無模型」：
   有模型 → `SERVICE_RUNNING`，無模型 → `NOT_CALIBRATED`。
   **舊旗標未被讀取——因為根本沒有持久化位置可讀**（見 §2）。
6. `startEngine()`（`:344`）：守衛只檢查當下記憶體旗標（`:392`），
   模型存在即建引擎、綁定相機、開始追蹤；無模型只記 `Log.w`（`:399`），
   引擎仍建立（`FaceTrackerEngine` 仍啟動，樣本仍進來，但主路徑遇 `model == null` 返回）。

**現況結論**：R2／R4 的自動恢復不是「某個分支忘了檢查旗標」，而是「旗標沒有持久化，
檢查無從談起」。任何修復都必須先有持久化位置（見 §4），不得只在 `onServiceConnected`
加一個讀取不存在的 key 的檢查（那只會永遠讀到預設值，假裝已修復）。

### 1.3 Manifest 宣告：無 receiver，無開機路徑（現況）

- `AndroidManifest.xml`（全檔 82 行）：宣告的元件只有 6 個 Activity、1 個 FileProvider、
  1 個無障礙服務。**無 `<receiver>` 宣告**，無 `BOOT_COMPLETED`、`PACKAGE_REPLACED`、
  或其他系統廣播接收器。
- 服務的唯一系統入口是 `<intent-filter>` 的
  `android.accessibilityservice.AccessibilityService` action
  （`AndroidManifest.xml:74-76`）＋ `BIND_ACCESSIBILITY_SERVICE` 權限保護。
- 全樹 grep `startService`／`startForegroundService` 作為**啟動呼叫**：零命中
  （`startForegroundService` 只作為私有方法名出現，內部呼叫的是 `startForeground`，
  `GazeAccessibilityService.kt:366` 起）。App 程式碼**從不自行啟動服務**；
  服務的生死完全由系統（使用者在系統設定中啟用／停用、系統回收／重建）決定。
- 裝置重啟後的恢復（R4）因此完全依賴平台行為：系統在開機後重新綁定已啟用的
  無障礙服務。本樹程式碼無法證實開機綁定的時機、順序、以及備份還原是否先於綁定生效，
  一律標「待實機驗證」，不得以程式碼推斷平台行為。

### 1.4 交錯路徑：行程在校正／交接期間重建

- 校正頁重建：`onCreate` 讀 `savedInstanceState` 的兩個整數檢查點
  （`CalibrationActivity.kt:206-215`），有已 commit 點數時顯示
  「上次校正中斷，已採的 N 點無法保留，從第 1 點重新開始」，`view.post` 內
  `startPoint(0)`＋`requestCameraHandover()`（`:218-228`）。
  特徵串列為新實例的空串列，舊進度**不恢復**（誠實重採，見 IMPLEMENTATION_NOTES 出入第 13 點）。
  但 `handoverGeneration`、`handoverPending`、`engineStarted` 皆為新實例初始值，
  舊實例的交接請求已隨行程死亡而消失，不會晚到（行程內 `Handler` 佇列不跨行程）。
- 服務重建與校正頁重建同時發生時：新服務實例的 `instance` 為新實例，
  校正頁的 `requestPauseForCalibration` 會作用於新實例（`pausedForCalibration` 立於新實例），
  舊實例的旗標已不存在。此交錯無跨行程殘留，但新服務實例仍會在
  `onServiceConnected` 先 `startEngine()`（R2），隨後才被校正頁的交接請求暫停——
  中間存在一個「短暫自動恢復追蹤」的窗口。政策設計須關閉此窗口（見 §4 步驟 3–4：
  相機維持停止、追蹤不自動恢復，直到使用者主動啟動）。

## 2. 旗標未持久化現況（證據清單）

### 2.1 未持久化的即時狀態（全部為記憶體欄位，行程重啟即丟失）

| 欄位 | 宣告位置 | 寫入點（現況） | 讀取點（重啟時） |
|---|---|---|---|
| `userStopped` | `GazeAccessibilityService.kt:118`（`@Volatile`，初始 `false`） | `stopCompletely` 立真（`:1930`）；`resumeTracking` 清假（`:1851`）；`stopTracking` 不碰（D2-1 語義分離） | **無**（`onServiceConnected` 不讀取） |
| `inRecoveryMode` | `:130`（`@Volatile`，初始 `false`） | `stopTracking` 立真（`:1890`）；`attemptRecovery`／`failRecovery`／`stopCompletely` 清假 | **無** |
| `pausedForCalibration` | `:113`（`@Volatile`，初始 `false`） | `requestPauseForCalibration` 立真（`:1654`）；`resumeAfterCalibration` 清假（`:1691`） | **無**（新實例初始 `false`，即「未暫停」） |
| `currentMode` | `:245`（初始 `ActionMode.TAP`） | `pauseTracking`／面板分支／`attemptRecovery` 等 | **無**（新實例初始 `TAP`，即「未暫停」） |
| `panelExpanded`、`handleArmed` 等面板／把手狀態 | `:247` 起（面板狀態區） | 主路徑各分支 | **無**（overlay 在 `onServiceConnected` 重建為預設收合態，`addControlPanel` `:462` 起） |
| `recoveryDwellAccumMs`、`recoveryArmed`、`recoveryVerifying` | `:134` 起（重啟目標狀態區） | RECOVERY 分支／`stopTracking`／`attemptRecovery` | **無** |
| `flowState`／`flowMachine` | `:170-177`（`@Volatile`，初始 `NOT_CALIBRATED`） | `syncFlowState` 各同步點；`deriveInitialFlowState` 重建 | **無**（新實例以 `deriveInitialFlowState` 重新推導，見 §1.2） |
| `engine`、`model`（`@Volatile`） | `:50-55` | `startEngine`／`profileListener` | **無**（新實例重新載入模型、重建引擎） |
| `cooldownUntil`、`swipeStartPoint`、`screenDwell` 進度、`anchorX／anchorY`、`dwellStart` | 主路徑狀態區（`GazeAccessibilityService.kt` 內分散宣告） | 主路徑各分支 | **無** |
| `lastSampleAtMs`、`lastFacePresent` 等快照事實 | companion 區（`:1712` 起） | `onSampleOnMainThread` | **無**（新實例初始 0／false，`trackingSnapshot()` 回「不新鮮」） |
| 校正頁／訓練頁的 `engineStarted`、`handoverGeneration`、`cameraRequestPending` | `CalibrationActivity.kt:75,107`；`TrainingActivity.kt:56,60` | 各 Activity 生命週期 | **無**（新實例初始值） |

### 2.2 已持久化的設定（重啟後會被讀回，見 §3 的 A 類）

SharedPreferences 清單（全樹 `getSharedPreferences` 呼叫點）：

| prefs 檔 | 讀取點（重啟時） | 內容 |
|---|---|---|
| `gaze_settings` | `GazeSettings.load()`（`onServiceConnected` `:329`、`FaceTrackerEngine.start` `:127`、校正頁 `onCreate` 等） | 全部使用者參數（見資料流文件 §2.6） |
| `calibration_profiles` | `CalibrationStore.loadModel／exists／getActiveProfileId`（`startEngine`、快照、引導等） | 三情境模型、中繼資料、目前情境 id |
| `condition_history` | `reloadConditionState`（`startEngine` 內經 `reloadConditionState`，`:405`） | 條件紀錄與基準 |
| `gaze_model`（舊） | `migrateIfNeeded`（首次讀取時） | 舊單一模型（僅遷移） |
| `eyecontrol_setup` | `WelcomeActivity.isFirstRun` | `welcome_seen` |
| `crash_report` | `CrashReporter.pendingReport` | `shown_*` 旗標 |

檔案留存（`filesDir/`）：`gaze-logs/`（CSV）、`verify-frames/`（影格）、
`crash-reports/`（閃退報告）。行程重建後，舊 CSV 檔案仍在磁碟上，
但 `GazeLog` 的 `pendingUpload`／`writer`／`currentFile` 皆為記憶體狀態（新實例初始值），
舊檔案**不會**在重啟後補上傳（無佇列、無重試，見資料流文件 §2.7）；新 `record()` 會開新檔。

### 2.3 持久化缺口的直接證據（程式自承）

- `GazeAccessibilityService.kt:122-127`（`inRecoveryMode` 宣告註解）：
  「已知缺口 K-30：本旗標與 `userStopped` 皆未持久化，行程重啟即丟失，
  `onServiceConnected` 不檢查舊旗標、現況自動恢復語義維持不變」。
- `GazeAccessibilityService.kt:192-196`（`deriveInitialFlowState` 註解）：
  「模型存在即 `SERVICE_RUNNING`（行程重啟不檢查舊旗標、現況自動恢復語義…）」。
- `stopCompletely` 註解（`:1922-1924`）與 `STATE_DESIGN.md` §2.2 註同旨。
- `PLAN.md` §1.1「狀態儲存」列：**「沒有任何『目前流程狀態』的持久化」**。

## 3. A／B 兩類劃分（§11.2 原則，來源待定位，見文件頭）

### 3.1 A 類：可安全恢復的設定（重啟後可讀回，須驗證後才生效）

| # | 設定 | 持久化位置 | 重啟後恢復時的驗證（現況已有，政策要求保留） | 不得恢復的情形 |
|---|---|---|---|---|
| A1 | 操作偏好（dwell／冷卻／半徑／眨眼／濾波／靈敏度） | `gaze_settings` | `GazeSettings.load()` 經 `clamped()` 範圍驗證（`GazeSettings.kt:84` 起）；NaN 先換預設值再夾範圍 | 存檔損毀時回整份預設值（`load` 的 `catch`，`:177-179`），不得以部分損毀值恢復 |
| A2 | 邊緣手勢開關（總開關＋四邊分項） | `gaze_settings`（5 個 key，`GazeSettings.kt:66-70`） | 布林值直接讀回；總關＝全部停用語義由 `EdgeGestureController`／設定頁分項停用保證 | 無（布林值無驗證問題；但須注意 `getBoolean(key, true)` 的預設只在從未存過時生效） |
| A3 | 校正設定檔（目前情境 id） | `calibration_profiles` 的 `active_profile` | `getActiveProfileId()` 驗證 id 在 `PROFILES` 清單內，否則回預設 `sitting`（`CalibrationStore.kt:42-46`） | id 不在清單內時不得恢復為未知情境 |
| A4 | UI 偏好（面板收合預設、條件修正／陀螺儀／臉距／虹膜精修開關） | `gaze_settings` | 同 A1（`clamped()` 只夾數值，布林直接讀回）；面板收合預設為收合（`addControlPanel` 預設收合） | 不得恢復「暫停時面板展開」的即時狀態（那屬 B 類，見 B6） |
| A5 | 已存有效校正資料（三情境模型＋中繼資料） | `calibration_profiles` 的 `model_<id>`／`meta_<id>` | `GazeModel.fromJsonString` 驗證 `version == MODEL_VERSION` 且 `wx.size == DIM`（`GazeModel.kt:141` 起），不符視為未校正；`getMeta` 損毀回 null | 版本／維度不符時**不得**恢復為「已校正」，必須走向未校正分支（見 §4 步驟 9 的品質確認） |
| A6 | 條件歷史與基準（`condition_history`） | `condition_history` 的 `records_<id>`／`reference_<id>` | `Condition.fromJson／Record.fromJson` 損毀回 null／空清單（`ConditionHistory.kt:91` 起、`203` 起）；未知分量以 NaN 表示、不參與判定 | 損毀時回空，不得以部分損毀紀錄內插偏移 |
| A7 | 引導完成標記（`welcome_seen`） | `eyecontrol_setup` | 布林直接讀回（`WelcomeActivity.kt:51-57`） | 不得因重啟而重設為「未看過」；重新執行引導須經設定頁明確入口 |
| A8 | 診斷／監控開關值本身（作為「設定」） | `gaze_settings` 的 `diagnostic_logging`／`remote_monitor` | 布林直接讀回（預設開啟語義見資料流文件 §0.2） | **開關值可恢復，但「正在錄製／正在上傳」的即時狀態不可恢復**（見 B7）；重啟後的新錄製須經 §4 步驟 7 的使用者主動啟動才開始，不得以「開關開著」為由自動開始錄製 |

### 3.2 B 類：不應自動恢復的即時狀態（重啟後必須丟棄／重置）

| # | 即時狀態 | 現況儲存 | 重啟後政策要求 | 若錯誤恢復的後果 |
|---|---|---|---|---|
| B1 | 相機串流（`FaceTrackerEngine` 實例、Provider 綁定、analyzer） | 記憶體（引擎實例＋`cameraProvider`＋`camera`） | **相機維持停止**，直到 §4 步驟 7 使用者主動啟動；不得以舊綁定殘留為由假裝串流仍在 | 舊綁定殘留佔住相機、新綁定失敗（K-02）；使用者以為相機已關、實際仍在串流（隱私） |
| B2 | 未完成校正（九點進度、已採特徵、當前點緩衝、逾時計時） | 記憶體（校正頁欄位）＋兩個整數檢查點（Bundle） | **不恢復進度**，重建後從第 1 點重採並明說進度已遺失（現況 `CalibrationActivity.kt:206-215` 已如此，政策要求保留）；不得以殘缺訓練集假裝完成 | 部分特徵混入新訓練集、模型被部分結果覆蓋（取消契約第 4／6 條同旨） |
| B3 | 未完成 dispatch（手勢 `GestureResultCallback` 進行中、雙擊第二下 `postDelayed`、滑動起點） | 記憶體（`dispatchGesture` 回呼、匿名 runnable、 `swipeStartPoint`） | **不恢復、不補發**；重啟後 `swipeStartPoint` 必須為 null、不得有待發第二下點擊 | 重啟後憑空點出一次點擊／滑動（K-07 殘留結構的跨行程版） |
| B4 | 暫停前座標（濾波器狀態、錨點、游標最後位置、dwell 進度） | 記憶體（`filterX／filterY`、`anchorX／anchorY`、`screenDwell`、`currentProgress`） | **全部重置**（`startEngine` 內的 `filterX.reset()／filterY.reset()` 現況已有，政策要求重啟路徑亦同）；游標不得出現在暫停前位置 | 恢復後第一幀即誤觸暫停前的目標 |
| B5 | 未完成計時／邊緣觸發／手勢（冷卻 `cooldownUntil`、邊緣候選序列、確認目標計時、提醒計時） | 記憶體（時間戳＋控制器狀態） | **全部重置**；冷卻不得跨行程沿用（重啟後冷卻歸零，但重啟本身須經 §4 步驟 7 的明確啟動，不得以「冷卻已過」為由自動開始） | 進行中的邊緣手勢在重啟後補執行返回／首頁 |
| B6 | 暫存追蹤狀態（`pausedForCalibration`、提醒 `reminderActive`、條件評估中間值、`lastSampleAtMs` 新鮮度） | 記憶體 | **全部歸零**；`lastSampleAtMs` 歸零使快照為「不新鮮」，不得以舊時間戳假裝服務在跑 | Dashboard 顯示虛假「追蹤中」 |
| B7 | 診斷錄製／上傳進行中狀態（`GazeLog` 當下檔案、`pendingUpload`、影格時段、`uploadExecutor` 佇列） | 記憶體（`writer`／`currentFile`／`pendingUpload`／影格 `sessionId`／執行緒佇列） | **不補傳、不續寫舊檔**；重啟後的新錄製須經使用者主動啟動（見 A8）；舊檔案留存本機、可被日後「清除紀錄」刪除，但不得自動上傳 | 行程死亡前已排程的上傳在重啟後補發，違反撤回語義（見資料流文件 §4） |
| B8 | 暫停／停止意圖本身（`userStopped`、`inRecoveryMode`、`currentMode == PAUSED`） | 記憶體（見 §2.1） | **政策要求改為持久化後恢復**（見 §4.2 預設定義）；在持久化落地前，現況「丟失意圖、自動恢復」維持不變，**不得宣稱為已修復**。本項為 B 類中的唯一例外：意圖本身不應自動恢復為「追蹤中」，而應恢復為「未啟動」等待使用者決定（見 §4 步驟 5–6） | 現況後果：使用者已完全停止，重啟後相機自動開啟（K-30 原文後果） |

### 3.3 預設定義與現況的關係（任務書定稿，政策要求）

- **預設（政策）**：重啟後**恢復設定、不自動恢復眼控工作階段**。
  即：A 類設定讀回並驗證；B 類即時狀態丟棄；服務停在「未啟動」狀態
  （overlay 可建、狀態列顯示「未啟動」，相機停止、無游標、無點擊），
  等待使用者主動啟動（見 §4）。
- **現況**：與預設相反——重啟後自動恢復追蹤（見 §1.2）。此為 K-30 缺口本身，
  不是政策的例外。政策落地前，現況維持不變並明示為缺口（`STATE_DESIGN.md` §2.2 註、
  `IMPLEMENTATION_NOTES.md` §3）；政策落地後，`onServiceConnected` 的現況序列
  （見 §1.2 第 5–6 步）必須改為 §4 的十步流程，D-8 決定持久化方案後才可動手
  （`PLAN.md` §6 的 D-8 連帶：`userStopped`／`inRecoveryMode` 是否持久化）。
  本文件不代 D-8 決定持久化儲存格式（新 prefs 檔 vs 現有 `gaze_settings` 新 key、
  寫入時機、損毀回退），只定「必須持久化」與「恢復語義」。

## 4. 重啟後安全流程（§11.3 十步，政策設計）

### 4.1 十步流程（依任務書 §11.3 要點，來源待定位，見文件頭）

以下十步為**重啟後**（R2／R4／R5 的新服務實例）的必經順序，不得調換、
不得跳步、不得合併。步驟 1–6 為「準備與等待」，步驟 7–10 為「使用者主動啟動後」
的驗證與恢復。步驟編號 `S1–S10` 供測試引用（見 §5）。

| 步 | 動作 | 現況對照（已有／缺口） | 失敗時的行為 |
|---|---|---|---|
| S1 | **載入非敏感設定**：讀回 A 類設定（A1–A8），逐項驗證（`clamped()`、模型版本／維度、情境 id、條件歷史解析），驗證失敗的項回預設／空值，不得以損毀值繼續 | 部分已有：`onServiceConnected` 已載入 `GazeSettings`（`:329`）、`startEngine` 已載入模型（`:398`）；缺口：無「重啟專用」的載入分支，載入與啟動綁在一起 | 任一項驗證失敗 → 該項回預設／未校正，**不**阻擋後續步驟（設定載入失敗不得導致服務無法顯示「未啟動」狀態）；失敗須寫入診斷紀錄（Logcat），不得靜默 |
| S2 | **清除不完整工作**：丟棄 B 類即時狀態（B2–B7 的記憶體狀態）；校正檢查點歸零（不得以 Bundle 整數續採）；`GazeLog` 不續寫舊檔（舊檔留存、可清除，不補傳）；影格時段／庫存歸零 | 部分已有（新實例記憶體本即為空）；缺口：無顯式清除步驟，依賴「新實例即乾淨」的隱含假設；舊 `GazeLog` 檔案的「不補傳」無顯式保證（只因 `pendingUpload` 丟失而偶然成立） | 清除本身不應失敗；若發現舊檔案殘留（例如舊 CSV 未關檔），只留存、不上傳、不續寫 |
| S3 | **相機維持停止**：不建立引擎、不呼叫 `ProcessCameraProvider`、不綁定相機；前台通知可顯示（服務存活），但通知文字不得宣稱「追蹤中」 | **缺口**：現況 `onServiceConnected` 直接 `startEngine()`（`:344`），相機立即啟動 | 此步無失敗分支；任何提早啟動引擎的路徑皆為違規，須以測試 R-1／R-2 攔截 |
| S4 | **不自動恢復追蹤**：不跑樣本主路徑、不顯示游標、不接受 dwell／眨眼／邊緣輸入；`trackingSnapshot()` 須回「不新鮮／無模型狀態待確認」，不得回虛假新鮮快照 | **缺口**：現況直接進入追蹤；快照新鮮度以 `lastSampleAtMs`（初始 0）判定，現況下重啟後很快即為「新鮮」 | 此步無失敗分支；任何自動進入主路徑的行為皆為違規 |
| S5 | **不執行點擊**：不 `dispatchGesture`、不 `performGlobalAction`、不補發暫停前的手勢；`swipeStartPoint` 為 null、無待發 runnable | 部分已有（新實例無待發手勢）；缺口：無顯式「禁止點擊」狀態，依賴「無樣本即無點擊」的隱含保證 | 此步無失敗分支；重啟後 5 秒內出現的任何點擊皆為嚴重違規（見 §5 R-5） |
| S6 | **顯示未啟動狀態**：overlay 建好（游標隱藏、面板收合預設），Dashboard 狀態列顯示「未啟動」類文字（具體措辭待改寫批次定，須區分「未啟動」與「已完全停止」／「未校正」）；狀態須與 `AppFlowState` 的重啟後初始態一致，不得顯示 `SERVICE_RUNNING` | **缺口**：現況初始態推導為 `SERVICE_RUNNING`（有模型時，見 §1.2）；Dashboard 文字以快照流程態為準，現況下重啟後即顯示運作中文字 | 顯示失敗（overlay 建失敗）→ 服務仍停在未啟動，不得因此自動啟動引擎作補償 |
| S7 | **使用者主動啟動**：使用者經 Dashboard「開始眼控」手點、或經 RECOVERY 目標注視（僅當重啟後意圖恢復為 RECOVERY 時，見 §4.2）明確啟動；**不得**以「服務已啟用」本身、「App 回到前景」、「螢幕解鎖」等被動事件代替主動啟動 | 部分已有（Dashboard 開始鈕經 `resumeTracking`，`:1841` 起）；缺口：現況下開始鈕在重啟後已無事可做（追蹤已自動恢復），語義被架空 | 使用者未啟動 → 永遠停在 S6，不得逾時自動啟動（**無逾時自動恢復**，此為硬性要求） |
| S8 | **重查權限與相機**：啟動時重查相機權限（`CAMERA` 已授予）、重查無障礙服務真實啟用狀態（`isServiceEnabled` 精確比對，`GazeAccessibilityService.kt:1776` 起）；任一未通過 → 顯示具體缺項、停在未啟動，不得啟動引擎 | 部分已有（`canStartTracking` 閘門，`:1797` 起；但該閘門的「引擎啟動成功」判定不覆蓋非同步綁定失敗，見 PHASE 6 文件）；缺口：閘門未被重啟流程強制呼叫（現況 `onServiceConnected` 不走閘門） | 缺權限 → 導引授權流程；服務未啟用 → 導引系統設定；兩者皆須明示原因，不得只說「請先…」含糊帶過（沿用 PLAN D1-14 要求） |
| S9 | **新建 tracking session**：新建 `FaceTrackerEngine` 實例（新 session、新 generation，見 PHASE 6 文件 §5）、重置濾波器與條件狀態、重載目前情境模型並驗證（A5）；舊 session 的任何狀態不得沿用 | 部分已有（`startEngine` 內的重置與重載，`:395-405`）；缺口：現況 `startEngine` 在重啟路徑上被自動呼叫，新 session 的建立與「使用者主動啟動」脫鉤 | 引擎同步啟動失敗 → `ERROR`＋原因（現況 `catch` 已有，`:407-425`）；非同步綁定失敗 → 須經 PHASE 6 的 `FAILED` 狀態與錯誤代碼，不得靜默停在空白（見 PHASE 6 文件 §4） |
| S10 | **確認品質才允許操作**：以 `verifyServiceRunning()`（快照新鮮＋有模型，`GazeAccessibilityService.kt:1817` 起）確認服務實際運作後，才接受 dwell／眨眼／邊緣輸入；確認前收到的樣本只更新快照事實，不驅動點擊 | 部分已有（`verifyServiceRunning` 定義）；缺口：現況主路徑不等待此確認（樣本一到即驅動）；且「新鮮樣本 ≠ 看得到臉」（IMPLEMENTATION_NOTES 出入第 22 點），確認只驗「服務在跑」，不得宣稱為「看得到臉」 | 確認未通過 → 停在未啟動／ERROR，不得接受輸入；等待確認**不設逾時自動判定失敗**（沿用 PLAN D1-15：使用者可能正在擺位，以手動重試驗證為準） |

### 4.2 預設定義（政策，任務書定稿）

- **重啟後恢復設定、不自動恢復眼控工作階段**（見 §3.3）。
- 「恢復設定」指 S1 的 A 類設定讀回與驗證；「不自動恢復工作階段」指 S3–S6
  的相機停止、追蹤不恢復、無點擊、顯示未啟動，直到 S7 使用者主動啟動。
- 意圖持久化落地後（D-8 決定後）：重啟後恢復的意圖只能是「未啟動」等待決定，
  或「RECOVERY_MODE 等待注視重啟」（僅當持久化意圖確為 RECOVERY 時）；
  **永遠不得**恢復為「直接追蹤中」（即現況的自動恢復語義，在政策落地後為違規）。
  在持久化落地前，意圖一律視為未知，一律走向「未啟動」等待決定（S6），
  不得以「上次可能是追蹤中」為由自動恢復。

### 4.3 與現況流程的對照（落地時必須改動的點，僅列出，不動程式）

| 現況點 | 政策要求 | 備註 |
|---|---|---|
| `onServiceConnected` 直接 `startEngine()`（`:344`） | 改為 S1–S6（載入＋清除＋停止＋顯示未啟動），`startEngine()` 只在 S7–S9 經使用者主動啟動呼叫 | 最大改動點；須與 D-8 持久化方案同批落地，不得分批（否則出現「有持久化、無等待」或「有等待、無持久化」的中間態） |
| `deriveInitialFlowState` 推導為 `SERVICE_RUNNING`（有模型時） | 重啟後初始態須為「未啟動」對應的流程態（新狀態或 `NOT_CALIBRATED`／`STOPPED` 的重啟分支，須與 `STATE_DESIGN.md` 協調，不得擅自新增狀態名） | 流程態命名待 `STATE_DESIGN.md` 增補，本文件不擅定新狀態名 |
| `startEngine` 守衛只檢查記憶體旗標（`:392`） | 守衛須同時檢查持久化意圖（D-8 落地後）與 S8 閘門 | 守衛語義收窄不得破壞 `resumeAfterCalibration` 的接回語義（接回不是重啟，見 R7） |
| `GazeLog` 舊檔「不補傳」靠 `pendingUpload` 丟失偶然成立 | S2 須顯式保證：重啟後不續寫、不補傳舊檔；新錄製只在 S7 後開始 | 顯式保證須有測試覆蓋（見 §5 R-7） |
| 無「未啟動」Dashboard 文字 | S6 文字須新增，並與 `STOPPED`／`NOT_CALIBRATED`／`RECOVERY_MODE` 文字區分（見 `STATE_DESIGN.md` §7 文字表增補） | 文字改寫批次另行辦理，本文件只定語義區分要求 |

## 5. 重啟測試清單（9 項，§11.4 清單要求，來源待定位，見文件頭）

以下 9 項由 §1 的觸發路徑與 §4 的十步流程推導，**非 §11.4 條文原文的轉錄**；
§11.4 原文待定位後，須逐項對照、不得宣稱已覆蓋原文未見的項目。
每項皆為實機測試（Instrumentation／手動實機），單元測試不能代替；
「通過」指行為符合 §4 政策，不指程式編譯通過。

| # | 測試 | 前置狀態 | 操作 | 通過標準（政策要求） |
|---|---|---|---|---|
| R-1 | 完全停止後行程重建 | 完全停止（`stopCompletely`，相機已關）→ 殺行程（系統回收模擬）→ 系統重建服務 | 觀察重建後 10 秒內（不碰 Dashboard） | 相機維持停止、無游標、無點擊、Dashboard 顯示「未啟動」類文字；**不**自動恢復追蹤 |
| R-2 | 暫停後行程重建 | 暫停（`PAUSED`，面板展開「重啟」）→ 殺行程 → 重建 | 觀察重建後（不碰 Dashboard） | 同 R-1；面板不得自動回到「展開＋重啟」態（B6 不恢復），須為預設收合＋未啟動 |
| R-3 | 追蹤中行程重建 | 追蹤中（游標運作）→ 殺行程 → 重建 | 觀察重建後（不碰 Dashboard） | 同 R-1；重啟後須經使用者主動啟動（S7）才恢復，不得以「上次在追蹤」為由自動恢復 |
| R-4 | RECOVERY_MODE 後行程重建 | 停止眼控（RECOVERY_MODE，重啟目標顯示中）→ 殺行程 → 重建 | 觀察重建後（不碰 Dashboard／不注視） | 相機停止、重啟目標**不**自動出現（B1／B6 不恢復）；持久化落地後，須走向 S6「未啟動」或持久化意圖對應的等待態，不得直接出現可注視的重啟目標（重啟目標的出現須經 S7 的主動啟動語義） |
| R-5 | 重啟後無點擊 | 任一重啟路徑（R-2／R-3）→ 重建後 5 秒內 | 不操作，觀察全系統 | 無任何 `dispatchGesture`／`performGlobalAction` 發生；無待發雙擊第二下、無滑動起點殘留（B3／B5） |
| R-6 | 重啟後主動啟動全流程 | 重建後停在未啟動 → 點 Dashboard「開始眼控」 | 依序觀察 S8–S10 | 權限／服務重查通過 → 新 session 建立（新 generation）→ 品質確認通過後才接受輸入；任一環節失敗顯示具體原因、不靜默回追蹤 |
| R-7 | 重啟後診斷不補傳、不續寫舊檔 | 追蹤中（監控開啟、CSV 寫入中）→ 殺行程 → 重建 → 停在未啟動 2 分鐘 | 檢查本機檔案與伺服端（伺服端檢查待定位，見資料流文件 §7） | 舊 CSV 檔案留存本機、**不**上傳；無新 CSV 檔案產生（新錄製只在 S7 後開始）；舊檔案可經「清除紀錄」刪除 |
| R-8 | 裝置重啟後不自動恢復 | 追蹤中 → 裝置重啟 → 系統重新綁定服務 | 觀察開機後（不碰 Dashboard） | 同 R-1（平台行為，待實機驗證）；本 App 無開機 receiver，不得以「無 receiver」推斷「不恢復」或「會恢復」，以實機結果為準 |
| R-9 | 未校正情境下重啟 | 未校正情境（無模型）→ 殺行程 → 重建 → 點「開始眼控」 | 觀察 S8–S10 | 閘門以「未校正」拒絕、顯示具體缺項、不啟動引擎、不顯示假游標；重新校正後可正常啟動 |

**測試邊界聲明**：R-1–R-9 皆為政策落地後的驗收項，**現況下預期全部不通過**
（現況自動恢復），不得以現況行為反推測試標準錯誤。R-8 的平台行為部分
（開機綁定時機）不屬 App 程式可控範圍，測試結果須註明裝置／系統版本，
不得泛化為全部裝置。

## 6. 待定位／待決定清單

| # | 項目 |
|---|---|
| T-1 | §11.2／§11.3／§11.4 條文原文：未見於本次收到的先前文件，本文件的分類／十步／九項為依任務書要點推導，須與原文對照後確認 |
| T-2 | D-8 決定：`userStopped`／`inRecoveryMode` 是否持久化、持久化格式與寫入時機（`PLAN.md` §6）；本政策假定「必須持久化」，格式不代定 |
| T-3 | 重啟後「未啟動」流程態的命名與 `STATE_DESIGN.md` 的協調：不得擅自新增狀態名 |
| T-4 | S6／S8 的使用者可見文字措辭：待文案改寫批次定，須區分未啟動／已停止／未校正／RECOVERY 四態 |
| T-5 | 裝置重啟後的平台綁定行為（R4／R-8）：待實機驗證，不得以程式碼推斷 |
| T-6 | 備份還原與重啟的交錯（還原的 prefs 是否先於 `onServiceConnected` 生效）：待實機驗證，見資料流文件 §7 T-5 |

---

*複用來源：`~/workspace/eyecontrol-master-audit/docs/audit/KNOWN_ISSUES.md`（K-30）、
`~/workspace/eyecontrol-phase3/PLAN.md`（§1.1／§1.2／§3 D2-6／§6 D-8）、
`~/workspace/eyecontrol-phase3/STATE_DESIGN.md`（§2.2 註）、
`~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md`（§3 已知缺口）、
`~/workspace/eyecontrol-phase3/CANCEL_CONTRACT.md`（取消／重建語義對照）。*
