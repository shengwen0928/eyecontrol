# EyeControl STEP 5 — CI 分支推送計畫（只寫計畫，不推送）

> 性質：STEP 5（CI 準備）文件。**本任務未推送、未建分支、未建 PR、未發 Release、
> 未上傳 APK、未修改 `.github/workflows/` 下任何檔案、未改版本號、未碰 secrets／keystore。**
> 本文件是「等待擁有者批准才推送」的提案，本身不是授權，也不得被解讀為授權。
>
> 基準：工作樹 `~/workspace/eyecontrol-p3`（repo 複本，無 `.git/`）；遠端 `main` HEAD
> `c6b80964`（全 SHA `c6b809640de5bc0a83cd0b96c70a55395de1c784`，commit 訊息自稱 v0.5.9，
> 以 GitHub `list_commits`（`sha=main`，前 3 筆）於 2026-11-23 核實，第一筆即此 SHA）。
> 工作樹版本 `versionCode = 13`、`versionName = "0.5.9"`（`app/build.gradle.kts:29-30`；
> 遠端同一檔案同一行號亦為 13／0.5.9，blob SHA 比對核實，見 §2）。
>
> 變更清單快照時點：2026-11-23（台北時間，STEP 5／STEP 7 文件寫入前）。
> 快照後新增的本計畫文件與 `docs/engineering-report.md` 列入推送集，見 §2.4 的算術。
> **CI 通過 ≠ 實機通過**：CI 通過後狀態最多為「待實機驗證」，仍禁止發布
> （見 `docs/release-readiness.md` §0；`docs/ci-validation-report.md` §4 原則 4）。

## 1. 唯讀核對：`.github/workflows/` 現況（未修改）

核對方式：本機工作樹檔案與遠端 HEAD 的 blob SHA 逐檔比對（見 §2 方法）。
兩個 workflow 檔案皆與遠端一致、**零異動**，本任務亦未寫入：

| 檔案 | 遠端 blob SHA（前 12 碼） | 本機 blob SHA | 狀態 |
|---|---|---|---|
| `.github/workflows/build-apk.yml` | `c0bd3960bfbd` | `c0bd3960bfbd` | 未異動 |
| `.github/workflows/attach-apk-to-release.yml` | `d7fcbafaf69e` | `d7fcbafaf69e` | 未異動 |

現行 workflow 內容摘要（唯讀記錄，行號為工作樹行號；完整內容見檔案本身）：

- `build-apk.yml`：觸發條件為 `push` 到 `main` 分支＋`workflow_dispatch`（手動觸發）。
  步驟：checkout → Temurin JDK 17 → 以 runner 預裝 SDK 安裝
  `platforms;android-36`／`build-tools;36.0.0`（不用 `android-actions/setup-android`，
  見 `~/AGENTS.md` Lessons）→ `gradle/actions/setup-gradle@v4`（Gradle 8.13，
  不走 wrapper；repo 無 `gradlew`，wrapper jar 已知損壞，見
  `docs/ci-validation-report.md` §1.3）→ 還原 release keystore（僅 secrets 非空時，
  secrets 值未讀取、未驗證是否已設定）→
  `gradle assembleSideloadDebug bundlePlayRelease --stacktrace` → 顯示 AAB 簽章憑證 →
  上傳 2 個 artifact（`eyecontrol-sideload-debug-apk`、`eyecontrol-play-release-aab`，
  `if-no-files-found: error`，保留 30 天）。
- `attach-apk-to-release.yml`：僅 `workflow_dispatch`（指定 tag），建 debug APK 後掛到
  release；與本分支的 CI 驗證無關，本計畫不使用它。
- **兩個 workflow 皆無測試步驟、無 Lint 步驟**（見 `docs/ci-validation-report.md` §1.3、
  §2 CI-06／CI-07）。在 workflow 更新前，CI 永遠不會跑單元測試；CI 通過只證明編譯，
  不證明測試通過。

### 1.1 Workflow 修改限制（硬限制，記錄於此，不得繞過）

