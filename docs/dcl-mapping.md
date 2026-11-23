# EyeControl DCL-01～DCL-46 規格對照（STEP 2）

> 性質：STEP 2 規格對照文件。只讀程式碼、只寫文件，**未修改任何程式碼、版本號、workflow，未推送、未 commit**。
> 基線：工作樹 `~/workspace/eyecontrol-p3`（v0.5.9＋未推送 Phase 3 實作；遠端 main HEAD `c6b80964`）。
> 行號一律為目前工作樹的靜態閱讀行號；「已閱讀 ≠ 已提案 ≠ 已改碼 ≠ 靜態通過 ≠ 單元測試通過 ≠ CI 通過 ≠ 實機通過」。
> 複用（不得重做）：`~/workspace/eyecontrol-phase3/`（`PLAN.md`、`STATE_DESIGN.md`、`CANCEL_CONTRACT.md`、`D4_OPTIONS.md`）與 `eyecontrol-p3/docs/`（`engineering-baseline.md`、`engineering-task-matrix.md`、`d4-decision-record.md`、`engineering-remediation-plan.md`、`camera-binding-observability.md`、`restart-recovery-policy.md`）。
> DCL 規格文字以任務書為準（擁有者正式確認，不得增刪、不得擅改）。本文件不新增 DCL 題目、不問新功能選擇題。

## 0. 判定規則與狀態定義

| 狀態 | 定義 |
|---|---|
| 已實作 | 全部子項皆有程式碼證據，且行為與規格文字一致。 |
| 部分實作 | 核心或部分子項有程式碼證據，但有子項缺失；或僅有片段／相鄰機制可部分對應。缺失子項逐項列於「缺口」欄。 |
| 未實作 | 規格要求的核心機制在程式碼中不存在；片段（如既有管線的副產品）不計為核心已實作。 |
| 實作不符合規格 | 程式碼存在，但行為與規格文字直接牴觸（例如預設值相反、判定條件相反、自動恢復方向相反）。 |
| 無法確認 | 任務書未提供該編號的規格文字，或靜態閱讀無法建立對照（見 §7）。本文件不以推測填補。 |

判定原則：

1. **逐子項判定**：一項 DCL 有多個子句時，逐子句找證據；任一子句缺失即不得標「已實作」。子句層級的「不符合」在詳述中明寫，整項狀態取最能反映整體的標籤（見 §3 統計註）。
2. **設計文件不計為實作**：`camera-binding-observability.md`（九態）、`gesture-dispatch-state-machine.md`（統一 Dispatcher）、`restart-recovery-policy.md`（十步流程）皆為設計／政策文件，**未實作**，不得以其存在認定程式已實作。引用時一律註明「設計文件，尚未實作」。
3. **不得把閱讀程式碼宣稱為編譯成功**：本對照為靜態閱讀；全量編譯、單元測試執行、CI、實機驗證皆未在本任務執行，狀態一律不含「通過」語義。先前 Phase 3 實作亦一律為「已實作但尚未驗證」（見 `engineering-task-matrix.md`），本文件沿用該區分，不重複扣分。
4. **門檻數字**：凡標 `UNCALIBRATED`、註明「未經實機校準」或「經驗值」的常數，一律視為「邏輯先行、數值未定」，不得認定為已校準的品質門檻（見 DCL-12、DCL-07）。
5. **編號跳號已解決**：任務書原未載 DCL-02、DCL-03、DCL-04 的規格文字；擁有者已於 2026-11-23 補齊三項正式規格文字，**皆選 A、已正式確認，非新增需求**（補充指令，優先於舊狀態）。本文件已按新文字重新對照三項（見 §4.1），P-01 記為已解決。第一輪 DCL-02／03／04（見 `engineering-remediation-plan.md` §4，另一套編號）仍不得冒充。DCL-04 與校正取消決策「D-4」無文字連結，不得混為一談。

## 1. 關鍵 reconciliations（擁有者最新指示優先，本文件明確記錄）

| # | 衝突／覆蓋 | 本文件採用的準則 |
|---|---|---|
| R-1 | DCL-01／DCL-08 已定，舊 `D4_OPTIONS.md` 的 A／B／C 雙目標注視方案與第一輪 DCL-01 問題已被取代，不得再提問；D1-9 維持只留位置、不恢復互動。 | 校正取消的注視目標形式問題不再追問。DCL-01／DCL-08 要求「尚未校正時只能透過觸控或 Android 系統無障礙操作進行必要選擇」——採樣期（無模型、無落點預測）不得用未校正視線做選擇判定。D1-9 仍為僅常數預留（`CalibrationActivity.kt:1117-1123`，全專案零引用），見 DCL-01。D-4 剩餘的形式／秒數／位置決定仍未決（見 `d4-decision-record.md`，已決定 0 項），但其決定必須與 DCL-01／DCL-08 相容（見 §6 待批 P-02）。 |
| R-2 | DCL-05 覆蓋第一輪重啟政策預設（先前已啟用眼控時可自動嘗試恢復）。 | 第一輪 `restart-recovery-policy.md` §3.3／§4.2 的「重啟後恢復設定、不自動恢復眼控工作階段」預設，**已被 DCL-05 覆蓋**。本文件按 DCL-05 判定現況（見 DCL-05：現況自動恢復的方向與 DCL-05 要求的「自動嘗試恢復＋限制條件」仍有落差，標不符合）；`restart-recovery-policy.md` 頂部已加覆蓋聲明（本任務的兩處文件更新之一），其餘內容不改。 |
| R-3 | DCL-06 七態相機狀態機為準，第一輪九態提案退為參考。 | `camera-binding-observability.md` §3.1 的九態（IDLE／INITIALIZING／PROVIDER_READY／BINDING／BOUND／STREAMING／FAILED／STOPPING／STOPPED）**退為參考**，不得作為 DCL-06 的對照基準。DCL-06 七態（IDLE／REQUESTING_PERMISSION／BINDING／BOUND／STREAMING／FAILED／STOPPING）中無一態以具名狀態實作（見 DCL-06）。`camera-binding-observability.md` 頂部已加聲明（本任務的兩處文件更新之二），其餘內容不改。 |
| R-4 | DCL-19／DCL-20：緊急停止後允許自動恢復，正式覆蓋 DCL-19 原禁止自動恢復的限制；DCL-21 不需要倒數。 | 按新文字判定：DCL-20 的「允許自動恢復」為準，現況 RECOVERY_MODE 自動恢復路徑不因舊禁止而扣分；DCL-21 只判定「無倒數」本身（見 DCL-21）。 |
| R-5 | DCL-13 眨眼點擊預設關、須個人化意圖校正＋品質評估才能啟用。 | 按新文字判定，現況眨眼點擊為預設開啟且僅用時間窗判定，標不符合（見 DCL-13）。 |
| R-6 | DCL-24～DCL-40 只做對照、不得在本輪實作。 | 本文件只對照、不提案實作細節；§5 中 DCL-24～40 的最小方案一律寫為「本輪不實作；未來實作須另立設計並經批准」，不給出可直接施工的規格。 |

## 2. 統計

| 狀態 | 項數 | 編號 |
|---|---|---|
| 已實作 | 2 | DCL-02、DCL-21 |
| 部分實作 | 16 | DCL-01、DCL-03、DCL-04、DCL-07、DCL-08、DCL-09、DCL-10、DCL-11、DCL-12、DCL-14、DCL-17、DCL-18、DCL-19、DCL-20、DCL-22、DCL-23 |
| 未實作 | 26 | DCL-06、DCL-15、DCL-16、DCL-24、DCL-25、DCL-26、DCL-27、DCL-28、DCL-29、DCL-30、DCL-31、DCL-32、DCL-33、DCL-34、DCL-35、DCL-36、DCL-37、DCL-38、DCL-39、DCL-40、DCL-41、DCL-42、DCL-43、DCL-44、DCL-45、DCL-46 |
| 實作不符合規格 | 2 | DCL-05、DCL-13 |
| 無法確認 | 0 | —（DCL-02／03／04 已於 2026-11-23 補文字重新對照，見 §4.1） |
| **合計** | **46** | — |

統計註：

- 「不符合／無法確認」清單：不符合——DCL-05、DCL-13；無法確認——無。詳見 §4。
- DCL-21 標「已實作」僅指其自身文字（不需要額外倒數）已滿足；其前提「所有安全條件」是否完備，屬 DCL-20 的缺口，不在此項重複扣分（見 DCL-21 詳述）。
- DCL-24～DCL-40 共 17 項全數「未實作」，與 R-6 一致：本階段只做對照，程式中無任何智慧吸附／混合辨識機制（見 §4.3）。
- DCL-41～DCL-46 共 6 項全數「未實作」：程式中無任何視覺辨識／敏感畫面判斷機制；現有的影像上傳管線（`VerifyFrames`／`GazeLog`）與 DCL-41／43 的清除要求無對應控制點，見 §5 矛盾 T-10。

## 3. 逐項對照總表

| DCL | 狀態 | 一句話依據（詳述見 §4） |
|---|---|---|
| DCL-01 | 部分實作 | 九點單目標採樣、20 幀／KEEP_RATIO 0.7、失敗重試、重校清除皆有；但無品質儲存門檻（差級照樣存檔），取消目標僅常數預留。 |
| DCL-02 | 已實作 | 左右獨立開關＋總開關五鍵獨立保存，總關全停、重開恢復原分項狀態；實機驗收未做。 |
| DCL-03 | 部分實作 | 資料流稽核文件已覆蓋 (1)～(5) 項；撤回機制部分完成、聲明未改、網路流量驗證未做。 |
| DCL-04 | 部分實作 | 集中派發守衛已在 STEP 3 工作樹實作接線（逾時排程未接線）；CI／實機驗證未做。 |
| DCL-05 | 實作不符合規格 | 現況行程重建後無條件自動恢復追蹤，旗標未持久化；與「可自動嘗試恢復＋限制條件」的要求方向相反（政策設計未實作）。 |
| DCL-06 | 未實作 | 七態狀態機不存在；僅有 `closed` 旗標、幀槽 generation 片段與僅記 Log 的綁定失敗。 |
| DCL-07 | 部分實作 | 50dp 換算＋短邊 15% 上限已寫入控制器；但未讀 `densityDpi`／`xdpi`／`ydpi`、未處理 Insets／保護殼，實機量測未做。 |
| DCL-08 | 部分實作 | 進度／提示／報告／ERROR 頁與服務暫停守衛皆有；但採樣期無相機權限檢查，ASK／ERROR／REPORT 的注視選擇在校正成功前即使用舊模型判讀。 |
| DCL-09 | 部分實作 | 引擎重建與 `startEngine` 內濾波重置皆有；但臉部重現時主路徑不重置濾波器、不清除舊手勢殘留、無穩定度門檻即解鎖。 |
| DCL-10 | 部分實作 | 左右獨立開關＋總開關＋恢復預設值皆有；但邊緣「寬度」本身不可調、無安全最小／最大值。 |
| DCL-11 | 部分實作 | 臉遺失分支停止新操作、清 dwell／邊緣、灰圈提示皆有；但未清眨眼狀態、滑動起點、濾波歷史，雙擊第二下未取消。 |
| DCL-12 | 部分實作 | 各指標散見於邊緣檢查、影像降級、校正評估；但無統一品質狀態與三態，閾值皆未校準，且主路徑無「不得抵銷」規則。 |
| DCL-13 | 實作不符合規格 | 眨眼點擊預設開啟、僅以 150～600ms 時間窗判定；無獨立開關、無個人化意圖校正、無 FP／FN 評估。 |
| DCL-14 | 部分實作 | 面板／把手有武裝重啟，`DwellTracker` 有遲滯式衰減；但無五態狀態機，螢幕停留重啟只靠冷卻時間，無明確離開確認。 |
| DCL-15 | 未實作 | 無 Click Arbiter；眨眼與停留兩路徑各自直接呼叫 `triggerActionAt`，僅共用冷卻時間戳。 |
| DCL-16 | 未實作 | 無統一 GestureArbiter；僅邊緣控制器有局部狀態機，各 `perform*` 直接呼叫 `dispatchGesture()`。 |
| DCL-17 | 部分實作 | 冷卻＋邊緣 COOLDOWN 鎖定有部分效果；但無進行中手勢鎖定，衝突意圖丟棄規則未建模，雙擊第二下以延遲排隊執行。 |
| DCL-18 | 部分實作 | 暫停／臉遺失／停止／ERROR 各有分支處理；但 `onInterrupt()` 為空實作，`pauseTracking()` 不清除待執行意圖，恢復時無完整重查。 |
| DCL-19 | 部分實作 | 觸控停止入口（Dashboard 停止對話框）存在；但無音量鍵操作、無自訂、無獨立於一般暫停的緊急停止。 |
| DCL-20 | 部分實作 | 允許自動恢復（RECOVERY_MODE）已實作；但恢復檢查僅 3 項，未覆蓋相機／臉部／眼睛／穩定性／權限／服務，手點恢復繞過臉部檢查。 |
| DCL-21 | 已實作 | 無額外倒數；條件通過即恢復、失敗即取消此次恢復、不重播舊操作（條件集合的完備性屬 DCL-20）。 |
| DCL-22 | 部分實作 | 一般暫停與停止的操作路徑存在；但暫停不清除進行中狀態、恢復不重查品質，緊急停止須經確認對話框、無即時眼控外入口。 |
| DCL-23 | 部分實作 | 無自訂安全區域、全螢幕接受眼控座標；但誤觸控制只部分存在（仲裁與品質門檻不完整），「未宣稱全系統畫面可控」靜態上無反證（見詳述）。 |
| DCL-24 | 未實作 | 無依穩定度／頭部移動／品質的動態平滑；僅有 dwell 期間的固定參數切換與使用者滑桿。 |
| DCL-25 | 未實作 | 無短期預測模組；`predict()` 為迴歸模型輸出，無方向／速度外推與信心退回。 |
| DCL-26 | 未實作 | 無智慧目標吸附；游標即濾波後預測座標，無目標清單。 |
| DCL-27 | 未實作 | 無節點讀取、無本機視覺辨識／OCR、無統一信心分數（`onAccessibilityEvent` 為空實作）。 |
| DCL-28 | 未實作 | 無 OCR／視覺辨識／目標分析可言端側或上傳；「不上傳」對不存在的資料空洞成立，不得讀為合規。 |
| DCL-29 | 未實作 | 無辨識範圍機制；「任意位置可點擊」不等於「辨識互動目標」。 |
| DCL-30 | 未實作 | 無形狀／文字／圖示／布局推論。 |
| DCL-31 | 未實作 | 無動態介面追蹤／頻率調整／移動目標更新／過期座標失效（500ms 條件評估不是介面辨識）。 |
| DCL-32 | 未實作 | 無智慧吸附總開關（邊緣開關不是吸附開關）；逐 App 設定之「無」為功能缺席所致，非已實作的克制。 |
| DCL-33 | 未實作 | 無漸進式吸附／平滑返回原始座標。 |
| DCL-34 | 未實作 | 無多目標選擇。 |
| DCL-35 | 未實作 | 無最佳互動點選擇。 |
| DCL-36 | 未實作 | 無短期目標記憶（`ConditionHistory` 是條件偏移記憶，不是目標記憶）。 |
| DCL-37 | 未實作 | 無場景切換的目標重驗／清除／重辨識（僅有方向改變時的濾波與邊緣重置）。 |
| DCL-38 | 未實作 | 無目標消失／重新出現處理。 |
| DCL-39 | 未實作 | 無相似目標身份辨識。 |
| DCL-40 | 未實作 | 無重疊／遮擋／Z-order 偵測（面板優先於邊緣是 overlay 內部分支序，不是遮擋偵測）。 |
| DCL-41 | 未實作 | 無敏感畫面視覺辨識；亦無其記憶體暫存／清除／系統限制處理（現有影像上傳管線與此無對應控制點）。 |
| DCL-42 | 未實作 | 無智慧吸附，故無敏感欄位吸附禁令的落點；另注意停留／眨眼點擊本身無敏感欄位守衛（規格外風險，見詳述）。 |
| DCL-43 | 未實作 | 無辨識資料清除機制（`VerifyFrames.endSession()` 清的是影格時段，不是辨識結果）。 |
| DCL-44 | 未實作 | 無敏感畫面判斷（四層優先序皆無程式落點）。 |
| DCL-45 | 未實作 | 無 FLAG_SECURE／MediaProjection 偵測與降級路徑。 |
| DCL-46 | 未實作 | 無視覺辨識恢復序列。 |

