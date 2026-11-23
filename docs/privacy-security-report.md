# EyeControl 隱私與資安審查報告（§8）

> 性質：唯讀審查文件。只新增本文件，**不修改任何程式碼、版本號、workflow，不讀取任何 secrets／金鑰／keystore 內容，不轉錄任何金鑰值**。本報告記錄機制與程式位置，金鑰只以「寫死常數」稱之，不抄錄其值；`app/debug-keystore.b64` 的內容本審查未讀取；CI workflow 的 secret 只以「以 GitHub secrets 傳入」稱之，未讀取任何值。
> 基線：`~/workspace/eyecontrol-p3`（repo 根目錄複本，含未推送的 Phase 3 實作）。`app/build.gradle.kts:29-30`：`versionCode = 13`、`versionName = "0.5.9"`。本目錄不是 git 工作樹（`git status` 回報非 git 儲存庫），任務書所稱「遠端 main HEAD `c6b80964`、v0.5.9」無法在本目錄以 git 再驗證，**取任務書與第一輪文件所述為前提，不宣稱為本輪 git 驗證結果**。合併後清單（merged manifest）、實機行為、伺服端設定皆未在本輪編譯／實機驗證，凡屬此類者一律標「待驗證／待定位」，不得宣稱已驗證。
> 行號：除標明「沿用第一輪」者外，皆為本輪以 `grep -n`／`cat -n` 在本樹實測的現況行號。第一輪文件的行號已有漂移（例：`SettingsActivity.saveAndFinish` 第一輪稱 `:285`，本樹實測為 `:278` 起），引用舊文件時不得直接混用行號。
> 複用：本報告複用並擴充第一輪兩份文件——`docs/privacy-data-flow.md`（資料流盤點）與 `docs/privacy-copy-audit.md`（文案稽核），不得重做。文案逐句判定沿用 `privacy-copy-audit.md`，本報告只在資料流事實改變或行號漂移處補充核實；文案本身不在本報告改寫範圍。

## 0. 方法與搜尋範圍

### 0.1 盤點方法

- 全樹 grep：網路呼叫（`HttpURLConnection`、`URL(`、`DownloadManager`、`ACTION_VIEW`、`WebView`）、檔案寫入（`filesDir`、`getExternalFilesDir`、`cacheDir`、`writeText`、`FileOutputStream`、`delete`）、持久化（`SharedPreferences`、`DataStore`、`Room`、`SQLite`）、剪貼簿（`ClipboardManager`）、Logcat（`Log.`）、背景工作（`WorkManager`、`JobScheduler`、`AlarmManager`、`Executor`、`Thread`）、同意機制（`consent`／`Consent`／`revoke`／`pendingUpload`／`generation`）、加密（`EncryptedSharedPreferences`／`KeyStore`／`Cipher`）、截圖／OCR／密碼（`FLAG_SECURE`／`MediaProjection`／`OCR`／`TextRecognizer`／`password`／`AccessibilityNodeInfo`／`getRootInActiveWindow`）。
- 逐檔閱讀呼叫鏈：`GazeLog.kt`、`VerifyFrames.kt`、`FaceTrackerEngine.kt`、`GazeAccessibilityService.kt`（設定監聽、五處 `record`、銷毀路徑）、`GazeSettings.kt`、`CalibrationStore.kt`、`ConditionHistory.kt`、`CrashReporter.kt`、`UpdateChecker.kt`、`SettingsActivity.kt`、`MainActivity.kt`、`EyeControlApp.kt`、`CalibrationActivity.kt`／`TrainingActivity.kt`（引擎實例）、`AndroidManifest.xml` 兩份、`app/build.gradle.kts`、全部 `res/xml` 與 `strings.xml`。
- 每種資料流以同一組問題盤點：只在裝置內處理？寫入本機儲存？送往遠端？何條件送出？是否需要使用者同意？使用者能否拒絕？撤回同意後如何停止（含待上傳佇列的清除或阻止、重新連線時不得偷傳）？保存期限與刪除方式？「同意」指使用者在知情前提下以開關／按鈕／系統授權明確表示接受；**單純「開關預設開啟、使用者未關」不計為明示同意**，本報告如實標示，不美化。
- 判定原則：**不得把 UI 顯示未同意就認定程式不會上傳**。每一項「不上傳」主張皆須從實際呼叫鏈證明（閘門函式、呼叫點、預設參數、執行緒提交點），UI 文案只作對照，不作證據。

### 0.2 零命中清單（全樹 grep 無結果，不得假裝存在）

| 搜尋對象 | 結果 | 意義 |
|---|---|---|
| `WorkManager`／`JobScheduler`／`AlarmManager` | 零命中 | 無背景排程上傳、無延遲上傳工作、無持久化上傳佇列（見 §5.1） |
| `DataStore`／`Room`／`SQLite`／`SQLiteOpenHelper` | 零命中 | 無 DataStore、無 Room、無 SQLite 資料庫（見 §3.11） |
| `consent`／`Consent`／`revoke`（程式識別符） | 零命中 | 無同意紀錄、同意版本、同意時間戳、撤回 API（見 §5.1） |
| `cacheDir`／`getCacheDir` | 零命中 | 程式不使用快取目錄（見 §3.12、§6.2） |
| `EncryptedSharedPreferences`／`KeyStore`／`Cipher`／`encrypt` | 零命中 | 持久化資料皆明文，無加密（見 §3.10） |
| `FLAG_SECURE`／`MediaProjection`／`PixelCopy`／`takeScreenshot` | 零命中 | 無截圖 API、無防截圖旗標（見 §6.3） |
| `OCR`／`TextRecognizer`／密碼輸入型態 | 零命中 | 無 OCR、無密碼欄位（見 §6.1） |
| `getRootInActiveWindow`／`AccessibilityNodeInfo`／`getWindows` | 零命中 | 無障礙服務不讀取視窗內容（見 §3.13） |
| `Firebase`／`Crashlytics`／`Sentry`／`Analytics`／`AdMob` 等 | 零命中 | 無第三方 Crash／Analytics SDK（見 §3.5、§3.6） |
| `BuildConfig.DEBUG`／`Log.isLoggable`（Log 守衛） | 零命中 | 全部 `Log.*` 呼叫皆無 debug 守衛（見 §6.1） |
| `networkSecurityConfig`／`usesCleartextTraffic` | 零命中 | 無自訂網路安全設定檔（見 §3.1） |
| `fullBackupContent`／`dataExtractionRules`／備份規則檔 | 零命中 | 無備份排除規則（見 §8 第三項） |
| `WebView`／`loadUrl`／`Socket`／`OkHttp`／`Retrofit` | 零命中 | 全部網路皆為 `HttpURLConnection`／`DownloadManager`／外部 Intent（見 §3.1） |

## 1. 第一輪結論複用對照表（再驗證結果）

| # | 第一輪結論（來源） | 本輪再驗證 | 現況 |
|---|---|---|---|
| R-1 | 外流只有更新檢查、APK 下載、Supabase 上傳、Play 商店意圖、使用者主動複製、備份六類（`privacy-data-flow.md` §1） | 仍成立，須補兩項：Phase 3 新增的邊緣確認影格擷取點（見 §3.3）與系統備份還原後的開關狀態殘留（見 §4.4）；其餘無新增網路呼叫 | 擴充成立 |
| R-2 | 關閉監控開關不阻止當下 CSV 檔案上傳，`pendingUpload` 機制（`privacy-data-flow.md` §4.2、K-11） | **仍成立，且更精確的機制已確認**：`settingsListener` 不呼叫 `GazeLog.close()`、不重置 `pendingUpload`（見 §5.2）；部分關閉（只關監控、留診斷）時，關閉後寫入的幀亦一併上傳 | 未修復 |
| R-3 | 「不上傳」等絕對字句與上傳程式碼矛盾（`privacy-data-flow.md` §5、K-12；`privacy-copy-audit.md` §1） | 仍成立：三處絕對表述原文仍在（`SettingsActivity.kt:165-167`、`activity_main.xml:760`、`strings.xml:3`），`SetupWizardActivity.kt:219`「關閉即停止」句亦仍在；行號見 §9 | 未修復（文案不在本報告改寫範圍） |
| R-4 | `allowBackup=true` 且無備份排除規則（`privacy-data-flow.md` §6、K-20／S-17） | **仍成立**：`AndroidManifest.xml:15` 仍為 `allowBackup="true"`，全樹仍無排除規則（見 §8 第三項） | 未修復 |
| R-5 | Play 版 `uploadFile` 僅靠間接防護，無 `BuildConfig` 顯式守衛（`privacy-data-flow.md` §3.2、K-45／S-12） | **仍成立**：`GazeLog.kt:174-175` 的 `uploadFile` 入口仍只檢查端點是否空白，無 flavor 守衛；且 Play 版 APK 仍內含上傳程式與寫死金鑰常數（見 §4.3） | 未修復 |
| R-6 | 診斷／監控預設開啟（`privacy-data-flow.md` §0.2，覆蓋舊「預設關」敘述） | 仍成立：`GazeSettings.kt:47,55` 預設值皆 `true`；`GazeSettings.kt:207-208` 的 `getBoolean(..., true)` 只在從未存過 key 時生效；損毀回退 `GazeSettings.kt:218-220` 亦回預設開啟 | 成立，現況不變 |
| R-7 | Play 版仍在本機寫入診斷 CSV 與驗證影格（`privacy-data-flow.md` §3） | 仍成立：`record(enabled = diagnosticLogging \|\| remoteMonitor)` 與 `setVerifyCapture(local = diagnosticLogging, …)` 皆不含 flavor 條件（見 §4.2） | 成立，現況不變 |
| R-8 | 影格路徑比 CSV 路徑嚴格：背景執行緒重查開關，排隊中影格不上傳（`privacy-data-flow.md` §4.3） | 仍成立，但有邊界：已通過重查並提交至 `GazeLog.uploadExecutor` 的影格無法撤回；部分關閉時不 `endSession`、不清空庫存（見 §5.3） | 成立，須加邊界 |
| R-9 | 無持久化上傳佇列、失敗不重試、不補傳（`privacy-data-flow.md` §2.7／§7 T-7） | 仍成立：`uploadExecutor` 為記憶體內單執行緒（`GazeLog.kt:42`），失敗只寫 `Log.w`（`GazeLog.kt:192-193`）；行程死亡即丟失已提交快照（見 §5.5） | 成立，現況不變 |
| R-10 | Phase 3 新增內容的隱私影響未在第一輪盤點（本輪新增） | 本輪已補：新增擷取點只走既有 `requestVerifyCapture` → `VerifyFrames.submit` 鏈，無新增端點、無新增檔案、無新增開關（見 §3.3） | 已補盤點 |
| R-11 | 行號漂移訂正 | `SettingsActivity.saveAndFinish` 本樹為 `:278` 起（第一輪 `:285`）；`SettingsActivity` 隱私對話框本樹為 `:157-171`（第一輪 `:154-171`）；`FaceTrackerEngine.setVerifyCapture` 本樹 `:243-248`；其餘關鍵行號見各節引證，以本報告為準 | 已訂正 |

## 2. 資料流總表

下表為全部外流面的總覽；逐項呼叫鏈與八問判定見 §3。表中「送往遠端」指程式主動發起的網路傳輸，不含使用者主動複製／截圖與系統備份（後兩者另列）。

| # | 外流面 | 只在裝置內 | 寫入本機 | 送往遠端 | 關鍵條件／閘門 |
|---|---|---|---|---|---|
| 1 | 網路請求總覽（3 個 HTTPS 端點） | 否 | 否 | 是（側載版） | 僅 `api.github.com`、`supabase.co`、`play.google.com`（外部 Intent）；無明文 URL（見 §3.1） |
| 2 | Supabase 上傳：診斷 CSV（`gaze_logs`） | 否 | 是（`filesDir/gaze-logs/`） | 是（側載＋監控曾開） | `pendingUpload` OR  latch＋`close(upload=true)`；見 §3.2 |
| 3 | Supabase 上傳：驗證影格（`frame-`） | 否 | 是（`filesDir/verify-frames/`） | 是（側載＋上傳開關當下開） | 背景執行緒重查 `uploadEnabled`；見 §3.3 |
| 4 | 診斷紀錄本機寫檔（CSV＋影格） | 否（條件上傳） | 是 | 間接（經 #2／#3） | 兩開關任一開即寫檔；Play 版亦寫檔（見 §3.4） |
| 5 | Crash Reporting（本機閃退紀錄） | 是（程式層面） | 是（`filesDir/crash-reports/`） | 否（程式不送出） | 僅使用者主動複製／截圖才外流（見 §3.5） |
| 6 | Analytics／第三方追蹤 | —（不存在） | 否 | 否 | 全樹零命中，無 SDK、無呼叫（見 §3.6） |
| 7 | 背景工作佇列（上傳執行緒） | 是（記憶體內） | 否（無持久化佇列） | 經 #2／#3 送出 | 單執行緒、無取消、無 shutdown、失敗不重試（見 §3.7） |
| 8 | 相機影像（全幀 Bitmap） | 是 | 否 | 否 | 三緩衝記憶體內，`stop()` 回收（見 §3.8） |
| 9 | Face Landmarker 輸出（關鍵點） | 是 | 否 | 否 | 純記憶體，不進 CSV、不寫 Log（見 §3.8） |
| 10 | 眼部特徵／感測器／游標座標 | 是（CSV 6 欄例外） | 是（僅 CSV 欄位值） | 是（僅 CSV 欄位值） | 22 維中僅索引 6／7／8 進 CSV（見 §3.9） |
| 11 | 校正資料（模型＋中繼資料） | 是 | 是（SharedPreferences） | 否 | 無刪除 UI、無期限（見 §3.10） |
| 12 | 使用者設定＋條件歷史 | 是（程式層面） | 是（SharedPreferences，明文） | 否（程式不送出；備份面除外） | 6 份 prefs，皆 `MODE_PRIVATE`、無加密（見 §3.10） |
| 13 | 暫存檔／快取／記憶體殘留 | 是 | 部分（見 §6.2） | 否 | 無 `cacheDir`；Bitmap／JPEG 庫存只在記憶體（見 §6.2） |
| 14 | Logcat 內容 | 是（不上傳） | 否（系統 log 緩衝） | 否 | 無 debug 守衛；不含影像／關鍵點／密碼／OCR（見 §6.1） |
| 15 | 無障礙服務能力（視窗內容讀取權） | 是（程式不讀） | 否 | 否 | 宣告 `canRetrieveWindowContent=true`，程式零讀取（見 §3.13） |
| 16 | 剪貼簿／分享（使用者主動） | — | 否 | 經使用者手動 | 僅兩處主動複製按鈕（見 §3.14） |
| 17 | 更新系統（GitHub API＋APK 下載＋商店意圖） | 否 | 是（APK 檔） | 是（側載版／使用者點選） | 無開關可關更新檢查；無雜湊驗證（見 §3.15） |
| 18 | 系統備份／還原（`allowBackup`） | 否（平台行為） | —（備份副本） | 是（經系統備份服務） | 無排除規則；實際內容待實機驗證（見 §3.16） |
| 19 | 第三方 SDK／模型／字型／素材 | 是（裝置端執行） | 是（模型檔在 APK 內） | 否（SDK 本身無呼叫；傳遞行為未驗證） | 無直接 SDK 網路呼叫；授權皆待定位（見 §7） |


