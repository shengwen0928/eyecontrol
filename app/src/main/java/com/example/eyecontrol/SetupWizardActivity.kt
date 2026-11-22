package com.example.eyecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eyecontrol.databinding.ActivitySetupWizardBinding

/**
 * 設定引導：只做能完整接線的 5 步。
 *
 * 0 歡迎 → 1 相機權限（真實權限請求＋重試）→ 2 無障礙服務（檢查狀態、跳系統設定、
 * 返回時重查）→ 3 眼球校正（啟動現有 CalibrationActivity，交接協議由校正頁內部處理；
 * 返回時重查 CalibrationStore）→ 4 完成。
 *
 * 距離／環境確認、眼睛辨識測試、操作練習三步本批不做：沒有對應的真實檢測流程，
 * 做畫面就是假畫面，留待後續批次。
 *
 * 防困住設計：第 2／3 步若使用者已去過系統設定／校正頁、回來仍未完成，
 * 出現「先跳過，之後再做」——狀態卡如實顯示未完成，主畫面會再次提醒，
 * 絕不把「跳過」寫成「已完成」。第 1 步（相機）若被永久拒絕（系統不再彈詢問框），
 * 同樣出現跳過與去系統設定的出口，跳過不寫成已完成。
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding

    private var currentStep = STEP_WELCOME
    private var returnedFromSettings = false
    private var returnedFromCalibration = false

    /** 是否曾經發起過相機權限請求：用來分辨「尚未問過」與「永久拒絕」。 */
    private var cameraAsked = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "沒有相機權限就無法追蹤眼球，可以點「再試一次」", Toast.LENGTH_LONG).show()
            }
            refreshStep()
        }

    private val launchCalibration =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 校正頁不回傳結果碼：回來一律重查 CalibrationStore 才算數。
            returnedFromCalibration = true
            refreshStep()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentStep = savedInstanceState?.getInt(KEY_STEP, STEP_WELCOME) ?: STEP_WELCOME
        returnedFromSettings =
            savedInstanceState?.getBoolean(KEY_FROM_SETTINGS, false) ?: false
        returnedFromCalibration =
            savedInstanceState?.getBoolean(KEY_FROM_CALIBRATION, false) ?: false
        cameraAsked = savedInstanceState?.getBoolean(KEY_CAMERA_ASKED, false) ?: false

        binding.btnWizardPrimary.setOnClickListener { onPrimary() }
        binding.btnWizardBack.setOnClickListener { onBack() }
        binding.btnWizardSkip.setOnClickListener { goToStep(currentStep + 1) }

        refreshStep()
    }

    override fun onResume() {
        super.onResume()
        // 從系統設定／校正頁回來時重查真實狀態，狀態卡與按鈕文字才會跟上。
        refreshStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, currentStep)
        outState.putBoolean(KEY_FROM_SETTINGS, returnedFromSettings)
        outState.putBoolean(KEY_FROM_CALIBRATION, returnedFromCalibration)
        outState.putBoolean(KEY_CAMERA_ASKED, cameraAsked)
    }

    private fun onPrimary() {
        when (currentStep) {
            STEP_WELCOME -> goToStep(STEP_CAMERA)
            STEP_CAMERA -> {
                if (hasCameraPermission()) {
                    goToStep(STEP_SERVICE)
                } else if (isCameraPermanentlyDenied()) {
                    // 系統不會再彈詢問框：再點請求也沒用，改走系統設定。
                    openAppSettings()
                } else {
                    cameraAsked = true
                    requestCamera.launch(Manifest.permission.CAMERA)
                }
            }
            STEP_SERVICE -> {
                if (isServiceEnabled()) {
                    goToStep(STEP_CALIBRATION)
                } else {
                    returnedFromSettings = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this, "請在清單中找到「眼控 EyeControl」並開啟", Toast.LENGTH_LONG).show()
                }
            }
            STEP_CALIBRATION -> {
                if (isCalibrated()) {
                    goToStep(STEP_DONE)
                } else {
                    val intent = Intent(this, CalibrationActivity::class.java)
                    intent.putExtra(
                        CalibrationActivity.EXTRA_PROFILE_ID,
                        CalibrationStore.getActiveProfileId(this),
                    )
                    launchCalibration.launch(intent)
                }
            }
            STEP_DONE -> {
                WelcomeActivity.markWelcomeSeen(this)
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                finish()
            }
        }
    }

    private fun onBack() {
        if (currentStep > STEP_WELCOME) {
            goToStep(currentStep - 1)
        } else {
            // 第一步按返回：歡迎頁已 finish，直接離開 App；下次啟動會重看歡迎頁，不困住。
            finish()
        }
    }

    private fun goToStep(step: Int) {
        currentStep = step.coerceIn(STEP_WELCOME, STEP_DONE)
        refreshStep()
    }

    private fun refreshStep() {
        binding.wizardStepLabel.text = "第 ${currentStep + 1} 步，共 5 步"
        binding.wizardProgress.progress = currentStep + 1
        binding.btnWizardBack.visibility = if (currentStep == STEP_WELCOME) View.GONE else View.VISIBLE
        binding.btnWizardSkip.visibility = View.GONE

        when (currentStep) {
            STEP_WELCOME -> {
                binding.wizardTitle.text = "歡迎使用 EyeControl"
                binding.wizardDesc.text =
                    "接下來三步，幫你把眼控設定好。不用趕，每一步都可以返回、可以重來。"
                setStatus(null)
                binding.btnWizardPrimary.text = "開始設定"
            }
            STEP_CAMERA -> {
                binding.wizardTitle.text = "允許使用相機"
                binding.wizardDesc.text =
                    "前鏡頭用來看眼睛的位置。影像只在手機上即時處理，不存照片、不上傳。"
                if (hasCameraPermission()) {
                    setStatus("相機權限：已允許。")
                    binding.btnWizardPrimary.text = "下一步"
                } else if (isCameraPermanentlyDenied()) {
                    // 永久拒絕：系統詢問框不會再出現，必須去系統設定手動開啟。
                    // 比照第 2／3 步出現跳過出口，跳過不寫成已完成。
                    setStatus(
                        "相機權限被拒絕，且系統不會再跳出詢問。\n" +
                            "沒有相機權限就無法追蹤眼球。請到系統設定開啟，或先跳過、之後再做。",
                    )
                    binding.btnWizardPrimary.text = "開啟系統設定"
                    binding.btnWizardSkip.visibility = View.VISIBLE
                } else {
                    setStatus("相機權限：尚未允許。點下面按鈕，系統會跳出詢問。")
                    binding.btnWizardPrimary.text = "允許相機權限"
                }
            }
            STEP_SERVICE -> {
                binding.wizardTitle.text = "開啟眼控服務"
                binding.wizardDesc.text =
                    "眼控要代替手指點按螢幕，必須在系統無障礙設定裡開啟服務。服務不會讀取或記錄畫面內容。"
                if (isServiceEnabled()) {
                    setStatus("眼控服務：已開啟。")
                    binding.btnWizardPrimary.text = "下一步"
                } else {
                    setStatus("眼控服務：尚未開啟。點下面按鈕會開啟系統設定。")
                    binding.btnWizardPrimary.text = "開啟系統設定"
                    if (returnedFromSettings) {
                        binding.btnWizardSkip.visibility = View.VISIBLE
                    }
                }
            }
            STEP_CALIBRATION -> {
                val profileName =
                    CalibrationStore.profileName(CalibrationStore.getActiveProfileId(this))
                binding.wizardTitle.text = "看九個點，完成校正"
                binding.wizardDesc.text =
                    "依序看著九個點，眼睛不要動頭。做的是「$profileName」情境，換姿勢可以在主畫面切換情境、各自校正。"
                if (isCalibrated()) {
                    val err = CalibrationStore
                        .getMeta(this, CalibrationStore.getActiveProfileId(this))?.looErrorPx
                        ?.let { "留一法誤差 ${"%.0f".format(it)} px。" } ?: ""
                    setStatus("「$profileName」已校正。$err")
                    binding.btnWizardPrimary.text = "下一步"
                } else {
                    setStatus("「$profileName」尚未校正。點下面按鈕開始。")
                    binding.btnWizardPrimary.text = "開始校正"
                    if (returnedFromCalibration) {
                        binding.btnWizardSkip.visibility = View.VISIBLE
                    }
                }
            }
            STEP_DONE -> {
                binding.wizardTitle.text = "設定完成"
                binding.wizardDesc.text = "目前狀態如下。沒完成的部分，之後都可以在主畫面繼續。"
                val cam = if (hasCameraPermission()) "已允許" else "尚未允許"
                val svc = if (isServiceEnabled()) "已開啟" else "尚未開啟"
                val profileName =
                    CalibrationStore.profileName(CalibrationStore.getActiveProfileId(this))
                val cal = if (isCalibrated()) "已校正" else "尚未校正"
                setStatus("相機權限：$cam\n眼控服務：$svc\n「$profileName」校正：$cal")
                binding.btnWizardPrimary.text = "開始使用"
                binding.btnWizardBack.visibility = View.GONE
            }
        }
    }

    private fun setStatus(text: String?) {
        if (text == null) {
            binding.wizardStatusCard.visibility = View.GONE
        } else {
            binding.wizardStatusCard.visibility = View.VISIBLE
            binding.wizardStatus.text = text
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 永久拒絕：曾經問過、目前未授予、且系統表示不應再顯示詢問框
     *（使用者勾選「不再詢問」後拒絕）。尚未問過時 shouldShowRequestPermissionRationale
     *同樣回 false，必須用 cameraAsked 區分，否則第一步就會誤判為永久拒絕。
     */
    private fun isCameraPermanentlyDenied(): Boolean =
        !hasCameraPermission() && cameraAsked &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

    /** 開啟本 App 的系統設定頁（權限頁），回來時 onResume 會重查真實狀態。 */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
        Toast.makeText(this, "請在「權限」中找到相機並允許", Toast.LENGTH_LONG).show()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun isCalibrated(): Boolean =
        CalibrationStore.exists(this, CalibrationStore.getActiveProfileId(this))

    companion object {
        private const val STEP_WELCOME = 0
        private const val STEP_CAMERA = 1
        private const val STEP_SERVICE = 2
        private const val STEP_CALIBRATION = 3
        private const val STEP_DONE = 4

        private const val KEY_STEP = "wizard_step"
        private const val KEY_FROM_SETTINGS = "wizard_from_settings"
        private const val KEY_FROM_CALIBRATION = "wizard_from_calibration"
        private const val KEY_CAMERA_ASKED = "wizard_camera_asked"
    }
}