## 4. 逐項詳述

### 4.1 A. 校正與追蹤安全（DCL-01、DCL-05～DCL-12）

#### DCL-01 完整校正流程可靠性 — 部分實作

規格子項對照：

| 子項 | 狀態 | 證據 |
|---|---|---|
| 一次只顯示一個校正目標 | 已實作 | `CalibrationActivity.kt:345-349` `buildPoints` 產生 3×3 九點；`CalibrationActivity.kt:351` `startPoint` 逐點進行；`CalibrationActivity.kt:379-424` `onSamplingSample` 每次只對當前點採樣；`CalibrationView.showTarget`（`:950`）一次只畫一個目標點。 |
| 檢查人臉存在 | 已實作（部分） | `onSamplingSample` 遇 `sample == null` 顯示臉遺失提示並走逾時檢查（`:382-390`）。但未檢查追蹤品質（見下）。 |
| 檢查追蹤品質 | 未實作 | 採樣路徑未呼叫 `EyeImageMetrics.isDegraded`（全檔僅 `specularRatio` 被收為條件樣本，`:399`）；無任何品質分數或品質守衛進入採樣判定。 |
| 檢查樣本數量 | 已實作 | `SETTLE_MS = 900L`、`SAMPLES_PER_POINT = 20`（`:1094-1095`）；收滿 20 幀才 `commitPoint`（`:416-422`）。 |
| 排除無效樣本 | 已實作（部分） | 眨眼／閉眼幀丟棄（`:402`，以 `blinkThreshold` 判定）；`settleUntil` 前的幀不計入（`:407`）。但影像降級幀未排除（見上）。 |
| 排除離群值 | 已實作 | `commitPoint`（`:657-676`）以虹膜特徵前 4 維樣本中心為基準，只保留最近的 `KEEP_RATIO = 0.7`（`:1101`）。 |
| 排除不足樣本 | 未實作 | `finishCalibrationInternal`（`:695-760`）未驗證 9 點是否各有樣本、總樣本數是否足夠；`GazeModel.train` 僅要求非空且等長（`GazeModel.kt:184` 附近 `require`）。樣本不足時仍可訓練並存檔。 |
| 校正失敗須提供原因與重新嘗試方式 | 已實作 | `finishCalibration` 失敗進 `enterError`（`:680-693`）；`enterError`（`:477-499`）顯示真實原因訊息＋「重試」／「退出」兩個選擇；交接逾時亦進 ERROR 態（`:301-315`）。 |
| 重新校正不得混入前一次失敗資料 | 已實作（報告頁路徑） | `recalibrate`（`:839-859`）清除 `allFeatures`／`allTargets`／`allDeviceAngles`／`pointBuffer`／條件樣本並歸零 `committedPoints`／`pointTimeoutCount`，且不呼叫任何存檔／清除持久化函式。但注意：存檔失敗重試（`finishCalibration` 的 `retry`）重用同一回合的 `allFeatures`，屬同一回合重跑存檔，非「重新校正」，不得混淆。 |
| 取消時停止取樣、釋放攝影機並清除未完成資料 | 未實作 | 取消目標僅常數預留（見下）；既有 `exitCalibration`（`:501-504`）只顯示 Toast 並 `finish()`，未清除任何暫存欄位、未立終端認領旗標、未遞增世代；`CANCEL_CONTRACT.md` 第 1～4、8～9 條（終止、釋放、清除、競態、冪等）皆無程式落點。`onDestroy`（`:865-873`）的停引擎＋`resumeAfterCalibration()` 是生命週期釋放，不是取消語義。 |
| 保留上一份有效校正 | 已實作（退出路徑） | `exitCalibration` 註解明載不刪舊模型、不清條件歷史；`recalibrate` 不覆蓋舊模型直到新模型存檔成功（`:836-838` 註解）；`finishCalibrationInternal` 先存檔成功才清歷史、寫基準（`:724`、`:736-737`）。但取消路徑本身無此保證的程式檢查點（因取消功能不存在）。 |
| 只有通過品質門檻才能儲存 | **實作不符合規格** | `finishCalibrationInternal` 無任何品質門檻守衛：無論 `gradeErr` 為何，一律 `CalibrationStore.saveModel`（`:724`）；等級門檻 60／120／200 px（`:718-724`）只決定報告文字與主按鈕（`isPoorGrade`，`showReportChoices` `:806-835`），「偏差」等級仍存檔、仍可按「完成」結束。規格要求的「門檻」本身亦未定（D-12 未決），見 §5 矛盾 T-03。 |
| 相機錯誤或生命週期中斷不得產生假成功 | 已實作（部分） | 存檔／訓練例外進 ERROR 態不 `finish()`（`:680-693`）；`startEngineSafely` 失敗進 ERROR 態（`:326-343`）。但 `finishCalibrationInternal` 未驗證樣本充足性（見上），「假成功」的另一面（殘缺資料存檔）未堵住。 |
| 尚未校正時只能透過觸控或 Android 系統無障礙操作進行必要選擇 | 部分實作 | 選擇階段使用真實 `MaterialButton`（`showChoices` `:601-638`，`contentDescription = label`，`setOnClickListener`），可觸控點按；無模型時 `gazeHintLine`（`:507-515`）明說注視選擇無法判讀、可用手指或系統返回。但採樣期（`Phase.SAMPLING`）的 `CalibrationView` 為自繪 View、無任何按鈕，必要選擇（取消／退出）在採樣期無觸控落點，只能靠系統返回；且 ASK／ERROR／REPORT 的注視選擇在「校正成功前」即以舊模型判讀（見 DCL-08），與「停用所有依賴眼球座標的操作」有張力。 |

缺口小結：品質儲存門檻、取消語義（停止取樣／清除／競態／冪等）未實作；追蹤品質檢查與不足樣本排除未實作。其餘子項有證據。

#### DCL-02 左右邊緣操作開關 — 已實作（實機驗收未做）

規格文字來源：擁有者 2026-11-23 補充，選 A，已正式確認。實作涵蓋四邊（上／下／左／右）開關，為 DCL-02「左右」要求的超集，與 D-3 定稿一致；邊緣「寬度」不可調屬 DCL-10，不在此項扣分。

| 子項 | 狀態 | 證據 |
|---|---|---|
| (1) 左右獨立啟用／停用 | 已實作 | 獨立 prefs 鍵 `edge_left_enabled`／`edge_right_enabled`（`GazeSettings.kt:80-81`，讀取 `:228-229`）；`EdgeGestureConfig.edgeEnabled()` 按邊分派（`EdgeGestureController.kt`）；設定頁四邊開關各自綁定（`SettingsActivity.kt:82-85`）。 |
| (2) 關一側不影響另一側 | 已實作 | 控制器按邊逐項檢查 `edgeEnabled(edge)`，分項關閉只擋該邊候選；全樹無任何在分項開關變更時改寫他邊鍵的程式（`KEY_EDGE_*` 只在 `GazeSettings.kt` 出現；`settings.copy` 改寫邊緣欄位零命中）。純測試 `EdgeSwitchRestoreTest`（STEP 3 工作樹）驗證右關左執行。 |
| (3) 全域總開關 | 已實作 | `edge_gestures_enabled` 鍵（`GazeSettings.kt:73,184,225`）＋設定頁總開關（`SettingsActivity.kt:81`）。 |
| (4) 全域關閉時兩側全停用 | 已實作 | `!config.enabled` 時控制器回 `Cancelled`／`NoAction`（`EdgeGestureController.kt:update` 總開關分支）；設定頁總關時分項列停用（`refreshEdgeGestureRows`，`SettingsActivity.kt:215-228`）。 |
| (5) 全域重開恢復先前各自儲存的左右狀態 | 已實作 | 關鍵行為已核實：`refreshEdgeGestureRows()` 只改分項列的 `isEnabled`／`alpha`，**永不改 `isChecked`**；儲存時讀取 `isChecked`（`SettingsActivity.kt:298-302`），故總關期間分項 checked 值原樣保留；總開關關閉本身不寫入任何分項鍵（五鍵獨立保存／讀取）；服務每幀由設定重組 `EdgeGestureConfig`（`GazeAccessibilityService.kt:1272-1275`），重開即恢復。`resetToDefaults()` 全重置為預設值屬使用者明示重置，非總開關語義。純測試驗證「總關→重開同一組分項狀態」序列。 |
| (6) 單側錯誤不得自動關另一側 | 已實作 | 控制器檢查失敗（`CHECKS_FAILED` 等）只取消當前候選，不寫入任何開關旗標；重新觸發鎖定（COOLDOWN）按邊獨立（`lockedEdge`）。無任何錯誤路徑改寫開關狀態。 |
| (7) 仍須遵守追蹤品質、穩定性、離開／重新進入等安全條件 | 已實作 | 三項檢查（Gaze Confidence／Stability／Intent）每幀獨立評估，與開關狀態無關（`evaluateChecks`）；幾何改變取消、離開帶解除鎖定（`update` 的 `GEOMETRY_CHANGED`／`LEFT_ZONE` 分支）；服務端在提醒／RECOVERY／PAUSED／臉遺失等分支提早返回，邊緣更新只在主路徑（`GazeAccessibilityService.kt:901`）執行，各分支以 `cancelEdgeGesture()` 中斷（呼叫點 `:402,562,694,792,813,888,1132`）。 |
| (8) 左右設定獨立保存、重啟不遺失 | 已實作 | 五鍵各自 `putBoolean`／`getBoolean`（`GazeSettings.kt:126-130,225-229`），無遷移重置、無重啟清除；設定重載（`settingsListener`）只讀 prefs，不改分項值。 |

缺口小結：程式證據齊備，標「已實作」；驗收清單的六項情境（左關右正常、右關左正常、全域關、全域重開恢復、單側錯誤、重啟後設定）**皆未實機驗證**，留待 STEP 6 實機清單，不得宣稱為已驗收。

#### DCL-03 K-12 隱私與資料上傳聲明 — 部分實作

規格文字來源：擁有者 2026-11-23 補充，選 A，已正式確認。**本輪未改任何聲明文案**（(7)(8) 要求稽核完成後才改，且須另批）；以下只判定稽核文件覆蓋與機制現況。