- GitHub App（Muse 用的）沒有 workflows 權限：`push-files` 建立／修改
  `.github/workflows/` 下的檔案會回 403。**workflow 檔案一律走雲端瀏覽器＋
  使用者登入工作階段，在 GitHub 網頁上建立／修改**（來源：`~/AGENTS.md` Lessons
  第一條；`docs/ci-validation-report.md` §4 原則 5；`docs/risk-register.md` R9／R20）。
- 因此 §5 的 workflow diff **只是提案**，本任務不實作、不推送該 diff。
  是否採納、由誰在瀏覽器中修改、何時修改，皆待擁有者決定。
- 本推送集不含任何 workflow 檔案（見 §2），故專用分支的推送本身不受上述 403 限制；
  但「推送會不會 403」最終以實際推送工具的回應為準，本文件不預斷。

### 1.2 關鍵推論：專用分支推送本身不會觸發 `build-apk.yml`

`build-apk.yml` 的 `push` 觸發只列 `main`。推送到專用分支（§3）**不會自動觸發**
該 workflow。取得 CI 編譯證據的路徑只有兩條（皆須擁有者操作，本任務不執行）：

- 路徑 A（不改 workflow）：推送分支後，由擁有者在 GitHub Actions 頁面以
  `workflow_dispatch` 手動觸發 `build-apk.yml`，並在觸發時選取專用分支。
  此路徑只驗編譯＋產物（CI-05／CI-08／CI-09），不驗測試。
- 路徑 B（改 workflow，瀏覽器）：按 §5 提案在 workflow 中加入測試步驟（與／或
  將 `push` 觸發擴及專用分支），再觸發。此路徑才能取得 CI 測試證據
  （CI-06／CI-10）。workflow 修改本身須走 §1.1 的瀏覽器路徑。

## 2. 變更清單：工作樹相對遠端 HEAD `c6b80964`（blob SHA 逐檔比對）

### 2.1 方法（可重現，唯讀）

1. 以 GitHub `get_file_contents`（`ref=main`，解析至 HEAD `c6b80964`）自根目錄
   遞迴走訪遠端樹，取得 65 個遠端檔案的 blob SHA 與大小（走訪腳本輸出見
   本任務工作紀錄；遠端樹含 `.github/`、`.gitignore`、`LICENSE`、`README.md`、
   `app/`、`build.gradle.kts`、`docs/evaluation.md`、`docs/privacy.html`、
   `gradle.properties`、gradle wrapper、`settings.gradle.kts`）。
2. 本機以 `git hash-object`（不需 repo，只算 blob SHA）逐檔計算工作樹 108 個檔案
   的 SHA，與遠端 SHA 逐一比對。SHA 相同＝未異動；SHA 不同＝異動；
   僅本機有＝新增；僅遠端有＝刪除。
3. 交叉驗證：16 個異動檔的遠端內容另以 `get_file_contents` 逐檔取回，
   取回內容的 `git hash-object` 皆與遠端 blob SHA 一致（16／16 吻合），
   故 §2.3 的增／刪行數以取回內容與本機檔案的 `diff` 為準（行數為 `diff`
   輸出的 `>`／`<` 行數，含長行整行計數，僅作量級參考，非語義審查）。
4. 限制：本比對只證明「檔案位元組是否相同」，不證明語義正確、不證明編譯通過。
   遠端分支清單未核對（`list_branches` 不在唯讀工具集內，本任務未呼叫需審批的
   寫入類工具）：§3 的分支名是否已存在，推送前須由擁有者在 GitHub 上核對。

### 2.2 比對結果總計（快照時點，STEP 5／STEP 7 文件寫入前）

| 類別 | 檔數 |
|---|---|
| 新增（僅本機有） | 43 |
| 異動（SHA 不同） | 16 |
| 刪除（僅遠端有） | 0 |
| 未異動（SHA 相同） | 49 |
| 遠端檔案總數 | 65 |
| 本機檔案總數（快照時點） | 108 |

### 2.3 異動檔（16 檔，含 `diff` 量級）

