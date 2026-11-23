# EyeControl 架構圖（STEP 1 盤點，擴充 engineering-baseline.md）

> 性質：STEP 1 盤點文件。**只讀取程式碼、只新增本文件，未修改任何程式碼、版本號、workflow。**
> 工作樹：`~/workspace/eyecontrol-p3`（repo 複本，無 `.git/`；遠端 main HEAD `c6b80964`、v0.5.9，
> 加上未推送的 Phase 3 實作；`versionCode = 13`、`versionName = "0.5.9"`）。
> 行號：一律為目前工作樹行號；凡與先前文件（以 v0.5.9 樹為準的 PLAN.md 等）行號不同處，
> 以本文件為準。
> 狀態詞彙：只用「已確認（附行號）」「部分接通」「待定位」「尚未驗證」。
> **閱讀程式碼 ≠ 編譯成功 ≠ 測試通過 ≠ 實機通過。**本任務未執行 Gradle 編譯、未執行任何測試、
> 未實機驗證、未推送、未觸發 CI。測試數字一律標明是靜態計數或先前記載，不得冒充本任務重跑結果。
> 密鑰：本文件只記錄機制，絕不抄錄任何密鑰值（含 Supabase anon key、keystore 內容、GitHub secret 值）。

## 0. 與 engineering-baseline.md 的分工（不得重複建相同內容）

本文件**擴充** `docs/engineering-baseline.md`，不重做其已完成的分析：

| 內容 | 在哪裡 | 本文件做法 |
|---|---|---|
| 遠端 HEAD、逐檔 blob SHA 比對（51 相同／14 異動／12 新增）、快照方法 | baseline §1–§2、附錄 A | 不重做、不重抄 SHA 表；直接引用其結論，行號改以目前工作樹重標 |
| Gradle／AGP／Kotlin／SDK、gradlew 缺失、CI 機制、簽章機制 | baseline §3 | §3 只做「目前工作樹現況覆核＋行號」，機制細節引用 baseline §3，不重抄 workflow 全文 |
| Manifest、權限、無障礙服務宣告 | baseline §4 | §3.3 只列覆核結果與差異（無差異），細節引用 baseline §4 |
| 三個相機消費者、交接協議、元件總表、生命週期、設定儲存檔 | baseline §5–§6 | 本文件 §1–§2 以「17 模組＋呼叫鏈」重新組織同一批程式碼；元件事實引用 baseline，新增的是模組邊界、輸入／輸出、執行緒、錯誤處理、測試狀態與「是否真正接通」的判定 |
| 五個不能動、診斷／監控預設、設計系統 | baseline §7 | §0.1 只做目前工作樹再驗證（行號更新），結論引用 baseline §7 |
| 已知缺陷 K-30／K-02／K-14／K-07／K-62 等 | baseline §8 | 風險細節移至 `docs/risk-register.md`；本文件 §4 只列模組層面的缺口對照 |
| Phase 3 方案（PLAN／STATE_DESIGN）與實作出入 | `~/workspace/eyecontrol-phase3/` | 本文件複用其結論（D1-9 僅預留、D3-7 dispatch 部分未做等），有衝突處以實際程式碼為準並在 §4 註明 |

複用文件（已讀）：`~/workspace/eyecontrol-phase3/PLAN.md`、`STATE_DESIGN.md`、
`IMPLEMENTATION_NOTES.md`（另有 `TEST_PLAN.md`、`TEST_RESULTS.md`、`CANCEL_CONTRACT.md`、
`D4_OPTIONS.md`，測試與 D-4 部分引用其記載、不代為背書）。

### 0.1 五個不能動：目前工作樹再驗證（全部維持現況）

| 紅線 | 目前工作樹證據 |
|---|---|
| 九點資料流 | `CalibrationActivity.buildPoints` 3×3（`CalibrationActivity.kt:345-349`，邊距 8%）；`SETTLE_MS = 900L`、`SAMPLES_PER_POINT = 20`、`KEEP_RATIO = 0.7`、`POINT_TIMEOUT_MS = 10_000L`（`CalibrationActivity.kt:1094-1101`）；訓練→存檔→清歷史→寫基準順序在 `finishCalibrationInternal`（`CalibrationActivity.kt:695-760`）。Phase 3 新增階段／報告頁，未改上述常數與順序 |
| 22 維特徵契約 | `GazeFeatureExtractor.DIM = 22`（`GazeFeatureExtractor.kt:67`）；`GazeModel.fromJsonString` 驗 `wx.size == DIM`（`GazeModel.kt:154`）；影像度量欄位（`devicePitch` 等）不進 22 維（`GazeFeatureExtractor.kt:19-31` 的 `GazeSample` 定義） |
| `requestPauseForCalibration` 協議 | 簽名 `requestPauseForCalibration(onReleased: () -> Unit)`（`GazeAccessibilityService.kt:1648`）；`resumeAfterCalibration()`（同檔 `:1688`）。背景停引擎、`===` 身分確認、主執行緒藏面板後回呼的順序保留；僅回呼內新增 overlay 收尾（重啟／確認目標收掉），協議本體未動。無逾時、無失敗回呼的現況亦保留（校正頁自設逾時在 `CalibrationActivity.kt:267-314`） |
| `buildConfig = true` | `app/build.gradle.kts:111`（`buildFeatures` 段） |
| `noCompress += "task"` | `app/build.gradle.kts:115`（`androidResources` 段）；模型檔 `app/src/main/assets/face_landmarker.task`（3.6 MB） |

## 1. 17 模組架構圖

### 1.1 總圖（資料流向，箭頭＝函式呼叫／回呼）

```
前鏡頭 ── CameraX ImageAnalysis (1280×720, RGBA_8888, KEEP_ONLY_LATEST)
  │  FaceTrackerEngine.analyze() :266 ── detectAsync() :280
  ▼
MediaPipe Face Landmarker (LIVE_STREAM, 1 臉, face_landmarker.task)
  │  ResultListener :138 ── face == null → onSample(null)；有臉 → lookupFrameMetrics()
  │  EyeImageMetrics.measure()（數字 only；cropEyes() 只供 VerifyFrames）
  ▼
GazeFeatureExtractor.extract() :69 ── 22 維 GazeSample
  │  ＋ DeviceOrientationTracker.snapshot() ＋ LightSensorTracker.snapshot()
  ▼
GazeAccessibilityService.onSample() :484 ── mainHandler.post ──► onSampleOnMainThread() :491
  │  守衛：pausedForCalibration / userStopped / inRecoveryMode / model == null / 臉遺失 / 閉眼 / 降級幀
  │  GazeModel.predict() :598 ── 條件偏移 ── 靈敏度 ── OneEuroFilter.filter()（X/Y 各一）
  ▼
  ┌─ 提醒 preempt（reminderActive）── updateReminder() :1123 → 單點快速校正
  ├─ 面板／把手分支（線性 dwell，服務內嵌）── setPanelExpanded() :469
  ├─ EdgeGestureController.update()（Phase 3）── 判決 ── performEdgeAction() :1519
  ├─ DwellTracker.update()（螢幕點擊）── triggerActionAt() :1479
  └─ 眨眼分支（150–600 ms）── triggerActionAt() :1479
  ▼
Gesture Dispatch：dispatchGesture()（performTap :1561／DoubleTap :1573／LongPress :1580／Swipe :1592／Scroll :1542）
  ▼  AccessibilityService 手勢注入（唯一對外操作機制）
Overlay：CursorOverlayView（游標／灰圈／提醒／重啟／確認目標）＋ ControlPanelView（面板／把手）
  │  WindowManager.addView(TYPE_ACCESSIBILITY_OVERLAY, FLAG_NOT_TOUCHABLE) :432-462
  ▼
GazeLog.record()（CSV 17 欄，120 秒輪轉）── 關檔 → Supabase 上傳（僅側載＋開關開）
VerifyFrames（眼區裁切 JPEG，本機＋上傳）── 經 GazeLog.uploadFrame()
```

三個相機消費者共用同一引擎類別、各自持有實例，走同一交接協議（見 §2.2）：

| 消費者 | 引擎實例 | 模型 | 交接呼叫 |
|---|---|---|---|
| `GazeAccessibilityService` | `engine`（`:52`） | `model`（`:56`，profileListener 即時換模型） | 被交接方（提供協議） |
| `CalibrationActivity` | 自有引擎（`CalibrationActivity.kt` 內建 `FaceTrackerEngine`，`onCreate`／`onResume` 啟動） | 自訓自存（`GazeModel.train` → `CalibrationStore.saveModel`） | `requestPauseForCalibration`（`:281`）／`resumeAfterCalibration`（`onDestroy :865-873`） |
| `TrainingActivity` | 自有引擎（`TrainingActivity.kt:44,110`） | 自載（`:48,178`） | 同上（`:201`／`:238`） |

### 1.2 模組總表（存在性判定：類別存在 ≠ 功能完成）

