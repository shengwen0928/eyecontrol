# EyeControl Phase 3 — CI 驗證報告（CI Validation Report）

> 性質：本地分析與準備文件，**只新增文件，不改任何現有檔案、不改版本號、不改 `.github/workflows/`、不推送、不 commit、不發版**。
> 撰寫日期：2026-11-23。工作樹：`~/workspace/eyecontrol-p3`（repo 複本，無 `.git`，程式碼一律唯讀）。
> 基線：v0.5.9，`versionCode = 13`／`versionName = "0.5.9"`（`app/build.gradle.kts:29-30`）。
> 本文件所有 CI 檢查項目的狀態一律為「尚未執行」。本文件不宣稱任何 CI 已執行、已通過或已成功。

## 0. 邊界與誠實聲明

- 本文件只記錄 CI 應檢查的事項與現況證據。**沒有授權就停止於本地分析與準備階段**：推送與發版另需所有權人核准（見 §5）。
- 「環境受限」不得寫成「成功」。本機 Gradle 永遠跑不起來，不等於 CI 已驗證；kotlinc harness 的通過數字不得冒充 CI 結果（見 §1）。
- 本文件未讀取任何密鑰值。簽章機制只記錄公開的設定方式（見 §3），不含任何密鑰、密碼或憑證內容。

## 1. 現況證據（為什麼 CI 驗證仍是未完成狀態）

### 1.1 本機 Gradle 無法執行（已實證）

- 現象與根因：`gradle tasks`／`gradle help`／`gradle --stop` 皆以 `Could not receive a message from the daemon` 失敗；JVM 發出的 loopback TCP 連線被沙箱攔截，Gradle 必須經 daemon 行程通訊，故在此 VM 上永遠跑不起來。已試過皆無效：隔離 `GRADLE_USER_HOME`、heap 降至 512MB、清 daemon registry、重啟 daemon、sdkmanager 代理參數（來源：`~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md` §0）。
- 後果：`gradle testSideloadDebugUnitTest` 與 `gradle assembleSideloadDebug` **未在本機驗證**；全量編譯留待所有權人核准推送後由 CI 驗證；任何回報皆不得宣稱本機已驗證建置（同上，§0「後果」段）。

### 1.2 本機 harness 的通過範圍（不得冒充 CI）

- 本機 gate 改走 kotlinc＋JUnitCore harness，只驗純 Kotlin 類別：最終全量輸出 `COMPILE_OK`、`OK (110 tests)`，7 個測試類別（來源：`~/workspace/eyecontrol-phase3/TEST_RESULTS.md` §1、§2）。
- 限制：Android 框架類別（服務接線、設定頁、Overlay 繪製、`CalibrationActivity`）只經靜態核對，**未經編譯驗證**；harness 以 JUnitCore 文字輸出計數，不用 Gradle 的 XML 測試報告；CI 上的 XML 計數待推送後以 CI 報告為準，不得以本機數字冒充 CI 結果（來源：`TEST_RESULTS.md` §2「XML 計數說明」、§3）。
- 測試依賴現況：`app/build.gradle.kts:135` 已有 `testImplementation("junit:junit:4.13.2")`；`app/src/test/` 下已有 7 個測試類別檔案（撰寫時以檔案清單查證）。測試是否存在，不等於測試已在 CI 執行。

### 1.3 Gradle Wrapper 與 CI 現況（存在待確認的落差）

- repo 無 `gradlew`；`gradle/wrapper/` 只有 jar＋properties，且該 jar 在 runner 上零輸出 exit 1（來源：`~/workspace/eyecontrol-phase3/TEST_PLAN.md` §0.3；`~/AGENTS.md`「Lessons」教訓段亦載明同一事實）。
- 現行 workflow 不走 wrapper：`.github/workflows/build-apk.yml` 使用 `gradle/actions/setup-gradle@v4` 指定 `gradle-version: "8.13"`，直接呼叫 `gradle` 指令（見 §2 檢查項 CI-01 的備註）。**落差待確認**：本文件 §4 要求 CI 使用正式專案的 Gradle Wrapper 與建置設定，但現況是 wrapper jar 已知損壞、workflow 現行做法為 setup-gradle。wrapper 是否修復、修復是否納入 CI 工作，屬待決定事項，本文件不代決，且本組不得修改 `.github/workflows/` 下的任何檔案。
- 現行 CI 只有建置步驟：`build-apk.yml` 只有 checkout → JDK 17 → SDK packages → Gradle 8.13 → 建置 → 上傳 2 個 artifact；`attach-apk-to-release.yml` 只負責掛附件；**兩個 workflow 皆無測試步驟**，且 `.github/workflows/` 下的檔案絕對不能改（來源：`TEST_PLAN.md` §0.2）。因此在 workflow 更新前，CI 永遠不會跑本計畫的測試；本計畫的自動化測試 gate 一律為「本機 Gradle 執行」，不得宣稱為 CI 驗證（同上）。

