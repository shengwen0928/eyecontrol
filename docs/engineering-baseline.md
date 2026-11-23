# EyeControl 工程基準（PHASE 0）

> 性質：PHASE 0 基準文件。只讀程式碼、只新增本文件，**未修改任何既有檔案**。
> 基準樹：`~/workspace/eyecontrol-p3`（repo 複本，**無 `.git/`**，見 §1.3）。
> 對照基準：遠端 `shengwen0928/eyecontrol` 分支 `main`、commit `c6b80964`（全稱見 §1.1）。
> 行號引用：除特別註明「遠端（v0.5.9）」者外，一律為**目前工作樹**的行號。
> 狀態詞彙：本文件只用「已確認（有行號／SHA 證據）」「待確認」「待定位」「未驗證」；
> 「已閱讀 ≠ 已提案 ≠ 已改碼 ≠ 靜態通過 ≠ 單元測試通過 ≠ CI 通過 ≠ 實機通過 ≠ 發布通過」。
> 找不到來源的問題標「待定位」，不得編造檔名或函式——本文件無此類編造，凡查無來源處皆明寫。
> 密鑰：本文件只記錄簽名／金鑰**機制**，絕不記錄任何密鑰值（見 §7.4）。

## 0. 摘要

- 原始版本：遠端 `main` HEAD `c6b809640de5bc0a83cd0b96c70a55395de1c784`，commit 訊息稱 v0.5.9
  （2026-11-22T14:09:29Z，作者 Su ShengWen）。`v0.5.9` 標籤本身**未在本任務驗證**
  （唯讀工具集不含可用的標籤查詢，見 §1.2），不得宣稱標籤已核實。
- 目前版本：工作樹程式碼為 v0.5.9 加上**未推送的 Phase 3 實作**；版本號未動
  （`versionCode = 13`、`versionName = "0.5.9"`，`app/build.gradle.kts:29-30`）。
- 逐檔 blob SHA 比對（親自執行，方法見 §2）：遠端 65 檔；工作樹 77 檔；
  **相同 51 檔、異動 14 檔、新增 12 檔、缺失 0 檔**。
  先前回報「14 檔異動、10 檔新增」：異動數一致；新增數不一致——
  10 檔為 Phase 3 程式／測試新增，另有 2 份文件（`docs/ci-validation-report.md`、
  `docs/device-acceptance-matrix.md`，寫入時間 2026-11-22 23:18–23:19 UTC）
  不在先前回報的 10 檔之內，且其中一份在本任務第一次雜湊掃描時仍在寫入中
  （第一次掃描 76 檔、第二次掃描 77 檔）。該 2 份文件非 Phase 3 實作檔案，
  作者與用途**待定位**，本任務未讀其內容、未改動，提請協調員確認歸屬。
- 核心架構不變：CameraX → MediaPipe Face Landmarker → 22 維特徵 → 嶺回歸
  `GazeModel` → `OneEuroFilter` → `GazeAccessibilityService` 注入手勢；
  三個相機消費者（服務、校正頁、訓練頁）共用 `FaceTrackerEngine` 與同一交接協議。
- Phase 3 已改碼的部分（靜態證據）：具名流程狀態機、RECOVERY_MODE、邊緣手勢控制器、
  校正頁報告／詢問／ERROR 頁、引導順序對調、服務啟動閘門、邊緣開關。
  **一律未經本任務驗證**：未執行編譯、未執行測試、未實機驗證、未走 CI。
  先前協調員重跑的 harness 結果（110 項全過）見 `~/workspace/eyecontrol-phase3/TEST_RESULTS.md`，
  本任務引用該記載、不代為背書（見 §8）。
- 五個不能動維持現況（見 §7.1），全部以行號驗證；`D1-9` 僅常數預留、零引用（見 §6.5）；
  PLAN D3-7 的 dispatch 部分（`onCancelled`、雙擊第二下取消）**未做**（見 §6.6）。

## 1. 版本與來源

### 1.1 遠端 HEAD（已確認）

| 項目 | 值 | 證據 |
|---|---|---|
| repo | `shengwen0928/eyecontrol` | 任務書指定；GitHub 唯讀查詢的 owner／repo 參數 |
| 分支 | `main` | `list_commits` 以 `sha=main` 查詢，第一筆即下述 commit |
| HEAD commit | `c6b809640de5bc0a83cd0b96c70a55395de1c784` | `list_commits`（`sha=main`，`perPage=3`）第一筆 `sha` |
| commit 訊息 | `v0.5.9: adaptive dwell, image-assisted tracking, diagnostics/monitoring on by default, verification frames, continuous log rotation (includes v0.5.8)` | 同上第一筆 `commit.message` |
| commit 時間 | `2026-11-22T14:09:29Z`（作者與提交者皆 Su ShengWen） | 同上第一筆 `commit.author.date`／`committer.date` |
| 前一 commit | `d40d4cc5df02f2def68f2fd3cca3357e42f0fb6c`（v0.5.7） | 同上第二筆 |

### 1.2 限制與未驗證項（版本部分）

- `v0.5.9` **標籤**是否存在、指向哪個 commit：未驗證。`list_tags` 不在唯讀工具集內，
  本任務未使用需審批的寫入路徑去查詢，不得宣稱標籤已核實。
  「c6b80964 即 v0.5.9 程式碼」是任務書與 `PLAN.md` 的說法，本任務僅確認該 commit
  的訊息自稱 v0.5.9、且本地 `versionName` 為 `0.5.9`。
- 本地 git 歷史：**無法讀取**。工作樹無 `.git/`（`test -d` 已確認不存在），
  不得宣稱讀過本地 git 歷史；歷史資訊一律來自上述遠端唯讀查詢。
- 遠端在 c6b80964 之後是否有新 commit：本任務只查了 `main` 前 3 筆（查詢時點
  2026-11-23 07:2x CST）；之後的遠端變動**待定位**，基準快照以查詢時點為準。

### 1.3 工作樹狀態（已確認）

- 路徑 `~/workspace/eyecontrol-p3`；無 `.git/`；無 `gradlew`／`gradlew.bat`
  （全樹 `find` 只見 `gradle/wrapper/gradle-wrapper.jar`＋`gradle-wrapper.properties`）。
- 無 `local.properties`（`find` 未見；CI 亦在建置前 `rm -f local.properties`）。
- 檔案總數 77（含本任務撰寫時已存在的 2 份文件，見 §0；不含本文件與任務矩陣文件——
  兩份新文件寫入後樹內文件數為 79，SHA 快照以寫入前為準，見 §2.4）。

## 2. v0.5.9 差異：逐檔 blob SHA 比對（親自執行）

### 2.1 方法（可重現）

1. 遠端：`/opt/hatch/bin/github call-read-tool --name get_file_contents`
   （owner `shengwen0928`、repo `eyecontrol`、每層目錄 `path`、`sha=c6b80964`），
   遞迴走訪全部目錄，取得每檔的 Git blob SHA（共 65 檔）。目錄查詢遇 MCP 逾時時
   重試 5 次並以本機快取續跑；最終 65 檔皆取回，無遺漏目錄。
2. 本地：對工作樹每檔以 `sha1("blob <len>\0" + 內容)` 計算 blob SHA
   （位元組級，含二進位檔 `face_landmarker.task` 與 `gradle-wrapper.jar`）。
3. 以路徑為鍵比對：SHA 相同＝相同；遠端有、本地 SHA 不同＝異動；本地有、遠端無＝新增；
   遠端有、本地無＝缺失。
4. 另取回 14 檔異動檔的遠端全文，與本地做 `diff`，差異摘要見 §2.3（逐行差異本身未附，
   遠端全文快照存於本機 `/tmp/remote_content/`，為臨時檔，不得視為正式交付物）。

### 2.2 結果總表

| 類別 | 數量 | 說明 |
|---|---|---|
| 遠端檔案 | 65 | `get_file_contents` 遞迴列出 |
| 工作樹檔案（快照時點） | 77 | 第二次雜湊掃描；第一次掃描為 76（見 §0） |
| 相同 | 51 | SHA 完全一致 |
| 異動 | **14** | 清單見 §2.3，與先前回報一致 |
| 新增 | **12** | 清單見 §2.3；先前回報 10 檔，差 2 份文件（見 §0） |
| 缺失 | **0** | 無遠端檔案在工作樹遺失 |

### 2.3 異動／新增清單（附 SHA 前 7 碼；完整 SHA 見附錄 A）

**異動 14 檔**（遠端 → 本地）：