| # | 模組（任務書名稱） | 實際落點 | 判定 |
|---|---|---|---|
| M1 | CameraX 管線 | `FaceTrackerEngine`（部分） | 已確認，接通 |
| M2 | Face Landmarker | `FaceTrackerEngine.start()` 內建，無獨立類別 | 已確認，接通；無獨立模組檔案 |
| M3 | 特徵萃取 | `GazeFeatureExtractor` | 已確認，接通 |
| M4 | Gaze Model | `GazeModel` | 已確認，接通 |
| M5 | Filter | `OneEuroFilter`（服務持有兩實例） | 已確認，接通；臉遺失時不重置（已知缺口） |
| M6 | Calibration | `CalibrationActivity`＋`CalibrationStore`＋`GazeEvaluator` | 已確認，接通；取消目標僅預留（D1-9） |
| M7 | Tracking Quality Engine | **無此具名模組**，功能分散 | 部分接通（分散實作）；不得稱為獨立引擎 |
| M8 | Smart Target Recognition | **不存在**（全樹零命中） | 待定位／不存在；最接近者為錨點＋命中區判定（分散） |
| M9 | Cursor Controller | **無獨立類別**，邏輯在服務＋`CursorOverlayView` | 部分接通（分散實作） |
| M10 | Gesture Arbiter | **無獨立類別**，仲裁內嵌於主路徑優先序 | 部分接通（隱式仲裁，無具名仲裁器） |
| M11 | Gesture Dispatch Controller | **無獨立類別**，`perform*` 在服務內；無 `onCancelled` | 部分接通；取消語義缺失（K-07／K-62） |
| M12 | AccessibilityService | `GazeAccessibilityService` | 已確認，接通 |
| M13 | Overlay | `CursorOverlayView`＋`ControlPanelView` | 已確認，接通 |
| M14 | Settings | `GazeSettings`＋`SettingsActivity` | 已確認，接通 |
| M15 | Diagnostics | `GazeLog`＋`CrashReporter`＋`VerifyFrames` | 已確認，接通（預設開啟，見風險 R1） |
| M16 | Update System | `UpdateChecker` | 已確認，接通（僅側載版）；無完整性校驗（見風險 R14） |
| M17 | Activities | 6 個 Activity＋`EyeControlApp` | 已確認，接通 |

### 1.3 逐模組明細（每模組 10 欄）

約定：「執行緒」只寫程式碼可見的執行緒證據，未註明者寫「待定位」；
「測試狀態」只寫靜態可見的單元測試檔案與 `@Test` 計數，執行結果一律寫「尚未驗證」
（本任務未跑測試；先前 harness 記載見 `~/workspace/eyecontrol-phase3/TEST_RESULTS.md`，引用不背書）。

#### M1 — CameraX 管線

- **實際程式檔案**：`FaceTrackerEngine.kt`（597 行）；`app/src/main/AndroidManifest.xml`（相機權限與前鏡頭要求）。
- **主要類別**：`FaceTrackerEngine(context, onSample)`（`:39`）；內部 `FrameSlot`（`:62`，三緩衝幀槽）、`PendingCapture`（`:78`）。
- **主要函式**：`start(lifecycleOwner)`（`:122`）、`stop()`（`:506`）、`unbindOnMainThread()`（`:554`）、`analyze(proxy)`（`:266`）、`toBitmap(proxy)`（`:450`）、`setVerifyCapture()`（`:243`）、`requestVerifyCapture()`（`:256`）、`updateExposure()`（`:406`）。
- **輸入資料**：前鏡頭 `ImageProxy`（RGBA_8888）；`LifecycleOwner`（綁定生命週期）；設定 `irisRefinement`（`start()` 讀一次）。
- **輸出資料**：`GazeSample?` 回呼（有臉→樣本、無臉→`null`）；眼部影像度量（經 `lookupFrameMetrics :293` 附加）；驗證影格事件。
- **依賴模組**：M2（Landmarker）、`EyeImageMetrics`、`DeviceOrientationTracker`、`LightSensorTracker`、`VerifyFrames`、`GazeSettings`。
- **執行緒**：分析在單執行緒 executor（`:196`、`setAnalyzer :219`）；`ProcessCameraProvider` listener 在主執行緒 executor（`:236`）；`unbindAll` 強制主執行緒（`:554-568`，CountDownLatch 最多 2 秒）；結果回呼執行緒由 MediaPipe 決定，程式碼未註明，標待定位。
- **生命週期**：`start()` 重複呼叫先 `stop()` 舊實例（`:125`）；`stop()` 順序：立 `closed` → 停感測器 → 主執行緒解綁 → 清幀槽 → 停 executor（500 ms）→ `landmarker.close()` → 回收 Bitmap（`:506-544`）。無 generation 守衛（K-02／K-14，見風險 R4）。
- **錯誤處理**：Landmarker 建立失敗記 Log 後向上拋（`:188-194`）；綁定失敗只記 Log、不拋出、無回呼（`:233-240`，綁定失敗無可觀測信號，見風險 R4）；`detectAsync` 失敗只記 Log、丟幀（`:282-284`）。
- **測試狀態**：無專屬單元測試（全樹測試檔見 §3.7）。尚未驗證。

#### M2 — Face Landmarker（MediaPipe）

- **實際程式檔案**：`FaceTrackerEngine.kt`；模型 `app/src/main/assets/face_landmarker.task`；依賴 `com.google.mediapipe:tasks-vision:0.10.26`（`app/build.gradle.kts`）。
- **主要類別**：無自有類別；直接使用 `com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker`／`FaceLandmarkerResult`（import `:24-25`）。
- **主要函式**：`FaceLandmarkerOptions` 建置（`:130-188`）：`setModelAssetPath("face_landmarker.task")`（`:133`）、`setRunningMode(LIVE_STREAM)`（`:136`）、`setNumFaces(1)`（`:137`）、`setResultListener`（`:138`）、`setErrorListener`（`:185`）；`FaceLandmarker.createFromOptions`（`:188`）；`detectAsync()`（`:280`）。
- **輸入資料**：`Bitmap`（`BitmapImageBuilder`，`:280`）＋單調遞增毫秒時間戳（`:274-275`）；1280×720 解析度選擇器（`:203-218`，見 M1）。
- **輸出資料**：`faceLandmarks()` 第一張臉的 `NormalizedLandmark` 清單；無臉時 `firstOrNull()` 為 null → `onSample(null)`（`:139-147`）。**478 點**：程式碼未寫 478 常數；依據為註解「478 點，含虹膜」（`GazeFeatureExtractor.kt:46`）＋虹膜索引 468／473（同檔 `:54-64` 附近）＋ `extract()` 守衛 `lm.size <= R_IRIS`（`:71`）。MediaPipe 信心值**未被讀取**（全樹無信心值取用），不得以信心值定義品質狀態。
- **依賴模組**：M1（餵幀）、M3（吃 landmark 清單）。
- **執行緒**：`detectAsync` 非同步；結果回呼執行緒待定位（程式碼未註明）；幀槽世代驗證防錯幀（`:55-70` 註解、`lookupFrameMetrics :293`）。
- **生命週期**：隨 `start()` 建立、隨 `stop()` 關閉（`:531-532`）；`closed` 旗標防 stop 後誤用（`:118-121`）。
- **錯誤處理**：`setErrorListener` 只記 Log（`:185`）；建立失敗拋出由呼叫端處理（校正頁 `startEngineSafely`，`CalibrationActivity.kt:326-336`）。
- **測試狀態**：無單元測試（MediaPipe 原生依賴無法純 JVM 測）。尚未驗證。

#### M3 — 特徵萃取（GazeFeatureExtractor）

- **實際程式檔案**：`GazeFeatureExtractor.kt`（226 行）。
- **主要類別**：`object GazeFeatureExtractor`；`data class GazeSample`（`:19-31`）；`data class RefinedIris`（`:34-39`）；`enum FaceTurn`（`:42-44`）。
- **主要函式**：`extract(lm, timestampMs, refinedIris?)`（`:69`）、`eyeGeometry(lm)`（`:162`）、`faceTurnDirection(f, basePitch, baseYaw)`（`:201`）。
- **輸入資料**：landmark 清單（正規化座標）；可選 `RefinedIris`（虹膜影像精修位置，`EyeImageMetrics` 產出）。
- **輸出資料**：`GazeSample(features: DoubleArray[22], leftOpen, rightOpen, timestampMs, devicePitch/roll=NaN, ambientLux=NaN, eyeMeanLum/eyeContrast/specularRatio=NaN)`；landmark 不足（`lm.size <= 473`）或眼寬／iod 退化時回 `null`（`:71-94`）。22 維組成（`:140-153`）：雙眼虹膜相對位置 4、眼頭交互 2、iod 1、姿態 3、二階 3、雙眼平均／差分 4、iod²／roll² 2、睜眼 2、偏置 1。
- **依賴模組**：M2（landmark）；`EyeImageMetrics`（僅 `eyeGeometry` 被引擎用於度量換算）。
- **執行緒**：純 Kotlin 物件，無自有執行緒；在 MediaPipe 結果回呼執行緒被呼叫（引擎 `:161`）。
- **生命週期**：無狀態（`object`，無生命週期）。
- **錯誤處理**：退化輸入回 `null`（呼叫端視為無臉幀）；NaN 度量欄位以 NaN 表示未知、不得以 0 假裝量到（檔頭註解 `:6-18`）。
- **測試狀態**：無專屬測試檔；`GazeModelMathTest` 餵合成特徵向量測模型數學，不測 `extract()` 本身。尚未驗證。

#### M4 — Gaze Model（Ridge Regression）