## 3. 逐項審查（呼叫鏈與八問判定）

### 3.1 網路請求總覽：只有三個 HTTPS 目的地，無其他網路呼叫

全樹唯一的網路呼叫點（除外部 Intent 外）只有兩處 `HttpURLConnection` 與一處 `DownloadManager`：

- `UpdateChecker.kt:47`：`GET https://api.github.com/repos/shengwen0928/eyecontrol/releases/latest`，`Accept: application/vnd.github+json`、`User-Agent: eyecontrol-updater`，逾時各 5 秒（`UpdateChecker.kt:48-52`）；回應只讀 `tag_name` 與 `assets[].browser_download_url`（`UpdateChecker.kt:55-63`）。
- `GazeLog.kt:179`：`POST <Supabase 端點>/rest/v1/gaze_logs`，逾時各 10 秒，標頭 `apikey`／`Authorization: Bearer`／`Prefer: return=minimal`（`GazeLog.kt:180-188`）。端點常數在 `GazeLog.kt:39`，金鑰為寫死常數（`GazeLog.kt:40` 起，值不轉錄，見 §3.2）。
- `UpdateChecker.kt:104-113`：`DownloadManager` 下載 `browser_download_url`；`UpdateChecker.kt:89-100` 的商店意圖（`market://`／`https://play.google.com/store/apps/details?id=…`）只送出 package name，由 Play 商店／瀏覽器 App 自行連網。

| 問題 | 判定 |
|---|---|
| 只在裝置內處理？ | 否。兩處 `HttpURLConnection` 皆直接對外。 |
| 寫入本機儲存？ | 請求本身不寫檔；下載的 APK 寫入外部私有目錄（見 §3.15）。 |
| 送往遠端？ | 是。請求本體無使用者識別碼、無裝置資訊欄位（更新檢查）；Supabase 請求含時間戳 id（見 §3.2）。網路層可見來源 IP／時間／User-Agent／TLS SNI；伺服端紀錄內容待定位（§10 T-3）。 |
| 何條件送出？ | 更新檢查：側載版全新啟動時（見 §3.15）。Supabase：見 §3.2／§3.3。 |
| 是否需要使用者同意？ | 更新檢查無同意、無開關（見 §3.15）。Supabase 無專門同意對話框，僅以預設開啟的開關為依據（見 §5.4）。 |
| 使用者能否拒絕？ | 更新檢查只能改用 Play 版或斷網。Supabase 可關開關，但撤回語義有缺口（見 §5）。 |
| 撤回後如何停止？ | 見 §5。無持久化佇列，故無「重新連線偷傳已撤回資料」的重試佇列；但已提交至執行緒的任務無法撤回（見 §5.5）。 |
| 保存期限與刪除？ | 請求即發即棄，無本機留存；伺服端保留期限待定位。 |

傳輸安全：全部 URL 皆為 `https://`，全樹無 `http://` 目的地、無自訂 `TrustManager`、無憑證釘選（pinning）、無 `networkSecurityConfig` 設定檔（零命中）。即：傳輸走平台預設 TLS 驗證，**未驗證**是否存在中間人降級風險，本報告不宣稱「已釘選」或「絕對安全」；此為殘餘風險 R-8（見 §9），非已證實的漏洞。

### 3.2 Supabase 上傳（診斷 CSV）：`gaze_logs` 表，`pendingUpload` latch 機制

**呼叫鏈（CSV 路徑）**：`GazeAccessibilityService` 五處 `GazeLog.record(enabled = diagnosticLogging || remoteMonitor, remoteUpload = remoteUploadEnabled(), …)`（`GazeAccessibilityService.kt:528,633,672,1315,1365`，含臉遺失／降級／RECOVERY 分支）→ `GazeLog.record()`（`GazeLog.kt:65` 起）：`enabled=false` 時呼叫 `close()` 後返回（`GazeLog.kt:84-86`）；開新檔時 `pendingUpload = false`（`GazeLog.kt:101`），每幀 `pendingUpload = pendingUpload || remoteUpload`（`GazeLog.kt:106`）；滿 120 秒關檔（`GazeLog.kt:110-113`）→ `GazeLog.close(upload=true)`（`GazeLog.kt:139`）：`doUpload = upload && pendingUpload && file != null && rows > 0`（`GazeLog.kt:141`），快照整份 CSV 文字後 `uploadExecutor.submit { uploadFile(...) }`（`GazeLog.kt:154-158`）→ `uploadFile()`（`GazeLog.kt:174` 起）POST 至 Supabase。`remoteUpload` 的真值來自 `remoteUploadEnabled() = settings.remoteMonitor && BuildConfig.REMOTE_MONITOR_ENABLED`（`GazeAccessibilityService.kt:75-76`）。

**負載**：JSON `{"id": <檔名去副檔名>, "rows": <筆數>, "csv": <整份 CSV>}`（`GazeLog.kt:177`）。CSV 表頭 17 欄（`GazeLog.kt:31`）：`t,face,iod,pitch,yaw,rawX,rawY,gainX,gainY,curX,curY,sens,offX,offY,lux,imgQ,glassesHint`。不含影像、不含完整特徵向量、不含身分識別碼欄位；但 `id` 為時間戳檔名（`gaze-<yyyy-MM-dd_HH-mm-ss-SSS>-<流水號>`，`GazeLog.kt:92-94`），伺服端接收時間與來源 IP 可關聯多次上傳至同一裝置／使用者——此關聯性未在現有文案中揭露（沿用 `privacy-copy-audit.md` §2.6 判定）。是否構成「身分資訊」屬法律／政策判斷，本報告只記錄欄位事實，不代為定性。

**金鑰機制（值不轉錄）**：Supabase anon 金鑰為寫死在 `GazeLog.kt:40` 起的原始字串常數，隨原始碼編譯進**兩個 flavor 的 APK**（程式碼共用，flavor 閘門是執行期 `BuildConfig` 檢查，不是原始碼移除；見 §4.3）。程式註解稱 RLS 只允許 INSERT、讀取只用 service_role 金鑰且該金鑰絕不進 App（`GazeLog.kt:33-37`）。**RLS 主張無法由本樹驗證，待定位**（§10 T-1）：本樹無任何伺服端設定檔，須以 Supabase 控制台驗證並留存紀錄。金鑰寫死意味：任何取得 APK 者皆可解出該常數；其實際風險完全取決於未驗證的 RLS 設定，本報告不宣稱「金鑰外洩即資料外洩」或「金鑰安全」，只記錄「防護單點依賴 RLS」的事實。

| 問題 | 判定 |
|---|---|
| 只在裝置內處理？ | 否。本機寫檔與遠端上傳共用同一檔案、同一開關組。 |
| 寫入本機儲存？ | 是。`filesDir/gaze-logs/`（`GazeLog.kt:27,91`）。只開 `remoteMonitor`、關 `diagnosticLogging` 時檔案仍寫入（OR 邏輯）；只開 `diagnosticLogging` 時檔案寫入但 `pendingUpload` 永不為真、關檔不上傳。 |
| 送往遠端？ | 是，有條件。檔案期間**任何一幀**處於監控開啟狀態，且關檔時 `upload` 參數為真（預設 `close(upload=true)`），整份檔案即上傳。關檔觸發點：滿 120 秒輪轉、下一幀 `enabled` 轉 false、服務 `onDestroy`（`GazeAccessibilityService.kt:1618` 的 `GazeLog.close()`，預設上傳）、寫入失敗（`GazeLog.kt:136` 的 `onFailure { close() }`）。 |
| 是否需要使用者同意？ | 無專門同意對話框。預設開啟（`GazeSettings.kt:47,55`、`GazeSettings.kt:207-208`）；設定頁副標說明用途，但開啟本身不彈確認。 |
| 使用者能否拒絕？ | 設定頁關閉兩開關並按儲存（`SettingsActivity.kt:278` 起的 `saveAndFinish`）；Play 版無此資料流（見 §4）。 |
| 撤回後如何停止？ | **不完全有效**：當下檔案仍會上傳（見 §5.2 完整時序）。唯一丟棄當下檔案的路徑是「清除紀錄」（`GazeLog.clearAll`，`GazeLog.kt:222-225`，`close(upload=false)`＋刪檔）。 |
| 保存期限與刪除？ | 120 秒一段、自動輪轉；開新檔時只留最近 3 份（`MAX_FILES=3`，`GazeLog.kt:28,104`，**只在開新檔時修剪**，停止使用後舊檔不自動消失）。已上傳資料無 App 內刪除、無 DELETE 呼叫；伺服端保留期限與刪除流程待定位（§10 T-2）。 |

### 3.3 Supabase 上傳（驗證影格）：`frame-` 列，與 CSV 共用端點與表

**呼叫鏈（影格路徑）**：`FaceTrackerEngine` 結果回呼內的 `maybeCaptureVerifyFrame`（有臉幀才取裁切；優先序：退化事件＞服務端事件請求＞臉部重現＞週期 1Hz）→ `captureEyeCrop` → `verifyFrames.submit(crop, meta)`（`FaceTrackerEngine.kt:355`）→ `VerifyFrames.submit()`（`VerifyFrames.kt:72` 起）：開關全關、額滿或佇列已滿時直接丟棄並回收 Bitmap；否則交背景單執行緒 `worker` 壓 JPEG → `storeAndUpload()`（`VerifyFrames.kt:173` 起）：**執行當下重查** `localEnabled`（`VerifyFrames.kt:182`，且要求 `sessionId == id`）與 `uploadEnabled`（`VerifyFrames.kt:188`），上傳時以 base64 JPEG 包進 JSON 經 `GazeLog.uploadFrame()`（`VerifyFrames.kt:190`）→ `GazeLog.kt:169-171` 提交至同一 `uploadExecutor` → 同一 `uploadFile()` POST（`rows=0`、`csv` 欄位放影格 JSON，`id` 前綴 `frame-`，`VerifyFrames.kt:254-281` 的 `buildFrameJson`）。

**開關來源**：`applyVerifyCaptureFlags()`（`GazeAccessibilityService.kt:79-84`）→ `engine?.setVerifyCapture(local = settings.diagnosticLogging, upload = remoteUploadEnabled())` → `FaceTrackerEngine.setVerifyCapture()`（`FaceTrackerEngine.kt:243-248`）。**校正頁與訓練頁的引擎實例不設開關**：全樹 grep，`CalibrationActivity.kt` 與 `TrainingActivity.kt` 皆無 `setVerifyCapture` 呼叫，`VerifyFrames` 預設 `localEnabled=false`／`uploadEnabled=false`（`VerifyFrames.kt:36-39`），故校正與訓練流程不產生影格；`FaceTrackerEngine.kt:71-76` 註解與此一致。

**Phase 3 新增擷取點（本輪補盤點）**：服務端事件請求新增二階段確認相關種類——`CAPTURE_EDGE_CONFIRM_SHOW`／`CAPTURE_EDGE_CONFIRM_CANCELLED`（`VerifyFrames.kt:326,329`），呼叫點在 `GazeAccessibilityService.kt:855,866,890,911,947,954,1258`（`requestVerifyCapture`）。**無新增端點、無新增檔案、無新增開關**：全部走既有 `requestVerifyCapture`（`FaceTrackerEngine.kt:256` 起，佇列上限 8 筆、逾時 2 秒丟棄）→ `VerifyFrames.submit` 鏈，受同一組開關與同一重查機制約束。新增的只是「何時請求一張影格」，不是新的資料流。

**負載與裁切範圍**：眼區裁切為雙眼外框聯集（`EyeImageMetrics.cropEyes`，沿用第一輪定位 `EyeImageMetrics.kt:100` 起；本輪再讀確認函式與「只裁眼區、絕不含全臉」註解仍在），上限 320px 寬、JPEG 品質 70、單張上限 32KB（`VerifyFrames.kt:301-308`）。影格 JSON 含 `jpeg_b64` 與 meta：`t`、`lux`、`iod`、`pitch`、`yaw`、`glassesHint`、`degraded`、`dwellProgress`、`deviceRoll`、`contrast`、`frameTs`、`reqT`、`csvSession`、`lastGood`（`VerifyFrames.kt:261-279`）。**注意**：影格是眼睛影像（生物特徵影像），即使只是雙眼裁切，其敏感度高於純數字 CSV；本報告不以「不含全臉」淡化此點。

