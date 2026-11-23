# EyeControl 隱私與資料流盤點（PHASE 5 K-12）

> 2026-11-23 增補（review 樹，未合併／未推送）：特徵契約已由授權變更為 25 維（`MODEL_VERSION` 2→3；新增連續光特徵 f[22..24]，舊模型強制重校）；光線改為連續模型輸入、多光源校正；`ConditionHistory` 的 lux 維度退出歷史修正。詳見 `docs/light-robust-gaze-design.md`。本變更不改變任何上傳面（特徵向量從不上傳；診斷 CSV 欄位不變）。本文以下「22 維」字樣已就 §2.3 與總表兩處改為 25 維，其餘歷史敘述原文不改。

> 性質：唯讀稽核文件，只新增本文件，不改任何程式碼、文案、版本號與 workflow。
> 基線：`~/workspace/eyecontrol-p3`（v0.5.9 加未推送的 Phase 3 實作；遠端 main HEAD `c6b80964`；
> `app/build.gradle.kts`：`versionCode = 13`、`versionName = "0.5.9"`）。
> 本文件引用的檔案行號一律以 p3 樹為準。行號為撰寫時親自查證的現況行號；
> 與 v0.5.7 稽核文件的行號已有漂移，引用舊文件時以舊文件自述的基線為準，不得直接混用。
> 密鑰處理：本文件只記錄機制與程式位置，**不轉錄任何密鑰值**（Supabase anon 金鑰、
> 簽名密碼等一律不抄錄）。伺服端設定（Supabase RLS、GitHub 伺服端紀錄、系統備份實際內容）
> 無法由本樹程式碼驗證者，一律標「待定位／待實機驗證」，不得宣稱已驗證。

## 0. 先前文件複用與基線差異

### 0.1 複用的先前結論（已再驗證，仍成立者）

| 先前來源 | 結論 | 本樹再驗證 |
|---|---|---|
| `eyecontrol-master-audit/docs/audit/SECURITY_AND_PRIVACY_AUDIT.md` §8.1 | 外流只有更新檢查、APK 下載、Supabase 上傳、Play 商店意圖、使用者主動複製、備份六類 | 仍成立，但須補：眼區驗證裁切已成為 Supabase 上傳的第二種負載（見 §2.7／§2.10）；診斷 CSV 已改為 120 秒自動輪轉連續紀錄（見 §2.7） |
| 同上 §8.2、K-11（`KNOWN_ISSUES.md`） | 關閉監控開關不阻止當下檔案上傳（`pendingUpload` 機制） | **仍成立**，見 §4.2。本樹 `settingsListener` 仍未在開關關閉時重置 `pendingUpload` 或以 `close(upload=false)` 丟棄檔案 |
| 同上 S-04、K-12 | 「不上傳」等絕對字句與上傳程式碼矛盾 | 仍成立，且範圍已變化：部分文案已按 flavor 補例外段（見 `privacy-copy-audit.md`），但 Settings 對話框、主畫面頁尾、`strings.xml` 三處仍為絕對表述（見 §5） |
| 同上 S-17、K-20 | `allowBackup=true` 且無備份排除規則 | **仍成立**：`AndroidManifest.xml:16` 仍為 `allowBackup="true"`，全樹仍無 `fullBackupContent`／`dataExtractionRules`／備份規則檔（見 §6） |
| 同上 S-12、K-45 | Play 版 `uploadFile` 僅靠間接防護，無 `BuildConfig` 顯式守衛 | **仍成立**：`GazeLog.kt:175` 起的 `uploadFile` 入口仍無 `BuildConfig.REMOTE_MONITOR_ENABLED` 檢查（見 §3.2） |
| `eyecontrol-phase3/IMPLEMENTATION_NOTES.md` §1 出入第 12／21 點、§2 未決點 | STEP_CAMERA／歡迎頁已修，Settings 對話框、主畫面頁尾、`strings.xml` 的絕對表述仍待後續批次；相機非同步綁定失敗無可觀測信號 | 前者再驗證成立（見 §5）；後者屬 PHASE 6 範圍，見 `camera-binding-observability.md` |
| `eyecontrol-phase3/PLAN.md` §0.2 紅線第 4 點 | 保留 v0.5.9「診斷紀錄、遠端監控預設開啟」的決定，本階段不動兩開關預設值與語義 | 本文件如實記錄該預設，不代為改變；文案改寫須以此預設為前提（見 §5） |

### 0.2 基線差異：預設值已經改變，舊稽核的「預設關」敘述已過時

舊稽核（v0.5.7）稱診斷與監控「預設關閉屬實」。本樹現況相反：

- `GazeSettings.kt:47-57`：`diagnosticLogging: Boolean = true`、`remoteMonitor: Boolean = true`，
  註解明載「預設開啟（調校期間）；關掉後停止上傳」。
- `GazeSettings.load()`（`GazeSettings.kt:185-186`）：`getBoolean(KEY, true)`；註解說明
  預設值只在「從未存過該 key」時生效，先前明確關掉者升級後仍是關。
- `GazeSettings()` 無參數建構（損毀回退路徑，`GazeSettings.kt:177-179` 的 `catch` 回 `GazeSettings()`）
  同樣回預設開啟的兩開關。

因此：**首次安裝（從未存過設定）的側載版，診斷紀錄與遠端監控皆為開啟**；任何仍寫
「預設關閉」的文件皆已失實。`docs/privacy.html` 本樹版本已改為「預設開啟」，與程式一致；
舊稽核文件與舊版 `privacy.html` 的「預設關」敘述不得再引用為現況。

### 0.3 盤點方法

- 全樹 grep 網路呼叫（`HttpURLConnection`、`URL(`、`DownloadManager`、`Intent.ACTION_VIEW`）、
  檔案寫入（`filesDir`、`getExternalFilesDir`、`writeText`、`bufferedWriter`、
  `FileOutputStream`）、SharedPreferences（全部 `PREFS_NAME`／`getSharedPreferences`）、
  剪貼簿（`ClipboardManager`）、Logcat（`Log.`）。
- 逐檔閱讀：`GazeLog.kt`、`VerifyFrames.kt`、`CrashReporter.kt`、`UpdateChecker.kt`、
  `GazeSettings.kt`、`CalibrationStore.kt`、`ConditionHistory.kt`、
  `FaceTrackerEngine.kt`、`GazeAccessibilityService.kt`（資料流相關段）、
  `SettingsActivity.kt`、`MainActivity.kt`、`EyeControlApp.kt`、
  `AndroidManifest.xml`、Play 疊加 Manifest、`app/build.gradle.kts`。
- 每種資料流以同一組問題盤點：只在裝置內處理？寫入本機儲存？送往遠端？
  何條件送出？是否需同意？能否拒絕？撤回後如何停止？已排程未上傳資料如何處置？
  備份／系統自動還原行為？保存期限與刪除方式？
- 「同意」在本文件中指：使用者在知情的前提下，以開關／按鈕／系統授權明確表示接受。
  單純「開關預設開啟、使用者未關」**不**計為明示同意；本文件如實標示，不美化。

## 1. 資料流總覽