- **實際程式檔案**：`GazeModel.kt`（342 行）。
- **主要類別**：`class GazeModel(wx, wy, basePitch, baseYaw, kx, ky, deviceBasePitch?, deviceBaseRoll?, baseIod)`（`:19-33`）。
- **主要函式**：`predict(f, screenW, screenH, devicePitch, deviceRoll, gyroCompensation, distanceCompensation)`（`:37-92`）、`train(features, targets, lambda=5e-4, …)`（`:172-268`）、`correctForDeviceTilt(…)`（`:285-310`）、`solve()` 高斯消去（`:311`）、`toJsonString()`（`:95`）、`fromJsonString()`（`:129`，驗 `version == 2` 且 `wx.size == DIM`，`:154`）、`save/load/exists`（`:111-163`，舊版單模型路徑，現況由 `CalibrationStore` 接管多情境）。
- **輸入資料**：22 維特徵；校正時基準（basePitch／baseYaw／baseIod／裝置基準）；裝置即時 pitch／roll（NaN 時跳過修正）。
- **輸出資料**：螢幕像素座標 `(outX, outY)`；訓練產出新 `GazeModel`（kx／ky 由資料決定、夾 ±3.0，`:235,261`）；JSON 序列化（含 `MODEL_VERSION = 2`，`:124`）。
- **依賴模組**：M3（特徵契約 `DIM`）；`GazeSettings`（開關由呼叫端傳入）；`CalibrationStore`（存檔）。
- **執行緒**：純計算，無自有執行緒；在主執行緒（服務）與校正頁主執行緒被呼叫。
- **生命週期**：無狀態生命週期；模型實例由呼叫端持有（服務 `model` 欄位、校正頁區域變數）。
- **錯誤處理**：`predict` 要求 `f.size >= 9` 否則 `require` 拋出（`:47`）；`fromJsonString` 失敗回 `null`（版本／維度不符）；`train` 要求非空且特徵與目標等長；`solve` 退化行靠正則項兜底（`:322` 附近）。
- **測試狀態**：`GazeModelMathTest.kt`（10 項 `@Test`，只測 `train`／`predict` 數學，不碰 JSON 往返——`org.json` 在純 JVM 為 stub，檔頭自述）。靜態計數 10 項；執行結果尚未驗證。

#### M5 — Filter（OneEuroFilter）

- **實際程式檔案**：`OneEuroFilter.kt`（76 行）；持有者 `GazeAccessibilityService` 的 `filterX`／`filterY`（`:110-111`，預設 `minCutoff 0.2, beta 0.015`）。
- **主要類別**：`class OneEuroFilter(minCutoff, beta, dCutoff, deadBand=5.0, maxVelocity=3500.0)`（`:13-19`）。
- **主要函式**：`filter(x, tSeconds)`（`:35`，突波限幅＋速度平滑＋死區內動態 cutoff＋自適應 cutoff）、`updateParams(minCutoff, beta)`（`:20`）、`reset()`（`:75`）、`alpha()`（`:29`）。
- **輸入資料**：靈敏度放大後的單軸座標（`gainedX/gainedY`，服務 `:660` 附近）＋秒級時間戳（`sample.timestampMs / 1000.0`）。
- **輸出資料**：平滑後單軸座標（箝制 `0..screenW-1`／`0..screenH-1`，服務 `:664-665`）。
- **依賴模組**：M4 輸出；`GazeSettings.filterMinCutoff/filterBeta`（動態 `updateParams`：停留中加強平滑 `0.04/0.005`，服務 `:590-596`）。
- **執行緒**：主執行緒（服務主路徑每幀呼叫）；無同步保護，不得跨執行緒共用。
- **生命週期**：`reset()` 只在 `startEngine` 與旋轉時呼叫；**臉遺失時不重置**（服務臉遺失分支 `:514-545` 只清 dwell，未重置 filter——已知缺口，完整恢復序列屬後續階段）。
- **錯誤處理**：`dt` 夾至少 1 ms（`:43`）；未初始化首幀直接回傳原值。
- **測試狀態**：`OneEuroFilterTest.kt`（7 項 `@Test`）。靜態計數 7 項；執行結果尚未驗證。

#### M6 — Calibration（校正）

- **實際程式檔案**：`CalibrationActivity.kt`（1125 行，含內部 `CalibrationView`）、`CalibrationStore.kt`（116 行）、`GazeEvaluator.kt`（123 行）。
- **主要類別**：`CalibrationActivity`（階段 `Phase`：HANDOVER／SAMPLING／REPORT／ASK／ERROR，檔頭 `:34-35`）、`CalibrationStore`（`object`，情境 `sitting/lying/flat`，`:19-28`）、`GazeEvaluator`（`object`）、`CalibrationView`（內部繪製類別，`:900` 附近）。
- **主要函式**：`buildPoints()`（`:345`，3×3）、`startPoint()`（`:351`）、`onSample/onSamplingSample`（`:367-424`）、`commitPoint()`（`:657`，離群值剔除 `KEEP_RATIO`）、`finishCalibration/finishCalibrationInternal`（`:680,695`）、`showReport/showReportChoices`（`:765,806`）、`enterAsk`（`:444`，D1-10）、`enterError`（`:477`，D1-11）、`exitCalibration`（`:501`）、`startEngineSafely`（`:326`）、`requestPauseForCalibration` 交接（`:267-314`，自設逾時 `POINT_TIMEOUT_MS`）、`GazeEvaluator.evaluate()`（`:38`）、`leaveOnePointOut()`（`:70`）、`CalibrationStore.saveModel/loadModel/exists/getMeta`（`:63-95`）。
- **輸入資料**：`GazeSample` 串流（自有引擎）；`EXTRA_PROFILE_ID`（`:1099`）；舊模型（選擇頁注視判定用，`:446,480`）。
- **輸出資料**：`GazeModel` JSON＋`ProfileMeta(calibratedAt, looErrorPx)` 存入 `calibration_profiles`；常駐報告頁（等級 60／120／200 px 經驗值門檻，`:718-724` 附近，D-12 未決）；條件基準（`ConditionHistory.saveReference`，`:736-744`，完整重校才清歷史）。
- **依賴模組**：M1／M2／M3／M4、`ConditionHistory`、`CrashReporter`、M12（交接協議）、M14（`blinkThreshold` 等設定）。
- **執行緒**：主執行緒（Activity 生命週期＋樣本處理）；交接逾時用 `handoverHandler.postDelayed`（`:314`）。
- **生命週期**：`onCreate :158` → `onResume :232`（發起交接＋`startEngineSafely`）→ `onPause :248`（停引擎）→ `onDestroy :865`（無條件 `resumeAfterCalibration`）；`onSaveInstanceState` 只存進度整數、重建後誠實重採（`:259-263`，IMPLEMENTATION_NOTES 出入 13）。無相機權限檢查（沿用 PLAN §1.1 記載；本任務未逐行重核權限分支，標待定位細節）。
- **錯誤處理**：訓練／存檔失敗進 ERROR 態＋重試，不以 `finish()` 帶過（`:680-693`）；單點逾時 3 次詢問（D1-10）；交接逾時顯示原因＋重試／退出（D1-11）。**D1-9 取消目標僅 4 個 companion 常數預留、零引用、無事件、無視覺元素**（`CalibrationActivity.kt:1111-1125` 附近，見 §4）。
- **測試狀態**：無校正流程單元測試（Activity／引擎依賴無法純 JVM 測）。尚未驗證。

#### M7 — Tracking Quality Engine（追蹤品質引擎）

- **實際程式檔案**：**無此具名檔案／類別。**功能分散在：`EyeImageMetrics.kt`、`GazeAccessibilityService.kt` 主路徑、`EdgeGestureController.kt`、`FaceTrackerEngine.kt`（驗證影格觸發）。
- **主要類別**：不存在。最接近的具名物：`EyeImageMetrics`（`object`，`:23`）、`EdgeCheckReport/EdgeCheckResult`（`EdgeGestureController.kt:559-578`）、`AppFlowState` 流程態（非品質態）。
- **主要函式**：`EyeImageMetrics.isDegraded(sample)`（`:296`，亮度／對比／反光三門檻 `:42-48`，未經實機校準）；`EyeImageMetrics.measure()`（`:77`）；服務臉遺失分支（`:514-545`）、降級幀分支（`:630-655`，凍結游標、不累積、不碰濾波）；`EdgeGestureController.evaluateChecks()`（`:374`，連續 present／離散度／原始預測邊界／影像降級）。
- **輸入資料**：`GazeSample`（含影像度量 NaN 語義）、臉 present／absent（二元）、雙眼睜開程度。
- **輸出資料**：分散的分支決策（凍結／清進度／灰圈／邊緣檢查 Fail 原因）；**無統一品質狀態／分數輸出**（`STATE_DESIGN.md` §4 的 `QualityState` 為設計文件 TARGET，本階段未實作為程式碼列舉——全樹品質列舉零命中）。
- **依賴模組**：M2／M3（樣本）、M13（灰圈顯示）、M15（降級影格）。
- **執行緒**：隨呼叫端（引擎回呼執行緒量測、主執行緒判定）。
- **生命週期**：無獨立生命週期；無「恢復序列」狀態機（STATE_DESIGN §4.2 的 7 步恢復序列未實作，僅 D2-3 重置與臉遺失重置部分存在）。
- **錯誤處理**：度量未知（NaN）≠降級、照常處理（服務 `:628` 註解）；幀槽驗證失敗度量回 null。
- **測試狀態**：無品質引擎測試。尚未驗證。**判定：不得在對外說法中使用「Tracking Quality Engine 已完成」；正確說法為「品質判斷分散在三處分支，尚無統一引擎」。**

#### M8 — Smart Target Recognition（智慧目標辨識）

- **實際程式檔案**：**不存在。**全樹 grep `SmartTarget`／`TargetRecognition`／`recogn` 零命中（大小寫不敏感）。
- **主要類別／函式**：待定位／不存在。最接近的既有機制（皆非「辨識」）：`DwellTracker` 錨點 EMA（M9 判定用）、面板／把手命中矩形（服務 `:700-830`）、邊緣帶判定（`EdgeGestureController.zoneFor :113`）、提醒／重啟／確認目標的固定幾何。以上皆為固定幾何＋注視計時，無內容辨識、無 UI 元素語義讀取（`AccessibilityService` 的 `canRetrieveWindowContent=true` 宣告存在，但程式碼未見 `AccessibilityNodeInfo` 查詢／目標辨識呼叫——本任務 grep 未見相關呼叫，標待定位）。
- **判定：本模組在原始碼中不存在。**架構圖中不得畫為已實作方塊；後續若要實作，須先立需求與決策（D-5／D-11 連帶，見 PLAN §6），不得以現況命中判定冒充。

