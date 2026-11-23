# EyeControl 功能對照表（STEP 1 盤點）

> 2026-11-23 增補（review 樹，未合併／未推送）：特徵契約已由授權變更為 25 維（`MODEL_VERSION` 2→3；新增連續光特徵 f[22..24]，舊模型強制重校）；光線改為連續模型輸入、多光源校正；`ConditionHistory` 的 lux 維度退出歷史修正。詳見 `docs/light-robust-gaze-design.md`。本文以下所寫「22 維」皆指變更前的狀態，原文不改。

> 性質：STEP 1 盤點文件。只讀取程式碼、只新增本文件，未修改任何程式碼、版本號、workflow。
> 工作樹：`~/workspace/eyecontrol-p3`（遠端 main HEAD `c6b80964`、v0.5.9 加未推送 Phase 3 實作）。
> 行號一律為目前工作樹行號。**待確認不得擅改為已完成；未驗證一律寫「尚未驗證」。**
> 本任務未執行編譯、未執行測試、未實機驗證、未觸發 CI——「靜態檢查」欄只代表本任務已讀程式碼核實，
> 絕不等於編譯成功或測試通過。

## 0. 五欄證據定義

| 欄 | 「有／已核實」的含義 | 「尚未驗證／無」的含義 |
|---|---|---|
| 原始碼存在 | 在工作樹中找到具名類別／函式／常數，附行號 | 找不到，標「待定位」或「不存在（grep 零命中）」，不得編造檔名 |
| 實際呼叫 | 找到呼叫端（呼叫鏈見 `docs/architecture-map.md` §2），功能真正接通 | 類別存在但無呼叫端，或只有預留常數／註解，標「未接通」並說明 |
| 靜態檢查 | 本任務已讀相關程式碼並核實行為（grep＋逐段閱讀） | 本任務未讀到該細節，標「待定位」；靜態檢查通過≠編譯通過 |
| 自動化測試 | 有對應單元測試檔＋`@Test` 靜態計數（執行結果另見本欄說明） | 無對應測試檔，標「無」；有測試檔但本任務未執行，執行結果一律寫「尚未驗證」 |
| 實機驗證 | 在實機（SM-S9180 等）上驗證通過，有可查證據 | **本表全部為「尚未驗證」**（本任務未做任何實機驗證；先前版本的實機回報不在本任務驗證範圍內，不得代為背書） |

測試總數（靜態計數，本任務未執行）：7 個測試類別、110 項 `@Test`
（`AppFlowStateTest` 29、`ConditionHistoryTest` 22、`DwellTrackerTest` 11、
`EdgeGestureControllerTest` 25、`GazeModelMathTest` 10、`OneEuroFilterTest` 7、
`RecoveryGateTest` 6）。先前記載見 `~/workspace/eyecontrol-phase3/TEST_RESULTS.md`
（協調員親自重跑 harness OK (110 tests)），本表引用該記載、不代為背書。

## 1. 核心追蹤管線（任務書核實清單）