| # | 資料流 | 只在裝置內 | 寫入本機 | 送往遠端 | 關鍵條件 |
|---|---|---|---|---|---|
| 1 | 相機影像（全幀 Bitmap） | 是（見例外 1b） | 否（全幀） | 否（全幀） | — |
| 1b | 眼區驗證裁切（雙眼聯集 JPEG） | 否 | 是 | 是（側載＋兩開關開著時） | 見 §2.1 |
| 2 | Face Landmarker 輸出（臉部關鍵點） | 是 | 否 | 否 | — |
| 3 | 眼部特徵與感測器快照（25 維，含連續光特徵＋角度／亮度／影像度量） | 是（見例外：部分數值進 CSV） | 是（僅 CSV 欄位值） | 是（僅 CSV 欄位值，條件同 §2.7） | 見 §2.3 |
| 4 | 視線預測與游標座標（原始／放大／濾波後） | 是（見例外：部分數值進 CSV） | 是（僅 CSV 欄位值） | 是（僅 CSV 欄位值，條件同 §2.7） | 見 §2.4 |
| 5 | 校正資料（模型權重＋中繼資料＋校正中暫存） | 是 | 是（SharedPreferences） | 否 | — |
| 6 | 使用者設定（`gaze_settings` 等 6 份 prefs） | 是 | 是（SharedPreferences） | 否（程式不送出；備份面見 §6） | — |
| 7 | 診斷紀錄（GazeLog CSV） | 否（條件上傳） | 是（`filesDir/gaze-logs/`） | 是（條件同 §2.7） | 兩開關任一開即寫檔；`pendingUpload` 曾真即關檔上傳 |
| 8 | Crash Report（閃退紀錄） | 是（程式不送出） | 是（`filesDir/crash-reports/`） | 否（僅使用者主動複製／截圖才外流） | 見 §2.8 |
| 9 | 更新檢查資訊（GitHub API 請求） | 否 | 否 | 是（側載版全新啟動時） | 無 App 內開關可關閉；見 §2.9 |
| 10 | Supabase 請求（CSV＋影格 JSON） | 否 | —（送出前快照在記憶體） | 是（條件同 §2.7／§2.10） | 見 §2.10 |
| 11 | GitHub Release 更新請求（APK 下載＋商店意圖） | 否 | 是（APK 檔） | 是（使用者點下載／點商店列） | 見 §2.11 |

補充邊界（不屬 11 種之列，但盤點時已檢查）：

- 無障礙服務宣告 `canRetrieveWindowContent=true`（`accessibility_service_config.xml`），
  但全樹無 `AccessibilityNodeInfo`／`getRootInActiveWindow`／`getWindows` 呼叫，
  `onAccessibilityEvent`／`onInterrupt` 為空實作（`GazeAccessibilityService.kt:1632-1633`）。
  程式碼**不讀取、不記錄**其他 App 畫面內容；宣告本身賦予的能力與程式行為的落差，
  屬 K-22／S-01 範圍，本文件只記錄資料流事實：**無畫面內容資料流**。
- Logcat：`FaceTrackerEngine.kt:185,192,234,283` 與 `GazeAccessibilityService.kt` 多處
  `Log.d／w／e` 只寫文字與例外，不寫影像、關鍵點、特徵向量或完整樣本。
  但 `GazeAccessibilityService.kt:624` 的 `Log.d("臉轉向：…")` 與 `:1060,1093,1201` 的
  偏移／條件 `Log.d` 會把臉轉向分類與偏移數值寫入 logcat；logcat 本身不上傳，
  但任何能讀 logcat 者可見。`Log` 無 `BuildConfig.DEBUG` 守衛（K-62／S-19 殘留）。
- 剪貼簿：只有使用者主動點「複製摘要」／「複製紀錄」才寫入剪貼簿
  （`SettingsActivity.kt:354-357`、`MainActivity.kt:262-265`），程式不自動外傳。

## 2. 十一種資料流逐項盤點

### 2.1 相機影像（全幀）與眼區驗證裁切

**處理鏈**：`FaceTrackerEngine.start()`（`FaceTrackerEngine.kt:122`）→
`ImageAnalysis` 1280×720、`OUTPUT_IMAGE_FORMAT_RGBA_8888`（`:205-218`）→
`analyze()`（`:266` 起）→ `toBitmap()`（`:443` 起，三緩衝複用）→
`detectAsync`（`:283`）→ 結果回呼內的 `lookupFrameMetrics`／`captureEyeCrop`。

**全幀影像**：

- 只在裝置內處理：**是**。`toBitmap` 產出的 Bitmap 只存在三緩衝槽
  （`cachedRotatedBmps`，`FaceTrackerEngine.kt:42`）與中間緩衝 `cachedBmp`（`:41`），
  `stop()`（`:506` 起）回收全部緩衝；全樹無全幀寫檔、無全幀上傳呼叫。
- 寫入本機儲存：**否**（全幀）。`EyeImageMetrics.kt:14` 註解與實作一致：
  `measure()` 只產出數字，Bitmap 只在記憶體讀取。
- 送往遠端：**否**（全幀）。無任何全幀影像進入 `GazeLog.uploadFile` 或其他網路呼叫。

**眼區驗證裁切（例外，必須與全幀分開陳述）**：

- 來源：`captureEyeCrop()`（`FaceTrackerEngine.kt:380` 起）只裁雙眼外框聯集，
  `EyeImageMetrics.cropEyes()`（`EyeImageMetrics.kt:100` 起）；`VerifyFrames.kt:26-27`
  註解稱「絕不含全臉、絕不含螢幕截圖」。裁切尺寸上限 320px 寬、JPEG 品質 70、
  單張上限 32KB（`VerifyFrames.kt:296-306`）。
- 寫入本機：**是**。`VerifyFrames.storeAndUpload()`（`VerifyFrames.kt:172` 起）：
  `localEnabled` 為真且時段 id 仍有效時，寫入 `filesDir/verify-frames/<時段id>/frame-<序號>.jpg`
  （`:184-187`）。時段 120 秒、每時段最多 60 張、本機只留最近 3 個時段
  （`VerifyFrames.kt:288-297`、`pruneSessions()` `:195` 起）。
- 送往遠端：**是，有條件**。同一函式內，`uploadEnabled` 當下仍為真時，
  以 base64 JPEG 包進 JSON，經 `GazeLog.uploadFrame()`（`GazeLog.kt:169`）上傳
  至 Supabase（見 §2.10）。上傳檢查發生在背景執行緒執行 `storeAndUpload` 的當下，
  不是擷取當下；關開關後已排隊但尚未執行到檢查的影格不會上傳（見 §4.3 對照）。
- 需同意／能否拒絕：**無專門同意對話框**。開關即 `GazeSettings` 的
  `diagnosticLogging`（本機留存）與 `remoteMonitor`（上傳），預設皆開啟（見 §0.2），
  使用者須進設定頁按「儲存」才能關閉（`SettingsActivity.kt:285-300` 的
  `saveAndFinish()`）。校正頁的引擎實例不設開關（`FaceTrackerEngine.kt:71-76` 註解），
  故校正流程不產生影格；訓練頁同樣不設開關（預設 `localEnabled=false`／
  `uploadEnabled=false`，`VerifyFrames.kt:36-40`），訓練流程不產生影格。