#### M9 — Cursor Controller（游標控制）

- **實際程式檔案**：**無獨立類別。**邏輯分散：`GazeAccessibilityService.onSampleOnMainThread`（`:491-985`，預測→偏移→靈敏度→濾波→顯示）＋ `CursorOverlayView.kt`（繪製）＋ `DwellTracker.kt`（停留判定）。
- **主要類別**：`CursorOverlayView`（`:45`）；`DwellTracker`（`:19`）；服務內游標狀態欄位（`lastCursorX/Y`、`hasValidCursor`、`anchorX/Y`、`currentProgress`）。
- **主要函式**：`CursorOverlayView.setCursor(Triple?)`（`:156`）、`setFaceLost()`（`:163`）、`flashClick()`（`:168`）、`showReminderTarget/hideReminderTarget`（`:174,181`）、`updateRecoveryTarget/hideRecoveryTarget`（`:195,203`）、`updateConfirmTarget/hideConfirmTarget`（`:217,225`）、`onDraw`（`:234`）；`DwellTracker.update()`（`:48`）、`reset()`（`:37`）。
- **輸入資料**：濾波後座標＋停留進度（0..1）＋臉遺失／提醒／重啟／確認四種目標狀態。
- **輸出資料**：全螢幕 overlay 繪製（游標圓點＋進度環、灰圈、中央目標）；點擊落點取 `DwellTracker.anchorX/Y`（服務 `:895` 附近）。
- **依賴模組**：M4／M5（座標）、M10 仲裁（優先序）、M13（overlay 承載）。
- **執行緒**：主執行緒（`mainHandler.post` 後；overlay 方法只在主執行緒呼叫，`reloadConditionState` 註解 `:90` 明載）。
- **生命週期**：隨服務 `onServiceConnected` 建 overlay、隨 `onDestroy` 移除（`:1607-1632`）；臉遺失時游標留原地變灰（不隱藏）。
- **錯誤處理**：`model == null` 或 `cursorView == null` 時主路徑提早返回（`:511-512`）；`setCursor(null)` 隱藏游標（暫停／停止）。
- **測試狀態**：`DwellTrackerTest.kt`（11 項 `@Test`，鎖定錨點／速度／衰減行為）；`CursorOverlayView` 無測試。執行結果尚未驗證。

#### M10 — Gesture Arbiter（手勢仲裁）

- **實際程式檔案**：**無獨立類別／檔案。**仲裁為 `onSampleOnMainThread` 內的隱式優先序（`:491-985`），另由 `EdgeGestureController` 回傳判決、`cancelEdgeGesture()`（`:1250`）強制重置。
- **主要函式**：即主路徑分支順序（見 §2.1）：RECOVERY 分支 → 臉遺失 → 閉眼／眨眼 → 降級幀 → 提醒 preempt → 面板／把手 → 邊緣（`edgeController.update :835` 附近）→ 螢幕 dwell。互斥保證：提醒上升沿 `cancelEdgeGesture()`（`showReminder :1067`）；面板／把手命中時 `cancelEdgeGesture()` 中斷邊緣（IMPLEMENTATION_NOTES 出入 30）；確認／重啟／提醒三種目標同一時間只顯示一種（`edgeConfirmShowing` 旗標，`:161` 附近）。
- **判定：隱式仲裁已接通，但無具名仲裁器、無仲裁狀態機可單獨測試。**「單一有效手勢」規則為 TARGET（STATE_DESIGN §5），現況 dispatch 未完成前仍可接受新觸發的防護未在程式碼中見到集中守衛，標待定位細節。
- **測試狀態**：無仲裁測試；邊緣互斥部分由 `EdgeGestureControllerTest` 覆蓋控制器內狀態，不覆蓋服務端優先序。尚未驗證。

#### M11 — Gesture Dispatch Controller（手勢派發）

- **實際程式檔案**：`GazeAccessibilityService.kt`（`:1479-1605`），無獨立檔案／類別。
- **主要函式**：`triggerActionAt(x, y)`（`:1479`，按 `ActionMode` 分派）、`performEdgeAction(left, right, top, bottom)`（`:1519`，頂＞底＞左＞右，頂＝返回／底＝首頁／左＝上捲／右＝下捲）、`performScroll(up)`（`:1542`，500 ms）、`performTap`（`:1561`，60 ms）、`performDoubleTap`（`:1573`，兩次 `performTap`＋`postDelayed(150)` 第二下）、`performLongPress`（`:1580`，800 ms）、`performSwipe`（`:1592`，500 ms）。全部只 override `onCompleted`（閃光／模式回 TAP），**全樹 `onCancelled` 零命中**（手勢回呼的 `onCancelled`，`AppFlowState` 的 `CalibrationCancelled` 事件名不算）。
- **輸入資料**：落點座標＋`ActionMode`＋捲動方向。
- **輸出資料**：`dispatchGesture(GestureDescription, GestureResultCallback)` 系統手勢注入。
- **依賴模組**：M10（仲裁後才派發）、M12（`AccessibilityService.dispatchGesture`）。
- **執行緒**：主執行緒（`mainHandler.postDelayed` 在雙擊路徑 `:1575`）；`dispatchGesture` 回呼執行緒由系統決定，程式碼未註明。
- **生命週期**：無派發中狀態追蹤；雙擊第二下為匿名 `postDelayed` Runnable，**無具名持有、無取消**（K-07，PLAN D3-7 dispatch 部分未做，見 §4）。
- **錯誤處理**：`onCancelled` 缺失（K-62）；派發失敗無重試、無狀態清理（`onCompleted` 外無回呼處理）。尚未驗證實機取消行為。
- **測試狀態**：無 dispatch 測試（需系統服務，無法純 JVM 測）。尚未驗證。

#### M12 — AccessibilityService（無障礙服務本體）

- **實際程式檔案**：`GazeAccessibilityService.kt`（1982 行）；`app/src/main/AndroidManifest.xml`（service 宣告，`foregroundServiceType="camera"`，`BIND_ACCESSIBILITY_SERVICE`）；`app/src/main/res/xml/accessibility_service_config.xml`（`canPerformGestures=true`、`canRetrieveWindowContent=true`、`typeAllMask`、`notificationTimeout=100`）。
- **主要類別**：`GazeAccessibilityService : AccessibilityService(), LifecycleOwner`（`:41`）；`enum ActionMode`（`:43`，TAP／DOUBLE_TAP／LONG_PRESS／SWIPE／PAUSED）；`AppFlowState` 狀態機持有（M6／Phase 3，見 M17 註）；`TrackingSnapshot`（`:1734`）；companion 靜態入口（`instance`、`trackingSnapshot()`、靜態控制函式 `:1734-1982`）。
- **主要函式**：`onCreate :315`、`onServiceConnected :320`（建 overlay、註冊 prefs 監聽、`startEngine`）、`onConfigurationChanged :352`（overlay 跟轉、不重建）、`startForegroundService :367`（通知管道＋`startForeground(1001, …, TYPE_CAMERA)`，`:384`）、`startEngine :391`、`onSample/onSampleOnMainThread :484,491`、`requestPauseForCalibration/resumeAfterCalibration :1648,1688`、`pauseTracking/resumeTracking/stopTracking/stopCompletely :1823-1952`（companion，經 `mainHandler.post` 生效）、`onDestroy :1607`（停引擎、關 `GazeLog`、移除 overlay）、`onAccessibilityEvent :1634`（`= Unit`，不處理事件）、`onInterrupt :1635`（`= Unit`）。
- **輸入資料**：`GazeSample` 串流、prefs 變更（設定／情境）、Dashboard 靜態控制呼叫、校正／訓練頁交接呼叫、系統設定啟用／停用。
- **輸出資料**：手勢注入（M11）、overlay 顯示（M13）、`TrackingSnapshot`（Dashboard 輪詢）、`GazeLog` 紀錄。
- **依賴模組**：全部模組（M1–M11、M13–M15）。為全系統唯一協調者；亦為單點複雜度集中處（1982 行）。
- **執行緒**：主執行緒為唯一寫入執行緒（狀態欄位 `@Volatile` 供 Dashboard 讀取；`profileListener` 可能在非主執行緒回呼，經 `mainHandler.post` 轉主執行緒，`:86-104`）；引擎回呼經 `mainHandler.post` 轉主執行緒（`:484-491`）；`stopTracking/stopCompletely` 的停引擎放背景執行緒（`:1927-1952`）。
- **生命週期**：見 `onServiceConnected`／`onDestroy`；`userStopped`／`inRecoveryMode` 未持久化（K-30）；`onServiceConnected` 不檢查舊旗標、自動恢復追蹤（現況語義維持）。
- **錯誤處理**：`startEngine` 失敗捕捉→流程態 ERROR＋原因（`:391-431`，相機非同步綁定失敗除外、無可觀測信號）；樣本守衛三道提早返回（`:484,491,511`）。
- **測試狀態**：`AppFlowStateTest`（29 項）與 `RecoveryGateTest`（6 項）測狀態機／守衛純邏輯，不測服務本體。服務本體尚未驗證。

#### M13 — Overlay（浮動覆蓋層）