| # | 功能／技術 | 原始碼存在（證據） | 實際呼叫（證據） | 靜態檢查 | 自動化測試 | 實機驗證 |
|---|---|---|---|---|---|---|
| 1 | Android CameraX | 有：`FaceTrackerEngine.kt:13-22`（import）、`:205-218`（`ImageAnalysis.Builder`）、`:229`（`bindToLifecycle`）；依賴 CameraX 1.4.2（`app/build.gradle.kts`） | 有：服務／校正／訓練三消費者皆經 `FaceTrackerEngine.start()`（服務 `GazeAccessibilityService.kt:391`） | 已讀核實 | 無（無引擎測試） | 尚未驗證 |
| 2 | 1280×720 影像 | 有：`FaceTrackerEngine.kt:203-218`，`ResolutionStrategy(Size(1280, 720), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`；`OUTPUT_IMAGE_FORMAT_RGBA_8888`、 `STRATEGY_KEEP_ONLY_LATEST`（`:216-217`） | 有：`start()` 內建 `ImageAnalysis` 後 `setAnalyzer`（`:219`）；實際取得解析度由裝置／CameraX 決定，程式碼只要求偏好策略，**實際幀尺寸未量測** | 已讀核實（策略存在；實際解析度待定位） | 無 | 尚未驗證 |
| 3 | MediaPipe Face Landmarker | 有：`FaceTrackerEngine.kt:24-25`（import）、`:130-188`（Options 建置）、`:188`（`createFromOptions`）；模型 `app/src/main/assets/face_landmarker.task`；依賴 tasks-vision 0.10.26 | 有：`analyze()` → `detectAsync()`（`:266-285`）→ `ResultListener`（`:138`） | 已讀核實 | 無 | 尚未驗證 |
| 4 | 478 特徵點 | 部分：程式碼無 478 常數；依據為註解「478 點，含虹膜」（`GazeFeatureExtractor.kt:46`）＋虹膜索引 468／473（同檔 landmark 索引常數段）＋守衛 `lm.size <= R_IRIS`（`:71`） | 有：`extract()` 吃整張 landmark 清單（`:69`）；實際回傳點數由模型決定，程式碼未斷言 478 | 已讀核實（註解＋索引；點數本身待定位） | 無（`extract()` 本身無測試） | 尚未驗證 |
| 5 | 22 維 GazeFeatureExtractor | 有：`DIM = 22`（`GazeFeatureExtractor.kt:67`）、`extract()`（`:69-155`）、特徵陣列（`:140-153`） | 有：引擎結果回呼呼叫 `extract()`（`FaceTrackerEngine.kt:161`）；模型載入驗 `wx.size == DIM`（`GazeModel.kt:154`） | 已讀核實 | 無（`extract()` 無測試；`GazeModelMathTest` 只餵合成向量） | 尚未驗證 |
| 6 | Ridge Regression GazeModel | 有：`GazeModel.kt` 全檔；`train()` 嶺回歸（`:172-268`，`lambda=5e-4`、正規方程＋高斯消去 `solve :311`）、`predict()`（`:37-92`）、`MODEL_VERSION = 2`（`:124`） | 有：校正頁 `train`（`CalibrationActivity.kt:700` 附近）、服務 `predict`（`GazeAccessibilityService.kt:598`）、校正頁／訓練頁 `predict` | 已讀核實 | 有：`GazeModelMathTest.kt` 10 項（只測數學，不測 JSON 往返）；執行結果尚未驗證 | 尚未驗證 |
| 7 | OneEuroFilter | 有：`OneEuroFilter.kt:13`（類別）、`filter()`（`:35`）、`reset()`（`:75`）、`updateParams()`（`:20`） | 有：服務 `filterX/filterY` 每幀呼叫（`GazeAccessibilityService.kt:664-665`）；停留中動態改參數（`:590-596`） | 已讀核實 | 有：`OneEuroFilterTest.kt` 7 項；執行結果尚未驗證 | 尚未驗證 |
| 8 | 九點校正 | 有：`buildPoints()` 3×3（`CalibrationActivity.kt:345-349`）；`SETTLE_MS=900L`、`SAMPLES_PER_POINT=20`、`KEEP_RATIO=0.7`、`POINT_TIMEOUT_MS=10_000L`（`:1094-1101`） | 有：`startPoint`→採樣→`commitPoint`→`finishCalibrationInternal` 完整鏈（見架構圖 §2.2） | 已讀核實 | 無 | 尚未驗證 |
| 9 | 校正品質評估 | 有：`GazeEvaluator.kt`；`evaluate()`（`:38`）、`leaveOnePointOut()`（`:70`）、`CalibrationReport`／`PointError`；等級門檻 60／120／200 px（`CalibrationActivity.kt:718-724` 附近，經驗值） | 有：`finishCalibrationInternal` 呼叫兩者並顯示常駐報告頁（`CalibrationActivity.kt:695-773`） | 已讀核實 | 無 | 尚未驗證 |
| 10 | 坐姿／躺姿／平放設定檔 | 有：`CalibrationStore.kt:19-28`（`sitting/lying/flat`＋中文名）、`PROFILES`、`get/setActiveProfileId`、`saveModel/loadModel/exists/getMeta`（`:44-95`） | 有：服務 profileListener 即時換模型（`GazeAccessibilityService.kt:86-104`）；校正頁按 `profileId` 存取（`CalibrationActivity.kt:99,164,724`）；設定頁情境切換（`SettingsActivity.kt:104,227`） | 已讀核實 | 無 | 尚未驗證 |
| 11 | 臉部距離補償 | 有：`GazeModel.predict()` 臉距縮放段（`:81-90`，`baseIod/f[6]` 比值、夾 0.7–1.4）；`train()` 計 `baseIod`（`:186-194`）；開關 `distanceCompensation=true`（`GazeSettings.kt`） | 有：服務 `predict` 傳入開關（`GazeAccessibilityService.kt:598-599`）；校正頁同（`CalibrationActivity.kt:571-572`） | 已讀核實 | 無（`GazeModelMathTest` 是否覆蓋縮放分支，待定位——本任務未逐項核對其案例） | 尚未驗證 |
| 12 | 裝置方向補償 | 有：`DeviceOrientationTracker.kt` 全檔（旋轉向量 pitch／roll／yaw）；`GazeModel.correctForDeviceTilt()`（`:285-310`）；開關 `gyroCompensation=false`（預設關，`GazeSettings.kt`，防與 kx／ky 重複計算） | 有：引擎每幀附加 `devicePitch/deviceRoll`（`FaceTrackerEngine.kt:170-181`）；服務 `predict` 傳入（`:598-599`）；校正頁取裝置基準平均（`CalibrationActivity.kt:697-704` 附近） | 已讀核實 | 無 | 尚未驗證 |
| 13 | Dwell Click（停留點擊） | 有：`DwellTracker.kt`（錨點 EMA、取消半徑 2.5×、速度上限 2000 px/s、衰減 2×、離開 300 ms 重置）；服務內面板／把手另用線性計時（不走 `DwellTracker`） | 有：螢幕點擊走 `screenDwell.update()`（`GazeAccessibilityService.kt:895` 附近）→ 滿進度 → `triggerActionAt()`；面板／把手線性 dwell 在主路徑 `:700-830` | 已讀核實 | 有：`DwellTrackerTest.kt` 11 項（螢幕 dwell）；面板／把手線性 dwell 無測試；執行結果尚未驗證 | 尚未驗證 |
| 14 | Blink Click（眨眼點擊） | 有：眨眼狀態機在服務主路徑（`GazeAccessibilityService.kt:548-580`，雙眼閉合計時、150–600 ms 窗口取自 `blinkMinMs/blinkMaxMs`、閾值 `blinkThreshold=0.08`）；設定 `GazeSettings.kt`（眨眼參數＋範圍夾制） | 有：睜眼後符合窗口＋有有效游標＋未冷卻＋非暫停＋非提醒 → `triggerActionAt(lastValidX, lastValidY)`（`:570-576`）；單眼閉合凍結（`:582-585`）；校正／訓練頁閉眼丟棄樣本 | 已讀核實 | 無 | 尚未驗證 |
| 15 | AccessibilityService | 有：`GazeAccessibilityService.kt`（1982 行）；Manifest 宣告＋`accessibility_service_config.xml`（`canPerformGestures=true`） | 有：唯一手勢注入機制；`onServiceConnected` 啟動引擎＋overlay（`:320-350`） | 已讀核實 | 部分：`AppFlowStateTest` 29 項只測狀態機純邏輯，不測服務本體；執行結果尚未驗證 | 尚未驗證 |
| 16 | Gesture Dispatch | 有：`performTap/DoubleTap/LongPress/Swipe/Scroll`（`GazeAccessibilityService.kt:1542-1605`）、`performEdgeAction`（`:1519`，頂／底走 `performGlobalAction`，側邊走捲動） | 有：`triggerActionAt()`（`:1479`）與邊緣判決後呼叫；**取消未接通**：`onCancelled` 全樹零命中、雙擊第二下匿名 `postDelayed(150)` 無取消（K-07／K-62，PLAN D3-7 dispatch 部分未做） | 已讀核實 | 無 | 尚未驗證 |
| 17 | 浮動控制面板 | 有：`ControlPanelView.kt`（5 鍵：單擊／雙擊／長按／滑動／暫停，`:24-33`）；承載 `addControlPanel`（服務 `:449`，`TYPE_ACCESSIBILITY_OVERLAY`＋`FLAG_NOT_TOUCHABLE`） | 有：把手 dwell 展開（服務 `:728-740`）、面板按鈕 dwell（`:749-800`）、自動收合 1.2 秒／閒置 4 秒（常數 `:1976-1977`）；命中判定在服務端，View 只繪製 | 已讀核實 | 無 | 尚未驗證 |
| 18 | 邊緣操作 | 有：Phase 3 `EdgeGestureController.kt`（676 行，狀態機＋`edgeBandPx/zoneFor/update/reset`）；開關 5 個 key（`GazeSettings.kt`＋設定頁）；確認目標繪製（`CursorOverlayView.kt:217-225`） | 有：主路徑每幀餵幀判決（服務 `:835` 附近）→ `performEdgeAction`；**門檻未校準**：離散度上限 `Float.MAX_VALUE`（永不阻擋）、連續幀數／方向死區皆標 UNCALIBRATED（`EdgeGestureController.kt:490-515`）；邊緣帶改 50 dp（高密度裝置物理寬度約為 v0.5.9 的 4 倍，實機對照待做） | 已讀核實 | 有：`EdgeGestureControllerTest.kt` 25 項（控制器純邏輯）；服務端接線／確認目標／開關無測試；執行結果尚未驗證 | 尚未驗證 |
| 19 | Foreground Service | 有：`startForegroundService()`（`GazeAccessibilityService.kt:367-386`，通知管道 `IMPORTANCE_LOW`、`startForeground(1001, …, FOREGROUND_SERVICE_TYPE_CAMERA)`）；權限 `FOREGROUND_SERVICE`＋`FOREGROUND_SERVICE_CAMERA`（Manifest） | 有：`onServiceConnected` 呼叫（`:326`）；無獨立 Foreground Service 類別（由無障礙服務自身前景化） | 已讀核實 | 無 | 尚未驗證 |
| 20 | 追蹤中斷提示 | 有：臉遺失分支（服務 `:514-545`，灰圈 `setFaceLost()`、清 dwell／把手／面板進度、記 `GazeLog` 臉遺失幀、提醒狀態機照推）；降級幀分支（`:630-655`，凍結）；提醒目標（條件變化時）；`trackingSnapshot` 新鮮度（`SAMPLE_FRESH_MS=3000L`，`:1731`） | 有：灰圈／提醒／狀態列皆接通；**無統一品質狀態機**（`QualityState` 僅設計文件 TARGET，未實作）；臉遺失時濾波器不重置（恢復序列未完整實作） | 已讀核實 | 無 | 尚未驗證 |
| 21 | 設定介面 | 有：`SettingsActivity.kt`（409 行）＋`GazeSettings.kt`（226 行）；滑桿／開關／情境／重校／引導／訓練／無障礙設定／隱私／關於／商店更新列 | 有：`saveAndFinish` 存檔→prefs 監聽即時重載（服務 `:64`）；診斷摘要對話框接 `GazeLog.summarize`；Play 版隱藏遠端監控列（`SettingsActivity.kt:77-78`） | 已讀核實 | 無 | 尚未驗證 |
| 22 | 診斷紀錄 | 有：`GazeLog.kt`（CSV 17 欄、10 Hz 節流、120 秒自動輪轉連續紀錄、最近 3 份）、`CrashReporter.kt`（閃退留存，最近 5 份）、`VerifyFrames.kt`（眼區裁切，每 session 最多 60 張） | 有：服務每幀呼叫 `record()`（臉遺失／降級／正常三分支）；`onDestroy` 關檔；設定頁摘要；**預設開啟**（`diagnosticLogging=true`、`remoteMonitor=true`） | 已讀核實 | 無 | 尚未驗證 |
| 23 | 更新檢查機制 | 有：`UpdateChecker.kt`（查 GitHub releases、下載、安裝、商店入口）；`MainActivity.checkForUpdate()`（`:299`） | 有：僅側載版（`SELF_UPDATE_ENABLED` gating）；Play 版走商店；**下載 APK 無雜湊／簽章校驗**；檢查失敗靜默 | 已讀核實 | 無（`isNewer` 純函數亦無測試） | 尚未驗證 |
| 24 | Sideload 與 Play 雙 flavor | 有：`app/build.gradle.kts:38-50`（dimension `distribution`；sideload 兩 BuildConfig 皆 true，play 皆 false）；Play 疊加 Manifest 移除 INTERNET／REQUEST_INSTALL_PACKAGES | 有：更新檢查、遠端監控上傳、設定頁監控列皆以 `BuildConfig.SELF_UPDATE_ENABLED`／`REMOTE_MONITOR_ENABLED` gating；CI 同建 `assembleSideloadDebug`＋`bundlePlayRelease` | 已讀核實 | 無（flavor gating 無測試） | 尚未驗證 |
| 25 | 相機交接協議（五不能動之一） | 有：`requestPauseForCalibration`（`GazeAccessibilityService.kt:1648`）、`resumeAfterCalibration`（`:1688`） | 有：校正頁（`CalibrationActivity.kt:281`）與訓練頁（`TrainingActivity.kt:201`）皆走協議；無逾時／無失敗回呼（校正頁自設逾時補救） | 已讀核實 | 無 | 尚未驗證 |

