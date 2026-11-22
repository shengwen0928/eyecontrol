plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Base64

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
        versionCode = 5
        versionName = "0.5.1"
    }

    // 固定 debug 簽名（見檔案上方說明）：覆蓋安裝與 App 內更新都靠同一支簽名才能成立。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            storeType = "PKCS12"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
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