- **實際程式檔案**：`CursorOverlayView.kt`（307 行）、`ControlPanelView.kt`（229 行）；承載在 M12（`addCursorOverlay :432`、`addControlPanel :449`）。
- **主要類別**：`CursorOverlayView`（M9）、`ControlPanelView`（`:45`，`ControlPanelConfig` 5 鍵：單擊／雙擊／長按／滑動／暫停，`:24-25`；`ControlPanelButton`，`:14`）、`CursorOverlayConfig`（`:14`）。
- **主要函式**：`ControlPanelView.setPanelCollapsed()`（`:67`）、`setHandleProgress()`（`:76`）、`setHover()`（`:142`）、`onDraw`（`:148`）、`drawHandleTab`（`:203`）；承載函式見 M12。
- **輸入資料**：游標三元組／目標狀態（M9）、面板收合態／hover 進度（服務主路徑）、螢幕尺寸／方向（`onConfigurationChanged` 跟轉，不重建，服務 `:352`）。
- **輸出資料**：純繪製（`Canvas`）；**View 本身不吃觸控**（`FLAG_NOT_TOUCHABLE`，服務 `:439,456`）；全部命中判定在服務端（面板 180×700、把手命中 90×240，`ControlPanelView.kt:83-88` 常數；服務 `:700-830`）。
- **依賴模組**：M9（游標狀態）、M12（承載與命中判定）。
- **執行緒**：主執行緒（View 方法只在主執行緒呼叫）。
- **生命週期**：隨服務建立／銷毀；`pausedForCalibration` 期間面板 `GONE`、恢復時 `VISIBLE`（協議回呼內，`:1675-1695`）；自動收合：選完模式 1.2 秒後（`PANEL_COLLAPSE_AFTER_SELECT_MS = 1200L`，`:1976`）或閒置 4 秒（`PANEL_AUTO_COLLAPSE_MS = 4000L`，`:1977`）收回（服務 `:718-724,792`）；暫停模式保持展開。
- **錯誤處理**：`removeView` 包在 `runCatching`／`onDestroy` 清理（`:1607-1632`）；View 無自有錯誤處理。
- **測試狀態**：無 overlay 測試。尚未驗證。

#### M14 — Settings（設定）

- **實際程式檔案**：`GazeSettings.kt`（226 行，純資料＋`clamped()`＋`load/save`）、`SettingsActivity.kt`（409 行）、`app/src/main/res/layout/activity_settings.xml`。
- **主要類別**：`data class GazeSettings`（欄位見 §3.5）；`SettingsActivity : AppCompatActivity`（`:31`）。
- **主要函式**：`GazeSettings.load/save/clamped`（`:140-224`）；`SettingsActivity.onCreate :35`、`onResume :202`、`saveAndFinish :278`、`resetToDefaults :306`、`showDiagSummary :336`、`refreshEdgeGestureRows :213`、`refreshProfileViews :227`、滑桿換算函式（`:368-409`，dwell 400–2500 ms 等）。
- **輸入資料**：使用者手點／滑桿（設定頁為手點介面，無眼控操作）；`gaze_settings` prefs；情境模型狀態（`storedModelVersionText :248`）。
- **輸出資料**：`GazeSettings` 存檔 → `settingsListener` 即時重載（服務 `:64`）；診斷摘要對話框（`GazeLog.summarize`）；隱私說明對話框（`:156-172`，含更新檢查連網說明）；商店更新入口（Play 版，`:190`）。
- **依賴模組**：M6（情境／重校入口）、M15（診斷摘要／開關）、M16（商店更新列）、M12（`isServiceEnabled` 顯示）。
- **執行緒**：主執行緒（Activity）。
- **生命週期**：標準 Activity（`onCreate` 讀設定填 UI、`onResume` 重刷情境顯示）；設定變更即時生效（prefs 監聽），無需重啟服務（`saveAndFinish` 只存檔＋`finish()`）。
- **錯誤處理**：`load()` 存檔損毀回預設值（`:208-222`）；`clamped()` 夾範圍＋NaN 換預設（`:106-125`）；布林開關不參與夾範圍。
- **測試狀態**：無設定頁測試。尚未驗證。備註：Play 版設定頁不顯示遠端監控列（`rowRemote` 依 `BuildConfig.REMOTE_MONITOR_ENABLED` 設 `VISIBLE/GONE`，`SettingsActivity.kt:77-78`）。

#### M15 — Diagnostics（診斷）

- **實際程式檔案**：`GazeLog.kt`（312 行）、`CrashReporter.kt`（69 行）、`VerifyFrames.kt`（367 行）；安裝點 `EyeControlApp`（`CrashReporter.install`，`EyeControlApp.kt:11`）。
- **主要類別**：`object GazeLog`、`object CrashReporter`、`class VerifyFrames(appContext)`（`:33`）、`data class VerifyFrameMeta`（`:338`）。
- **主要函式**：`GazeLog.record()`（`:65`，10 Hz 節流 `MIN_INTERVAL_MS=100`，17 欄）、`close(upload=true)`（`:139`，120 秒輪轉）、`currentSessionId()`（`:162`）、`uploadFrame()`（`:169`）、`uploadFile()`（`:175-198`，Supabase REST POST）、`summarize()`（`:236`）、`latestFile/clearAll`（`:216,222`）；`CrashReporter.install/recordCaught/pendingReport/markShown/clearAll`（`:14-67`）；`VerifyFrames.submit/emitLastGoodAsFaceLost/endSession/storeAndUpload/encodeCrop`（`:72,100,125,173,208`）。
- **輸入資料**：每幀三段座標（raw／gain／cur）＋特徵 6／7／8＋偏移＋lux＋影像度量（`record` 參數）；例外堆疊（CrashReporter）；眼區裁切 Bitmap＋meta（VerifyFrames）。
- **輸出資料**：本機 `filesDir/gaze-logs/*.csv`（最近 3 份）、`filesDir/crash-reports/*.txt`（最近 5 份）、`filesDir/verify-frames/<session>/*.jpg`（每 session 最多 60 張、最近 3 時段）；關檔後上傳 Supabase `gaze_logs` 表（CSV 全文或影格 JSON，`rows=0`）；設定頁診斷摘要文字。
- **依賴模組**：M1（影格來源）、M12（每幀呼叫 `record`）、M14（開關）、網路（`HttpURLConnection`，見 §3.6）。
- **執行緒**：`record` 在主執行緒被呼叫（寫檔同步在呼叫執行緒，10 Hz 節流）；上傳在 `uploadExecutor` 單執行緒（`:41`）；VerifyFrames 編碼在自有單執行緒 worker（`:48`）；CrashReporter 在例外執行緒寫檔（`runCatching` 包覆）。
- **生命週期**：`GazeLog` 無顯式 start／stop，`record(enabled=false)` 即關檔（`:80-83`）；`close()` 在服務 `onDestroy` 呼叫；VerifyFrames session 隨 `endSession()` 結束（引擎 `stop()` 時呼叫）。
- **錯誤處理**：寫檔／上傳失敗只記 Logcat、不打擾、不重試（`uploadFile :173` 註解）；`record` 寫入失敗即 `close()`（`:134` 附近）；上傳前快照檔案內容進記憶體防覆寫（`close()` 註解 `:145-152`）。
- **測試狀態**：無診斷模組測試。尚未驗證。**預設開啟**：`diagnosticLogging=true`、`remoteMonitor=true`（`GazeSettings.kt` 預設與 `load()` 預設皆 true）；Play 版因無 INTERNET 權限上傳不可能發生（`remoteUploadEnabled()`，服務 `:75`）。

#### M16 — Update System（更新檢查）

- **實際程式檔案**：`UpdateChecker.kt`（143 行）；呼叫端 `MainActivity.checkForUpdate()`（`:299`，`SELF_UPDATE_ENABLED` gating）、`SettingsActivity` 商店列（`:190`）。
- **主要類別**：`object UpdateChecker`；`data class UpdateInfo(versionTag, downloadUrl)`（`:32`）。
- **主要函式**：`checkForUpdate(activity, onUpdate)`（`:36`，背景執行緒查、有新版才主執行緒回呼）、`fetchLatest()`（`:46`，`GET https://api.github.com/repos/shengwen0928/eyecontrol/releases/latest`）、`isNewer()`（`:73`，語意化比較）、`startDownload()`（`:103`，`DownloadManager`）、`installApk()`（`:124`，`FileProvider`＋未知來源設定引導）、`openStoreListing()`（`:89`，Play 版）。
- **輸入資料**：GitHub releases API JSON（`tag_name`、`assets[].name == "eyecontrol-debug.apk"`、`browser_download_url`）；`BuildConfig.VERSION_NAME`。
- **輸出資料**：更新提示對話框（MainActivity）、APK 下載（`getExternalFilesDir(DIRECTORY_DOWNLOADS)`）、安裝 Intent。
- **依賴模組**：M17（Main／Settings）；`FileProvider`（`res/xml/file_paths.xml`，僅 downloads 目錄）。
- **執行緒**：`kotlin.concurrent.thread` 背景執行緒（daemon，`:37`）；回呼經 `runOnUiThread`。
- **生命週期**：App 啟動時檢查一次（`MainActivity.onCreate`，`savedInstanceState == null` 時，`:240`）；檢查失敗靜默不打擾（`runCatching … ?: return`，`:37`）。
- **錯誤處理**：無新版／API 失敗／asset 缺失皆靜默（`fetchLatest` 拋出由 `runCatching` 吞掉；asset 缺失拋 `IllegalStateException`，`:66`）；**下載的 APK 無雜湊／簽章完整性校驗**（程式碼中無 checksum／簽名驗證呼叫，見風險 R14）。
- **測試狀態**：無更新系統測試（`isNewer` 純函數亦無測試）。尚未驗證。

#### M17 — Activities（活動層）

