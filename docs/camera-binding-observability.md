# EyeControl 相機綁定可觀測性設計（PHASE 6）

> **覆蓋聲明（DCL-06，擁有者正式確認，STEP 2 加註）**：本文件 §3.1 的九態提案（IDLE／INITIALIZING／PROVIDER_READY／BINDING／BOUND／STREAMING／FAILED／STOPPING／STOPPED）**退為參考**；DCL-06 的七態相機狀態機（IDLE／REQUESTING_PERMISSION／BINDING／BOUND／STREAMING／FAILED／STOPPING）**為準**，含權限拒絕、Provider 失敗、`bindToLifecycle` 例外、攝影機被占用、初始化期間生命週期停止、重新嘗試成功的處理，以及所有非同步回呼的生命週期世代識別要求。本文件的狀態定義、錯誤代碼、session／generation 機制與紀錄欄位皆為尚未實作的設計；七態狀態機須另立設計並經批准（見 `dcl-mapping.md` §5 矛盾 T-04、§6 待批 P-06），不得直接沿用九態設計改名。本聲明只加註、不改本文件其餘結論與內容。

> 性質：唯讀設計文件，只新增本文件，不改任何程式碼。
> 基線：`~/workspace/eyecontrol-p3`（v0.5.9 加未推送的 Phase 3 實作；遠端 main HEAD `c6b80964`）。
> 行號以 p3 樹為準，皆為撰寫時親自查證的現況行號。
> 本文件設計的 `CameraBindingState`、錯誤代碼、session／generation 機制與紀錄欄位，
> **皆為尚未實作的設計**，不得宣稱為現況。現況描述與設計提案在每節以「現況」／「設計」
> 嚴格區分。
> 先前文件複用：`eyecontrol-phase3/IMPLEMENTATION_NOTES.md` §1 出入第 8／21／22 點
> （同步啟動失敗已捕捉、非同步綁定失敗無可觀測信號、樣本新鮮度不等於看得到臉）、
> `PLAN.md` §1.1／§1.3（引擎啟停與交接協議現況）、`CANCEL_CONTRACT.md` 第 2／8 條
> （取消路徑只經 `stop()` 釋放、世代檢查模式）、`KNOWN_ISSUES.md` K-02／K-14／K-15／K-58。
> 任務書提及的 §10.3 清單未見於本次收到的先前文件，來源標「待定位」；
> 本文件的錯誤代碼依實際 API 呼叫點自行設計（見 §4），不得宣稱為 §10.3 原文。

## 0. 現況一句話

相機綁定在現況下只有三種可觀測輸出：同步例外（拋給呼叫端）、`Log.e` 文字、
以及「之後有沒有樣本」。Provider 是否就緒、是否真正綁定、是否開始出流、
綁定失敗的原因、晚到回呼是否覆蓋了新 session，**全部無法區分**。
`FaceTrackerEngine.start()` 的非同步綁定回呼失敗時只記 `Log.e("相機綁定失敗")`，
無回呼、無旗標、無外拋（`IMPLEMENTATION_NOTES.md` 出入第 21 點已記為已知缺口）；
服務端 `startEngine` 的同步 `catch` 只能覆蓋 landmarker 建立失敗，
覆蓋不了非同步綁定失敗（`GazeAccessibilityService.kt:391-425` 註解自承）。

## 1. 綁定流程映射（現況）

### 1.1 流程總圖（文字序列圖）

```
呼叫端（服務／校正頁／訓練頁）
  │  engine.start(lifecycleOwner)            FaceTrackerEngine.kt:122
  ├─► [同步] 建 FaceLandmarker options（setModelAssetPath / LIVE_STREAM / ResultListener / ErrorListener）
  │     │  FaceTrackerEngine.kt:130-186
  ├─► [同步] FaceLandmarker.createFromOptions ──失敗──► throw（Log.e 後往上拋，:187-194）
  ├─► [同步] 建 analysisExecutor（單執行緒）
  │     │  :196
  ├─► [非同步] ProcessCameraProvider.getInstance(context) → providerFuture
  │     │  :198
  │     └─► addListener({ … }, 主執行緒 executor)  :199-236
  │           ├─► providerFuture.get()（無 try/catch，:200）──失敗──► 例外沿主執行緒拋出（見 N3）
  │           ├─► 建 ImageAnalysis（1280×720、KEEP_ONLY_LATEST、RGBA_8888）:205-218
  │           ├─► setAnalyzer(analysisExecutor) :219
  │           ├─► provider.unbindAll() :222
  │           └─► provider.bindToLifecycle(owner, DEFAULT_FRONT_CAMERA, analysis) ──失敗──► catch → Log.e，:221-235
  │                 成功時回傳 Camera（:225-229），留作曝光補償用；無任何「已綁定」旗標／回呼
  └─► [非同步] ResultListener 收到結果 → onSample(face / null)；ErrorListener → Log.e（:185）
        analyze() → detectAsync（:266-285）；結果回呼在 MediaPipe 執行緒
```

### 1.2 三個消費者的啟動／交接路徑（現況，含行號）