| 檔案 | 差異摘要（`diff` 實測，遠端行數 → 本地行數） |
|---|---|
| `app/build.gradle.kts` | 唯一差異：`dependencies` 末新增 `testImplementation("junit:junit:4.13.2")` 一行（本地 :135）。版本號、flavor、`buildConfig`、`noCompress` 皆未動。 |
| `app/src/main/java/com/example/eyecontrol/CalibrationActivity.kt` | 427 行 → 1125 行。新增 `Phase` 列舉與報告／詢問／ERROR 階段、交接逾時、單點逾時詢問、選擇注視判定、檢查點；`finished` 旗標移除。九點資料流常數未動（見 §7.1）。 |
| `app/src/main/java/com/example/eyecontrol/CursorOverlayView.kt` | 140 行 → 307 行。新增重啟目標（`updateRecoveryTarget`／`hideRecoveryTarget`，本地 :195-215）與確認目標（`updateConfirmTarget`／`hideConfirmTarget`，本地 :217-232）繪製；既有提醒目標本體保留。 |
| `app/src/main/java/com/example/eyecontrol/GazeAccessibilityService.kt` | 1276 行 → 1982 行。新增流程狀態機接線、RECOVERY_MODE、邊緣控制器接線、`stopCompletely`；刪除內嵌 `edgeSize = 50f` 判定（遠端行，見 §6.6）；dispatch 本體（`perform*`）結構保留、`onCancelled` 仍無（見 §6.6）。 |
| `app/src/main/java/com/example/eyecontrol/GazeSettings.kt` | 僅新增邊緣手勢 5 個開關欄位＋key＋`load`／`save`（本地 :62-74、:118-122、:175-179、:210-216）。其餘欄位與預設值未動。 |
| `app/src/main/java/com/example/eyecontrol/MainActivity.kt` | 478 行 → 583 行。新增流程態文字、閘門診斷、停止對話框三鈕；**`requestCamera` 權限請求 launcher 被移除**（遠端 :92、:116；本地僅 `hasCameraPermission()` 檢查＋Toast，本地 :107、:120、:156、:318-320）——意圖未見於方案文件，列為待確認（見 §6.4 D1-5 備註與 §8）。 |
| `app/src/main/java/com/example/eyecontrol/SettingsActivity.kt` | 366 行 → 409 行。僅新增邊緣手勢開關列接線（`refreshEdgeGestureRows`，本地 :213-225；開關讀寫 :80-101、:295-300）。 |
| `app/src/main/java/com/example/eyecontrol/SetupWizardActivity.kt` | 300 行 → 457 行。步驟對調（`STEP_CALIBRATION = 3`、`STEP_SERVICE = 4`，本地 :445-446；遠端為服務 3、校正 4）；新增跳過狀態、完成彙總、`btnWizardReguide`；`isServiceEnabled()` 改精確比對（本地 :423-435）。 |
| `app/src/main/java/com/example/eyecontrol/VerifyFrames.kt` | 僅新增 2 個擷取種類常數：`CAPTURE_EDGE_CONFIRM_SHOW`、`CAPTURE_EDGE_CONFIRM_CANCELLED`（本地 :326、:329）。本體與上傳語義未動。 |
| `app/src/main/java/com/example/eyecontrol/WelcomeActivity.kt` | 僅文字：檔頭註解改寫＋側載版依 flavor 改寫兩段隱私文字（本地 :22-34）。流程與跳轉未動。 |
| `app/src/main/res/layout/activity_main.xml` | 刪除 `btnCamera`／`btnAccessibility` 兩個 `gone` 按鈕（全樹 grep 零命中，見 §6.4）。其餘 layout 未比對逐行。 |
| `app/src/main/res/layout/activity_settings.xml` | 新增邊緣手勢區塊（`rowEdge*`／`switchEdge*`，共 10 個 id）。 |
| `app/src/main/res/layout/activity_setup_wizard.xml` | 新增 `btnWizardReguide`（「重新指引」）。 |
| `app/src/main/res/layout/activity_welcome.xml` | 新增 `welcomeCameraNote`／`welcomePrivacyNote` 兩個文字元件 id（供側載版文字改寫）。 |

**新增 12 檔**：

| 檔案 | 性質 |
|---|---|
| `app/src/main/java/com/example/eyecontrol/AppFlowState.kt` | 新增：流程狀態機（D1-1），226 行 |
| `app/src/main/java/com/example/eyecontrol/EdgeGestureController.kt` | 新增：邊緣手勢控制器（D3），676 行 |
| `app/src/main/java/com/example/eyecontrol/RecoveryGate.kt` | 新增：重啟驗證純判斷（D2-3），56 行 |
| `app/src/test/java/com/example/eyecontrol/AppFlowStateTest.kt` | 新增測試，`@Test` 29 項（靜態計數） |
| `app/src/test/java/com/example/eyecontrol/ConditionHistoryTest.kt` | 新增測試，`@Test` 22 項 |
| `app/src/test/java/com/example/eyecontrol/DwellTrackerTest.kt` | 新增測試，`@Test` 11 項 |
| `app/src/test/java/com/example/eyecontrol/EdgeGestureControllerTest.kt` | 新增測試，`@Test` 25 項 |
| `app/src/test/java/com/example/eyecontrol/GazeModelMathTest.kt` | 新增測試，`@Test` 10 項 |
| `app/src/test/java/com/example/eyecontrol/OneEuroFilterTest.kt` | 新增測試，`@Test` 7 項 |
| `app/src/test/java/com/example/eyecontrol/RecoveryGateTest.kt` | 新增測試，`@Test` 6 項 |
| `docs/ci-validation-report.md` | 新增文件，**非 Phase 3 實作檔案，歸屬待定位**（見 §0） |
| `docs/device-acceptance-matrix.md` | 新增文件，**非 Phase 3 實作檔案，歸屬待定位**（見 §0） |

測試項數合計 110（29+22+11+25+10+7+6），與 `TEST_RESULTS.md` 記載一致——
此為**靜態計數**，本任務未執行測試（見 §8）。

**缺失 0 檔**：遠端 65 檔在工作樹皆存在（51 相同＋14 異動）。

### 2.4 快照時點說明（誠實記錄）

- 遠端樹走訪完成於 2026-11-23 07:2x CST；本地第一次雜湊掃描得 76 檔、
  第二次得 77 檔，差異為 `docs/device-acceptance-matrix.md`（mtime 2026-11-22 23:19:24 UTC，
  即本任務進行期間前後）。本基準以第二次掃描（77 檔）為準；若其他工作者同時寫入
  同一工作樹，後續比對須重新快照，不得沿用本表。
- 本文件與任務矩陣文件寫入後，工作樹文件數為 79；兩者皆為本任務新增文件，
  不在上述 77 檔快照內。

## 3. 建置與發行機制

### 3.1 Gradle／AGP／Kotlin／SDK（`app/build.gradle.kts`、根 `build.gradle.kts`）

| 項目 | 值 | 位置 |
|---|---|---|
| AGP | `8.11.1` | 根 `build.gradle.kts`：`id("com.android.application") version "8.11.1"` |
| Kotlin | `2.1.0` | 根 `build.gradle.kts`：`id("org.jetbrains.kotlin.android") version "2.1.0"` |
| Gradle（CI／wrapper 宣告） | `8.13` | `gradle/wrapper/gradle-wrapper.properties`：`distributionUrl=.../gradle-8.13-bin.zip`；CI `setup-gradle@v4` 亦指定 `8.13` |
| `compileSdk` | `36` | `app/build.gradle.kts:22` |
| `minSdk` | `26` | `app/build.gradle.kts:27` |
| `targetSdk` | `36` | `app/build.gradle.kts:28` |
| `versionCode`／`versionName` | `13`／`"0.5.9"` | `app/build.gradle.kts:29-30`（未動） |
| `applicationId` | `tw.com.zhu.eyecontrol` | `app/build.gradle.kts:26`；`namespace` 維持 `com.example.eyecontrol`（:21） |
| Java／Kotlin JVM | `17` | `compileOptions`（Java 17）、`kotlinOptions.jvmTarget = "17"` |
| `viewBinding`／`buildConfig` | 皆 `true` | `app/build.gradle.kts` `buildFeatures` 段（`buildConfig = true` 為五不能動之一，見 §7.1） |
| `noCompress` | `+= "task"` | `app/build.gradle.kts` `androidResources` 段（五不能動之一） |
| flavors | `sideload`／`play`，dimension `distribution` | sideload：`SELF_UPDATE_ENABLED=true`、`REMOTE_MONITOR_ENABLED=true`；play：兩者皆 `false` |
| 主要依賴 | CameraX `1.4.2`（core／camera2／lifecycle／view）；MediaPipe tasks-vision `0.10.26`；core-ktx `1.15.0`；appcompat `1.7.0`；material `1.12.0`；constraintlayout `2.2.0`；lifecycle-runtime-ktx `2.8.7`；測試 junit `4.13.2`（僅 `testImplementation`，本地新增一行） | `app/build.gradle.kts` `dependencies` 段 |
| `isMinifyEnabled` | `false`（release） | `app/build.gradle.kts` `buildTypes.release` |

### 3.2 gradlew 與 CI（已核實機制，未讀取密鑰）

- **repo 無 `gradlew`**：全樹 `find` 僅見 `gradle/wrapper/gradle-wrapper.jar`
  （43,705 位元組，ZIP 檔頭 `PK`）與 `gradle-wrapper.properties`。
  該 jar「已知損壞」（任務書與 `~/AGENTS.md` 教訓：在 runner 上零輸出、瞬間 exit 1）——
  本任務**未執行**該 jar，僅以 `file`／檔頭確認其為 ZIP 檔案，不宣稱已重現損壞。
- **CI 用 `gradle/actions/setup-gradle@v4`**：兩個 workflow 皆以此步驟指定 Gradle 8.13，
  不用 wrapper（`.github/workflows/build-apk.yml`、`attach-apk-to-release.yml`；
  兩檔 blob SHA 與遠端一致，**未動**）。
- `build-apk.yml`：push 到 `main` 或手動觸發；Temurin JDK 17；以 runner 預裝 SDK
  裝 `platforms;android-36`、`build-tools;36.0.0`（註解明載不用 `android-actions/setup-android`，
  因其 `sdkmanager tools` 已失效）；建 `assembleSideloadDebug`＋`bundlePlayRelease`；
  上傳 2 個 artifact（APK／AAB，保留 30 天）。**無測試步驟**。
- `attach-apk-to-release.yml`：手動觸發，建 sideload debug APK 掛到指定 release。**無測試步驟**。
- **固定 debug keystore 只用於側載 debug 版**：機制見 `app/build.gradle.kts` 檔頭註解——
  `app/debug-keystore.b64`（base64 文字檔，blob SHA 與遠端一致）於建置時解回
  `debug.keystore`（已被 `.gitignore` 排除）；debug signingConfig 用 `android`／`androiddebugkey`
  標準 debug 憑證。本任務**未解碼、未讀取**該檔案內容，只記錄機制與 SHA。