- **實際程式檔案**：`MainActivity.kt`（583 行）、`WelcomeActivity.kt`（60 行）、`SetupWizardActivity.kt`（457 行）、`SettingsActivity.kt`（M14）、`CalibrationActivity.kt`（M6）、`TrainingActivity.kt`（866 行）、`EyeControlApp.kt`（11 行）。
- **主要類別**：`MainActivity : AppCompatActivity`（`:36`，launcher）、`WelcomeActivity`、 `SetupWizardActivity`（6 步：歡迎→須知→相機→服務→校正→完成，Phase 3 後步驟 3／4 對調為校正先於服務，見 PLAN D1-4）、`TrainingActivity`（7 項練習，`Kind` 列舉）、`EyeControlApp : Application`。
- **主要函式**：`MainActivity.refreshStatus()`（`:329`，1 秒輪詢 `trackingSnapshot()`，`STATUS_POLL_MS`，`statusHandler`，`:43-47`）、`checkForUpdate :299`、`showCrashReportIfPending :247`；`SetupWizardActivity.goToStep`／步驟狀態卡（`:130-330`）、相機權限 launcher（`:59`）；`WelcomeActivity` 的 `isFirstRun/markSeen`（`eyecontrol_setup` prefs，`:48-60`）；`TrainingActivity.onSample :305`（自有 dwell 判定，不吃靈敏度／濾波設定，見 PLAN §1.1）、`startExercise/advanceExercise`（`:261,279`）。
- **輸入資料**：`trackingSnapshot()`（Dashboard）、系統設定返回結果（引導重查）、相機權限結果、使用者手點（全部 Activity 的按鈕皆手點；眼控只在服務 overlay 層，首次啟動授權步驟無法眼控，K-23）。
- **輸出資料**：狀態列文字（8 態，`STATE_DESIGN.md` §7 對照）、引導完成狀態（只存 `savedInstanceState`，無逐項持久化，見 PLAN §1.1）、訓練進度（當次 session，不存成績）。
- **依賴模組**：M12（快照／控制）、M6（校正入口）、M14（設定入口）、M16（更新）。
- **執行緒**：主執行緒；Dashboard 輪詢 `Handler(Looper.getMainLooper())`，`onPause` 移除回呼（`:288-291`）。
- **生命週期**：標準 Activity 生命週期；`MainActivity` 為 launcher（Manifest）；`CalibrationActivity`／`TrainingActivity` 為 `portrait`＋全螢幕主題（Manifest）；`TrainingActivity.onPause/onDestroy` 停引擎＋`resumeAfterCalibration`（`:221-238`）。
- **錯誤處理**：`MainActivity` 閘門診斷（未校正／服務未開／引擎失敗分項 Toast，D1-14）；引導跳過規則（曾往返後才出現跳過）；訓練頁模型缺失提示（待定位細節）。
- **測試狀態**：無 Activity 測試（無 instrumented test，全樹 `androidTest` 目錄不存在）。尚未驗證。

## 2. 實際呼叫鏈（追蹤程式碼確認是否真正接通）

### 2.1 服務追蹤主鏈（唯一完整接通的端到端鏈）

```
FaceTrackerEngine.start() :122
 → ProcessCameraProvider.getInstance + addListener(main executor) :198-236
 → ImageAnalysis(1280×720, KEEP_ONLY_LATEST, RGBA_8888) :205-218 + bindToLifecycle(DEFAULT_FRONT_CAMERA) :229
 → analyze() :266 → toBitmap() :450 → detectAsync() :280
 → ResultListener :138 → (無臉) onSample(null) :147 / (有臉) lookupFrameMetrics() :293 → EyeImageMetrics.measure() → GazeFeatureExtractor.extract() :161
 → sample.copy(devicePitch/roll, ambientLux, eye metrics) :174-181 → onSample(outSample)
 → GazeAccessibilityService.onSample() :484（守衛 pausedForCalibration/userStopped）→ mainHandler.post → onSampleOnMainThread() :491
 → RECOVERY 分支 :505 / model==null 返回 :511 / 臉遺失分支 :514 / 閉眼凍結 :548-580 / 降級幀分支 :630
 → predict() :598 → 條件偏移 :606 → 靈敏度 :612 → filterX/filterY.filter() :664 → GazeLog.record() :670
 → 條件評估(500 ms) :682 → 提醒分支 :690 → 面板/把手分支 :700 → 邊緣分支(edgeController.update) :835
 → 螢幕 dwell(screenDwell.update) :895 → 進度滿 → triggerActionAt() :1479 → perform*() → dispatchGesture()
 → cursor.setCursor(Triple(x, y, progress)) :935 附近（游標顯示）
```

**接通判定：已確認。**每一步皆有呼叫端與被呼叫端行號對應，無斷鏈（斷點只在錯誤分支：綁定失敗無信號，見 §4）。

### 2.2 校正鏈

```
MainActivity「校正」鈕 / SetupWizard STEP_CALIBRATION / Settings「重新校正」
 → CalibrationActivity.onCreate :158 → onResume :232 → requestPauseForCalibration :281
 → (服務) 背景停引擎 + === 確認 + 主執行緒面板 GONE + onReleased :1648-1687
 → startEngineSafely :326 → 自有引擎 start() → 9 點採樣(onSamplingSample :382, settle 900 ms, 每點 20 幀)
 → commitPoint 離群值剔除 :657 → finishCalibrationInternal :695 → GazeModel.train :172 → GazeEvaluator.evaluate/leaveOnePointOut
 → CalibrationStore.saveModel :724 → ConditionHistory.clearHistory/saveReference :736-744 → showReport :765（常駐報告頁）
 → onDestroy :865 → resumeAfterCalibration :1688（清旗標、面板 VISIBLE、startEngine）
```

**接通判定：已確認。**但有兩處已知斷口：(a) 首次校正無舊模型時，REPORT／ASK／ERROR 頁的注視選擇無法判讀（`onChoiceSample` 吃舊模型預測，`:519-564`）；(b) 取消目標僅預留（見 §4）。

### 2.3 訓練鏈

```
Settings/主畫面 → TrainingActivity.onCreate :96 → 自有引擎 + 自載模型 :110,178
 → requestPauseForCalibration :201 → onResume :212 / onPause :221 / onDestroy :229-238(resume)
 → onSample :305（臉遺失凍結、閉眼凍結、自有 dwell 判定 handleButtonDwell :384、onTargetGaze :454）
```

**接通判定：已確認**（第三個相機消費者獨立接通）；**不吃靈敏度與濾波設定**（PLAN §1.1 記載，程式碼 `onSample` 內未見 `filterX`／`sensitivity` 取用——本任務未逐行窮舉訓練頁全部取用，標待定位細節，結論引用 PLAN）。

### 2.4 邊緣手勢鏈（Phase 3 新鏈）

```
主路徑 :835 → edgeConfig() :1210 → edgeFrame() :1220 → edgeController.update(frame, config) :153
 → EdgeDecision: NoAction / Candidate / Blocked / EnterConfirm / Confirming / Execute / Cancelled（EdgeGestureController.kt:655-676）
 → Execute → performEdgeAction() :1519 → 頂／底：performGlobalAction(GLOBAL_ACTION_BACK/HOME)（:1522,1527）；側邊：performScroll() :1542
 → EnterConfirm → showEdgeConfirm() :1264 → CursorOverlayView.updateConfirmTarget() :217
 → 中斷 → cancelEdgeGesture() :1250 → controller.reset() :457
```

**接通判定：已確認接通至 dispatch；但門檻未校準**（`DISPERSION_MAX_PX_UNCALIBRATED = Float.MAX_VALUE` 永不阻擋，`:509`；連續幀數 2、方向死區 2 px 皆標 UNCALIBRATED，`:502-515`）；**dispatch 取消部分未接通**（見 M11）。

### 2.5 RECOVERY_MODE 重啟鏈（Phase 3 新鏈）

```
Dashboard「停止追蹤」→ stopTracking() :1886（立 inRecoveryMode，引擎續跑）→ 主路徑 RECOVERY 分支 :505 → updateRecoveryTarget() :1301
 → 重啟目標 dwell 滿 → attemptRecovery() :1411 → RecoveryGate.evaluate()（服務／引擎／模型三檢查）→ 通過：重置(filter/錨點/冷卻) + 清旗標 + 面板收合；失敗：failRecovery() :1467 → ERROR + 原因
 → Dashboard 手點恢復 resumeFromRecovery() :1868（走同一驗證序列）
```

**接通判定：已確認。**`stopCompletely()`（`:1925`）為分離的完全停止路徑（立 `userStopped`、背景停引擎）。

### 2.6 條件提醒／快速校正鏈

```
主路徑每 500 ms → evaluateConditions() :1035 → hasSignificantChange() → 無歷史：interpolate() :179 → 有偏移：套用 + 存基準；無歷史且變化：showReminder() :1067
 → 主路徑提醒分支 :690 → updateReminder() :1123（注視 1 秒 + 最少樣本數 → completeQuickCalibration() :1173 → ConditionHistory.addRecord() :217）
 → 逾時 5 秒／移開 0.5 秒 → dismissReminder(markSkipped=true) :1101（存新基準 + 冷卻 60 秒）
```

**接通判定：已確認。**提醒 preempt 最高優先（主路徑 `:690`＋`showReminder` 內 `cancelEdgeGesture()`）。

### 2.7 啟動鏈

```
EyeControlApp.onCreate → CrashReporter.install()
 → MainActivity(launcher) → isFirstRun? → WelcomeActivity → SetupWizardActivity(6 步) → MainActivity
 → 使用者開系統無障礙設定啟用服務 → onServiceConnected :320 → startForegroundService :367 → 建 overlay :335-344 → 註冊 prefs 監聽 → startEngine :344
 → MainActivity 1 秒輪詢 trackingSnapshot() 刷新狀態卡
```