| 消費者 | LifecycleOwner | 啟動入口 | 停止入口 | 交接方式 |
|---|---|---|---|---|
| `GazeAccessibilityService` | 自建 `LifecycleRegistry`（`GazeAccessibilityService.kt:47-48`；`onCreate` 設 `CREATED` `:315-318`，`onServiceConnected` 設 `RESUMED` `:320-322`，`onDestroy` 設 `DESTROYED` `:1607-1610`） | `startEngine()`（`:391` 起；守衛 `pausedForCalibration \|\| userStopped` 即返回，`:392`；先停舊引擎 `:395-396`，再建新引擎、同步 `catch` `:407-425`） | `engine?.stop()`（`onDestroy` `:1617`；`stopCompletely` 背景執行緒 `:1938-1940`；`startEngine` 冪等重啟 `:395`） | 被交接方：`requestPauseForCalibration`（見 §1.3） |
| `CalibrationActivity` | Activity 自身（`this`） | `startEngineSafely()`（`CalibrationActivity.kt:326` 起；`try { engine.start(this) } catch` → `enterError`，`:327-339`）；觸發點：交接回呼 `onHandoverReleased`（`:286` 起，檢查世代＋`RESUMED`＋`!engineStarted`）與 `onResume`（`:232` 起，`handoverDone` 分支） | `onPause`（`:248-256`，`engineStarted` 守衛＋`cancelHandoverTimeout`＋`resetChoiceDwell`）；`onDestroy`（`:865-873`，`::engine.isInitialized` 檢查後 `stop()`，再 `resumeAfterCalibration()`） | 主動交接方：`requestCameraHandover()`（`:272` 起，世代遞增、逾時 `POINT_TIMEOUT_MS`） |
| `TrainingActivity` | Activity 自身（`this`） | `requestCameraAndStart()`（`TrainingActivity.kt:195` 起；`cameraRequestPending` 守衛，`:197-198`；回呼內**無 try/catch**，`:206` 的 `engine.start(this)` 直接執行） | `onPause`（`:222-226`）、`onDestroy`（`:231-239`，`pausedService` 為真才 `resumeAfterCalibration()`） | 主動交接方：同一 `requestPauseForCalibration`，回呼檢查 `!isFinishing && !leaving && RESUMED`（`:201-208`）；`onResume` 重綁（`:213-217`） |

引擎實例化位置：服務 `GazeAccessibilityService.kt:404`（每 `startEngine` 新建）；
校正頁 `CalibrationActivity.kt:202`（`onCreate` 建一次）；訓練頁
`TrainingActivity.kt:110`（`onCreate` 建一次）。三者各持獨立的 `VerifyFrames`
實例（引擎內 `VerifyFrames(context.applicationContext)`，`FaceTrackerEngine.kt:76`），
但只有服務端經 `setVerifyCapture` 開啟開關（`GazeAccessibilityService.kt:79-83`）；
校正／訓練頁的引擎預設不產生影格。

### 1.3 交接協議現況（不得改動本體，見 PLAN §1.3）

`requestPauseForCalibration(onReleased)`（`GazeAccessibilityService.kt:1648` 起）：

1. `instance == null` 時直接同步回呼（`:1650-1653`）。
2. 否則立 `pausedForCalibration = true`（`:1654`），在**無名背景 `Thread`** 內停引擎
   （`:1656-1664`）：取 `target = svc.engine`，`target?.stop()`，以 `===` 確認仍是
   同一實例才置 null（K-14 的 `===` 守衛，現況已有）。
3. 切主執行緒 `mainHandler.post`（`:1665` 起）：藏游標、藏提醒／重啟目標、
   中斷邊緣手勢、面板 `GONE`，同步流程態為 `CALIBRATING`，最後呼叫 `onReleased()`。
   **無逾時、無失敗回呼**：背景停引擎卡住、或 `instance` 中途變 null 時，
   呼叫端永遠等不到回呼（校正頁以自設逾時＋世代檢查兜底，見 §1.2；訓練頁無逾時兜底）。
4. `resumeAfterCalibration()`（`:1688` 起）：`mainHandler.post` 內清旗標、面板恢復、
   `startEngine()`；`instance == null` 時直接返回。

**設計約束**：本文件不得改動協議本體（呼叫順序、`===` 身分確認、無逾時語義、
回呼順序）。可觀測性只能以「新增狀態／紀錄／回呼參數」的方式**疊加**在協議之外，
且疊加不得改變協議的時序語義；若疊加需要協議內新回呼，須另立決策，不得在本設計中
假裝已定（見 §7 未決點）。

### 1.4 停止路徑現況

`FaceTrackerEngine.stop()`（`FaceTrackerEngine.kt:506` 起），順序：

1. `closed = true`（`:508`）→ 停感測器（`:509-510`）→ `unbindOnMainThread()`（`:511`）
   → `cameraProvider = null`（`:512`）→ 清幀槽＋世代遞增（`:514-519`）
   → 清 `pendingCaptures`、`verifyFrames.endSession()`、清 `camera` 參照（`:520-528` 段）
   → 停 `analysisExecutor`（`awaitTermination(500ms)`，`:529-532` 段）
   → `landmarker.close()`（`:533` 段）→ 回收 Bitmap 緩衝（`:536` 起）。
2. `unbindOnMainThread()`（`:554` 起）：主執行緒直接 `unbindAll()`；背景執行緒
   排到主執行緒並以 `CountDownLatch` 等最多 2 秒，**逾時放行**（`:574` 段，
   `latch.await(2, SECONDS)` 包在 `runCatching` 內，逾時無任何紀錄）。