| 子項 | 狀態 | 證據 |
|---|---|---|
| (1) 完整檢查所有網路請求與傳輸路徑 | 已稽核 | `privacy-data-flow.md` §1（11 種資料流總覽）＋§2（逐項盤點）＋§3（3 個 HTTPS 端點）；`privacy-security-report.md` §3.1 網路請求總覽。 |
| (2) 每項資料的內容、用途、觸發條件、目的地、保留時間 | 已稽核（伺服端保留時間除外） | §2.1～§2.11 逐流載明內容／用途／觸發／目的地；本機保留期限已載（120 秒輪轉、`MAX_FILES=3`、`pruneSessions`）；**伺服端保留期限與刪除流程待定位**（`privacy-data-flow.md:364`；`privacy-security-report.md` §10 T-1，RLS 主張無法由本樹驗證）。 |
| (3) Play 與 Sideload 分開稽核 | 已稽核 | `privacy-data-flow.md` §3（兩版本差異）＋§4（差異表）；`privacy-security-report.md` §4（Play／Sideload 分別判定，含 Play 版仍本機寫檔的不對稱）。 |
| (4) 眼球影像／追蹤資料／Gaze 座標與特徵／診斷／Crash／Supabase／背景佇列／更新檢查／第三方 SDK | 已稽核 | 眼區裁切 §2.1、特徵 §2.3、座標 §2.4、診斷 §2.7、Crash §2.8、Supabase §2.10、背景待上傳佇列 §2.7（`uploadExecutor` 記憶體佇列、無持久化佇列）、更新檢查 §2.9、第三方 SDK（全樹 `Firebase`／`Crashlytics`／`Sentry`／`Analytics` 等零命中、無 Analytics SDK，`privacy-security-report.md:162`；傳遞依賴執行期行為未驗證，見 R-14／T-6）。 |
| (5) 追蹤資料完整生命週期（產生→儲存→排隊→傳輸→刪除） | 已稽核（含已載明的刪除缺口） | §2 各流「保存／刪除」段＋§4 撤回機制＋§6 備份行為；刪除缺口已明載（`VerifyFrames` 無 `clearAll`、已上傳資料無 App 內刪除路徑、伺服端刪除待定位）。 |
| (6) 撤回同意後停止新收集並阻止先前待傳送資料繼續上傳 | 部分實作 | STEP 3 工作樹已修 CSV 未提交部分（`settingsListener` 開→關時 `GazeLog.close(upload=false)`，見 `step3-fix-log.md` F-4）；已提交至 `uploadExecutor` 的任務與已上傳的伺服端資料無取消／刪除路徑，列為提案（同文件 P-R1b）。影格路徑執行時重查開關已存在（`VerifyFrames.storeAndUpload`）。 |
| (7) 三項「不會上傳」絕對聲明等稽核完成後才改 | 未改（符合規格順序） | `privacy-copy-audit.md` 已定位三處絕對聲明（設定頁對話框／主畫面頁尾／`strings.xml`）；本輪未改任何一處文案，改寫須另批。 |
| (8) 無足夠證據前不得宣稱完全不會上傳 | 遵守中 | 本文件與稽核文件皆未作此宣稱；網路流量驗證未做（`privacy-security-report.md` R-14／T-6：實機網路抓包待做），不得以靜態稽核冒充流量證據。 |

缺口小結：稽核文件覆蓋 (1)～(5)（伺服端保留時間除外）；(6) 機制只完成一部分；(7)(8) 按規格順序未改聲明、未做流量驗證。整項標「部分實作」，不得讀為「聲明已與行為一致」。

#### DCL-04 D3-7 手勢派發取消與回呼管理 — 部分實作

規格文字來源：擁有者 2026-11-23 補充，選 A，已正式確認。注意：`D4_OPTIONS.md`／`d4-decision-record.md` 的「D-4」指校正取消決策，與本編號 DCL-04 無文字連結，不得混為一談。STEP 2 時點的事實：四處 `dispatchGesture` 皆只有 `onCompleted`、無 `onCancelled`、回傳值忽略（`gesture-dispatch-state-machine.md` §0，設計文件）；STEP 3 工作樹狀態見下表（行號為 STEP 3 工作樹行號，commit 後須重新快照；設計文件本身不計為實作）。

| 子項 | 狀態 | 證據 |
|---|---|---|
| (1) 集中式 Gesture Dispatch Controller | 已實作（工作樹） | 新增純 Kotlin `GestureDispatchGuard.kt`（唯一狀態機）；服務端單一欄位 `dispatchGuard`（`GazeAccessibilityService.kt:270`）。 |
| (2) 所有 dispatchGesture() 經統一派發控制 | 已實作（工作樹） | 四處呼叫點（`performScroll`／`performTap`／`performLongPress`／`performSwipe`）皆改為 `begin()` 取 ID＋包裝回呼＋`onDispatchReturned` 處理回傳值（`:1646,1667,1703,1727`）；全樹 `dispatchGesture` 呼叫點窮舉仍為此四處，無旁路。 |
| (3) 處理回傳 false、onCancelled、onCompleted、逾時、Service 停止、使用者暫停、新手勢搶占、過期回呼 | 部分實作 | 回傳 false（`onDispatchReturned`）、`onCancelled`（四處皆加覆寫，`:1654,1675,1711,1735`）、`onCompleted`（ID 配對後才閃光）、Service 停止（`onDestroy` 內 `onInterrupted`，`:1747`）、暫停（`cancelPendingGesture` 內 `onInterrupted`，`:1336`，覆蓋暫停／停止／中斷／校正交接呼叫點）、搶占（`begin` 取代舊 ID）、過期回呼（`isCurrent` 隔離）皆已接線；**逾時只有決策（`onTimeout`）已實作，計時器排程未接線**（須一個毫秒值，不得發明，列為提案 P-04T，見 `step3-fix-log.md`）。 |
| (4) 每次手勢唯一 Gesture ID 或 Generation Token | 已實作（工作樹） | 嚴格遞增 `Long` ID，永不重用（`GestureDispatchGuard.begin()`）；搶占時舊 ID 立即失效。 |
| (5) 舊回呼不得改變新手勢狀態 | 已實作（工作樹） | `isCurrent` 閘門：舊 ID 的完成／取消／逾時／回傳一律忽略；完成副作用（游標閃光）只在 `onCompleted` 回傳 true 時執行。 |
| (6) 取消／失敗／完成皆正確清理狀態 | 已實作（工作樹） | 唯一終端轉換點 `finishTerminal`（狀態回 IDLE、ID 清空、清理計數）；服務端既有狀態清理（`cancelPendingGesture`）維持不變。 |
| (7) 清理只執行一次 | 已實作（工作樹） | 結構保證（終端轉換後 ID 失效，重複終端呼叫皆忽略）＋`cleanupCount` 計數；純測試斷言重複終端呼叫後計數仍為 1。 |
| (8) 不得死鎖、狀態殘留、錯誤重複派發 | 已實作（狀態機層級） | 無鎖、無執行緒、無佇列、無重發路徑；但**系統側進行中的手勢本身無法由 App 取消**（`dispatchGesture` 無取消 API，`onCancelled` 由系統驅動）——守衛只做狀態層隔離與清理，此限制已在 `step3-fix-log.md` F-8 明載，不得宣稱為系統級取消。 |
| (9) 已取消不得自動重播 | 已實作（工作樹） | 守衛永不重發；取消後 ID 死亡，任何舊回呼無效；純測試斷言取消後狀態與 ID 行為。 |
| (10) 取消後等待使用者產生新的有效眼控操作意圖 | 已實作（工作樹） | 取消後守衛回 IDLE；下一次派發必須是新的 `begin()` 呼叫（即新的派發請求），無自動重試路徑。 |

缺口小結：十項中九項的狀態機與接線已在 STEP 3 工作樹完成，逾時排程未接線；harness 取消情境測試 11 項全過（`GestureDispatchGuardTest`，`OK (141 tests)` 含既有 127 項＋本輪 14 項，見 `step3-fix-log.md` §4）。CI 編譯與實機驗證皆未做，不得宣稱已驗收；`gesture-dispatch-state-machine.md` 仍為設計文件，其餘設計內容（如進行中鎖定語義的裝置驗證）不在本輪範圍。

#### DCL-05 重新啟動恢復 — 實作不符合規格

| 子項 | 狀態 | 證據 |
|---|---|---|
| 先前已啟用眼控時系統可自動嘗試恢復 | 實作方向相反 | `GazeAccessibilityService.kt:320-350` `onServiceConnected` 不檢查任何舊旗標，一律 `deriveInitialFlowState()` 後 `startEngine()`（`:343-344`）；`deriveFlowState`（`:183-196`）只看當下記憶體旗標（新實例皆為初始值）與模型存在，有模型即推導為 `SERVICE_RUNNING`。行程重建／裝置重啟後的系統重新綁定（R2／R4，見 `restart-recovery-policy.md` §1）同樣走此路徑。現況不是「嘗試恢復」（有條件、可說明失敗），而是「無條件恢復」。 |
| 遵守 Android 背景服務、攝影機及權限限制 | 無法確認（靜態） | 程式碼從不自行啟動服務（全樹無 `startService` 啟動呼叫，見 `restart-recovery-policy.md` §1.3；Manifest 無 `<receiver>`、無 `BOOT_COMPLETED`），服務生死由系統決定。但裝置重啟後的平台綁定時機與權限重查行為，靜態閱讀無法確認，須實機驗證（該文件 T-5）。`startEngine` 不重查相機權限（見 DCL-06）。 |
| 不得恢復未完成校正 | 已實作（現況偶然成立） | 校正頁重建後從第 1 點重採（`CalibrationActivity.kt:205-216` 檢查點告知＋`startPoint(0)`），特徵串列為新實例空串列。但此為「新實例即乾淨」的隱含結果，無顯式清除步驟（`restart-recovery-policy.md` §4 S2）。 |
| 不得恢復舊手勢、點擊或滑動 | 已實作（現況偶然成立） | 新服務實例的 `swipeStartPoint`、`screenDwell`、`cooldownUntil` 皆為初始值；雙擊第二下的 `postDelayed` 不跨行程。但無顯式「禁止補發」狀態（同上 S5）。 |
| 重新檢查權限及服務 | 未實作 | `onServiceConnected` 未呼叫 `canStartTracking`／`verifyServiceRunning`（`GazeAccessibilityService.kt:1799-1821`）；`startEngine` 守衛只檢查記憶體旗標（`:391-392`）。 |
| 無法恢復時須說明原因 | 未實作（重啟路徑） | 重啟路徑無失敗分支（恢復即成功）；`canStartTracking` 的診斷只在 Dashboard 開始鈕路徑出現（`MainActivity.kt:127-192`），不在重啟路徑。 |
| 不得宣稱 Android 保證所有重啟情境都能自動恢復 | 無反證（靜態） | 程式碼與文件皆未作此宣稱；`restart-recovery-policy.md` §1.3 明確標裝置重啟行為「待實機驗證」。但現況行為（無條件恢復）本身會讓使用者誤以為恢復有保證，見 §5 矛盾 T-02。 |

關鍵：第一輪政策設計（`restart-recovery-policy.md` §4 十步流程 S1～S10：載入設定、清除、相機停止、不自動恢復、顯示未啟動、使用者主動啟動後重查）**尚未實作**，且其預設已被 DCL-05 覆蓋（R-2）。按 DCL-05 判定，現況的「無條件自動恢復」不符合「自動嘗試恢復＋限制條件＋重查＋說明原因」的要求。D-8 持久化格式未決（`PLAN.md` §6），見待批 P-03。

#### DCL-06 Camera Binding 錯誤 — 未實作

七態中無一態以具名狀態實作：全樹 grep `CameraBindingState`／`bindingState` 零命中。`camera-binding-observability.md` 的九態設計已退為參考（R-3），且其本身亦未實作。

| 子項 | 狀態 | 證據 |
|---|---|---|
| 狀態機 IDLE／REQUESTING_PERMISSION／BINDING／BOUND／STREAMING／FAILED／STOPPING | 未實作 | `FaceTrackerEngine.kt` 僅有 `closed: Boolean`（`:120`）與幀槽 `generation`（`:65`）；無任何狀態列舉、無狀態轉換函式、無對外唯讀狀態暴露。 |
| 處理權限拒絕 | 未實作 | `FaceTrackerEngine.start`（`:122-241`）全函式無 `checkSelfPermission`；無權限時仍走到 Provider／綁定，失敗原因無法區分（`camera-binding-observability.md` N1）。`GazeAccessibilityService.startEngine` 亦不檢查權限。 |
| 處理 Provider 失敗 | 未實作（僅記 Log） | `providerFuture.get()` 無 `try/catch`（`FaceTrackerEngine.kt:199-200`）；失敗沿主執行緒拋出，無錯誤代碼、無重試信號（N3／N4）。 |
| 處理 bindToLifecycle 例外 | 未實作（僅記 Log） | 綁定 `try/catch` 只 `Log.e("相機綁定失敗")`（`:233-235`），無回呼、無旗標、無外拋；綁定失敗後引擎欄位仍非 null，呼叫端以為引擎在跑（N7）。服務端 `startEngine` 的同步 `catch`（`GazeAccessibilityService.kt:408-427`）自承覆蓋不了非同步綁定失敗。 |
| 處理攝影機被占用 | 未實作 | 無法區分「無前鏡頭」「被其他 App 占用」「Selector 錯誤」（N5）；`unbindAll()` 無回傳、無紀錄（N12）。 |
| 處理初始化期間生命週期停止 | 未實作 | Provider listener 閉包內無 `closed`／generation 檢查（`FaceTrackerEngine.kt:199-236` 全段）；`start(lifecycleOwner)` 不檢查、不記錄 owner 狀態（N10）。晚到 listener 可在 `stop()` 後重新綁定（N13，K-02 殘留）。 |
| 處理重新嘗試成功 | 未實作 | 無重試狀態、無重試計數、無 `FAILED → BINDING` 轉換；重試即重新 `start()`（防呆先 `stop()`，`:125`），舊 listener 的晚到回呼不受 generation 保護。 |
| 所有非同步回呼須有生命週期世代識別，避免過期回呼重新綁定 | 未實作（片段除外） | 引擎層無 binding generation（幀槽 `generation` 僅用於影像度量驗證，`:297-305`、`:386-393`，且 `stop()` 遞增幀槽世代 `:514-519` 不保護綁定 listener）。交接世代 `handoverGeneration` 只在 `CalibrationActivity.kt:107` 保護交接回呼與逾時，不保護引擎綁定。`requestPauseForCalibration` 的背景執行緒與 `resumeAfterCalibration` 的 `post` 亦無 generation（N18）。 |

