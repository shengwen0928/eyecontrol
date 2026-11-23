# EyeControl Phase 3 — 發布準備判定（Release Readiness）

> 性質：發布狀態判定文件，**只新增文件，不改任何現有檔案、不改版本號、不改 `.github/workflows/`、不推送、不 commit、不發版**。
> 撰寫日期：2026-11-23。工作樹：`~/workspace/eyecontrol-p3`（repo 複本，無 `.git`，程式碼一律唯讀）。
> 基線：v0.5.9，`versionCode = 13`／`versionName = "0.5.9"`（`app/build.gradle.kts:29-30`）。
> 依據：`~/workspace/eyecontrol-phase3/`（`PLAN.md`、`TEST_PLAN.md`、`IMPLEMENTATION_NOTES.md`、`TEST_RESULTS.md`、`CANCEL_CONTRACT.md`、`D4_OPTIONS.md`）與 `~/workspace/eyecontrol-master-audit/docs/phase2/`（`GAPS_AND_DECISIONS.md`、`STATE_DIAGRAMS.md`）。所有主張附文件引用證據；找不到來源者標「待定位」。

## 0. 判定

### 發布狀態：禁止發布

四種狀態中，本文件判定目前狀態為**最嚴格者：禁止發布**。在 §1 的理由全部消除或經所有權人逐項處置之前，不得推送、不得發版、不得宣稱可發布。

### 四種狀態定義

| 狀態 | 定義 | 進入條件（證據門檻） |
|---|---|---|
| **禁止發布** | 不得推送、不得發版、不得對外宣稱完成。本地分析與準備工作可繼續。 | 任一 §1 理由未消除（目前狀態，見 §0 判定）。 |
| **待 CI** | CI 證據齊全，等待實機驗證。仍禁止發布。 | §2 清單（CI 證據）全部具備；§1 理由中非 CI 項目仍可未消除，但須逐項列入待辦，不得視為已消除。 |
| **待實機驗證** | CI 已通過，實機驗證進行中或待執行。仍禁止發布。 | 已達「待 CI」全部條件，且 CI 實際通過（見 §3 清單前置條件）；實機矩陣項目尚未全部完成。 |
| **可進入下一階段** | 可進入 PLAN §7 步驟 7 的審查與發版準備，並提請所有權人核准推送／發版。**此狀態本身不是發布許可**，推送與發版仍須所有權人另行核准。 | §2、§3、§4 全部清單具備；無未處置的 P0 未解決問題；D-4 已決定且相關實作已驗證。 |

### 關鍵聲明：CI 通過不等於發布通過

**不得把 CI 通過寫成等於發布通過。** CI 通過只證明建置與自動化測試在 CI 環境下執行成功，不證明實機行為、量測門檻、未決策事項或隱私文字缺口已解決。CI 通過後狀態最多升級為「待實機驗證」；發布仍須 §3、§4 的實機證據與所有權人核准。反之亦然：實機驗證通過但 CI 未執行，同樣不得發布。

## 1. 禁止發布的理由（逐項列出，共 9 項）

以下 9 項每一項都獨立構成禁止發布的理由。消除某一項不代表其他項已消除；狀態升級須按 §2–§4 的證據清單逐項核對。

### 理由 1：未驗證編譯

- 事實：本機 Gradle 因沙箱攔截 JVM loopback 連線而永遠跑不起來；`gradle testSideloadDebugUnitTest` 與 `gradle assembleSideloadDebug` 未在本機驗證，全量編譯留待 CI（來源：`IMPLEMENTATION_NOTES.md` §0；`TEST_RESULTS.md` §1、§3）。
- 事實：Android 框架類別（服務接線、設定頁、Overlay 繪製、`CalibrationActivity`）只經靜態核對，未經編譯驗證（來源：`TEST_RESULTS.md` §3）。
- 後果：無法確認 Phase 3 實作（29 項）在全量編譯下可建置；任何「編譯通過」的主張目前皆無證據。

### 理由 2：未實機驗證