## 2. 缺少觀測信號的節點（共 18 個，分 9 類）

計數方法：一個「節點」＝一個「程式執行到此、外部（呼叫端／Dashboard／診斷紀錄）
無法得知結果或原因」的檢查點；同一 `catch` 內不同失敗原因分開計數，
同一原因在不同呼叫端出現只計一次（以引擎層為準）。

| 類別 | 節點 | 現況證據（檔案：行） | 缺什麼 |
|---|---|---|---|
| A. 權限 | N1. `start()` 不檢查相機權限 | `FaceTrackerEngine.kt:122` 起全函式無 `checkSelfPermission`（K-58） | 無權限時仍會走到 Provider／綁定，失敗原因無法區分為「無權限」 |
| A. 權限 | N2. 權限在綁定後被撤銷 | 無任何權限撤銷監聽／重查 | 綁定成功後撤銷權限，串流靜默停止，無狀態變化 |
| B. Provider 初始化 | N3. `providerFuture.get()` 無防護 | `FaceTrackerEngine.kt:199-200`：listener 內直接 `get()`，無 try/catch；執行緒為主執行緒 executor（`:236`） | `ExecutionException`／`InterruptedException` 沿主執行緒拋出（K-15），無錯誤代碼、無重試信號 |
| B. Provider 初始化 | N4. Provider 取得失敗／逾時無失敗路徑 | `getInstance` 無逾時、無失敗回呼；`cameraProvider` 欄位（`:40`）只在成功時賦值 | 無法區分「Provider 尚未就緒」與「Provider 永遠拿不到」 |
| C. CameraSelector | N5. 前鏡頭不存在／不可用 | `CameraSelector.DEFAULT_FRONT_CAMERA`（`FaceTrackerEngine.kt:227`）；綁定 `catch` 只記通用 Log（`:233-235`） | 無法區分「無前鏡頭」「前鏡頭被其他 App 佔用」「Selector 參數錯誤」 |
| C. CameraSelector | N6. 實際綁定的相機身分未知 | `camera` 欄位（`:53-55`）只存 `Camera` 物件，無 cameraId／ facing 記錄 | 診斷時無法確認綁的是哪顆相機 |
| D. UseCase 綁定 | N7. `bindToLifecycle` 失敗原因被吞掉 | `FaceTrackerEngine.kt:221-235`：`unbindAll`＋`bindToLifecycle` 同在一個 `try`，`catch (e: Exception)` 只 `Log.e("相機綁定失敗", e)` | 無錯誤代碼、無旗標、無回呼；`analysisExecutor` 已建、`analyzer` 已設，但綁定失敗後引擎欄位仍非 null，呼叫端以為引擎在跑 |
| D. UseCase 綁定 | N8. 解析度 fallback 無信號 | `ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`（`:209-212`） | 實際綁定的解析度未知，無法判斷是否為 fallback 結果 |
| D. UseCase 綁定 | N9. 綁定成功但無幀（analyzer 未被呼叫） | `setAnalyzer`（`:219`）後無任何「首幀到達」信號 | 無法區分 BOUND（已綁定）與 STREAMING（已出流）；見 §3 的嚴格區分要求 |
| E. LifecycleOwner | N10. 綁定時 LifecycleOwner 狀態未記錄 | `start(lifecycleOwner)`（`:122`）不檢查、不記錄 owner 狀態；listener 在主執行緒稍後執行，期間 owner 可能暫停／銷毀 | 無法區分「owner 未 RESUMED 故未綁定」與「綁定失敗」；校正頁回呼內的 `RESUMED` 檢查（`CalibrationActivity.kt:293`）只保護「啟動引擎」一步，不保護 Provider listener 內的綁定 |
| E. LifecycleOwner | N11. 服務 LifecycleRegistry 狀態變化無綁定關聯紀錄 | `GazeAccessibilityService.kt:47-48,315-322,1607-1610`：狀態變化只設 registry 值，無任何綁定狀態紀錄 | 服務重建後，無法從紀錄判斷綁定是在哪個 lifecycle 狀態下發生的 |
| F. 相機占用 | N12. `unbindAll()` 的前任佔用無信號 | `FaceTrackerEngine.kt:222` 的 `unbindAll()` 無回傳、無紀錄；`stop()` 的 `unbindOnMainThread` 逾時放行無紀錄（`:554` 起） | 無法得知「上一個引擎是否已釋放」「本次 unbind 是否完成」；快速啟停時新舊綁定可能交錯（K-02 殘留） |
| F. 相機占用 | N13. 晚到 Provider listener 在 `stop()` 後重綁 | listener 閉包未捕獲 generation／`closed` 檢查（`FaceTrackerEngine.kt:199-236` 全段無 `closed` 檢查） | `stop()` 已立 `closed=true`、已清 `cameraProvider`，晚到的 listener 仍會 `get()`、`unbindAll()`、`bindToLifecycle`，在已停的引擎上殘留綁定、佔住相機（K-02 原文描述的競態，現況仍在） |
| G. 前景服務生命週期 | N14. 服務被系統殺掉／重建後，舊綁定的殘留狀態未知 | `onDestroy`（`GazeAccessibilityService.kt:1607` 起）停引擎、關 `GazeLog`、移除 overlay，但無綁定狀態紀錄；新實例的 `onServiceConnected` 直接 `startEngine()`（`:344`） | 無法區分「全新啟動」與「重建後恢復」，無法得知舊綁定是否已釋放（見 `restart-recovery-policy.md`） |
| G. 前景服務生命週期 | N15. 前台服務通知／`startForeground` 失敗無信號 | `startForegroundService()`（`:366` 起）無 try/catch、無結果檢查 | 通知建立失敗時服務仍繼續 `startEngine()`，無狀態區分 |
| H. 非同步回呼過晚 | N16. ResultListener／ErrorListener 只有 Log | `ErrorListener`（`FaceTrackerEngine.kt:185`）只 `Log.e`；`detectAsync` 失敗只 `Log.e`（`:283`，且 `closed` 時靜默） | 無錯誤代碼、無「串流已停」信號；`ErrorListener` 觸發後，呼叫端仍以為串流正常 |
| H. 非同步回呼過晚 | N17. 無「串流停滯」偵測 | 全樹無首幀／末幀時間戳（引擎層）；只有服務層 `lastSampleAtMs`（`GazeAccessibilityService.kt` 快照用） | 綁定成功但 MediaPipe 不再回結果時，引擎層無任何信號；服務層只能以 3 秒新鮮度間接推斷（且 null 樣本亦更新時間戳，見 IMPLEMENTATION_NOTES 出入第 22 點） |
| I. Service 已停但回呼續行 | N18. 交接背景執行緒的晚到 `post` 無世代檢查 | `requestPauseForCalibration` 的背景 `Thread`（`GazeAccessibilityService.kt:1656` 起）與 `resumeAfterCalibration` 的 `mainHandler.post`（`:1689` 起）皆無 generation；`stopCompletely` 的背景執行緒（`:1938` 起）以 `===` 守衛引擎實例，但 `post` 內的狀態同步無世代檢查 | 服務銷毀／新引擎接手後，晚到的 `post` 仍會藏／顯面板、同步流程態、呼叫 `onReleased`，可能覆蓋新 session 的狀態（K-14 殘留結構） |