- **release AAB 用 GitHub secrets 鑰匙**：機制見 `app/build.gradle.kts` signingConfigs 與
  `build-apk.yml` 的 "Restore release keystore" 步驟——CI 從 secret 解出鑰匙檔，
  路徑由 `EYE_RELEASE_KEYSTORE_FILE` 環境變數傳入；本機則取 `app/release.keystore`
  （工作樹不存在此檔，已確認）；缺密碼環境變數時建置期明確報錯。
  本任務**未讀取任何 secret 值**，workflow 檔案內容中出現的 secret 引用僅為變數名。
- `proguard-rules.pro`：僅 111 位元組（blob SHA 與遠端一致）；release 雖引用預設
  proguard 檔但 `isMinifyEnabled=false`，故實際不混淆——以程式碼為準，待確認是否為有意設定（待定位）。

## 4. Manifest 與無障礙服務宣告

### 4.1 主 Manifest（`app/src/main/AndroidManifest.xml`，82 行，與遠端 SHA 一致）

- 權限：`CAMERA`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CAMERA`、
  `POST_NOTIFICATIONS`、`INTERNET`、`REQUEST_INSTALL_PACKAGES`。
  前鏡頭為必要 feature（`android.hardware.camera.front`，`required=true`）。
- `application`：`android:name=".EyeControlApp"`，`allowBackup="true"`，
  icon／roundIcon 為 `@mipmap/ic_launcher*`，label／theme 如檔案所示。
- Activity（6 個，皆 `exported=false`，除 MainActivity 外）：
  `MainActivity`（launcher，`exported=true`）、`CalibrationActivity`
  （`singleTop`、直向、全螢幕主題）、`SettingsActivity`、`WelcomeActivity`、
  `SetupWizardActivity`、`TrainingActivity`（直向、全螢幕主題）。
- `FileProvider`（APK 更新分享）：authorities `${applicationId}.fileprovider`，
  資源 `@xml/file_paths`（`external-files-path`，`Download/`）。
- 服務：`GazeAccessibilityService`，`exported=true`，`foregroundServiceType="camera"`，
  `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`，
  intent-filter `AccessibilityService`，meta-data `@xml/accessibility_service_config`。

### 4.2 Play 版疊加 Manifest（`app/src/play/AndroidManifest.xml`，與遠端 SHA 一致）

- 僅移除 `INTERNET` 與 `REQUEST_INSTALL_PACKAGES` 兩權限（`tools:node="remove"`），
  與 `BuildConfig.SELF_UPDATE_ENABLED=false`／`REMOTE_MONITOR_ENABLED=false` 對應。
  無 Kotlin／res 來源，僅此一檔。

### 4.3 無障礙服務設定（`app/src/main/res/xml/accessibility_service_config.xml`，與遠端 SHA 一致）

- `description=@string/accessibility_desc`；`accessibilityEventTypes="typeAllMask"`；
  `accessibilityFeedbackType="feedbackGeneric"`；
  flags `flagDefault|flagRetrieveInteractiveWindows`；
  `canPerformGestures="true"`；`canRetrieveWindowContent="true"`；
  `notificationTimeout="100"`。
- 註：`strings.xml` 內的 `accessibility_desc` 文字仍為絕對表述（未提監控例外），
  見 §8（K-12 殘餘，第 4 步遺留 (e) 僅修了 STEP_CAMERA／歡迎頁兩處）。

## 5. 相機管線與三個相機消費者

### 5.1 `FaceTrackerEngine.kt`（597 行，與遠端 SHA 一致——Phase 3 未改引擎本體）

- 建構：`FaceTrackerEngine(context, onSample: (GazeSample?) -> Unit)`（:39-42）。
  內部：`FaceLandmarker?`、`ProcessCameraProvider?`、單執行緒 `analysisExecutor`、
  三槽 `FrameSlot`（時間戳＋Bitmap＋generation，:62-68）、`VerifyFrames`、
  `DeviceOrientationTracker`、`LightSensorTracker`、`closed` 旗標（:120）。
- `start(lifecycleOwner)`（:122-241）：
  重複 start 先 `stop()`；重置 `closed=false`；讀設定 `irisRefinement`；
  啟動姿勢／光感追蹤；建 `FaceLandmarkerOptions`（模型 `face_landmarker.task`、
  `LIVE_STREAM`、`setNumFaces(1)`、result／error listener；:130-186）；
  `FaceLandmarker.createFromOptions` 失敗時記 Log 後**往上拋**（:187-196，
  由呼叫端決定降級）；建 `ImageAnalysis`（1280×720、`STRATEGY_KEEP_ONLY_LATEST`、
  RGBA_8888；:207-221）；`providerFuture.addListener` 內 `unbindAll()` 後
  `bindToLifecycle(..., DEFAULT_FRONT_CAMERA, analysis)`（:222-240）；
  綁定例外只記 `Log.e「相機綁定失敗」`、**無回呼、無旗標、無外拋**（:238-240）——
  此為已知缺口（見 §8）。
  Result listener：無臉 → `onSample(null)`；有臉 → `GazeFeatureExtractor.extract`
  → 附加姿勢／亮度／眼部影像度量 → `onSample(outSample)`（:137-184）。
  `analyze()`（:266 起）：幀槽寫入＋`detectAsync`；`closed` 時直接返回（:268）。
- `stop()`（:506-542）：順序 `closed=true` → 停感測器 → `unbindOnMainThread()`
  → `cameraProvider=null` → 清幀槽（generation++）→ 清驗證影格佇列 → 停分析執行緒
  （`awaitTermination` 500 ms）→ `landmarker.close()` → 回收 Bitmap。
  `unbindOnMainThread()`（:554-568）：主執行緒直接 `unbindAll()`；背景執行緒排到
  主執行緒並以 `CountDownLatch` 最多等 2 秒（v0.5.4 閃退修復，見 MEMORY 記載）。
- 世代守衛範圍（待確認，見 §8 K-02）：`closed` 檢查存在於 `analyze`／`lookupFrameMetrics`／
  `captureEyeCrop`（:268、:297、:386），幀槽 generation 僅用於影像度量驗證；
  **綁定 listener 內未見 `closed`／generation 檢查**（:207-241 實測）——
  `stop()` 後才執行的 listener 是否會重新綁定，靜態上無法排除，列為待確認。
- `setVerifyCapture(local, upload)`（:243）、`requestVerifyCapture(kind, ...)`（:256）：
  驗證影格開關與事件請求（佇列上限 8、逾時 2 秒丟棄）。

### 5.2 消費者一：`GazeAccessibilityService`（1982 行，Phase 3 異動）

- 類別：`AccessibilityService`＋`LifecycleOwner`（:41），供 `FaceTrackerEngine.start`
  的 `lifecycleOwner`（`lifecycleRegistry`，`onCreate` 設 `CREATED`、:315-318；
  `onServiceConnected` 設 `RESUMED`、:320-322）。
- `onServiceConnected`（:320-350）：`instance = this`；啟動前台服務（通知，
  `startForegroundService` :367-390）；載入設定、註冊兩個 prefs 監聽
  （`settingsListener` :65、`profileListener` :87）；取螢幕尺寸；建兩個 overlay；
  `deriveInitialFlowState()`；`startEngine()`。**不恢復** `userStopped`／`currentMode`
  （行程重啟即丟失，K-30，見 §8）。
- `startEngine()`（:391-431）：`pausedForCalibration || userStopped` 時直接返回；
  先停舊引擎（冪等）；載入目前情境模型（無模型只記 Log 警告）；重置濾波器；
  建引擎並 `try { engine?.start(this) } catch`——同步失敗記 `CrashReporter`、
  清引擎、流程態進 `ERROR`（:408-427）；成功後 `applyVerifyCaptureFlags()`、
  同步流程態。**無 generation 守衛**（K-14 結構，見 §8）。
- `onSample`（:484-489）：`pausedForCalibration || userStopped` 提早返回；
  否則 post 到主執行緒。`onSampleOnMainThread`（:491 起）：再查 `pausedForCalibration`；
  更新 `lastSampleAtMs`／`lastFacePresent`／`lastHasModel` 等 Dashboard 快照欄位；
  `inRecoveryMode` 時只跑重啟目標分支並返回（:500-506）；`model == null` 返回；
  臉遺失分支（灰圈、清進度、`cancelEdgeGesture()`、記 `GazeLog` 臉遺失幀、推進提醒）；
  閉眼凍結；提醒 preempt（:648、:691）；把手／面板 dwell；邊緣控制器判決
  （:835-925）；螢幕 dwell 提交（`triggerActionAt`）。
- 提醒：`evaluateConditions` 每 500 ms（`CONDITION_CHECK_INTERVAL_MS`，companion）；
  `showReminder`／`updateReminder`／`dismissReminder`（:1035-1208）；
  提醒期間面板／停留／邊緣／眨眼輸入皆拒絕（preempt 最高優先）。
- `onConfigurationChanged`（:352-366）：更新螢幕尺寸、重置濾波器、重置條件狀態、
  `cancelEdgeGesture()`（方向改變強制重置邊緣手勢）。
- `onDestroy`（:1607-1632）：`instance` 清 null；反註冊監聽；`engine?.stop()`；
  `GazeLog.close()`；移除兩個 overlay。**不持久化任何流程旗標**。

### 5.3 消費者二：`CalibrationActivity`（1125 行，Phase 3 異動）

- 自有引擎（:54，`lateinit var engine`；`onCreate` :202-204 建 `FaceTrackerEngine`，
  回呼經 `runOnUiThread { onSample(sample) }`）；自有模型訓練與存檔
  （`finishCalibrationInternal` :695-760）；自有 `Phase` 列舉
  （`HANDOVER, SAMPLING, REPORT, ASK, ERROR`，:71）。
- 進入：`view.post`（:218-231）與 `onResume`（:232-246）皆發起交接
  （`requestCameraHandover` :272-284，`handoverPending` 守衛＋`handoverGeneration`
  世代＋自設逾時 `POINT_TIMEOUT_MS`）；回呼 `onHandoverReleased`（:286-299）世代比對後
  `startEngineSafely`（:326-343，失敗進 ERROR 態、不 `finish()`）。
- `onPause`（:248-257）：停引擎、取消交接逾時、重置選擇停留。
  `onDestroy`（:865-873）：取消逾時、停引擎、**無條件** `resumeAfterCalibration()`。
- 九點流程：`buildPoints` 3×3（0.08／0.5／0.92，:345-349）；
  `SETTLE_MS = 900L`、`SAMPLES_PER_POINT = 20`、`KEEP_RATIO = 0.7`、
  `POINT_TIMEOUT_MS = 10_000L`（companion，:1108-1112）；
  `startPoint`（:351）、`onSamplingSample`（:379）、`checkPointTimeout`（:431）、
  逾時 3 次進 `enterAsk`（:444；`POINT_TIMEOUTS_BEFORE_ASK = 3`）；
  `finishCalibration`（:680，失敗進 ERROR＋重試）→ `finishCalibrationInternal`
  （`GazeModel.train` → `GazeEvaluator.evaluate`／`leaveOnePointOut` →
  `CalibrationStore.saveModel` → `ConditionHistory.clearHistory`／`saveReference`
  → `showReport` :765）→ 報告頁三選擇（`showReportChoices` :806：
  重新校正／去練習／完成；`recalibrate` :839）。
- 選擇注視判定複用 TrainingActivity 規則（`onChoiceSample` :519、`choiceCursor` :566、
  `buttonAt` :582）；無舊模型時無法判讀（`choiceModel == null` 時重置返回，
  檔頭註解與 :507-533 實測）——首次校正的詢問／重試頁注視選擇無法判讀，
  列為已知缺口（見 §8）。
- 無相機權限檢查（全檔 grep `CAMERA`／`Permission` 零命中）；無 `onSaveInstanceState`
  以外的旋轉保護以外的進度持久化（檢查點只存兩個整數，:259-264；重建後誠實重採，
  `restoreNotice` :212-216）。

### 5.4 消費者三：`TrainingActivity`（866 行，與遠端 SHA 一致——Phase 3 未改）

- 自有引擎（:44，`lateinit`；:110 建 `FaceTrackerEngine`）；自有模型載入
  （`setupModelAndStart` :178，`CalibrationStore.loadModel` :179）；
  同走 `requestPauseForCalibration`（:201，`requestCameraAndStart` :195-210，
  含重複請求守衛）／`resumeAfterCalibration`（`onDestroy` :238）。
- `onResume`（:212）、`onPause`（:221，停引擎）、`onDestroy`（:229，停引擎＋接回服務）。
- 練習：`ExerciseDef`／`Kind { ENTER, DWELL, DWELL_LONG, CANCEL, SCROLL }`
  （:797-804）；按鈕 dwell（`handleButtonDwell` :384，秒數取 `settings.dwellTimeMs`，
  `DWELL_LONG` 取 2 倍）；**不吃靈敏度與濾波設定以外的設定？待定位**——
  實測：`setupModelAndStart` 讀 `settings.filterMinCutoff`／`filterBeta`（:184-185），
  游標計算用 `settings.gyroCompensation`／`distanceCompensation`（:446）；
  「不吃靈敏度」為 PLAN 記載，本任務未逐項核對全部設定欄位，標待定位。
- 無模型時導引去校正（`goToTraining` 對照見 CalibrationActivity :860-863；
  訓練頁 `noModel` 旗標，`TrainingView` :605）。

### 5.5 交接協議 `requestPauseForCalibration`／`resumeAfterCalibration`（五不能動之一的協議，不得改動）

位置：`GazeAccessibilityService.kt` companion，`requestPauseForCalibration` :1648-1686、
`resumeAfterCalibration` :1688-1714。

1. `instance == null` 時直接同步回呼。
2. 否則立 `pausedForCalibration = true`，背景執行緒停引擎（`===` 身分確認，停的必須是
   同一實例），主執行緒藏面板（`GONE`）、收提醒／重啟／確認目標、重置重啟計時、
   `cancelEdgeGesture()`、同步流程態 `CALIBRATING` 後才回呼 `onReleased`。
   **無逾時、無失敗回呼**（回呼永不來時呼叫端空等——校正頁以自設逾時處理，見 §5.3）。
3. `resumeAfterCalibration`：主執行緒清旗標 → 面板 `VISIBLE`（PAUSED 時展開；
   RECOVERY_MODE 時維持 `GONE`）→ `startEngine()`；`userStopped` 時以事實推導補同步 STOPPED。
- 本協議在 Phase 3 實作中**本體未改**，僅在回呼內新增 overlay 收尾
  （重啟目標、邊緣確認目標）——`diff` 實測：協議函式簽名與 `===`／背景停表／
  主執行緒回呼順序保留。D-4 取消契約（`CANCEL_CONTRACT.md`）要求取消路徑
  不得自行呼叫 `resumeAfterCalibration()`（只由 `onDestroy` 呼叫一次），現況
  CalibrationActivity 全檔僅 `onDestroy` 一處呼叫（:873），符合。

## 6. 全部 Activity／Service／View／設定儲存檔：呼叫與生命週期關係

### 6.1 元件總表（行數為目前工作樹）

| 元件 | 檔案（行數） | 性質 |
|---|---|---|
| `EyeControlApp` | `EyeControlApp.kt`（11） | Application；只安裝 `CrashReporter` |
| `MainActivity` | `MainActivity.kt`（583） | Dashboard／主畫面（launcher） |
| `WelcomeActivity` | `WelcomeActivity.kt`（60） | 首次啟動歡迎頁 |
| `SetupWizardActivity` | `SetupWizardActivity.kt`（457） | 6 步引導 |
| `CalibrationActivity` | `CalibrationActivity.kt`（1125） | 九點校正頁（自繪 `CalibrationView`＋`choiceBar`） |
| `TrainingActivity` | `TrainingActivity.kt`（866） | 操作訓練頁（自繪 `TrainingView`） |
| `SettingsActivity` | `SettingsActivity.kt`（409） | 設定頁 |
| `GazeAccessibilityService` | `GazeAccessibilityService.kt`（1982） | 無障礙服務（唯一手勢注入） |
| `CursorOverlayView` | `CursorOverlayView.kt`（307） | 游標／提醒／重啟／確認目標 overlay |
| `ControlPanelView` | `ControlPanelView.kt`（229） | 模式面板（純繪製，命中判定在服務端） |
| `FaceTrackerEngine` | `FaceTrackerEngine.kt`（597） | 相機＋Landmarker 引擎 |
| `GazeFeatureExtractor` | `GazeFeatureExtractor.kt`（226） | 22 維特徵萃取 |
| `GazeModel` | `GazeModel.kt`（342） | 嶺回歸模型＋序列化 |
| `OneEuroFilter` | `OneEuroFilter.kt`（76） | 平滑濾波 |
| `DwellTracker` | `DwellTracker.kt`（122） | 螢幕點擊 dwell 狀態機（純 Kotlin） |
| `EdgeGestureController` | `EdgeGestureController.kt`（676） | 邊緣手勢控制器（純 Kotlin，新增） |
| `AppFlowState`／`AppFlowStateMachine` | `AppFlowState.kt`（226） | 流程狀態機（純 Kotlin，新增） |
| `RecoveryGate` | `RecoveryGate.kt`（56） | 重啟驗證純判斷（新增） |
| `GazeEvaluator` | `GazeEvaluator.kt`（123） | 校正品質評估 |
| `CalibrationStore` | `CalibrationStore.kt`（116） | 情境模型 prefs 存取 |
| `GazeSettings` | `GazeSettings.kt`（226） | 設定 prefs 存取 |
| `ConditionHistory` | `ConditionHistory.kt`（257） | 條件歷史／基準 prefs 存取 |
| `GazeLog` | `GazeLog.kt`（312） | 診斷 CSV 紀錄＋上傳 |
| `VerifyFrames` | `VerifyFrames.kt`（367） | 眼區驗證影格留存＋上傳 |
| `EyeImageMetrics` | `EyeImageMetrics.kt`（302） | 眼部影像度量 |
| `DeviceOrientationTracker`／`LightSensorTracker` | 各 86／59 行 | 姿勢／光感感測器 |
| `CrashReporter` | `CrashReporter.kt`（69） | 閃退紀錄 |
| `UpdateChecker` | `UpdateChecker.kt`（143） | GitHub 更新檢查（僅 sideload 有效） |
| 測試 | `app/src/test/...` 7 檔 | 純 JVM 單元測試（見 §2.3） |

### 6.2 呼叫關係（功能呼叫圖，文字版）

```
MainActivity ──首次啟動──> WelcomeActivity ──開始設定──> SetupWizardActivity
     │  (isFirstRun, WelcomeActivity.kt:51)      │  (STEP_DONE 後回 MainActivity)
     ├──校正鈕──> CalibrationActivity ──完成──> (finish 回呼叫端；報告頁「去練習」──> TrainingActivity)
     ├──設定鈕──> SettingsActivity ──重新執行引導──> SetupWizardActivity
     ├──練習鈕──> TrainingActivity
     └──狀態輪詢 (1s)──> GazeAccessibilityService.trackingSnapshot() (companion :1753)
                        + canStartTracking() / pauseTracking() / resumeTracking()
                        + resumeFromRecovery() / stopTracking() / stopCompletely()