## 2. 其他主要功能（非任務書清單，但為完整盤點所必需）

| 功能 | 原始碼存在 | 實際呼叫 | 靜態檢查 | 自動化測試 | 實機驗證 |
|---|---|---|---|---|---|
| 條件感知漂移修正（亮度／角度／臉距變化→提醒／內插） | 有：`ConditionHistory.kt`（`interpolate :179`、`hasSignificantChange :131`、50 筆上限、IDW、±250 px）、`DeviceOrientationTracker`、`LightSensorTracker` | 有：`evaluateConditions()`（服務 `:1035`，每 500 ms）→ `showReminder/updateReminder/completeQuickCalibration`（`:1067,1123,1173`） | 已讀核實 | 有：`ConditionHistoryTest.kt` 22 項（純函式，不測 prefs／JSON）；執行結果尚未驗證 | 尚未驗證 |
| 提醒／單點快速校正 | 有：`showReminder/updateReminder/dismissReminder/completeQuickCalibration`（服務 `:1067-1200`）、提醒目標繪製（`CursorOverlayView.kt:174`） | 有：提醒 preempt 最高優先（主路徑 `:690`＋`showReminder` 內取消邊緣）；逾時 5 秒／移開 0.5 秒略過 | 已讀核實 | 無 | 尚未驗證 |
| RECOVERY_MODE（停止後注視重啟） | 有：Phase 3 `inRecoveryMode` 旗標、重啟目標（`CursorOverlayView.kt:195`）、`RecoveryGate.kt`、`attemptRecovery/failRecovery`（服務 `:1411,1467`）、`stopTracking/stopCompletely` 分離（`:1886,1925`） | 有：主路徑 RECOVERY 分支（`:505`）→ `updateRecoveryTarget`（`:1301`）→ 驗證序列；Dashboard 手點 `resumeFromRecovery`（`:1868`）走同一序列 | 已讀核實 | 有：`RecoveryGateTest.kt` 6 項（守衛純判斷）；服務端重啟鏈無測試；執行結果尚未驗證 | 尚未驗證 |
| 流程狀態機 AppFlowState | 有：`AppFlowState.kt`（226 行，8 態＋轉換表）；服務接線 `syncFlowState/deriveFlowState`（`:183,223`） | 有：服務多處同步狀態、快照新增 `flowState/flowErrorReason` 欄位；檔頭「影子模式」文字已過時（以程式碼為準） | 已讀核實 | 有：`AppFlowStateTest.kt` 29 項；執行結果尚未驗證 | 尚未驗證 |
| 引導流程（6 步，校正先於服務） | 有：`SetupWizardActivity.kt`（457 行）、`WelcomeActivity.kt`（60 行）；步驟對調（Phase 3 D1-4） | 有：`MainActivity` 首次啟動導引導；相機權限 launcher（引導 `:59`）；逐項完成無持久化（只存 `savedInstanceState`） | 已讀核實 | 無 | 尚未驗證 |
| 操作訓練（7 項練習） | 有：`TrainingActivity.kt`（866 行，自有引擎／模型／dwell 判定） | 有：獨立接通（見架構圖 §2.3）；不吃靈敏度／濾波設定（PLAN 記載） | 已讀核實（PLAN 引用部分待定位細節） | 無 | 尚未驗證 |
| 臉轉向辨識（僅記錄） | 有：`GazeFeatureExtractor.faceTurnDirection()`（`:201`） | 有：服務每幀計算、變化時記 Log（`:620-626`）；方向補償本身在模型內，不另作動作 | 已讀核實 | 無 | 尚未驗證 |
| 虹膜影像精修（實驗功能，預設關） | 有：`EyeImageMetrics.measure/refineIrisCenter`、`RefinedIris`、`irisRefinement` 開關（預設 false）；`FaceTrackerEngine` 在 `start()` 讀一次設定 | 有：精修位置經 `extract()` 的 `refinedIris` 參數餵入特徵（引擎 `:158-161`）；開啟後須重校（註解自述） | 已讀核實 | 無 | 尚未驗證 |
| 低亮度自動曝光補償 | 有：`FaceTrackerEngine.updateExposure()`（`:406`，只調 CameraX 曝光、不動螢幕亮度；門檻 30／80 lux、持續 2 秒） | 有：結果回呼每幀評估（`:170` 附近 `updateExposure(lux)`） | 已讀核實 | 無 | 尚未驗證 |
| 眼部影像度量（亮度／對比／反光） | 有：`EyeImageMetrics.kt`（`measure :77`、`isDegraded :296`、門檻未經實機校準） | 有：引擎幀槽驗證後量測並附加至樣本（`:293-181`）；服務降級分支與邊緣檢查取用；數字不進 22 維 | 已讀核實 | 無 | 尚未驗證 |
| 驗證影格留存／上傳 | 有：`VerifyFrames.kt`（眼區裁切、週期 1 Hz＋事件觸發、120 秒時段／60 張上限） | 有：引擎 `maybeCaptureVerifyFrame`（`:314`）＋服務端事件請求（`requestVerifyCapture`）；校正頁引擎不設開關、故校正不產影格 | 已讀核實 | 無 | 尚未驗證 |
| 遠端監控上傳（Supabase） | 有：`GazeLog.uploadFile()`（`:175-198`，`gaze_logs` 表） | 有：關檔時上傳（`close()`）；開關＋flavor 雙 gating（`remoteUploadEnabled()`）；伺服端 RLS 實際設定尚未驗證 | 已讀核實（機制；金鑰值不抄錄） | 無 | 尚未驗證 |
| 閃退紀錄 | 有：`CrashReporter.kt`（`install/recordCaught/pendingReport`） | 有：`EyeControlApp` 安裝；`MainActivity.showCrashReportIfPending`（`:247`）；校正／服務多處 `recordCaught` | 已讀核實 | 無 | 尚未驗證 |
| 靈敏度放大 | 有：`sensitivity` 設定（預設 1.0，範圍 0.5–2.5） | 有：服務預測後、濾波前繞螢幕中心放大（`:612-618` 附近） | 已讀核實 | 無 | 尚未驗證 |
| 陀螺儀修正開關（預設關） | 有：`gyroCompensation=false`（`GazeSettings.kt`） | 有：`predict()` 內條件分支（`GazeModel.kt:70-79`）；未經實機開／關對照驗證前不得預設開啟（註解自述） | 已讀核實 | 無 | 尚未驗證 |
| 校正取消目標（D1-9，待 D-4） | 僅預留：4 個 companion 常數（`CalibrationActivity.kt:1111-1125`），零引用 | **未接通**：無事件、無視覺元素、無注視判定；九點座標未動 | 已讀核實（grep 零引用） | 無 | 尚未驗證 |
| 智慧目標辨識（Smart Target Recognition） | **不存在**：全樹 grep 零命中 | **未接通**：無此功能；固定幾何命中判定不得冒充辨識 | 已讀核實（grep） | 無 | 尚未驗證 |
| 追蹤品質引擎（具名模組） | **不存在**：無此具名類別／檔案 | 部分接通（分散分支，見架構圖 M7）；無統一品質狀態／分數 | 已讀核實（grep） | 無 | 尚未驗證 |
| 手勢取消（`onCancelled`／雙擊取消） | **不存在**：手勢 `onCancelled` 全樹零命中 | **未接通**：PLAN D3-7 dispatch 部分未做 | 已讀核實（grep） | 無 | 尚未驗證 |
| 首次校正注視選擇（無舊模型） | 有：選擇機制（`onChoiceSample :519`、報告／詢問／ERROR 頁） | **部分未接通**：無舊模型時注視選擇無法判讀（吃舊模型預測），按鈕只剩手點／系統返回 | 已讀核實 | 無 | 尚未驗證 |