**總計：18 個節點，9 類（A–I）。** 其中 N7／N13／N16 為 P0 級（直接導致「畫面空白、無原因、無重試」）；
N3／N10／N18 為 P1 級（競態／閃退面）；其餘為 P2 級（診斷／可維護性面）。
本節只記錄現況缺口，不宣稱任何一項已修復。

## 3. `CameraBindingState` 設計（新增狀態機，尚未實作）

### 3.1 狀態定義（嚴格區分 Provider 就緒與真正綁定出流）

狀態由 `FaceTrackerEngine` 持有（單一事實來源），`@Volatile` 可見；
呼叫端只讀，不得直接寫入。狀態變化一律經 §3.3 的轉換函式，並寫入 §6 的紀錄。

| 狀態 | 進入條件（必須全部滿足） | 含義 | 允許的下一步 |
|---|---|---|---|
| `IDLE` | 引擎新建、或 `stop()` 完成後 | 未啟動，無 Provider、無綁定、無串流 | `INITIALIZING`（`start()`） |
| `INITIALIZING` | `start()` 已進入、landmarker 建立中／已成功、Provider 尚未就緒 | 正在準備，尚未拿到 Provider | `PROVIDER_READY`、`FAILED`（landmarker 建立失敗） |
| `PROVIDER_READY` | `providerFuture.get()` 成功、`cameraProvider` 已賦值，**尚未** `unbindAll`／`bindToLifecycle` | Provider 已就緒，**不等於**相機已綁定、**不等於**有串流。此態與 `BOUND` 的區分是本設計的核心：舊程式以「listener 已執行」暗示「已綁定」，現況 N7／N9 證明該暗示不成立 | `BINDING`、`FAILED`（`get()` 失敗）、`STOPPING`（`stop()` 介入） |
| `BINDING` | 已呼叫 `unbindAll()`、正在／剛呼叫 `bindToLifecycle()`，尚未收到回傳 `Camera` | 正在綁定，結果未知 | `BOUND`、`FAILED`（綁定例外） |
| `BOUND` | `bindToLifecycle()` 已回傳非 null `Camera`，`analyzer` 已附著，**尚未**收到第一個 `ResultListener` 結果 | 已綁定、未出流。游標不得顯示、不得宣稱「追蹤中」；Dashboard 只能顯示「相機已綁定，等待畫面」 | `STREAMING`（首個結果到達）、`FAILED`（綁定後立即失敗，見 N16）、`STOPPING` |
| `STREAMING` | 已收到至少一個 `ResultListener` 結果（含 `face == null` 的 null 樣本） | 已出流。`face == null` 屬正常串流狀態（臉遺失），不離開本態；離開本態的唯一原因是錯誤／停止／停滯（見 §3.2） | `FAILED`（`ErrorListener`／停滯）、`STOPPING` |
| `FAILED` | 任一環節失敗且已記錄錯誤代碼（見 §4） | 終態（須明確重試才離開）。`FAILED` 必須攜帶 `errorCode`＋`failureReason`，不得以裸 `FAILED` 呈現 | `INITIALIZING`（明確重試：新 session）、`STOPPING`（`stop()` 清理） |
| `STOPPING` | `stop()` 已進入、`closed` 已立，釋放尚未全部完成 | 正在釋放；此態內到達的晚到回呼一律按 §5 的 generation 檢查丟棄 | `STOPPED`（釋放全部完成，含 unbind 確認） |
| `STOPPED` | `stop()` 全部步驟完成：unbind 已確認、executor 已終止、landmarker 已關閉、Bitmap 已回收 | 已完全停止，可再次 `start()`（新 session） | `INITIALIZING`（`start()`，新 session） |