| 檔案 | 遠端大小（bytes） | 本機大小（bytes） | diff 量級 |
|---|---|---|---|
| `app/build.gradle.kts` | 5966 | 6011 | +1／−0 |
| `app/src/main/AndroidManifest.xml` | 3275 | 3394 | +2／−0 |
| `app/src/main/java/com/example/eyecontrol/CalibrationActivity.kt` | 18832 | 50099 | +741／−43 |
| `app/src/main/java/com/example/eyecontrol/CursorOverlayView.kt` | 4964 | 12694 | +168／−1 |
| `app/src/main/java/com/example/eyecontrol/GazeAccessibilityService.kt` | 57427 | 102035 | +937／−76 |
| `app/src/main/java/com/example/eyecontrol/GazeLog.kt` | 15183 | 15611 | +5／−0 |
| `app/src/main/java/com/example/eyecontrol/GazeSettings.kt` | 10763 | 13612 | +43／−0 |
| `app/src/main/java/com/example/eyecontrol/MainActivity.kt` | 21157 | 27786 | +147／−42 |
| `app/src/main/java/com/example/eyecontrol/SettingsActivity.kt` | 18103 | 20943 | +47／−0 |
| `app/src/main/java/com/example/eyecontrol/SetupWizardActivity.kt` | 14227 | 23628 | +183／−26 |
| `app/src/main/java/com/example/eyecontrol/VerifyFrames.kt` | 15324 | 15699 | +6／−0 |
| `app/src/main/java/com/example/eyecontrol/WelcomeActivity.kt` | 1607 | 2659 | +15／−1 |
| `app/src/main/res/layout/activity_main.xml` | 35564 | 34554 | +0／−19 |
| `app/src/main/res/layout/activity_settings.xml` | 67810 | 80086 | +271／−0 |
| `app/src/main/res/layout/activity_setup_wizard.xml` | 3745 | 4299 | +11／−0 |
| `app/src/main/res/layout/activity_welcome.xml` | 9523 | 10095 | +12／−1 |

> 註：`CalibrationActivity.kt` 與 `GazeAccessibilityService.kt` 的大幅增行主要來自
> Phase 3 實作（D1／D2／D3 系列）＋ STEP 3 修復（F-1～F-8），非單一改動；
> 本比對不拆分 Phase 3 與 STEP 3 的各自貢獻（兩者皆未推送，皆在同一工作樹內）。
> `activity_main.xml` 為淨刪行（+0／−19），其餘異動檔皆為淨增行。
> `app/build.gradle.kts` 的 +1 行須以 CI 驗證其語義（版本號未改，見基準段）；
> `AndroidManifest.xml` 的 +2 行為 F-6 的備份規則屬性（見 `step3-fix-log.md` F-6）。

### 2.4 新增檔（43 檔，快照時點）

程式（新增 5 檔）：

| 檔案 | 來源 |
|---|---|
| `app/src/main/java/com/example/eyecontrol/AppFlowState.kt` | Phase 3 D1-1 |
| `app/src/main/java/com/example/eyecontrol/EdgeGestureController.kt` | Phase 3 D3-1 |
| `app/src/main/java/com/example/eyecontrol/RecoveryGate.kt` | Phase 3 D2-3 |
| `app/src/main/java/com/example/eyecontrol/GuardDecisions.kt` | STEP 3 F-1／F-4（新增） |
| `app/src/main/java/com/example/eyecontrol/GestureDispatchGuard.kt` | STEP 3 F-8（新增） |

資源（新增 2 檔）：`app/src/main/res/xml/backup_rules.xml`、
`app/src/main/res/xml/data_extraction_rules.xml`（皆 STEP 3 F-6）。

測試（新增 12 檔，皆在 `app/src/test/java/com/example/eyecontrol/` 下）：