GazeAccessibilityService ──startEngine()──> FaceTrackerEngine.start(this)
     │  onSample ──主執行緒──> onSampleOnMainThread ──> DwellTracker / EdgeGestureController
     │                                      ──> GazeModel.predict + OneEuroFilter
     │                                      ──> dispatchGesture (performTap/DoubleTap/LongPress/Swipe/Scroll)
     │                                      ──> performGlobalAction (BACK/HOME, 經 performEdgeAction)
     │                                      ──> CursorOverlayView + ControlPanelView (WindowManager overlay)
     │  prefs 監聽──> GazeSettings.load / CalibrationStore.exists+loadModel / ConditionHistory
     │  條件評估──> ConditionHistory.interpolate / hasSignificantChange ──> 提醒 (showReminder)
     └── GazeLog.record / VerifyFrames.requestVerifyCapture (經引擎)

CalibrationActivity ──requestPauseForCalibration──> 服務停引擎 ──onReleased──> 自有 FaceTrackerEngine.start
     │  onSample ──> 採樣 (allFeatures/allTargets) ──> GazeModel.train ──> CalibrationStore.saveModel
     │                                          └──> ConditionHistory.clearHistory + saveReference
     └── onDestroy ──resumeAfterCalibration──> 服務 startEngine()

TrainingActivity ──requestPauseForCalibration──> 服務停引擎 ──onReleased──> 自有 FaceTrackerEngine.start
     │  onSample ──> GazeModel.predict (舊模型) ──> 按鈕 dwell / 練習目標
     └── onDestroy ──resumeAfterCalibration──> 服務 startEngine()