- 事實：全部實機清單（`TEST_PLAN.md` §3：F／G／R／E 系列）全部列為「已實作未驗證」，由所有權人在真機（SM-S9180）上驗證（來源：`TEST_RESULTS.md` §4；`IMPLEMENTATION_NOTES.md` §4「未驗證的部分」段）。
- 事實：JSON 往返（`CalibrationStore` 序列化）與 Android 框架類別不可純 JVM 測，改列實機清單，不得冒充為已測（來源：`TEST_RESULTS.md` §4）。
- 後果：29 項實作的真機行為（流程轉換、引導閘門、RECOVERY_MODE、邊緣手勢）皆無證據；程式碼靜態核對不得代替實機驗證。

### 理由 3：50dp 未量測

- 事實：邊緣帶由 `edgeSize = 50f` raw px 改為 50 dp × 密度、短邊 15% 上限；高密度裝置上的帶寬比 v0.5.9 的 50 px 寬，SM-S9180 上約 4 倍物理寬度（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 24 點）。
- 事實：實機 E-9 須以 v0.5.9 為對照量測誤觸／漏觸，不得宣稱未測已測；E-10 量測（誤觸率／漏觸率／操作時間）未執行、不設通過門檻（來源：同上；`TEST_PLAN.md` §3.2 E-9／E-10、§3.5）。
- 後果：幾何改動對誤觸率／漏觸率的實際影響未知；邊緣帶改動是否改善或倒退皆無數據。

### 理由 4：D-4 未決

- 事實：D-4（校正取消目標的形式與秒數）未決；D1-9 只留介面配置位置（4 個 companion 常數，`CalibrationActivity.kt:1117-1123`），全專案零引用、無事件、無視覺元素、九點座標未動（來源：`IMPLEMENTATION_NOTES.md` §4；`PLAN.md` §6 D-4 列）。
- 事實：D-4 選項文件只提案、不定案，形式、秒數、位置皆待決定；決定前必須量測的事項（角落重疊、誤觸率、取消耗時等）目前皆無數字（來源：`D4_OPTIONS.md` §0.2、§6、§7）。
- 事實：取消事件發生後的底層行為契約已立（`CANCEL_CONTRACT.md` 第 1–9 條），但契約明載不決定形式、秒數、位置（見該文件「本契約不決定的事」）。
- 後果：校正頁無眼控取消目標，P0 缺口（K-19）未修復；純眼控使用者在校正中途無法以眼睛主動退出（來源：`GAPS_AND_DECISIONS.md` PG-03）。

### 理由 5：D3-7 dispatch 未做

- 事實：PLAN D3-7 的 dispatch 部分本步未做：四個 dispatch 的 `onCancelled` 回呼（K-62）與 `performDoubleTap` 第二下具名化＋`removeCallbacks`（K-07）皆未動，現況 dispatch 本體原樣保留；控制器 Cancelled 輸出與 dispatch 取消是兩回事，不得混為一談；dispatch 部分須另排一步、另列實機驗證（G-T15 對應項）（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 27 點、§3）。
- 後果：手勢被拒時無取消回饋、無狀態修正；雙擊第二下在模式切換／暫停／停止／提醒介入／`onDestroy` 時無法取消（來源：`PLAN.md` §4 D3-7；`STATE_DIAGRAMS.md` §5 G-T14／G-T15）。

### 理由 6：K-12 殘餘

- 事實：STEP_CAMERA 說明句「不存照片、不上傳」未動，STEP_CAMERA／歡迎頁絕對表述與監控例外段的張力仍在（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 12 點）。
- 事實：第 4 步僅處理 STEP_CAMERA／歡迎頁兩處，SettingsActivity 對話框、主畫面頁尾、`strings.xml` 的 accessibility_desc 仍為絕對表述，待後續批次（來源：`IMPLEMENTATION_NOTES.md` §2「第 4 步處理結果」段）。
- 後果：App 內文字與監控預設開啟的事實（側載版）不一致，授權前揭露不完整（見 `GAPS_AND_DECISIONS.md` PG-24）；在文字修復並實機核對之前，不得宣稱揭露已誠實。