| 問題 | 判定 |
|---|---|
| 只在裝置內處理？ | 否（條件上傳）。 |
| 寫入本機儲存？ | 是。`filesDir/verify-frames/<時段id>/frame-<序號>.jpg`（`VerifyFrames.kt:184-185`）。時段 120 秒、每時段最多 60 張、本機只留最近 3 個時段（`VerifyFrames.kt:292,295,298`，`pruneSessions()` 於 `VerifyFrames.kt:195` 起，只在 `endSession`／新時段時執行）。 |
| 送往遠端？ | 是，有條件。上傳檢查發生在背景執行緒執行 `storeAndUpload` 的當下，不是擷取當下；關開關後已排隊但尚未執行到檢查的影格不會上傳（見 §5.3 對照）。已通過檢查並提交至 `GazeLog.uploadExecutor` 的影格無法撤回。 |
| 是否需要使用者同意？ | 無專門同意對話框；與 CSV 共用開關，預設開啟。 |
| 使用者能否拒絕？ | 同 CSV。Play 版不上傳（見 §4），但仍留存本機影格。 |
| 撤回後如何停止？ | **較 CSV 嚴格，但不完整**：排隊中影格受重查保護；已寫入的本機影格不受關開關影響、**無任何刪除路徑**（`GazeLog.clearAll()` 不清 `verify-frames/`；全樹無 `VerifyFrames` 的 `clearAll`）；已上傳影格無 App 內刪除。部分關閉時不 `endSession`（見 §5.3）。 |
| 保存期限與刪除？ | 無時間期限，只有數量／時段上限與淘汰；無 UI 清除按鈕。刪除只能靠解除安裝、清除 App 資料，或時段淘汰（且淘汰只在新時段／`endSession` 時發生）。 |

### 3.4 診斷紀錄本機寫檔（CSV＋影格）：Play 版亦寫檔

見 §3.2／§3.3 的寫檔面。補充一點第一輪已指出、本輪再確認的關鍵不對稱：**「不上傳」≠「不記錄」**。Play 版的 `record()` 與 `setVerifyCapture()` 呼叫皆不含 flavor 條件（見 §4.2），故 Play 版在預設開啟的 `diagnosticLogging` 下，仍持續寫入診斷 CSV 與驗證影格到 `filesDir`，只差在不上傳。任何稱 Play 版「不記錄／不儲存診斷資料」的敘述皆失實（文案判定沿用 `privacy-copy-audit.md`）。

寫入節流：CSV 10Hz（`MIN_INTERVAL_MS=100`，`GazeLog.kt:30`）、每 50 行 flush（`GazeLog.kt:135`）；影格週期取樣 1Hz、退化事件 5 秒內不重複（`FaceTrackerEngine.kt` 內常數，沿用第一輪；本輪確認取樣邏輯未變）。`GazeLog.summarize()`（`GazeLog.kt:236` 起）讀最新檔算摘要，僅在使用者主動點「複製摘要」時經剪貼簿外流（`SettingsActivity.kt:354-356`）。

### 3.5 Crash Reporting：本機閃退紀錄，無遠端回報服務

**呼叫鏈**：`EyeControlApp.onCreate()` 安裝 `CrashReporter.install()`（`EyeControlApp.kt:7-10`、`CrashReporter.kt:23-29`）：未攔截例外先寫檔、再交回原處理器；已攔截錯誤以 `recordCaught` 寫檔（`CrashReporter.kt:32-34`），呼叫點僅三處——校正頁引擎啟動失敗（`CalibrationActivity.kt:335`）、校正完成失敗（`CalibrationActivity.kt:686`）、服務引擎啟動失敗（`GazeAccessibilityService.kt:416`）。`writeReport()`（`CrashReporter.kt:36-51`）寫入 `filesDir/crash-reports/crash-<秒級時間戳>.txt`，內容：時間、種類、執行緒、App 版本、裝置廠牌／型號／Android 版本、完整 stack trace（`CrashReporter.kt:42-47`）。

| 問題 | 判定 |
|---|---|
| 只在裝置內處理？ | 是（程式層面）。`CrashReporter` 全檔無任何網路呼叫。 |
| 寫入本機儲存？ | 是，自動寫檔、無事前同意、無開關可關閉閃退寫檔。只留最近 5 份（`MAX_FILES=5`，`CrashReporter.kt:18,49`）。檔名秒級，同秒兩次崩潰後者覆寫前者（殘留，見 §9 R-11）。 |
| 送往遠端？ | 否（程式不送出）。外流只發生在使用者主動行為：下次開啟時對話框顯示前 4000 字（`MainActivity.kt:247-272`）、可「複製紀錄」經剪貼簿、可截圖。 |
| 是否需要使用者同意？ | 寫檔本身無同意；外流需使用者親點複製／截圖。 |
| 撤回／刪除？ | `clearAll()` 刪檔（`CrashReporter.kt:66-68`），但**不清 `shown_*` 旗標**（`CrashReporter.kt:57-64` 的 `crash_report` prefs）；清除後 `pendingReport()` 的「已顯示」判斷仍以殘留旗標為準（檔名相同時不再提示——實務影響有限，但屬狀態不一致殘留）。 |
| 保存期限？ | 只留 5 份，無時間期限；備份面見 §3.16。 |

**文案限縮**：`CrashReporter.kt:14` 檔頭稱「不含影像與任何個人資料」須限縮——裝置型號屬裝置資訊，stack trace 可能含程式路徑與例外訊息（例外訊息內容取決於拋出點，本審查未逐一枚舉三處 `recordCaught` 的例外訊息，故不宣稱「絕不含使用者資料」）。程式不上傳屬實；「任何個人資料」一語過寬（沿用 `privacy-copy-audit.md` §2.7 判定）。

### 3.6 Analytics：不存在

全樹 grep `Firebase`／`Crashlytics`／`Sentry`／`Analytics`／`AdMob`／`AppsFlyer`／`Amplitude`／`Mixpanel` 皆零命中；`app/build.gradle.kts:122-135` 的依賴清單中亦無任何 Analytics／追蹤 SDK。**結論：本 App 無 Analytics、無第三方追蹤、無行為分析上傳。** 但須與 §3.2 區分：Supabase 診斷上傳是開發者自有端點的調校資料流，不是第三方追蹤 SDK；文案不得以「無第三方追蹤」暗示「無對外傳輸」（沿用 `privacy-copy-audit.md` §2.5 判定）。

**傳遞性保留**：本審查未取得依賴樹／SBOM（樹內無 lockfile、無 SBOM 檔案），無法證實 CameraX／MediaPipe 等依賴的傳遞依賴在執行期絕不發起網路呼叫；程式碼層面無此類呼叫，執行期傳遞行為**待定位**（§10 T-6）。本報告不宣稱「全依賴樹零網路行為」。

### 3.7 背景工作佇列：兩個記憶體內單執行緒，無持久化佇列、無取消、無 shutdown

| 佇列 | 位置 | 性質 | 關閉／取消機制 |
|---|---|---|---|
| `GazeLog.uploadExecutor` | `GazeLog.kt:42`（`Executors.newSingleThreadExecutor()`） | 記憶體內、無界佇列；CSV 快照（整份 CSV 字串）與影格 JSON 皆經此提交（`GazeLog.kt:157,170`） | **永不 shutdown**（全樹 grep `shutdown` 在 `GazeLog.kt`／`VerifyFrames.kt` 零命中）；**無取消 API、無佇列檢視、無 purge**；已提交任務無法撤回（見 §5.5）。失敗只寫 Log、不重試、不通知（`GazeLog.kt:192-193`）。 |
| `VerifyFrames.worker` | `VerifyFrames.kt:49`（同上） | 記憶體內單執行緒；`inFlight` 上限 4（`VerifyFrames.kt:50,81,105,311`）作背壓，超量新影格直接丟棄 | 同上：永不 shutdown；但 `storeAndUpload` 執行時重查開關（見 §3.3），故未執行的排隊任務受開關保護。 |
| `FaceTrackerEngine.analysisExecutor` | `FaceTrackerEngine.kt:45,196` | 相機分析執行緒 | `stop()` 內 `shutdown()`＋`awaitTermination(500ms)`（`FaceTrackerEngine.kt:526-529`，本輪實測行號）。此為唯一有 shutdown 的執行緒。 |
| `UpdateChecker` 檢查執行緒 | `UpdateChecker.kt:36-44` | daemon 執行緒，即發即棄 | 無佇列、無取消；失敗靜默返回。 |
| `MainActivity.statusPoller` | `MainActivity.kt:44-49` | 主執行緒狀態輪詢（500ms 級，見檔案內常數） | `onPause` 移除回呼（`MainActivity.kt:288-291`）；不觸及任何資料。 |

**無 WorkManager／JobScheduler／AlarmManager**（零命中，見 §0.2）。因此不存在「系統排程在 App 關閉後、斷網重連後自動補傳」的機制；反之，也不存在「使用者可檢視／逐筆取消待上傳內容」的機制（見 §5.4 G-7）。

### 3.8 相機影像與 Face Landmarker 輸出：只在裝置內

**處理鏈**：`FaceTrackerEngine.start()`（`FaceTrackerEngine.kt:122` 起，沿用第一輪定位）→ `ImageAnalysis` 1280×720、`OUTPUT_IMAGE_FORMAT_RGBA_8888` → `analyze()` → `toBitmap()`（三緩衝複用，`cachedRotatedBmps`，`FaceTrackerEngine.kt:47`）→ `detectAsync`（`FaceTrackerEngine.kt` 內，`detectAsync 失敗` Log 在 `:283`）→ 結果回呼內的 `lookupFrameMetrics`／`captureEyeCrop`。模型載入：`BaseOptions.setModelAssetPath("face_landmarker.task")`（`FaceTrackerEngine.kt:133`），`LIVE_STREAM` 模式、僅 1 張臉。

- **全幀影像**：只在裝置內處理；`toBitmap` 產出的 Bitmap 只存在三緩衝槽與中間緩衝 `cachedBmp`（`FaceTrackerEngine.kt:46-47`），`stop()`（`FaceTrackerEngine.kt:506-543`）回收全部緩衝並將幀槽清為 `bitmap=null`、`timestampMs=-1`（`FaceTrackerEngine.kt:514-518`）。全樹無全幀寫檔、無全幀上傳呼叫。**例外只有眼區裁切**（見 §3.3），必須與全幀分開陳述。
- **Face Landmarker 輸出**：首張臉關鍵點清單只在結果回呼執行緒內使用，餵給量測、特徵提取與影格擷取。無寫檔、無上傳、無 Log 輸出關鍵點座標。關鍵點本身不進 CSV（CSV 只含特徵索引 6／7／8 三個衍生數值，見 §3.9）。
- **權限守衛缺口（非資料流本身，但屬同一信任邊界）**：`FaceTrackerEngine.start()` 本身不檢查相機權限（全檔無 `checkSelfPermission`），依賴呼叫端守衛；呼叫端檢查見 `MainActivity.kt:318-320`、`SettingsActivity.kt:114,134`、`SetupWizardActivity.kt:401`、`TrainingActivity.kt:101`。本報告只記錄資料流事實：無權限時 CameraX 綁定失敗只寫 Log（`FaceTrackerEngine.kt:234`），不產生影像資料流。

### 3.9 眼部特徵、感測器快照與游標座標：22 維中僅 6 個值進 CSV

- **特徵向量**：`GazeFeatureExtractor.extract()`（`GazeFeatureExtractor.kt:69`，`DIM = 22`，`GazeFeatureExtractor.kt:67`）產出的 22 維向量；`GazeSample`（`GazeFeatureExtractor.kt:19-30`）另附感測器快照（`devicePitch`／`deviceRoll`、`ambientLux`）與影像度量（`eyeMeanLum`／`eyeContrast`／`specularRatio`）。感測器來源：`DeviceOrientationTracker`（`TYPE_GAME_ROTATION_VECTOR` 優先，否則 `TYPE_ROTATION_VECTOR`，`DeviceOrientationTracker.kt:42-45`）與 `LightSensorTracker`（`TYPE_LIGHT`，`LightSensorTracker.kt:31-33`），兩者皆只提供記憶體快照，無持久化、無寫檔（兩檔全檔無 `SharedPreferences`／`filesDir` 寫入呼叫）。
- **進 CSV 的只有 6 個欄位值**：特徵索引 6（iod）、7（pitch）、8（yaw）（`GazeLog.kt:118-120`）與 `lux`／`imgQ`／`glassesHint`（`GazeLog.kt:127-129`），以及三段座標 `rawX／rawY／gainX／gainY／curX／curY` 與當下靈敏度／偏移（`GazeLog.kt:121-126`）。其餘 19 維特徵不進 CSV、不存檔、不上傳。校正期間的特徵只存在 `CalibrationActivity` 記憶體串列中，取消／重建即丟失（見 §3.10 暫存段）。
- **同意／撤回**：與 CSV 共用同一組開關，無特徵專屬開關。關閉兩開關後，下一幀 `record(enabled=false)` 即關檔不寫入（但當下檔案仍上傳，見 §5.2）。

### 3.10 校正資料、使用者設定與條件歷史：6 份明文 SharedPreferences，無加密、無刪除 UI

全部皆 `MODE_PRIVATE` SharedPreferences，明文 XML（平台預設格式），**無加密**（`EncryptedSharedPreferences` 零命中）。無 `DataStore`、無 `Room`、無 `SQLite`（零命中）。