片段說明：`stop()` 的釋放順序（`closed=true` → 停感測器 → `unbindOnMainThread()` → 清幀槽 → 停執行緒 → 關 landmarker → 回收 Bitmap，`FaceTrackerEngine.kt:506-542`）與主執行緒解綁機制（`:554-568`，最多等 2 秒）已實作，但那是釋放順序，不是七態狀態機，不得以此認定 DCL-06 已實作。

#### DCL-07 邊緣區域尺寸 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 比對 v0.5.9 與目前版本 | 已實作（文件層） | `docs/edge-width-device-measurement.md` §1 記載：v0.5.9 為函式內 `val edgeSize = 50f`（raw px）；目前為 `EDGE_BAND_DP = 50f` × 密度、上限短邊 15%（`EdgeGestureController.kt:106-111`、`490-496`）。舊判定行已刪除（見 `engineering-baseline.md` §6.6 附近的 diff 記載）。 |
| 測量實際 px、dp、mm、densityDpi | 部分實作（公式推算，無實測） | 程式只讀 `density`（`GazeAccessibilityService.kt:837`、`:1241`、`:1266`）；全樹 grep `densityDpi`／`xdpi`／`ydpi` 零命中（`edge-width-device-measurement.md` §1.2、U-2／U-3）。mm 換算僅為公式（50dp 名目 7.94mm，僅在 `densityDpi = density × 160` 成立時），SM-S9180 實測值待讀取（該文件 §5 R-1）。實際 px 在程式內可算（`edgeBandPx`），但裝置上的實際值**無法確認**（未實機）。 |
| 考慮解析度、方向、系統 Insets、導覽列及保護殼 | 部分實作 | 解析度／方向：螢幕尺寸取 `currentWindowMetrics.bounds`（`GazeAccessibilityService.kt:335-338`、`:352-366`），方向改變時重置濾波器與邊緣手勢；控制器以幾何比對為第二道防線（`EdgeGestureController.kt:153-171`）。系統 Insets／導覽列：邊緣邏輯內 `WindowInsets` 零命中（`edge-width-device-measurement.md` §4.2），僅 `CalibrationActivity.kt:196-199` 為隱藏系統列之用，與邊緣帶無關。保護殼：程式與文件中皆無任何考慮，**待定位**（無來源可對照）。 |
| 50dp 不得宣稱為最佳值 | 已實作（文件層） | `edge-width-device-measurement.md` 總聲明「50dp 未驗證，不得宣稱安全」；控制器註解明載換算改變高密度裝置帶寬、須以 v0.5.9 為對照量測（`EdgeGestureController.kt:96-105` 註解）；常數標「待實機量測（E-9）」。程式中無任何「最佳」宣稱。 |
| 須實機測試才能確定正式預設值 | 未實作（量測未做） | 量測協議（E-9／E-10、實驗設定 V-0～V-5）已備妥於 `edge-width-device-measurement.md` §5～§6，但量測本身未執行；正式預設維持現況 50dp＋15% 上限（該文件 §4 末），不得讀為已確定。 |

#### DCL-08 校正期間 UI — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 保留視覺狀態、進度、說明及錯誤提示 | 已實作 | 目標點＋進度環＋「N / 9」文字（`onSamplingSample` `:410-413`）；臉遺失提示（`:382-390`）；交接等待期「相機啟動中」文字（`CalibrationView.setWaiting`，`engineering-task-matrix.md` D1-13）；報告頁常駐顯示等級／誤差／最差點／校正時間（`showReport` `:765-805`）；ERROR 頁顯示真實原因（`enterError` `:477-499`）。 |
| 校正成功前停用所有依賴眼球座標的操作 | 部分實作（有缺口） | 服務端：`pausedForCalibration` 守衛使樣本提早返回（`GazeAccessibilityService.kt:484-492`），面板在交接時設為 `GONE`（`requestPauseForCalibration` `:1648-1686`）。但校正頁自身在 REPORT／ASK／ERROR 三階段即以舊模型預測落點做注視選擇（`onChoiceSample` `:519-564`、`choiceCursor` `:566-580`），屬「校正成功前依賴眼球座標的操作」；首次校正無舊模型時該等選擇無法判讀（`gazeHintLine` `:507-515`），見 DCL-01。採樣期本身無其他眼控操作（單一目標採樣），此部分符合。 |
| 重新嘗試、取消及確認使用觸控或系統無障礙操作 | 部分實作 | 選擇階段為真實按鈕，可觸控（`showChoices` `:601-638`）；`exitCalibration` 經 `finish()` 返回，系統返回亦可離開。但採樣期無任何觸控落點（自繪 View 無按鈕），取消／重試在採樣期只能靠系統返回／手動離開；D1-9 眼控取消目標僅預留，未實作（見 DCL-01）。系統無障礙操作（TalkBack／Switch Access）的相容性為靜態推斷、未經實機驗證（見 `d4-decision-record.md` Q-D4-18）。 |
| 校正失敗時不得開啟眼控 | 已實作（部分） | 訓練／存檔失敗進 ERROR 態不 `finish()`（`:680-693`）；交接／引擎失敗進 ERROR 態（`:301-343`）。但「偏差」等級仍存檔並可開啟眼控（見 DCL-01 品質門檻），「失敗」的定義不含低品質校正。另：`onDestroy` 無條件 `resumeAfterCalibration()`（`:865-873`）恢復的是校正前的服務狀態，非「開啟眼控」的新授權，語義上符合；但若校正前服務已在運作，取消／失敗後服務恢復追蹤時將以舊模型運作，此為既有語義，非本子項的違反。 |
| （相鄰缺口）相機權限檢查 | 未實作 | `CalibrationActivity.kt` 全檔 grep `CAMERA`／`Permission` 零命中，無相機權限檢查；`MainActivity` 的相機權限請求 launcher 在 Phase 3 實作中被移除、現況僅檢查＋Toast（見 `engineering-baseline.md` §2.3／§8，意圖待確認）。校正頁在無權限時將直接走交接／引擎啟動失敗路徑（ERROR 態），而非事前檢查。 |

#### DCL-09 追蹤恢復穩定性 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 攝影機恢復後重建追蹤 | 已實作 | 引擎持續運作時臉部重現即恢復樣本流；`startEngine` 重建引擎實例（`GazeAccessibilityService.kt:391-431`）；`attemptRecovery` 在引擎為 null 時補 `startEngine()`（`:1411-1465`）。 |
| 清除 OneEuroFilter 舊歷史 | 部分實作 | 只在 `startEngine`（`:401-402`）、`onConfigurationChanged`（`:358-359`）、`attemptRecovery` 引擎本就跑著時（`:1439-1440`）重置。臉部重現的主路徑（`onSampleOnMainThread` 臉遺失分支 `:511-543`）**未**重置 `filterX`／`filterY`——臉回來第一幀即以舊濾波歷史平滑新座標。 |
| 清除舊手勢 | 部分實作 | 臉遺失分支清 `screenDwell` 與邊緣手勢（`cancelEdgeGesture()`，`:522-524`）、清面板／把手進度（`:516-520`）。但未清 `swipeStartPoint`（`:246`，僅在 `showReminder` 附近 `:1082` 與 `attemptRecovery` `:1451` 清除）、未清 `isBlinking`／`blinkStartTime`（`:282-283`）、未清待發的雙擊第二下（見 DCL-11）。 |
| 重新判斷臉部與眼睛特徵 | 已實作（部分） | 每幀以 `sample == null` 判臉存在、以 `leftOpen`／`rightOpen` 與 `blinkThreshold` 判睜眼（`:548-585`）、以 `EyeImageMetrics.isDegraded` 判影像降級（`:630-659`）。但無「重新判斷」的序列要求（見下）。 |
| 動態監測追蹤穩定度 | 未實作（主路徑） | 主路徑無連續穩定樣本計數、無 dispersion 計算、無穩定度狀態。僅邊緣控制器有連續 present 幀數與 dispersion 量測（`EdgeGestureController.kt:374-412`），且 dispersion 門檻為 `Float.MAX_VALUE`（永不阻擋，`:509`）。 |
| 通過品質門檻後才解鎖 | 未實作 | 臉回來後第一幀有效樣本即可累積 dwell（`screenDwell.update`，主路徑 `:930-965` 段）；無任何品質門檻守衛在 dwell 累積之前。`STATE_DESIGN.md` §4.2 的恢復序列（7 步）已聲明不在 Phase 3 範圍，僅 (5)(6) 中與 D-2／D-3 相關的部分已做。 |
| 不得使用舊座標或舊意圖 | 部分實作 | `screenDwell.reset()` 使舊 dwell 進度不沿用；`hasValidCursor` 在臉遺失期間維持舊值，降級／臉遺失時游標凍在上一位置顯示（`:630-659` 的凍結語義為有意設計：顯示最後有效位置而非舊意圖執行）。但 `swipeStartPoint` 舊起點可被臉重現後的新終點配對執行（舊意圖殘留），見 DCL-11。 |

#### DCL-10 左右邊緣寬度 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 左右獨立調整、獨立儲存 | 部分實作（開關層面） | `GazeSettings` 左／右／頂／底四個獨立欄位＋獨立 key（`:62-74`、`:175-179`），`save`／`load` 各自讀寫（`:118-122`、`:213-216`）；`SettingsActivity` 四列獨立開關（`:80-101`、`:295-300`）；`EdgeGestureConfig.edgeEnabled` 按邊分派（`EdgeGestureController.kt:615-620`）。但「調整」僅指開／關，**寬度值本身不可調**（見下）。 |
| 可恢復建議值 | 已實作 | `SettingsActivity.resetToDefaults`（`:306-329`）將五個邊緣開關恢復為 `GazeSettings()` 預設值（全開），並以 Toast 明示「按儲存才生效」。 |
| 支援左右獨立啟用及總開關 | 已實作 | 總開關 `edgeGesturesEnabled`（預設開）＋四邊分項；總關時分項列 disable（`refreshEdgeGestureRows` `:213-225`）；控制器在總關／分項關時回 `Cancelled(DISABLED)`／`NoAction`（`EdgeGestureController.kt:186-230`）。 |
| 安全最小值及最大值 | 未實作 | 無任何邊緣寬度設定項，故無最小／最大值。唯一的寬度相關常數為固定 `EDGE_BAND_DP = 50f` 與短邊 15% 上限（`:490-496`），皆非使用者可調的安全範圍。 |
| 不得因改一側影響另一側 | 已實作 | 各邊開關獨立讀寫、獨立判定；`zoneFor` 角落優先序（頂＞底＞左＞右，`:113-134`）為既有語義，不因開關改變。 |
| 不得在手勢執行中造成狀態混亂 | 已實作（部分） | 幾何改變時進行中的候選／確認取消（`:153-171`）；`reset()` 保留鎖定、只清進行中進度（`:457-468`）；方向改變時服務端 `cancelEdgeGesture()`（`GazeAccessibilityService.kt:352-366`）。但開關在手勢執行中切換的行為未經實機驗證（E-7／E-8 未做）。 |

缺口小結：DCL-10 的「寬度」本體（獨立寬度值、最小／最大值）未實作；已實作者為「開關」本體。不得把開關獨立性說成寬度獨立性。

#### DCL-11 追蹤中斷自動恢復 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 中斷時立即停止新眼控操作 | 已實作 | 臉遺失分支直接 `return`（`GazeAccessibilityService.kt:511-543`），不進入預測／dwell／面板／邊緣分支；`onSample` 在 `pausedForCalibration`／`userStopped` 時提早返回（`:484-489`）。 |
| 清除 dwell、blink、swipe、edge history、pending gestures、過期 gaze／filter history | 部分實作 | 清除：`screenDwell.reset()`、面板／把手進度清零、`cancelEdgeGesture()`（`:516-524`）。未清除：`isBlinking`／`blinkStartTime`（眨眼狀態）、`swipeStartPoint`（滑動起點）、`hasValidCursor`／最後游標位置、濾波器歷史（無 reset）、`cooldownUntil`（冷卻時間戳沿用）。「pending gestures」：進行中的 `dispatchGesture` 回呼無取消（`onCancelled` 零命中，見 DCL-16／17）；雙擊第二下的 `postDelayed(150)` 未在臉遺失時移除（`performDoubleTap` `:1573-1577`）。 |
| 顯示中斷提示 | 已實作（部分） | 游標留原地變灰圈（`cursor.setFaceLost()`，`:513`；`CursorOverlayView.setFaceLost`）。無文字提示（臉遺失文字提示僅在校正頁，`CalibrationActivity.kt:382-390`）；Dashboard 以快照 `facePresent` 顯示「等待臉部」（`MainActivity.kt:399`）。 |
| 臉部重新出現後自動重建追蹤並重新判斷穩定性 | 部分實作 | 自動重建：引擎續跑，下一幀有效樣本即恢復主路徑。重新判斷穩定性：未實作（見 DCL-09，無穩定序列／門檻）。 |
| 不得累積中斷期間停留時間 | 已實作 | `screenDwell.reset()` 使中斷期間無累積；閉眼期間亦重置 dwell（`:560`、`:583`）。但 RECOVERY 目標的進度在臉遺失時「凍結不清零」（`updateRecoveryTarget` `:1311-1326`），屬 RECOVERY 語義（有意設計），不得與本子項混淆。 |
| 不得重播舊操作 | 部分實作 | dwell／邊緣舊進度已清，不重播。但舊 `swipeStartPoint` 與待發雙擊第二下可在中斷後被新幀配對／觸發（見上），屬舊操作殘留。 |