## 2. CI 檢查清單（全部狀態：尚未執行）

下表 10 項皆為本階段要求的 CI 檢查項目。**每一項的狀態一律為「尚未執行」，不得寫成功。**「證據」欄在本文件撰寫時全部留空，待 CI 實際執行後以 CI 執行紀錄（log 連結、run 編號、產物）填寫，不得以本機 harness 輸出、靜態閱讀或推論填寫。

| 編號 | 檢查項目 | 預期做法（以正式專案設定為準） | 狀態 | 證據（待 CI 執行後填寫） |
|---|---|---|---|---|
| CI-01 | 檢查 Gradle Wrapper | 確認正式專案的 Gradle Wrapper 設定完整可用（wrapper properties、發行版、校驗設定），CI 執行時使用該 Wrapper 與建置設定。現況落差見 §1.3：repo 無 `gradlew`、wrapper jar 已知損壞，現行 workflow 改用 setup-gradle 8.13。檢查結果以實際檢查紀錄為準，不得預設 wrapper 可用。 | 尚未執行 | （留空） |
| CI-02 | 設定 JDK | 依 workflow 現行設定使用 Temurin JDK 17（`actions/setup-java@v4`，`java-version: "17"`，見 `build-apk.yml`）。實際採用的 JDK 發行版與版本以 CI log 為準。 | 尚未執行 | （留空） |
| CI-03 | 設定 Android SDK | 使用 runner 預裝的 Android SDK（`ANDROID_SDK_ROOT`，預設 `/usr/local/lib/android/sdk`），cmdline-tools 目錄名可能是版本號而非 `latest`，以 glob 取第一個（見 `build-apk.yml`「Install SDK packages」步驟；`~/AGENTS.md` 亦載明 `android-actions/setup-android` 在此 runner 上會失敗，不得改用它）。 | 尚未執行 | （留空） |
| CI-04 | 安裝必要 SDK 套件 | 接受授權（`yes \| sdkmanager --licenses`）並安裝 `platforms;android-36` 與 `build-tools;36.0.0`（見 `build-apk.yml`）。實際安裝的套件清單以 CI log 為準。 | 尚未執行 | （留空） |
| CI-05 | 執行 Gradle 編譯 | 執行 workflow 的建置步驟：`gradle assembleSideloadDebug bundlePlayRelease --stacktrace`（建置前 `rm -f local.properties`，見 `build-apk.yml`）。編譯結果以 CI log 的成功／失敗為準，本機 harness 結果不得代替。 | 尚未執行 | （留空） |
| CI-06 | 執行可用單元測試 | 執行 `gradle testSideloadDebugUnitTest`（`TEST_PLAN.md` §1.4），以 `app/build/test-results/testSideloadDebugUnitTest/` 的 XML 計數（`failures`／`errors`）為準。現況：workflow 無測試步驟（見 §1.3），測試是否在 CI 執行取決於 workflow 更新，本組不得改 workflow，是否更新、何時更新待所有權人決定。 | 尚未執行 | （留空） |
| CI-07 | 執行 Lint | 執行 Android Lint 並保存報告。現況：兩個 workflow 皆無 Lint 步驟；Lint 是否納入 CI、採用的任務與門檻，待所有權人決定，本文件不預設。本項狀態為尚未執行，不得以「無 Lint 步驟」寫成「Lint 通過」。 | 尚未執行 | （留空） |
| CI-08 | 執行 APK 建置 | 驗證 sideload debug APK 產物確實產出，路徑為 `app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`（見 `build-apk.yml`「Upload APK」步驟）。產物存在與否以 CI artifact 紀錄為準。 | 尚未執行 | （留空） |
| CI-09 | 儲存建置產物 | 依 workflow 上傳 2 個 artifact：`eyecontrol-sideload-debug-apk`（APK）與 `eyecontrol-play-release-aab`（AAB，路徑 `app/build/outputs/bundle/playRelease/app-play-release.aab`），`if-no-files-found: error`，保留 30 天（見 `build-apk.yml`）。實際上傳紀錄以 CI artifact 清單為準。 | 尚未執行 | （留空） |
| CI-10 | 保存測試結果 | 保存單元測試結果（XML 計數與 HTML 報告，路徑見 `TEST_PLAN.md` §1.4：`app/build/test-results/testSideloadDebugUnitTest/` 與 `app/build/reports/tests/testSideloadDebugUnitTest/`）。現況：workflow 無保存測試結果的步驟；保存方式待 workflow 更新時一併決定。本項狀態為尚未執行。 | 尚未執行 | （留空） |