**禁止的轉換**（轉換函式必須拒絕並記錄，不得靜默接受）：

- `IDLE → BOUND／STREAMING`（未經 Provider／綁定直接宣稱已綁定）；
- `PROVIDER_READY → STREAMING`（跳過綁定）；
- `BOUND → INITIALIZING`（未經停止直接重啟）；
- `FAILED → BOUND／STREAMING`（未經重試直接恢復）；
- 任何態 → `IDLE`（`IDLE` 只在建構時存在；`stop()` 完成後為 `STOPPED`，不得回到 `IDLE` 混淆「從未啟動」與「已停止」）。

### 3.2 串流停滯（`STREAM_STALLED` 的判定，門檻待校準）

- 判定量：引擎層記錄 `lastResultAtMs`（`elapsedRealtime`，每次 `ResultListener` 到達即更新，
  含 null 樣本）與 `lastFrameAtMs`（每次 `analyze()` 收到幀即更新）。
- 停滯定義（邏輯已定、數值待定）：`STREAMING` 態下，`now - lastResultAtMs` 超過停滯門檻，
  且 `now - lastFrameAtMs` 未超過同一門檻（幀還在進、結果不再來）→ 轉 `FAILED(STREAM_STALLED)`。
  若兩者皆停（幀也不進），優先判定為綁定／Lifecycle 問題，不得直接定為停滯。
- **門檻值不得在本設計中編造**：須以實機量測正常幀間隔分布後校準，程式中以具名常數
  `STREAM_STALL_THRESHOLD_MS_UNCALIBRATED` 佔位、預設不觸發（或只記錄、不轉態，
  二選一待實作批次決定），並在測試計畫中列為校準待辦。不得以未驗證數字假裝已偵測。

### 3.3 狀態持有與執行緒規則（設計）

1. 狀態欄位與 `bindingGeneration`（見 §5）皆為 `@Volatile`；寫入只在狀態轉換函式內，
   轉換函式本身不要求特定執行緒（Provider listener 在主執行緒、ResultListener 在
   MediaPipe 執行緒、`stop()` 可在任意執行緒），但**同一 session 內的轉換必須以
   generation 檢查序列化**：轉換函式先比對傳入的 generation 與當下 generation，
   不符即拒絕並記錄（見 §5）。
2. `FAILED` 進入時必須同時寫入 `errorCode` 與 `failureReason`（見 §4／§6），
   兩者缺一不可；`FAILED` 離開時（重試／停止）必須先清除舊的 `errorCode`，
   不得讓舊錯誤殘留到新 session。
3. 呼叫端（服務／校正頁／訓練頁）不得直接讀取引擎內部欄位推斷狀態；
   對外只暴露 `bindingState: CameraBindingState`（唯讀）與
   `lastBindingError: BindingError?`（唯讀，含代碼與使用者訊息，見 §4）。
   服務的 `AppFlowState` 與綁定狀態的對應關係（例如 `FAILED` → `ERROR`）由服務層
   在收到狀態變化回呼後決定，引擎層不直接寫服務的流程態。

## 4. 錯誤代碼設計（依實際 API 調整，非 CameraX 原生代碼）

**前提聲明**：CameraX／MediaPipe 在本程式呼叫點上**沒有**提供統一的公開錯誤代碼列舉；
以下代碼為本文件新定義的**內部**代碼，僅供引擎→呼叫端→使用者訊息的映射使用，
不得宣稱為 CameraX／MediaPipe 的原生代碼。§10.3 清單原文待定位（見文件頭），
本表不得宣稱為其轉錄。