#### DCL-12 多指標追蹤品質 — 部分實作

指標覆蓋（散見各處，無統一彙整）：

| 規格指標 | 程式落點 |
|---|---|
| 臉部追蹤 | `sample == null` 即臉遺失（`GazeAccessibilityService.kt:511`）；邊緣幀 `facePresent`（`EdgeGestureController.kt:581-602`）。 |
| 眼睛特徵完整度 | `leftOpen`／`rightOpen` 與 `blinkThreshold`（`:548-585`）；邊緣幀 `eyesOpen`、`featuresFinite`（`:581-602`）；特徵有限性守衛見 `edgeFrame`（`:1220-1249`）。 |
| 注視穩定度 | 僅邊緣控制器：連續 present 幀數＋最大偏離 dispersion（`evaluateChecks` `:374-412`）；主路徑無。 |
| 頭部移動 | 特徵 7／8（pitch／yaw）與 `faceTurnDirection`（`GazeFeatureExtractor.kt:201-225`，死區 0.02）；條件歷史的 pitch／roll 平滑值（`GazeAccessibilityService.kt:1002-1033`）。僅記錄／條件修正用，未納入品質判定。 |
| 校正品質 | `GazeEvaluator` 訓練集誤差＋留一法誤差（`GazeEvaluator.kt:38-96`），為歷史值，僅在校正完成時計算；`ProfileMeta.looErrorPx` 存檔（`CalibrationStore.kt:31-32`）。不得冒充即時品質（`STATE_DESIGN.md` §4.4）。 |
| 樣本有效性 | `EyeImageMetrics.isDegraded`（亮度／對比／反光三門檻，`EyeImageMetrics.kt:296`）；預測非有限時校正頁選擇路徑回 null（`CalibrationActivity.kt:566-580`），但服務主路徑未對預測非有限設守衛（濾波後僅 `coerceIn` 箝制，`:662-663`）。 |
| 臉部距離變化 | 特徵 6（IOD）與 `distanceCompensation` 縮放（`GazeAccessibilityService.kt:614-618`）；條件歷史的 IOD 平滑值。僅修正用，未納入品質判定。 |

| 子項 | 狀態 | 證據 |
|---|---|---|
| 狀態至少 NORMAL、UNSTABLE、UNAVAILABLE | 未實作 | 全樹無品質狀態列舉（僅 `AppFlowState`、`ActionMode`、`FaceTurn`、`TrainingActivity.Kind`、`Edge*` 列舉，見 `engineering-baseline.md` §8.1）。`STATE_DESIGN.md` §4 的品質層七態（`FACE_NOT_FOUND`…`RECOVERING`）為設計定義，**未實作**為程式狀態。 |
| 必要輸入無效時不得用其他高分指標抵銷 | 未實作（主路徑）／部分實作（邊緣） | 主路徑無分數、無彙整規則，無法判定抵銷；邊緣 `EdgeCheckReport.allPass()`（`EdgeGestureController.kt:568-578`）要求全部檢查通過，任一失敗即 `Fail`，此為「不得抵銷」的局部實現，僅限邊緣路徑。 |
| 所有閾值須透過測試資料校準，不得任意指定 | 未實作（校準未做） | 影像降級門檻註明「啟發式、未經實機校準，只擋極端情況」（`EyeImageMetrics.kt:40-48`）；邊緣門檻標 `UNCALIBRATED`、dispersion 預設不阻擋（`EdgeGestureController.kt:502-511`）；校正等級門檻 60／120／200 px 為經驗值（`CalibrationActivity.kt:718-724` 註解）；`FACE_TURN_DEAD_ZONE = 0.02`（`GazeFeatureExtractor.kt:225`）無校準記載。門檻「存在」不等於「已校準」。 |

### 4.2 B. 點擊與手勢（DCL-13～DCL-23）

#### DCL-13 眨眼點擊 — 實作不符合規格

全部子項皆與現況行為牴觸：

| 子項 | 證據 |
|---|---|
| 預設關閉、獨立開關 | **相反**：全樹 grep `blinkEnabled`／眨眼開關零命中；`GazeSettings` 只有 `blinkMinMs`／`blinkMaxMs`／`blinkThreshold` 三個參數（`:17-19`），無啟用開關；`SettingsActivity` 只有三個眨眼參數滑桿（`:51-57`、`:283-285`），無開關列。眨眼點擊在服務啟動後即生效。 |
| 只有完成個人化意圖校正且通過品質評估後才能啟用 | **相反**：無任何個人化意圖校正流程、無啟用閘門；眨眼判定只讀通用設定值，與目前情境的校正模型無關（模型僅用於游標座標，眨眼觸發用的是閉眼前最後有效游標 `lastValidX`／`lastValidY`，`GazeAccessibilityService.kt:557-558`）。 |
| 須區分自然眨眼與刻意眨眼 | 未實作：判定只看「雙眼皆閉合」（`bothClosed`，`:548-550`），無持續時間以外的特徵（無閉合深度變化率、無單／雙眼模式、無注視穩定度聯合判定）。 |
| 不得單純依靠 150～600ms 時間範圍判定 | **相反**：判定核心即時間窗——預設 `DEFAULT_BLINK_MIN_MS = 150L`、`DEFAULT_BLINK_MAX_MS = 600L`（`GazeSettings.kt:133-134`），觸發條件為 `blinkDuration in settings.blinkMinMs..settings.blinkMaxMs`（`GazeAccessibilityService.kt:570`），其餘條件僅為冷卻／暫停／提醒守衛。 |
| 須評估 FP／FN | 未實作：全樹無 FP／FN 量測、紀錄或評估程式；`GazeLog` 僅記錄座標與特徵，不記錄眨眼判定結果。 |
| 無法通過時不得啟用 | 未實作：無「通過／不通過」概念，眨眼點擊永遠啟用。 |

#### DCL-14 停留點擊重新觸發 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 狀態 READY、DWELLING、TRIGGERED、DISARMED、REARMING | 未實作 | 無此五態列舉。螢幕停留只有 `DwellTracker` 的進度值（0..1，`DwellTracker.kt:48-104`）與服務端的 `cooldownUntil` 時間戳（`GazeAccessibilityService.kt:276`）；面板／把手只有 `handleArmed`／`panelButtonArmed` 布林（`:263`、`:270`）。`AppFlowState`／`EdgeControllerState` 皆無此五態。 |
| 同一目標停留期間只能觸發一次 | 已實作（機制上） | 觸發後設 `cooldownUntil = now + cooldownMs` 並 `screenDwell.reset()`（主路徑觸發分支，`:953-962` 段）；冷卻期間 `screenDwell.reset()` 不累積。面板／把手觸發後同樣設冷卻並清武裝（`:736-791` 段）。 |
| 須離開目標並確認離開後才能重新啟用 | 部分實作（僅面板／把手） | 面板／把手：視線離開面板／把手區後重新武裝（主路徑 `:819-822` 段：`!gazeInPanelRect` 時 `panelButtonArmed = true`；把手同理 `handleArmed`）。螢幕停留：**無**離開確認——`DwellTracker` 在遠跳時重新錨定（`distToAnchor > radius × 2.5` 時歸零重錨，`:73-80`），但冷卻到期後同一位置持續停留即可再次累積觸發，無需先離開。 |
| 不能只依賴冷卻時間 | **不符合（螢幕停留）** | 螢幕停留的重啟門檻實質上只有冷卻時間（`cooldownMs` 預設 1200ms，`GazeSettings.kt:131`）加上 `DwellTracker` 的衰減／重錨；無 DISARMED／REARMING 語義。面板／把手因有武裝規則，較接近規格，但螢幕點擊為主要路徑，不得以面板規則冒充全體。 |
| 邊界抖動須遲滯處理 | 已實作（等效機制） | `DwellTracker`：錨點 EMA 跟隨（α=0.2，`:84-85`）、取消半徑 2.5×（`:73`、`:108`）、速度上限 2000 px/s（`:87`、`:114`）、短暫離開 2 倍速衰減、離開 300ms 後重置（`:91-101`、`:120`）。遲滯以衰減／重錨實現，非具名遲滯狀態，但行為上符合「抖動不立即歸零／不立即重觸發」。 |
| 追蹤中斷清除舊停留狀態 | 已實作 | 臉遺失／閉眼／降級分支皆 `screenDwell.reset()`（`GazeAccessibilityService.kt:522`、`:560`、`:583`、`:646`）。 |
| 手勢取消不得自動重播 | 部分實作 | 邊緣取消分支清 `screenDwell`（主路徑 `Cancelled` 分支）；`cancelEdgeGesture` 清控制器狀態。但一般手勢取消（dispatch 層）不存在（見 DCL-16／17），「取消後重播」的防護只在邊緣／dwell 層有，dispatch 層的待發雙擊第二下可視為一種重播殘留（見 DCL-11）。 |

#### DCL-15 眨眼與停留點擊仲裁 — 未實作

無統一 Click Arbiter：全樹 grep `Arbiter`／`ClickArbiter` 零命中（僅文件註解提及 `dispatchGesture`）。兩路徑各自獨立：

- 眨眼路徑：`GazeAccessibilityService.kt:565-577`，睜眼幀直接 `triggerActionAt(lastValidX, lastValidY)` 並設冷卻、`screenDwell.reset()` 後 `return`。
- 停留路徑：主路徑 dwell 滿後 `triggerActionAt(anchorX, anchorY)` 並設冷卻（`:953-962` 段）。

| 子項 | 狀態 |
|---|---|
| 統一 Click Arbiter | 未實作（無此類別／函式）。 |
| 同一時間只允許一個有效點擊意圖 | 未實作：兩路徑無互斥鎖、無進行中意圖旗標；僅共用 `cooldownUntil` 時間戳作事後節流，冷卻到期後兩路徑可先後觸發。 |
| 衝突意圖丟棄；不排隊、不延遲、不重播 | 未實作：無衝突判定；眨眼路徑在冷卻期間靜默不觸發（可視為丟棄），但無明確的衝突語義記錄；雙擊第二下的 `postDelayed` 屬延遲排隊（見 DCL-17），與「不排隊」牴觸。 |

#### DCL-16 統一手勢仲裁 — 未實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 所有操作須經 GestureArbiter（單擊、雙擊、長按、滑動、邊緣操作、返回、Home、側邊捲動、暫停、恢復） | 未實作 | 全樹 grep `GestureArbiter` 零命中。`gesture-dispatch-state-machine.md` 的統一 `GestureDispatcher` 設計（七態、唯一 `gestureId`、十步流程）**尚未實作**（該文件為 PHASE 3 設計，見 `engineering-remediation-plan.md` §2 PHASE 3）。 |
| 狀態至少 IDLE、VALIDATING、ARBITRATING、DISPATCHING、EXECUTING、COMPLETED、CANCELLED、FAILED | 未實作 | 無此狀態列舉。最接近者為邊緣控制器的局部狀態（`EdgeControllerState`：`IDLE／CANDIDATE／CONFIRMING／COOLDOWN`，`EdgeGestureController.kt:554-557`，另有單幀 `Execute` 判決），僅覆蓋邊緣路徑，不含 VALIDATING／ARBITRATING／DISPATCHING／EXECUTING／FAILED。 |
| 不得讓各功能直接各自呼叫 dispatchGesture() | **不符合** | 四個 `perform*` 函式各自直接呼叫 `dispatchGesture`（`performScroll` `:1542-1560`、`performTap` `:1561-1571`、`performLongPress` `:1580-1590`、`performSwipe` `:1592-1605`）；`triggerActionAt`（`:1479-1517`）按模式直接分派；`performEdgeAction`（`:1519-1541`）直接呼叫 `performGlobalAction`（返回／首頁）與 `performScroll`。暫停／恢復走 `currentMode` 旗標，不經任何仲裁。 |