FaceTrackerEngine ──> MediaPipe FaceLandmarker (face_landmarker.task)
     │  ──> GazeFeatureExtractor.extract ──> GazeSample (features + 睜眼 + 感測器 + 影像度量)
     │  ──> EyeImageMetrics.measure (眼區裁切度量；VerifyFrames 用同一裁切)
     └──> DeviceOrientationTracker.snapshot + LightSensorTracker.snapshot (附加於樣本)

SettingsActivity ──> GazeSettings.load/save ──> prefs "gaze_settings" (服務 settingsListener 即時重載)
     │  ──> CalibrationStore (情境切換、模型版本文字) / GazeLog.summarize / CrashReporter
     └──> SetupWizardActivity (重新執行引導)

UpdateChecker ──> GitHub releases API (僅 SELF_UPDATE_ENABLED 時由 MainActivity 呼叫)
CrashReporter ──> EyeControlApp.install (全域未捕捉例外) + 各處 recordCaught
```

### 6.3 生命週期關係

| 生命週期事件 | 行為（證據行號） |
|---|---|
| App 啟動 | `EyeControlApp.onCreate` 安裝 `CrashReporter`（:7-10）；`MainActivity.onCreate` 若 `isFirstRun` 則跳 `WelcomeActivity` 並 `finish()`（:95-99）。 |
| 歡迎頁 | 兩鈕皆手點：開始設定 → 引導；先看看主畫面 → `markWelcomeSeen` → 主畫面（`WelcomeActivity.kt:36-44`）。無眼控操作、無狀態預檢。 |
| 引導 | 6 步：歡迎 0 → 須知 1 → 相機 2 → **校正 3** → **服務 4** → 完成 5（`SetupWizardActivity.kt:441-447`）。步驟只存 `savedInstanceState`（`KEY_STEP` 等）＋跳過旗標；無逐項完成持久化。`onResume` 重查狀態（:110）。完成頁彙總逐項狀態，可點回對應步驟（:301-390）。 |
| 主畫面 | `onResume` 開始 1 秒輪詢 `refreshStatus`（:274-287，`STATUS_POLL_MS = 1000L` :581）；`onPause`／`onDestroy` 停輪詢（:288-297）。開始／暫停／停止皆經服務 companion 函式（見 §6.2）。 |
| 服務建立 | 見 §5.2。行程重啟後 `onServiceConnected` 不檢查舊旗標，模型存在即自動恢復追蹤（K-30）。 |
| 服務銷毀 | 見 §5.2 `onDestroy`。overlay 移除、`GazeLog.close()`（`close(upload=true)` 預設上傳，`GazeLog.kt:139`）。 |
| 校正頁 | 見 §5.3。`onPause` 停引擎；`onDestroy` 停引擎＋無條件接回服務。重建後從第 1 點重採（檢查點只存整數）。 |
| 訓練頁 | 見 §5.4。`onPause`／`onDestroy` 皆停引擎；`onDestroy` 接回服務。 |
| 設定頁 | `onCreate` 讀設定建 UI（:31 起）；`onResume` 刷新（:202）；`saveAndFinish`（:278）寫回 prefs；服務經 `settingsListener` 即時重載。設定頁本身無 `onSaveInstanceState` 草稿保存（稽核 PG 項，現況未核對是否已修——**待定位**，見 §8）。 |
| 旋轉／設定變更 | 服務 `onConfigurationChanged` 重置濾波器與邊緣手勢（:352-366）；校正頁無 `onConfigurationChanged` 處理（Activity 重建 → 重採，見 §5.3）。 |
| 情境切換 | 服務 `profileListener` 換模型＋主執行緒重推導流程態（:87-110）；主畫面 `profileGroup` 切換寫 `active_profile`（`MainActivity.kt:564-578`）；校正頁以 Intent extra 或目前情境為 `profileId`（:163-165）。 |

### 6.4 設定儲存檔（三份 SharedPreferences，皆無流程狀態持久化）

| prefs 名稱 | 存取者 | 內容 |
|---|---|---|
| `gaze_settings` | `GazeSettings`（`PREFS_NAME`，`GazeSettings.kt:127`） | 全部使用者設定（dwell、冷卻、眨眼、濾波、靈敏度、陀螺／臉距／條件修正、診斷／監控、虹膜精修、邊緣 5 開關）。`load()` 損毀時回預設值（含監控開回 `true`，:181-226）。`clamped()` 夾範圍（:77-99）。 |
| `calibration_profiles` | `CalibrationStore`（`prefs()` :38） | 三情境模型（`model_<id>`）＋中繼資料（`meta_<id>`：`calibratedAt`、`looErrorPx`）＋`active_profile`。`exists()`＝模型載入成功（:61）。舊版單一模型遷移 `migrateIfNeeded`（:98）。 |
| `condition_history` | `ConditionHistory`（`prefs()` :247） | 每情境最多 50 筆紀錄＋基準條件（`reference_<id>`）。 |
| `eyecontrol_setup` | `WelcomeActivity`（:48） | 僅 `welcome_seen` 布林。 |
| `gaze_model`（舊版） | `GazeModel.load/save`（:116、:111） | 舊版單一模型，僅供遷移讀取。 |
| `crash_reports`（推斷名，待定位） | `CrashReporter`（:57） | 閃退報告已顯示標記；確切 prefs 名稱未逐字核對，標待定位。 |

流程狀態（`userStopped`、`pausedForCalibration`、`inRecoveryMode`、`currentMode`）
皆為服務記憶體欄位（`GazeAccessibilityService.kt:113-121` 附近，`@Volatile`），
**無任何持久化**——行程重啟即丟失（K-30，見 §8）。

### 6.5 D1-9 預留（親自 grep 驗證）

- 僅 `CalibrationActivity.kt` companion 4 個常數（:1117-1123）：
  `CANCEL_TARGET_RESERVED = "UNIMPLEMENTED: 待 D-4 決策"`、
  `CANCEL_TARGET_RESERVED_CORNER`、`CANCEL_TARGET_RESERVED_X_FRACTION = 0.92f`、
  `CANCEL_TARGET_RESERVED_Y_FRACTION = 0.08f`，附「絕對不得視為已完成」註解。
- 全專案 grep `CANCEL_TARGET_RESERVED`：僅定義處＋檔頭註解（:47）＋繪製處註解（:1034）
  **零引用**（無讀取、無比較、無傳參）；`CANCEL` 字串另見服務端兩處
  `CAPTURE_EDGE_CONFIRM_CANCELLED`（邊緣確認取消擷取，與 D1-9 無關）。
- 無視覺元素：繪製處註解明載「刻意不繪製任何取消目標」；九點座標計算
  （`buildPoints` :345-349）未引用預留常數。
- 無事件、無取消／退出行為：`exitCalibration()`（:501）為既有退出語義
  （ASK／ERROR 頁的「退出」選擇共用），**不是** D1-9 取消目標，不得混為一談。

### 6.6 PLAN D3-7 dispatch 部分（親自 grep 驗證，未做）

- 四個 dispatch（`performScroll` :1542、`performTap` :1561、`performDoubleTap` :1573、
  `performLongPress` :1580、`performSwipe` :1592）**全部只 override `onCompleted`**，
  全檔 grep `onCancelled` **零命中**（K-62 仍在）。
- `performDoubleTap` 第二下仍為匿名 `mainHandler.postDelayed({ performTap(x, y) }, 150)`
  （:1573-1577），**無具名 Runnable、無 `removeCallbacks`**（K-07 仍在）；
  全檔 grep `removeCallbacks` 零命中（僅 CalibrationActivity 交接逾時一處 :318，與 dispatch 無關）。
- 與之區分：`EdgeGestureController` 的 `EdgeDecision.Cancelled` 輸出與
  `EdgeCancelReason`（`EdgeGestureController.kt:192-200、:639-652`）**已實作**
  （控制器層取消），不得與 dispatch 層取消混為一談（見任務矩陣 D3-7）。

## 7. 紅線與機制核實

### 7.1 五個不能動（全部以目前工作樹行號驗證，維持現況）

| 紅線 | 驗證 |
|---|---|
| 九點資料流 | `CalibrationActivity.buildPoints` 3×3（:345-349）；`SETTLE_MS 900L`、`SAMPLES_PER_POINT 20`、`KEEP_RATIO 0.7`、`POINT_TIMEOUT_MS 10_000L`（companion :1108-1112）；`finishCalibrationInternal` 訓練→存檔→清歷史→寫基準順序（:695-760）。Phase 3 新增階段／報告頁，未改上述常數與順序（`diff` 實測：常數行皆保留）。 |
| 22 維特徵契約 | `GazeFeatureExtractor.DIM = 22`（`GazeFeatureExtractor.kt:67`）；`GazeModel.fromJsonString` 驗 `wx.size == DIM`（`GazeModel.kt:154`）；`predict` 要求 `f.size >= 9`（:37-47）。`GazeSample.features` 為 `DoubleArray`，影像度量欄位不進 22 維（`GazeSample` :19-31）。 |
| `requestPauseForCalibration` 協議 | 見 §5.5。簽名、背景停表、`===` 身分確認、主執行緒回呼順序保留；僅回呼內新增 overlay 收尾。 |
| `buildConfig = true` | `app/build.gradle.kts` `buildFeatures` 段（與遠端 `diff` 無差異行）。 |
| `noCompress += "task"` | `app/build.gradle.kts` `androidResources` 段（與遠端 `diff` 無差異行）；模型檔 `app/src/main/assets/face_landmarker.task` blob SHA 與遠端一致。 |

### 7.2 診斷／監控預設開啟（維持現況，不得擅改）

- `GazeSettings.diagnosticLogging = true`、`remoteMonitor = true`（預設值與 `load()` 預設皆 `true`，
  `GazeSettings.kt:52-60` 附近欄位定義與 :210-216 讀取預設）。
- `GazeLog`：CSV 17 欄（HEADER :31）、10 Hz 節流、120 秒自動輪轉（`MAX_SECONDS = 120L` :29；
  `record` 逾時即 `close()` 再開新段，:110-111，**非停止**）；`close(upload = true)` 預設上傳。
  遠端端點與金鑰機制：`GazeLog.kt:39-41` 有 Supabase URL 與 anon key 常數——
  **本文件不抄錄其值**；註解稱 RLS 僅允許 INSERT（不能讀／改／刪），讀取用 service_role
  金鑰、絕不進 App。伺服端實際 RLS 設定**未驗證**（稽核亦未檢查，見 §8）。
- `VerifyFrames`：眼區裁切留存＋上傳（`FRAMES_DIR`、每 session 60 張上限等，:285-314）；
  校正頁引擎不設開關、故校正流程不產生影格（`FaceTrackerEngine.kt` 註解 :78-84 附近）。

### 7.3 設計系統（現況色值，僅記錄）

- `ControlPanelView`／`CursorOverlayView`／`CalibrationView` 繪製色值：
  Sage `#819B89`（RGB 129,155,137／82,111,92 為深鼠尾草綠）、Warm Ivory
  `#F7F6F2`（RGB 247,246,242）、背景 `#EDEBE8` 系。色值以程式碼 `Color.rgb`／`argb`
  為準；`res/values/colors.xml`（與遠端 SHA 一致）另有命名色值，本任務未逐項對照
  程式內色值與資源色值是否一致，標待定位。

