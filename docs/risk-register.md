# EyeControl 風險清單（STEP 1 盤點）

> 性質：STEP 1 盤點文件。只讀取程式碼、只新增本文件，未修改任何程式碼、版本號、workflow。
> 工作樹：`~/workspace/eyecontrol-p3`（遠端 main HEAD `c6b80964`、v0.5.9 加未推送 Phase 3 實作）。
> 行號一律為目前工作樹行號。嚴重度＝影響 × 發生可能性（靜態判斷，未經實機量測）：
> **高**＝可能導致功能失效、隱私外洩、使用者受困或發行受阻；**中**＝可能導致誤觸、誤判或體驗受損；
> **低**＝文件／流程／可維護性風險。本任務未執行編譯、測試與實機驗證，風險皆為靜態推斷，
> 不得改寫為已發生的實機事故（除非註明為先前回報）。

## 0. 最高風險前五項（摘要）

| 排名 | 風險 | 嚴重度 | 一句話 |
|---|---|---|---|
| R1 | 診斷／監控預設開啟上傳，與「不儲存、不上傳」文案矛盾；眼區影像預設外流 | 高 | 預設開啟＋文案否認，涉及知情同意與上架審查 |
| R2 | 全部功能未經本任務驗證：無編譯、無測試執行、無實機、無 CI 紀錄 | 高 | 任何「已完成」宣稱皆無三重證據；Phase 3 實作一律未驗證 |
| R3 | 停止後自動恢復（K-30）＋ RECOVERY_MODE 相機持續運作 | 高 | 行程重啟丟失停止意圖；停止眼控後相機仍開著，使用者可能誤以為已關閉 |
| R4 | 相機綁定失敗無可觀測信號＋引擎啟停競態（K-02／K-14） | 高 | 綁定失敗只記 Log，流程態不進 ERROR；啟停無 generation 守衛 |
| R5 | 邊緣手勢幾何改動未經實機對照＋門檻未校準＋無取消語義 | 高 | 50 dp 帶寬約為 v0.5.9 的 4 倍（高密度裝置）；離散度檢查永不阻擋；手勢無法取消 |

其餘風險見 §1 全表（R6–R20）。

## 1. 風險全表

### R1 — 診斷／監控預設開啟上傳，與文案矛盾（隱私／知情同意）

- **證據**：`GazeSettings.kt`（`diagnosticLogging=true`、`remoteMonitor=true`，預設與 `load()` 預設皆 true）；`GazeLog.uploadFile()`（`GazeLog.kt:175-198`，Supabase `gaze_logs` 表，CSV 全文或眼區 JPEG base64）；`VerifyFrames.kt`（眼區裁切留存＋上傳）；`strings.xml:3` 的 `accessibility_desc` 稱「影像只在本機即時處理，不儲存、不上傳」。
- **影響**：使用者在系統無障礙設定頁看到的官方描述否認上傳，但側載版預設即上傳診斷數字與眼區影像；若使用者未讀設定頁，不知情即外流。Play 上架審查（資料安全表／隱私政策）亦可能因此被質疑。
- **觸發條件**：側載版首次啟用服務且使用者未手動關閉兩開關（`load()` 預設只在「從未存過該 key」時生效，明確關過者升級後仍關——此為緩解，但首次使用者無此保護）。
- **緩解／下一步**：上架前整段移除診斷／監控程式與 Supabase 專案（既定決定，見 `GazeLog.kt` 檔頭與 `VerifyFrames.kt` 檔頭）；調校期內先修正全部絕對表述文案（K-12 殘餘，見 R12），或將預設改為關閉——**須使用者決策，不得擅改**。伺服端 RLS 實際設定尚未驗證，不得假設「僅 INSERT」已生效。

### R2 — 零驗證狀態：未編譯、未跑測試、未實機、未查 CI