#### DCL-17 手勢衝突 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 已執行中的手勢優先完成 | 未實作 | 無進行中手勢旗標／鎖定；`dispatchGesture` 的 `GestureResultCallback` 只 override `onCompleted`（四處皆然），無 `onCancelled`、無 busy 檢查，新觸發可在舊手勢未完成時發出（僅受冷卻時間戳間接節流）。 |
| 新衝突意圖直接丟棄 | 部分實作 | 邊緣路徑：`Blocked`／`Cancelled` 判決丟棄衝突候選（`EdgeGestureController.kt:271-313`、`323-372`）；冷卻期間的 dwell／面板／把手累積不計入（主路徑冷卻守衛）。但一般點擊路徑無明確的「衝突丟棄」語義，只有冷卻節流。 |
| 不排隊、不延遲、不重播 | 部分不符合 | `performDoubleTap` 的第二下以匿名 `mainHandler.postDelayed({ performTap(x, y) }, 150)` 排隊執行（`:1573-1577`），無具名 Runnable、無 `removeCallbacks`（`engineering-baseline.md` §6.6，K-07）；模式切換／暫停／停止／提醒介入時該待發點擊**不會**被取消，屬延遲重播殘留。 |
| 下一次操作須是新的有效意圖 | 部分實作 | 觸發後 `screenDwell.reset()`＋冷卻（見 DCL-14），下一次觸發須重新累積；邊緣 COOLDOWN 須離開原觸發帶才重新武裝（`enterCooldown`／`lockedEdge`，`EdgeGestureController.kt:442-468`）。但舊 `swipeStartPoint` 可被新終點配對（舊意圖＋新座標），見 DCL-11。 |

#### DCL-18 安全事件最高優先級 — 部分實作

| 安全事件 | 處理現況 |
|---|---|
| 使用者暫停 | `currentMode = PAUSED` 時螢幕點擊分支與眨眼觸發被拒（主路徑 `:757-791` 段、`GazeAccessibilityService.kt:570`）；但 `pauseTracking()`（`:1823-1835`）**不清除**進行中的 dwell／swipe 起點／邊緣狀態，僅設模式、展開面板、隱藏游標。 |
| 攝影機追蹤中斷 | 臉遺失分支停止新操作並清 dwell／邊緣（`:511-543`）；但未清全部待執行意圖（見 DCL-11）。相機綁定失敗本身無可觀測信號（見 DCL-06），「中斷」只能以樣本新鮮度間接推斷。 |
| 臉部離開畫面 | 同上（`sample == null` 即臉遺失）。 |
| AccessibilityService 停止 | `onDestroy`（`:1607-1632`）停引擎、關 `GazeLog`、移除 overlay；但 `onInterrupt()` 為空實作（`:1635` `= Unit`），系統中斷時**無任何取消／清除動作**；`onAccessibilityEvent` 亦為空實作（`:1634`）。服務被系統停用後須使用者回系統設定重開，App 內無區分顯示以外的處理（Dashboard 只顯示「未開啟」，見 `engineering-baseline.md` 相關記載）。 |
| 眼控關閉 | `userStopped`／`inRecoveryMode` 守衛（`onSample` `:484-489`、主路徑 RECOVERY 分支 `:503-506`）；`stopTracking` 清 dwell／邊緣／面板進度（`:1886-1925`）；`stopCompletely` 清邊緣、藏目標、停引擎（`:1925-1967`），但未清 `swipeStartPoint`。 |
| 嚴重錯誤 | `startEngine` 同步失敗進 `ERROR` 並帶原因（`:408-427`）；`failRecovery` 進 `ERROR`（`:1467-1477`）；`AppFlowState.ERROR` 為流程層終態之一。但非同步綁定失敗不進 ERROR（見 DCL-06），「嚴重錯誤」的覆蓋不完整。 |
| 立即取消目前操作並清除待執行意圖 | 部分實作：提醒 preempt（`reminderActive` 時只跑提醒狀態機，`:690-696`）、RECOVERY 單一拒絕分支、停止分支皆有「立即停止新操作」的效果；但「清除待執行意圖」不完整（雙擊第二下、swipe 起點、眨眼狀態殘留，見 DCL-11／17）。 |
| 恢復後須重新檢查權限、服務及品質條件 | 未實作：`resumeTracking`（`:1841-1866`）不重查權限／服務／品質，僅清旗標、恢復模式、引擎為 null 時重啟；`attemptRecovery` 僅查 3 項（見 DCL-20）。 |

#### DCL-19 獨立緊急停止 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 不得依賴眼球追蹤 | 部分實作 | 觸控停止入口不依賴眼球（見下）；但面板「暫停」與 RECOVERY 重啟目標皆依賴眼球，不得視為緊急停止。 |
| 提供實際可行的實體音量鍵操作方式 | 未實作 | 全樹 grep `KeyEvent`／`onKeyDown`／`dispatchKeyEvent`／`KEYCODE_VOLUME`／`volume` 零命中；無任何音量鍵監聽、攔截或處理程式。 |
| 提供螢幕觸控停止入口 | 已實作 | Dashboard「停止追蹤」鈕 → `AlertDialog`（`MainActivity.kt:203-221`），提供「停止眼控」（RECOVERY）與「完全停止」兩個觸控選項；另有「暫停眼控」鈕（`:193-201` 段）。 |
| 可在 Android 支援範圍內自訂 | 未實作 | 無任何緊急停止自訂設定（`GazeSettings`／`SettingsActivity` 皆無相關 key／列）；無自訂入口。 |
| 不得假設所有裝置都允許全域攔截音量鍵 | 無反證（靜態） | 程式中無全域攔截假設（因根本無音量鍵程式）；裝置／系統是否允許攔截，靜態無法確認，須實機／系統行為驗證，見 §5 矛盾 T-07。 |
| 須保留替代停止方式 | 已實作（部分） | 觸控對話框（Dashboard）＋面板暫停（眼控）＋系統設定停用服務三條路徑存在；但「獨立緊急停止」本身（區別於一般暫停／停止）不存在——停止對話框即一般停止入口，無獨立的緊急停止語義或狀態。 |

#### DCL-20 緊急停止後恢復 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 允許自動恢復 | 已實作 | RECOVERY_MODE＋眼控 dwell 重啟（`updateRecoveryTarget` `:1301-1408`）與 Dashboard 手點恢復（`resumeFromRecovery` `:1868-1882`）共用 `attemptRecovery`（`:1411-1465`）；無任何禁止自動恢復的程式邏輯。R-4 覆蓋成立。 |
| 須重新檢查攝影機、臉部、眼睛特徵、追蹤品質、穩定性、權限及服務 | 部分實作（僅 3 項） | `RecoveryGate.evaluate`（`RecoveryGate.kt:44-56`）只查 (a) 服務實例仍在、(b) 引擎仍在跑、(c) 校正模型有效。臉部／眼睛特徵只在 dwell 累積階段以凍結語義檢查（臉遺失／閉眼時不推進，`updateRecoveryTarget` `:1311-1344`），**手點恢復路徑完全繞過**臉部／眼睛檢查（`resumeFromRecovery` 直接 `attemptRecovery`）。攝影機（綁定狀態）、追蹤品質、穩定性、權限、服務真實啟用狀態皆未在恢復時重查。 |
| 清除舊操作及舊座標 | 已實作（部分） | `attemptRecovery` 通過後：濾波器重置（引擎本就跑著時，`:1439-1440`；否則經 `startEngine` 內重置）、`swipeStartPoint = null`、面板收合、`screenDwell.reset()`、錨點重設為螢幕中心、冷卻重設（`:1448-1464`）。未清除：`isBlinking`、提醒狀態（`reminderActive` 未在此處處理）、面板 hover 殘留（`hoverBtnIndex`／`hoverStart` 未在此處清零，僅在 `stopTracking` 時清零）。 |
| 不得重播舊手勢 | 已實作（部分） | 同上清除使舊 dwell／swipe 不重播；邊緣狀態在進入 RECOVERY 時已由 `stopTracking` 的 `cancelEdgeGesture()` 清除。但雙擊第二下待發 runnable 在停止／恢復路徑皆未取消（見 DCL-17），理論上可在恢復後觸發。 |
| 正式覆蓋 DCL-19 原禁止自動恢復的限制 | 已實作（語義層） | 程式行為與 DCL-20 一致（允許恢復）；`restart-recovery-policy.md` 等第一輪文件中「不自動恢復」的舊預設已被 R-4 覆蓋，不再作為判定依據。 |

#### DCL-21 自動恢復倒數 — 已實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 不需要額外倒數 | 已實作 | 全樹 grep `countdown`／`倒數` 在服務與 `RecoveryGate` 中零命中；`attemptRecovery` 在 dwell 滿或手點觸發時立即執行驗證序列，無倒數計時、無倒數 UI。恢復後的 `cooldownUntil`（`:1463`）是恢復**之後**的一般操作冷卻，不是恢復前的倒數，不得混淆。 |
| 所有安全條件通過後立即恢復 | 已實作（在此項的範圍內） | `updateRecoveryTarget` 進度滿即 `attemptRecovery()`（`:1393-1396`）；`RecoveryGate` 全過即 `Allow`，隨即執行重置與狀態切換（`:1411-1465`）。「所有安全條件」的集合是否完備屬 DCL-20 的缺口（僅 3 項檢查），不在此項重複扣分。 |
| 恢復當下任何條件不通過就取消此次恢復 | 已實作 | `RecoveryGateDecision.Deny` 即 `failRecovery`（`:1422-1425`），進 `ERROR`＋原因 Toast，不回到眼控運作；`startEngine` 補啟動後再驗一次，失敗亦 `failRecovery`（`:1427-1437`）。 |
| 不得重播舊操作 | 已實作（在此項的範圍內） | 同 DCL-20 的清除（`attemptRecovery` `:1448-1464`）；失敗路徑 `failRecovery` 清恢復旗標、藏目標（`:1467-1477`），不執行舊操作。 |

#### DCL-22 一般暫停與緊急停止 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 一般暫停透過明確操作切換 | 已實作 | 面板「暫停」dwell 滿（主路徑面板分支）或 Dashboard「暫停眼控」鈕（`MainActivity.kt` 暫停鈕 → `pauseTracking()` `:1823-1835`）；暫停時面板展開顯示「重啟」，狀態明確。 |
| 有誤觸防護 | 已實作（部分） | 面板按鈕 dwell 須經武裝（`panelButtonArmed`）與冷卻守衛（主路徑 `:752-791` 段）；Dashboard 暫停為手點按鈕。但暫停操作本身無二次確認（此為規格所許的「明確操作」，非缺口）。 |
| 立即停止新眼控操作 | 已實作（部分） | `PAUSED` 模式下螢幕點擊分支被拒（主路徑 `:757-791` 段：非第 5 鍵不反應）、眨眼觸發被拒（`:570` 的 `currentMode != PAUSED` 條件）、游標隱藏（`pauseTracking` `:1823-1835`）。但引擎續跑、樣本續進，面板「重啟」鍵以外的面板輸入仍以 dwell 計時（僅在觸發時被拒），且進行中的 dwell／swipe 未清除（見 DCL-18）。 |
| 恢復時重新檢查品質及穩定性 | 未實作 | `resumeTracking`（`:1841-1866`）無任何品質／穩定性檢查；面板「重啟」dwell 滿即回 `TRACKING` 語義，無穩定序列門檻。 |
| 緊急停止立即停止、不得被確認視窗或第二次確認延遲 | 部分實作（有保留） | 緊急停止本身無獨立入口（見 DCL-19）；最接近的「完全停止」須經 `AlertDialog` 確認對話框（`MainActivity.kt:203-221`），對話框即入口、無第二次確認，但對話框的出現與點按本身即為一次確認步驟，緊急情境下不如直接動作即時。對話框內的「停止眼控」與「完全停止」為二選一，非「確認＋取消」的二次確認，語義上不違反「不得被第二次確認延遲」，但「立即性」仍受對話框路徑限制。 |

#### DCL-23 操作安全區域 — 部分實作

| 子項 | 狀態 | 證據 |
|---|---|---|
| 不增加額外使用者自訂安全區域 | 已實作 | 全樹 grep `safeZone`／`safeArea`／`safetyZone`／`exclusion` 零命中；`GazeSettings`／`SettingsActivity` 無任何安全區域設定。 |
| 螢幕所有可操作區域都可接受眼控座標 | 已實作 | 游標座標取全螢幕範圍（`coerceIn(0f, screenW - 1f)`，`GazeAccessibilityService.kt:662-663`）；螢幕尺寸取 `currentWindowMetrics.bounds`（`:335-338`）；面板／把手／邊緣／螢幕四路徑皆吃同一幀座標，無區域排除。 |
| 透過停留點擊、追蹤品質、仲裁及安全中斷控制誤觸 | 部分實作 | 停留點擊（`DwellTracker`＋冷卻）已實作；追蹤品質（僅降級凍結＋邊緣檢查，見 DCL-12）、仲裁（僅邊緣局部，見 DCL-16）、安全中斷（部分清除，見 DCL-18）皆不完整。誤觸控制鏈不完整，不得認定已滿足。 |
| 不得宣稱 Android 所有系統畫面都能被控制 | 無反證（靜態） | 全樹 grep「所有系統畫面／都能控制／任何畫面」等宣稱零命中；程式以 `dispatchGesture`／`performGlobalAction` 注入操作，未在 UI 文字中宣稱全系統畫面可控。系統畫面實際可控範圍（例如系統設定頁、鎖定畫面）須實機驗證，靜態無法確認，列為待確認（不影響整項狀態）。 |

### 4.3 C. 游標與智慧目標吸附（DCL-24～DCL-40）— 全部未實作

本節 17 項全數「未實作」，與 R-6（本階段只做對照、不得在本輪實作）一致。以下先記共同證據，再逐項記「最接近的相鄰機制為何不是該功能」，避免日後把相鄰機制誤認為已實作。

**共同證據**：