`AppFlowStateTest.kt`、`ConditionHistoryTest.kt`、`DwellTrackerTest.kt`、
`EdgeGestureControllerTest.kt`、`EdgeSwitchRestoreTest.kt`、`GazeEvaluatorTest.kt`、
`GazeModelMathTest.kt`、`GazeSettingsDefaultsTest.kt`、`GestureDispatchGuardTest.kt`、
`GuardDecisionsTest.kt`、`OneEuroFilterTest.kt`、`RecoveryGateTest.kt`。

文件（新增 24 檔，皆在 `docs/` 下）：

`accuracy-baseline-plan.md`、`architecture-map.md`、`calibration-interaction-redesign.md`、
`camera-binding-observability.md`、`ci-validation-report.md`、`d4-decision-record.md`、
`dcl-mapping.md`、`device-acceptance-matrix.md`、`device-diagnostic-evidence.md`、
`edge-gesture-safety.md`、`edge-width-device-measurement.md`、`engineering-baseline.md`、
`engineering-remediation-plan.md`、`engineering-task-matrix.md`、`function-matrix.md`、
`gesture-dispatch-state-machine.md`、`privacy-copy-audit.md`、`privacy-data-flow.md`、
`privacy-security-report.md`、`release-readiness.md`、`restart-recovery-policy.md`、
`risk-register.md`、`step3-fix-log.md`、`verification-ledger.md`。

### 2.5 推送集最終算術（寫入 STEP 5／STEP 7 文件後）

- 本計畫文件 `docs/ci-branch-plan.md`（本文件）與 `docs/engineering-report.md`
  為快照後新增的 2 個檔案，遠端皆無，故新增數由 43 增為 **45**。
- `docs/verification-ledger.md` 在快照時點已屬新增檔；STEP 7 對它的行狀態更新
  不改變其「新增」類別（仍不在遠端），異動數維持 **16**。
- **最終推送集：新增 45 檔、異動 16 檔、刪除 0 檔，合計 61 檔；未異動 49 檔；
  推送後工作樹檔案總數 110。** 推送執行者須在推送前重新執行 §2.1 的比對
  （工作樹在批准等待期間若有任何寫入，清單即失效，須重算）。

### 2.6 未異動檔（49 檔，摘要）

含兩個 workflow 檔案（見 §1）、`.gitignore`、`LICENSE`、`README.md`、
`app/debug-keystore.b64`、`app/proguard-rules.pro`、
`app/src/main/assets/face_landmarker.task`（blob SHA 相同）、
`build.gradle.kts`、`gradle.properties`、gradle wrapper、`settings.gradle.kts`、
`docs/evaluation.md`、`docs/privacy.html`，以及未被 Phase 3／STEP 3 改動的
其餘 Kotlin／資源檔案（完整清單以 §2.1 方法重算為準）。
特別記錄：`FaceTrackerEngine.kt`、`GazeModel.kt`、`GazeFeatureExtractor.kt`、
`OneEuroFilter.kt`、`TrainingActivity.kt`、`UpdateChecker.kt` 等皆未異動；
「五個不能動」錨點在本機工作樹中皆存在且未被 STEP 3 改動其常數／協議
（見 `docs/engineering-report.md` §4；STEP 3 期間 `CalibrationActivity.kt`
全檔未動，見 `step3-fix-log.md` §5——此敘述指 STEP 3 期間相對 Phase 3 工作樹，
非指相對遠端 HEAD）。

## 3. 分支與 Commit 提案（等待批准，不執行）

### 3.1 專用分支名提案

```
phase3-step3-review
```

- 提案理由：名稱明示內容為「Phase 3＋STEP 3 修復的審查分支」，非版本分支、
  非發布分支；**絕不直接推 `main`**（見 `docs/risk-register.md` R20；
  推送邊界：批准後只推專用分支、絕不直接推 main、不自動發布 Release、
  發布另需批准）。
- 推送前核對（擁有者／執行者）：在 GitHub 上確認該分支名尚未被占用；
  若已存在，改名後重提，不得覆蓋既有分支。本任務未核對分支占用
  （見 §2.1 限制第 4 點）。
