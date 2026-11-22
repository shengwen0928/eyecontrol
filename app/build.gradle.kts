plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Base64
import org.gradle.api.GradleException

// 固定 debug 簽名：CI 每次編譯都用同一支鑰匙簽名，之後 App 內更新才能直接覆蓋安裝，
// 不必刪除重裝。鑰匙是公開的標準 debug 憑證（android/android），不是正式發行鑰匙，
// 以 base64 文字檔形式放在 repo 裡（推送工具只收文字檔），編譯時在這裡解回 PKCS12 檔。
// 解出的 debug.keystore 已被 .gitignore 排除，不會進版本控管。
val debugKeystoreFile = file("debug.keystore")
val debugKeystoreB64File = file("debug-keystore.b64")
if (!debugKeystoreFile.exists() && debugKeystoreB64File.exists()) {
    debugKeystoreFile.writeBytes(
        Base64.getMimeDecoder().decode(debugKeystoreB64File.readText())
    )
}

android {
    namespace = "com.example.eyecontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.com.zhu.eyecontrol"   // Play 上架識別碼（namespace 維持原程式碼包名即可）
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.5.7"
    }

    // 發行通路：sideload（GitHub release 直裝，App 內自己查 GitHub 更新）與
    // play（Play 商店版：Play 政策禁止 App 自己下載 APK 更新，改跳 Play 商店更新，
    // 且不宣告 INTERNET／REQUEST_INSTALL_PACKAGES，見 src/play/AndroidManifest.xml）。
    // 兩版共用同一個 applicationId，簽名不同（debug 固定鑰匙 vs release 鑰匙），
    // 互換通路安裝時須先刪除舊版。
    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
            buildConfigField("boolean", "REMOTE_MONITOR_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
            // Play 版不宣告 INTERNET，遠端監控無法上傳，設定頁不顯示該開關。
            buildConfigField("boolean", "REMOTE_MONITOR_ENABLED", "false")
        }
    }

    // 固定 debug 簽名（見檔案上方說明）：覆蓋安裝與 App 內更新都靠同一支簽名才能成立。
    // Play 商店版簽名：release 鑰匙絕不進 repo。CI 從 GitHub secret 解出鑰匙檔
    //（路徑由 EYE_RELEASE_KEYSTORE_FILE 傳入），本機編 Play 版時把鑰匙檔放 app/
    // 目錄命名為 release.keystore 即可。沒有鑰匙時不設定 storeFile，Play release
    // 打包會在執行期報錯，不影響其他 variant 的編譯。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            storeType = "PKCS12"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystoreFile = System.getenv("EYE_RELEASE_KEYSTORE_FILE")
                ?.let { file(it) }?.takeIf { it.exists() }
                ?: file("release.keystore").takeIf { it.exists() }
            if (keystoreFile != null) {
                // 鑰匙在、密碼缺：設定期就明確報錯，不要拖到打包執行期才以難懂的簽名錯誤爆開。
                val storePass = System.getenv("EYE_RELEASE_STORE_PASSWORD")
                val keyPass = System.getenv("EYE_RELEASE_KEY_PASSWORD")
                if (storePass == null || keyPass == null) {
                    throw GradleException(
                        "release keystore 已設定（${keystoreFile.path}）但缺少 " +
                            "EYE_RELEASE_STORE_PASSWORD / EYE_RELEASE_KEY_PASSWORD 環境變數",
                    )
                }
                storeFile = keystoreFile
                storePassword = storePass
                storeType = System.getenv("EYE_RELEASE_STORE_TYPE") ?: "PKCS12"
                keyAlias = System.getenv("EYE_RELEASE_KEY_ALIAS") ?: "eyecontrol-release"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        // UpdateChecker / MainActivity 用 BuildConfig.VERSION_NAME 比對版本；
        // AGP 8 預設不產生 BuildConfig，一定要開。
        buildConfig = true
    }
    // MediaPipe 的 .task 模型是壓縮格式，打包時不可再壓縮，否則載入失敗。
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    // CameraX：前鏡頭串流（1.4.2+ 的原生庫已對齊 16KB 分頁）
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe Tasks Vision：Face Landmarker（含虹膜）；0.10.26 起原生庫以 16KB 分頁對齊編譯
    implementation("com.google.mediapipe:tasks-vision:0.10.26")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