### 7.4 未讀取密鑰聲明

- 本任務未讀取、未解碼、未抄錄：`app/debug-keystore.b64` 內容、`GazeLog` 內的
  Supabase 金鑰值、任何 GitHub secret 值、任何環境變數密碼。上述檔案僅以 blob SHA
  （`debug-keystore.b64` 與遠端一致）與機制註解記錄。`GazeLog` 內的 anon key 為
  Supabase 設計上可公開的金鑰，依任務紅線仍不抄錄。

## 8. 已知缺陷與無法驗證的項目

### 8.1 已知缺陷（靜態證據，皆未經實機驗證）

| 編號／來源 | 缺陷 | 目前工作樹證據 |
|---|---|---|
| K-30（稽核 P1 級，PLAN §6 D-8 待決） | `userStopped`／`inRecoveryMode` 未持久化，行程重啟自動恢復追蹤 | 旗標為記憶體欄位（`GazeAccessibilityService.kt:113-121`）；`onServiceConnected` 不檢查舊旗標（:320-350）；`stopCompletely` 註解自承（:1935-1940 附近）。維持現況，不得宣稱已修復。 |
| K-02／K-14（稽核 P1） | 引擎啟停競態：`start()` 無 generation 守衛；`startEngine` 無 generation | `startEngine` 先停舊引擎再建新引擎（:391-431），無世代計數；綁定 listener 無 `closed` 檢查（`FaceTrackerEngine.kt:207-241`）。`stop()` 的幀槽 generation 僅用於度量驗證，不構成啟停世代守衛。**待確認**：listener 晚到重綁是否實際發生，須實機反覆啟停驗證。 |
| K-03（稽核 P1） | Bitmap 回收與非同步 detect 競態 | `stop()` 在 `awaitTermination(500ms)` 後即 `close()`／`recycle()`（`FaceTrackerEngine.kt:526-540`）。稽核主張 `awaitTermination` 不保證原生管線排空；本任務未驗證 MediaPipe 行為，標待確認。 |
| 相機非同步綁定失敗無可觀測信號 | 綁定失敗只記 Log，流程態不進 ERROR，僅由樣本新鮮度間接擋下驗證 | `FaceTrackerEngine.kt:238-240`（catch 只 `Log.e`）；`GazeAccessibilityService.startEngine` 註解自承（:408-427）。`canStartTracking`／`verifyServiceRunning` 皆不宣稱覆蓋（:1799-1821）。 |
| K-07／K-62 | 雙擊第二下無取消、dispatch 無 `onCancelled` | 見 §6.6。 |
| K-19／D-4 | 校正頁無眼控取消目標 | 見 §6.5。D-4 形式／秒數／位置皆未決（`D4_OPTIONS.md` 三選項＋8 問，未答）。 |
| 首次校正注視選擇無法判讀 | 詢問／重試頁吃舊模型預測；無舊模型時按鈕只能手點 | `CalibrationActivity` 檔頭註解（:40-49）與 `onChoiceSample`（:519-564）。取消契約 §0.3 要求 D-4 形式在無模型時仍須有返回路徑。 |
| K-12 殘餘 | 絕對表述「不存照片、不上傳」仍存於多處 | 已修：引導 STEP_NOTICE、歡迎頁側載版文字（`WelcomeActivity.kt:22-34`）、STEP_CAMERA 說明句未動（IMPLEMENTATION_NOTES 遺留 (e)）。仍為絕對表述處：`SettingsActivity` 對話框、主畫面頁尾、`strings.xml` 的 `accessibility_desc`——本任務未逐一 grep 核對，**待定位**（以 IMPLEMENTATION_NOTES 記載為準，不得照抄為已驗證）。 |
| K-23 | 歡迎頁／引導前段／系統設定頁只能手點 | 歡迎頁兩鈕皆手點（`WelcomeActivity.kt:36-44`）；引導按鈕皆手點；系統設定頁在 App 控制外。D-1 順序下首次啟動仍須手動授權，屬已知限制，非本階段缺陷。 |
| K-17 已處理（待驗證） | Dashboard 死按鈕已移除 | `btnCamera`／`btnAccessibility` 全樹零命中（見 §2.3）。但相鄰行為改變：`MainActivity` 的相機權限請求 launcher 同遭移除（見 §2.3）——**意圖待確認**，可能為疏漏，須協調員確認是否應恢復請求路徑（引導 STEP_CAMERA 仍可請求權限，`SetupWizardActivity.kt:139`）。 |
| 門檻未校準 | dispersion、連續 present 幀數、方向死區、邊緣帶上限、確認寬限皆未量測 | `EdgeGestureController` companion 常數標 `UNCALIBRATED`、dispersion 預設 `Float.MAX_VALUE`（不阻擋，:490-525）。等級門檻 60／120／200 px 為經驗值（`CalibrationActivity.kt:718-724` 附近）。 |
| 臉遺失時濾波器不重置 | 臉回來第一幀即可累積（恢復序列 TARGET 未實作） | `onSampleOnMainThread` 臉遺失分支只清 dwell 進度，未重置 `filterX`／`filterY`（:512-545）；重置只在 `startEngine` 與旋轉時。STATE_DESIGN §4.2 聲明完整恢復序列不在 Phase 3 範圍。 |
| 品質層未建模 | 無 `QualityState` 具名狀態；MediaPipe 信心值未被讀取 | 全樹無品質狀態列舉（僅 `AppFlowState`、`ActionMode`、`FaceTurn`、`TrainingActivity.Kind`、`Edge*` 列舉）。不得以任何信心值定義狀態（STATE_DESIGN §4.4）。 |
| 文件漂移 | `AppFlowState.kt` 檔頭稱「影子模式」「不得被既有程式引用」 | 實際服務已接線（`GazeAccessibilityService.kt:175、:223-231` 等多處引用）。檔頭文字已過時，須後續批次修正文件，不得視為程式缺陷。 |

### 8.2 無法驗證的項目（本任務方法邊界）