**接通判定：已確認。**首次啟動授權步驟只能手點（K-23，系統頁在 App 控制外）。

## 3. 盤點：依賴、建置、Manifest、儲存、網路、隱私、測試、CI、簽章、授權

### 3.1 依賴（`app/build.gradle.kts` dependencies 段，覆核 baseline §3.1 無差異）

| 依賴 | 版本 | 用途（程式碼證據） |
|---|---|---|
| `androidx.camera:camera-core/camera2/lifecycle/view` | 1.4.2 | M1（`FaceTrackerEngine.kt:13-22` import） |
| `com.google.mediapipe:tasks-vision` | 0.10.26 | M2（import `:24-25`；模型檔 `face_landmarker.task`） |
| `androidx.core:core-ktx` | 1.15.0 | `ContextCompat` 等 |
| `androidx.appcompat:appcompat` | 1.7.0 | Activity 基底 |
| `com.google.android.material:material` | 1.12.0 | Training／設定頁 Material 元件 |
| `androidx.constraintlayout:constraintlayout` | 2.2.0 | Layout |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | `LifecycleRegistry`（服務 `:48`） |
| `junit:junit`（`testImplementation`） | 4.13.2 | 單元測試（唯一測試依賴） |

**無** OkHttp／Retrofit／Coroutines／WorkManager／Room／DataStore／Compose／Firebase／Analytics 依賴（grep 依賴段與 import 未見；網路只用 `HttpURLConnection`，見 §3.6）。無 `androidTestImplementation`。

### 3.2 Gradle（覆核 baseline §3，行號更新）

- AGP `8.11.1`、Kotlin `2.1.0`（根 `build.gradle.kts`，2 行 plugin 宣告）；Gradle wrapper 宣告 `8.13`（`gradle/wrapper/gradle-wrapper.properties`，`distributionUrl`）；CI 亦用 `gradle/actions/setup-gradle@v4` 指定 8.13（見 §3.8）。
- `compileSdk = 36`、`minSdk = 26`、`targetSdk = 36`、`versionCode = 13`、`versionName = "0.5.9"`、`applicationId = "tw.com.zhu.eyecontrol"`、`namespace = "com.example.eyecontrol"`（`app/build.gradle.kts:21-30`）。
- Java 17／Kotlin JVM 17；`viewBinding = true`、`buildConfig = true`（`:111`）；`isMinifyEnabled = false`（release，proguard 檔雖引用但實際不混淆——baseline §3.2 註記，維持現況）；`noCompress += "task"`（`:115`）。
- 雙 flavor：`sideload`（`SELF_UPDATE_ENABLED=true`、`REMOTE_MONITOR_ENABLED=true`，`:42-43`）／`play`（兩者皆 false，`:47-49`），dimension `distribution`。
- **repo 無 `gradlew`／`gradlew.bat`**（全樹只見 `gradle/wrapper/gradle-wrapper.jar`＋properties；baseline §3.2 記載該 jar 在 runner 上零輸出瞬間 exit 1——本任務未執行該 jar，不重現、不背書）。

### 3.3 AndroidManifest、權限、Service（覆核 baseline §4 無差異）