| prefs 檔 | 內容 | 位置 | 上傳？ | 刪除方式 |
|---|---|---|---|---|
| `gaze_settings` | dwell／冷卻／半徑／眨眼／濾波／靈敏度／陀螺儀／臉距／條件修正／診斷／監控／虹膜精修／邊緣手勢 5 開關 | `GazeSettings.kt:127`（`PREFS_NAME`）、`save()` `:101-124`、`load()` `:181-221` | 否（程式不送出；CSV 只記當下 `sens／offX／offY` 數值，不是整份設定檔） | 無刪除 UI；清除 App 資料／解除安裝（備份面見 §3.16） |
| `calibration_profiles` | `active_profile`＋`model_<id>`／`meta_<id>`（模型 JSON：`wx／wy` 權重、`basePitch／baseYaw／kx／ky／baseIod`、選填裝置基準、`version`；中繼資料 `calibratedAt`、`looErrorPx`） | `CalibrationStore.kt:16,34-36`，`saveModel()` `:79-92`，`prefs()` `:38-39` | 否 | **無刪除全部校正資料的 UI**（`SettingsActivity.kt:28-29` 註解明載「清除全部校正資料…一律不顯示」）；覆蓋方式只有重新校正同一情境。`calibratedAt` 只記錄、不觸發過期。舊版 `gaze_model` 僅遷移讀取、舊檔保留不刪（`GazeModel.kt:116-117` 的 `PREFS`／`KEY`，`CalibrationStore.kt:98-115` 的 `migrateIfNeeded`；遷移失敗亦不再重跑）。 |
| `condition_history` | 每情境最多 50 筆 `records_<id>`＋基準 `reference_<id>`；內容為條件向量（亮度桶、pitch／roll、iod、反光指標）＋偏移 | `ConditionHistory.kt:204-205`（`PREFS_NAME`／前綴）、`MAX_RECORDS_PER_PROFILE=50`（`:39`）、`loadRecords` `:208`、`addRecord` `:217`、`clearHistory` `:229`、`saveReference` `:241` | 否 | 完整重校存檔成功後才清空（`CalibrationActivity.kt:736`）；取消時不得清空。無時間過期、無單獨刪除 UI。`conditionCorrection` 關閉時不讀不寫（檔頭註解）。 |
| `eyecontrol_setup` | `welcome_seen`（布林） | `WelcomeActivity.kt:48-49,52-57` | 否 | 同上（清除 App 資料／解除安裝）。 |
| `crash_report` | `shown_<檔名>`（閃退對話框已顯示旗標） | `CrashReporter.kt:19-20,57-64` | 否 | `clearAll()` 不清旗標（見 §3.5）。 |
| `gaze_model`（舊） | 舊單一模型 JSON | `GazeModel.kt:116-117`，`save()` `:111`（僅舊版路徑） | 否 | 遷移後保留不刪。 |

**校正中暫存**：`allFeatures`／`allTargets`／`allDeviceAngles`、`pointBuffer`、校正亮度／反光樣本等只存在 `CalibrationActivity` 記憶體中，取消／重建即丟失，不寫入任何持久化位置。`onSaveInstanceState` 只存兩個整數檢查點（`CalibrationActivity.kt:259-263`：`pointIndex`、`committedPoints`），重建後從第 1 點重採。**`GazeSettings.save()` 不先 `clamped()`**（`SettingsActivity.saveAndFinish` 在呼叫前 `clamped()`，`SettingsActivity.kt:278` 起）；損毀回退時兩監控開關回預設開啟（`GazeSettings.kt:218-220` 的 `catch` 回 `GazeSettings()`，見 §1 R-6）。

**設定寫入的 flavor 殘留**：Play 版 `switchRemote` 整列隱藏（`SettingsActivity.kt:77-78`），但 `saveAndFinish` 仍讀取 `binding.switchRemote.isChecked` 寫入 prefs（`SettingsActivity.kt:293`）。隱藏列的 checked 狀態即載入值，故 Play 版儲存時會原樣保留既有 `remote_monitor` 值（例如經備份還原帶入的 `true`）；Play 版不上傳仍靠 `BuildConfig` 閘門（見 §4.3），不是靠「值已清除」。此為殘留風險 R-6（見 §9）。

### 3.11 DataStore／SQLite／Room：不存在

全樹 grep 皆零命中（見 §0.2）。本 App 的結構化持久化只有 SharedPreferences（見 §3.10）與 `filesDir` 下的三類檔案（見 §3.4／§3.5）。**無資料庫檔案、無資料庫備份／匯出路徑。**

### 3.12 快取：程式不使用快取目錄

`cacheDir`／`getCacheDir` 全樹零命中。無 HTTP 快取設定、無圖片快取、無 `Cache` 物件。唯一的類暫存寫入是：下載的 APK（外部私有目錄，見 §3.15）、`filesDir` 下的三類診斷檔案（見 §3.4／§3.5），以及記憶體內暫存（見 §6.2）。**無「清除快取即清除診斷資料」的路徑**：系統「清除快取」不影響 `filesDir` 內容；診斷 CSV 只能以「清除紀錄」刪除，驗證影格無 UI 刪除路徑。

### 3.13 無障礙服務能力：宣告過度，程式零讀取

宣告（`app/src/main/res/xml/accessibility_service_config.xml:3-8`）：`description` 引用 `strings.xml` 的 `accessibility_desc`、`accessibilityEventTypes="typeAllMask"`、`accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"`、`canPerformGestures="true"`、`canRetrieveWindowContent="true"`、`notificationTimeout="100"`。服務宣告 `android:exported="true"` 但受 `BIND_ACCESSIBILITY_SERVICE` 權限保護（`AndroidManifest.xml:68-79`）。

程式行為：`onAccessibilityEvent`／`onInterrupt` 為空實作（`GazeAccessibilityService.kt:1634-1635`）；全樹無 `AccessibilityNodeInfo`／`getRootInActiveWindow`／`getWindows` 呼叫。**程式碼不讀取、不記錄其他 App 的畫面內容、無 OCR、無螢幕文字擷取。** 但宣告本身賦予的能力（`canRetrieveWindowContent=true`＋`typeAllMask`）大於程式實際使用的能力，屬宣告面缺口：若未來程式改動加入節點讀取，宣告上已無需再經使用者重新授權。本報告只記錄資料流事實（**無畫面內容資料流**），宣告收斂屬程式修正範圍，不在本報告代決。另：服務以 `dispatchGesture` 注入點擊／滑動（`GazeAccessibilityService.kt:1554,1566,1585,1600`），屬控制面（代替手指操作），不產生畫面內容資料流；注入了什麼手勢、點了哪個 App，程式不記錄（CSV 只記游標座標，不記目標 App／點擊結果）。

### 3.14 剪貼簿與分享：僅兩處使用者主動複製

- 閃退紀錄「複製紀錄」：`MainActivity.kt:261-264`（`ClipData.newPlainText("閃退紀錄", …)`）。
- 診斷摘要「複製摘要」：`SettingsActivity.kt:354-356`（`ClipData.newPlainText("診斷摘要", …)`）。

程式不自動寫入剪貼簿、不讀取剪貼簿（`getPrimaryClip` 零命中）、無 `ACTION_SEND` 分享意圖、無其他 App 間資料傳遞。外流完全取決於使用者複製後的後續行為（貼上、傳送），程式不追蹤。通知內容亦不含資料：前台服務通知只寫固定文字「眼控輔助服務已啟動／正在使用前鏡頭進行眼動追蹤」（`GazeAccessibilityService.kt:376-380`）；Toast 皆為固定提示文字或狀態原因（`MainActivity.kt:185` 的 `$reason` 為流程狀態原因，非 gaze 資料）。

### 3.15 更新系統：側載版更新檢查無開關、無雜湊驗證；Play 版改跳商店

**呼叫鏈（側載版）**：`MainActivity.onCreate` 僅全新啟動時（`savedInstanceState == null`，`MainActivity.kt:240`）→ `checkForUpdate()`（`MainActivity.kt:299-316`）：`BuildConfig.SELF_UPDATE_ENABLED` 為 false 時直接返回（`MainActivity.kt:301`）→ `UpdateChecker.checkForUpdate`（`UpdateChecker.kt:36-44`，daemon 執行緒查 GitHub API）→ 有新版時主執行緒彈對話框，使用者點「下載更新」→ `UpdateChecker.startDownload`（`UpdateChecker.kt:103-114`，`DownloadManager`，目的地 `getExternalFilesDir(DIRECTORY_DOWNLOADS)/eyecontrol-debug.apk`，`UpdateChecker.kt:117-118`，通知可見）→ `MainActivity.downloadReceiver`（`MainActivity.kt:51-89`，`RECEIVER_NOT_EXPORTED`，`MainActivity.kt:234-238`）僅查 `STATUS_SUCCESSFUL`＋位元組數 → `UpdateChecker.installApk`（`UpdateChecker.kt:124-142`，經 FileProvider 跳安裝畫面；未允許安裝未知來源時先開系統設定）。

| 問題 | 判定 |
|---|---|
| 只在裝置內處理？ | 否。更新檢查請求與 APK 下載皆對外（GitHub API／CDN）。 |
| 寫入本機儲存？ | 是。APK 寫入 App 私有外部目錄，**安裝成功後不刪除**（僅下載失敗刪殘檔，`MainActivity.kt:71-74`）；隨 App 解除安裝刪除；下次下載覆寫同一固定檔名。 |
| 送往遠端？ | 是。請求 metadata（URL、IP、時間、User-Agent）可見於 GitHub／CDN 側；伺服端紀錄待定位。請求本體無使用者識別碼、無裝置資訊欄位。 |
| 是否需要使用者同意？ | 檢查本身無同意、**無 App 內開關可關閉**（`SettingsActivity.kt:28-29` 註解明載「更新檢查開關一律不顯示」）；下載與安裝皆須使用者親點（對話框＋系統安裝畫面），無靜默更新。Play 版以 `BuildConfig` 閘門不發請求（`MainActivity.kt:301`）。 |
| 撤回後如何停止？ | 不適用（無開關）；每次全新啟動都會再查。下載後不安裝即不生效。 |
| 保存期限與刪除？ | 單一固定檔名檔案，無版本區分、無期限、無 UI 刪除按鈕。 |
| 完整性缺口 | **無 checksum／簽名驗證**：`downloadReceiver` 只查狀態與位元組數，`installApk` 前無雜湊比對（`MainActivity.kt:51-89`、`UpdateChecker.kt:124-142` 全段無雜湊呼叫）。信任完全依賴 HTTPS＋GitHub API 回應的 `browser_download_url`。此為供應鏈殘餘風險 R-7（見 §9），非已證實的竄改事件。 |

**Play 版**：設定頁「到 Play 商店檢查更新」列僅 Play 版顯示（`SettingsActivity.kt:188-192`：`SELF_UPDATE_ENABLED` 為真時 `GONE`），點選後 `UpdateChecker.openStoreListing`（`UpdateChecker.kt:89-100`）先 `market://`、失敗退回網頁版，只送出 package name。Play 版不宣告 `INTERNET`（見 §4），商店／瀏覽器 App 自行連網——那是使用者主動觸發的外部 App 行為，不是本 App 的網路請求。

**`UpdateChecker.checkForUpdate` 本身無 flavor 守衛**：防護只靠唯一呼叫端 `MainActivity.checkForUpdate` 的 `BuildConfig` 檢查（`MainActivity.kt:301`）。與 `uploadFile` 同構的「呼叫端閘門」模式（見 §4.3），列為結構性殘留，不單獨計為資料流缺口。

### 3.16 系統備份／還原：`allowBackup=true`，無排除規則（K-20 殘留，見 §8）

宣告：`AndroidManifest.xml:15`，`android:allowBackup="true"`。排除規則：**無**——全樹無 `android:fullBackupContent`、`android:dataExtractionRules`，`app/src/main/res/xml/` 僅有 `accessibility_service_config.xml` 與 `file_paths.xml`。依 Android 預設備份行為（平台行為，**實際備份內容待實機／裝置驗證**，本樹程式碼無法證實哪些檔案實際被備份服務取走）：以下 App 私有資料原則上在備份範圍內——6 份 SharedPreferences（含校正模型、條件歷史、設定、`welcome_seen`、`shown_*` 旗標）、`filesDir/` 下的 `gaze-logs/`、`verify-frames/`、`crash-reports/`。`getExternalFilesDir()` 下的 APK 不在自動備份範圍內（外部私有目錄語義不同，屬平台行為，待驗證）。

後果（以程式碼＋平台預設行為推演，未經實機證實者已標明）：(1) 模型權重、條件歷史、設定（含 `remote_monitor=true` 旗標）可能在換機／重裝時還原；還原至不同裝置／螢幕時模型可能誤用，程式無裝置指紋檢查；(2) 診斷 CSV、驗證影格、閃退報告可能隨備份離開裝置、上傳至使用者 Google 帳號備份，與「資料只留在這支手機」「不上傳」等字句直接衝突；(3) 備份還原可能使「已關閉監控」的使用者在新裝置上回到「監控開啟」（若備份時旗標為真），或反之——`getBoolean(key, true)` 的預設只在「從未存過 key」時生效，還原後的 prefs 有值即不走預設。修正方向（本報告只記錄，不代決）：宣告備份排除規則（至少排除三類診斷目錄與條件歷史），或將 `allowBackup` 改為 `false` 並說明重裝須重校；至少須在隱私文件中如實揭露備份行為。


## 4. Sideload／Play 兩版本差異表（行為不同須分別寫明，不得互相套用）

依據：`app/build.gradle.kts` 的 `productFlavors`——sideload：`SELF_UPDATE_ENABLED=true`、`REMOTE_MONITOR_ENABLED=true`（`:42-43`）；play：兩者皆 `false`（`:47,49`）；`buildConfig = true`（`:111`）。兩版共用同一個 `applicationId`（`tw.com.zhu.eyecontrol`，沿用第一輪；本輪確認 `app/build.gradle.kts` 內 `defaultConfig` 段仍為同一 id，簽名不同、互換通路安裝須先刪除舊版——此為第一輪與 build 檔註解所述，本輪未實機驗證安裝行為）。