1. **編譯**：未執行 Gradle（本機 Gradle 因沙箱 JVM loopback 問題無法執行，見
   `IMPLEMENTATION_NOTES.md` §0；本任務未重試、未繞過）。全量 Android 編譯
   （`assembleSideloadDebug`／`bundlePlayRelease`）**未驗證**，留待 CI。
2. **單元測試執行**：未執行 harness／Gradle 測試。`@Test` 110 項為靜態計數；
   `TEST_RESULTS.md` 記載的「協調員親自重跑 OK (110 tests)」為先前記載，
   本任務引用、不重跑、不背書。
3. **CI**：未推送、未觸發 workflow；CI 執行紀錄**未檢查**。
4. **實機**：全部實機清單（TEST_PLAN §3 的 C／G／R／E 系列）**未做**；
   實機裝置 SM-S9180 上的圖示、校正、游標、手勢、旋轉、監控上傳皆未驗證。
5. **JSON 往返**：`GazeModel`／`CalibrationStore`／`ConditionHistory` 的
   `org.json` 讀寫在純 JVM 測試中為 stub，**未驗證**（須實機或 Robolectric）。
6. **伺服端**：Supabase RLS／資料表實際設定、監控上傳實際到達與否**未檢查**。
7. **二進位檔內容**：`face_landmarker.task`、`gradle-wrapper.jar` 僅記錄 SHA／大小／
   檔頭，內容未檢查、模型雜湊未與官方比對。
8. **效能／量測數字**：誤觸率、漏觸率、操作時間、dispersion 門檻、邊緣帶物理寬度
   對照（50 dp 在 SM-S9180 上約為 v0.5.9 的 4 倍，見 IMPLEMENTATION_NOTES 出入 24）
   皆**未量測**，不得宣稱為已驗證數字。
9. **稽核文件引用行號**：`KNOWN_ISSUES.md` 等稽核文件以 v0.5.7 樹為準，
   行號已漂移；本文件引用的 K 編號以稽核文件語義為準，行號一律以目前工作樹重標，
   不得直接沿用稽核行號。

### 8.3 複用文件與依據位階

- 最高依據：`~/workspace/eyecontrol-master-audit/docs/phase2/GAPS_AND_DECISIONS.md` §3.1
  （D-1、D-2、D-3 定稿）。本任務讀取了該文件目錄與 D-1…D-3 條目存在性，
  未逐字重抄；方案細節以 `~/workspace/eyecontrol-phase3/` 七份文件為準
  （`PLAN.md`、`STATE_DESIGN.md`、`TEST_PLAN.md`、`IMPLEMENTATION_NOTES.md`、
  `CANCEL_CONTRACT.md`、`D4_OPTIONS.md`、`TEST_RESULTS.md`，皆已讀）。
- 稽核文件（`eyecontrol-master-audit/docs/audit/`、`phase2/`）已讀目錄與
  `KNOWN_ISSUES.md` 前段、`FILE_INVENTORY.md` 前段；其餘稽核專篇未逐篇重讀，
  本文件不代為斷言未讀部分的內容。
- 與方案文件不一致處：以方案文件為準；與本文件不一致處（例如 `AppFlowState.kt`
  檔頭的影子模式文字），以程式碼現況為準並記錄漂移（見 §8.1）。

## 附錄 A：全部檔案 blob SHA 對照表（快照時點：2026-11-23 07:2x CST）

遠端 SHA 取自 `get_file_contents`（`sha=c6b80964`）；本地 SHA 以
`sha1("blob <len>\0"+內容)` 親自計算。狀態：`=` 相同、`M` 異動、`A` 新增。