- **證據**：本任務方法邊界（見 `docs/architecture-map.md` §5）；`IMPLEMENTATION_NOTES.md` §0（本機 Gradle 因沙箱 JVM loopback 無法執行）；CI 兩個 workflow 皆無測試步驟（`.github/workflows/build-apk.yml`、`attach-apk-to-release.yml`）；全樹無 instrumented test。
- **影響**：Phase 3 的 29 項實作、邊緣控制器、狀態機接線、報告頁等，皆可能在編譯期或實機期才暴露錯誤；先前 harness「110 項全過」為先前記載，本任務未重跑、不得背書。
- **觸發條件**：任何一次推送／發版前的 CI 編譯；任何一次實機操作。
- **緩解／下一步**：推送前先在可編譯環境跑 `gradle testSideloadDebugUnitTest`＋`assembleSideloadDebug`（CI）；實機清單按 `TEST_PLAN.md` §3（F／G／R／E 系列）逐項執行並留證據；不得以「已讀程式碼」宣稱編譯成功。

### R3 — 停止意圖未持久化（K-30）＋ RECOVERY_MODE 相機持續運作

- **證據**：`userStopped`／`inRecoveryMode` 為記憶體欄位（`GazeAccessibilityService.kt:113-130`）；`onServiceConnected` 不檢查舊旗標（`:320-350`）；`stopTracking` 改語義後引擎續跑（`:1886`）；重啟目標副文字明示相機運作中（D2-5 文字要求）。
- **影響**：使用者按「停止」後行程重啟（系統回收／重開機），服務自動恢復追蹤，與停止意圖相反；RECOVERY_MODE 下相機持續運作，若使用者未看到副文字，可能誤以為相機已關閉。
- **觸發條件**：系統殺掉服務行程後重建；使用者停止後離開、未注意 overlay 副文字。
- **緩解／下一步**：持久化與否待 D-8 決定（PLAN §6），本階段維持現況並明示缺口，不得宣稱已修復；D2-5 文字三處同步（對話框／狀態列／目標副文字）為硬性配套，缺一不可。

### R4 — 相機綁定失敗無可觀測信號＋引擎啟停競態（K-02／K-14）

- **證據**：綁定 `catch` 只 `Log.e`（`FaceTrackerEngine.kt:233-240`），無回呼、無旗標、無外拋；`startEngine` 捕捉失敗進 ERROR 的路徑覆蓋不到非同步綁定失敗（`GazeAccessibilityService.kt:391-431` 註解自承）；`start()`／`startEngine` 無 generation 守衛（交接期間另有 `startEngine` 呼叫時兩實例可能交錯，PLAN §1.3）；`stop()` 的幀槽 generation 只用於度量驗證，不構成啟停守衛。
- **影響**：相機綁定失敗時使用者只看到空白／無游標，無原因、無重試入口；引導的服務驗證只能靠樣本新鮮度間接擋下（且新鮮度不等於看得到臉，IMPLEMENTATION_NOTES 出入 22）。
- **觸發條件**：相機被佔用、權限在服務建立後被收回、快速反覆啟停、交接期間重入。
- **緩解／下一步**：引擎層新增可觀測信號（綁定結果回呼／旗標），屬後續設計（IMPLEMENTATION_NOTES 出入 21）；本階段不得以樣本新鮮度冒充綁定成功。

### R5 — 邊緣手勢：幾何改動＋門檻未校準＋無取消

- **證據**：`edgeBandPx` 50 dp × 密度（`EdgeGestureController.kt:106-111`），高密度裝置（SM-S9180）物理寬度約為 v0.5.9 50 px 的 4 倍（IMPLEMENTATION_NOTES 出入 24）；`DISPERSION_MAX_PX_UNCALIBRATED = Float.MAX_VALUE`（`:509`，永不阻擋）；連續 present 幀數 2、方向死區 2 px、確認寬限 500 ms 皆標 UNCALIBRATED／待校準；`onCancelled` 缺失、雙擊第二下無取消（M11）。
- **影響**：誤觸率／漏觸率可能較 v0.5.9 大幅變化；離散度檢查名存實亡；誤派發的手勢無法取消，使用者須等手勢完成。
- **觸發條件**：日常注視掃過邊緣、閱讀時視線漂移、高密度／小螢幕裝置、方向改變。
- **緩解／下一步**：實機 E-9 以 v0.5.9 為對照量測誤觸／漏觸後再定帶寬與門檻；補 `onCancelled`＋雙擊取消（PLAN D3-7 dispatch 部分，另排一步）；校準前不得宣稱邊緣手勢「已加固」。

### R6 — 首次校正注視選擇無法判讀（眼控可達性缺口）