| 代碼 | 偵測點（現況檔案：行） | 可用的原始資訊（例外型別／API 回傳） | 使用者可理解訊息（不得含堆疊） | 是否可重試 |
|---|---|---|---|---|
| `CAM_PERMISSION_NOT_GRANTED` | 新增：`start()` 入口預檢（現況無此檢查，N1） | `checkSelfPermission(CAMERA)` 結果 | 「尚未允許相機權限，請先允許相機權限再開始。」＋導引至權限請求／系統設定 | 是（授權後重試） |
| `LANDMARKER_CREATE_FAILED` | `FaceTrackerEngine.kt:187-194`（`createFromOptions` 的 `catch`，現況已拋出） | 拋出的 `Exception` 型別與訊息（模型檔損壞／記憶體不足等，現況未區分） | 「眼睛偵測模型啟動失敗，請重新啟動 App；若持續發生，請重新安裝。」 | 是（重試一次；持續失敗須導引重裝／回報） |
| `PROVIDER_UNAVAILABLE` | `FaceTrackerEngine.kt:199-200`（`providerFuture.get()`，現況無防護，N3／N4） | `ExecutionException`／`InterruptedException`（`get()` 的受檢例外）；`get()` 逾時須新增逾時等待（設計，逾時值待定，不得編造） | 「相機服務暫時無法啟動，請稍後再試。」 | 是 |
| `FRONT_CAMERA_UNAVAILABLE` | `FaceTrackerEngine.kt:221-235` 的綁定 `catch` 內，以例外型別區分（設計） | `IllegalArgumentException`（無符合的相機／Selector 無解）等綁定例外；須在 `catch` 內以型別＋訊息關鍵字區分，不得僅憑訊息文字比對作唯一依據（訊息文字可能隨版本變化，區分邏輯須以型別為主） | 「找不到前鏡頭，請確認裝置有前鏡頭且未被其他 App 佔用。」 | 否（硬體／佔用問題，重試前須先排除佔用） |
| `BIND_FAILED` | 同上綁定 `catch`（其餘例外） | 綁定例外（`CameraX` 內部例外、狀態例外等） | 「相機綁定失敗，請重試；若持續發生，請重新啟動 App。」 | 是 |
| `LIFECYCLE_NOT_READY` | 新增：綁定前檢查 owner 狀態（現況無此檢查，N10） | `lifecycle.currentState`（服務的 `LifecycleRegistry`／Activity 的 `lifecycle`） | 「畫面尚未準備好，請稍後再試。」（內部訊息；使用者一般不會看到，須有重試路徑，不得顯示為錯誤） | 是（自動重試一次，由呼叫端在 `RESUMED` 後重發） |
| `CAMERA_IN_USE` | 新增：綁定失敗且例外指向佔用時（設計；現況 N5／N12 無區分） | 綁定例外型別／訊息（以型別為主）；輔助證據：前一 session 的 unbind 未確認（見 §6 的釋放確認欄位） | 「相機正被其他功能佔用，請先關閉其他相機功能再重試。」 | 是（排除佔用後） |
| `UNBIND_TIMEOUT` | `FaceTrackerEngine.kt:554` 起的 `unbindOnMainThread`（現況逾時放行無紀錄，N12） | `CountDownLatch.await(2s)` 逾時（現況常數 2 秒，`FaceTrackerEngine.kt:574` 段） | 不直接顯示給使用者（內部狀態）；對外顯示為「相機釋放逾時，正在重試釋放」，並禁止在未確認釋放前開始新綁定 | 是（重試 unbind；逾時後的新綁定須等確認或走 `FAILED`） |
| `LANDMARKER_RUNTIME_ERROR` | `FaceTrackerEngine.kt:185` 的 `ErrorListener`（現況只 Log，N16） | `ErrorListener` 傳入的例外 | 「眼睛偵測發生錯誤，請重新開始眼控。」 | 是 |
| `STREAM_STALLED` | 新增：引擎層停滯偵測（見 §3.2，門檻待校準，N17） | `lastResultAtMs`／`lastFrameAtMs` 時間戳差 | 「追蹤畫面停止更新，請重新開始眼控。」 | 是 |
| `ENGINE_STOPPED_LATE_CALLBACK` | 新增：generation 檢查（見 §5，N13／N18） | generation 不符（內部事件，不屬使用者錯誤） | **不顯示給使用者**（內部丟棄事件，只寫診斷紀錄）；不得以此代碼向使用者報錯 | 不適用（丟棄即可） |

**使用者訊息規則**：

1. 訊息一律為白話＋下一步行動，不得含例外類別名、不得含 `e.message` 原文、
   不得含堆疊（現況 `CalibrationActivity.kt:335` 的
   `message = "相機啟動失敗：${e.message}"` 屬須修正的現況，見 §7 未決點）。
2. 訊息只在「需要使用者知道／行動」時顯示（`FAILED` 且須重試／授權／排除佔用時）；
   內部事件（`UNBIND_TIMEOUT` 的中間態、`ENGINE_STOPPED_LATE_CALLBACK`、
   `LIFECYCLE_NOT_READY` 的自動重試）不得以錯誤對話框打擾使用者，
   只寫診斷紀錄（見 §6）。
3. 校正頁的 ERROR 報告頁、服務的 Dashboard 狀態列、引導頁的驗證失敗提示，
   三處顯示同一代碼時必須用同一訊息表，不得各寫一套（訊息表集中管理，位置待實作批次定）。

## 5. Session ID 與 Generation Token 保護（設計）

### 5.1 機制

- `bindingGeneration: Long`（`@Volatile`，初始 0，單調遞增，永不重用、永不回繞到舊值；
  行程重建後新引擎實例從 0 開始，**行程內唯一性已足夠**，不得以此假裝跨行程唯一）。
- `start()` 進入時：先 `stop()` 舊 session（現況 `:125` 的防呆保留），再遞增
  generation，產生新 `sessionId`，狀態 → `INITIALIZING`。
- `sessionId` 格式（設計）：`"bind-" + generation + "-" + startElapsedRealtimeMs`，
  其中時間戳取 `SystemClock.elapsedRealtime()`（單調時鐘，只供區分與排序，
  不含 wall-clock 日期、不含使用者資訊、不含裝置資訊）。`sessionId` 只出現在
  診斷紀錄與 Logcat，不得寫入 CSV／影格 JSON（避免與 `GazeLog` 的檔名 session
  概念混淆；`GazeLog.currentSessionId()` 為 CSV 檔名 id，兩者**不得混用**）。
- `stop()` 進入時：先遞增 generation（使全部進行中的非同步等待失效），再立
  `closed = true`，再執行現況釋放順序（見 §1.4）。遞增必須在 `closed` 之前、
  在任何 `await`／`post` 之前，不得插入非同步等待（沿用 `CANCEL_CONTRACT.md`
  第 8 條的認領模式：檢查與立旗標之間不得插入等待）。