### 4.1 差異總表

| 資料流／行為 | Sideload 版 | Play 版 | 閘門位置（程式證據） |
|---|---|---|---|
| 更新檢查（GitHub API 請求） | 發請求（全新啟動時） | **不發請求** | `MainActivity.kt:301`（`SELF_UPDATE_ENABLED`）；`UpdateChecker.checkForUpdate` 本身無守衛，見 §3.15 |
| APK 下載／安裝 | App 內 `DownloadManager` 下載＋FileProvider 安裝 | 不走此路；改跳 Play 商店（外部 App 連網） | `MainActivity.kt:299-316`＋`UpdateChecker.kt:103-142`；商店列可見性 `SettingsActivity.kt:188-189` |
| Supabase 上傳（CSV＋影格） | 可能上傳（預設開啟） | **不上傳**（見 §4.3 但書） | `remoteUploadEnabled()`（`GazeAccessibilityService.kt:75-76`）＋`applyVerifyCaptureFlags`（`:79-84`） |
| 眼區裁切上傳 | 可能上傳 | **不上傳**（見 §4.3 但書） | 同上，經 `setVerifyCapture`（`FaceTrackerEngine.kt:243-248`） |
| 診斷 CSV 本機寫檔 | 寫檔（預設開啟） | **仍寫檔** | `record(enabled = diagnosticLogging \|\| remoteMonitor)` 不含 flavor 條件（五處呼叫點見 §3.2）；Play 版 `diagnosticLogging` 開關列仍顯示、仍可儲存為 `true`，CSV 照寫、只不上傳 |
| 驗證影格本機留存 | 留存（預設開啟） | **仍留存** | `setVerifyCapture(local = settings.diagnosticLogging, …)` 不含 flavor 條件；Play 版影格只寫本機、不上傳 |
| 遠端監控開關列 | 顯示 | 整列隱藏（`View.GONE`） | `SettingsActivity.kt:77-78`（`REMOTE_MONITOR_ENABLED`） |
| `INTERNET`／`REQUEST_INSTALL_PACKAGES` 權限 | 宣告（`AndroidManifest.xml:8-9`） | 疊加層 `tools:node="remove"` 移除（`app/src/play/AndroidManifest.xml:8-13`） | 合併後清單未在本輪編譯驗證，見 §4.3 |
| 設定值殘留 | — | `switchRemote` 隱藏但值仍可經 `save()` 寫入（見 §3.10 末段） | `SettingsActivity.kt:293`（`saveAndFinish` 無 flavor 分支） |
| 診斷／監控預設值 | 皆 `true`（首次安裝） | 皆 `true`（`diagnosticLogging` 仍生效寫檔；`remoteMonitor` 值雖存但上傳閘門恆 false） | `GazeSettings.kt:47,55,207-208`（兩 flavor 共用同一份設定程式碼） |
| Crash Report／校正資料／條件歷史／設定 | 兩版相同（皆本機寫入、皆不上傳） | 兩版相同 | 見 §3.5／§3.10 |
| 相機影像／關鍵點／特徵 | 兩版相同（皆裝置內） | 兩版相同 | 見 §3.8／§3.9 |
| 備份行為 | 兩版相同（`allowBackup=true`，無排除） | 兩版相同 | 見 §3.16 |

### 4.2 Play 版「仍記錄」的證明（不得以「不上傳」概括）

Play 版不上傳的鏈路是：`remoteUploadEnabled()` 因 `BuildConfig.REMOTE_MONITOR_ENABLED=false` 恆為 false → 每幀 `remoteUpload=false` → `pendingUpload` 永不為真 → `close()` 的 `doUpload` 永不為真；影格側 `uploadEnabled=false` → `storeAndUpload` 的上傳分支永不執行。但**寫檔側的兩個呼叫皆不檢查 flavor**：CSV 的 `enabled` 只看兩開關的 OR；影格的 `local` 只看 `diagnosticLogging`。Play 版設定頁的診斷開關列正常顯示（`activity_settings.xml:741` 起的 `rowDiag`，無 flavor 分支），使用者在 Play 版開啟診斷（預設即開啟）即持續產生 `gaze-logs/` 與 `verify-frames/` 檔案。文案上「Play 版不上傳任何診斷資料」在「上傳」二字上屬實，在「不記錄」上不屬實（沿用 `privacy-copy-audit.md` §0.2 第 4 點）。

### 4.3 Play 版「不上傳」的真實防護層級（呼叫端閘門，非縱深防禦）

1. **`uploadFile` 本身無 flavor 守衛**：`GazeLog.kt:174-175` 的入口只檢查 `SUPABASE_URL.isBlank()`。`uploadFrame`（`GazeLog.kt:169-171`）亦無 flavor 檢查。Play 版不上傳完全依賴呼叫端永不以真值傳入 `remoteUpload`／`uploadEnabled`——即 §4.1 的兩個閘門函式。若未來任何呼叫點繞過這兩個函式（或 prefs 殘留值被誤用），`uploadFile` 本身不會攔截。K-45／S-12 仍未修復。
2. **Play 版 APK 仍內含上傳程式與金鑰常數**：`GazeLog`（含端點常數、寫死金鑰常數、`uploadFile` 全部程式碼）位於 `src/main` 共用程式碼，兩 flavor 共用同一份編譯單元；flavor 差異只在編譯期產生的 `BuildConfig` 布林值，不在原始碼移除。因此 Play 版 APK 經反編譯仍可見 Supabase 端點、上傳邏輯與金鑰常數；其實務防護是「執行期閘門恆 false」＋「清單移除 `INTERNET`」兩層，**不是「程式碼不存在」**。本審查未解包 APK 驗證，僅以原始碼結構推演，標為程式碼層面結論。
3. **清單移除未在本輪驗證**：`app/src/play/AndroidManifest.xml:8-13` 的 `tools:node="remove"` 宣告存在，但合併後清單（merged manifest）與實機安裝後的權限清單未在本輪編譯／安裝驗證（本審查不編譯）。若合併結果不如預期（例如其他程式庫清單重新引入 `INTERNET`），`uploadFile` 仍可能在 Play 版發出請求——而 `uploadFile` 本身無守衛（見上一點）。此為待驗證項（§10 T-5），不是已證實的缺口。
4. **Play 版的「平時不連網」只涵蓋 App 自行發起的請求**：使用者主動點「到 Play 商店檢查更新」列時，Play 商店／瀏覽器會連網（見 §3.15），那是外部 App 行為。文案須限縮為「App 自行發起的連線」（沿用 `privacy-copy-audit.md`）。
5. **跨 flavor 覆蓋安裝的殘留情境有限但未消除**：兩 flavor 簽名不同，覆蓋安裝須先刪除（build 檔註解所述）；刪除即清除 App 私有資料，prefs 殘留情境有限。但**備份還原**可把 `remote_monitor=true` 帶入 Play 版（見 §3.16），此時 Play 版的 `remoteUploadEnabled()` 仍因 `BuildConfig` 恆 false 而不上傳——防護成立，但「值已清除」的假設不成立，設定頁儲存時亦會原樣保留該值（見 §3.10 末段）。

### 4.4 兩版本共通、不得互相套用的提醒

- 預設開啟：兩版的 `diagnosticLogging` 預設皆開啟、皆寫檔；差別只在上傳。不得以側載版的「預設開啟會上傳」推論 Play 版，或以 Play 版的「不上傳」推論側載版。
- 文案分支：`SettingsActivity` 隱私對話框的更新段有 flavor 分支（`SettingsActivity.kt:158-162`），但首句（`SettingsActivity.kt:166`）兩 flavor 共用；主畫面頁尾（`activity_main.xml:760`）與 `strings.xml:3` 無 flavor 分支——三處皆為第一輪已判定的失實點，本輪確認原文仍在（見 §9）。

## 5. 撤回同意機制審查結論

### 5.1 機制盤點：不存在的機制（不得假裝有）

全樹 grep（`WorkManager`／`consent`／`Consent`／`revoke`／「撤回」／「同意」作為程式識別符）：`WorkManager` 零命中；`consent`／`Consent`／`revoke` 零命中（程式識別符層面）；「同意」只存在於文案與註解中，不存在同意紀錄、同意版本、同意時間戳等任何持久化欄位。`generation` 只存在於兩處無關機制——校正交接世代（`CalibrationActivity.kt` 內的 `handoverGeneration`）與幀槽世代（`FaceTrackerEngine.kt` 內的 `FrameSlot.generation`），**皆不是**同意世代／撤回世代。`pendingUpload`（`GazeLog.kt:51,101,106,141,148`）是「本檔案期間是否曾開啟監控」的**記憶體旗標**，不是持久化上傳佇列，也不是撤回旗標；行程死亡即丟失。**不存在「撤回世代」或「撤回時戳」可供上傳執行緒比對**：`uploadFile` 收到快照字串與 id 後，不再讀取任何設定（`GazeLog.kt:174-194` 全段無 `GazeSettings`／`SharedPreferences`／`BuildConfig` 讀取）。

### 5.2 撤回的真實語義（CSV 路徑）：結論——撤回不等於當下檔案不上傳

撤回操作＝設定頁關閉 `remoteMonitor`（或兩開關皆關）並按「儲存」。實際發生的事，按時間序：

1. `saveAndFinish()` 寫入 prefs（`SettingsActivity.kt:278` 起）；服務的 `settingsListener` 重載設定並呼叫 `applyVerifyCaptureFlags()`（`GazeAccessibilityService.kt:65-69,79-84`）。**該 listener 不呼叫 `GazeLog.close()`、不重置 `pendingUpload`、不觸及 `uploadExecutor`**——本輪以 `grep -n` 確認 `settingsListener` 本體只有上述兩行（`GazeAccessibilityService.kt:66-68`），無其他呼叫。
2. 下一幀 `GazeLog.record()` 以新設定計算 `enabled`：
   - **兩開關皆關** → `enabled=false` → `close()`（預設 `upload=true`，`GazeLog.kt:84-86,139`）。若當下檔案的 `pendingUpload` 為真（檔案期間曾有任何一幀監控開著），**整份檔案照常上傳**。關閉開關只停止「新錄製」，不阻止「當下檔案的上傳」。
   - **只關 `remoteMonitor`、留 `diagnosticLogging` 開** → `enabled` 仍為真，檔案繼續寫入；`pendingUpload` 保持 OR 累積的真值，後續關檔（滿 120 秒、`onDestroy` 等）時**一併上傳，含關閉後寫入的幀**。撤回後、關檔前的每一幀仍被寫入同一個「待上傳」檔案，且無任何標記區分撤回前後的幀。
   - **只關 `diagnosticLogging`、留 `remoteMonitor` 開** → 同上，檔案繼續寫入並上傳。
3. 唯一不以上傳舊檔為預設的路徑：設定頁「診斷摘要」→「清除紀錄」→ `GazeLog.clearAll()`（`close(upload=false)`＋刪檔，`GazeLog.kt:222-225`）。但該路徑同時刪除全部本機 CSV（含尚未上傳的診斷資料），且**不**刪除已上傳的伺服端資料、不刪除驗證影格檔案。使用者必須「放棄全部本機診斷資料」才能丟棄當下檔案，兩者綁定，無「只丟棄當下檔案、保留舊檔」的選項。
4. 服務銷毀（`onDestroy`）的 `GazeLog.close()`（`GazeAccessibilityService.kt:1618`）同樣預設上傳：使用者在服務運作中關閉監控、隨後停用服務，當下檔案仍會上傳。**停用服務不是撤回上傳的手段。**
5. 寫入失敗路徑亦預設上傳：`record()` 的 `onFailure { close() }`（`GazeLog.kt:136`）以預設 `upload=true` 關檔，寫入失敗本身亦可觸發上傳。

**結論**：「關閉開關即停止上傳」在 CSV 路徑上**不成立**。正確的現況敘述只能是「關閉後不再開始新的上傳檔案，但當下檔案仍會在關檔時上傳；要丟棄當下檔案須用『清除紀錄』（同時刪除全部本機 CSV）」。文案改寫要件沿用 `privacy-copy-audit.md`；程式修正不在本批次範圍，列為後續缺口 R-1（見 §9）。

### 5.3 撤回的真實語義（驗證影格路徑）：較嚴格，但有三個邊界

1. `setVerifyCapture(local, upload)`（`FaceTrackerEngine.kt:243-248`）：**只有兩者皆由開轉關時**才 `endSession()`（清庫存、刪舊時段目錄至 3 個上限，不刪當下時段已寫入的檔案）。**部分關閉（例如只關上傳、留本機）不 `endSession`**：`sessionId` 持續、`lastGoodStash` 不清空、`pendingCaptures` 佇列不清空。後續 `storeAndUpload` 仍會寫入本機（符合「留本機」的語義），上傳分支則靠執行時重查擋下——此部分語義正確，但「關閉上傳開關即結束時段／清空庫存」的直覺不成立。
2. `storeAndUpload()`（`VerifyFrames.kt:173-192`）在背景執行緒執行時**重新檢查**當下的 `localEnabled`／`uploadEnabled`：關開關後排隊中、尚未執行到檢查的影格不會寫入／不會上傳；已執行完檢查並已呼叫 `GazeLog.uploadFrame()` 的影格，其上傳任務已提交至 `uploadExecutor`，**關開關無法撤回**（見 §5.5）。
3. 已寫入 `verify-frames/` 的檔案不受開關影響（無刪除路徑，見 §3.3）。臉遺失庫存（`lastGoodStash`）在 `endSession()` 時清為 null（`VerifyFrames.kt:125-133`），兩開關全關後不再以舊裁切補發臉遺失證據；但部分關閉時庫存持續，`emitLastGoodAsFaceLost` 只要 `isActive`（任一開關開著）即可能發出**關閉上傳開關前**擷取的舊裁切（本機寫入面），其 JPEG 位元組最長保留 10 秒（`STASH_MAX_AGE_MS`，`VerifyFrames.kt:314`）。