- 撤回後停止：見 §4.3。已寫入的本機影格**不會**因關開關而刪除；已上傳的影格
  無 App 內刪除路徑。
- 備份／還原：`verify-frames/` 在 `filesDir` 下，屬預設備份範圍（見 §6，實際內容待驗證）。
- 保存期限／刪除：無時間期限，只有數量／時段上限（3 時段、60 張／時段）與
  `pruneSessions()` 淘汰；**無 UI 清除按鈕**（`GazeLog.clearAll()` 不清
  `verify-frames/`；全樹無 `VerifyFrames` 的 `clearAll`）。刪除只能靠解除安裝、
  清除 App 資料，或時段淘汰。

### 2.2 Face Landmarker 輸出

- 內容：`FaceLandmarkerResult.faceLandmarks()` 首張臉的關鍵點清單
  （`FaceTrackerEngine.kt:139`），只在結果回呼執行緒內使用，餵給
  `lookupFrameMetrics`、`GazeFeatureExtractor.extract`、`maybeCaptureVerifyFrame`。
- 只在裝置內處理：**是**。無寫檔、無上傳、無 Log 輸出關鍵點座標。
- 寫入本機／送往遠端：**皆否**。關鍵點本身不進 CSV（CSV 只含特徵 6／7／8 三個
  衍生數值，見 §2.3）；眼區裁切取的是影像像素，不是關鍵點清單。
- 需同意：屬核心功能，以相機權限＋無障礙服務授權為前提；無單獨開關。
  `FaceTrackerEngine.start()` 本身不檢查相機權限（全檔無 `checkSelfPermission`），
  依賴呼叫端守衛（K-58／S-13 殘留）；呼叫端見 §7（`camera-binding-observability.md`）。
- 備份／保存／刪除：**不適用**（純記憶體，`stop()` 後無殘留；幀槽在 `stop()` 內清為
  `timestampMs=-1`、`bitmap=null`，`FaceTrackerEngine.kt:514-519`）。

### 2.3 眼部特徵與感測器快照

- 內容：`GazeFeatureExtractor.extract()`（`GazeFeatureExtractor.kt:69`，`DIM = 25`，
  `:67`）產出的 25 維特徵向量（含連續光特徵 f[22..24]，由 `ambientLux` 換算）；每幀另附感測器快照
  （`devicePitch／deviceRoll`、`ambientLux`，`FaceTrackerEngine.kt:171-178`）
  與影像度量（`eyeMeanLum／eyeContrast／specularRatio`，同處）。
  感測器來源：`DeviceOrientationTracker`（旋轉向量）與 `LightSensorTracker`
  （環境光），兩者皆只提供記憶體快照（`snapshot()`），無持久化、無寫檔
  （兩檔全檔無 `SharedPreferences`／`filesDir`／寫入呼叫）。
- 只在裝置內處理：**原則上是，CSV 欄位為例外**。特徵向量本體不寫檔、不上傳；
  但 CSV 記入特徵索引 6（iod）、7（pitch）、8（yaw）三個值
  （`GazeLog.kt:119-121` 的 `f?.getOrNull(6/7/8)`），以及 `lux／imgQ／glassesHint`
  三個度量值（`:131-133`）。其餘 19 維不進 CSV。
- 寫入本機：**僅上述 6 個欄位值**，隨 CSV 寫入（見 §2.7）。特徵向量不以完整形式存檔；
  校正期間的特徵只存在 `CalibrationActivity` 記憶體串列（`allFeatures` 等，
  `CalibrationActivity.kt:59-63`），重建即丟失（見 `restart-recovery-policy.md`）。
- 送往遠端：**僅上述 6 個欄位值**，隨 CSV 上傳（條件同 §2.7）。
- 需同意／拒絕／撤回：與 CSV 共用同一組開關（見 §2.7／§4）；無特徵專屬開關。
  關閉兩開關後，`GazeLog.record(enabled=false)` 即關檔不寫入（見 §4.2 的但書）。
- 備份／保存／刪除：與 CSV 相同（見 §2.7）；特徵向量本身無保存期限問題（不存檔）。

### 2.4 視線預測與游標座標

- 內容：`GazeModel.predict()` 的原始預測（`rawX／rawY`）、經條件偏移與靈敏度
  放大後的座標（`gainedX／gainedY`）、經 `OneEuroFilter` 後的游標座標
  （`curX／curY`），三段皆在 `GazeAccessibilityService.onSampleOnMainThread`
  內計算（`GazeAccessibilityService.kt:665` 起的主路徑；臉遺失／降級／RECOVERY
  分支的 `GazeLog.record` 呼叫見 `:528,633,672,1315,1365`）。
- 只在裝置內處理：**原則上是，CSV 欄位為例外**。三段座標只在記憶體內用於
  游標繪製與 dwell 判定；`trackingSnapshot()` 只回布林／狀態，不回座標
  （`GazeAccessibilityService.kt:1752` 起）。
- 寫入本機／送往遠端：**僅 CSV 欄位值**（`rawX／rawY／gainX／gainY／curX／curY`，
  `GazeLog.kt:31` 表頭、`GazeLog.kt:122-125` 寫入），條件同 §2.7。
  臉遺失幀的座標欄位為空（`face=0`，座標皆 null），仍會寫入一行（見 §2.7）。
- 需同意／拒絕／撤回：與 CSV 共用開關（見 §2.7／§4）。
- 備份／保存／刪除：與 CSV 相同。

### 2.5 校正資料

- 內容：三情境模型 JSON（`wx／wy` 權重、`basePitch／baseYaw／kx／ky／baseIod`、
  選填的 `deviceBasePitch／deviceBaseRoll`、`version`，`GazeModel.kt:95-109` 的
  `toJsonString()`）＋中繼資料（`calibratedAt`、`looErrorPx`，`CalibrationStore.kt:79` 起的
  `saveModel()`）；舊版單一模型（`gaze_model` prefs，`GazeModel.kt:121` 起）僅供遷移讀取。
- 只在裝置內處理：**是**。無上傳呼叫；`saveModel`／`loadModel`／`exists`／`getMeta`
  皆只讀寫 `calibration_profiles` prefs（`CalibrationStore.kt:39`，`MODE_PRIVATE`）。
- 寫入本機：**是**。鍵格式 `model_<id>`／`meta_<id>`、`active_profile`
  （`CalibrationStore.kt:33-34`）；版本／維度不符視為未校正
  （`GazeModel.fromJsonString`，`GazeModel.kt:141` 起）。
- 送往遠端：**否**。
- 需同意／能否拒絕：校正為使用者主動進入 `CalibrationActivity` 完成九點採樣後
  才存檔（`finishCalibrationInternal` 內的唯一 `saveModel` 呼叫點，
  見 `CANCEL_CONTRACT.md` 第 5／6 條引用的位置）；未完成校正不寫入（取消契約）。
  無「不同意校正資料儲存」的單獨選項：不校正即無模型、游標不運作
  （`GazeAccessibilityService.startEngine` 遇無模型只記 Log，`:399`）。