### 理由 7：相機綁定無觀測信號

- 事實：相機非同步綁定失敗無可觀測信號：綁定回呼與 landmarker ErrorListener 只記 Log，無回呼、無旗標、無外拋；綁定失敗時 flowState 不進 ERROR，僅由 D1-15 樣本新鮮度在引導側擋下「驗證通過」；需引擎層新增可觀測信號，屬後續設計（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 21 點、§2 (d)）。
- 事實：D1-15 樣本新鮮度不等於看得到臉：臉移出時引擎仍送 null 樣本、時間戳照樣更新，G-3「臉移出即未通過」的例子按此定義實際上仍會通過（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 22 點）。
- 後果：相機綁定失敗時，使用者可能看到「服務已開啟」卻無追蹤畫面，且系統無具名錯誤狀態可供診斷與復原。

### 理由 8：K-30 未修

- 事實：K-30（`userStopped`／`inRecoveryMode` 未持久化，行程重啟自動恢復）維持現況，待 D-8 決定，不得宣稱為已修復（來源：`IMPLEMENTATION_NOTES.md` §3；`PLAN.md` §6 D-8 列、D2-6）。
- 事實：現況 `onServiceConnected` 不檢查舊旗標，行程重啟後自動恢復追蹤，與使用者「已停止」的意圖相反（來源：`PLAN.md` §1.1「退出 App」列；`GAPS_AND_DECISIONS.md` PG-16）。
- 後果：停止／暫停意圖在行程重啟後丟失；使用者最後的操作意圖不被尊重。

### 理由 9：門檻未校準

- 事實：門檻待校準清單全部標記 UNCALIBRATED、預設不阻擋：連續 present 最少幀數 2、離散度上限 `Float.MAX_VALUE`（永不阻擋、只記量測值）、方向死區 2 px、邊緣帶短邊上限比例 0.15；原始預測 ±15% 容忍邊界為現況值沿用（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 29 點；`EdgeGestureController.kt:499-509`，`DISPERSION_MAX_PX_UNCALIBRATED` 等常數）。
- 事實：確認取消寬限 500 ms、確認目標幾何皆為版面選擇／現況同值沿用，未經實機驗證（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 25／26 點）。
- 事實：門檻只定邏輯不定值，待實機量測校準，不得寫死為已驗證數字（來源：`TEST_RESULTS.md` §4）。
- 後果：邊緣手勢的信心／穩定性檢查目前實質上不阻擋（dispersion 檢查永不觸發）；檢查的實際保護作用未知，不得宣稱誤觸已受控。

### 補充說明（不改變判定）

- kotlinc harness 110 項全過（`TEST_RESULTS.md` §2）只證明純 Kotlin 類別在本機 harness 下通過，不消除上述任何一項理由。harness 通過不得用於升級狀態。
- D1-9 預留、取消契約、D-4 選項文件等「準備工作」的完成，不等於取消功能的完成；D-4 未決前，取消功能不存在（見理由 4）。

## 2. 升級到「待 CI」所需的證據清單

以下全部具備，狀態方可由「禁止發布」升級為「待 CI」。「待 CI」仍禁止發布（見 §0）。

