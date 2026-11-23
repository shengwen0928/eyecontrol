package com.example.eyecontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.eyecontrol.databinding.ActivityWelcomeBinding

/**
 * 歡迎頁：只在首次啟動顯示。
 * 「開始設定」進引導（共 6 步）；「先看看主畫面」只記下已看過、維持延期語義，
 * 之後可以在設定頁重新執行引導（設定頁「重新執行設定引導」列）。
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // K-12 殘餘（第 4 步遺留 (e)）：側載版依 flavor 修為準確文字（與
        // docs/privacy.html 例外段一致：全臉影像永不儲存、不離開裝置，調校版
        // 另會上傳診斷數字與眼區驗證裁切）；Play 版維持版面原文字、原意不動。
        // 只改文字，不改流程、跳轉與版面幾何。
        if (BuildConfig.REMOTE_MONITOR_ENABLED) {
            binding.welcomeCameraNote.text =
                "只在手機上即時看眼睛位置，全臉影像不儲存；調校版另會留存與上傳眼區驗證裁切。"
            binding.welcomePrivacyNote.text =
                "隱私說明：全臉影像永不儲存、永不離開這支手機；校正資料只留在手機。" +
                    "調校版（GitHub 側載版）另會上傳診斷數字與眼區驗證裁切，正式上架前移除；" +
                    "更新檢查會連線 GitHub 查詢新版本。"
        }

        binding.btnWelcomeStart.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
            finish()
        }
        binding.btnWelcomeLater.setOnClickListener {
            markWelcomeSeen(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    companion object {
        private const val PREFS_NAME = "eyecontrol_setup"
        private const val KEY_WELCOME_SEEN = "welcome_seen"

        fun isFirstRun(context: Context): Boolean =
            !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WELCOME_SEEN, false)

        fun markWelcomeSeen(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WELCOME_SEEN, true).apply()
        }
    }
}