1. 游標管線只有一條：`GazeModel.predict` → 條件偏移 → 靈敏度增益 → `OneEuroFilter` → 箝制（`GazeAccessibilityService.kt:596-663`）。座標輸出後直接用於面板／邊緣／dwell 三路徑，中間無任何目標查詢、吸附修正或座標改寫。
2. 全樹 grep `snap`／`magnet`／`smartTarget`／`OCR`／`tesseract`／`ML Kit` 零命中（僅 `trackingSnapshot` 的 `snap` 變數名，與吸附無關）；`build.gradle.kts` 依賴只有 CameraX／MediaPipe／AndroidX／Material，無任何 OCR／視覺辨識依賴（`app/build.gradle.kts:122-134`）。
3. 無障礙節點讀取不存在：`onAccessibilityEvent` 為空實作（`GazeAccessibilityService.kt:1634`），全樹無 `getRootInActiveWindow`／`AccessibilityNodeInfo`／`getWindows` 呼叫。無障礙設定檔宣告 `canRetrieveWindowContent="true"`（見 `engineering-baseline.md` §4.3）僅為能力宣告，未被程式使用。
4. 無信心分數：`EdgeCheckReport` 的檢查結果（`EdgeGestureController.kt:568-578`）僅供邊緣路徑內部使用，不輸出任何對外信心分數；`STATE_DESIGN.md` §4.4 明載 MediaPipe 信心值未被讀取。

| DCL | 最接近的相鄰機制（不是該功能） | 缺口 |
|---|---|---|
| DCL-24 自適應游標平滑 | 濾波參數按 dwell 進度二檔切換（`GazeAccessibilityService.kt:588-594`：進度＞0 時 `minCutoff 0.04／beta 0.005`，否則取使用者設定）；使用者可調靈敏度（`sensitivity`，`GazeSettings.kt:26`）與濾波參數（`filterMinCutoff`／`filterBeta`，`:20-21`，設定頁滑桿）。 | 無依注視穩定度／頭部移動／追蹤品質的動態調整；無延遲—穩定度平衡量測；低品質時僅 `isDegraded` 凍結游標（`:630-659`），非「安全鎖定」狀態（無鎖定語義、無解鎖門檻）。「使用者可調整速度及平滑程度」子項部分存在（靈敏度＋濾波滑桿），但「自適應」核心未實作。 |
| DCL-25 短期預測 | `GazeModel.predict`（迴歸輸出）與 `OneEuroFilter` 的速度限幅（`maxVelocity = 3500`，`OneEuroFilter.kt:18`、`:44-50`）。 | 兩者皆非短期預測：`predict` 為當幀迴歸，無方向／速度外推；限幅為平滑鉗制，非預測。無信心概念、無退回實際測量座標的分支、無預測誤差／延遲／過度預測量測。 |
| DCL-26 智慧目標吸附 | 無。游標即濾波後座標，無目標概念。 | 無目標距離／尺寸／品質／信心／歧義輸入；無「只改變游標位置」的修正層。 |
| DCL-27 混合目標辨識 | 無（見共同證據 3）。 | 四層來源（節點／視窗／系統標記／視覺）皆無；無統一信心／位置／互動屬性；無衝突處理；無低信心回退（回退對象「原始眼球座標」即現況全部行為，不得冒充為回退機制）。 |
| DCL-28 完全端側運算 | 無辨識功能可言端側或上傳。 | 須注意的相鄰事實：`VerifyFrames`／`GazeLog` 會上傳眼區裁切與游標數字（見 DCL-41 註），但其內容不在 DCL-28 列舉的「螢幕畫面、OCR 文字、視覺特徵、目標辨識結果」範圍內；本項標未實作，不以「未上傳不存在的資料」認定合規。離線運作亦無從驗證（無功能可離線運行）。 |
| DCL-29 辨識範圍 | `dispatchGesture` 可在任意座標注入點擊（機械上全螢幕可達）。 | 「可點擊任意位置」不等於「辨識一般 UI／遊戲 UI／自繪介面／無節點控制元件／非標準區域」；無互動可能性判斷，無「有物件≠可互動」的守衛。 |
| DCL-30 非標準介面智慧推論 | 無。 | 無形狀／文字／圖示／位置／上下文推論；無信心與互動可能性門檻；無「只控制游標」模式（現況游標移動後 dwell 即可能觸發點擊）。 |
| DCL-31 動態介面更新 | 條件評估每 500ms（`CONDITION_CHECK_INTERVAL_MS`，`GazeAccessibilityService.kt:685-688`）。 | 該評估偵測的是亮度／角度／臉距條件變化（提醒用），不是畫面／介面變化；無辨識頻率調整、無移動目標更新、無追蹤失敗重辨識、無資源不足降頻、無過期座標失效（座標每幀重算，無「過期吸附」概念）。 |
| DCL-32 使用者控制 | 邊緣手勢總開關（`GazeSettings.kt:66`）。 | 邊緣開關不是智慧吸附開關；無吸附總開關、無 AI 自動決定模式、無「關閉時只用原始座標」的切換（現況恆為原始座標）。「不增加逐 App 設定」為功能缺席所致，非已實作的克制。 |
| DCL-33 漸進式吸附 | `DwellTracker` 的錨點 EMA 跟隨（`DwellTracker.kt:84-85`）。 | 錨點跟隨是 dwell 錨點的漂移補償，不是吸附混合；無距離／信心混合權重、無高信心完整吸附、無離開平滑返回、無低信心停止吸附。 |
| DCL-34 多目標選擇 | 無。 | 無候選者集合、無距離／尺寸／信心／互動可能性／重疊／歧義／穩定度綜合評分、無「無明確最佳者回原始座標」分支。 |
| DCL-35 最佳互動點 | 無。 | 無目標內部點選擇、無邊界／幾何／文字圖示分布考量、無「不能確認安全點時不吸附」守衛。 |
| DCL-36 短期目標記憶 | `ConditionHistory`（條件偏移記憶，`ConditionHistory.kt`）。 | 條件記憶存的是亮度／角度／臉距／偏移，與目標識別／位置／形狀／信心無關；無目標記憶、無逐次重新驗證、無場景切換清除。不得以條件記憶冒充目標記憶。 |
| DCL-37 畫面及場景切換 | 方向改變時重置濾波器與邊緣手勢（`GazeAccessibilityService.kt:352-366`）；控制器幾何改變取消（`EdgeGestureController.kt:153-171`）。 | 僅為幾何／狀態重置，無目標重新驗證、無彈窗／重大變化清除、無 App／場景切換重辨識、無「不確定回原始座標」的目標語義（現況恆為原始座標）。 |
| DCL-38 目標消失及重新出現 | 無。 | 無目標存在性追蹤、無有期限識別保留、無重新驗證、無「消失期間不得用預測座標吸附」守衛（無預測、無吸附）。 |
| DCL-39 相似目標身份辨識 | 無。 | 無視覺特徵／位置／軌跡／形狀／周圍 UI／時間連續性綜合判斷。 |
| DCL-40 重疊及遮擋 | 面板／把手分支優先於邊緣分支（主路徑分支序）。 | 分支序是同一 overlay 內部的輸入優先序，不是視窗／通知／浮動面板／透明覆蓋層／Z-order 偵測；無可見／可操作區域判斷、無完全遮擋禁吸附規則。 |

### 4.4 D. 敏感畫面與隱私（DCL-41～DCL-46）— 全部未實作

**共同證據**：全樹 grep `FLAG_SECURE`／`MediaProjection`／`sensitive`／`password` 零命中；無任何視覺辨識、OCR、敏感欄位標記讀取、敏感判斷程式。`VerifyFrames` 與 `GazeLog` 的存在不構成 DCL-41～46 的實現（見各項註與 §5 矛盾 T-10）。

| DCL | 狀態與說明 |
|---|---|
| DCL-41 敏感畫面視覺辨識 | 未實作。無端側辨識可言「暫時性／記憶體暫存」；無「不上傳、不寫入日誌、不快取、不持久化、不擷取密碼／付款憑證／秘密值」的辨識資料管線（現有 `GazeLog`／`VerifyFrames` 上傳的是游標數字與眼區裁切，屬診斷／監控管線，與辨識資料無關，不得混為一談）；無「每次辨識結束立即清除」的清除點；無 FLAG_SECURE／MediaProjection／系統限制的偵測與優先處理（亦無繞過程式，此為「無處理」而非「已處理」）。 |
| DCL-42 敏感畫面智慧吸附 | 未實作。無吸附，故無「允許吸附一般按鈕／禁止吸附密碼／付款／敏感個資欄位」的落點。**相鄰風險（規格外，須記錄）**：停留／眨眼點擊可經 `dispatchGesture` 落在任意座標，含密碼欄位，程式中無任何敏感欄位守衛；DCL-42 本身只規範「吸附」，不得以此風險直接認定 DCL-42 不符合，但未來若實作吸附或點擊守衛，須一併處理（見 §6 待批 P-09）。 |
| DCL-43 敏感辨識資料清除 | 未實作。無 OCR 結果／中間處理資料／視覺特徵／暫時辨識結果可清除；無跨影格記憶（因無辨識）；例外／取消／錯誤路徑的清除亦無落點。`VerifyFrames.endSession()`（經 `FaceTrackerEngine.stop()` 呼叫）清的是影格時段佇列與庫存，不是辨識資料，不得冒充。 |
| DCL-44 敏感畫面判斷方式 | 未實作。四層優先序（1. AccessibilityNodeInfo 2. 視窗資訊 3. 系統敏感欄位標記 4. 本機視覺特徵）皆無程式落點（見 §4.3 共同證據 3）；無系統與 AI 衝突時的嚴格保護模式；無「無法確定時採敏感保護模式」的預設分支。 |
| DCL-45 系統限制下的辨識降級 | 未實作。無 FLAG_SECURE／MediaProjection／系統限制的偵測；無停用 OCR／視覺辨識的開關（因功能不存在）；無「只對資訊完整可信且確認可互動的節點吸附」的節點路徑（節點從未讀取）；無「節點資訊不足回原始座標」的降級分支（現況恆為原始座標，不得冒充為降級）。亦無繞過系統限制的程式，此為「無處理」而非「已處理」。 |
| DCL-46 視覺辨識恢復 | 未實作。六步序列（清除舊結果及座標 → 重做 OCR／視覺辨識 → 重判敏感區域 → 重驗信心及目標有效性 → 通過品質與安全條件才恢復混合辨識 → 不通過繼續降級）皆無程式落點；無舊目標／舊意圖重播防護（因無目標／辨識）。臉部重現的主路徑恢復（見 DCL-09）是追蹤恢復，不是辨識恢復，不得混淆。 |

## 5. 技術矛盾逐項記錄

每項格式：問題／影響／不改變產品意圖的最小方案。標「待批」者，未經批准不得實作、不得擅改規格（見 §6）。