- 撤回後停止／刪除：**無刪除全部校正資料的 UI**。`SettingsActivity.kt:29` 註解
  明載「清除全部校正資料…一律不顯示」；全樹無刪除模型鍵的公開 API
  （只有 `ConditionHistory.clearHistory` 清條件歷史，且只在完整重校存檔成功後呼叫）。
  覆蓋方式只有重新校正同一情境。`migrateIfNeeded`（`CalibrationStore.kt:102` 起）
  的一次性旗標：遷移失敗亦不再重跑（K-60 殘留）。
- 已排程未上傳資料：**不適用**（不上傳）。
- 備份／還原：`calibration_profiles` 在預設備份範圍內（見 §6）；還原至不同裝置／
  螢幕時模型可能誤用，程式無裝置指紋檢查（K-20 殘留）。
- 保存期限：**無期限**，直到被覆蓋、清除 App 資料或解除安裝；`calibratedAt`
  只記錄、不觸發過期（全樹無讀取 `calibratedAt` 作過期判斷的程式位置，
  顯示用途見 `SettingsActivity.refreshProfileViews` 只讀 `looErrorPx`）。

**校正中暫存（須與已存檔模型分開）**：`allFeatures／allTargets／allDeviceAngles`、
`pointBuffer`、`calibrationLuxSamples／calibrationGlassesSamples` 等只存在
`CalibrationActivity` 記憶體中（`CalibrationActivity.kt:59-90`），取消／重建即丟失，
不寫入任何持久化位置（取消契約第 4 條）。`onSaveInstanceState` 只存兩個整數檢查點
（`CalibrationActivity.kt:262-263`），重建後誠實地從第 1 點重採
（`CalibrationActivity.kt:206-215` 註解）。

### 2.6 使用者設定

- 內容與位置（全部 `MODE_PRIVATE` SharedPreferences）：

| prefs 檔 | 內容 | 位置 |
|---|---|---|
| `gaze_settings` | dwell／冷卻／半徑／眨眼／濾波／靈敏度／陀螺儀／臉距／條件修正／診斷／監控／虹膜精修／邊緣手勢 5 開關 | `GazeSettings.kt:132`（`PREFS_NAME`）、`save()` `:108` 起、`load()` `:170` 起 |
| `calibration_profiles` | `active_profile`（目前情境 id） | `CalibrationStore.kt:28`；寫入見 `setActiveProfileId` `:49` 起 |
| `condition_history` | 見 §2.6 註（條件歷史屬設定／記憶，見下） | `ConditionHistory.kt:221` 起 |
| `eyecontrol_setup` | `welcome_seen`（布林） | `WelcomeActivity.kt:51-57` |
| `crash_report` | `shown_<檔名>`（布林，閃退對話框已顯示旗標） | `CrashReporter.kt:57-64` |
| `gaze_model`（舊） | 舊單一模型 JSON，僅遷移讀取、舊檔保留不刪 | `GazeModel.kt:121` 起、`CalibrationStore.migrateIfNeeded` |

- 只在裝置內處理：**是（程式層面）**。無任何設定值上傳呼叫；CSV 記入的
  `sens／offX／offY` 是當下生效的靈敏度與偏移數值，不是整份設定檔（見 §2.3／§2.4）。
- 寫入本機：**是**。`GazeSettings.save()` 不先 `clamped()`（K-61 殘留的一部分；
  `SettingsActivity.saveAndFinish` 在呼叫前 `clamped()`，`:285` 起）；損毀回預設
  （`GazeSettings.load` 的 `catch`，`:177-179`），回退時兩監控開關回預設開啟（見 §0.2）。
- 送往遠端：**否**（程式不送出）。備份面見 §6：設定檔（含 `remote_monitor` 旗標）
  可能隨系統備份離開裝置並在換機時還原。
- 需同意／拒絕／撤回：設定頁「儲存」才寫入（`SettingsActivity.kt:285` 起）；
  「恢復預設值」只改畫面控制項、按儲存才生效（`resetToDefaults`，`:302` 起）。
  無設定檔匯出／刪除 UI；清除方式只有清除 App 資料／解除安裝。
- 保存期限：**無期限**；邊緣手勢等新 key 以 `getBoolean(key, true)` 相容舊檔。

**條件歷史**（`condition_history`）：每情境最多 50 筆 `records_<id>`＋基準
`reference_<id>`（`ConditionHistory.kt:36,221-222`）；內容為條件向量
（亮度桶、pitch／roll、iod、反光指標）＋偏移（`ConditionHistory.kt:73` 起）。
只在裝置內讀寫（`loadRecords／addRecord／clearHistory／loadReference／saveReference`，
`:203` 起），不上傳；`conditionCorrection` 關閉時不讀不寫（檔頭註解）。
完整重校存檔成功後才清空（`CalibrationActivity` 完成路徑；取消時不得清空，
見取消契約第 6 條）。無時間過期、無單獨刪除 UI。

### 2.7 診斷紀錄（GazeLog CSV）

- 內容：17 欄 CSV（表頭 `GazeLog.kt:31`）：
  `t,face,iod,pitch,yaw,rawX,rawY,gainX,gainY,curX,curY,sens,offX,offY,lux,imgQ,glassesHint`。
  不含影像、不含完整特徵向量、不含身分識別碼欄位；檔名為時間戳
  （`gaze-<yyyy-MM-dd_HH-mm-ss-SSS>-<流水號>.csv`，`GazeLog.kt:92-94`）。
  寫入節流 10Hz（`MIN_INTERVAL_MS=100`，`:30`）、每 50 行 flush（`:137`）。
- 只在裝置內處理：**否**。本機寫檔與遠端上傳共用同一檔案、同一開關組（見下）。
- 寫入本機：**是，條件為兩開關任一開**。服務每幀呼叫
  `GazeLog.record(enabled = diagnosticLogging || remoteMonitor, …)`
  （`GazeAccessibilityService.kt:528,633,672,1315,1365` 五處，含臉遺失／降級／
  RECOVERY 分支）。`enabled=false` 時 `record()` 關檔並返回（`GazeLog.kt:83-86`），
  不寫入。檔案在 `filesDir/gaze-logs/`（`GazeLog.kt:27,91`）。
  **注意**：只開 `remoteMonitor`、關 `diagnosticLogging` 時，檔案仍會寫入本機
  （`OR` 邏輯）；只開 `diagnosticLogging`、關 `remoteMonitor` 時，檔案寫入但
  `pendingUpload` 永不為真、關檔不上傳（見下）。
- 送往遠端：**是，有條件**。`pendingUpload` 在開新檔時重置為 false、按幀
  `OR` 累積當下 `remoteUpload`（`GazeLog.kt:101,106`）；關檔時
  `doUpload = upload && pendingUpload && file != null && rows > 0`（`:141`），
  快照整份 CSV 文字交背景單執行緒上傳（`:155-158`）。`remoteUpload` 的真值為
  `remoteUploadEnabled() = settings.remoteMonitor && BuildConfig.REMOTE_MONITOR_ENABLED`
  （`GazeAccessibilityService.kt:75-76`）。因此 Play 版永不為真（見 §3.2）。