**兩路徑對照**：影格路徑的「關閉後排隊中影格不上傳」比 CSV 路徑嚴格，但兩者皆不刪除已寫入檔案、皆不刪除已上傳資料。文案不得以影格路徑的語義概括 CSV 路徑（沿用第一輪結論）。

### 5.4 同意品質缺口清單

| # | 缺口 | 證據 | 影響 |
|---|---|---|---|
| G-1 | 首次開啟監控無確認對話框 | `SettingsActivity` 全檔無監控確認對話框；開關切換＋儲存即生效 | 使用者可能未意識到「開啟即開始錄製＋關檔即上傳整份 CSV」 |
| G-2 | 隱私對話框未提及監控 | `SettingsActivity.kt:157-171` 的對話框只談影像＋更新檢查 | 授權前／設定內的揭露面缺監控例外（見 `privacy-copy-audit.md`） |
| G-3 | 無同意紀錄／版本／時間戳 | 全樹無 consent 持久化（見 §5.1） | 無法證明使用者何時、以何版本文案同意；政策變更時無法重徵同意 |
| G-4 | 撤回不丟棄當下檔案（CSV） | 見 §5.2 | 撤回語義與使用者直覺相反（K-11） |
| G-5 | 已上傳資料無刪除路徑 | 全樹無 DELETE／刪除 API 呼叫；`GazeLog` 只有 `uploadFile`（POST） | 撤回後伺服端資料仍在，刪除須開發者手動處理（流程待定位，§10 T-2） |
| G-6 | 預設開啟＋引導可跳過 | 見 §1 R-6；引導 NOTICE 段雖有例外說明，但 `SetupWizardActivity` 有跳過機制（`skippedCamera`／`skippedCalibration`／`skippedService` 旗標，`SetupWizardActivity.kt:51-53`），`WelcomeActivity`「先看看主畫面」亦可跳過引導（`WelcomeActivity.kt:40-44`） | 未看引導的使用者可能在不知情下已上傳 |
| G-7 | 無上傳佇列可檢視／取消 | 無 WorkManager、無持久化佇列（見 §5.1） | 使用者無法查看「待上傳」內容、無法逐筆取消；「待上傳」實際上只有「當下開啟的檔案」＋「已提交至執行緒的快照」兩種記憶體內形態，皆無 UI 可見性 |
| G-8 | 撤回無「僅停止上傳、保留本機診斷」的獨立路徑 | `clearAll()` 綁定刪檔（見 §5.2 第 3 點）；影格無任何清除路徑 | 使用者為阻止一次上傳，須放棄全部本機診斷資料（CSV）或無法清除（影格） |

### 5.5 待上傳佇列的清除／阻止機制與重新連線行為：逐項核實

本節直接回答任務書的要求：**待上傳佇列 `pendingUpload` 的清除或阻止機制、重新連線時不得偷傳已撤回同意的資料**。

1. **`pendingUpload` 不是佇列**：它是單一布林旗標（`GazeLog.kt:51`），只在開新檔時重置（`GazeLog.kt:101`）、關檔時重置（`GazeLog.kt:148`）、`record` 時 OR 累積（`GazeLog.kt:106`）。**不存在「清除待上傳佇列」的 API**：全樹無任何函式可重置 `pendingUpload` 而不關檔；`settingsListener` 不重置它（見 §5.2）；`clearAll()` 透過 `close(upload=false)` 間接重置它，但代價是刪除全部 CSV。**不存在「阻止當下檔案上傳」的 API**：`close()` 的 `upload` 參數預設 `true`，除 `clearAll()` 外全部呼叫點皆用預設值（`GazeLog.kt:84-86` 的 `record` 內部呼叫、`GazeLog.kt:110-113` 的輪轉呼叫、`GazeLog.kt:136` 的失敗呼叫、`GazeAccessibilityService.kt:1618` 的銷毀呼叫）。
2. **已提交的任務無法阻止**：`close()` 在關檔當下把 CSV 全文快照進記憶體字串、提交至 `uploadExecutor`（`GazeLog.kt:154-158`）；`uploadFrame` 同理（`GazeLog.kt:169-171`）。提交後，程式不保留 `Future` 參照、不提供取消 handle、不檢查設定、不檢查撤回旗標（`uploadFile` 內無設定讀取，見 §5.1）。**執行緒佇列中等待執行的任務，撤回同意後仍會執行**——這是記憶體內佇列的「偷傳」窗口：撤回發生在「提交後、執行前」的任務不受撤回影響。窗口大小取決於執行緒當下負載（單執行緒、每任務一次同步 HTTP POST、逾時各 10 秒），本報告不量化窗口，只記錄機制存在。
3. **進行中的上傳無法中斷**：`uploadFile` 的 `HttpURLConnection` 無中途取消參照、無 `disconnect()` 呼叫點（`conn.disconnect()` 只在讀取 `responseCode` 後，`GazeLog.kt:191`）。撤回同意無法中斷已發出的 POST。
4. **重新連線／重啟時不補傳**：無持久化佇列、無重試、無啟動時掃描舊檔上傳的程式（`GazeLog` 全檔無啟動掃描；`latestFile` 只供摘要讀取）。離線時關檔上傳失敗即丟棄（只寫 Log，`GazeLog.kt:192-193`），**不會在重新連線後自動補傳**——此為「不偷傳」方向的正面事實，但同時意味「離線期間的診斷資料靜默丟失」，使用者無從得知。行程死亡時，已提交但未執行的快照隨行程丟失（不上傳、亦不重試）；但**本機檔案仍在**，下次 `summarize()` 可讀、使用者可手動複製——檔案留存與上傳是兩條獨立路徑，行程死亡只終止後者。
5. **重新連線後的新檔案仍受當下設定約束**：重啟後新檔案的 `pendingUpload` 從 false 重新累積，若設定已改為關閉，新檔案不上傳。此部分語義正確。風險只在「舊檔案／已提交任務」與「備份還原帶回舊設定」（見 §3.16），不在重啟後的新檔案。
6. **影格佇列的對照**：`VerifyFrames.worker` 的排隊任務受執行時重查保護（見 §5.3），故「重新連線偷傳」的窗口只存在於「已通過重查、已提交至 `GazeLog.uploadExecutor`」的影格，與 CSV 相同。`VerifyFrames.endSession()` 不清空 `worker` 佇列（`VerifyFrames.kt:125-133` 全段無佇列操作）；`FaceTrackerEngine.stop()` 清空的是 `pendingCaptures`（`FaceTrackerEngine.kt:520`），不是 `worker` 佇列——排隊中的 `storeAndUpload` 任務在 `stop()` 後仍可能執行，但執行時重查開關（`localEnabled`／`uploadEnabled` 仍為舊值，除非 `setVerifyCapture` 已改），且 `sessionId` 已清空使本機寫入分支的 `sessionId == id` 檢查失敗（`VerifyFrames.kt:182`），上傳分支則看 `uploadEnabled` 當下值。**`stop()` 本身不保證排隊影格不上傳**，保證來自重查；若 `setVerifyCapture` 未被呼叫（例如校正頁引擎實例，預設即關），則無上傳可言。

### 5.6 撤回同意機制審查結論（總判）

**撤回同意機制不是真正有效。** 分路徑判定：CSV 路徑**無效**（當下檔案必上傳、含撤回後寫入的幀、已提交任務不可撤回）；影格路徑**部分有效**（排隊中未執行任務受重查保護，但已提交任務不可撤回、已寫入檔案不可刪除、已上傳資料不可刪除）；已上傳資料的刪除**完全缺失**；同意紀錄與佇列可見性**完全缺失**。唯一有效的撤回效果是「下一個新檔案／新時段不再上傳」。任何稱「關閉即停止上傳」的敘述，在現況程式下皆不成立（見 §9）。

## 6. Logcat 與暫存檔審查

### 6.1 Logcat：完整清單——含什麼、不含什麼

全樹 `Log.*` 呼叫共 19 處（`GazeLog.kt` 2 處、`VerifyFrames.kt` 3 處、`FaceTrackerEngine.kt` 4 處、`GazeAccessibilityService.kt` 10 處，含 `FlowStateLogger` 的 1 處 `Log.w` 委派）。**全部無 `BuildConfig.DEBUG` 守衛**（零命中，見 §0.2）：release 版亦會寫入 logcat。Logcat 本身不上傳（無任何 log  shipping 呼叫），但任何能讀取 logcat 者（adb、bugreport、有特權的系統工具）可見以下內容。

| 位置 | 等級與內容 | 是否含敏感資料？ |
|---|---|---|
| `FaceTrackerEngine.kt:185` | `Log.e`「FaceLandmarker 錯誤」＋例外物件 | 僅錯誤訊息與 stack trace；不含影像、關鍵點、特徵。例外訊息內容取決於 MediaPipe 實作，未逐一枚舉，**不宣稱「絕不含」**，只記錄呼叫點不主動寫入 gaze 資料。 |
| `FaceTrackerEngine.kt:192` | `Log.e`「FaceLandmarker 建立失敗」＋例外 | 同上。 |
| `FaceTrackerEngine.kt:234` | `Log.e`「相機綁定失敗」＋例外 | 同上。 |
| `FaceTrackerEngine.kt:283` | `Log.e`「detectAsync 失敗」＋例外（`closed` 時不寫） | 同上。 |
| `GazeLog.kt:192-193` | `Log.w`「monitor upload failed: HTTP <code>」／例外 | 僅 HTTP 狀態碼與例外；**不含 CSV 內容、不含金鑰**（`uploadFile` 內無金鑰寫 Log 呼叫）。例外 stack trace 可能含主機名稱（已知端點），不含請求 body。 |
| `VerifyFrames.kt:153` | `Log.w` 影格達上限（60 張） | 僅計數上限文字，無資料。 |
| `VerifyFrames.kt:186,201` | `Log.w` 本機寫入／修剪失敗＋例外 | 僅失敗訊息，無影像內容。 |
| `GazeAccessibilityService.kt:170`（`FlowStateLogger`）＋`:234` | `Log.w` 流程狀態和解訊息 | 僅狀態機事件名稱／狀態名稱，無座標、無影像。 |
| `GazeAccessibilityService.kt:399` | `Log.w` 情境尚未校正（含情境名稱） | 僅情境名稱（坐著／躺著／平放），無 gaze 資料。 |
| `GazeAccessibilityService.kt:417` | `Log.e`「引擎啟動失敗」＋例外 | 同 MediaPipe 錯誤列。 |
| `GazeAccessibilityService.kt:624` | `Log.d`「臉轉向：$faceTurn」 | **含臉轉向分類**（列舉值，變化時才寫）。不含座標、影像、關鍵點。臉轉向屬臉部姿態的粗分類，非影像本身。 |
| `GazeAccessibilityService.kt:888,1256` | `Log.d` 邊緣手勢取消原因 | 僅手勢判決原因文字，無座標。 |
| `GazeAccessibilityService.kt:1060` | `Log.d` 條件內插偏移數值 `($appliedOffsetX, $appliedOffsetY)` | **含偏移數值（像素）**。不含原始座標、影像、特徵。 |
| `GazeAccessibilityService.kt:1093,1133,1167` | `Log.d` 提醒顯示／逾時／略過 | 僅流程文字，無資料。 |
| `GazeAccessibilityService.kt:1201` | `Log.d`「單點快速校正完成：偏移=($ox, $oy)，條件=$cond」 | **含偏移數值與條件向量**（`Condition` 的 `toString`：亮度桶、pitch／roll、iod、反光指標，`ConditionHistory.kt:72` 起的 data class）。不含影像、關鍵點、完整特徵。 |
| `GazeAccessibilityService.kt:1283` | `Log.d` 邊緣手勢檢查未通過（失敗原因清單） | 僅檢查名稱與原因，無座標。 |

**不含的內容（以呼叫點窮舉證明）**：

- **不含敏感畫面**：無任何 `Log` 呼叫傳入 Bitmap、螢幕截圖、視窗內容；`onAccessibilityEvent` 為空實作（見 §3.13），故無畫面文字可寫入 Log。
- **不含密碼**：全樹無密碼欄位、無密碼字串（`password` 零命中）；無任何憑證寫入 Log。
- **不含 OCR 文字**：無 OCR 程式庫、無文字辨識呼叫（零命中）；Log 內容無任何螢幕文字。
- **不含眼球原始影像**：無任何 `Log` 呼叫傳入 Bitmap／JPEG 位元組／base64；`VerifyFrames` 的 base64 只進上傳 JSON（`VerifyFrames.kt:189`），不進 Log。
- **不含關鍵點座標與完整特徵向量**：無任何 `Log` 呼叫傳入 `faceLandmarks`、`features` 陣列、`GazeSample`；最接近的是 CSV 欄位值的子集（偏移／條件向量，見上表），且只在 `Log.d` 的三處。
- **不含 CSV 全文與金鑰**：上傳失敗 Log 只寫狀態碼（見上表）。

**殘留風險**：`Log.d` 在 release 版仍輸出（無守衛），且 `:1060`／`:1201` 寫入偏移與條件向量——雖非影像與原始座標，但屬可推斷使用者臉部姿態／使用條件的行為數據。透過 adb／bugreport 可取得的使用者，毋須 App 授權即可讀取。建議方向（待決定，不在本報告改寫）：以 `BuildConfig.DEBUG` 守衛 `Log.d`，或移除 gaze 數值寫 Log 的呼叫；`Log.e`／`Log.w` 的例外 stack trace 保留與否另行決定。本報告不宣稱 logcat 可被一般 App 讀取（Android 4.1 起一般 App 無權讀取他 App 的 log），風險邊界為「具 adb／裝置存取權者」，如實標示，不誇大。