| # | 問題 | 影響 | 最小方案（不改變產品意圖） |
|---|---|---|---|
| T-01 | DCL-13：眨眼點擊預設開啟、僅時間窗判定，與「預設關＋個人化意圖校正＋品質評估才能啟用」直接牴觸（`GazeAccessibilityService.kt:570`、`GazeSettings.kt:17-19`）。 | 自然眨眼誤觸點擊；使用者無法關閉眨眼點擊；無 FP／FN 數據即宣稱可用。 | 加獨立開關（預設關）＋啟用閘門（個人化意圖校正＋品質評估）；評估方法與門檻**待批**（P-05），不得先編 FP／FN 門檻數字。未批准前，現況行為維持並明示為不符合，不得宣稱已符合。 |
| T-02 | DCL-05：現況重啟無條件自動恢復（`onServiceConnected` `:320-344`），與「自動嘗試恢復＋重查＋說明原因」的要求方向相反；第一輪政策預設（不自動恢復）亦已被 DCL-05 覆蓋，兩者皆非現況。 | 使用者已停止／暫停的意圖在重啟後被推翻（K-30）；相機在使用者不知情下自動開啟。 | 依 `restart-recovery-policy.md` §4 十步流程的精神落地「嘗試恢復」路徑（載入驗證→重查→失敗說明），但其「不自動恢復工作階段」預設須按 R-2 改寫為 DCL-05 語義；持久化格式待 D-8／P-03 批准。 |
| T-03 | DCL-01：無品質儲存門檻，「偏差」等級仍存檔（`CalibrationActivity.kt:718-724`）；門檻值本身為經驗值、D-12 未決。 | 低品質校正覆蓋舊有效模型，使用者以為已校正、實際精度不足。 | 在 `finishCalibrationInternal` 增加「未達門檻不存檔、進不合格分支（主推重新校正、不刪舊模型）」的守衛；門檻是否取 60／120／200、是否另立儲存門檻**待批**（P-04），不得擅改門檻數值。 |
| T-04 | DCL-06：第一輪九態設計（`camera-binding-observability.md` §3.1）與 DCL-06 七態不一致；程式中兩者皆未實作。 | 若按九態實作，將與擁有者確認的七態規格牴觸；若不處理，後續實作無所適從。 | 七態為準、九態退為參考（R-3，兩處文件更新之一已執行）；七態狀態機設計須另立並經批准（P-06），不得直接沿用九態設計改名。 |
| T-05 | DCL-15／16／17：無統一仲裁，各路徑直接 `dispatchGesture()`；雙擊第二下以延遲排隊且不可取消（K-07／K-62）。 | 衝突意圖可並行／重疊觸發；取消／中斷後待發點擊仍可執行；手勢狀態無法統一觀測。 | 依 `gesture-dispatch-state-machine.md` 的統一 Dispatcher 設計落地（設計已完成，實作**待批** P-07）；落地前不得宣稱已仲裁。最小範圍：先補 `onCancelled` 與雙擊第二下具名化＋`removeCallbacks`（PLAN D3-7 (b)，另排一步，見 `engineering-task-matrix.md` D3-7）。 |
| T-06 | DCL-09／11：臉遺失分支未清 `isBlinking`、`swipeStartPoint`、濾波歷史；臉重現無穩定門檻。 | 中斷前的眨眼／滑動意圖可在恢復後被新幀配對觸發；舊濾波歷史污染新座標。 | 臉遺失分支補清上述三者＋`filterX`／`filterY.reset()`；是否同時清 `hasValidCursor`（現況游標留原地變灰為有意顯示語義）**待確認**（P-08），不得擅改顯示語義。穩定門檻（連續幀數／dispersion）須量測校準後才定值，不得預設數字。 |
| T-07 | DCL-19／20：無音量鍵緊急停止；手點恢復繞過臉部／眼睛檢查（`resumeFromRecovery` → `attemptRecovery`）。 | 純眼控使用者在緊急情境下無眼控外即時停止手段（僅 Dashboard 對話框）；手點恢復可在臉部不在場時通過驗證。 | 保留觸控入口；音量鍵方案（是否全域攔截、裝置允許範圍、自訂方式）**待批**（P-09），須先確認系統允許範圍，不得假設全域攔截。手點恢復是否須與眼控 dwell 共用同一臉部／眼睛凍結語義，**待批**（P-09）。 |
| T-08 | DCL-07：50dp 換算未經實機量測；未讀 `densityDpi`／`xdpi`／`ydpi`；未處理 Insets／保護殼。 | 高密度裝置上帶寬物理意義改變（約為 v0.5.9 的數倍，見 `edge-width-device-measurement.md`），誤觸／漏觸未知即進入正式版。 | 按該文件 §5 量測協議實機量測（E-9／E-10）；量測前正式預設維持現況，不得宣稱 50dp 為最佳或已改善；量測後預設值**待批**（P-10）。 |
| T-09 | DCL-12：全部品質相關閾值未校準（影像降級門檻註明未校準、邊緣門檻 `UNCALIBRATED` 且 dispersion 不阻擋、校正等級門檻為經驗值）。 | 以未校準門檻作出的品質判斷（降級凍結、邊緣檢查、校正等級）可信度未知；dispersion「檢查函式先行、門檻不阻擋」易被誤讀為已檢查。 | 門檻校準量測（測試資料）後才定稿數值；現況文件與程式註解已明示未校準狀態，維持明示，不得以未驗證數字假裝已檢查。校準計畫與數據**待批**（P-10）。 |
| T-10 | DCL-24～46：智慧吸附／混合辨識／敏感辨識皆未實作，但現有 `VerifyFrames`／`GazeLog` 上傳管線（眼區裁切＋游標數字，預設開啟於側載版）與未來辨識資料的「不上傳／立即清除」要求無對應控制點。 | 未來若沿用現有管線實作辨識，辨識資料可能被上傳／留存，違反 DCL-41／43；現況亦易被誤讀為「已有影像辨識」。 | 本輪不實作（R-6）；未來實作須另立設計，辨識資料管線與診斷／監控管線分離，清除／降級／恢復序列一併設計並經批准（P-11）。不得以現有上傳管線冒充辨識管線。 |
| T-11 | DCL-01／08：ASK／ERROR／REPORT 的注視選擇在校正成功前以舊模型判讀；首次校正無舊模型時無法判讀（`CalibrationActivity.kt:507-515`、`519-564`）。 | 首次校正的詢問／重試頁純眼控路徑不存在；「校正成功前停用眼球座標操作」與「選擇頁注視判定」互相牴觸。 | 首次校正的詢問／重試一律走非眼控路徑（觸控／系統無障礙操作）並明示（第一輪 DCL-09 方向）；是否立項研究試探模型**待批**（P-02），不得用未校正視線做選擇判定。 |
| T-12 | DCL-10：邊緣「寬度」不可調、無最小／最大值；已實作者為開關。 | 使用者無法按姿勢／誤觸風險調整帶寬；左／右不對稱需求只能以開／關處理。 | 若要補寬度調整，須另立設計（粒度、範圍、預設、校準）並經批准（P-10）；本輪只對照，不得新增設定項。不得把開關說成寬度調整。 |
| T-13 | DCL-18：`onInterrupt()` 空實作；`pauseTracking()` 不清除待執行意圖。 | 系統中斷時無取消／清除；暫停後恢復可沿用舊 dwell／swipe 意圖。 | `onInterrupt` 走與 `onDestroy` 同級的取消／清除（設計**待批** P-07，不得擅改 `AccessibilityService` 生命週期語義）；`pauseTracking` 是否補清除，須與「暫停可無限停留、面板可重啟」的現況語義一併決定，**待批**。 |
| T-14 | DCL-23／42（相鄰風險）：停留／眨眼點擊無敏感欄位守衛，可落在密碼／付款欄位（DCL-42 本身只規範吸附，不直接涵蓋點擊）。 | 誤觸敏感欄位的風險未被任何一項 DCL 的程式落點覆蓋。 | 是否為點擊路徑增加敏感欄位守衛（須先有敏感判斷，即 DCL-44），**待批**（P-09）；在 DCL-44 落地前，不得宣稱敏感畫面已有保護。 |

## 6. 待批事項（需改規格者列為待批，不得擅改）

| # | 待批事項 | 來源 | 說明 |
|---|---|---|---|
| P-01 | ~~補 DCL-02、DCL-03、DCL-04 規格文字（或確認編號跳號為有意省略）~~ **已解決** | §4.1 | 擁有者已於 2026-11-23 補齊三項規格文字（皆選 A，已正式確認，非新增需求）。本文件已按新文字重新對照 DCL-02／03／04（見 §4.1），P-01 關閉，不得再就此三項提問。 |
| P-02 | D-4 剩餘決定（形式／秒數／位置／判定機制）與 DCL-01／08 的相容方式 | R-1、T-11 | `d4-decision-record.md` 23 項未決、已決定 0 項。決定須與「尚未校正時只能觸控／系統無障礙操作」相容；D1-9 維持只留位置。另：首次校正詢問／重試的非眼控路徑方向、是否研究試探模型，一併待批。 |
| P-03 | 重啟恢復的持久化格式與寫入時機（D-8），以及 DCL-05 語義的落地設計 | R-2、T-02 | `userStopped`／`inRecoveryMode` 是否持久化、新 prefs 檔或現有 key、損毀回退，皆待 D-8 決定；`restart-recovery-policy.md` 十步流程須按 DCL-05 改寫預設後再落地。 |
| P-04 | 校正品質儲存門檻（是否取 60／120／200、是否另立門檻） | T-03 | 門檻為經驗值、D-12 未決；在批准前不得以門檻擋存檔、不得擅改數值。 |
| P-05 | 眨眼點擊的個人化意圖校正方法、品質評估方法、FP／FN 評估協議與啟用門檻 | T-01 | 在方法與門檻批准前，不得啟用眨眼點擊為預設開啟（現況維持並明示不符合）。 |
| P-06 | DCL-06 七態相機狀態機設計（取代九態提案） | R-3、T-04 | 含狀態定義、錯誤代碼、session／generation 保護、紀錄欄位；九態文件僅供參考。 |
| P-07 | 統一手勢仲裁／Dispatcher 實作（含 `onCancelled`、雙擊取消、進行中鎖定、`onInterrupt` 處理） | T-05、T-13 | 設計文件已備妥（`gesture-dispatch-state-machine.md`）；實作排程（是否與推送同批）待批。 |
| P-08 | 臉遺失／恢復時的清除範圍（是否清 `hasValidCursor`）與穩定門檻量測值 | T-06 | 清除範圍須與「游標留原地變灰」的顯示語義一併決定；門檻值須量測後定。 |
| P-09 | 緊急停止的音量鍵方案、手點恢復的臉部／眼睛檢查、點擊路徑的敏感欄位守衛 | T-07、T-14 | 須先確認 Android／裝置允許範圍；敏感守衛以前置的敏感判斷（DCL-44）為前提。 |
| P-10 | 邊緣帶寬與品質門檻的實機量測計畫、正式預設值、寬度調整設計 | T-08、T-09、T-12 | 含 E-9／E-10 量測、dispersion／連續幀數／方向死區／降級門檻／校正等級門檻的校準數據；量測前不得定正式預設。 |
| P-11 | DCL-24～46 未來實作的整體設計（吸附／混合辨識／敏感辨識／清除／降級／恢復） | T-10、R-6 | 本輪不實作；未來任何一項實作皆須另立設計並經批准，不得沿用診斷／監控上傳管線。 |

## 7. 待定位清單（找不到來源，不得編造）

| 項 | 現況 |
|---|---|
| 保護殼對邊緣帶的影響（DCL-07） | 程式與文件中皆無任何考慮；無來源可對照。 |
| 裝置重啟後的平台綁定行為（DCL-05） | 靜態無法確認；`restart-recovery-policy.md` T-5 列為待實機驗證。 |
| 音量鍵全域攔截的裝置允許範圍（DCL-19） | 靜態無法確認；無程式落點可對照。 |
| 系統畫面實際可控範圍（DCL-23） | 靜態無反證亦無證據；須實機驗證。 |
| `AppFlowState.kt` 檔頭「影子模式」文字 | 已過時（服務已接線），文件漂移（見 `engineering-baseline.md` §8.1）；與本對照無關，僅記錄。 |

## 8. 方法與限制聲明

1. 本對照以靜態閱讀為唯一方法：逐檔 grep 關鍵字（`Arbiter`、`KeyEvent`、`FLAG_SECURE`、`MediaProjection`、`densityDpi`、`CameraBindingState`、`blinkEnabled`、`safeZone`、`OCR`、`AccessibilityNodeInfo` 等皆為零命中，見各項證據），並逐函式核對行為分支。未執行 Gradle 編譯、未執行單元測試、未走 CI、未實機驗證；行號以目前工作樹為準，若工作樹被其他工作者同時寫入，須重新快照核對。
2. 「未實作」的判定以「核心機制不存在」為準；相鄰機制（條件記憶、降級凍結、邊緣局部檢查、診斷上傳管線）已在各項中明示其非該功能，避免誤認。
3. 本文件未修改任何程式碼、版本號、workflow；未改動五個不能動（九點資料流、22 維特徵契約、`requestPauseForCalibration` 協議、`buildConfig=true`、`noCompress += "task"`）；未新增 DCL 題目、未問新功能選擇題。
4. 兩處小幅文件更新（只加註、不改原結論）已另行執行：`restart-recovery-policy.md` 頂部 DCL-05 覆蓋聲明；`camera-binding-observability.md` 頂部 DCL-06 七態為準、九態退為參考的聲明。

## 9. 2026-11-23 增補：光源連續模型輸入與多光源校正（review 樹，未合併）

> 本節為增補，不改寫上文 §1–§8 的歷史對照結論。§8.3 所寫「未改動五個
> 不能動」指的是 STEP 2 對照當時的工作範圍；下述變更為使用者事後明確
> 授權的契約變更，發生在本對照完成之後。

**授權與背景**：使用者 2026-11-23 19:31 選定方向「光源當模型輸入、多光源
校正」；19:32 補充五項硬性需求（光源連續變化、不得暫停／警告／固定值
不管、lux 為連續模型輸入、多光源校正跨光線範圍並明訂範圍外行為、執行期
每幀追蹤光線）。暫停／警告守衛方案已撤回。實機證據：同機同情境兩次校正，
靜止 86／297 px、行走光變中 342／658 px（見設計文件 §1）。

**變更內容**（已實作於本 review 樹、未推送、未發版、未實機驗證，設計與
規格變更提案全文見 `docs/light-robust-gaze-design.md`）：

1. 特徵契約 22 維 → 25 維（新增連續光特徵 f[22..24]：log-lux 主效應＋
   與雙眼平均虹膜位置的 2 個交互項）；`MODEL_VERSION` 2 → 3，舊模型
   強制重校。其餘四個不能動（九點資料流、`requestPauseForCalibration` 協議、
   `buildConfig=true`、`noCompress += "task"`）不動。
2. 校正流程：單輪 9 點 → 多輪 9 點，輪次間以注視選擇「繼續下一輪／完成／
   退出」；DCL-01（一次一目標、存檔前品質門檻、取消保留舊模型）維持。
   每筆樣本平行記錄 logLux 與輪次 id；等級取留一法與留一輪交叉驗證
   兩者較差者（門檻數值不動）。
3. `ConditionHistory` 的 lux 維度退出歷史修正（基準與執行期皆寫
   `luxBucket = -1`），避免與模型重複計算光線；歷史紀錄只代表非光線殘差。
   DCL-03 的稽核結論不受影響：本變更不改變任何上傳面（特徵向量從不上傳；
   診斷 CSV 欄位不變）。
4. DCL-05／DCL-13 的「不符合」判定（§4）不受本變更影響，仍以 STEP 3 後
   程式碼為準；本變更不宣稱修復該兩項。

**對照狀態**：本節所述實作未經 CI（推送批准後才跑）、未經實機驗證；
harness 純邏輯測試 162 項通過（含新增 20 項光特徵／留一輪測試），
僅證明純 Kotlin 數學契約，不證明建置與實機行為。
