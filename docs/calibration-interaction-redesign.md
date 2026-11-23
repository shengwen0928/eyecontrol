# EyeControl 校正前互動流程與 D-4 重新設計（第一輪分析 B 組）

> 2026-11-23 增補（review 樹，未合併／未推送）：特徵契約已由授權變更為 25 維（`MODEL_VERSION` 2→3；新增連續光特徵 f[22..24]，舊模型強制重校），校正流程改為多輪九點。詳見 `docs/light-robust-gaze-design.md`。本文件的提案寫於舊契約之下，其分析結論不追溯改寫；新工作依 v3 契約進行。

> 性質：只讀程式碼、只新增文件的分析與提案文件。**只提案、不定案；不改任何現有檔案、不改版本號、不碰 `.github/workflows/`、不推送、不 commit、不發版。**
> 基線：工作樹 `~/workspace/eyecontrol-p3`（無 `.git`，唯讀；遠端 main HEAD 為 `c6b80964`，v0.5.9 加上未推送的 Phase 3 實作）。本文件引用的程式行號一律以該樹為準。
> 先前文件複用：`~/workspace/eyecontrol-phase3/`（`PLAN.md`、`STATE_DESIGN.md`、`TEST_PLAN.md`、`IMPLEMENTATION_NOTES.md`、`CANCEL_CONTRACT.md`、`D4_OPTIONS.md`、`TEST_RESULTS.md`）與 `~/workspace/eyecontrol-master-audit/docs/phase2/`（`GAPS_AND_DECISIONS.md`、`USER_JOURNEY.md`、`STATE_DIAGRAMS.md`）。
> 誠實邊界：全部主張皆為靜態閱讀，未經實機驗證。找不到來源者標「待定位」，不得編造。凡寫「現況」皆指 p3 樹的程式碼事實；稽核文件描述的是 v0.5.7 樹，兩者有漂移處以 p3 樹為準，並在文中註明。
> 五個不能動維持：九點資料流、22 維特徵契約、 `requestPauseForCalibration` 協議、`buildConfig = true`、`noCompress += "task"`。禁假數據、禁假功能。
> 命名注意：本文件的「方案 A／B／C」指本輪任務 (d) 要求的三種新方案（非眼控選擇／分階段校正測試／預設流程加人工覆核），**不等於** `D4_OPTIONS.md` 的選項 A／B／C（角落常駐／確認式兩段／點間取消窗）。引用後者時一律寫全名 `D4_OPTIONS.md 選項 A` 等。

## 0. 摘要與閱讀地圖

- §1：D-4 原文查證。結論：**前提不符**——`GAPS_AND_DECISIONS.md` §3.2 的 D-4 原文沒有「A、B 兩個選項同時顯示再辨識注視」的內容（見 §1.2 逐字比對）。
- §2：校正前／校正中／校正後三種互動能力的重新定義，與三層校正前互動設計（觸控／Android 原生輔助操作相容性／外部實體開關），以及靜態檢查結果與限制。
- §3：現有校正演算法七步的檢查。關鍵事實已親自驗證：**校正採樣期確實沒有任何 `GazeModel.predict` 呼叫**，取樣期品質判斷只能是「樣本存在性＋睜眼＋settle＋幀數＋離群值剔除」，不能證明使用者正在注視目標。
- §4：本輪三種方案（A 非眼控選擇、B 分階段校正測試、C 預設流程加人工覆核）的比較。只提案、不定案。
- §5：校正重試與取消設計（非眼控重試／取消入口、世代 token 機制、快速重複測試情境）。
- §6：對照 `CANCEL_CONTRACT.md` 九條契約，逐條指出已覆蓋／部分覆蓋／未覆蓋處。
- §7：未決問題總表（編號 Q-D4-01…，答案一律未確認；決定記錄見 companion 文件 `d4-decision-record.md`，該文件只記錄未決問題與證據、不寫決定）。

## 1. D-4 原文查證：前提不符

### 1.1 逐字引用（`GAPS_AND_DECISIONS.md` §3.2）

§3.2 決策清單中 D-4 的整行原文如下（該文件第 260 行，表格原文，含標點）：

> `| D-4 | 校正取消目標的形式？ | 建議：注視角落 2 秒取消；或常駐小取消目標 | PG-03；R-02 |`

同一文件的 PG-03 修正方向與待決策句（第 66–67 行）是 D-4 的唯一上下文，逐字引用如下：

> 「**修正方向**：在校正頁增加眼控可達的「取消校正」目標（例如注視角落 2 秒取消——形式待 D-4 定稿），取消時明確告知「已取消，舊模型保留」；單點逾時 3 次後主動詢問是否退出；校正進度檢查點化（已完成點可續採）。取消目標須由校正頁自身實作，不得依賴已暫停的服務 overlay。」
>
> 「**待決策**：**D-4**（取消目標的形式與秒數）。」

`STATE_DIAGRAMS.md` R-02（第 348 行）與 CALIBRATION 行（第 140 行）對同一事項的表述亦只到「眼控取消目標注視達標」與「角落注視 2 秒取消目標（K-19 修正，秒數為修正建議，未驗證）」，同樣沒有 A/B 同時顯示的內容。`PLAN.md` §6 的 D-4 列（第 288 行）亦只寫「校正取消目標的形式與秒數」，`STATE_DESIGN.md` §2.1 `CALIBRATING` 列只寫「取消目標注視（D-4 待決，介面位置先行）」。

### 1.2 逐字比對：前提不符

任務轉述的前提稱：MASTER PROMPT §6.4 稱 D-4 原始設計含「A、B 兩個選項同時顯示再辨識注視」。

比對結果：**前提不符。**

| 前提要素 | GAPS 原文有無 | 證據 |
|---|---|---|
| 「A、B 兩個選項」 | 無。全文檢索 `A、B`、`兩個選項` 零命中；D-4 行只有「注視角落 2 秒取消；或常駐小取消目標」兩個以「；或」並列的**建議舉例**，不是兩個同時顯示的選項 | `GAPS_AND_DECISIONS.md:260` |
| 「同時顯示」 | 無。全文檢索 `同時顯示` 零命中；D-4 行與 PG-03 句皆未寫兩個目標同時出現 | 同上 |
| 「再辨識注視」／辨識使用者在看 A 還是 B | 無。D-4 行與 PG-03 句皆未提辨識、落點預測或分辨兩個注視對象 | 同上 |
| 「原始設計」含驗證目的 | 無。D-4 只問「形式？」，待決策只列「形式與秒數」；未寫要驗證什麼 | `GAPS_AND_DECISIONS.md:66-67, 260` |

補充查證限制：本輪在先前文件與 p3 樹中**未定位到 MASTER PROMPT §6.4 的原文**（待定位：未在 `~/workspace/eyecontrol-master-audit/`、`~/workspace/eyecontrol-phase3/`、`~/workspace/eyecontrol-p3/docs/` 中找到名為 MASTER PROMPT 或含 §6.4 的文件）。因此本節只能比對「任務轉述的前提」與「GAPS 原文」，不能斷言 MASTER PROMPT §6.4 的原文寫了什麼；前提不符的結論針對的是「GAPS 原文含有該內容」這一主張。

### 1.3 D-4 候選目的：逐一列為未確認問題，不得自答

以下為 D-4「原始設計要驗證什麼」的候選目的。本文件**不回答其中任何一項**，全部列為未決問題（編號與決定記錄文件共用）：

- **Q-D4-01**：D-4 的目的是否為「使用者能否選擇 A/B」？即：是否要驗證使用者能在兩個同時顯示的選項之間以注視做出選擇？（GAPS 原文無此內容；若此為目的，須先有獨立於 D-4 原文的授權文字。）
- **Q-D4-02**：D-4 的目的是否為「兩種校正條件的效果」？即：是否要比較兩種條件（例如兩種姿勢、兩種亮度、兩種目標佈局）下的校正結果？（GAPS 原文無「兩種條件」字樣；`ConditionHistory` 的條件概念見 §3.5，但與 D-4 行無文字連結。）
- **Q-D4-03**：D-4 的目的是否為「辨識準確度」？即：是否要量測系統分辨使用者注視位置的準確度？（GAPS 原文無「準確度」字樣；校正採樣期無落點預測，見 §3.2，辨識準確度在採樣期無法量測。）
- **Q-D4-04**：D-4 的目的是否為「流程能否完成」？即：是否只驗證校正流程（含取消出路）能走完、不困住使用者？（此目的與 PG-03「取消出路」及 D-1 六種情境的第 (3) 種「使用者取消，返回啟動流程」文字相容，但 GAPS 未明寫 D-4 的目的即為此，仍列未確認。）
- **Q-D4-05**：D-4 的目的是否為其他變因？例如取消目標的位置、秒數、誤觸率、取消耗時、與採樣目標的互斥性（`D4_OPTIONS.md` §6 列的量測項目）？（這些是 Phase 3 方案文件提出的量測項目，不是 GAPS D-4 原文的目的；是否採納為 D-4 目的，待確認。）
- **Q-D4-06**：D-4 的「2 秒」是否為定稿秒數、建議值，還是僅為舉例？（GAPS 行文為「建議：注視角落 2 秒取消」，`D4_OPTIONS.md` §0.2 已明言該句僅為未驗證、未定稿的舉例建議；本文件沿用此定性，不把 2 秒當作依據。）

後果：任何以「A、B 同時顯示再辨識注視」為前提的設計（例如同時顯示兩個注視選項、再以落點預測分辨使用者選了哪一個），在本輪皆**不得**以「D-4 原始設計」為依據提出；若要提出，須另立授權與前提，並直面 §3.2「採樣期無落點預測」的事實。§4 的三種方案因此全部不以該前提為基礎。

## 2. 三種互動能力的重新定義

### 2.1 定義總表

| 能力 | 時間範圍 | 互動手段（現況程式事實） | 關鍵限制 |
|---|---|---|---|
| 校正前互動 | 進入 `CalibrationActivity` 之前：`WelcomeActivity` → `SetupWizardActivity`（6 步）→ 啟動校正頁 | 只能手點（第一層）；Android 原生輔助操作相容性未驗證（第二層）；外部實體開關無程式支援（第三層）。詳見 §2.2 | 首次啟動時服務尚未建立，**沒有任何眼控手段**。服務開啟後的引導頁 dwell 可達性未驗證 |
| 校正中互動 | `CalibrationActivity` 內：`HANDOVER` → `SAMPLING`（9 點）→ `REPORT`／`ASK`／`ERROR` | `SAMPLING`：無按鈕、無輸入，全自動採樣，唯一退出是系統返回（手動）；`REPORT`／`ASK`／`ERROR`：真實 `MaterialButton`，手指可點，**有舊模型／剛訓練好的模型時**注視可觸發，無模型時注視無法判讀 | 採樣期無落點預測，任何同時顯示的第二注視目標都無法被分辨（見 §3.2）；服務 overlay 在校正期間已暫停，不得依賴 |
| 校正後互動 | 9 點完成且存檔成功之後：報告頁選擇 → `finish()` 回引導／主畫面，或 `去練習`，或 `重新校正` | 報告頁三鈕＋展開鈕（`showReportChoices()`，`CalibrationActivity.kt:806-837`），判定機制同校正中選擇階段；`重新校正`重跑 9 點（`recalibrate()`，`:839-858`），不清條件歷史、不覆蓋舊模型直到新模型存檔成功 | 報告頁的模型是剛訓練好的，可判讀；但「完成」即 `finish()`，報告不留存於任何可重看的頁面（PG-11 的常駐報告缺口在 p3 樹中僅以「報告頁不立即 finish」部分回應，關閉後仍無重看入口——待定位：全樹未見報告存檔／重看入口） |

