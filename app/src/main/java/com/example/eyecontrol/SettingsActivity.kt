package com.example.eyecontrol

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eyecontrol.databinding.ActivitySettingsBinding

/**
 * 可調參數設定頁：滑桿即時顯示數值，按「儲存」寫入 SharedPreferences。
 * 無障礙服務運作中也會透過偏好監聽即時套用，不必重啟服務。
 * 滑桿範圍與步進和 GazeSettings 的允許範圍一致，換算函數集中在此。
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

        binding.btnSave.setOnClickListener { saveAndFinish() }
        binding.btnDefaults.setOnClickListener { resetToDefaults() }
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