## 3. 待定位清單（本任務找不到、不得編造）

| 項 | 說明 |
|---|---|
| 實際取得的相機解析度／幀率 | 程式碼只宣告 1280×720 偏好策略；裝置實際回應、FPS、CPU／記憶體成本皆未量測 |
| MediaPipe 實際回傳 landmark 點數 | 程式碼未斷言；478 為註解說法，未以執行期檢查驗證 |
| MediaPipe 結果回呼執行緒 | 程式碼未註明；幀槽世代驗證存在，但回呼執行緒歸屬待定位 |
| 品質狀態的即時量測（信心值） | MediaPipe 信心值未被讀取；`QualityState` 未實作，不得以任何分數宣稱品質 |
| `accessibility_service_config` 的 `canRetrieveWindowContent` 實際使用 | 已宣告，未見節點查詢呼叫；是否完全未用，待定位（本任務 grep 未見相關呼叫） |
| 全部 UI 文案與 `privacy.html` 的一致性 | 只核對 `accessibility_desc` 一處矛盾；其餘文案位置未逐一核對，見 `docs/privacy-copy-audit.md` |
| 第三方依賴授權條款清單 | Repo 內無 NOTICE／第三方授權檔；各依賴授權待定位 |
| `face_landmarker.task` 官方雜湊比對 | 未比對；只記錄檔案存在與大小 |
| Supabase 伺服端 RLS／資料表實際設定 | 未檢查；只記錄 App 端註解說法（RLS 僅 INSERT） |
| CI 是否成功編出目前工作樹 | 未推送、未觸發、未查執行紀錄 |
| 圖示／校正閃退修正的實機結果 | 本任務未實機驗證；先前回報（v0.5.4 主執行緒修正）不得代為背書為已修復 |

## 4. 判讀規則（給 STEP 2 對照者）

1. 「原始碼存在＋實際呼叫」同時成立，才可稱為「功能已接通（靜態）」；仍須補編譯＋測試＋實機三重證據，才可稱為「已完成」。
2. 「類別存在但無呼叫」「僅常數預留」「僅設計文件 TARGET」三者一律不得寫成已完成；本表已按此規則標示 D1-9、品質引擎、智慧辨識、手勢取消四項。
3. 門檻標 `UNCALIBRATED`、註解寫「未經實機校準／經驗值」者，數字不得當作已驗證規格引用；校準前預設不阻擋的邏輯（如離散度 `Float.MAX_VALUE`）必須在對照時明示。
4. 預設開啟的上傳功能（診斷／監控）與「不儲存、不上傳」文案並存為已知矛盾，對照時不得以任一單方說法覆蓋另一方，須列為待決策事項。