- **證據**：`onChoiceSample` 吃舊模型預測（`CalibrationActivity.kt:519-564`）；IMPLEMENTATION_NOTES 出入 14 明載：首次校正（無舊模型）時詢問／重試頁的注視選擇無法判讀，按鈕只剩手點、系統返回仍在。
- **影響**：最需要眼控的使用者在第一次校正遇到逾時／失敗時，無法只用眼睛退出或重試，須他人協助，違背「除系統授權外皆有眼控出路」的設計原則（STATE_DESIGN §0 原則 4）。
- **觸發條件**：首次校正單點逾時 3 次、存檔失敗、交接逾時。
- **緩解／下一步**：D-4 決策須包含無模型時的返回路徑（見 `CANCEL_CONTRACT.md` §0.3）；在 D-4 定稿前不得宣稱校正頁已眼控可達。

### R7 — 校正取消目標僅預留（D1-9，待 D-4）

- **證據**：僅 4 個 companion 常數、零引用（`CalibrationActivity.kt:1111-1125`）；`onDestroy` 無條件 `resumeAfterCalibration`（`:865-873`）。
- **影響**：校正中途無法眼控取消；只能靠系統返回／手動離開，且離開語義（舊模型保留）只在 `exitCalibration` 註解中，未形成可見的取消出路。
- **緩解／下一步**：D-4 三選項待決（見 `D4_OPTIONS.md`）；取消契約已先建立（`CANCEL_CONTRACT.md`），實作須待決策後，不得擅自定稿形式／秒數。

### R8 — Supabase anon key 內嵌於 App 程式碼

- **證據**：`GazeLog.kt:39-41` 有 Supabase URL 與 anon key 常數（值不抄錄）；註解稱 RLS 僅允許 INSERT、讀取用 service_role 金鑰（絕不進 App）。
- **影響**：anon key 隨 APK 可提取；若伺服端 RLS 設定與註解不符（未驗證），可能導致未授權讀取／寫入。即使 RLS 正確，key 外洩仍使端點可被濫用寫入垃圾資料。
- **緩解／下一步**：驗證伺服端 RLS／資料表實際設定（本任務未檢查）；調校完成後刪除 Supabase 專案並移除整段程式（既定決定）；不得將 service_role 金鑰寫入 App（紅線）。

### R9 — CI 無測試、無 gradlew，本地建置鏈脆弱

- **證據**：兩 workflow 皆無測試步驟；repo 無 `gradlew`（只見 wrapper jar）；本機 Gradle 因沙箱 JVM loopback 永遠跑不起來（IMPLEMENTATION_NOTES §0）；GitHub App 無 workflows 權限，改 workflow 須走瀏覽器＋使用者登入（403 限制，見架構圖 §3.9）。
- **影響**：回歸只能靠 CI 編譯守門；測試步驟缺失使純邏輯回歸無自動守門；workflow 修改路徑受限，CI 改進須使用者親自操作。
- **緩解／下一步**：CI 加測試步驟須走瀏覽器修改 workflow（使用者操作）；本機 gate 維持 kotlinc harness（僅純 Kotlin 類別）；推送邊界：未經授權不得 push／發版（見 R20）。

### R10 — 更新下載無完整性校驗

- **證據**：`UpdateChecker.startDownload()` 用 `DownloadManager` 下載 APK（`:103`），`installApk()` 直接經 `FileProvider` 跳安裝（`:124`），程式碼中無雜湊／簽章校驗呼叫（grep 無 checksum／簽名驗證）。
- **影響**：下載過程被竄改／中間人替換時，App 會引導安裝未經驗證的 APK（仍需使用者在安裝畫面親自確認，Android 簽名不一致時覆蓋安裝亦會失敗——此為系統層緩解，非 App 層校驗）。
- **觸發條件**：不安全網路下的更新下載（GitHub releases 為 HTTPS，風險較低但非零）。
- **緩解／下一步**：後續批次考慮 release asset 雜湊校驗或簽名比對；現況不得宣稱更新鏈已驗證完整性。

### R11 — 品質門檻皆為經驗值／未校準，報告頁等級可能誤導