- 基底：遠端 `main` HEAD `c6b80964`。分支建立後，`main` 不受任何影響；
  後續合併（merge／PR）另需批准，本計畫不提案合併。

### 3.2 Commit 提案（單一 commit，全部 61 檔）

> 以下為 commit message 草稿（供批准時使用，尚未 commit）。單一 commit 的理由：
> 回滾方式即刪除整個分支（見 §7），拆分 commit 不增加回滾能力，反而增加
> 中間狀態被誤讀為可用的風險。若擁有者要求拆分（例如程式與文件分開），
> 須另行批准拆分方式，本計畫不預設。

```
Phase 3 implementation + STEP 3 safety fixes (review branch, unverified on device)

- Phase 3 interactive architecture (D1/D2/D3, 29 items implemented,
  D1-9 constants-only pending D-4) + STEP 3 minimal fixes F-1..F-8
  and verification-only V-2/V-3, all unverified on device.
- 61 files vs remote main HEAD c6b80964 (v0.5.9): 45 added, 16 modified,
  0 deleted. Version unchanged: versionCode 13 / versionName 0.5.9.
  No workflow changes. No secrets/keystore touched.
- Local pure-Kotlin harness: COMPILE_OK, OK (141 tests) on 2026-11-23
  (kotlinc harness only; full Gradle build, CI and device verification
  NOT done). CI pass does not equal device pass.
- See docs/engineering-report.md (STEP 7) and docs/ci-branch-plan.md
  (this plan). Release status: DO NOT RELEASE (see docs/release-readiness.md).
```

### 3.3 推送步驟（批准後，由批准的執行者操作；本任務不執行）

1. 擁有者書面批准本計畫（含分支名、61 檔清單、§5 的 workflow 處置）。
2. 執行者重新執行 §2.1 比對，確認清單與 §2.5 算術一致；不一致即停下重提。
3. 在 GitHub 上核對分支名未被占用（見 §3.1）。
4. 以快照推送建立專用分支 `phase3-step3-review`（基底 `c6b80964`），
   推送全部 61 檔、單一 commit（§3.2 草稿）。**不推 `main`，不建 PR，
   不發 Release，不上傳 APK 到 release。**
5. 按 §1.2 選擇路徑 A（手動 `workflow_dispatch`）或路徑 B（先走瀏覽器改
   workflow，再觸發），取得 CI 執行紀錄；將 CI run 編號／log 連結／產物
   清單填入 `docs/ci-validation-report.md` §2（CI-01～CI-10）與
   `docs/verification-ledger.md`（僅依 CI 證據升級對應行，見該帳本規則）。
6. CI 通過後，狀態最多為「待實機驗證」；實機驗證按
   `docs/device-acceptance-matrix.md` 與 `~/workspace/eyecontrol-phase3/TEST_PLAN.md`
   §3 執行，不得以 CI 結果代替。

## 4. 測試程式清單（將隨分支推送，供 CI／審查者核對）

12 個測試類別（皆在 `app/src/test/java/com/example/eyecontrol/` 下；
本機 harness 計數見 `docs/engineering-report.md` §6，CI 計數尚未取得）：

| 測試類別 | 來源階段 | 覆蓋對象（摘要） |
|---|---|---|
| `AppFlowStateTest` | Phase 3 | D1-1 流程狀態機轉換 |
| `ConditionHistoryTest` | Phase 3 回歸 | 條件歷史純函式 |
| `DwellTrackerTest` | Phase 3 回歸 | 停留追蹤純邏輯 |
| `EdgeGestureControllerTest` | Phase 3 | D3-1…D3-8 邊緣控制器 |
| `EdgeSwitchRestoreTest` | STEP 3 V-2 | DCL-02 總關／分項恢復（3 項） |
| `GazeEvaluatorTest` | STEP 3 F-1 批次 | 校正品質評估（7 項） |
| `GazeModelMathTest` | Phase 3 回歸 | 模型數學（不含 JSON 往返） |
| `GazeSettingsDefaultsTest` | STEP 3 F-1 | 設定預設值（眨眼預設關，4 項） |
| `GestureDispatchGuardTest` | STEP 3 F-8 | 派發守衛狀態機（11 項） |
| `GuardDecisionsTest` | STEP 3 F-1／F-4 | 眨眼判定＋撤回真值表（6 項） |
| `OneEuroFilterTest` | Phase 3 回歸 | 濾波器純邏輯 |
| `RecoveryGateTest` | Phase 3 | D2-3 恢復守衛 2×2×2（6 項） |