### 6.2 暫存檔與記憶體殘留

| 暫存形態 | 位置／生命週期 | 清除機制 | 外流？ |
|---|---|---|---|
| 相機幀 Bitmap（三緩衝＋中間緩衝） | `FaceTrackerEngine.kt:46-47`（`cachedBmp`、`cachedRotatedBmps`），記憶體內 | `stop()` 回收全部緩衝（`FaceTrackerEngine.kt:534-542`），幀槽清為 null（`:514-518`） | 否 |
| 眼區裁切 Bitmap（獨立複製） | `EyeImageMetrics.cropEyes` 產出，所有權交 `VerifyFrames`，用完 `recycle()`（`VerifyFrames.kt:208-228` 的 `encodeCrop`／`recycleQuietly`） | 壓 JPEG 後回收；`submit` 丟棄路徑亦回收（`VerifyFrames.kt:73-79`） | 否（除 §3.3 的留存／上傳路徑外） |
| 臉遺失庫存 JPEG（`lastGoodStash`） | `VerifyFrames.kt:66`（`AtomicReference`），記憶體內，JPEG 位元組＋meta | `endSession()` 清為 null（`VerifyFrames.kt:131`）；`stop()` 經 `endSession()` 清除；逾 10 秒視為過期不發（`VerifyFrames.kt:103`） | 否（除經 `storeAndUpload` 的留存／上傳外） |
| CSV 寫入緩衝（`BufferedWriter`，50 行 flush） | `GazeLog.kt:53,135`，記憶體內 | `close()`／`flush()` 寫出（`GazeLog.kt:139-159,213-215`）；`summarize()` 前 `flush()` | 否（寫出即入 `filesDir` 檔案，見 §3.4） |
| 上傳快照（CSV 全文／影格 JSON 字串） | `GazeLog.kt:154-158` 的 `csvText` 區域變數，提交至 `uploadExecutor` 後由執行緒持有 | 任務執行完畢即釋放；行程死亡即丟失（見 §5.5） | 經上傳送出（見 §3.2／§3.3） |
| `pendingCaptures` 佇列（影格請求） | `FaceTrackerEngine.kt` 內 `ConcurrentLinkedQueue`，上限 8、逾時 2 秒 | `stop()` 清空（`FaceTrackerEngine.kt:520`）；`setVerifyCapture` 全關時**不清**（見 §5.3）；請求本身只含種類＋時間戳＋停留進度，不含影像 | 否 |
| `onSaveInstanceState` 狀態 | `CalibrationActivity.kt:259-263`：僅兩個整數（`pointIndex`、`committedPoints`）；`SetupWizardActivity.kt:116-124`：僅步驟／跳過旗標 | 系統 Bundle 生命週期；不含特徵、影像、座標 | 否 |
| 服務／Activity 狀態快照（`trackingSnapshot`） | `GazeAccessibilityService.kt` 內（沿用第一輪定位，`:1752` 起） | 只回布林／狀態，不回座標（第一輪結論，本輪確認函式仍以狀態為主、無 CSV／影像回傳） | 否 |
| `cacheDir`／快取檔 | 不存在（零命中） | 不適用 | 否 |
| 下載的 APK 檔 | `getExternalFilesDir(DIRECTORY_DOWNLOADS)/eyecontrol-debug.apk`（`UpdateChecker.kt:117-118`） | 僅下載失敗刪除（`MainActivity.kt:71-74`）；安裝成功後不刪除；解除安裝時隨 App 私有外部目錄刪除 | 否（APK 本身為公開 release 產物，非使用者資料；但舊版 APK 殘留可能被誤裝，見 §3.15） |
| `FileProvider` 分享 URI | `AndroidManifest.xml:57-65`，`file_paths.xml` 僅開放 `Download/` 路徑，`exported=false`、`grantUriPermissions=true` | 單次授權（`FLAG_GRANT_READ_URI_PERMISSION`，`UpdateChecker.kt:139`）；只分享 APK 檔，不分享 `filesDir` 內容 | 否 |

**結論**：暫存檔面無未受控的影像／ gaze 資料殘留——全部記憶體暫存皆有明確的 `stop()`／`endSession()`／`recycle()` 清除點，唯一的檔案殘留是三類診斷檔案本身（見 §3.4／§3.5），其清除缺口（影格無刪除 UI、CSV 修剪只在開新檔時）屬 §3／§5 的範疇，不重複計為暫存缺口。

### 6.3 其他暴露面：近期任務縮圖、覆蓋層、通知

- **無 `FLAG_SECURE`**（零命中）：全部 Activity 皆未設定防截圖／防近期任務縮圖旗標。App 畫面（設定頁、診斷摘要對話框、閃退紀錄對話框）可被系統截圖與近期任務縮圖 capture。本報告未實機驗證縮圖實際內容，僅記錄「無旗標」的事實；診斷摘要與閃退紀錄為文字摘要（非影像），縮圖暴露的敏感度低於影像類 App，但仍屬殘留（R-12，見 §9）。游標覆蓋層（`TYPE_ACCESSIBILITY_OVERLAY`，`GazeAccessibilityService.kt:434-458`）只繪製游標與控制面板，不含 gaze 資料；覆蓋層本身不截取螢幕。
- **無螢幕錄製／投影 API**：`MediaProjection` 零命中。
- **前台通知**：固定文字，無資料（見 §3.14）。

## 7. 第三方程式庫、模型、字型及素材授權清查表

**判定規則**：授權狀態以「本樹內是否有授權文件／來源記載」＋「是否取得官方授權文本」為準；**授權未驗證者一律標「待定位／未驗證」，不得宣稱合法**。本節的網路搜尋結果僅作「待查線索」，不作授權結論（搜尋到的第三方頁面陳述未經官方文本核對，且未確認與本樹所用確切版本一致）。

**本樹授權文件現況**：僅有根目錄 `LICENSE`（MIT，`Copyright (c) 2026 Su ShengWen`，`LICENSE:1,3`）與 `README.md:205` 的「MIT License，見 LICENSE」一句。**全樹無 `NOTICE` 檔、無第三方授權清單、無 SBOM、無 Gradle lockfile**（`find` 零命中）。`README.md:208-209` 的「致謝」段列出 MediaPipe Face Landmarker 與 CameraX 的文件連結，但未記載授權條款、未附授權文本。

| # | 項目 | 版本／來源（本樹證據） | 授權狀態 |
|---|---|---|---|
| L-1 | 本 App 原始碼 | `LICENSE:1,3`：MIT，著作權人 Su ShengWen（2026） | **已確認（本樹文件）**。但 `docs/privacy.html:16` 署名 `Zhu` 與 LICENSE 的 `Su ShengWen` 不一致（沿用 `privacy-copy-audit.md` §2.6 判定，待確認，不得自行填寫法定姓名／信箱）。另：AGPL 切換議題屬 Research & IP 線（見任務書外之第三份總規格），本報告不代決授權變更，僅記錄現況為 MIT。 |
| L-2 | `com.google.mediapipe:tasks-vision` | `app/build.gradle.kts:128`：`0.10.26` | **待定位／未驗證**。本樹無授權文本、無 POM 記載。線索（非結論）：網路搜尋見 MediaPipe 原始碼檔頭多為 Apache-2.0 標頭、模型卡片稱 Apache-2.0，但未核對 0.10.26 確切產物的授權文本與 NOTICE 義務。 |
| L-3 | `face_landmarker.task` 模型檔 | `app/src/main/assets/face_landmarker.task`，3,758,596 位元組；來源記載於 `README.md:156-157`：`https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task`；載入於 `FaceTrackerEngine.kt:133`；`app/build.gradle.kts:115` 的 `noCompress += "task"` | **待定位／未驗證**。本輪實測 SHA-256：`64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`；zip 內容物：`face_detector.tflite`、`face_landmarks_detector.tflite`、`geometry_pipeline_metadata_landmarks.binarypb`、`face_blendshapes.tflite`。**不得宣稱合法**：模型組合包（.task bundle）整體的授權覆蓋範圍（含 `geometry_pipeline_metadata_landmarks.binarypb`）在公開討論中仍有疑問（搜尋到的開發者論壇提問未獲官方定論），本審查未取得官方模型授權文本。另：`latest` 為浮動版本，本樹檔案的版本號（float16/1）未在本樹記載，僅以 SHA 指紋為準；未來須以官方發布頁的 SHA 對照驗證。模型在裝置端執行，不連網（見 §3.8）。 |
| L-4 | CameraX（core／camera2／lifecycle／view） | `app/build.gradle.kts:122-125`：`1.4.2`（`cameraxVersion` 變數） | **待定位／未驗證**。本樹無授權文本。線索（非結論）：AndroidX 原始碼檔頭多為 Apache-2.0，但未核對 1.4.2 確切產物的授權文本與 NOTICE 義務。 |
| L-5 | `androidx.core:core-ktx` | `app/build.gradle.kts:130`：`1.15.0` | **待定位／未驗證**（同上，無本樹授權記載）。 |
| L-6 | `androidx.appcompat:appcompat` | `app/build.gradle.kts:131`：`1.7.0` | **待定位／未驗證**（同上）。 |
| L-7 | `com.google.android.material:material` | `app/build.gradle.kts:132`：`1.12.0` | **待定位／未驗證**（同上）。 |
| L-8 | `androidx.constraintlayout:constraintlayout` | `app/build.gradle.kts:133`：`2.2.0` | **待定位／未驗證**（同上）。 |
| L-9 | `androidx.lifecycle:lifecycle-runtime-ktx` | `app/build.gradle.kts:134`：`2.8.7` | **待定位／未驗證**（同上）。 |
| L-10 | `junit:junit`（僅測試） | `app/build.gradle.kts:135`：`4.13.2`（`testImplementation`，不進 APK） | **待定位／未驗證**（不進發行產物，風險低，但本樹仍無授權記載）。 |
| L-11 | 傳遞依賴（未列舉） | 無 lockfile／SBOM，無法列舉 | **待定位／未驗證**。須以依賴樹（例如 Gradle dependency report）列舉後逐一核對；本審查未編譯，未取得依賴樹。 |
| L-12 | Gradle wrapper（`gradle-wrapper.jar`）＋ AGP／Kotlin 外掛 | 根 `build.gradle.kts` 的 plugins 段：AGP `8.11.1`、Kotlin `2.1.0`；`gradle/wrapper/gradle-wrapper.jar` 為二進位檔 | **待定位／未驗證**（建置工具，不進 APK；本樹無授權記載）。 |
| L-13 | Supabase 服務（非 SDK） | 直接 REST 呼叫（`GazeLog.kt:179`），無 Supabase SDK 依賴 | 無 SDK 授權問題；**服務條款／資料處理條款待定位**（§10 T-1／T-2）：本審查未取得 Supabase 專案的服務條款確認紀錄。 |
| L-14 | 字型 | 全樹 grep `font`／`Typeface`：僅 `themes.xml:20` 的 `android:fontFamily="sans-serif"`（系統字型）；無內嵌字型檔（`res/` 內無 `font/` 目錄） | **無第三方字型**：使用系統字型，無授權問題。本輪確認無 Noto 等打包字型（與 MASTER 設計系統「系統字體不打包」的定版一致）。 |
| L-15 | 圖示與 drawable 素材 | `ic_launcher_foreground.xml`（眼形向量，檔內註解稱自繪）與 `ic_eye.xml`／`ic_eye_mark.xml` 等向量 drawable，皆為本樹自有 XML，未見外部圖片檔；`res/` 內無點陣圖素材 | **本樹自有素材**：未見外部授權素材。但「自繪」主張僅以檔內註解與向量路徑為據，未見設計稿來源紀錄；若圖示源自外部設計稿，來源待補記。本報告不宣稱「已驗證原創」，只記錄「樹內無外部素材檔」。 |
| L-16 | `org.json.JSONObject`（`org.json`） | 程式碼 `import org.json.JSONObject`（`CalibrationStore.kt`、`GazeModel.kt`、`ConditionHistory.kt`、`UpdateChecker.kt`），由 Android 平台提供 | **待定位／未驗證**（平台內建 API，本樹無授權記載；一般隨 Android 平台授權，但未在本樹核對）。 |

**清查結論**：除本 App 自身 MIT 授權與「無打包字型、無外部點陣素材」兩項外，**全部第三方項目皆為「待定位／未驗證」，不得宣稱合法**。上架前須補：依賴樹列舉、逐項授權文本核對、`NOTICE`／第三方授權清單、模型 bundle 的官方授權文本與 SHA 對照驗證。授權切換（MIT→AGPL）屬另線任務，本報告不處理。

## 8. 第一輪 K-12 缺口逐項核實現況