- 主 Manifest（82 行）：權限 `CAMERA`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CAMERA`、`POST_NOTIFICATIONS`、`INTERNET`、`REQUEST_INSTALL_PACKAGES`；`uses-feature camera.front required=true`；Application `EyeControlApp`；6 個 Activity（Main 為 launcher／exported；Calibration／Training 為 portrait＋全螢幕主題）；`FileProvider`（`file_paths.xml`，僅 `Download/`）；無障礙服務宣告（`foregroundServiceType="camera"`、`BIND_ACCESSIBILITY_SERVICE`）。
- Play 疊加 Manifest（`app/src/play/AndroidManifest.xml`）：以 `tools:node="remove"` 移除 `INTERNET` 與 `REQUEST_INSTALL_PACKAGES`。
- 無障礙設定（`accessibility_service_config.xml`）：`typeAllMask`、`feedbackGeneric`、`flagDefault|flagRetrieveInteractiveWindows`、`canPerformGestures=true`、`canRetrieveWindowContent=true`、`notificationTimeout=100`。
- **無**其他 Service／Receiver／ContentProvider 宣告（除 FileProvider 外）；無 `SYSTEM_ALERT_WINDOW` 權限宣告（overlay 走 `TYPE_ACCESSIBILITY_OVERLAY`，不需該權限——機制說明，非程式碼行號證據，標待定位細節）。

### 3.4 Service（除無障礙服務外無其他 Service）

全樹 Service 只有 `GazeAccessibilityService`（M12）。無獨立 Foreground Service 類別——前景化由無障礙服務自身 `startForeground()` 完成（`:367-386`）。無 JobService／WorkManager 排程。

### 3.5 資料儲存（全部本機，`MODE_PRIVATE`；無加密）

| 儲存 | 位置／檔名 | 內容 | 寫入端 |
|---|---|---|---|
| `gaze_settings` prefs | `GazeSettings.PREFS_NAME`（`:127`） | 全部可調參數（dwell／冷卻／半徑／眨眼／濾波／靈敏度／陀螺儀／臉距／條件／診斷／監控／虹膜／邊緣 5 開關） | `GazeSettings.save/load` |
| `calibration_profiles` prefs | `CalibrationStore.PREFS_NAME`（`:16`） | 三情境模型 JSON（`model_<id>`）＋`ProfileMeta`（`meta_<id>`：`calibratedAt`、`looErrorPx`）＋`active_profile`＋遷移旗標 | `CalibrationStore`；舊版單模型 `GazeModel.save/load`（`GazeModel.kt:111-163`，`gaze_model` prefs，僅遷移用） |
| `condition_history` prefs | `ConditionHistory` 私有 `PREFS_NAME`（`:204`） | 每情境最多 50 筆 `Record`（條件＋偏移）＋基準 `Condition` | `ConditionHistory` |
| `eyecontrol_setup` prefs | `WelcomeActivity.kt:48` | `welcome_seen`（首次啟動旗標） | `WelcomeActivity` |
| `crash_report` prefs | `CrashReporter.kt:37` | 已顯示報告檔名旗標（`shown_<檔名>`） | `CrashReporter` |
| `filesDir/gaze-logs/` | `GazeLog.kt:91`（`DIR="gaze-logs"`） | CSV 診斷紀錄，最近 3 份，120 秒輪轉 | `GazeLog` |
| `filesDir/crash-reports/` | `CrashReporter.kt:37` | 閃退報告 txt，最近 5 份 | `CrashReporter` |
| `filesDir/verify-frames/` | `VerifyFrames.kt:288`（`FRAMES_DIR`） | 眼區裁切 JPEG，每 session 最多 60 張、最近 3 時段 | `VerifyFrames` |
| `getExternalFilesDir(Download)/eyecontrol-debug.apk` | `UpdateChecker.kt:118` | 下載的更新 APK（FileProvider 分享安裝） | `UpdateChecker` |
| 流程狀態持久化 | — | **無**：`userStopped`／`inRecoveryMode`／`currentMode`／`pausedForCalibration` 皆記憶體欄位（K-30）；引導逐項完成亦無持久化（只存 `savedInstanceState`） | — |

**無資料庫、無 DataStore、無加密儲存。**模型 JSON 以明文存於 SharedPreferences（`allowBackup=true`，Manifest——備份行為待定位，見風險清單）。

### 3.6 網路請求（全部三處，皆 `HttpURLConnection`，無第三方 HTTP 庫）

| 請求 | 檔案行號 | 條件 | 傳送內容 |
|---|---|---|---|
| Supabase 診斷上傳 `POST {SUPABASE_URL}/rest/v1/gaze_logs` | `GazeLog.kt:175-198` | 側載版＋`remoteMonitor` 開＋當段曾開啟上傳（`pendingUpload`） | CSV 全文（游標數字）或影格 JSON（含 base64 眼區 JPEG）；header 含 `apikey`／`Authorization: Bearer <anon key>`（值不抄錄） |
| GitHub releases 查詢 `GET api.github.com/repos/shengwen0928/eyecontrol/releases/latest` | `UpdateChecker.kt:46-66` | 僅側載版（`SELF_UPDATE_ENABLED`） | 無個人資料（僅 `Accept`／`User-Agent: eyecontrol-updater` header）；回傳 tag＋asset URL |
| APK 下載 | `UpdateChecker.kt:103`（`DownloadManager`） | 使用者點「下載更新」後 | 下載 `browser_download_url` 的 APK；**無完整性校驗** |

Play 版：無 INTERNET 權限，上述三者皆不可能發生（更新走 Play 商店 `openStoreListing`，`:89`）。其餘程式碼無任何網路呼叫（grep `HttpURLConnection`／`URL(`／`supabase`／`OkHttp`／`Retrofit` 全樹僅上述兩檔命中）。

### 3.7 隱私資料流（摘要；細節見 `docs/privacy-data-flow.md`，本文件只覆核程式碼落點）

```
相機幀(Bitmap) → 引擎記憶體內量測 → landmark/特徵（記憶體）→ 預測座標（記憶體）
  ├─ 數字紀錄：GazeLog CSV（本機）→ [側載+開關] Supabase gaze_logs（數字 + 眼區 JPEG）
  ├─ 影像留存：VerifyFrames 眼區裁切（本機 + [側載+開關] Supabase同一表）
  ├─ 影像本身：measure() 只產數字、不存影像；cropEyes() 僅供 VerifyFrames（EyeImageMetrics.kt 檔頭）
  └─ 不出裝置：特徵向量、landmark 清單、完整相機幀、全臉影像皆無上傳路徑（grep 無相關上傳呼叫）
感測器：旋轉向量（pitch/roll/yaw）＋光感測器（lux）→ 樣本附加欄位 → 條件歷史（本機 prefs）→ 診斷 CSV 的 lux 欄
無障礙服務：canRetrieveWindowContent=true 已宣告，但未見節點內容讀取／記錄呼叫（待定位）；onAccessibilityEvent 不處理事件
```

**文案矛盾（已知缺口 K-12 殘餘）**：`strings.xml:3` 的 `accessibility_desc` 稱「影像只在本機即時處理，不儲存、不上傳」，與預設開啟的診斷／監控上傳事實矛盾；`SettingsActivity` 隱私對話框（`:156-172`）與主畫面頁尾亦有絕對表述殘餘（IMPLEMENTATION_NOTES 遺留 (e) 記載，本任務未逐一重核全部文案位置，標待定位）。`docs/privacy.html` 為上架用隱私權政策頁（GitHub Pages），內容與程式碼的一致性**尚未驗證**（見 `docs/privacy-copy-audit.md`，引用不重做）。

### 3.8 現有測試

- 位置：`app/src/test/java/com/example/eyecontrol/`，7 個測試類別，靜態 `@Test` 計數 **110 項**（`AppFlowStateTest` 29、`ConditionHistoryTest` 22、`DwellTrackerTest` 11、`EdgeGestureControllerTest` 25、`GazeModelMathTest` 10、`OneEuroFilterTest` 7、`RecoveryGateTest` 6；`grep -c "@Test"` 實測合計 110）。
- 性質：全部為純 JVM 單元測試（JUnit 4），只覆蓋純 Kotlin 邏輯（狀態機、條件內插、dwell、邊緣控制器、模型數學、濾波、重啟守衛）；`org.json` 在純 JVM 為 stub，JSON 往返未測（測試檔頭自述）；Android 框架接線（服務、Activity、overlay、dispatch、引擎、更新、診斷上傳）**零測試**。
- 無 `androidTest` 目錄、無 instrumented test、無 UI 測試。
- **執行狀態：尚未驗證。**本任務未執行任何測試（本機 Gradle 因沙箱 JVM loopback 問題無法執行，見 IMPLEMENTATION_NOTES §0；本任務未重試）。先前記載「協調員親自重跑 harness OK (110 tests)」見 `~/workspace/eyecontrol-phase3/TEST_RESULTS.md`，本文件引用該記載、**不代為背書**，不得改寫為本任務已驗證。

### 3.9 CI（唯讀核對，不得修改；GitHub App 無 workflows 權限限制）

- 檔案（唯讀核對，未修改）：`.github/workflows/build-apk.yml`（push 到 main／手動觸發；Temurin JDK 17；runner 預裝 SDK 裝 `platforms;android-36`＋`build-tools;36.0.0`；`gradle/actions/setup-gradle@v4` 指定 8.13；建 `assembleSideloadDebug`＋`bundlePlayRelease`；上傳 APK／AAB artifact 各一，保留 30 天；**無測試步驟**）、`.github/workflows/attach-apk-to-release.yml`（手動觸發，建 sideload debug APK 掛到指定 release；**無測試步驟**）。
- **限制（必須記錄）**：GitHub App（Muse 用）**沒有 workflows 權限**，以 `push-files` 建立／修改 `.github/workflows/` 下檔案會回 403；workflow 檔案一律走雲端瀏覽器＋使用者登入工作階段在 GitHub 網頁上建／改（見 `~/AGENTS.md` 教訓）。本任務遵守此限制：只讀取 workflow，未嘗試修改。
- CI 執行紀錄：本任務未檢查（未推送、未觸發）。CI 是否曾成功編出目前工作樹（含 Phase 3 實作）：**尚未驗證**。`docs/ci-validation-report.md` 的既有記載引用不重做。

### 3.10 簽章

- Debug（側載）：固定 debug 簽名機制（`app/build.gradle.kts` 檔頭註解＋`:60-70` 附近 signingConfig）：`app/debug-keystore.b64`（base64 文字檔）在建置時解回 `debug.keystore`（`.gitignore` 排除）；標準 debug 憑證（`android`／`androiddebugkey`，PKCS12）。本任務**未解碼、未讀取**該檔內容。
- Release（Play）：鑰匙絕不進 repo；CI 從 GitHub secret `EYE_RELEASE_KEYSTORE_B64` 解出（`build-apk.yml` 的 Restore 步驟），路徑由 `EYE_RELEASE_KEYSTORE_FILE` 傳入；本機取 `app/release.keystore`（工作樹不存在，已確認）；缺密碼環境變數時建置期明確報錯（`app/build.gradle.kts` signingConfigs 段）。本任務未讀取任何 secret 值。
- 兩 flavor 共用同一 `applicationId`、簽名不同，互換通路須先刪除舊版（`app/build.gradle.kts` flavor 註解）。

### 3.11 第三方授權

- 本 repo 授權：MIT（`LICENSE`，Copyright 2026 Su ShengWen）。注意：MASTER 第三份規格要求未來切換為 AGPL-3.0，但**現行授權仍為 MIT**，切換前須做相容性稽核、不得直接改 LICENSE（見 MEMORY 記載；本任務未改 LICENSE）。
- 第三方元件授權文件：repo 內**無** MediaPipe／CameraX／AndroidX 的授權聲明檔或 NOTICE 檔（grep `LICENSE`／`NOTICE` 僅見本 repo MIT 與 README 提及）。各依賴的授權條款（Apache-2.0 等）**待定位**——須在發行準備時補齊第三方授權清單（見風險 R19）。`face_landmarker.task` 模型檔的授權／來源雜湊未與官方比對（baseline §8.2 第 7 項，維持待定位）。
- `proguard-rules.pro`：僅保留 `com.google.mediapipe.**`（MediaPipe JNI／反射用）。

## 4. 缺口與文件衝突對照（以實際程式碼為準）

| 項 | 先前文件說法 | 實際程式碼 | 判定 |
|---|---|---|---|
| D1-9 取消目標 | PLAN：只留介面位置 | 僅 4 個 companion 常數（`CANCEL_TARGET_RESERVED*`，`CalibrationActivity.kt:1111-1125`），全樹零引用、無事件、無視覺元素、九點座標未動 | 一致：未完成，僅預留 |
| D3-7 dispatch 部分 | PLAN D3-7：`onCancelled`＋雙擊第二下具名化 | `onCancelled` 全樹零命中（手勢回呼）；`performDoubleTap` 第二下仍為匿名 `postDelayed(150)`（`:1573-1578`） | 一致：未做（IMPLEMENTATION_NOTES 出入 27） |
| 邊緣帶幾何 | PLAN：50 px 改 dp 換算 | `edgeBandPx`：50 dp × 密度、短邊 15% 上限（`EdgeGestureController.kt:106-111,490-493`）；服務端舊 `edgeSize` 已刪 | 一致：已改，高密度裝置帶寬變寬，實機對照尚未驗證 |
| 狀態機「影子模式」 | `AppFlowState.kt` 檔頭舊文字（稱不得被引用） | 服務已多處接線（`syncFlowState :223`、`deriveFlowState :183` 等） | **衝突：以程式碼為準**，檔頭文字已過時，須後續批次修正文件 |
| 品質層狀態機 | STATE_DESIGN §4：`QualityState` 七態 | 無品質列舉；只有分散分支 | 設計 TARGET，未實作；不得稱為已完成 |
| 恢復序列 | STATE_DESIGN §4.2：7 步 | 只實作重置部分（D2-3 (d)＋臉遺失清 dwell）；臉遺失不重置濾波器 | 部分實作，完整序列未做 |
| 首次校正注視選擇 | PLAN D1-7：複用「看點採樣」機制 | 實作複用 TrainingActivity 按鈕 dwell 規則（IMPLEMENTATION_NOTES 出入 14），無舊模型時無法判讀 | 出入已記錄；首次校正詢問頁眼控不可達（已知缺口） |
| `GazeLog` 120 秒 | GazeSettings 註解「每 120 秒自動關檔上傳」 | `MAX_SECONDS = 120L`（`:29`）；逾時 `close()` 後下一筆開新段，連續不中斷（`:110-114`） | 一致 |
| Dashboard 死按鈕 | PLAN D1-5：移除方案 | `btnCamera`／`btnAccessibility` 全樹零命中（baseline §2.3 驗證） | 一致：已移除；相鄰的相機權限請求 launcher 同遭移除，意圖待確認（baseline §8.1） |

## 5. 閱讀方法與限制（誠實記錄）

1. 方法：全樹檔案清單＋逐檔關鍵函式閱讀＋grep 驗證（`onCancelled`、`SmartTarget`、`QualityState`、`onSaveInstanceState` 等零命中皆為實測 grep 結果）；行號以目前工作樹為準。
2. 未做：未執行 Gradle 編譯、未執行測試、未實機驗證、未檢查 CI 執行紀錄、未讀取任何密鑰值、未解碼 keystore、未比對 `face_landmarker.task` 官方雜湊、未檢查 Supabase 伺服端 RLS 實際設定、未逐一核對全部 UI 文案與 `privacy.html` 的一致性。
3. 引用不背書：`TEST_RESULTS.md` 的 harness 結果、`ci-validation-report.md`、`device-acceptance-matrix.md` 的既有記載，本文件引用其存在與結論，不代為重跑或背書。
4. 下一步（STEP 2 對照用）：以本文件 §1.2「不存在／分散」判定為起點，對照 DCL-01～DCL-46 逐項確認；任何「已完成」宣稱皆須補上編譯＋單元測試＋實機三重證據，否則維持「尚未驗證」。