## 3. 簽章機制（如實記錄，不讀取密鑰值）

本節只記錄機制，**未讀取、未記錄任何密鑰值、密碼或憑證內容**。以下事實皆來自公開可讀的建置設定與 workflow 檔案。

### 3.1 固定 debug keystore（僅用於側載 debug 版）

- 機制：`app/build.gradle.kts:14-18` 在編譯時將 repo 內的 `debug-keystore.b64`（base64 文字檔）解回 `debug.keystore`（PKCS12）；debug signing 設定為 `storePassword = "android"`、`keyAlias = "androiddebugkey"`、`keyPassword = "android"`（`app/build.gradle.kts:60-64` 附近，見該檔 signingConfigs 段）。檔案頭註解明載：鑰匙是公開的標準 debug 憑證（android/android），不是正式發行鑰匙；固定簽名是為了讓 App 內更新可直接覆蓋安裝、不必刪除重裝。
- 適用範圍：**僅用於側載 debug 版**。不得用固定 debug keystore 充當正式發布簽章（見 §4）。

### 3.2 Release AAB 簽章（GitHub secrets 鑰匙）

- 機制：release signing 設定只在鑰匙檔存在時才設定 `storeFile`；鑰匙路徑由環境變數 `EYE_RELEASE_KEYSTORE_FILE` 傳入，否則取本機 `app/release.keystore`（`app/build.gradle.kts:67-83`，含 `EYE_RELEASE_STORE_PASSWORD`／`EYE_RELEASE_KEY_PASSWORD`／`EYE_RELEASE_KEY_ALIAS` 環境變數，預設 alias 為 `eyecontrol-release`）。檔案頭註解明載：Play 商店版簽名鑰匙絕不進 repo。
- CI 側：`build-apk.yml`「Restore release keystore」步驟僅在 `secrets.EYE_RELEASE_KEYSTORE_B64` 非空時，將其 base64 解出至 `$RUNNER_TEMP/release.keystore`，並寫入 `EYE_RELEASE_KEYSTORE_FILE` 環境變數；步驟註解明載「絕不印出、不進 repo」。另有「Show Play AAB signing certificate」步驟以 `keytool -printcert -jarfile` 顯示 AAB 簽章憑證。
- 本文件未讀取 secrets 的值，也未驗證 secrets 是否已設定。secrets 的設定狀態屬待確認事項，不得預設已設定或未設定。

### 3.3 簽章紅線

- 固定 debug keystore 不得充當正式發布簽章；release AAB 必須使用 GitHub secrets 鑰匙機制（§3.2）。
- 任何 CI 執行紀錄、log 或報告中不得出現密鑰值、密碼或 base64 鑰匙內容。

## 4. CI 執行原則

