package com.example.eyecontrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.eyecontrol.databinding.ActivityWelcomeBinding

/**
 * 歡迎頁：只在首次啟動顯示。
 * 「開始設定」進引導；「先看看主畫面」同樣記下已看過，之後可在主畫面或設定裡重新執行引導。
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