- 何條件送出：檔案期間**任何一幀**處於監控開啟狀態，且關檔時 `upload` 參數為真
  （預設 `close(upload=true)`，`GazeLog.kt:139`）。關檔觸發點：滿 120 秒自動輪轉
  （`GazeLog.kt:110-113`，關檔後下一筆開新段、連續紀錄）、`enabled` 轉 false、
  `onDestroy`（`GazeAccessibilityService.kt:1618` 的 `GazeLog.close()`，預設上傳）、
  寫入失敗（`record` 的 `onFailure { close() }`，`GazeLog.kt:138`）。
- 需同意：**無專門同意對話框**。預設開啟（見 §0.2）；設定頁副標說明用途
  （`activity_settings.xml:758-773`），但開啟本身不彈確認（K-12／S-10 殘留）。
- 能否拒絕：設定頁關閉兩開關並按儲存；Play 版無此資料流（見 §3.2）。
- 撤回後停止：見 §4.2（**當下檔案仍會上傳**，為本盤點最重要的殘留缺口）。
- 已排程未上傳資料：無持久化上傳佇列。已提交至 `uploadExecutor` 的快照只存在
  記憶體／執行緒佇列中，行程死亡即丟失（不上傳、亦不重試）；`uploadExecutor`
  永不 shutdown（`GazeLog.kt:40`，K-10 殘留）。失敗只寫 Log、不重試、不通知
  （`uploadFile`，`:175` 起）。
- 備份／還原：`gaze-logs/` 在 `filesDir` 下，屬預設備份範圍（見 §6）。
- 保存期限與刪除：120 秒一段、自動輪轉；開新檔時只留最近 3 份
  （`MAX_FILES=3`，`GazeLog.kt:28,104`）。刪除：設定頁「診斷摘要」對話框的
  「清除紀錄」呼叫 `GazeLog.clearAll()`（`SettingsActivity.kt:358-359`），
  以 `close(upload=false)` 丟棄當下檔案並刪除全部 CSV（`GazeLog.kt:222-225`）——
  **這是唯一不以上傳舊檔為預設的關檔路徑**。`summarize()`（`:231` 起）讀最新檔
  算摘要，使用者主動點「複製摘要」才經剪貼簿外流（見 §1 補充）。

### 2.8 Crash Report（閃退紀錄）

- 內容：`crash-<時間戳>.txt`（秒級時間戳，`CrashReporter.kt:38-39`），含時間、種類、
  執行緒、App 版本、裝置廠牌／型號／Android 版本、完整 stack trace
  （`CrashReporter.kt:42-47`）。不含影像；「不含任何個人資料」的檔頭註解
  （`CrashReporter.kt:14`）須限縮：裝置型號屬裝置資訊，stack trace 可能含程式路徑，
  見 `privacy-copy-audit.md`。
- 只在裝置內處理：**是（程式層面）**。`CrashReporter` 無任何網路呼叫；
  安裝於 `EyeControlApp.onCreate()`（`EyeControlApp.kt:9-11`），未攔截例外先寫檔、
  再交回原處理器（`CrashReporter.kt:21-27`）；已攔截錯誤以 `recordCaught` 寫檔
  （`:32-34`），不結束行程。
- 寫入本機：**是**，`filesDir/crash-reports/`（`:22,37`），無事前同意（寫檔為自動）。
- 送往遠端：**否（程式不送出）**。外流只發生在使用者主動行為：
  下次開啟時對話框顯示前 4000 字、可「複製紀錄」經剪貼簿外流、可截圖
  （`MainActivity.kt:248-268`）；「清除紀錄」呼叫 `CrashReporter.clearAll`（`:267`）。
- 需同意／拒絕：無開關可關閉閃退寫檔；拒絕外流的方式是不點複製／不截圖。
- 撤回／刪除：`clearAll()` 刪檔（`CrashReporter.kt:66-68`），但**不清 `shown_*` 旗標**
  （K-62 殘留）；檔名秒級，同秒兩次崩潰後者覆寫前者（K-62 殘留）。
- 備份／還原：`crash-reports/` 在預設備份範圍內（見 §6）。
- 保存期限：只留最近 5 份（`MAX_FILES=5`，`:23,49`），無時間期限。

### 2.9 更新檢查資訊（GitHub API 請求）

- 內容：`GET https://api.github.com/repos/shengwen0928/eyecontrol/releases/latest`
  （`UpdateChecker.kt:47`），`Accept: application/vnd.github+json`、
  `User-Agent: eyecontrol-updater`，connect／read timeout 各 5 秒（`:48-52`）。
  請求本體無自訂欄位、無使用者識別碼、無裝置資訊欄位；回應只讀 `tag_name` 與
  `assets[].browser_download_url`（`:55-63`）。
- 只在裝置內／寫入本機：請求本身不寫檔；版本比對在記憶體內（`isNewer`，`:72` 起）。
- 送往遠端：**是，側載版**。觸發點：`MainActivity.onCreate` 僅全新啟動時
  （`savedInstanceState == null`，`MainActivity.kt:240`），背景 daemon 執行緒
  （`UpdateChecker.kt:40`）；失敗靜默返回（`:41` 的 `runCatching … ?: return`），
  無重試、無使用者可見提示（K-59 殘留）。
- 需同意／能否拒絕：**無 App 內開關可關閉**（`SettingsActivity.kt:29` 註解明載
  「更新檢查開關一律不顯示」）；拒絕的唯一方式是不用側載版（改用 Play 版）、
  或斷網。Play 版以 `BuildConfig.SELF_UPDATE_ENABLED=false` 在 `MainActivity.checkForUpdate`
  直接返回（`MainActivity.kt:301`），不發請求。
- 撤回後停止：**不適用**（無開關）；每次全新啟動都會再查。
- 已排程未上傳資料：不適用（請求即發即棄，無佇列）。
- 備份／保存／刪除：不適用（無本機留存）。網路層可見來源 IP／時間／User-Agent；
  GitHub 伺服端紀錄內容**待定位**（無法由本樹驗證）。
- 完整性缺口（非資料流本身，但同一次請求的信任邊界）：無 checksum／簽名驗證
  （K-13／S-08），見 §2.11。

### 2.10 Supabase 請求

- 端點與方式：`SUPABASE_URL` 常數（`GazeLog.kt:39`，值不轉錄）＋
  `POST /rest/v1/gaze_logs`（`GazeLog.kt:179`），`HttpURLConnection`，timeout 各 10 秒，
  `Content-Type: application/json`、`apikey` 標頭、`Authorization: Bearer` 標頭、
  `Prefer: return=minimal`（`:180-188`）。金鑰為 `SUPABASE_ANON_KEY` 常數
  （`GazeLog.kt:37-38`，值不轉錄）；註解稱 RLS 只允許 INSERT、讀取只用
  service_role 金鑰且該金鑰絕不進 App（`:33-37`）。**RLS 主張無法由本樹驗證，
  待定位**（須以 Supabase 控制台驗證，見 §7）。
