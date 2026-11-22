package com.example.eyecontrol

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eyecontrol.databinding.ActivitySettingsBinding
import org.json.JSONObject

/**
 * 可調參數設定頁：9 分組，每項一句白話說明。
 * 滑桿即時顯示數值，按「儲存」寫入 SharedPreferences；無障礙服務運作中也會透過
 * 偏好監聽即時套用，不必重啟服務。滑桿範圍與步進和 GazeSettings 的允許範圍一致，
 * 換算函數集中在此。
 *
 * 本頁不新增任何設定 key：全部控制項只接 GazeSettings 現有 key。
 * 沒有對應功能的項目（例如清除全部校正資料、游標大小、更新檢查開關）一律不顯示，
 * 不做點了沒反應的假控制項。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val s = GazeSettings.load(this)

        setupSlider(binding.seekDwell, binding.valDwell, MAX_DWELL_PROGRESS, progressForDwell(s.dwellTimeMs)) {
            "${dwellFromProgress(it)} 毫秒"
        }
        setupSlider(binding.seekCooldown, binding.valCooldown, MAX_COOLDOWN_PROGRESS, progressForCooldown(s.cooldownMs)) {
            "${cooldownFromProgress(it)} 毫秒"
        }
        setupSlider(binding.seekRadius, binding.valRadius, MAX_RADIUS_PROGRESS, progressForRadius(s.dwellRadiusPx)) {
            "${radiusFromProgress(it)} 像素"
        }
        setupSlider(binding.seekBlinkMin, binding.valBlinkMin, MAX_BLINK_MIN_PROGRESS, progressForBlinkMin(s.blinkMinMs)) {
            "${blinkMinFromProgress(it)} 毫秒"
        }
        setupSlider(binding.seekBlinkMax, binding.valBlinkMax, MAX_BLINK_MAX_PROGRESS, progressForBlinkMax(s.blinkMaxMs)) {
            "${blinkMaxFromProgress(it)} 毫秒"
        }
        setupSlider(binding.seekThreshold, binding.valThreshold, MAX_THRESHOLD_PROGRESS, progressForThreshold(s.blinkThreshold)) {
            "%.2f".format(thresholdFromProgress(it))
        }
        setupSlider(binding.seekCutoff, binding.valCutoff, MAX_CUTOFF_PROGRESS, progressForCutoff(s.filterMinCutoff)) {
            "%.2f".format(cutoffFromProgress(it))
        }
        setupSlider(binding.seekBeta, binding.valBeta, MAX_BETA_PROGRESS, progressForBeta(s.filterBeta)) {
            "%.3f".format(betaFromProgress(it))
        }

        binding.switchGyro.isChecked = s.gyroCompensation
        binding.switchDistance.isChecked = s.distanceCompensation
        binding.switchCondition.isChecked = s.conditionCorrection

        // 開關整列可點：點列身切換開關，點開關本身由開關自己處理，不會重複觸發。
        binding.rowCondition.setOnClickListener { binding.switchCondition.toggle() }
        binding.rowDistance.setOnClickListener { binding.switchDistance.toggle() }
        binding.rowGyro.setOnClickListener { binding.switchGyro.toggle() }

        // 基本：情境切換（點一下換下一個，三個情境循環）。
        binding.rowProfile.setOnClickListener {
            val ids = CalibrationStore.PROFILES.map { it.id }
            val next = ids[(ids.indexOf(CalibrationStore.getActiveProfileId(this)) + 1) % ids.size]
            CalibrationStore.setActiveProfileId(this, next)
            refreshProfileViews()
            Toast.makeText(this, "已切換為「${CalibrationStore.profileName(next)}」", Toast.LENGTH_SHORT).show()
        }

        // 校正管理：重校目前情境（走與主畫面相同的相機權限檢查）。
        binding.rowRecalibrate.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, CalibrationActivity::class.java)
                intent.putExtra(
                    CalibrationActivity.EXTRA_PROFILE_ID,
                    CalibrationStore.getActiveProfileId(this),
                )
                startActivity(intent)
            }
        }

        // 操作輔助：重新執行引導、系統無障礙設定。
        binding.rowWizard.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        // 校正管理：操作練習（與重校正相同的相機權限檢查；無模型時練習頁導引去校正）。
        binding.rowTraining.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, TrainingActivity::class.java))
            }
        }
        binding.rowA11ySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "請在清單中找到「凝視」", Toast.LENGTH_LONG).show()
        }

        // 無障礙：系統顯示與字體大小設定。
        binding.rowSystemDisplay.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
                .onFailure {
                    Toast.makeText(this, "無法開啟系統顯示設定", Toast.LENGTH_SHORT).show()
                }
        }

        // 隱私：如實說明。Play 商店版沒有更新檢查、不連網；側載版的更新檢查
        // 會連線 api.github.com 查詢 releases，兩個版本說法分開，不可混寫。
        binding.rowPrivacy.setOnClickListener {
            val updateText = if (BuildConfig.SELF_UPDATE_ENABLED) {
                "唯一會連網的功能是「更新檢查」：App 啟動時向 api.github.com 查詢最新 release 版本，不傳送任何個人資料。"
            } else {
                "Play 商店版沒有「更新檢查」連線：更新一律走 Play 商店，本 App 平時不連網。"
            }
            AlertDialog.Builder(this)
                .setTitle("隱私說明")
                .setMessage(
                    "影像只在手機上即時處理，不儲存、不上傳；校正資料只有數學權重數值，存在手機本機。\n\n" +
                        updateText,
                )
                .setPositiveButton("知道了", null)
                .show()
        }

        // 系統資訊：關於。
        binding.rowAbout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("關於凝視")
                .setMessage(
                    "讓視線，成為自由操作的力量。\n\n" +
                        "凝視是開源的 Android 無障礙工具，用眼睛移動游標、凝視停留或眨眼來操作手機，給無法用手操作的人。\n\n" +
                        "授權：MIT License。原始碼公開於 GitHub（shengwen0928/eyecontrol）。",
                )
                .setPositiveButton("知道了", null)
                .show()
        }

        // 系統資訊：Play 商店版才顯示「到 Play 商店檢查更新」列
        //（側載版走 App 內更新檢查，不顯示此列）。
        binding.rowStoreUpdate.visibility =
            if (BuildConfig.SELF_UPDATE_ENABLED) View.GONE else View.VISIBLE
        binding.rowStoreUpdate.setOnClickListener {
            UpdateChecker.openStoreListing(this)
        }

        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnDefaults.setOnClickListener { resetToDefaults() }

        refreshProfileViews()
        binding.valVersion.text = BuildConfig.VERSION_NAME
        binding.valModelVersion.text = storedModelVersionText()
    }

    override fun onResume() {
        super.onResume()
        // 從校正頁回來時，情境狀態與模型版本要跟著更新。
        refreshProfileViews()
        binding.valModelVersion.text = storedModelVersionText()
    }

    private fun refreshProfileViews() {
        binding.valProfile.text =
            CalibrationStore.profileName(CalibrationStore.getActiveProfileId(this))
        binding.valProfileStates.text =
            CalibrationStore.PROFILES.joinToString("\n") { profile ->
                val state = if (CalibrationStore.exists(this, profile.id)) {
                    val err = CalibrationStore.getMeta(this, profile.id)?.looErrorPx
                        ?.let { "（留一法誤差 ${"%.0f".format(it)} px）" } ?: ""
                    "已校正$err"
                } else {
                    "未校正"
                }
                "${profile.name}：$state"
            }
    }

    /**
     * 已存模型的格式版本：直接讀該情境存檔 JSON 的 version 欄位。
     * GazeModel.MODEL_VERSION 是私有常數（GazeModel 本批不動），此處不硬編版本號；
     * 模型格式升級時，顯示會跟著存檔內容走。沒有有效模型時顯示「尚未校正」。
     */
    private fun storedModelVersionText(): String {
        val activeId = CalibrationStore.getActiveProfileId(this)
        if (CalibrationStore.loadModel(this, activeId) == null) return "尚未校正"
        // key 格式與 CalibrationStore 內部的 "model_<id>" 一致。
        val raw = CalibrationStore.prefs(this).getString("model_$activeId", null)
            ?: return "未知"
        val version = runCatching { JSONObject(raw).optInt("version", 0) }.getOrDefault(0)
        return if (version > 0) "v$version" else "未知"
    }

    private fun setupSlider(
        seekBar: SeekBar,
        valueLabel: TextView,
        max: Int,
        initial: Int,
        format: (Int) -> String,
    ) {
        seekBar.max = max
        seekBar.progress = initial.coerceIn(0, max)
        valueLabel.text = format(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                valueLabel.text = format(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
    }

    private fun saveAndFinish() {
        val s = GazeSettings(
            dwellTimeMs = dwellFromProgress(binding.seekDwell.progress),
            cooldownMs = cooldownFromProgress(binding.seekCooldown.progress),
            dwellRadiusPx = radiusFromProgress(binding.seekRadius.progress),
            blinkMinMs = blinkMinFromProgress(binding.seekBlinkMin.progress),
            blinkMaxMs = blinkMaxFromProgress(binding.seekBlinkMax.progress),
            blinkThreshold = thresholdFromProgress(binding.seekThreshold.progress),
            filterMinCutoff = cutoffFromProgress(binding.seekCutoff.progress),
            filterBeta = betaFromProgress(binding.seekBeta.progress),
            gyroCompensation = binding.switchGyro.isChecked,
            distanceCompensation = binding.switchDistance.isChecked,
            conditionCorrection = binding.switchCondition.isChecked,
        ).clamped()
        s.save(this)
        Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun resetToDefaults() {
        val d = GazeSettings()
        binding.seekDwell.progress = progressForDwell(d.dwellTimeMs)
        binding.seekCooldown.progress = progressForCooldown(d.cooldownMs)
        binding.seekRadius.progress = progressForRadius(d.dwellRadiusPx)
        binding.seekBlinkMin.progress = progressForBlinkMin(d.blinkMinMs)
        binding.seekBlinkMax.progress = progressForBlinkMax(d.blinkMaxMs)
        binding.seekThreshold.progress = progressForThreshold(d.blinkThreshold)
        binding.seekCutoff.progress = progressForCutoff(d.filterMinCutoff)
        binding.seekBeta.progress = progressForBeta(d.filterBeta)
        binding.switchGyro.isChecked = d.gyroCompensation
        binding.switchDistance.isChecked = d.distanceCompensation
        binding.switchCondition.isChecked = d.conditionCorrection
        Toast.makeText(this, "已恢復預設值（按儲存才生效）", Toast.LENGTH_SHORT).show()
    }

    // 滑桿進度 ↔ 參數值的換算（範圍與 GazeSettings 一致）

    private fun dwellFromProgress(p: Int) = 400L + p * 50L
    private fun progressForDwell(v: Long) = ((v - 400L) / 50L).toInt()

    private fun cooldownFromProgress(p: Int) = 500L + p * 100L
    private fun progressForCooldown(v: Long) = ((v - 500L) / 100L).toInt()

    private fun radiusFromProgress(p: Int) = 60f + p * 5f
    private fun progressForRadius(v: Float) = ((v - 60f) / 5f).toInt()

    private fun blinkMinFromProgress(p: Int) = 80L + p * 10L
    private fun progressForBlinkMin(v: Long) = ((v - 80L) / 10L).toInt()

    private fun blinkMaxFromProgress(p: Int) = 300L + p * 50L
    private fun progressForBlinkMax(v: Long) = ((v - 300L) / 50L).toInt()

    private fun thresholdFromProgress(p: Int) = 0.04 + p * 0.01
    private fun progressForThreshold(v: Double) = ((v - 0.04) / 0.01).toInt()

    private fun cutoffFromProgress(p: Int) = 0.05 + p * 0.01
    private fun progressForCutoff(v: Double) = ((v - 0.05) / 0.01).toInt()

    private fun betaFromProgress(p: Int) = 0.005 + p * 0.001
    private fun progressForBeta(v: Double) = ((v - 0.005) / 0.001).toInt()

    companion object {
        private const val MAX_DWELL_PROGRESS = 42 // (2500 - 400) / 50
        private const val MAX_COOLDOWN_PROGRESS = 25 // (3000 - 500) / 100
        private const val MAX_RADIUS_PROGRESS = 28 // (200 - 60) / 5
        private const val MAX_BLINK_MIN_PROGRESS = 32 // (400 - 80) / 10
        private const val MAX_BLINK_MAX_PROGRESS = 24 // (1500 - 300) / 50
        private const val MAX_THRESHOLD_PROGRESS = 16 // (0.20 - 0.04) / 0.01
        private const val MAX_CUTOFF_PROGRESS = 45 // (0.50 - 0.05) / 0.01
        private const val MAX_BETA_PROGRESS = 45 // (0.050 - 0.005) / 0.001
    }
}
