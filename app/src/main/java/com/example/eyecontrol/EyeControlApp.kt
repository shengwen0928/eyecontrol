package com.example.eyecontrol

import android.app.Application

/** 應用程式進入點：只負責安裝閃退紀錄器，不做其他初始化。 */
class EyeControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