| 缺口 | 第一輪主張 | 本輪核實（程式證據） | 現況 |
|---|---|---|---|
| **K-12 缺口 1：撤回同意不阻止當下檔案上傳** | 關閉監控開關不阻止當下 CSV 檔案上傳（`pendingUpload` 機制） | `settingsListener`（`GazeAccessibilityService.kt:65-69`）不呼叫 `GazeLog.close()`、不重置 `pendingUpload`（`GazeLog.kt:51`）；`close()` 預設 `upload=true`（`GazeLog.kt:139`）、`doUpload` 只看 latch（`GazeLog.kt:141`）；部分關閉時撤回後寫入的幀亦一併上傳（見 §5.2）。影格路徑較嚴格但已提交任務不可撤回（見 §5.3／§5.5）。 | **仍存在，未修復**。且本輪確認範圍比第一輪更廣：不只「當下檔案」，還包括「撤回後、關檔前寫入的幀」與「已提交至執行緒的任務」。 |
| **K-12 缺口 2：Play 版不上傳只靠呼叫端閘門** | Play 版 `uploadFile` 僅靠間接防護，無 `BuildConfig` 顯式守衛 | `uploadFile`（`GazeLog.kt:174-175`）與 `uploadFrame`（`GazeLog.kt:169-171`）仍無 flavor 守衛；防護只靠 `remoteUploadEnabled()`（`GazeAccessibilityService.kt:75-76`）與 `applyVerifyCaptureFlags`（`:79-84`）兩個呼叫端閘門；Play 版 APK 仍內含上傳程式與寫死金鑰常數（共用 `src/main` 程式碼，見 §4.3）；清單 `INTERNET` 移除未經合併清單驗證（§10 T-5）；`UpdateChecker.checkForUpdate` 亦同構（本身無守衛，靠 `MainActivity.kt:301`）。 | **仍存在，未修復**。本輪新增「APK 仍內含程式與金鑰」與「清單移除未驗證」兩項邊界。 |
| **K-12 缺口 3：`allowBackup=true` 無排除規則** | `allowBackup=true` 且無備份排除規則 | `AndroidManifest.xml:15` 仍為 `allowBackup="true"`；全樹仍無 `fullBackupContent`／`dataExtractionRules`／備份規則檔（`res/xml/` 僅兩檔）；6 份 prefs 與三類診斷檔案原則上在預設備份範圍內（見 §3.16）；備份還原可帶回 `remote_monitor=true`（見 §3.10 末段、§4.3）。 | **仍存在，未修復**。本輪新增「備份還原帶回開關值」的具體後果鏈。 |

**補充核實**：第一輪三處絕對化敘述（`privacy-copy-audit.md` §1）原文仍在，行號以本輪為準：`SettingsActivity.kt:165-167`（隱私對話框首句＋更新段）、`activity_main.xml:760`（主畫面頁尾）、`strings.xml:3`（`accessibility_desc`）；`SetupWizardActivity.kt:219` 的「關閉即停止該管道的上傳與留存」句亦仍在（見 §5.2 判定：CSV 路徑失實）。文案改寫不在本報告範圍，判定沿用 `privacy-copy-audit.md`。

## 9. 殘餘風險清單（依嚴重度排序）

嚴重度定義：**高**＝預設狀態下即可發生非預期外流或敏感資料暴露；**中**＝需特定操作／條件才發生，或防護單點化；**低**＝影響有限或需高權限存取。排序僅供產品決定優先序，不代表已發生事件。

| # | 風險 | 嚴重度 | 證據 | 建議方向（待產品決定，本報告不代決、不改程式） |
|---|---|---|---|---|
| R-1 | 撤回同意後當下 CSV 檔案仍上傳（含撤回後寫入的幀），且已提交任務不可撤回 | 高 | §5.2／§5.5：`GazeLog.kt:51,101,106,139-158`；`GazeAccessibilityService.kt:65-69` | 在設定變更時以 `close(upload=false)` 丟棄當下檔案（或重置 latch 並丟棄已提交任務——須先有取消 handle）；文案先行誠實化（見 `privacy-copy-audit.md` 改寫要件）。 |
| R-2 | Play 版不上傳只靠呼叫端閘門；`uploadFile` 無顯式守衛；Play APK 仍內含上傳程式與寫死金鑰 | 高（結構性） | §4.3：`GazeLog.kt:169-175`；`GazeAccessibilityService.kt:75-76`；共用 `src/main` 程式碼 | 在 `uploadFile`／`uploadFrame` 內加 `BuildConfig.REMOTE_MONITOR_ENABLED` 顯式守衛；驗證合併後清單確已移除 `INTERNET`；評估 Play 版以 source set 隔離上傳程式碼。 |
| R-3 | `allowBackup=true` 無排除規則：診斷 CSV、眼區影格、閃退報告、校正模型與開關值可能隨系統備份離開裝置並還原 | 高 | §3.16：`AndroidManifest.xml:15`；無規則檔 | 宣告備份排除規則（至少排除三類診斷目錄與條件歷史），或 `allowBackup=false`；隱私文件先行揭露備份行為。 |
| R-4 | Supabase anon 金鑰寫死於 App 內，防護單點依賴未驗證的 RLS；伺服端保留／刪除／存取流程未知 | 高（條件性） | §3.2：`GazeLog.kt:33-41`（值不轉錄）；§10 T-1／T-2 | 以 Supabase 控制台驗證 RLS（僅 INSERT、限表、無讀改刪）並留存紀錄；確認保留期限與刪除流程；調校結束時輪換／撤銷金鑰並刪除專案（第一輪 S-03 要求）。 |
| R-5 | 預設開啟＋無確認對話框＋引導可跳過：使用者可能在不知情下已上傳眼區影像與 gaze 數字 | 高 | §5.4 G-1／G-6：`GazeSettings.kt:47,55,207-208`；`SetupWizardActivity.kt:51-53`；`WelcomeActivity.kt:40-44` | 首次啟動就監控上傳前彈確認（或改預設關閉——但須注意第一輪 PLAN 紅線「保留 v0.5.9 預設開啟決定，本階段不動兩開關預設值」，改預設須產品另行決定）；至少確保引導例外段不可跳過地被看見。 |
| R-6 | 驗證影格無刪除 UI；已上傳資料無刪除路徑；`clearAll()` 只清 CSV | 中 | §3.3／§5.3：無 `VerifyFrames` 清除 API；無 DELETE 呼叫 | 補影格清除路徑（本機）與伺服端刪除流程（聯絡方式／手動處理 SOP）；文案如實說明「已上傳資料無 App 內刪除」。 |
| R-7 | APK 更新無雜湊／簽名驗證，信任完全依賴 HTTPS＋GitHub API 回應 | 中 | §3.15：`MainActivity.kt:51-89`、`UpdateChecker.kt:124-142` 無雜湊呼叫 | 下載後比對 release 提供的雜湊（須先有可信雜湊來源），或改走 Play 商店／簽名驗證；至少記錄此缺口於 release-readiness。 |
| R-8 | 無憑證釘選、無網路安全設定檔；傳輸安全依賴平台預設 TLS | 中 | §3.1：零命中；全部 URL 為 https | 評估是否為 Supabase／GitHub 端點加釘選（須權衡憑證輪換風險）；本報告不宣稱為已證實漏洞。 |
| R-9 | Logcat 無 debug 守衛，release 版亦輸出偏移／條件向量 | 中 | §6.1：零命中；`GazeAccessibilityService.kt:1060,1201` | 以 `BuildConfig.DEBUG` 守衛 `Log.d`，或移除 gaze 數值寫 Log。 |
| R-10 | 無障礙宣告過度（`canRetrieveWindowContent=true`＋`typeAllMask`），程式雖零讀取，但未來改動無需重新授權即可讀取畫面 | 中 | §3.13：`accessibility_service_config.xml:3-8`；`GazeAccessibilityService.kt:1634-1635` | 收斂宣告至實際所需（事件類型、flags、`canRetrieveWindowContent`）；收斂前在隱私文件揭露宣告與行為的落差。 |
| R-11 | Crash Report 檔名秒級覆寫、`shown_*` 旗標不清、內容含裝置型號與 stack trace；文案稱「不含任何個人資料」過寬 | 低 | §3.5：`CrashReporter.kt:14,36-49,66-68` | 檔名加毫秒／流水號、`clearAll()` 同步清旗標、文案限縮為「程式不主動上傳；內容含裝置型號與 stack trace」。 |
| R-12 | 無 `FLAG_SECURE`：設定頁／診斷摘要／閃退紀錄可被截圖與近期任務縮圖 capture | 低 | §6.3：零命中 | 評估敏感畫面加 `FLAG_SECURE`（須權衡無障礙使用者截圖回報的需求——閃退紀錄對話框明示「截圖回報」，加旗標前須另定回報路徑）。 |
| R-13 | 第三方授權全部待定位／未驗證（含模型 bundle 整體授權覆蓋範圍）；無 NOTICE／SBOM | 中（法務／上架） | §7：L-2～L-13 | 補依賴樹、逐項授權核對、NOTICE 清單、模型 SHA 對照驗證；上架前完成。 |
| R-14 | 傳遞依賴執行期網路行為未驗證；合併後清單未驗證；伺服端紀錄／備份實際內容未驗證 | 中（驗證缺口） | §3.6／§4.3／§3.16；§10 | 補網路抓包驗證（實機）、合併清單檢查、備份還原實測、Supabase 控制台驗證。 |
| R-15 | 敏感度備註：眼區裁切為眼睛影像（生物特徵影像），即使不含全臉亦屬高敏感資料；CSV 含可關聯的時間戳 id | —（定性提醒） | §3.2／§3.3 | 不以「不含全臉」淡化；上架前整段移除的承諾須含「程式＋開關＋伺服端專案＋金鑰輪換」四項（第一輪 S-03），並留存移除證據。 |

## 10. 待定位／待驗證清單（找不到來源，不編造）

| # | 項目 | 為何待定位 | 驗證方式（建議） |
|---|---|---|---|
| T-1 | Supabase RLS 是否確為「僅 INSERT、限 `gaze_logs` 表、無 SELECT／UPDATE／DELETE」 | RLS 在伺服端，本樹只有註解主張（`GazeLog.kt:33-37`） | 登入 Supabase 控制台驗證並留存紀錄 |
| T-2 | Supabase 伺服端資料保留期限、刪除流程、存取紀錄、專案刪除狀態 | 本樹無任何伺服端設定檔與刪除呼叫 | 控制台／專案設定查核；刪除 SOP 留存 |
| T-3 | GitHub API／Release 下載的伺服端紀錄內容 | 無法由客戶端程式碼證實伺服端行為 | GitHub 側文件／存取紀錄查核（本報告不臆測） |
| T-4 | 系統自動備份實際取走的檔案清單、還原行為 | 須實機／裝置驗證；本樹只有 `allowBackup` 宣告 | 實機備份／還原測試 |
| T-5 | Play 版合併後清單是否確已移除 `INTERNET`／`REQUEST_INSTALL_PACKAGES` | 本審查不編譯，未取得 merged manifest | 編譯 Play variant 後檢查 merged manifest 與安裝後權限 |
| T-6 | 傳遞依賴執行期是否發起網路呼叫（CameraX／MediaPipe 等） | 無依賴樹／SBOM、無網路抓包 | 依賴樹列舉＋實機網路抓包 |
| T-7 | 第三方授權文本（全部 L-2～L-13）與模型 bundle 整體授權覆蓋 | 本樹無授權文件；公開討論未定論 | 官方授權文本核對、SHA 對照、NOTICE 清單 |
| T-8 | `docs/privacy.html` 署名 `Zhu` vs LICENSE `Su ShengWen` 的一致性 | 屬文件一致性問題，非資料流失實 | 改寫批次確認，不得自行填寫法定姓名／信箱 |
| T-9 | 任務書提及的 §9.5 範例原文 | 未見於本次收到的先前文件（沿用 `privacy-copy-audit.md` §4 T-1） | 不得照抄、不得臆造 |
| T-10 | 實機行為：Logcat 實際輸出、近期任務縮圖實際內容、備份還原後的開關狀態 | 皆須實機驗證，本審查僅程式碼層面 | 實機測試並留存紀錄 |

---

## 附錄 A. 審查範圍外／未動事項

- 本報告未修改任何程式碼、版本號、workflow、文案、設定檔；未推送任何內容；未讀取任何 secrets／金鑰／keystore 內容（金鑰值一律不轉錄）；未編譯、未安裝、未實機測試。
- CI workflow（`.github/workflows/`）不在 App 資料流範圍內，本報告僅確認其存在 release keystore 機制（以 GitHub secrets 傳入），未審查 workflow 內容、未讀取任何 secret 值。
- 文案逐句判定沿用 `docs/privacy-copy-audit.md`，本報告不重做文案稽核、不提供替換文案；資料流事實以本報告 §3／§4／§5 為準，兩份文件如有行號出入，以本報告的現況行號為準。

## 附錄 B. 關鍵檔案索引（本報告引證的程式位置）

- 資料流核心：`GazeLog.kt`（CSV＋上傳）、`VerifyFrames.kt`（影格）、`FaceTrackerEngine.kt`（相機＋裁切＋開關）、`GazeAccessibilityService.kt`（設定監聽＋五處 record＋銷毀）、`GazeSettings.kt`（預設值＋持久化）。
- 持久化：`CalibrationStore.kt`、`ConditionHistory.kt`、`GazeModel.kt`、`CrashReporter.kt`、`WelcomeActivity.kt`（`eyecontrol_setup`）。
- 更新：`UpdateChecker.kt`、`MainActivity.kt`（`checkForUpdate`、`downloadReceiver`）、`SettingsActivity.kt`（開關＋對話框＋清除紀錄）。
- 宣告與建置：`app/src/main/AndroidManifest.xml`、`app/src/play/AndroidManifest.xml`、`app/src/main/res/xml/accessibility_service_config.xml`、`app/src/main/res/xml/file_paths.xml`、`app/build.gradle.kts`、`app/src/main/assets/face_landmarker.task`。
- 第一輪文件：`docs/privacy-data-flow.md`、`docs/privacy-copy-audit.md`（複用來源，見 §1）。

*複用來源：`docs/privacy-data-flow.md`、`docs/privacy-copy-audit.md`（第一輪，PHASE 5 K-12）；先前文件：`~/workspace/eyecontrol-master-audit/docs/audit/KNOWN_ISSUES.md`（K-11／K-12／K-20／K-45）、`~/workspace/eyecontrol-master-audit/docs/audit/SECURITY_AND_PRIVACY_AUDIT.md`（§8／§10／S-03／S-04／S-12／S-17）——以上先前文件為第一輪文件的引用來源，本輪未重新開啟，僅沿用第一輪文件的轉述。*