- **證據**：等級門檻 60／120／200 px 為經驗值（`CalibrationActivity.kt:718-724` 附近，報告頁保留經驗值性質說明）；`EyeImageMetrics` 降級門檻「啟發式、未經實機校準」（`:38-50`）；邊緣門檻 UNCALIBRATED（R5）；D-12（門檻是否改解析度正規化）未決。
- **影響**：使用者可能把「極佳／優良」等級當作精度保證；降級幀判定過嚴／過鬆皆影響游標凍結頻率。
- **緩解／下一步**：報告頁保留經驗值說明（已做）；門檻校準待實機量測（E-10 等）；D-12 決策前不得改門檻。

### R12 — 文案絕對表述殘餘（K-12）

- **證據**：`accessibility_desc`（`strings.xml:3`）；IMPLEMENTATION_NOTES 遺留 (e)：STEP_CAMERA／歡迎頁已修部分，`SettingsActivity` 對話框、主畫面頁尾、`strings.xml` 仍為絕對表述；引導 STEP_NOTICE 已補監控例外段（D1-4）。
- **影響**：系統設定頁的無障礙服務描述與實際上傳行為矛盾（同 R1）；使用者信任與上架審查風險。
- **緩解／下一步**：後續批次統一修正全部絕對表述（以監控例外段為準）；本任務未逐一核對全部文案位置，標待定位。

### R13 — 模型與設定明文儲存、備份未定

- **證據**：模型 JSON 明文存 SharedPreferences（`CalibrationStore`）；`allowBackup=true`（Manifest）；無加密儲存、無金鑰管理程式碼。
- **影響**：模型為使用者個人校正資料（間接反映眼球特徵分布）；備份／還原行為未定，換機還原舊模型可能配新裝置失準；root 裝置可讀取。
- **觸發條件**：裝置備份還原、換機、裝置遺失。
- **緩解／下一步**：發行準備時決定備份策略與是否加密；現況不得宣稱模型已受保護。

### R14 — 無障礙服務權限範圍大（typeAllMask＋可取視窗內容）

- **證據**：`accessibility_service_config.xml`（`typeAllMask`、`canRetrieveWindowContent=true`）；`onAccessibilityEvent = Unit`（不處理事件，`GazeAccessibilityService.kt:1634`）。
- **影響**：宣告範圍超出實際使用（服務只用手勢注入＋overlay，不讀事件／節點內容——節點查詢呼叫待定位）；上架審查可能要求縮小宣告或說明必要性；使用者看到的授權描述與實際能力落差。
- **緩解／下一步**：確認是否可縮小 `accessibilityEventTypes`／`canRetrieveWindowContent` 而不影響功能；縮小前不得擅改（須實機驗證手勢注入不受影響）。

### R15 — 臉遺失時濾波器不重置、恢復序列未完整實作

- **證據**：臉遺失分支只清 dwell（服務 `:514-545`），未重置 `filterX/filterY`；重置只在 `startEngine` 與旋轉時；STATE_DESIGN §4.2 的 7 步恢復序列未實作（僅 D2-3 重置部分）。
- **影響**：臉回來第一幀即可累積 dwell，舊濾波狀態可能把游標拉向舊位置，造成恢復後誤觸。
- **緩解／下一步**：完整恢復序列屬核心演算法階段（D-14 連帶）；本階段不得以現況分支冒充恢復序列已完成。

### R16 — 陀螺儀修正可能重複計算（預設關為緩解，但符號未驗證）

- **證據**：`GazeModel.predict()` 註解自述 pitch 方向可能與 kx／ky 重複計算（`:64-70`）；`correctForDeviceTilt` 旋轉符號約定「需實機驗證（錯了改一行）」（`:285` 註解）；開關預設關（`gyroCompensation=false`）。
- **影響**：若使用者自行開啟，未經驗證的符號可能使修正反向、游標更偏。
- **觸發條件**：使用者在設定頁開啟陀螺儀修正。
- **緩解／下一步**：實機開／關對照確認有效再開（MEMORY 既定要求）；符號驗證前不得預設開啟、不得宣稱修正有效。

### R17 — 單一協調者複雜度集中（服務 1982 行）

- **證據**：`GazeAccessibilityService.kt` 1982 行，集狀態機、主路徑、面板／邊緣／提醒／重啟、dispatch、快照、靜態控制於一身；狀態只存在布林欄位與時間戳（Phase 3 已加 `AppFlowState` 影子→接線，檔頭文字已過時）。
- **影響**：修改任一分支易波及他分支（如 `stopTracking` 改語義影響三處，PLAN D2-1 風險評高）；審查與測試成本高。
- **緩解／下一步**：後續重構須小步＋每步測試（MASTER 紅線：不得一次重寫）；本階段不得重構服務本體。