- 負載兩種（同一張表、同一端點、同一執行緒）：
  1. CSV：JSON `{"id": <檔名去副檔名>, "rows": <筆數>, "csv": <整份 CSV>}`
     （`GazeLog.kt:178` 的 `uploadFile` 本體）。
  2. 影格：`rows=0`、`csv` 欄位放影格 JSON（含 `jpeg_b64`、meta 欄位），
     `id` 前綴 `frame-`（`VerifyFrames.kt:190,259-276` 的 `buildFrameJson`）。
- 只在裝置內／寫入本機：送出前快照在記憶體（CSV 快照見 `GazeLog.kt:153-158`）；
  本機留存見 §2.1／§2.7（影格與 CSV 檔案本身）。
- 送往遠端條件：CSV 見 §2.7；影格見 §2.1（背景執行緒當下的 `uploadEnabled`）。
  兩者皆須 `BuildConfig.REMOTE_MONITOR_ENABLED=true` 經 `remoteUploadEnabled()`／
  `applyVerifyCaptureFlags()` 層層傳入才為真（見 §3.2）；`uploadFile` 本身無
  flavor 守衛（見 §3.2）。
- 需同意／拒絕／撤回：與 §2.7／§2.1 共用開關，無專門同意對話框；已上傳資料
  無 App 內刪除、無 DELETE 呼叫；伺服端保留期限與刪除流程**待定位**。
- 識別性：JSON 無使用者 ID、無裝置 ID 欄位；但 `id` 含時間戳檔名、伺服端接收
  時間與來源 IP 可關聯多次上傳至同一裝置／使用者（關聯性未在現有文案中揭露，
  見 `privacy-copy-audit.md`）。是否構成「身分資訊」屬法律／政策判斷，
  本文件只記錄欄位事實，不代為定性。
- 失敗語義：非 2xx 只寫 `Log.w`、例外只寫 `Log.w`，不重試、不通知使用者
  （`GazeLog.kt:189-193`）。

### 2.11 GitHub Release 更新請求（APK 下載＋商店意圖）

- APK 下載：使用者在更新對話框點「下載更新」後，`UpdateChecker.startDownload`
  （`UpdateChecker.kt:103` 起）以 `DownloadManager` 下載 `browser_download_url`，
  目的地 `getExternalFilesDir(DIRECTORY_DOWNLOADS)/eyecontrol-debug.apk`
  （`:108-111`），通知可見 `VISIBILITY_VISIBLE_NOTIFY_COMPLETED`。
  完成後由 `MainActivity` 的 `downloadReceiver`（`RECEIVER_NOT_EXPORTED`，
  `MainActivity.kt:234-236`）接手，僅查 `STATUS_SUCCESSFUL`＋位元組數
  （`MainActivity.kt:54-89` 段，K-13 殘留：無雜湊驗證），再經 FileProvider
  跳安裝畫面（`UpdateChecker.kt:124` 起的 `installApk`）。
- 商店意圖：Play 版設定頁「到 Play 商店檢查更新」列點選後，
  `UpdateChecker.openStoreListing`（`UpdateChecker.kt:89` 起）先 `market://`、
  失敗退回網頁版；只送出 package name。Play 版不宣告 INTERNET，
  商店／瀏覽器 App 自行連網。
- 只在裝置內／寫入本機：下載的 APK 寫入 App 私有外部目錄（見上），
  **安裝成功後不刪除**（僅下載失敗刪殘檔，K-57／S-14 殘留）；隨 App 解除安裝刪除。
- 送往遠端：下載請求的 metadata（URL、IP、時間）可見於 GitHub／CDN 側；
  伺服端紀錄**待定位**。
- 需同意：下載與安裝皆須使用者親點（對話框＋系統安裝畫面）；無靜默更新。
- 撤回／刪除：下載後不安裝即不生效；APK 檔無 UI 刪除按鈕（下次下載覆寫同一檔名）。
- 保存期限：單一固定檔名檔案，無版本區分、無期限。

## 3. Sideload／Play 兩版本資料流差異

依據：`app/build.gradle.kts` 的 `productFlavors`（sideload：`SELF_UPDATE_ENABLED=true`、
`REMOTE_MONITOR_ENABLED=true`；play：兩者皆 `false`）與
`buildConfig = true`（五個不能動之一，見 `PLAN.md` §0.2）。

| 資料流 | Sideload | Play | 閘門位置 |
|---|---|---|---|
| 更新檢查（§2.9） | 發請求 | 不發請求 | `MainActivity.kt:301`（`SELF_UPDATE_ENABLED`） |
| APK 下載／安裝（§2.11） | App 內下載＋FileProvider 安裝 | 不走此路；改跳 Play 商店 | 同上＋`UpdateChecker.openStoreListing`；Manifest 疊加層移除 `REQUEST_INSTALL_PACKAGES` |
| Supabase 上傳（§2.10） | 可能上傳（預設開啟） | 不上傳（見但書） | `remoteUploadEnabled()`（`GazeAccessibilityService.kt:75-76`）＋`applyVerifyCaptureFlags`（`:79-83`） |
| 眼區裁切上傳（§2.1） | 可能上傳 | 不上傳（見但書） | 同上，經 `setVerifyCapture(local, upload)`（`FaceTrackerEngine.kt:243` 起） |
| 診斷 CSV 本機寫檔（§2.7） | 寫檔（預設開啟） | **仍寫檔** | `record(enabled = diagnosticLogging \|\| remoteMonitor)` 不含 flavor 條件；Play 版 `diagnosticLogging` 開關列仍顯示、仍可儲存為 true，CSV 照寫、只不上傳 |
| 驗證影格本機留存（§2.1） | 留存（預設開啟） | **仍留存** | `setVerifyCapture(local = settings.diagnosticLogging, …)` 不含 flavor 條件；Play 版影格只寫本機、不上傳 |
| 遠端監控開關列 | 顯示 | 整列隱藏（`View.GONE`） | `SettingsActivity.kt:76-77`（`REMOTE_MONITOR_ENABLED`） |
| INTERNET／安裝權限 | 宣告 | 疊加層 `tools:node="remove"` 移除 | `app/src/play/AndroidManifest.xml:5-13` |
| 設定值殘留 | — | `switchRemote` 隱藏但值仍可經 `save()` 寫入（K-61／K-45 殘留的一部分） | `SettingsActivity.kt:290`（`saveAndFinish` 無 flavor 分支） |

**但書（Play 版「不上傳」的真實防護層級）**：

1. `uploadFile` 本身**無** `BuildConfig.REMOTE_MONITOR_ENABLED` 守衛
   （`GazeLog.kt:175` 起只檢查 `SUPABASE_URL.isBlank()`）。Play 版不上傳，
   完全依賴呼叫端永不以真值傳入 `remoteUpload`／`uploadEnabled`。
   若 prefs 殘留 `remote_monitor=true`（例如跨 flavor 覆蓋安裝——但兩 flavor
   簽名不同，覆蓋安裝須先刪除，殘留情境有限，見舊稽核 §1.2），`record` 仍會
   嘗試上傳並在無 INTERNET 權限下失敗、只寫 Log。K-45／S-12 仍未修復。
2. Play 版的「平時不連網」只涵蓋 App 自行發起的請求；使用者主動點
   「到 Play 商店檢查更新」列時，Play 商店／瀏覽器會連網（見 §2.11），
   那是使用者主動觸發的外部 App 行為，不是本 App 的網路請求。