限制（重申）：上述測試皆為純 Kotlin／JUnit 測試，Android 框架類別
（服務／Activity／引擎／overlay／`GazeLog` 檔案寫入與上傳／備份規則）
不在 harness 覆蓋範圍；`GazeModel` JSON 往返與 prefs 讀寫在純 JVM 下為
`org.json`／Android stub，不在自動化範圍，須走實機（見
`docs/verification-ledger.md` §0.2）。測試檔案存在 ≠ CI 已執行測試。

## 5. CI 設定 diff 提案（只寫提案，須走瀏覽器；本任務不實作）

> 以下 diff 為**提案文字**，未寫入工作樹的任何 workflow 檔案。
> 採納與否、修改方式、修改時機，皆由擁有者在雲端瀏覽器＋登入工作階段中決定
> （見 §1.1）。若不採納，CI 只能執行現行 workflow 的編譯步驟（路徑 A），
> 測試證據須另以「本機 Gradle 執行」（一般機器／CI 外）或後續 workflow 更新取得，
> 不得以本機 harness 數字冒充 CI 測試結果。

### 5.1 提案 B-1：在 `build-apk.yml` 加入單元測試步驟（最小改動）

目的：使 CI 執行 `gradle testSideloadDebugUnitTest`，並保存 XML／HTML 測試報告，
對應 `docs/ci-validation-report.md` CI-06／CI-10。

```diff
       - name: Build sideload APK and Play AAB
         env:
           ...
         run: |
           rm -f local.properties
           gradle assembleSideloadDebug bundlePlayRelease --stacktrace
+
+      - name: Run unit tests
+        run: |
+          rm -f local.properties
+          gradle testSideloadDebugUnitTest --stacktrace
+
+      - name: Upload test results
+        if: always()
+        uses: actions/upload-artifact@v4
+        with:
+          name: unit-test-results
+          path: |
+            app/build/test-results/testSideloadDebugUnitTest/
+            app/build/reports/tests/testSideloadDebugUnitTest/
+          if-no-files-found: warn
+          retention-days: 30
```

注意事項（提案附帶說明，非決定）：

- 測試步驟置於建置步驟之後或之前，須由修改者決定；本提案置於其後，
  僅為示意位置，不得視為已定稿的步驟順序。
- `env:` 內容在上方以 `...` 省略：提案不複寫任何 secrets 名稱以外的值；
  實際修改時須以 GitHub 網頁上顯示的現行檔案全文為準，不得以本文件的
  省略寫法直接貼上覆蓋。
- 若測試步驟失敗，整個 job 失敗：此為期望行為（測試失敗不得放行），
  不得以「先讓 CI 變綠」為由刪除測試步驟或放寬門檻
  （見 `docs/ci-validation-report.md` §4 原則 2）。
- Lint（CI-07）未列入本提案：是否納入 Lint、採用的任務與門檻，待擁有者決定，
  本文件不預設（見 `docs/ci-validation-report.md` §2 CI-07）。

### 5.2 提案 B-2（可選）：`push` 觸發擴及專用分支

若擁有者希望「推送專用分支即自動觸發 CI」，須在瀏覽器中將
`build-apk.yml` 的 `push.branches` 由 `[main]` 改為包含專用分支
（例如 `[main, phase3-step3-review]`）。本提案**不建議**以「改觸發條件」
代替手動觸發的審查意義：自動觸發使「推送即 CI」成為預設，擁有者須自行
權衡。若不採納 B-2，路徑 A 的手動觸發已足夠取得 CI 編譯證據。

### 5.3 明確不提案的事項