| 編號 | 所需證據 | 驗收方式 | 現況 |
|---|---|---|---|
| CI-E1 | CI 檢查清單全部執行完畢 | `ci-validation-report.md` §2 的 CI-01…CI-10 每一項皆有 CI 執行紀錄（log 連結、run 編號），狀態由「尚未執行」改為有紀錄的已執行／已通過，不得以本機 harness 輸出代替 | 全部尚未執行 |
| CI-E2 | CI 使用正式專案的 Gradle Wrapper 與建置設定 | CI-01 檢查紀錄載明 wrapper 設定與實際採用的建置方式；wrapper 損壞的落差已決定處置方式並記錄（見 `ci-validation-report.md` §1.3） | 落差待確認 |
| CI-E3 | 未為求通過刪功能或降標準 | 變更紀錄（以 A 組 blob SHA 複驗為準）顯示無功能刪除、無門檻放寬；門檻維持 UNCALIBRATED 現況 | 待 CI 執行後核對 |
| CI-E4 | 簽章機制驗證 | debug keystore 僅用於側載 debug 版；release AAB 以 GitHub secrets 鑰匙機制簽章，CI 紀錄中無密鑰值外洩（見 `ci-validation-report.md` §3） | 機制已記錄，未驗證執行 |
| CI-E5 | 測試結果已保存 | CI-10：XML 計數與 HTML 報告已保存，計數以 XML 的 `failures`／`errors` 為準 | 尚未執行 |
| CI-E6 | 建置產物已儲存 | CI-09：APK 與 AAB artifact 已上傳，`if-no-files-found: error` 未觸發 | 尚未執行 |

## 3. 升級到「待實機驗證」所需的證據清單

以下全部具備，狀態方可由「待 CI」升級為「待實機驗證」。前置條件：§2 全部具備且 CI 實際通過（CI 通過本身見 §0 關鍵聲明，不等於發布通過）。

| 編號 | 所需證據 | 驗收方式 | 現況 |
|---|---|---|---|
| DV-E1 | 實機驗收矩陣全部項目已執行 | `device-acceptance-matrix.md` 全部項目（M-01…M-42）皆已在 SM-S9180／Android 17 實機上執行，「實際結果」與「證據」欄位已填入且可核對；無項目留空 | 全部未測 |
| DV-E2 | D-4 已決定且取消功能已實作驗證 | M-12 決策紀錄完整（形式／秒數／位置）；M-10 在 D-4 定稿後實作並實機驗證，含首次校正無舊模型情境的安全返回（見 `CANCEL_CONTRACT.md` §0.3） | D-4 未決，M-10 不可執行 |
| DV-E3 | D3-7 dispatch 已實作驗證 | M-13（G-T15）、M-14（G-T14）實機驗證通過；`onCancelled` 與雙擊第二下取消皆已實作 | 程式未做 |
| DV-E4 | K-12 殘餘已清除 | M-22、M-23 實機核對通過：STEP_CAMERA／歡迎頁／設定頁對話框／主畫面頁尾／strings accessibility_desc 皆與監控例外段一致 | 殘餘仍在 |
| DV-E5 | 相機綁定失敗有可觀測信號 | M-24：引擎層可觀測信號已設計、已實作，綁定失敗時 flowState／Dashboard 有具名錯誤狀態；實機驗證通過 | 設計待定，無信號 |
| DV-E6 | K-30 已決定並驗證 | M-26：D-8 已決定持久化策略，行程重啟後的行為已實機驗證並記錄；意圖保留或自動恢復的語義已明確且與顯示一致 | K-30 未修，待 D-8 |
| DV-E7 | 門檻已校準或已量測記錄 | M-21：誤觸率／漏觸率／操作時間量測表已記錄（含 v0.5.9 對照）；dispersion、連續 present 幀數、方向死區、邊緣帶上限比例、確認取消寬限的校準值或「維持不阻擋」的決定已記錄，不得以未驗證數字充當已校準值 | 全部 UNCALIBRATED，未量測 |
| DV-E8 | 50dp 已量測對照 | M-19（直向）、M-20（橫向）對照量測完成，兩版數字記錄成表 | 未量測 |

## 4. 升級到「可進入下一階段」所需的證據清單

以下全部具備，狀態方可由「待實機驗證」升級為「可進入下一階段」。此狀態仍不是發布許可（見 §0）。