3. Play 版仍會**在本機**寫入診斷 CSV 與驗證影格（見上表）。任何稱 Play 版
   「不上傳任何診斷資料」的文案，在「上傳」二字上屬實，在「不記錄」上不屬實；
   文案須區分「記錄」與「上傳」（見 `privacy-copy-audit.md`）。

## 4. 同意撤回機制檢查

### 4.1 搜尋結果：不存在的機制（不得假裝有）

全樹 grep（`WorkManager`／`workManager`／`consent`／`Consent`／`revoke`／
「撤回」／「同意」作為程式識別符）：

- `WorkManager`：**零命中**。無背景排程上傳、無延遲上傳工作、無上傳佇列持久化。
- `consent`／`Consent`／`revoke`：**零命中**（程式識別符層面）。「同意」只存在於
  文案與註解中，不存在同意紀錄、同意版本、同意時間戳等任何持久化欄位。
- `generation`：只存在於兩處無關機制——校正交接世代（`CalibrationActivity.kt:107` 的
  `handoverGeneration`）與幀槽世代（`FaceTrackerEngine.kt:65` 的 `FrameSlot.generation`），
  **皆不是**同意世代／撤回世代。相機綁定亦無 generation 守衛（見 PHASE 6 文件）。
- `pendingUpload`：只存在於 `GazeLog.kt:51,101,106,141,148`，是「本檔案期間是否曾開啟
  監控」的記憶體旗標，**不是**持久化上傳佇列，也不是撤回旗標；行程死亡即丟失。

### 4.2 撤回的真實語義（CSV 路徑，K-11 殘留）

撤回操作＝設定頁關閉 `remoteMonitor`（或兩開關皆關）並按「儲存」。
實際發生的事，按時間序：

1. `saveAndFinish()` 寫入 prefs（`SettingsActivity.kt:285` 起）；服務的
   `settingsListener` 重載設定並呼叫 `applyVerifyCaptureFlags()`
   （`GazeAccessibilityService.kt:65-69,79-83`）。**該 listener 不呼叫
   `GazeLog.close()`、不重置 `pendingUpload`**。
2. 下一幀 `GazeLog.record()` 以新設定計算 `enabled`：
   - 兩開關皆關 → `enabled=false` → `close()`（預設 `upload=true`，
     `GazeLog.kt:83-86,139`）。若當下檔案的 `pendingUpload` 為真（檔案期間
     曾有任何一幀監控開著），**整份檔案照常上傳**。關閉開關只停止「新錄製」，
     不阻止「當下檔案的上傳」。
   - 只關 `remoteMonitor`、留 `diagnosticLogging` 開 → `enabled` 仍為真，
     檔案繼續寫入；`pendingUpload` 保持 OR 累積的真值，後續關檔（滿 120 秒、
     `onDestroy` 等）時**一併上傳**，含關閉後寫入的幀。
   - 只關 `diagnosticLogging`、留 `remoteMonitor` 開 → 同上，檔案繼續寫入並上傳。
3. 唯一不以上傳舊檔為預設的路徑：設定頁「診斷摘要」→「清除紀錄」→
   `GazeLog.clearAll()`（`close(upload=false)`＋刪檔，`GazeLog.kt:222-225`）。
   但該路徑同時刪除全部本機 CSV（含尚未上傳的診斷資料），且**不**刪除已上傳的
   伺服端資料、不刪除驗證影格檔案。
4. 服務銷毀（`onDestroy`）的 `GazeLog.close()`（`GazeAccessibilityService.kt:1618`）
   同樣預設上傳：使用者在服務運作中關閉監控、隨後停用服務，當下檔案仍會上傳。

**結論**：「關閉開關即停止上傳」在 CSV 路徑上**不成立**。正確的現況敘述只能是
「關閉後不再開始新的上傳檔案，但當下檔案仍會在關檔時上傳；要丟棄當下檔案
須用『清除紀錄』」。文案改寫要件見 `privacy-copy-audit.md`；程式修正不在本批次範圍。

### 4.3 撤回的真實語義（驗證影格路徑，較嚴格）

1. `setVerifyCapture(local, upload)`（`FaceTrackerEngine.kt:243` 起）：兩者皆由開轉關時
   `verifyFrames.endSession()`（清庫存、刪舊時段目錄至 3 個上限，不刪當下時段已寫入的檔案）。
2. `storeAndUpload()`（`VerifyFrames.kt:172` 起）在背景執行緒執行時**重新檢查**
   當下的 `localEnabled`／`uploadEnabled`：關開關後排隊中、尚未執行到檢查的影格
   不會寫入／不會上傳；已執行完檢查並已呼叫 `GazeLog.uploadFrame()` 的影格，
   其上傳任務已提交至 `uploadExecutor`，關開關無法撤回。
3. 已寫入 `verify-frames/` 的檔案不受開關影響（無刪除路徑，見 §2.1）。
4. 臉遺失庫存（`lastGoodStash`）在 `endSession()` 時清為 null
   （`VerifyFrames.kt:136-144`），關閉兩開關後不再以舊裁切補發臉遺失證據。

兩路徑對照：影格路徑的「關閉後排隊中影格不上傳」比 CSV 路徑嚴格，
但兩者皆不刪除已寫入檔案、皆不刪除已上傳資料。文案不得以影格路徑的語義
概括 CSV 路徑。

### 4.4 同意品質缺口清單

| # | 缺口 | 證據 | 影響 |
|---|---|---|---|
| G-1 | 首次開啟監控無確認對話框 | `SettingsActivity` 全檔無監控確認對話框；開關切換＋儲存即生效 | 使用者可能未意識到「開啟即開始錄製＋關檔即上傳整份 CSV」（S-10 殘留） |
| G-2 | 隱私對話框未提及監控 | `SettingsActivity.kt:154-171` 的對話框只談影像＋更新檢查 | 授權前／設定內的揭露面缺監控例外（見 copy-audit） |
| G-3 | 無同意紀錄／版本／時間戳 | 全樹無 consent 持久化（見 §4.1） | 無法證明使用者何時、以何版本文案同意；政策變更時無法重徵同意 |
| G-4 | 撤回不丟棄當下檔案（CSV） | 見 §4.2 | 撤回語義與使用者直覺相反（K-11） |
| G-5 | 已上傳資料無刪除路徑 | 全樹無 DELETE／刪除 API 呼叫；`GazeLog` 只有 `uploadFile`（POST） | 撤回後伺服端資料仍在，刪除須開發者手動處理（流程待定位） |
| G-6 | 預設開啟＋無首次告知 | 見 §0.2；引導 NOTICE 段雖有例外說明，但使用者可跳過引導（`SetupWizardActivity` 跳過機制） | 未看引導的使用者可能在不知情下已上傳 |
| G-7 | 無上傳佇列可檢視／取消 | 無 WorkManager、無持久化佇列（見 §4.1） | 使用者無法查看「待上傳」內容、無法逐筆取消 |

## 5. 絕對化敘述殘餘三處（親自定位，逐字引用）