- 不提案修復／新增 `gradlew`：現行 workflow 用 `setup-gradle` 8.13，
  wrapper jar 損壞不阻塞 CI；wrapper 是否修復屬待決定事項
  （見 `docs/ci-validation-report.md` §1.3），不得在本提案中順帶決定。
- 不提案改動 `attach-apk-to-release.yml`。
- 不提案任何 secrets／keystore 的新增、輪換或設定變更。
- 不提案版本號變更（維持 13／0.5.9）。

## 6. CI 將執行的工作與預期產物（現行 workflow，路徑 A）

| 項 | 內容 | 證據來源（執行後填寫） |
|---|---|---|
| CI-01 | Gradle 設定檢查（現行做法：setup-gradle 8.13，非 wrapper） | CI log |
| CI-02 | Temurin JDK 17 | CI log |
| CI-03 | runner 預裝 SDK（`ANDROID_SDK_ROOT`，cmdline-tools glob 取第一個） | CI log |
| CI-04 | 安裝 `platforms;android-36`、`build-tools;36.0.0`（含授權接受） | CI log |
| CI-05 | `gradle assembleSideloadDebug bundlePlayRelease --stacktrace` | CI log（成功／失敗） |
| CI-06 | 單元測試 | **現行 workflow 無此步驟：尚未執行**（除非採納 §5.1） |
| CI-07 | Lint | **現行 workflow 無此步驟：尚未執行** |
| CI-08 | sideload debug APK 產出（`app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`） | CI artifact |
| CI-09 | 2 個 artifact 上傳（APK＋Play AAB），保留 30 天 | CI artifact 清單 |
| CI-10 | 測試結果保存 | **現行 workflow 無此步驟：尚未執行**（除非採納 §5.1） |

預期產物（若 CI-05 通過）：`eyecontrol-sideload-debug-apk`（APK）、
`eyecontrol-play-release-aab`（AAB）。**產物存在 ≠ 可發布**：APK 須經實機安裝
測試（`docs/verification-ledger.md`「APK 安裝測試通過」級別）與實機驗證矩陣
全部完成後，才可進入發布判定；且 AAB 的 release 簽章取決於 secrets 設定狀態，
本任務未驗證（見 §1），CI log 須核對簽章步驟的實際行為，不得預設已正確簽署。

待確認（CI 執行後以 log 為準，不得預填）：

- `secrets.EYE_RELEASE_KEYSTORE_B64`／`EYE_RELEASE_KEY_ALIAS` 等是否已設定；
  若未設定，`bundlePlayRelease` 的實際簽章行為為何。
- `gradle/wrapper/gradle-wrapper.jar` 損壞是否影響現行 workflow（預期不影響，
  因 workflow 不走 wrapper；仍以 CI log 為準）。
- 新增的純 Kotlin 檔案（`GuardDecisions.kt`、`GestureDispatchGuard.kt` 等）
  與測試檔案在全量編譯下的相容性（harness 只驗純 Kotlin 子集，
  Android 框架接線未經編譯驗證）。

## 7. 風險評估與回滾方法

### 7.1 風險評估