流程順序現況（p3 樹，已與稽核文件漂移）：`SetupWizardActivity.kt:20-25` 註解與 `STEP_CALIBRATION = 3`、`STEP_SERVICE = 4`（`:448-451`）明載 D-1 定稿順序——先校正、後啟用服務。稽核文件描述的 v0.5.7 順序（服務在校正之前）已不在 p3 樹。`USER_JOURNEY.md` 旅程 2 的步驟編號仍按舊樹，引用時須以 p3 樹為準。

### 2.2 校正前互動設計：三層

#### 第一層：一般觸控

現況事實：

- `WelcomeActivity.kt:28-36` 只有兩個 `setOnClickListener`；`SetupWizardActivity.kt:96-98` 三顆按鈕（主鈕／跳過／重新指引）只有 `setOnClickListener`，另有 `btnWizardBack`（`:97`）。全部為一般觸控操作，無任何 dwell／注視觸發程式碼。
- 點擊範圍：歡迎頁與引導頁按鈕皆為 `match_parent` 寬、`@dimen/ec_button_height`＝52dp 高（`activity_welcome.xml:210-231`、`activity_setup_wizard.xml:66-107`、`dimens.xml:14`）。52dp 高符合常見的最小觸控目標討論範圍，但本文件不宣稱符合某一無障礙標準（未驗證量測標準的適用版本，待定位）。
- 按鈕語意：歡迎頁兩鈕有具體 `contentDescription`（`activity_welcome.xml:216,227`）；引導頁主鈕的 `contentDescription` 在 layout 中寫死為「繼續」（`activity_setup_wizard.xml:73`），而其 `text` 由程式按步驟改為「開始設定／我知道了，繼續／允許相機權限／開啟系統設定／開始校正／開始使用」等（`SetupWizardActivity.kt:refreshStep()`，`:191-395`）。靜態推斷：TalkBack 優先朗讀 `contentDescription` 時，使用者聽到的是固定不變的「繼續」，而非當下步驟的動作語義——此為靜態檢查發現的語意遮蔽疑慮，**實際朗讀行為未驗證**（見 §2.3 限制）。
- 系統權限對話框與系統無障礙設定頁不在 App 控制內：`SetupWizardActivity.kt:148-153`（`STEP_SERVICE` 分支）以 `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)` 跳系統頁，`openAppSettings()`（`:415-423`）跳 App 設定頁；App 內無從控制這些系統頁的操作方式。

設計要求（提案，非定案）：第一層維持現況觸控語義，不得為眼控化而刪除觸控入口；主鈕的 `contentDescription` 須隨步驟文字同步（或移除寫死的通用描述，改由 `text` 承載語意），此為文字／屬性層面的修正提案，實作前須以實機 TalkBack 驗證現況行為確為遮蔽。

#### 第二層：Android 原生輔助操作相容性（Voice Access／Switch Access／TalkBack）

現況事實（全樹靜態檢索）：