### R18 — 授權切換未定（MIT 現況 vs AGPL 目標）

- **證據**：現行 `LICENSE` 為 MIT；MASTER 第三份規格要求 AGPL-3.0 最強版本，但切換批准與時程未決、法定姓名／年份／信箱未確認（MEMORY 記載）。
- **影響**：若在稽核完成前擅改 LICENSE，可能造成授權不相容或貢獻者權利爭議；公開 repo 已以 MIT 授權散布，追溯切換須法律稽核。
- **緩解／下一步**：Research & IP Master Audit 先行（`~/workspace/eyecontrol-research-ip/`）；不得直接改 LICENSE；AI 不得列為作者／發明人。

### R19 — 第三方授權清單缺失

- **證據**：Repo 內無 NOTICE／第三方授權檔（見架構圖 §3.11）；依賴含 MediaPipe／CameraX／AndroidX／Material；`face_landmarker.task` 來源雜湊未比對。
- **影響**：上架／發行時第三方授權揭露不完整，可能違反依賴授權的聲明義務。
- **緩解／下一步**：發行準備批次補齊第三方授權清單與模型來源說明。

### R20 — 推送／發行邊界（流程風險）

- **證據**：推送邊界（MEMORY）：未經明確授權不得 push、建遠端分支、建 PR、發布 Release 或更動正式版本標籤；批准後只推專用分支、絕不直接推 main、不自動發布 Release；workflow 修改須走瀏覽器（R9）。
- **影響**：誤推送未驗證的 Phase 3 實作到 main，將使未經驗證的程式碼成為公開 v0.5.9 後的正式內容；誤改 workflow 會 403 且可能中斷 CI。
- **緩解／下一步**：本任務全程未推送、未改 workflow；後續推送須逐項授權，並以專用分支＋CI 綠燈＋實機驗證為 gate。

## 2. 風險與模組對照（速查）

| 模組 | 主要風險 |
|---|---|
| M1／M2 相機管線 | R4（綁定無信號、競態）、R2（未驗證） |
| M3／M4 特徵／模型 | R11（門檻經驗值）、R13（明文儲存）、R16（陀螺儀重複計算） |
| M5 濾波 | R15（臉遺失不重置） |
| M6 校正 | R6（首次校正注視不可達）、R7（取消僅預留）、R11（等級門檻） |
| M7／M8 品質／辨識 | R2（不存在具名模組卻可能被誤稱為已完成） |
| M10／M11 仲裁／派發 | R5（無取消）、R17（複雜度集中） |
| M12 服務本體 | R3（K-30）、R4、R14（權限範圍）、R17 |
| M13 Overlay | R3（RECOVERY 相機持續運作的告知）、R5（邊緣帶幾何） |
| M14 設定 | R1／R12（文案與預設）、R16（開關預設） |
| M15 診斷 | R1（預設上傳）、R8（key 內嵌） |
| M16 更新 | R10（無完整性校驗）、R9（CI） |
| M17 Activities | R6（引導前段只能手點，K-23 已知限制） |
| 全域 | R2（零驗證）、R18／R19（授權）、R20（推送邊界） |

## 3. 已知但本清單不重複展開的項目

- `AppFlowState.kt` 檔頭「影子模式」文字過時（文件漂移，非程式風險）：見 `docs/architecture-map.md` §4，後續文件批次修正。
- Dashboard 死按鈕已移除，但相機權限請求 launcher 同遭移除、意圖待確認（baseline §8.1）：列為待確認事項，須協調員確認是否恢復請求路徑（引導 STEP_CAMERA 仍可請求權限）。
- 邊緣分項開關粒度（四邊分開 vs 側邊合一）：IMPLEMENTATION_NOTES 出入 31 記載待所有權人確認，維持四邊分開實作，不得擅自合併。
- 門檻校準待辦全清單（dispersion、穩定序列幀數、邊緣連續幀數、方向死區、邊緣帶上限、確認寬限）：見 `EdgeGestureController.kt` 的 `UNCALIBRATED` 常數與 `TEST_PLAN.md` §3.5（E-10），本清單以 R5／R11 概括，不逐項重列。