- 閉包捕獲：Provider listener、`ResultListener`、`ErrorListener`、交接背景執行緒的
  `post`，一律捕獲啟動當下的 `(generation, sessionId)`；執行時先比對當下 generation，
  不符即**直接返回、不得執行任何綁定／unbind／狀態同步／回呼**，並以
  `ENGINE_STOPPED_LATE_CALLBACK` 寫一筆診斷紀錄（見 §6，含舊／新 generation，
  不含影像資料）。

### 5.2 快速啟停保護（設計）

| 情境 | 現況問題 | 設計保護 |
|---|---|---|
| `start()` → 立即 `stop()` → 立即 `start()` | 舊 Provider listener 晚到，在新 session 的 Provider 上 `unbindAll`／重綁（N13） | 舊 listener 的 generation 已過期，直接返回；新 session 的 listener 以新 generation 重新 `get()`／綁定 |
| `stop()` 期間 `start()` 被呼叫 | `stop()` 的 `unbindOnMainThread` 逾時放行後，新綁定與舊 unbind 交錯（N12） | `stop()` 進入 `STOPPING` 態；`start()` 在 `STOPPING` 態時必須等待釋放確認（`STOPPED`）或走 `FAILED(UNBIND_TIMEOUT)`，不得直接綁定；等待須有逾時（逾時值待定，不得編造）與明確失敗路徑，不得無限等待 |
| 交接期間 `resumeAfterCalibration` 與 `stopCompletely` 交錯 | 晚到 `post` 覆蓋新狀態（N18） | 兩路徑皆捕獲 generation；`post` 執行時重查 generation 與 `instance === this`，不符即丟棄 |
| 校正頁重試交接（世代遞增） | 現況已有 `handoverGeneration`（`CalibrationActivity.kt:107,276-277,287`），但只保護交接回呼與逾時，不保護引擎綁定 | 交接世代與綁定 generation **分開管理、不得混用**：交接世代管「哪一次交接請求」，綁定 generation 管「哪一次引擎 session」；重試交接時，舊引擎 session 的 listener 仍以綁定 generation 判定，不得以交接世代冒充 |

### 5.3 不得發生的事（設計約束）

- 舊 callback 不得覆蓋新 session 的狀態、不得重新綁定、不得重新 `unbindAll`、
  不得呼叫新 session 的 `onReleased`／`onSample`。
- `stop()` 不得依賴 `cameraProvider = null` 作為唯一的重複保護；呼叫端守衛
  （`engineStarted`／`engine == null`）與引擎內 generation 檢查須同時存在（兩道防線，
  沿用 `CANCEL_CONTRACT.md` 第 9 條的模式）。
- `sessionId`／generation 不得寫入任何持久化位置（SharedPreferences／檔案），
  不得用於跨行程識別使用者或裝置。

## 6. 狀態變化紀錄欄位（設計）

### 6.1 紀錄去向與 K-12 合規

- 去向：**診斷紀錄（`GazeLog` 摘要／Logcat）只記狀態變化事件，不記每幀**。
  每幀寫入 CSV 的現況不變；綁定狀態變化**不得**新增 CSV 欄位（不得為狀態轉換
  新增假數據欄位，沿用 `PLAN.md` §1.2 的註記）。
- K-12 合規：紀錄欄位**不得**包含影像、Bitmap、關鍵點、特徵向量、完整樣本、
  游標座標、使用者身分資訊。`failureReason` 只含例外類別名與經清理的訊息摘要
  （不得含堆疊全文；堆疊只進 `CrashReporter` 檔案，不進綁定狀態紀錄）。
- 寫入執行緒：狀態變化可能發生在主執行緒／MediaPipe 執行緒／背景執行緒；
  紀錄寫入必須是非阻塞的（enqueue／Logcat），不得在結果回呼執行緒內做檔案 I/O
 （沿用 `VerifyFrames` 的背景執行緒模式，不得在分析執行緒內阻塞）。

### 6.2 欄位表（每筆狀態變化紀錄必須包含的欄位）

| 欄位 | 來源／格式 | 必填？ | 說明 |
|---|---|---|---|
| `timestampMs` | `SystemClock.elapsedRealtime()`（單調時鐘，區間計算用） | 是 | 不用 wall-clock 作區間計算；wall-clock 只在需要與檔名對照時另記，不得混用 |
| `sessionId` | §5.1 格式 | 是 | 舊 session 的晚到事件以舊 `sessionId` 記錄，不得改寫為新 `sessionId` |
| `generation` | 當下 `bindingGeneration`（Long） | 是 | 晚到事件另記 `expectedGeneration`（閉包捕獲值）與 `actualGeneration`（當下值） |
| `fromState`／`toState` | `CameraBindingState` 列舉名 | 是 | 非法轉換被拒時，`toState` 記為 `REJECTED`，並記 `attemptedState` |
| `errorCode` | §4 代碼（`FAILED`／被拒轉換時） | 條件必填 | 非失敗轉換記空，不得沿用舊 `errorCode` |
| `lifecycleState` | 綁定當下的 `Lifecycle.State` 名（`CREATED／STARTED／RESUMED／DESTROYED`） | 是（綁定相關轉換） | 服務的 `LifecycleRegistry` 狀態或 Activity 的 `lifecycle` 狀態，取綁定呼叫當下值 |
| `retryCount` | 同一 session 內的重試次數（Int，從 0 起） | 是 | 跨 session 歸零；重試上限待實作批次定，不得在本設計中編造上限值 |
| `failureReason` | 清理後的例外類別名＋訊息摘要（去堆疊、去路徑） | 條件必填 | 不得含使用者資料、不得含完整檔案路徑 |
| `releaseConfirmed` | 釋放確認組（`STOPPING → STOPPED` 時必填）：`unbindConfirmed: Boolean`、`executorTerminated: Boolean`、`landmarkerClosed: Boolean`、`bitmapsRecycled: Boolean` | 條件必填 | 四項皆須以實際檢查結果填寫，不得預設全真；`unbindConfirmed` 為 false 時不得進入 `STOPPED`（見 §3.1） |
| `caller` | 呼叫端識別（`SERVICE／CALIBRATION／TRAINING`，列舉，不得含 Activity 實例資訊） | 是 | 區分三個消費者，不得記錄記憶體位址或實例雜湊 |