- `Voice Access`、`Switch Access`、`TalkBack`、`SpeechRecognizer`、`RECORD_AUDIO` 在 p3 樹程式碼與 `AndroidManifest.xml` 中**零命中**（待定位：未檢索到任何相關 API 呼叫、權限宣告或設定項）。`AndroidManifest.xml` 宣告的權限只有 `CAMERA`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CAMERA`、`POST_NOTIFICATIONS`、`INTERNET`、`REQUEST_INSTALL_PACKAGES`，無錄音權限。
- 無障礙服務宣告（`res/xml/accessibility_service_config.xml`）為本 App 自身的 `GazeAccessibilityService` 配置（`canPerformGestures=true`、`canRetrieveWindowContent=true`、`accessibilityEventTypes=typeAllMask`、`accessibilityFeedbackType=feedbackGeneric`），不是對 Voice Access／Switch Access／TalkBack 的相容宣告。
- 原生相容性的唯一現成基礎是標準 Android 控制項語義：`MaterialButton`／`MaterialCardView`／`ProgressBar`／`TextView` 皆為平台標準 View，未見 `importantForAccessibility="no"`（全樹 layout 檢索零命中，與稽核文件 K-38 描述的 v0.5.7 殘留不同，已漂移）；`contentDescription` 覆蓋見 §2.3 表。Switch Access 與 Voice Access 若已在系統開啟，理論上可透過平台無障礙節點操作標準按鈕——此為平台機制的一般推斷，**本 App 的實際可操作性未驗證**，不得宣稱相容。

設計要求（提案，非定案）：

1. **TalkBack**：補齊全部互動控制項的可讀標籤（見 §2.3 缺口表），移除寫死的通用 `contentDescription` 遮蔽；`CalibrationView` 的 Canvas 繪製文字（目標序號、提示、報告行）須另有可被無障礙服務讀取的等效節點或 `contentDescription` 動態文字，否則 TalkBack 使用者完全讀不到校正進度與報告（現況：`CalibrationView` 為純繪製 `View`，無 `contentDescription`、無無障礙委派，見 `CalibrationActivity.kt:877-1125`）。
2. **Switch Access**：不得在 App 內另寫一套開關掃描；相容性只要求「全部校正前／校正後操作皆可經由標準可聚焦控制項完成」，即：每一步都有真實 `Button`（非僅 Canvas 繪製目標），焦點順序符合視覺順序（見 §2.3），且操作完成後焦點／狀態可被系統讀到。校正頁 `SAMPLING` 階段現況無任何真實控制項，Switch Access 在該階段無操作對象——此缺口與 §5 的非眼控取消入口提案直接相關。
3. **Voice Access**：僅在使用者明確啟用的前提下研究，不在本輪提案範圍內作任何預設。現況無語音功能，任何語音方案都須另立授權、權限與隱私評估。

#### 第三層：外部實體開關評估

現況事實：全樹未見任何外部開關、藍牙／USB HID、按鍵事件（`onKeyDown`／`KeyEvent`）或 `switch` 裝置 API（檢索零命中，待定位：未找到相關程式碼）。`CalibrationActivity`、`SetupWizardActivity`、`WelcomeActivity` 皆無 `onBackPressed`／`onKeyDown` 覆寫（檢索零命中）；系統返回行為為預設 Activity 行為。

評估結論（只評估，不定案）：

- 外部實體開關在本 App 內目前**沒有任何程式支撐點**；若未來要支援，唯一不發明新機制的路徑是經由 Android 平台的 Switch Access（系統層把開關事件轉為焦點／選擇操作），App 側只需滿足第二層的「標準控制項＋焦點順序」要求。任何 App 內直連藍牙開關的方案都屬新增功能，須另立需求、權限與測試計畫，本文件不提案。
- 評估限制：本輪無實體開關裝置、無 Switch Access 實機環境，無法驗證平台轉換後的實際操作路徑；以上評估僅為靜態架構推斷。

#### 語音：僅為明確啟用的選項，不得預設持續收音

- 現況：無錄音權限、無語音辨識程式碼、無持續收音機制。本文件**不提案**任何語音功能；若未來有人提案語音作為校正前／校正中入口，必須同時滿足：(a) 使用者明確啟用（預設關閉）；(b) 只在啟用期間收音，啟用狀態可見、可隨時關閉；(c) 不把語音當作純眼控使用者的預設出路（語音能力與眼控能力是不同假設，不得互相代替）；(d) 錄音資料的留存／上傳承諾與現有隱私揭露一致。以上四點為約束條件，不是功能設計。

### 2.3 靜態檢查：校正前頁面（Welcome／SetupWizard／Calibration）

檢查方法：只讀 layout XML 與 Activity 程式碼，不執行、不實機。**明示限制：靜態檢查無法驗證 TalkBack 實際行為**——焦點順序的實際朗讀順序、 `contentDescription` 與 `text` 的優先關係、Canvas 內容的朗讀結果、Switch Access 的掃描順序、Voice Access 的標籤編號，皆須實機以 TalkBack／Switch Access／Voice Access 逐項驗證後才能斷言。本節表格中的「推斷」欄一律標為靜態推斷，不得讀作實測結論。

| 檢查項 | WelcomeActivity | SetupWizardActivity | CalibrationActivity |
|---|---|---|---|
| 版面來源 | `activity_welcome.xml`（239 行，標準 View） | `activity_setup_wizard.xml`（107 行，標準 View） | **無 layout XML**：程式化 `FrameLayout`＋`CalibrationView`（Canvas 繪製）＋`choiceBar`（`LinearLayout`，平時 `GONE`），`CalibrationActivity.kt:174-193` |
| 控制項能否被系統輔助操作找到 | 兩顆 `MaterialButton` 為標準控制項，有 `contentDescription`；裝飾圖示 `ImageView` 有 `contentDescription="凝視標誌"`（`:26`），TalkBack 會朗讀裝飾圖示（靜態推斷：裝飾元素宜標為不重要，現況未標，待實機確認是否造成噪音） | 四顆 `MaterialButton` 為標準控制項，皆有 `contentDescription`；狀態卡 `TextView` 無標籤但為純文字（平台預設可讀）；完成頁 `ClickableSpan` 行（`doneSummaryText()`，`:370-400`）為文字內連結，TalkBack／Switch Access 的可達性**未驗證、待定位** | `SAMPLING` 階段：**零真實控制項**，`CalibrationView` 無 `contentDescription`、無焦點屬性，系統輔助操作找不到任何操作對象；`REPORT`／`ASK`／`ERROR` 階段：`choiceBar` 內的 `MaterialButton` 為真實控制項，`contentDescription = choice.label`（`:608`），可被找到（靜態推斷） |
| TalkBack 焦點順序 | 宣告順序＝視覺順序（標誌→標題→使命句→三步說明→隱私卡→兩鈕→提示行）；未見 `nextFocus*`／`accessibilityTraversal*` 屬性（全樹檢索零命中） | 宣告順序＝視覺順序（步驟標籤→進度條→標題→說明→狀態卡→主鈕→跳過→重新指引→上一步）；`GONE` 的按鈕（跳過／重新指引／上一步按步驟隱藏，`refreshStep()`）靜態推斷會被 TalkBack 跳過；未見顯式焦點順序屬性 | 無可供排序的控制項（採樣期）；選擇期按鈕為垂直 `LinearLayout` 宣告順序，即 `showChoices()` 的 `choices` 順序（報告頁：重新校正／去練習／完成／展開，`:806-837`）；Canvas 內容無焦點 |
| 按鈕語意／可讀標籤 | 具體（「開始設定」／「先看看主畫面，之後可在設定頁重新執行引導」）；`text` 與 `contentDescription` 一致 | **疑慮**：主鈕 `contentDescription` 寫死「繼續」（`:73`），`text` 隨步驟變化，靜態推斷存在語意遮蔽（見 §2.2 第一層）；其餘三鈕描述具體 | 選擇鈕 `contentDescription = label`，與 `text` 一致（`:608`）；採樣期目標點、進度文字、提示文字皆為 Canvas 繪製，**無任何可讀標籤** |
| 點擊範圍 | 兩鈕 `match_parent` × 52dp | 四鈕 `match_parent` × 52dp | 採樣期：Canvas 目標點無觸控處理（`CalibrationView` 無 `onTouchEvent`，全檔檢索零命中），觸控無法操作採樣；選擇期按鈕 `match_parent` × 52dp（`:617-625`） |
| 操作完成後是否正確回到校正流程 | 「開始設定」→ `SetupWizardActivity` 並 `finish()`（`WelcomeActivity.kt:34-38`）；「先看看」寫 `welcome_seen` 後進主畫面（`:39-43`），引導須經設定頁找回（語義為延期，非回到校正流程） | 主鈕按步驟推進；`STEP_CALIBRATION` 未校正時以 `launchCalibration` 啟動校正頁（`:66-71`），返回時 `onResume()`／`refreshStep()` 重查 `CalibrationStore.exists()` 才算數（`:139-142, 191`）；已校正時主鈕直接下一步；跳過只在曾往返後出現（`returnedFromCalibration`／`returnedFromSettings` 旗標） | 選擇期「完成」→ `finish()`（`:818`）；「去練習」→ 開訓練頁後 `finish()`（`goToTraining()`，`:860-863`）；「重新校正」→ `recalibrate()` 回 `SAMPLING`（`:839-858`）；「退出校正」／「退出」→ `exitCalibration()`（`:501-504`）`finish()`，服務接回由 `onDestroy()` 完成（`:865-874`）。採樣期無 App 內返回路徑，僅系統返回（預設行為，**未驗證**實際返回後的引導重查是否正確——靜態推斷：`onPause()` 停引擎、`onDestroy()` 接回服務，與 `exitCalibration()` 路徑相同，但無 Toast 告知） |
| 取消能否安全退出 | 「先看看主畫面」為延期出口，非取消；系統返回行為未驗證 | 「上一步」可逐級返回（`onBack()`，`:182-190`），步驟 0 按返回即 `finish()` 且不寫 `welcome_seen`；跳過機制誠實記「已跳過」不記完成（`doneItemState()`，`:355-361`）；無「放棄引導並保留進度」的暫停概念（稽核 PG-06 相關，p3 樹仍只存 `savedInstanceState`，`:129-137`） | **採樣期：無 App 內取消入口**（僅 `CANCEL_TARGET_RESERVED` 預留常數，`:1117-1123`，零引用、無繪製、無事件）；`ASK`／`ERROR` 期有「退出校正」／「退出」鈕（`:444-476`）；`REPORT` 期無取消鈕，「完成」為正常結束。取消後的安全性見 §5–§6 |

### 2.4 校正前互動的誠實告知缺口（靜態檢查附帶發現）

- 歡迎頁三步說明列「相機／服務／校正」，而引導實際為 6 步（`SetupWizardActivity.kt:191` 寫「共 6 步」；layout 預設文字仍寫「第 1 步，共 5 步」，`activity_setup_wizard.xml:14`，執行期被覆寫，殘留不一致）。此為靜態發現的文字不一致，是否造成使用者困惑須實機／使用者測試確認，本文件不宣稱影響程度。
- 引導步驟 0–2（歡迎／須知／相機）在服務開啟前完成，現況文字未說明「這幾步須手動或他人協助」。稽核 PG-01 的修正方向已要求此告知，p3 樹引導文字（`STEP_WELCOME`／`STEP_NOTICE` 分支）仍未見該句——靜態檢查確認缺口仍在。此告知屬文字提案，見 §7 Q-D4-12。

## 3. 校正演算法七步檢查：取樣期品質判斷是否真的能運作

### 3.1 七步與程式對照

任務所列七步，逐一對照 p3 樹（`CalibrationActivity.kt`，除註明外行號皆為該檔）：

| 步驟 | 程式位置 | 現況行為 |
|---|---|---|
| 1. 目標顯示 | `buildPoints()` `:345-349`（3×3 網格，x／y 取 0.08／0.5／0.92 倍寬高）；`startPoint()` `:351-360`（`view.showTarget(x, y, 0f, "N / 9")`）；`CalibrationView.onDraw()` `:1005-1060`（呼吸內圈＋脈衝光暈＋60dp 進度環＋提示文字） | 同一時刻只顯示一個採樣目標（`CalibrationView` 單一 `tx/ty`）。此為全部取樣期假設的基礎 |
| 2. 使用者注視 | 無程式碼。檔案頭註解（`:28-30`）明載指示「頭保持不動、只用眼睛看」，畫面提示文字同義（`onDraw` 提示行） | **無法驗證**：程式沒有任何手段確認使用者正在注視，更無法確認注視的是顯示的目標 |
| 3. 取樣開始 | `startPoint()` `:351-360`：清 `pointBuffer`／`pointDeviceAngles`，`settleUntil = now + SETTLE_MS`（900ms，companion `:1095`），`pointStartedAt = now` | 取樣開始不等於「使用者已看向目標」；settle 只是固定等待，不量測視線是否已穩定 |
| 4. 有效樣本累積 | `onSamplingSample()` `:379-424`：`sample == null` → 臉遺失提示／逾時檢查；眨眼／閉眼幀（`leftOpen`／`rightOpen < blinkThreshold`，預設 0.08，`GazeSettings.kt:135`）丟棄；`now < settleUntil` 丟棄；其餘幀 `pointBuffer.add(sample.features)`，進度＝`size / SAMPLES_PER_POINT`（20 幀，companion `:1096`） | 「有效」的定義＝「有臉＋雙眼睜開＋已過 settle」。無其他有效性檢查（見 §3.2） |
| 5. 樣本品質判斷 | `commitPoint()` `:657-678`（見 §3.2）；其餘無 | **只有事後離群值剔除，沒有取樣期品質判斷**。無逐幀品質分數、無穩定度門檻、無逐點品質 verdict（見 §3.3） |
| 6. 目標完成 | `onSamplingSample()` `:416-420`：`pointBuffer.size >= 20` → `commitPoint(target)` | 完成條件純為幀數，與注視正確性無關 |
| 7. 下一目標 | `onSamplingSample()` `:417-420`：未滿 9 點 → `startPoint(pointIndex + 1)`；滿 9 點 → `finishCalibration()`（`:680`）→ `GazeModel.train()`（`GazeModel.kt:172-...`）→ `CalibrationStore.saveModel()`（`CalibrationStore.kt:79`）→ `showReport()`（`:765`） | 九點資料流：`allFeatures`／`allTargets`／`allDeviceAngles` 記憶體串列，`finishCalibrationInternal()` `:695-763` 一次訓練存檔。此資料流為紅線「不能動」，本文件不提案改動 |

### 3.2 關鍵事實驗證：採樣期無 `GazeModel.predict`

**已親自驗證，結論為真。** 全檔檢索 `CalibrationActivity.kt` 中 `predict` 的命中只有兩處，皆不在採樣路徑：

1. `choiceCursor()` `:566-580`：`model.predict(sample.features, w, h, devicePitch, deviceRoll, gyroCompensation, distanceCompensation)`，僅供 `REPORT`／`ASK`／`ERROR` 三個選擇階段的注視判定（`onChoiceSample()` `:519-564` 經 `onSample()` 階段分派 `:367-377` 進入）。其模型來源為 `choiceModel`：報告頁為剛訓練好的模型（`showReport()` `:769`），詢問／錯誤頁為已存檔舊模型（`enterAsk()` `:446`、`enterError()` `:480` 的 `CalibrationStore.loadModel()`）。
2. 間接引用：`GazeEvaluator.meanError()`（`GazeEvaluator.kt:103-114`）內的 `model.predict()`，只在九點全部完成後的 `finishCalibrationInternal()`（`:711-713` 的 `evaluate()` 與 `leaveOnePointOut()`）中呼叫，屬事後評估，不是取樣期判斷。

`onSamplingSample()`（`:379-424`）全函式無 `predict`、無 `choiceModel`、無 `GazeModel` 引用；`onSample()` 的 `Phase.SAMPLING` 分支（`:376`）只進 `onSamplingSample()`。`D4_OPTIONS.md` §1.3 的前提（「校正頁現況沒有落點預測」）在 p3 樹中**仍然成立**，且因 Phase 3 新增了選擇階段而更須精確表述為：「**採樣期**無落點預測；選擇期有，但須有可用模型。」

附帶驗證：`gazeHintLine()`（`:507-515`）與 `onChoiceSample()` 的 `choiceModel == null` 分支（`:520-523`）明載「沒有可用模型時注視選擇無法判讀」，`IMPLEMENTATION_NOTES.md` §1 第 14 項亦確認此後果。首次校正（無舊模型）的 `ASK`／`ERROR` 頁注視選擇無法判讀，只能手指點按或系統返回——此為現況事實，不是提案。

### 3.3 取樣期「品質判斷」實際上是什麼：五項既有機制與其邊界

| 既有機制 | 程式位置 | 能判斷什麼 | 不能判斷什麼 |
|---|---|---|---|
| 臉存在性（二元） | `FaceTrackerEngine.kt:139-147`：無臉 → `onSample(null)`；`extract()` 回 null（`GazeFeatureExtractor.kt:74,84,94` 的 `lW/rW/iod` 守衛）→ 同樣 `onSample(null)`（`FaceTrackerEngine.kt:162-164`） | 有／無臉部 landmark。`extract()` 的 null 原因（眼寬過小／iod 過小／landmark 不足）與「無臉」在校正頁被合併為同一個 `null` 樣本，**無法區分** | 臉是否正對螢幕、是否睜眼注視、landmark 品質好壞 |
| 睜眼程度閾值 | `onSamplingSample()` `:397-400`：`leftOpen`／`rightOpen < blinkThreshold` 丟棄；`GazeFeatureExtractor.kt:147-148` 的 `leftOpen/rightOpen` 為眼高／眼寬比 | 雙眼是否睜開到閾值以上（預設 0.08）。閉眼／眨眼幀不污染訓練集 | 單眼／雙眼的個別狀態（任一眼低於閾值即整幀丟棄，語義為保守丟棄，不是「雙眼皆睜開」的精確判定）；睜眼是否等於注視 |
| Settle 時間窗 | `SETTLE_MS = 900L`（`:1095`），`onSamplingSample()` `:403` 的 `now < settleUntil` 丟棄 | 目標切換後固定等待 0.9 秒，排除視線移動初期的幀 | 視線是否已穩定、是否已到達目標；900ms 的充分性未經量測驗證（待定位：未見 settle 充分性的實機量測） |
| 幀數計數 | `SAMPLES_PER_POINT = 20`（`:1096`），`:416` 的 `>= 20` 完成條件 | 收滿 20 幀「有效」幀（定義見 §3.1 步驟 4）。幀率未在程式中量測／保證，20 幀對應的 wall-clock 時間取決於實際幀率，**不得換算為秒數宣稱** | 20 幀是否來自同一注視位置、是否含漂移、是否含使用者掃視殘留 |
| 離群值剔除（事後） | `commitPoint()` `:657-678`：以虹膜特徵前 4 維（`f[0..3]`，見 `GazeFeatureExtractor.kt:150-153` 的特徵順序）的樣本中心為基準，保留距離最近的 `KEEP_RATIO = 0.7`（70%，`:1100`），`keep` 至少 1 幀 | 每點事後丟棄最遠的 30% 樣本，減輕飄移／殘留眨眼幀的影響；特徵與裝置角度成對保留（`:667-676`） | 注視正確性：若多數幀皆為錯誤注視（如使用者全程看別處），中心本身即被拉偏，剔除保留的仍是錯誤樣本；`D4_OPTIONS.md` §1.3 已明言此點，本文件複核程式碼後確認成立。剔除比例固定 70%，無逐點品質 verdict、無「樣本不足重採該點品質」的分支（不足的唯一觸發是 10 秒逾時，見 §3.4） |

**結論：取樣期不存在「樣本品質判斷」作為獨立步驟。** 任務所列七步中的第 5 步，在現況程式中只有 `commitPoint()` 的事後剔除可對應，且其語義是「內部一致性篩選」，不是「注視目標正確性判斷」。任何把 `commitPoint()` 描述為「確認使用者看對目標」的說法皆為假功能宣稱，本文件明確否定。

### 3.4 其他現況守衛（非品質判斷，但影響取樣行為）

- 單點逾時：`POINT_TIMEOUT_MS = 10_000L`（`:1098`），`checkPointTimeout()`（`:431-442`）：逾時即重採該點，3 次（`POINT_TIMEOUTS_BEFORE_ASK = 3`，`:1103`）後進 `ASK`。逾時檢查只在 `sample == null` 幀與眨眼丟棄幀上執行（`:390, 399` 的 `checkPointTimeout(now)`），settle 期內的有效幀不觸發逾時檢查——靜態推斷：持續有臉但 settle 未過的狀態下逾時計時仍以 `pointStartedAt` 起算，10 秒一到仍會在下一個 null／眨眼幀或後續幀觸發，行為細節待實機確認，**不得宣稱為已驗證**。
- 臉遺失提示：連續 1 秒無有效樣本（`FACE_LOST_HINT_MS = 1000L`，`:1097`）顯示「找不到臉，請把臉移回畫面中央」（`:384-391`）。提示為二元（有／無），無方向／距離漸進指引（稽核 PG-09，p3 樹仍無預覽／臉框，待定位）。
- 條件樣本收集：`calibrationLuxSamples`／`calibrationGlassesSamples`（`:89-90`）在有效幀收集 `ambientLux`／`specularRatio`（`:394-396`，僅有限值），只在 `finishCalibrationInternal()`（`:735-747`）用於算校正基準（亮度桶／眼鏡提示），**不參與取樣期品質判斷**。`EyeImageMetrics.isDegraded()`（`EyeImageMetrics.kt:44-50`，亮度／對比／反光啟發式門檻，檔頭自述「未經實機校準」）只用於驗證影格觸發（`FaceTrackerEngine.kt:322-324`），不閘控採樣。
- 頭部姿態：特徵 7（pitchRot）／8（yawRot）／9（roll）每幀皆在特徵向量中（`GazeFeatureExtractor.kt:150-153`），`devicePitch`／`deviceRoll` 每幀附加（`FaceTrackerEngine.kt:171-172`），但採樣期無任何姿態穩定度檢查；姿態只在訓練時求平均基準（`GazeModel.train()` 的 `basePitch/baseYaw`，`GazeModel.kt:196-201`）與完成時求裝置基準（`finishCalibrationInternal()` `:698-701`）。
- Landmark 有效性：`FaceLandmarkerOptions` 建置（`FaceTrackerEngine.kt:131-188`）未設定任何信心閾值；結果回呼只取 `faceLandmarks().firstOrNull()`（`:139`），MediaPipe 信心值**未被讀取**（全樹檢索信心值讀取零命中，與 `STATE_DESIGN.md` §4.4 一致）。`extract()` 的守衛（眼寬／iod 下限 `1e-6`）是幾何退化守衛，不是品質量測。

### 3.5 可驗證的替代方案（提案）與其限制與誤差

以下七項替代／補強方案皆為**提案**，未實作、未驗證。每項只用現成量測（`GazeSample` 欄位、`GazeFeatureExtractor` 特徵、`EyeImageMetrics` 度量、感測器快照），不引入新感測器、不改 22 維特徵契約、不改九點資料流。**每一項都不得聲稱能證明使用者正在注視目標**——在單一目標顯示＋無落點預測的前提下，全部替代方案的上限都是「樣本內部一致性＋採集條件可信度」，不是「注視正確性」。

| # | 替代方案 | 可驗證的量測來源（現成） | 限制與誤差（必須同時說明） |
|---|---|---|---|
| S-1 | 取樣時間窗顯式化：記錄每點的 settle 起訖、有效幀時間戳序列、實際幀間隔，報告頁／診斷紀錄只呈現「收滿 20 幀所用時間、settle 後首幀延遲」等可量測事實，不換算為注視品質 | `settleUntil`、`pointStartedAt`、`sample.timestampMs`（`GazeSample`，`GazeFeatureExtractor.kt:26-35`） | 時間窗只能證明「等了多久、收了多少幀」，不能證明等待期間視線位置；幀率波動會使同一幀數對應不同時長，跨裝置比較須以時間戳為準，不得以幀數冒充時間 |
| S-2 | 臉部／眼睛特徵品質閘門：逐幀檢查特徵有限性（全部 22 維 `isFinite`）、`leftOpen/rightOpen` 雙眼分別記錄、`iod`（特徵 6）有限且大於退化下限；任一不通過即該幀不計入，並計數不通過原因（原因分類須與 `extract()` 的 null 原因區分） | `sample.features`、`leftOpen/rightOpen`、`f[6]`（`GazeFeatureExtractor.kt:150-153`）；現況 `onSamplingSample()` 未對 `features` 做有限性檢查（`:409` 直接 `add`），此為靜態發現的缺口 | 有限性只能排除數值退化，不能排除「數值有限但注視錯誤」；`leftOpen/rightOpen` 為幾何比值，受眼鏡／眼皮個體差異影響，閾值 0.08 的普適性未驗證；原因分類須實作後以單元測試覆蓋，不得以分類數量冒充品質分數 |
| S-3 | Landmark 有效性顯式化：把 `extract()` 回 null 的原因（landmark 不足／眼寬退化／iod 退化）與「無臉」區分回傳／記錄，校正頁據此顯示不同提示（例如「看不到臉」vs「眼睛量測不穩定」）；現況兩者合併為 `null` 樣本（`FaceTrackerEngine.kt:147,164`） | `FaceTrackerEngine.kt:139-164` 的結果回呼分支；`GazeFeatureExtractor.extract()` 的三處 `return null`（`:74,84,94`） | 原因區分只能改善提示準確性，不能提高注視正確性；landmark 存在不代表 landmark 準確，MediaPipe 無信心值可讀（見 §3.4），任何「landmark 品質分數」皆為假數據，禁止引入 |
| S-4 | 頭部姿態穩定度：以 settle 期＋採樣期的特徵 7／8（pitchRot／yawRot）與裝置 pitch／roll 序列，計算範圍（max−min）或與 settle 期均值的最大偏離，超過未定門檻即標記該點「姿態不穩定」（只標記、不自動判定注視錯誤）；門檻一律待實機量測校準，目前無數字 | 特徵 7／8、`sample.devicePitch/deviceRoll`（`GazeSample`）；`GazeModel.predict()` 的姿態補償語義（`GazeModel.kt:60-68`）僅供參考，不參與採樣判斷 | 姿態穩定不代表注視穩定（頭不動、眼球可動）；姿態不穩定也不代表注視錯誤（頭動時眼球可補償注視同一目標）；門檻未量測前不得寫入任何數字；此方案與「頭保持不動」的指示存在張力——指示本身即假設頭不動，穩定度量測只能檢驗指示被遵守的程度，不能證明注視 |
| S-5 | 離群值排除顯式化（現況 `commitPoint()` 的延伸）：保留現況 70% 剔除不動，新增可驗證的呈現——每點保留幀數（現況為 14 幀，20×0.7，算式非量測）、剔除幀的中心距離分佈摘要（只呈現，不評分）；不得以剔除比例冒充品質保證 | `commitPoint()` `:657-678` 的排序與 `keep` 計算 | 見 §3.3：中心拉偏時剔除失效；固定 70% 在樣本乾淨時丟棄好樣本、在樣本髒時保留髒樣本；分佈摘要的解讀須有實機對照，否則只是數字陳列 |
| S-6 | 每目標最低有效樣本數與不足重試：明定「每點須收滿 20 幀有效幀（定義見 §3.1 步驟 4）才算完成，否則只走現況 10 秒逾時重採」為顯式規則，並在 `ASK` 頁誠實呈現「已收 N／20 幀」的事實（現況 `ASK` 頁只說重採次數，`enterAsk()` `:444-470` 未顯示已收幀數）；不足重試的觸發條件只取現況逾時機制，不新增自動重試 | `pointBuffer.size`、`SAMPLES_PER_POINT`、`pointTimeoutCount`（`:83,112`） | 幀數是採集完整性指標，不是注視正確性指標；「已收 N 幀」不得被讀作「已正確注視 N 幀」；重採次數上限（3 次後詢問）為現況規則，不得以「重試」之名無限重採（PG-03 的無限重採缺口由 `ASK` 機制回應，p3 樹已實作） |
| S-7 | 條件一致性記錄（非閘門）：每點記錄 `ambientLux`／`specularRatio`／`eyeMeanLum`／`eyeContrast` 的均值與範圍，僅供報告頁／診斷紀錄呈現「本次校正期間條件是否劇變」，不閘控採樣、不評分 | `GazeSample` 的 `ambientLux/eyeMeanLum/eyeContrast/specularRatio`（`GazeFeatureExtractor.kt:26-35`）；現況只收集 lux／specular（`:394-396`），`eyeMeanLum/eyeContrast` 未在校正頁收集 | 條件劇變與注視錯誤無必然關係（亮度變化時使用者仍可正確注視）；`isDegraded()` 門檻未經實機校準（`EyeImageMetrics.kt` 檔頭自述），不得以其作為閘門；呈現時須標明「條件記錄，非品質評分」 |

共同限制（適用全部七項）：(1) 單一目標假設——全部方案都建立在「畫面上只有一個目標，settle 後視線即在該目標上」的假設上，該假設本身不可驗證，任何第二注視對象的出現都會使假設失效（見 §1.3 與 `D4_OPTIONS.md` §1.3）；(2) 無 ground truth——校正期間沒有獨立於特徵的注視位置真值，全部內部一致性指標都不能外推為螢幕座標誤差，螢幕誤差只能在九點完成後以 `GazeEvaluator` 的訓練集／留一法誤差事後估計（`GazeEvaluator.kt:36-102`），且訓練集誤差偏樂觀（該檔檔頭自述）；(3) 門檻未校準——任何新門檻在實機量測前皆為「待校準」，不得寫入程式當作已驗證值（沿用 `PLAN.md` §0.3）。

## 4. D-4 三種方案比較（只提案、不定案）

本節三種方案皆以 §1.2 的結論為前提：**不假定 D-4 原本要驗證 A/B 注視選擇**。方案 B（分階段校正測試）特別受此約束（見 §4.2 末段明示）。

### 4.1 方案 A：非眼控選擇

**流程（提案）：**

1. 進入校正頁**之前**（引導頁 `STEP_CALIBRATION` 或主畫面「重新校正」入口），由使用者以觸控、或由協助者代為操作、或經第二層原生輔助操作（TalkBack／Switch Access，須實機驗證可達性），選擇本次校正的「退出／重試策略」——例如：是否顯示非眼控取消入口、重試時從第 1 點重來或續採（現況重建後誠實重採，見 §5.1）、逾時詢問的預設傾向。選擇結果在進入 `SAMPLING` 前即確定並顯示，採樣期間不再出現新的選擇。
2. 採樣期間（`SAMPLING`）：不新增任何注視選擇目標，維持現況單一目標假設；非眼控取消入口（見 §5.2）以真實控制項形式存在（觸控／系統輔助操作可達），不參與注視判定、不污染 `pointBuffer`。
3. 採樣結束後（`REPORT`／`ASK`／`ERROR`）：沿用現況真實按鈕（手指可點；有模型時注視可觸發），不新增選擇。

**優點：** 不觸碰採樣期無落點預測的硬限制，互斥問題不存在；選擇的判定機制是平台標準控制項，無需新 dwell 規則；與現況選擇階段的真實按鈕機制一致（`showChoices()`）。

**缺點：** 純眼控使用者在採樣期間**仍然無法獨立操作選擇**——選擇發生在採樣前，且須觸控／協助者／原生輔助操作；若使用者在採樣中途改變主意（例如想取消），須依賴 §5.2 的非眼控入口，而非注視選擇。本方案不得被描述為「眼控選擇方案」，其誠實名稱即為「非眼控選擇」。

**安全風險：** (a) 採樣前選擇被誤觸／誤選（協助者代選與使用者意圖不一致），且採樣期間無法以眼睛更正；(b) 非眼控入口若以 Canvas 繪製而非真實控制項，會重蹈「看得到、點不到／輔助操作找不到」的覆轍，必須以真實 `View` 實作（提案約束）；(c) 選擇結果若被持久化為「預設策略」，可能在使用者不知情下影響後續校正回合——提案要求選擇結果僅作用於當次進入，不得寫入持久化設定（待確認，見 §7 Q-D4-14）。

**開發／測試成本：** 低（相對）。主要工作為引導／主畫面入口的選擇 UI（標準控制項）＋選擇結果傳遞（`Intent extra` 模式，沿用 `EXTRA_PROFILE_ID`，`CalibrationActivity.kt:1101`）＋實機驗證 TalkBack／Switch Access 可達性。無新 dwell 規則、無新狀態機分支（`Phase` 不變）。測試須含：無模型／有模型、服務已開／未開、觸控／TalkBack 兩種操作路徑的回歸；不得以單元測試代替實機輔助操作驗證。

**與現況的衝突：** (a) 與 D-1「校正優先」順序無衝突（選擇發生在校正前，不改變順序）；(b) 與 `STATE_DESIGN.md` §2.1 `CALIBRATING` 列的「取消目標注視（D-4 待決）」存在**語義衝突**：若 D-4 定稿採本方案，該列的「注視」字樣須修正為「非眼控入口」，否則狀態機描述與實作不符——此衝突須在 D-4 定稿後以文件修訂解決，本文件不擅自改寫狀態機；(c) 與 PG-03「眼控可達的取消目標」修正方向存在張力：本方案的取消入口不是眼控可達的，是否滿足 D-1 六種情境第 (3) 種「使用者取消」的要求，須由 D-4 決定確認（見 §7 Q-D4-08），本文件不代答。

### 4.2 方案 B：分階段校正測試

**流程（提案）：**

1. 第一階段（試探）：只採部分點（點數與哪些點待決定，見 §7 Q-D4-09；提案約束：不得少於 2 個不同目標點，否則 `leaveOnePointOut()` 回 `NaN`，`GazeEvaluator.kt:83-84`），訓練一個**臨時、僅記憶體、不存檔**的試探模型。
2. 第二階段（量測）：以試探模型預測其餘未參與訓練的點的樣本（或同一批樣本的留出點），計算內部一致性數字（留一法誤差的階段版本，沿用 `GazeEvaluator.leaveOnePointOut()` 的分組邏輯，不得新編評估公式）。
3. 第三階段（決定點）：呈現階段量測結果（數字＋「僅供參考」說明），由使用者／協助者決定繼續完成全部 9 點、重做試探階段，或退出。**決定本身經由非眼控入口或現況選擇階段按鈕作出，不以注視選擇 A/B 作出。**
4. 完整 9 點完成後，仍走現況 `finishCalibrationInternal()` 一次訓練存檔；試探模型**永遠不得**寫入 `CalibrationStore`、不得覆蓋舊模型、不得寫入條件歷史（沿用 `CANCEL_CONTRACT.md` 第 5、6 條的禁止語義，見 §6）。

**優點：** 在不宣稱注視正確性的前提下，提供「早期發現採集異常」的量測機會（例如試探階段內部誤差極大時，使用者可及早重做而非等 9 點跑完）；全部量測皆用現成評估函式，無新公式；試探結果不污染正式模型（若嚴守不存檔約束）。

**缺點：** (a) 階段劃分改變九點資料流的採集節奏（`startPoint`／`commitPoint` 的連續性被階段邊界打斷），`allFeatures`／`allTargets` 的累積語義須重新定義，否則階段樣本與正式樣本混淆；(b) 子集訓練的幾何覆蓋不足（例如只採 3 點時模型外推誤差大），階段數字與完整 9 點數字**不可直接比較**，呈現時須明確標示階段與點數，否則誤導；(c) 增加使用者等待與決策負擔，試探階段本身也可能被誤解為「已校正」——呈現文字須嚴守「試探、未完成、未存檔」的誠實表述；(d) 點數／點位選擇本身待決定（Q-D4-09），未決前無法估算耗時。

**安全風險：** (a) **最大風險：試探模型被誤當作正式模型使用。** 若試探模型的存檔／載入路徑與正式模型共用同一 `CalibrationStore` key，服務可能載入未完成的模型並據此操作游標——提案以「試探模型只存記憶體變數、與 `CalibrationStore` 零接觸」為硬約束，違反即為假功能；(b) 階段決定點若以注視目標呈現，會重蹈採樣期分辨難題——提案禁止此做法，決定點只出現在採樣暫停的階段邊界，且以真實按鈕呈現；(c) 使用者可能把階段數字讀作精度保證——呈現文字與 `GazeEvaluator` 檔頭的「訓練集誤差偏樂觀」說明須同步呈現。

**開發／測試成本：** 中高。須新增階段狀態（`Phase` 擴充或階段旗標，`Phase` 列舉現況為 5 態，`:71`）、試探訓練／評估呼叫點、階段邊界的樣本隔離（`pointBuffer`／`allFeatures` 的階段歸屬）、不存檔約束的靜態檢查（`saveModel()` 呼叫點審查）。測試須含：試探模型零存檔的斷言（`CalibrationStore` 內容前後一致）、階段樣本不混入正式訓練集的斷言、子集點數邊界（2 點／1 點時 `leaveOnePointOut()` 的 `NaN` 處理，不得顯示假數字）。

**與現況的衝突：** (a) 與紅線「九點資料流不能動」存在**直接張力**：分階段即改動資料流的採集與訓練時序，是否屬於「動九點資料流」，須由 D-4／所有權人確認（見 §7 Q-D4-10），本文件不代為定性；(b) 與 `PLAN.md` D1-7「9 點完成後顯示報告頁」的語義衝突：階段報告與正式報告為兩種報告，文字與狀態機須區分，否則使用者無法分辨「試探報告」與「正式報告」；(c) 與 `STATE_DESIGN.md` §2.1 的 `CALIBRATING` 離開條件（「9 點完成且存檔成功 → `CALIBRATED`」）衝突：階段邊界不是離開條件，狀態機須新增階段內轉換或維持 `CALIBRATING` 不變——維持不變為提案約束（階段不改變流程態）。

**明示（本方案的核心限制，不得省略）：** 方案 B **只能比較分階段測量結果，不能宣稱使用者具備 A/B 注視選擇能力**。分階段量測比較的是「不同階段／不同點子集的內部一致性數字」，不是「使用者在 A、B 之間做選擇的能力」；階段決定點的選擇動作本身不是注視選擇（見流程第 3 步）。任何把方案 B 描述為「A/B 測試」或「使用者可注視選擇 A 或 B」的說法，皆與 §1.2 的查證結論與 §3.2 的採樣期事實相牴觸，本文件明確禁止此種描述。

### 4.3 方案 C：預設流程加人工覆核

**流程（提案）：**

1. 預設流程：維持現況 9 點流程**不變**（settle／20 幀／70% 剔除／逾時詢問皆不動），9 點完成後進入現況報告頁（`showReport()`，`:765`）。
2. 人工覆核：報告頁新增顯式覆核步驟——覆核者（使用者本人以觸控／原生輔助操作，或協助者）須在報告頁確認「接受本次校正」或「重新校正」，確認前**不** `finish()`、不回引導／主畫面、不啟用服務（現況報告頁「完成」鈕即 `finish()`，`:818`，覆核即在此處插入確認語義，提案約束：確認動作不得僅為注視——報告頁注視判定雖有模型可用，但覆核的嚴肅性要求明確、可撤回的確認，注視誤觸的代價為接受一次錯誤校正，故提案要求覆核確認以真實按鈕點按為準，注視觸發僅為輔助且須經二次確認，二次確認的形式待決定）。
3. 覆核資訊：報告頁呈現現況既有數字（訓練集誤差、留一法誤差、最差點、逐點誤差、校正時間，`reportLines()`／`perPointLines()`，`:779-803`），加註「訓練集誤差偏樂觀」「門檻為經驗值、不是精度保證」（現況報告行已有，`:788-790`），**不新增**任何新數字。單點重做不在本方案範圍內（現況 `recalibrate()` 為整輪重做，`:839-858`；單點重做的提案見 §5.1 的未決問題，不與本方案綁定）。
4. 覆核結果：接受 → 沿用現況結束路徑（模型已存檔，`finish()`）；拒絕 → `recalibrate()` 整輪重做（不清條件歷史、不覆蓋舊模型直到新模型存檔成功，現況語義）；覆核者亦可選擇退出（`exitCalibration()` 語義，舊模型保留）。

**優點：** 改動最小（報告頁確認語義＋文字），不動採樣流程、不動資料流、不新增注視判定；覆核者看到的是真實事後數字，無新假數據風險；與現況報告頁機制（真實按鈕＋模型判讀）天然相容。

**缺點：** (a) 人工覆核**不能驗證注視正確性**：覆核者看到的留一法誤差是內部交叉驗證數字，不是獨立真值，覆核只能發現「數字異常大」等粗糙異常，不能發現「使用者全程看錯位置但內部一致」的系統性錯誤——此限制須在覆核文字中明示，不得以「人工覆核」之名暗示已驗證正確；(b) 增加一步確認，使用者負擔與誤解風險（覆核者可能不理解數字含義而盲目接受）；(c) 若覆核者為協助者，存在代使用者接受的代理風險，接受決定與使用者意圖可能不一致。

**安全風險：** (a) 覆核疲勞：多次重校後覆核者傾向一律接受，覆核流於形式——提案要求覆核頁不得預設勾選、不得以倒數自動接受（現況無自動接受，本方案禁止新增）；(b) 報告頁常駐但關閉後無重看入口（見 §2.1），覆核一旦接受即無法回看報告——覆核文字須提醒「接受後報告即關閉」，或另立報告留存提案（不在本方案範圍，見 §7 Q-D4-15）；(c) 覆核確認若與「完成」鈕語義混淆（同一鈕既是覆核接受又是結束），使用者可能誤以為「完成」只是關閉報告——提案要求覆核鈕文字與「完成」區分（文字待定，不得自創術語，沿用現況用詞）。

**開發／測試成本：** 低。主要工作為報告頁確認語義（按鈕文字／狀態分支）＋覆核文字，狀態機 `Phase.REPORT` 不變。測試須含：未覆核時不得 `finish()` 的斷言、拒絕後整輪重做的斷言（舊模型與條件歷史不變，直到新存檔）、覆核文字與數字一致性的檢查。無新 dwell 規則（覆核確認沿用現況選擇判定，僅加二次確認形式待定）。

**與現況的衝突：** (a) 與 `PLAN.md` D1-7「按『完成』才 `finish()`」無結構衝突，僅語義收窄（「完成」＝「覆核接受」）；(b) 與 D-1 六種情境第 (1) 種「成功顯示結果並進入下一步」相容，但「下一步」被覆核步驟延後一步——引導頁的等待語義（`launchCalibration` 無結果碼、回來重查存檔）不受影響，因模型在覆核前已存檔（`finishCalibrationInternal()` 先存檔、後顯示報告，`:724-763`），覆核拒絕並不撤銷已存檔模型——此為現況時序事實，覆核「拒絕」的語義因此只能是「重做一次」，不能是「撤銷本次存檔」，提案文字須誠實反映此點（見 §7 Q-D4-11）；(c) 與 `STATE_DESIGN.md` §2.1 `CALIBRATED` 進入條件（「存檔成功」）無衝突：覆核期間流程態已為 `CALIBRATED`，覆核不是流程態轉換，狀態機不變。

### 4.4 三方案對照表

| 維度 | A：非眼控選擇 | B：分階段校正測試 | C：預設流程加人工覆核 |
|---|---|---|---|
| 選擇／決定的操作方式 | 觸控／協助者／原生輔助操作，採樣前確定 | 階段邊界真實按鈕，非注視選擇 | 報告頁真實按鈕覆核確認，注視僅輔助（須二次確認，形式待定） |
| 採樣期是否新增注視目標 | 否 | 否（階段邊界才出現決定點） | 否 |
| 能否解決純眼控使用者採樣中途退出 | 否（須 §5.2 非眼控入口或他人協助） | 否 | 否（覆核在採樣結束後） |
| 量測／數字來源 | 無新數字 | 現成 `GazeEvaluator` 階段數字，須標示階段／點數 | 現成報告數字，無新數字 |
| 是否動九點資料流 | 否 | **有張力**（階段劃分改動採集／訓練時序，見 §4.2） | 否 |
| 是否動狀態機 | `CALIBRATING` 取消分支文字須修（若定稿） | 階段不改流程態（提案約束），但樣本隔離須新狀態 | 否（`REPORT` 語義收窄） |
| 誤觸／誤接受風險 | 採樣前誤選、採樣期無法更正 | 試探模型誤用為正式模型（硬約束防範）；階段數字誤讀為精度 | 覆核疲勞、盲目接受；拒絕不撤銷已存檔（時序事實） |
| 開發／測試成本 | 低 | 中高 | 低 |
| 與 D-4 原文的關係 | 回應「形式？」的一種答案（非眼控形式），但與 PG-03「眼控可達」方向有張力（見 §4.1） | 回應「兩種校正條件的效果？」候選目的（Q-D4-02）的一種量測手段，**不是** A/B 注視選擇 | 回應「流程能否完成？」候選目的（Q-D4-04）的一種把關手段 |
| 定案狀態 | **未定案** | **未定案** | **未定案** |

**共同聲明：** 三種方案皆為提案，皆未定案，皆不得在 D-4 決定前實作。D-4 決定須回答 §7 的全部相關問題（形式、秒數、位置、判定機制、覆核語義等），缺一即不得動手。

## 5. 校正重試與取消設計

### 5.1 現況重試入口盤點（程式事實）

| 入口 | 位置 | 觸發方式 | 語義 |
|---|---|---|---|
| 單點逾時重採（自動） | `checkPointTimeout()` `:431-442` | 單點 10 秒未滿 → 自動 `startPoint(pointIndex)` 重採；3 次後進 `ASK` | 非使用者發起的重試；當前點 `pointBuffer` 丟棄（`startPoint()` 清緩衝），已 `commitPoint` 的點保留在 `allFeatures` 中 |
| `ASK`「繼續重採」 | `enterAsk()` `:444-470` | 真實按鈕（手指可點；有舊模型時注視可觸發，無模型時注視無法判讀） | 計數歸零、回 `SAMPLING`、重採當前點 |
| `ERROR`「重試」 | `enterError()` `:477-498` | 同上 | 重試動作由呼叫端傳入：交接重試（`requestCameraHandover()`）或重新存檔（`finishCalibration()`）；`errorRetry` 欄位 `:153` |
| 報告頁「重新校正」 | `showReportChoices()` `:806-837` → `recalibrate()` `:839-858` | 同上（報告頁模型為剛訓練好的，可判讀） | **整輪重做**：清全部訓練串列與條件樣本、`committedPoints` 歸零、回第 1 點；不清條件歷史、不覆蓋舊模型直到新存檔成功 |
| 系統返回／離開頁面 | 預設 Activity 行為，無覆寫 | 手動系統返回／手勢返回 | `onPause()` 停引擎、`onDestroy()` 接回服務；未完成資料丟棄（記憶體串列隨實例銷毀），無任何存檔寫入 |

**缺口（靜態確認）：** (1) 採樣期無使用者發起的重試入口（只有自動逾時重採）；(2) 無單點重做入口——「重做某一點」只能整輪重來或等該點逾時重採，PG-11「允許重做單一失敗點」在 p3 樹中未實作（待定位：全樹未見單點重做函式）；(3) 首次校正無模型時，`ASK`／`ERROR` 的注視重試無法判讀，重試入口實質上只剩觸控（見 §3.2）。

### 5.2 非眼控重試入口與明確取消入口（提案）

提案約束（全部為約束條件，不是實作細節）：

1. **非眼控重試入口**：以真實 `View`（`MaterialButton` 或等效標準控制項）呈現，不得以 Canvas 繪製目標冒充按鈕；入口在 `SAMPLING` 階段亦須存在（現況 `choiceBar` 在採樣期為 `GONE`，`:168-169`），但其**呈現位置不得與九點目標重疊**（九點網格覆蓋四角／四邊中點／中心，見 `buildPoints()`），且不得參與注視判定（觸控／系統輔助操作專用，注視經過時不累積任何計時）。入口的觸發語義為「重採當前點」（`startPoint(pointIndex)` 語義，丟棄當前點 `pointBuffer`，已提交點保留），不得觸發整輪重做（整輪重做保留給報告頁）。
2. **明確取消入口**：單一入口，收斂三條既有退出路徑——`ASK`「退出校正」（`:463-465`）、`ERROR`「退出」（`:491-493`）、D-4 定稿的取消目標（未實作，僅預留）——為同一函式（沿用 `exitCalibration()` `:501-504` 的語義為收斂目標，或明確取代它，不得並存兩套結束語義；此為 `CANCEL_CONTRACT.md` 第 8 條第 6 點的要求，見 §6）。取消入口在五個 `Phase`（`HANDOVER`／`SAMPLING`／`REPORT`／`ASK`／`ERROR`）皆須可達（`HANDOVER` 期取消的語義見 §6 第 2、3 條）；`REPORT` 期的「取消」語義為「放棄查看報告、返回」，不得撤銷已存檔模型（契約第 6 條第 5 點）。
3. **入口可見性與誠實告知**：取消入口須以可見文字標示其作用（例如「取消校正」），不得以無標示的角落熱區充當；觸發後須告知「已取消」與舊模型狀態（現況 `exitCalibration()` 的 Toast 為「已退出校正，舊模型保留」，無模型時此句不誠實，見 §6 第 7 條——提案要求告知文字按有／無舊模型分支，文字待 D-13 術語定稿後統一）。
4. **不得依賴的手段**：取消／重試入口不得依賴已暫停的服務 overlay、面板、把手、邊緣手勢或 `DwellTracker`（校正期間服務已暫停，`requestPauseForCalibration` 回呼後面板 `GONE`，`GazeAccessibilityService.kt:1678-1684`）；不得依賴舊模型預測（首次校正無模型，見 §0.3 契約全域約束）。

### 5.3 取消後的處置：停止取樣／釋放相機／清暫存／不覆蓋有效模型／不污染下一輪

處置清單（提案，逐項對照契約見 §6）：

1. **停止取樣**：取消認領後，`onSample()` 不再接受任何樣本（現況 `isFinishing` 守衛 `:368` 為唯一閘門，提案要求新增顯式終端認領旗標，見 §5.4），`startPoint()`／`commitPoint()`／`finishCalibration()` 不得再被呼叫（同一回合不可恢復）。
2. **釋放相機**：只經 `FaceTrackerEngine.stop()`（`FaceTrackerEngine.kt:506`），經現況生命週期路徑（`onPause()` `:248-257` → `onDestroy()` `:865-874`）完成，不得直接操作 CameraX、不得繞過 `unbindOnMainThread()`（`:554`）。取消入口本身不直接停引擎（現況 `exitCalibration()` 亦不直接停引擎，依賴 `finish()` 觸發的生命週期序列——提案維持此模式，但須以測試驗證取消後、銷毀前的窗口期內無新樣本被接受，見 §5.5）。
3. **清暫存**：清除範圍不得小於 `recalibrate()` 的清除清單（`:839-852`：`allFeatures`／`allTargets`／`allDeviceAngles`／`pointBuffer`／`pointDeviceAngles`／`calibrationLuxSamples`／`calibrationGlassesSamples`／`committedPoints`／`pointTimeoutCount`／`report`／`reportExpanded`），另加 `filterX/filterY` 狀態重置、選擇階段暫存（`currentChoices`／`currentButtons`／停留狀態／`choiceModel` 引用）與 `errorRetry` 清空。清除為「丟棄」，不得寫入任何持久化位置（見契約第 4 條）。
4. **不覆蓋有效模型**：取消路徑不得呼叫 `CalibrationStore.saveModel()`（`:79`）、`setActiveProfileId()`（`:50`）或任何對 `calibration_profiles` 的寫入；不得呼叫 `ConditionHistory.clearHistory()`（`:229`，行號以 p3 樹 `ConditionHistory.kt` 為準，待複核——本文件引用契約所載行號，未逐一重驗 `ConditionHistory.kt` 行號，標待定位）／`saveReference()`／`addRecord()`。唯一合法寫入路徑仍為 `finishCalibrationInternal()` 成功時（`:724,736,737`）。
5. **不污染下一輪**：下一輪（重新進入校正頁的新實例，或同一頁內的 `recalibrate()`）須從第 1 點開始，舊回合的條件樣本不得影響新回合基準；`onSaveInstanceState()` 的檢查點（`:259-264`，只存 `pointIndex`／`committedPoints` 整數）須在取消時失效（現況 `onCreate()` `:217-224` 重建後誠實重採、不續採殘缺訓練集，此語義須保留）。

### 5.4 世代 token 機制（提案）：`calibrationSessionId`／generation token

**現況查證：`calibrationSessionId` 不存在。** 全樹檢索 `calibrationSessionId`／`cancelGeneration` 零命中。現況僅有的三種世代／會話計數，皆**不是**校正回合 token，不得冒充：

| 現況計數 | 位置 | 作用範圍 | 能否充當校正回合 token |
|---|---|---|---|
| `handoverGeneration` | `CalibrationActivity.kt:107`，遞增於 `requestCameraHandover()` `:276`，比對於 `onHandoverReleased()` `:287` 與逾時 runnable `:304` | 僅相機交接請求的世代 | 否：只覆蓋交接等待期，不覆蓋採樣／報告／詢問／錯誤期；取消時不遞增 |
| 幀槽 `generation` | `FaceTrackerEngine.kt:65,277,301-305,390-393,517` | 僅 Bitmap 幀槽的有效性（防錯幀量測） | 否：引擎內部機制，校正頁不可見、不可控 |
| `VerifyFrames.sessionId` | `VerifyFrames.kt:56,127,141-144,182,254-261` | 僅驗證影格的上傳會話 | 否：診斷機制，與校正回合無關 |

**提案機制（未實作，須 D-4 定稿後設計）：**

1. 新增校正回合世代計數（名稱待實作時定，本文件以 `calibrationGeneration` 稱之，**不是**既有變數名）：在每一回合開始時遞增——回合開始的定義待決定（候選：`onCreate()` 進入、`startPoint(0)` 首次呼叫、`recalibrate()`，見 §7 Q-D4-13），取消認領時亦遞增（使全部進行中的非同步等待失效）。
2. 全部非同步邊界後重查世代與終端認領旗標：交接回呼（`onHandoverReleased()`）、逾時 runnable（`armHandoverTimeout()`）、樣本回呼（`runOnUiThread { onSample(sample) }`，`:202-204`）、選擇按鈕回呼（`showChoices()` 的 `setOnClickListener`，`:626-628`）、重試動作（`errorRetry` 執行前）。檢查點沿用現況 `handoverGeneration` 模式（比對＋`isFinishing` 檢查），不得只寫「要注意競態」。
3. 單一終端認領旗標（主執行緒專用布林，名稱待定）與世代計數為**同一機制**的兩面：認領旗標解決「取消與完成只發生其一」（先到先得、敗者無副作用），世代計數解決「晚到回呼失效」；兩者不得各立一套旗標（契約第 8、9 條要求同一旗標同時解決競態與冪等）。
4. 主執行緒序列化不變量：取消入口、完成入口、交接回呼、逾時 runnable、選擇回呼全部在主執行緒執行與判定（現況引擎回呼經 `runOnUiThread` 回主執行緒，`:202-204`），不得新增背景執行緒讀寫 `phase`／旗標／世代。
5. 世代 token 的可觀測性：世代值本身不顯示給使用者、不寫入診斷紀錄當作數據（避免假數據欄位）；測試以行為斷言（晚到回呼不啟動引擎、不進入 `ERROR`、不重新發起交接），不以世代值斷言。

### 5.5 快速重複開始／取消／重開始的測試情境（提案清單，未執行）

以下情境皆為未來實作後須覆蓋的測試情境，本文件只列出，不宣稱已通過。情境編號供 `TEST_PLAN.md` 補充時引用（現況 `TEST_PLAN.md` §3 的 C-3 只列「校正中途注視取消目標」一項，快速重複情境未覆蓋）：

| 編號 | 情境 | 預期行為（提案，須實作後驗證） |
|---|---|---|
| T-C1 | `HANDOVER` 期（交接回呼未到）觸發取消 | 不啟動引擎、不進入 `ERROR`；晚到回呼失效；服務接回恰一次 |
| T-C2 | `SAMPLING` 中途（某點採集中）觸發取消 | 不再累積 `pointBuffer`、不 `commitPoint`、不存檔；舊模型與條件歷史不變 |
| T-C3 | 第 9 點第 20 幀收滿的同一時刻觸發取消 | 完成與取消只發生其一：或存檔成功進報告頁、或取消且舊模型保留，不得出現部分存檔 |
| T-C4 | `REPORT` 期觸發取消 | 只放棄查看報告，已存檔新模型不被刪除／改寫，條件歷史不恢復 |
| T-C5 | `ASK` 期觸發取消（與「退出校正」鈕同時可見時先後觸發兩條退出路徑） | 單一入口、單一 Toast、單一返回；第二觸發無副作用 |
| T-C6 | `ERROR` 期重試待執行時觸發取消 | `errorRetry` 失效，重試不執行（不重新交接、不重新存檔） |
| T-C7 | 連續快速觸發取消兩次（同一目標重複觸發） | 冪等：只執行一次副作用、只顯示一次告知、服務只接回一次、不閃退 |
| T-C8 | 取消 → 立即重新進入校正 → 再次取消 | 第二輪從第 1 點開始，舊回合殘留不混入新回合；世代 token 區分兩輪 |
| T-C9 | 取消 → 立即重新進入校正 → 完成 9 點 | 新模型存檔成功，舊回合的部分樣本未參與訓練；條件基準以新回合為準 |
| T-C10 | 取消後旋轉螢幕／觸發 Activity 重建 | 重建後不顯示舊回合已採點數、不從中斷點續採；重建實例的檢查點已失效 |
| T-C11 | 取消後經 `onPause()` → `onDestroy()` 序列 | 引擎只釋放一次等效、`resumeAfterCalibration()` 恰一次、服務追蹤正常恢復（服務已開時）或維持未啟用（服務未開時） |
| T-C12 | 首次校正（無模型、服務未啟用）觸發取消 | 只憑校正頁自身結束機制安全返回，不滯留、不閃退、不顯示錯誤；`exists()` 仍為假 |
| T-C13 | 交接逾時 runnable 即將到時觸發取消 | 逾時 runnable 不觸發 `ERROR`、不重新發起交接 |
| T-C14 | 單點逾時將至／選擇停留將滿時觸發取消 | 不重採、不進入詢問、不觸發選擇動作 |

## 6. 對照 `CANCEL_CONTRACT.md` 九條契約：逐條覆蓋分析

對照基線：契約文件以 p3 樹為準撰寫，其引用的 `CalibrationActivity.kt` 行號與本輪實測行號**大部分一致**（`onSample()` `:367`、`startPoint()` `:351`、`commitPoint()` `:657`、`finishCalibration()` `:680`、`exitCalibration()` `:501`、`onDestroy()` `:865`、`handoverGeneration` `:107`、`cancelHandoverTimeout()` `:317`、`resetChoiceDwell()` `:647`、`errorRetry` `:153`、`recalibrate()` `:839` 皆吻合），僅服務端行號有漂移（契約稱 `resumeAfterCalibration()` 在 `GazeAccessibilityService.kt:1236`，實測為 `:1688`；`requestPauseForCalibration` 實測為 `:1648`）。以下逐條結論針對「契約要求」與「p3 樹現況程式碼」的關係：**契約本身是未來實作的約束，不是現況描述**——「已覆蓋」指現況已有機制滿足該條（通常經由生命週期路徑），「未覆蓋」指現況無此機制、須未來實作補齊。由於 D-4 未定稿、取消目標未實作，全部九條的「取消入口」部分皆為未實作，表中據實標示。

| 契約條 | 要求摘要 | 現況覆蓋結論 | 證據與缺口 |
|---|---|---|---|
| 第 1 條：終止目前校正工作 | 取消後立即、不可逆地終止全部進行中工作；先進終端狀態、再釋放資源；五階段皆接受取消 | **部分覆蓋（經由生命週期，無顯式終端狀態）** | 已有：`onSample()` `:368` 的 `isFinishing` 守衛＋`Phase` 分派（`:367-377`），`finish()` 後晚到樣本被擋；`exitCalibration()` `:501-504` 以 `finish()` 結束。缺口：無顯式終端認領旗標、無終端 `Phase`；`startPoint()`／`commitPoint()`／`finishCalibration()` 入口無認領檢查；「先進終端狀態、再釋放」順序現況為「`finish()` → `onPause()`／`onDestroy()` 釋放」，終端狀態的建立依賴 `isFinishing` 的隱式語義，未以具名狀態表達；取消入口本身不存在，`HANDOVER`／`REPORT` 期的取消皆未實作 |
| 第 2 條：停止並釋放相機資源 | 只經 `FaceTrackerEngine.stop()` 釋放，順序與現況生命週期一致；取消後不重啟引擎；交接期取消不啟動引擎 | **部分覆蓋（經由生命週期）** | 已有：`onPause()` `:248-253`（`engineStarted` 守衛＋`engine.stop()`）、`onDestroy()` `:865-871`（`::engine.isInitialized` 檢查＋`stop()`）、`onResume()` `:237` 的 `isFinishing \|\| engineStarted` 守衛、`startEngineSafely()` `:326-341`。缺口：取消入口本身不直接停引擎，取消後、銷毀前的窗口期內引擎仍在運行（依賴 `isFinishing` 擋樣本，而非停引擎）；交接期取消無專用路徑（`requestCameraHandover()` 無取消 API，晚到回呼靠 `isFinishing` 檢查 `:292` 擋下，世代檢查 `:287` 亦存在，但「取消時遞增世代」未實作） |
| 第 3 條：停止臉部追蹤與非同步工作 | 晚到樣本／選擇停留失效；交接逾時 runnable 移除；單點逾時不推進；不誤停服務／診斷紀錄 | **部分覆蓋** | 已有：`onPause()` `:255-256` 的 `cancelHandoverTimeout()`＋`resetChoiceDwell()` 組合；`onSample()` 閘門擋晚到樣本；選擇回呼 `isFinishing` 守衛（`:627`）。缺口：`errorRetry` 在退出路徑上**未被清空**（`exitCalibration()` 只 `Toast`＋`finish()`，無 `errorRetry = null`）；取消時無顯式「清除重試引用」步驟，依賴實例銷毀丟棄；單點逾時計時只在 `onSamplingSample()` 內檢查（`:431`），終端閘門擋樣本即停止推進，此部分與契約一致，但無顯式取消路徑驗證 |
| 第 4 條：清除未完成暫存資料 | 丟棄全部記憶體暫存（訓練串列／緩衝／條件樣本／進度／選擇暫存／報告／重試），`committedPoints` 歸零，檢查點失效，清除範圍不小於 `recalibrate()` 清單 | **未覆蓋（取消路徑）／已有範本（重跑路徑）** | 已有範本：`recalibrate()` `:839-852` 的清除清單。缺口：`exitCalibration()` **不清除任何欄位**；取消後的清除完全依賴 Activity 實例銷毀（新實例欄位為空），銷毀前的晚到回呼窗口期內殘留仍可被讀到（契約第 4 條第 4 點明言「頁面銷毀即丟失」不得作為不清除的理由）；`onSaveInstanceState()` 檢查點（`:259-264`）在取消時無失效步驟（現況 `onCreate()` 重建後誠實重採，`:217-224`，但此為重建語義，非取消清除） |
| 第 5 條：不覆蓋有效校正資料 | 取消後已存檔模型／中繼資料／情境 id 皆不變，不得呼叫任何寫入函式 | **已覆蓋（空缺性滿足）** | 已有：`exitCalibration()` `:501-504` 只 `Toast`＋`finish()`，無任何 `saveModel()`／`setActiveProfileId()`／遷移寫入呼叫；`finishCalibrationInternal()` 內的唯一 `saveModel()` 呼叫點（`:724`）不在退出路徑上。注意：此為「現況退出路徑恰好不寫入」的空缺性滿足，不是顯式守衛；未來取消入口須以靜態檢查維持此性質（契約完成判準），不得以「現況沒寫」代替守衛 |
| 第 6 條：不將部分結果標記為有效 | 分界點（`finishCalibrationInternal()` 成功返回）前不得標記有效、不得寫條件基準／歷史；取消後流程態走向 `NOT_CALIBRATED` | **部分覆蓋** | 已有：退出路徑無 `saveModel()`／`clearHistory()`／`saveReference()`／`addRecord()`／`GazeModel.train()` 呼叫；`AppFlowState` 有 `CalibrationCancelled → NOT_CALIBRATED` 轉換（`AppFlowState.kt:180`，狀態機層）。缺口：流程態的實際同步依賴 `syncFlowState()` 的事實推導（`GazeAccessibilityService` 內，行號待定位），校正頁退出時無顯式事件發送（`exitCalibration()` 不發 `CalibrationCancelled` 事件——待定位：全樹檢索 `CalibrationCancelled` 的發送點，僅見狀態機定義與測試，校正頁無發送呼叫）；報告頁出現前的部分結果偽造報告頁問題現況不存在（報告只在存檔後顯示，`showReport()` `:765`），但未來取消入口須維持此性質 |
| 第 7 條：安全返回啟動流程 | `finish()` 返回呼叫端，不得另開頁面；服務接回只由 `onDestroy()` 的 `resumeAfterCalibration()` 完成一次；返回後不啟用服務、不寫完成標記；告知須含「已取消」與「舊模型保留」（無模型時只說「已取消」） | **部分覆蓋，有一處文字缺口** | 已有：`exitCalibration()` `:501-504` 以 `finish()` 結束、無 `startActivity()`；`onDestroy()` `:873` 無條件 `resumeAfterCalibration()`（全檔唯一呼叫點，靜態確認）；`resumeAfterCalibration()` `:1688-1712`（含 `instance == null` 直接返回、面板恢復、`startEngine()`）。**缺口（文字）**：`exitCalibration()` 的 Toast「已退出校正，舊模型保留」為**無條件**文案，無模型時亦如此宣稱，與契約第 7 條第 5 點「無舊模型時不得宣稱保留了並不存在的舊模型」直接不符——靜態確認的現況缺陷，須在取消入口實作時分支處理（文字待 D-13 統一）。另：現況退出語義為「已退出」，契約要求「已取消」，用詞統一待 D-4／D-13 決定 |
| 第 8 條：防止競態條件 | 單一主執行緒序列化點；單一終端認領旗標（先到先得）；完成進行中互斥；世代檢查；非同步邊界後重查；單一取消入口；重試失效 | **未覆蓋（核心機制缺失）** | 已有模式：主執行緒化（`:202-204`）、`handoverGeneration` 世代模式（`:107,276,287,304`）、各回呼的 `isFinishing` 檢查。缺口：**無單一終端認領旗標**（`isFinishing` 為 Activity 生命週期旗標，非取消／完成共用的認領旗標；`finishCalibration()` `:680-693` 入口無認領檢查，完成進行中取消的互斥無機制）；取消時不遞增任何世代；三條退出路徑雖收斂於 `exitCalibration()`（`ASK`／`ERROR` 兩處呼叫，`:463,491`），但無 D-4 目標入口可收斂，單一入口尚未完整；`errorRetry` 執行前無認領重查（`enterError()` 的重試回呼 `:486-489` 只 `hideChoices()` 後 `invoke()`，無終端檢查） |
| 第 9 條：重複取消冪等性 | 取消入口冪等；四種釋放各最多一次有效執行；清除結果穩定；生命週期重複釋放協調；告知只顯示一次 | **部分覆蓋（依賴生命週期守衛，未驗證）** | 已有：`engineStarted` 守衛（`onPause()` `:251`）、`cancelHandoverTimeout()` 移除後置空（`:317-320`）、`resumeAfterCalibration()` 唯一呼叫點（`onDestroy()` `:873`）、`isFinishing` 守衛。缺口：無取消入口可驗證冪等；`exitCalibration()` 重複呼叫的行為未經測試（兩次 `finish()` 的 `isFinishing` 時序窗口未驗證）；`onPause()`→`onDestroy()` 的重複釋放協調依賴 `engineStarted`＋`stop()` 內部置空兩道防線（`FaceTrackerEngine.stop()` `:506-540`），此為現況結構，契約要求的「效果等同只釋放一次」**未經實機／時序測試驗證**，不得宣稱為已驗證 |

**對照總評：** 九條中，**已覆蓋 1 條（第 5 條，空缺性滿足）、部分覆蓋 6 條（第 1、2、3、6、7、9 條，皆經由生命週期路徑間接滿足、無顯式取消機制）、未覆蓋 2 條（第 4、8 條，取消清除與競態認領機制缺失）**。全部「部分覆蓋」的結論都附帶同一前提：現況根本不存在 D-4 取消入口，覆蓋評估的對象是既有退出路徑（`exitCalibration()`＋生命週期），不是未來取消功能。D-4 定稿後的實作檢查清單以 `CANCEL_CONTRACT.md` 末尾的 11 項檢查表為準，本文件不重寫該表。

## 7. 未決問題總表（本節只列問題，不回答）

| 編號 | 問題 | 關聯證據 |
|---|---|---|
| Q-D4-01…Q-D4-06 | D-4 候選目的六問（見 §1.3） | `GAPS_AND_DECISIONS.md:66-67,260` |
| Q-D4-07 | MASTER PROMPT §6.4 原文為何？「A、B 同時顯示再辨識注視」的前提出自何處？（待定位） | 本輪未定位到該文件，見 §1.2 |
| Q-D4-08 | D-4 形式採眼控取消、非眼控取消，抑或兩者並存？非眼控形式是否滿足 D-1 第 (3) 種情境的要求？ | §4.1 與 PG-03 的張力 |
| Q-D4-09 | 分階段方案的階段點數／點位為何？階段數字如何呈現才不誤導？ | §4.2；`GazeEvaluator.kt:83-84` |
| Q-D4-10 | 分階段改動採集／訓練時序，是否屬於紅線「九點資料流不能動」？ | §4.2；`PLAN.md` §0.2 紅線第 1 條 |
| Q-D4-11 | 覆核拒絕的語義：已存檔模型不撤銷，覆核文字如何誠實反映？覆核確認是否須二次確認、形式為何？ | §4.3；`CalibrationActivity.kt:724,765` |
| Q-D4-12 | 校正前文字是否補「須手動或他人協助」告知？主鈕 `contentDescription` 遮蔽是否屬實（須 TalkBack 實機驗證）？ | §2.3–§2.4 |
| Q-D4-13 | 世代 token 的回合開始定義與遞增點為何？（`onCreate`／首次 `startPoint(0)`／`recalibrate`／取消認領） | §5.4 |
| Q-D4-14 | 採樣前選擇結果是否持久化？抑或僅作用於當次進入？ | §4.1 安全風險 (c) |
| Q-D4-15 | 報告頁關閉後是否留存／可重看？覆核接受後如何回看報告？ | §2.1、§4.3 安全風險 (b)；PG-11 |
| Q-D4-16 | 取消／重試的秒數與位置：取用 `dwellTimeMs`（預設 1000ms、範圍 400–2500ms，`GazeSettings.kt:130-131`），抑或另立專用秒數？位置為何？（秒數／位置皆待實機量測校準，目前無數字） | `D4_OPTIONS.md` §0.3、§6；`CANCEL_CONTRACT.md`「本契約不決定的事」 |
| Q-D4-17 | 單點重做是否納入本次設計？抑或維持整輪重做＋逾時重採？ | §5.1 缺口 (2)；PG-11 |
| Q-D4-18 | TalkBack／Switch Access／Voice Access 的實機驗證由誰、何時、以何裝置與系統版本執行？靜態檢查的全部推斷以何為驗證標準？ | §2.3 限制聲明 |

未決問題總數：**18 項**（Q-D4-01…Q-D4-18，其中 Q-D4-01…Q-D4-06 為 §1.3 的六個候選目的問）。決定記錄見 `d4-decision-record.md`（只記錄未決問題與證據，不寫決定）。

---

## 附錄：複用文件對照（本輪實際讀取與取用）

| 文件 | 取用內容 | 未取用／漂移註記 |
|---|---|---|
| `GAPS_AND_DECISIONS.md` §3.1 D-1／D-2／D-3 定稿 | D-1 六種情境、D-2 RECOVERY_MODE、D-3 組合方案的定稿文字 | §3.1 行號以 v0.5.7 樹為準，本文件程式行號一律以 p3 樹重驗 |
| `GAPS_AND_DECISIONS.md` §3.2 D-4…D-14 清單 | D-4 行原文（§1.1）、D-5…D-14 僅作關聯引用 | D-4 以外各項未在本輪定稿範圍，不展開 |
| `USER_JOURNEY.md` | 旅程 1（歡迎頁）、旅程 2（引導）、旅程 5（9 點校正）的缺口定性（K-23／K-19） | 旅程步驟編號與行號以 v0.5.7 樹為準，p3 樹順序已變（先校正、後服務），引用時已註明 |
| `STATE_DIAGRAMS.md` | R-02（取消分支）、CALIBRATION 行（採樣守衛與 TARGET 取消目標） | TARGET 提案與 `STATE_DESIGN.md` 的對照以 `STATE_DESIGN.md` §6 為準，本文件不重寫 |
| `PLAN.md` | §0.2 紅線、§2.2 D1-7…D1-13、§6 D-4 列 | D1-9「只留介面位置」已在 p3 樹實作（預留常數），本文件確認其零引用狀態 |
| `STATE_DESIGN.md` | §2.1 `CALIBRATING` 列、§3–§4 互動／品質層定義 | 品質層完整恢復序列為 TARGET，本文件不將其當作現況 |
| `TEST_PLAN.md` | §3 C-3（取消測試項）、F-03／F-14（狀態機取消轉換） | 快速重複情境（§5.5）為本文件新增提案，TEST_PLAN 未覆蓋 |
| `IMPLEMENTATION_NOTES.md` | §1 第 13、14、16 項（檢查點誠實重採、注視判定複用後果、逾時計時細節）、§0 Gradle 限制 | 實作與方案的其餘出入不在本輪範圍，不複述 |
| `CANCEL_CONTRACT.md` | 九條契約全文與末尾 11 項檢查表（§6 對照對象） | 契約行號大部分與 p3 樹吻合，服務端行號漂移已註明（§6 表頭） |
| `D4_OPTIONS.md` | §1.3 無落點預測前提、§6 量測項目、選項 A／B／C 的風險分析（與本輪方案命名區分，見檔頭） | 本輪方案 A／B／C 不等於該文件選項 A／B／C；該文件的秒數立場（只取 `dwellTimeMs`）沿用 |
| `TEST_RESULTS.md` | 110 項純 Kotlin 測試全過（harness）、Gradle 本機無法執行、全量編譯留待 CI | 本文件不宣稱任何實機驗證已完成；測試數字僅轉述，不作本輪證據 |