1. **使用正式專案的 Gradle Wrapper 與建置設定**：CI 必須以正式專案的設定執行，不得以本機替代工具鏈（kotlinc harness、隔離 `GRADLE_USER_HOME`、curl 下載平台包等本機變通做法）冒充 CI。現況落差（wrapper jar 損壞、workflow 現行做法為 setup-gradle）見 §1.3，修復方式與責任歸屬待決定，不得擅自改 workflow。
2. **不得為求通過刪功能或降標準**：不得刪除功能、放寬門檻、跳過測試或 Lint、改用較寬鬆的建置變體，只為讓 CI 變綠。門檻待校準項（dispersion、穩定序列幀數、邊緣連續 present 幀數、方向死區、邊緣帶上限比例、確認取消寬限）維持 `UNCALIBRATED`、預設不阻擋的現況，不得以未驗證數字充當已校準值（來源：`IMPLEMENTATION_NOTES.md` §1 出入第 29 點、§3）。
3. **不得把「環境受限」寫成「成功」**：本機 Gradle 不可用是環境限制，不是驗證結果；harness 110 項全過只證明純 Kotlin 類別在本機 harness 下通過，不證明全量編譯、Android 框架接線或 CI 執行成功。
4. **CI 通過不等於發布通過**：CI 檢查全部通過後，狀態最多升級為「待實機驗證」，仍須實機驗證矩陣的證據（見 `device-acceptance-matrix.md`）與發布準備判定（見 `release-readiness.md`）。CI 通過本身不是發布許可。
5. **Workflow 修改權限**：`.github/workflows/` 下的檔案本組絕對不能改（GitHub App 無 workflows 權限，須另走瀏覽器＋使用者登入工作階段；來源：`~/AGENTS.md`「Lessons」第一條；`TEST_PLAN.md` §0.2）。任何 workflow 變更需求只記錄於此，不得在本工作樹內實作。

## 5. 推送授權提案格式（全部欄位待填寫，不得照抄）

以下為推送授權提案的**格式範本**，不是已填寫的提案。本文件不預填其中任何實質內容：**預計推送分支、變更檔案清單、變更摘要、風險評估、隱私影響、CI 工作、預期產物、回滾方法，全部為「待填寫」**。變更檔案清單須以 A 組親自以 blob SHA 逐檔複驗為前提，寫明「待 A 組複驗確認」，不得照抄任何檔案數量、檔案清單或摘要數字（包括先前文件中記載的異動／新增檔案計數，本文件一律不轉載）。

| 欄位 | 內容（本文件撰寫時） |
|---|---|
| 預計推送分支 | 待填寫（待 A 組複驗確認） |
| 變更檔案清單（逐檔 blob SHA） | 待填寫（待 A 組複驗確認：須由 A 組親自以 blob SHA 逐檔複驗遠端 main HEAD 後填寫，本文件不預填、不轉載任何清單或計數） |
| 變更摘要 | 待填寫（待 A 組複驗確認） |
| 風險評估 | 待填寫（待 A 組複驗確認） |
| 隱私影響 | 待填寫（待 A 組複驗確認） |
| CI 工作（本文件 §2 清單的執行結果） | 待填寫（全部 CI 項目前狀態皆為「尚未執行」，見 §2；CI 執行後以 CI 紀錄填寫） |
| 預期產物 | 待填寫（待 A 組複驗確認） |
| 回滾方法 | 待填寫（待 A 組複驗確認） |
| 授權狀態 | 未授權 |

**沒有授權就停止於本地分析與準備階段。** 在所有權人明確授權之前，不得推送、不得 commit、不得發版。本文件本身不是授權，也不得被解讀為授權。

## 6. 引用證據清單

- `~/workspace/eyecontrol-phase3/IMPLEMENTATION_NOTES.md` §0（本機 Gradle 限制）、§1 出入第 27／29 點、§3（已知缺口）、§4（未推送、未 commit、未發版）。
- `~/workspace/eyecontrol-phase3/TEST_RESULTS.md` §1–§3（harness 範圍與限制、全量編譯未驗證）。
- `~/workspace/eyecontrol-phase3/TEST_PLAN.md` §0.2–§0.3（CI 只有建置步驟、無 gradlew、wrapper jar 損壞）、§1.4（本機執行指令與報告位置）。
- `~/workspace/eyecontrol-phase3/PLAN.md` §0.2（不改版本號、不推送、不 commit）、§6（D-4…D-14 不代決）。
- `~/workspace/eyecontrol-p3/.github/workflows/build-apk.yml`（JDK／SDK／Gradle／簽章／產物步驟全文）。
- `~/workspace/eyecontrol-p3/.github/workflows/attach-apk-to-release.yml`（僅掛附件，無測試步驟）。
- `~/workspace/eyecontrol-p3/app/build.gradle.kts:29-30`（版本）、`:14-18` 與 signingConfigs 段（簽章機制）、`:135`（測試依賴）。
- `~/AGENTS.md`「Lessons」（GitHub App 無 workflows 權限；setup-android 失敗教訓；wrapper jar 損壞教訓）。