| 路徑 | 遠端 SHA | 本地 SHA | 狀態 |
|---|---|---|---|
| `.github/workflows/attach-apk-to-release.yml` | `d7fcbafaf69e232a5c52f3574e125e7f0bdfb56d` | `d7fcbafaf69e232a5c52f3574e125e7f0bdfb56d` | = |
| `.github/workflows/build-apk.yml` | `c0bd3960bfbdb076e2a2b87a657339577d00a1e3` | `c0bd3960bfbdb076e2a2b87a657339577d00a1e3` | = |
| `.gitignore` | `24178051276898e1e83f01de05bba095ac24c1f0` | `24178051276898e1e83f01de05bba095ac24c1f0` | = |
| `LICENSE` | `7f27c961a1e7db8875beb74d0c0934c5f736ac3e` | `7f27c961a1e7db8875beb74d0c0934c5f736ac3e` | = |
| `README.md` | `1ebef1e29d977611be702dcaa05ca630997438f9` | `1ebef1e29d977611be702dcaa05ca630997438f9` | = |
| `app/build.gradle.kts` | `3f436e5f65cca03bf7bc78a7a1dd85bc3b3100a2` | `2aa2e4576450e290fbd60b9aa500ff70a7a2a0fb` | M |
| `app/debug-keystore.b64` | `21cf6ac0966efebce92a445d6c3e067cf8686e80` | `21cf6ac0966efebce92a445d6c3e067cf8686e80` | = |
| `app/proguard-rules.pro` | `a27201e0abb9ec2e8e11b18f42844de5d63fd92f` | `a27201e0abb9ec2e8e11b18f42844de5d63fd92f` | = |
| `app/src/main/AndroidManifest.xml` | `7b56c6f7a14b62f7904db54531ca9d92ce1dd266` | `7b56c6f7a14b62f7904db54531ca9d92ce1dd266` | = |
| `app/src/main/assets/face_landmarker.task` | `c50c845d113e02ac6e3627b8121db7fb9a35bb4b` | `c50c845d113e02ac6e3627b8121db7fb9a35bb4b` | = |
| `app/src/main/java/com/example/eyecontrol/AppFlowState.kt` | — | `4c82633e01a852d295343ff704760ebd067aa51b` | A |
| `app/src/main/java/com/example/eyecontrol/CalibrationActivity.kt` | `3be2ed9c0ec4a5240009334b0ac2e91246fbd022` | `53b3833b8e67b294609e209500b2c9788dc526aa` | M |
| `app/src/main/java/com/example/eyecontrol/CalibrationStore.kt` | `07f3ed283ac39ff7791c9525774956c48d6d33ac` | `07f3ed283ac39ff7791c9525774956c48d6d33ac` | = |
| `app/src/main/java/com/example/eyecontrol/ConditionHistory.kt` | `e8f42bce0897c93e1da15db7e49112527ecf0f6b` | `e8f42bce0897c93e1da15db7e49112527ecf0f6b` | = |
| `app/src/main/java/com/example/eyecontrol/ControlPanelView.kt` | `254a149d90ccfdbfcc72433c5665e3f916e628ec` | `254a149d90ccfdbfcc72433c5665e3f916e628ec` | = |
| `app/src/main/java/com/example/eyecontrol/CrashReporter.kt` | `f5722402f688b7b674151683b8766a5a02bd01ab` | `f5722402f688b7b674151683b8766a5a02bd01ab` | = |
| `app/src/main/java/com/example/eyecontrol/CursorOverlayView.kt` | `43f72c4ca847d6f21fdbee6366ce3b0aa0fd65c8` | `fefe242f3af3fa7f5f7fecacf9fcbd024b5909c8` | M |
| `app/src/main/java/com/example/eyecontrol/DeviceOrientationTracker.kt` | `d42c2565efadbb1fe82adcba2a09532a131cc318` | `d42c2565efadbb1fe82adcba2a09532a131cc318` | = |
| `app/src/main/java/com/example/eyecontrol/DwellTracker.kt` | `fcfc4331245d0de4c504c4c7787b920939216784` | `fcfc4331245d0de4c504c4c7787b920939216784` | = |
| `app/src/main/java/com/example/eyecontrol/EdgeGestureController.kt` | — | `8c22b6489598c9b90f91e4cac03e4a3773e57537` | A |
| `app/src/main/java/com/example/eyecontrol/EyeControlApp.kt` | `738b03e5e7b5f1472148c90d473e4a8ea7db995e` | `738b03e5e7b5f1472148c90d473e4a8ea7db995e` | = |
| `app/src/main/java/com/example/eyecontrol/EyeImageMetrics.kt` | `a5d8038f65a84649d84d60f38362bacaf6fb8e86` | `a5d8038f65a84649d84d60f38362bacaf6fb8e86` | = |
| `app/src/main/java/com/example/eyecontrol/FaceTrackerEngine.kt` | `26e18c77a0c724600e7b409530172f2ba3d4a563` | `26e18c77a0c724600e7b409530172f2ba3d4a563` | = |
| `app/src/main/java/com/example/eyecontrol/GazeAccessibilityService.kt` | `7634d401ebce41e60320429470a173fdbd8f7690` | `f1f056fd4c6fe0d1e495764b7d3d7d79060eeb74` | M |
| `app/src/main/java/com/example/eyecontrol/GazeEvaluator.kt` | `9911a78cf751b497b81bd9fefa11f009a9153cc9` | `9911a78cf751b497b81bd9fefa11f009a9153cc9` | = |
| `app/src/main/java/com/example/eyecontrol/GazeFeatureExtractor.kt` | `3414f4cfcce64e2277431be5aeed5061760a1cc1` | `3414f4cfcce64e2277431be5aeed5061760a1cc1` | = |
| `app/src/main/java/com/example/eyecontrol/GazeLog.kt` | `113a90820a5101203f72c47598f8dcbede3197ce` | `113a90820a5101203f72c47598f8dcbede3197ce` | = |
| `app/src/main/java/com/example/eyecontrol/GazeModel.kt` | `52d33062e687a4a04f7d255a2ea56d59a42a7794` | `52d33062e687a4a04f7d255a2ea56d59a42a7794` | = |
| `app/src/main/java/com/example/eyecontrol/GazeSettings.kt` | `641dcdd8b8506f51acd606eb8974015d763957c2` | `5aa79cd87eb89333fa52c5950f171ce6a262a315` | M |
| `app/src/main/java/com/example/eyecontrol/LightSensorTracker.kt` | `644f45902ac3b0df034d46173aa9eb2008f9fe1f` | `644f45902ac3b0df034d46173aa9eb2008f9fe1f` | = |
| `app/src/main/java/com/example/eyecontrol/MainActivity.kt` | `e674b0ab067ba9a34121408c0403aa681a3ee184` | `c2fec3acbab15c4b943806a6a7c0ad7357530b7c` | M |
| `app/src/main/java/com/example/eyecontrol/OneEuroFilter.kt` | `a79fea5e822dab6dcacee21bce55cf33bc900afd` | `a79fea5e822dab6dcacee21bce55cf33bc900afd` | = |
| `app/src/main/java/com/example/eyecontrol/RecoveryGate.kt` | — | `0eb0c35593c4a3a3eaab81ff569efe799046cea0` | A |
| `app/src/main/java/com/example/eyecontrol/SettingsActivity.kt` | `b8e5f4b5c9cdbf3ade6d978593d225b36dedda42` | `e20588b203d926862b25ddde52f3af18f7f45437` | M |
| `app/src/main/java/com/example/eyecontrol/SetupWizardActivity.kt` | `bd9a0bfc85c3b725512498102b9578b89e3143a7` | `9a7a5f298f98395e44316df004dc148941c75aab` | M |
| `app/src/main/java/com/example/eyecontrol/TrainingActivity.kt` | `dfc28cd6fd2d83aa443ceec17eb5721eaa32ff38` | `dfc28cd6fd2d83aa443ceec17eb5721eaa32ff38` | = |
| `app/src/main/java/com/example/eyecontrol/UpdateChecker.kt` | `1b45ca24374bc84040d2f6c9ec0d10f2360147cc` | `1b45ca24374bc84040d2f6c9ec0d10f2360147cc` | = |
| `app/src/main/java/com/example/eyecontrol/VerifyFrames.kt` | `4e5bc26a1082403ddd1accf576fe20103a1a0f2e` | `f7d83deadbcf42755efb13196a16cb1b8f6ef775` | M |
| `app/src/main/java/com/example/eyecontrol/WelcomeActivity.kt` | `0966ed24426f9159e2026d22a6f910a942d03cc4` | `4502b4c2acdca69217d8f747b7ff1d824580bfd8` | M |
| `app/src/main/res/drawable/chip_bg.xml` | `7ad5be52b243858ca0022bb40ffab4b155987c6c` | `7ad5be52b243858ca0022bb40ffab4b155987c6c` | = |
| `app/src/main/res/drawable/dot_circle.xml` | `31f8530be0bbb25b2ca8b3865a5c51cbd1607614` | `31f8530be0bbb25b2ca8b3865a5c51cbd1607614` | = |
| `app/src/main/res/drawable/ic_eye.xml` | `aa38d6ffc9508fc169c927a2fab0572d9db5620a` | `aa38d6ffc9508fc169c927a2fab0572d9db5620a` | = |
| `app/src/main/res/drawable/ic_eye_mark.xml` | `b63a8355bc22c25f9945e72a5415e433f6736d94` | `b63a8355bc22c25f9945e72a5415e433f6736d94` | = |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | `82bebf0aeb9836cc5d7a570031993e8770f76a3d` | `82bebf0aeb9836cc5d7a570031993e8770f76a3d` | = |
| `app/src/main/res/drawable/num_circle.xml` | `8adf6045ff4de6d6e6161da74a4e743f78796f76` | `8adf6045ff4de6d6e6161da74a4e743f78796f76` | = |
| `app/src/main/res/drawable/preview_bar.xml` | `a7013770849fd767470fe335582d10f66becfd53` | `a7013770849fd767470fe335582d10f66becfd53` | = |
| `app/src/main/res/drawable/preview_box.xml` | `7dce64a2eb918c6e1fcad02dbf73200c42ca6f21` | `7dce64a2eb918c6e1fcad02dbf73200c42ca6f21` | = |
| `app/src/main/res/drawable/preview_ring.xml` | `870d57782234b37ea971b67846bd1e3b5aec4a2a` | `870d57782234b37ea971b67846bd1e3b5aec4a2a` | = |
| `app/src/main/res/layout/activity_main.xml` | `fb5138daba4b95d6212445cf5b2c235abc7759e8` | `7d25ac355598ff41c740598215f3eece5d5de1fd` | M |
| `app/src/main/res/layout/activity_settings.xml` | `26d8d1431f6f47331fc56d2be965b2aa5ef58b7a` | `7c61840f3c04faa227e79685c25a3058803909ca` | M |
| `app/src/main/res/layout/activity_setup_wizard.xml` | `8bdec46f72afc05baa122cc5a8ed4af22d9e5a9c` | `dcb234e4489615c80f804f82db38c0291a7ac789` | M |
| `app/src/main/res/layout/activity_welcome.xml` | `12fab84a0a78430f0e2d8154baaebd25c90c6303` | `c81982507ab3aa3ef4d4e4440edaf8df3dd35deb` | M |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | `c7bd273a68afaead267e93792704073478690f04` | `c7bd273a68afaead267e93792704073478690f04` | = |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | `c7bd273a68afaead267e93792704073478690f04` | `c7bd273a68afaead267e93792704073478690f04` | = |
| `app/src/main/res/values/colors.xml` | `ca6cc7ca62cc574d7d578967681e415f891166e9` | `ca6cc7ca62cc574d7d578967681e415f891166e9` | = |
| `app/src/main/res/values/dimens.xml` | `55201dc333f4a610a56fa0000f719ccb530aefa1` | `55201dc333f4a610a56fa0000f719ccb530aefa1` | = |
| `app/src/main/res/values/strings.xml` | `79da4aa4bc7c5880bd3986e22ae2645a834d5576` | `79da4aa4bc7c5880bd3986e22ae2645a834d5576` | = |
| `app/src/main/res/values/themes.xml` | `c1138dcf05d0aacd3b315cd6c3d1404c8297c26d` | `c1138dcf05d0aacd3b315cd6c3d1404c8297c26d` | = |
| `app/src/main/res/xml/accessibility_service_config.xml` | `45ef12d68a234085df4a90d78f8dbd46bd58e78d` | `45ef12d68a234085df4a90d78f8dbd46bd58e78d` | = |
| `app/src/main/res/xml/file_paths.xml` | `d4d16ee865d1b743a196dd67cc3f23fc7d5a9cd1` | `d4d16ee865d1b743a196dd67cc3f23fc7d5a9cd1` | = |
| `app/src/play/AndroidManifest.xml` | `3f2f9b05d5d244697bf40bb210313f8be4a99e60` | `3f2f9b05d5d244697bf40bb210313f8be4a99e60` | = |
| `app/src/test/java/com/example/eyecontrol/AppFlowStateTest.kt` | — | `1e8cc57509317e47236e76ad49c61d834bde162f` | A |
| `app/src/test/java/com/example/eyecontrol/ConditionHistoryTest.kt` | — | `43476ba27ff909079530d399ce631017544380bc` | A |
| `app/src/test/java/com/example/eyecontrol/DwellTrackerTest.kt` | — | `8bf1defc54661ef8b4c4e28dd0c253975084f8fc` | A |
| `app/src/test/java/com/example/eyecontrol/EdgeGestureControllerTest.kt` | — | `331a13ce25846963fe6cb438a2d24e72b06f8507` | A |
| `app/src/test/java/com/example/eyecontrol/GazeModelMathTest.kt` | — | `eadc2b77502f76d815c37b2b00ce3728adb87857` | A |
| `app/src/test/java/com/example/eyecontrol/OneEuroFilterTest.kt` | — | `714a6393c67fcc68c832637e324c5f1f997ec232` | A |
| `app/src/test/java/com/example/eyecontrol/RecoveryGateTest.kt` | — | `d70bf5d4b7c182a5f71e630000924d42feccc091` | A |
| `build.gradle.kts` | `f3840faef98d9968309fe39cdd3c40e6d2b25b97` | `f3840faef98d9968309fe39cdd3c40e6d2b25b97` | = |
| `docs/ci-validation-report.md` | — | `43ca42bf4ac544a442c21041f5900da68b1a18fc` | A |
| `docs/device-acceptance-matrix.md` | — | `fd9cc1f4ff566bdcbd849e620bcf6d1a83c25635` | A |
| `docs/evaluation.md` | `ac81b7c033ef3aad272bd18d27917e3a3ffbb274` | `ac81b7c033ef3aad272bd18d27917e3a3ffbb274` | = |
| `docs/privacy.html` | `5d8d6869014be389fdb6ddbe3bf218bb2a51d6d3` | `5d8d6869014be389fdb6ddbe3bf218bb2a51d6d3` | = |
| `gradle.properties` | `71e8721766721771f59dceb96492f7ce36921140` | `71e8721766721771f59dceb96492f7ce36921140` | = |
| `gradle/wrapper/gradle-wrapper.jar` | `9bbc975c742b298b441bfb90dbc124400a3751b9` | `9bbc975c742b298b441bfb90dbc124400a3751b9` | = |
| `gradle/wrapper/gradle-wrapper.properties` | `37f853b1c84d2e2dd1c88441fcc755d7f6643668` | `37f853b1c84d2e2dd1c88441fcc755d7f6643668` | = |
| `settings.gradle.kts` | `99e0afd6009d9fae57c5fd9eb5857ef03721c8ae` | `99e0afd6009d9fae57c5fd9eb5857ef03721c8ae` | = |