### 6.3 紀錄範例（欄位示意，非真實數據）

> 欄位示意（格式待實作批次定，此處只示範欄位齊全性，不構成可直接採用的日誌格式）：
> `timestampMs=…，sessionId=bind-3-…，generation=3，fromState=BINDING，toState=FAILED，`
> `errorCode=BIND_FAILED，lifecycleState=RESUMED，retryCount=0，`
> `failureReason=IllegalStateException: …（摘要），releaseConfirmed=不適用，caller=SERVICE`

**禁假數據**：範例中的 `…` 不得被理解為真實數值；實作時每一欄位必須有真實來源，
無來源的欄位記空／記「未知」，不得編造。

## 7. 未決點與後續工作（本設計不代決）

| # | 未決點 | 現況／待決定內容 |
|---|---|---|
| U-1 | §10.3 清單原文 | 未見於本次收到的先前文件，待定位；本文件 §4 代碼為自行設計，實作前須與 §10.3 原文對照，不得直接宣稱一致 |
| U-2 | 協議內是否新增失敗回呼／逾時 | `requestPauseForCalibration` 本體不得改動（見 §1.3）；疊加式觀測（狀態回呼參數、逾時通知）是否允許、形式為何，待決策 |
| U-3 | `startEngineSafely`／訓練頁的錯誤訊息顯示 | 現況校正頁顯示 `e.message` 原文（`CalibrationActivity.kt:335`）；訓練頁 `engine.start` 無 `try/catch`（`TrainingActivity.kt:206`，K-04 殘留）。改為 §4 訊息表的時機與範圍，待實作批次定 |
| U-4 | 停滯門檻、重試上限、unbind 等待逾時、Provider 逾時 | 全部待實機量測／校準，不得預設數字；程式中以 `UNCALIBRATED` 具名常數佔位 |
| U-5 | 狀態變化的對外暴露形式 | `bindingState` 經 `trackingSnapshot()` 暴露、或經獨立回呼暴露，二選一待定；不得在未定前假裝 Dashboard 已能顯示綁定狀態 |
| U-6 | `FAILED` 與 `AppFlowState.ERROR` 的對應 | 服務層對應關係待 `STATE_DESIGN.md` 增補；引擎層不直接寫流程態（見 §3.3 第 3 點） |
| U-7 | 舊 session 的 Provider listener 是否須在 `stop()` 內顯式取消 | CameraX `ListenableFuture` 無取消綁定的 API 可用時，以 generation 丟棄為唯一保護（見 §5）；是否須另加 `providerFuture.cancel()` 等手段，待查 API 後定，不得假設 |

## 8. 驗證清單（設計完成後的檢查點，非本批次執行）

1. 晚到 Provider listener 在 `stop()` 後到達：不綁定、不覆蓋新 session 狀態（N13）。
2. 綁定失敗（以前鏡頭被佔用模擬）：狀態為 `FAILED(FRONT_CAMERA_UNAVAILABLE／CAMERA_IN_USE)`，
   Dashboard 顯示 §4 訊息，不顯示堆疊；重試後可恢復。
3. Provider 尚未就緒時 `stop()`：狀態經 `STOPPING` 到 `STOPPED`，晚到 listener 被丟棄，
   無殘留綁定（N4／N13）。
4. 綁定成功但無結果：狀態停在 `BOUND`，不宣稱「追蹤中」；停滯門檻校準後轉
   `FAILED(STREAM_STALLED)`（N9／N17）。
5. 快速啟停 10 次：無相機殘留佔用、無雙引擎交錯、每筆紀錄的 `sessionId`／`generation` 單調且不重用。
6. 交接期間服務銷毀：晚到 `post` 被丟棄，面板狀態不被舊回呼覆蓋（N18）。
7. 診斷紀錄抽查：每筆狀態變化紀錄含 §6.2 全部必填欄位，且不含影像／特徵／座標／身分資訊（K-12）。

---

*複用來源：`~/workspace/eyecontrol-phase3/PLAN.md`（§1.1／§1.3）、
`~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md`（§1 出入第 8／21／22 點、§2 未決點）、
`~/workspace/eyecontrol-phase3/CANCEL_CONTRACT.md`（第 2／8／9 條）、
`~/workspace/eyecontrol-master-audit/docs/audit/KNOWN_ISSUES.md`（K-02／K-14／K-15／K-58）。*