| 風險 | 說明 | 緩解（本計畫內） |
|---|---|---|
| 誤推 `main` | 專用分支被誤認為可直接合併／推送到 `main`，使未驗證程式成為正式內容 | 本計畫明定只推專用分支；合併／PR 另需批准；分支名與 commit message 皆標「review／unverified」 |
| CI 紅燈被誤讀為「程式一定有錯」或反向誤讀 | CI 失敗可能源於環境／secrets／workflow 設定，非程式邏輯；CI 通過亦只證明編譯 | 以 CI log 逐項判定（§6 表）；CI 通過≠實機通過，重申於 commit message 與本文件首段 |
| 清單漂移 | 批准等待期間工作樹被寫入，61 檔清單失效 | §3.3 步驟 2 強制推送前重算比對；不一致即停下重提 |
| 分支名占用／覆蓋 | 提案分支名已存在，推送覆蓋他人工作 | 推送前在 GitHub 核對占用（§3.1）；已存在即改名重提 |
| Workflow 誤改 | 有人在推送過程中順帶改 workflow，觸發 403 或中斷 CI | 推送集不含 workflow 檔案（§2）；workflow 改動只走瀏覽器（§1.1／§5） |
| Secrets／簽章誤判 | 以「CI 產出 AAB」推定 release 簽章正確，或誤以為 secrets 已設定 | §6 待確認清單；本任務未讀取 secrets；CI log 核對為準 |
| 文件漂移 | `dcl-mapping.md` 部分細節表仍描述 STEP 3 前狀態（見工程報告 §2.4），審查者以舊文字判定新程式 | 工程報告 §2.4 明載漂移範圍；審查以程式碼＋step3-fix-log 為準，DCL 標籤不擅自重評 |
| 診斷數據誤讀 | 裝置診斷證據（臉率 12.2% 等）被當作已證實的程式缺陷，驅動未批准的修復 | 該證據僅供參考、未經程式交叉驗證（見工程報告 §8）；P-DEV-1 仍為提案，不得擅自實作 |

### 7.2 回滾方法

- **推送前回滾**：不推送即無須回滾。本文件與工程報告皆為工作樹內文件，
  刪除或不採納即撤回提案；遠端不受任何影響。
- **推送後、合併前回滾**：刪除遠端專用分支 `phase3-step3-review`
  （GitHub 網頁操作，擁有者執行）。`main`（HEAD `c6b80964`）全程未動，
  無須 revert、無須 force-push。本地工作樹不受分支刪除影響。
- **CI 失敗回滾**：不刪除分支；在同一專用分支上以 follow-up commit 修正
  （修正內容另需審查；不得為求通過刪功能、降標準、跳過測試），重新觸發 CI。
  若修正涉及 workflow，仍走瀏覽器路徑（§1.1）。
- **誤推 `main` 的處置（假設性，不得預演）**：立即停下、通知擁有者，
  由擁有者在 GitHub 上處置（本計畫不授權任何 force-push／history 改寫；
  正式版本標籤更動另需批准）。本計畫的全部步驟皆以「不推 main」為前提，
  此條僅為事故通報原則，非操作授權。

## 8. 授權狀態

| 欄位 | 狀態 |
|---|---|
| 推送授權 | **未授權**（等待擁有者批准） |
| 分支建立 | 未執行 |
| PR 建立 | 未執行（亦不在本計畫授權範圍內） |
| Release／APK 上傳 | 未執行（禁止發布，見 `docs/release-readiness.md`） |
| Workflow 修改 | 未執行（提案見 §5，須走瀏覽器，另需決定） |
| CI 執行 | 尚未執行（全部 CI 項目前狀態皆為「尚未執行」，見 `docs/ci-validation-report.md` §2） |

## 9. 引用證據清單

- 遠端 HEAD 核實：GitHub `list_commits`（`sha=main`，2026-11-23）。
- 遠端樹走訪：GitHub `get_file_contents`（`ref=main`）遞迴目錄走訪，65 檔。
- 本機 blob SHA：`git hash-object` 逐檔計算；16 異動檔遠端內容取回後 SHA
  再驗 16／16 吻合。
- Workflow 內容：`~/workspace/eyecontrol-p3/.github/workflows/build-apk.yml`、
  `attach-apk-to-release.yml`（唯讀）。
- 版本：`app/build.gradle.kts:29-30`（本機與遠端一致）。
- 限制來源：`~/AGENTS.md` Lessons（workflows 403、setup-android 失敗、wrapper jar
  損壞）；`docs/ci-validation-report.md` §1–§5；`docs/release-readiness.md` §0；
  `docs/risk-register.md` R9／R20；`docs/step3-fix-log.md` §5（禁區核對）。
- 測試與實機清單：`~/workspace/eyecontrol-phase3/TEST_PLAN.md` §1.4／§3；
  `docs/device-acceptance-matrix.md`；`docs/verification-ledger.md` §0.1–§0.2。