| 編號 | 所需證據 | 驗收方式 | 現況 |
|---|---|---|---|
| NX-E1 | §2 與 §3 全部清單具備 | CI 證據與實機證據皆已留存，兩者缺一不可（見 `TEST_PLAN.md` §4 交付物要求） | 皆未具備 |
| NX-E2 | 全部實機項目通過或已處置 | 實機矩陣中每一項為「通過」，或失敗項已有所有權人的處置決定（修復後重測／接受風險，逐項記錄）；無未處置的失敗項 | 全部未測 |
| NX-E3 | 無未處置的 P0 未解決問題 | K-19（D-4 取消）、K-25（RECOVERY_MODE 實機驗證）、K-01（邊緣誤觸量測）等 P0 項目皆已驗證通過或經所有權人明確處置 | P0 項目皆未驗證 |
| NX-E4 | 量測門檻已定稿 | M-21 量測結果已由所有權人定稿為門檻（或明確決定維持現況值）；門檻數字有量測紀錄為據，不得憑空定稿 | 門檻待定 |
| NX-E5 | 文件與實作一致 | `STATE_DESIGN.md` 與實作一致（有出入先改文件再改程式，見 `PLAN.md` §7 步驟 7）；版本號未動；`.github/workflows/` 未動 | 待步驟 7 審查時核對 |
| NX-E6 | 推送／發版授權另行取得 | 所有權人就推送分支、變更檔案清單（以 A 組 blob SHA 複驗為準）、回滾方法逐項授權（格式見 `ci-validation-report.md` §5）；本文件的狀態升級本身不是授權 | 未授權 |

## 5. 待定位事項

- 任務書所稱「§13 矩陣」：在先讀文件中未找到名為 §13 的實機矩陣（`STATE_DESIGN.md` 僅 §0–§8；`USER_JOURNEY.md` §13 為三個結構性斷點），標為**待定位**。本文件的實機證據清單以 `TEST_PLAN.md` §3 為準；若後續定位到 §13 矩陣，須增補 §3 清單，不得以本文件代替。
- D-5…D-14（除 D-4、D-8、D-12 外）：見 `PLAN.md` §6，一概不代決；其中影響本階段的項目（D-5 擺位指引臉框、D-6 回饋形式、D-10 第二入口、D-11 練習必要性、D-13 術語、D-14 合併順序）是否納入下一階段驗收，待所有權人決定，本文件不預設。
- 邊緣開關粒度（左／右分開 vs 側邊合一）：待所有權人確認（見 `IMPLEMENTATION_NOTES.md` §1 出入第 31 點），確認前維持四邊分開的實作；本判定不以任一粒度為前提。

## 6. 引用證據清單

- `~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md` §0、§1 出入第 12／14／16／21／22／24／25／26／27／29／31 點、§2、§3、§4。
- `~/workspace/eyecontrol-phase3/TEST_RESULTS.md` §1–§4。
- `~/workspace/eyecontrol-phase3/TEST_PLAN.md` §0.2、§1.4、§3–§3.5、§4。
- `~/workspace/eyecontrol-phase3/PLAN.md` §0.2、§1.1、§4（D3-7）、§6、§7。
- `~/workspace/eyecontrol-phase3/CANCEL_CONTRACT.md` §0.3、第 1–9 條、「本契約不決定的事」。
- `~/workspace/eyecontrol-phase3/D4_OPTIONS.md` §0.2、§1.3、§6、§7。
- `~/workspace/eyecontrol-master-audit/docs/phase2/GAPS_AND_DECISIONS.md` §3.1（D-1／D-2／D-3）、§3.2（D-4…D-14）、PG-03／PG-16／PG-24。
- `~/workspace/eyecontrol-master-audit/docs/phase2/STATE_DIAGRAMS.md` §1、§2、§5（G-T14／G-T15）。
- `~/workspace/eyecontrol-p3/app/build.gradle.kts:29-30`（版本）；`~/workspace/eyecontrol-p3/app/src/main/java/com/example/eyecontrol/CalibrationActivity.kt:1117-1123`（D1-9 預留常數）；`~/workspace/eyecontrol-p3/app/src/main/java/com/example/eyecontrol/EdgeGestureController.kt:499-509`（UNCALIBRATED 常數）。
- 本階段新文件：`docs/ci-validation-report.md`（CI 證據）、`docs/device-acceptance-matrix.md`（實機證據）。