以下三處為 `IMPLEMENTATION_NOTES.md` §2 未決點指明的殘餘三處，本文件親自定位並逐字引用。
完整掃描（含其他類似表述）見 `privacy-copy-audit.md`；此處只列三處的資料流定性。

1. **SettingsActivity 隱私對話框**（`SettingsActivity.kt:166-167`，側載／Play 共用首句）：
   首句稱影像與校正資料的絕對敘述，更新檢查段稱「唯一會連網」。
   定性：側載版失實（Supabase 上傳為第二種連網，且預設開啟）；Play 版首句亦失實
   （Play 版仍在本機寫入診斷 CSV 與影格，「不儲存」不成立；「不上傳」在 Play 版成立，
   但首句未分 flavor）。
2. **主畫面頁尾**（`activity_main.xml:760`）：稱影像絕對敘述＋「除此之外不連網」。
   定性：兩 flavor 皆失實——側載版有 Supabase 上傳；Play 版無更新檢查連線，
   該句的「會連線查詢新版本」前提即不成立。
3. **`strings.xml` 的 `accessibility_desc`**（`app/src/main/res/values/strings.xml:3`，
   系統設定頁授權時可見）：稱影像絕對敘述＋「不讀取、不記錄其他應用程式的內容」。
   定性：後半句程式行為屬實（見 §1 補充），前半句失實（同第 1 處）；且此字串為
   使用者授權無障礙服務時的直接依據，誤導性最直接，優先修正級別最高。

三處皆**未在本批次改寫**（紅線：不改任何現有檔案與文案）；改寫要件見
`privacy-copy-audit.md`，且改寫前須先以本文件確認資料流（已完成）。

## 6. 備份／系統自動還原行為

- 宣告：`AndroidManifest.xml:16`，`android:allowBackup="true"`。
- 排除規則：**無**。全樹無 `android:fullBackupContent`、`android:dataExtractionRules`、
  無 `res/xml/backup_rules.xml`／`data_extraction_rules.xml`（`app/src/main/res/xml/` 僅有
  `accessibility_service_config.xml` 與 `file_paths.xml`）。
- 依 Android 預設備份行為（平台行為，**實際備份內容待實機／裝置驗證**，
  本樹程式碼無法證實哪些檔案實際被備份服務取走）：
  以下 App 私有資料原則上在備份範圍內：6 份 SharedPreferences
  （含校正模型、條件歷史、設定、`welcome_seen`、`shown_*` 旗標）、
  `filesDir/` 下的 `gaze-logs/`、`verify-frames/`、`crash-reports/`。
  `getExternalFilesDir()` 下的 APK 不在自動備份範圍內（外部私有目錄語義不同，
  屬平台行為，待驗證）。
- 後果（以程式碼＋平台預設行為推演，未經實機證實者已標明）：
  1. 模型權重、條件歷史、設定（含 `remote_monitor=true` 旗標）可能在換機／重裝時
     還原；還原至不同裝置／螢幕時模型可能誤用，程式無裝置指紋檢查。
  2. 診斷 CSV、驗證影格、閃退報告可能隨備份離開裝置、上傳至使用者 Google 帳號備份，
     與「資料只留在這支手機」「不上傳」等字句直接衝突（K-20）。
  3. 備份還原可能使「已關閉監控」的使用者在新裝置上回到「監控開啟」（若備份時旗標為真），
     或反之；`getBoolean(key, true)` 的預設只在「從未存過 key」時生效，
     還原後的 prefs 有值即不走預設。
- 修正方向（**本文件只記錄，不代決**）：(a) 宣告備份排除規則，至少排除
  `gaze-logs/`、`verify-frames/`、`crash-reports/` 與 `condition_history`；
  (b) 或將 `allowBackup` 改為 `false` 並在文件中說明重裝須重校。
  至少須在隱私文件中如實揭露備份行為（見 copy-audit）。

## 7. 待定位／待驗證清單（找不到來源，不編造）

| # | 項目 | 為何待定位 |
|---|---|---|
| T-1 | Supabase RLS 是否確為「僅 INSERT、限 `gaze_logs` 表、無 SELECT／UPDATE／DELETE」 | RLS 在伺服端，本樹只有註解主張（`GazeLog.kt:33-37`）；須登入 Supabase 控制台驗證並留存紀錄（舊稽核 N-01／N-04 同旨） |
| T-2 | Supabase 伺服端資料保留期限、刪除流程、存取紀錄、專案刪除狀態 | 本樹無任何伺服端設定檔與刪除呼叫 |
| T-3 | GitHub API／Release 下載的伺服端紀錄內容（IP、時間以外是否記錄其他） | 無法由客戶端程式碼證實伺服端行為 |
| T-4 | 系統自動備份實際取走的檔案清單、還原行為 | 須實機／裝置驗證；本樹只有 `allowBackup` 宣告 |
| T-5 | 裝置重啟／系統重建服務後，備份還原是否先於 `onServiceConnected` 生效 | 平台行為，須實機驗證；見 `restart-recovery-policy.md` |
| T-6 | `docs/privacy.html` 稱「關閉開關即停止上傳／留存」在伺服端是否有對應的停止機制 | 程式端已證偽（見 §4.2）；伺服端無對應機制可查 |
| T-7 | 上傳失敗／行程死亡時，未上傳檔案是否會在下次啟動補傳 | 程式碼證實**不會**補傳（無佇列、無重試）；但「檔案仍在本機、可被日後手動複製」屬使用者行為，非程式補傳 |

## 8. 結論（資料流確認後的定性）

1. **全臉影像**：只在裝置內即時處理、不儲存、不上傳——**屬實**，可繼續如此陳述，
   但必須與眼區裁切分開陳述，不得以「影像」一詞概括兩者。
2. **眼區裁切與診斷數字**：側載版預設開啟、會留存並上傳——**現況事實**；
   任何無條件的「不儲存、不上傳」「除此之外不連網」「唯一會連網」敘述，
   在側載版皆失實，在 Play 版亦須區分「記錄」與「上傳」。
3. **撤回語義**：CSV 路徑的撤回不等於「當下檔案不上傳」；影格路徑較嚴格但
   亦不刪除已寫入檔案。文案改寫必須以此為準，不得沿用「關閉即停止上傳／留存」。
4. **Play 版**：不上傳的防護只靠呼叫端閘門，`uploadFile` 本身無 flavor 守衛；
   且 Play 版仍在本機記錄診斷資料。文案與程式皆須補顯式守衛／區分敘述，
   程式修正不在本批次範圍，列為後續缺口。
5. **備份**：`allowBackup=true` 且無排除規則，使「只留在這支手機」的敘述在備份面
   不成立；揭露或排除規則二選一，待產品決定，本文件不代決。

---

*複用來源：`~/workspace/eyecontrol-master-audit/docs/audit/KNOWN_ISSUES.md`（K-11／K-12／K-20／
K-30／K-45）、`~/workspace/eyecontrol-master-audit/docs/audit/SECURITY_AND_PRIVACY_AUDIT.md`
（§8／§10／S-03／S-04／S-12／S-17）、`~/workspace/eyecontrol-phase3/PLAN.md`、
`~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md`、
`~/workspace/eyecontrol-phase3/CANCEL_CONTRACT.md`。*
