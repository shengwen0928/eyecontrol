/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eyecontrol.databinding.ActivitySetupWizardBinding

/**
 * 設定引導：只做能完整接線的 6 步。
 *
 * 0 歡迎 → 1 使用前須知（聲明：影像本機處理、服務用途、更新方式與 Play 安全防護
 * 警告說明；純告知，看完按鈕繼續）→ 2 相機權限（真實權限請求＋重試）→
 * 3 眼球校正（啟動現有 CalibrationActivity，交接協議由校正頁內部處理；
 * 返回時重查 CalibrationStore）→ 4 無障礙服務（檢查狀態、跳系統設定、返回時重查）→
 * 5 完成。順序依 D-1 定稿：先校正、後啟用服務。
 *
 * 距離／環境確認、眼睛辨識測試、操作練習三步本批不做：沒有對應的真實檢測流程，
 * 做畫面就是假畫面，留待後續批次。
 *
 * 防困住設計：校正／服務兩步若使用者已去過校正頁／系統設定、回來仍未完成，
 * 出現「先跳過，之後再做」——狀態卡如實顯示未完成，主畫面會再次提醒，
 * 絕不把「跳過」寫成「已完成」。第 2 步（相機）若被永久拒絕（系統不再彈詢問框），
 * 同樣出現跳過與去系統設定的出口，跳過不寫成已完成。
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding

    private var currentStep = STEP_WELCOME
    private var returnedFromSettings = false
    private var returnedFromCalibration = false

    /**
     * 各步是否被使用者明確跳過（D1-16 完成頁「已跳過」判定的唯一來源）。
     * 只存記憶體＋onSaveInstanceState 旋轉保存，不新增任何持久化（逐項持久化
     * 待 D-8 決定）。完成頁一律先看當下真實狀態：已完成者顯示已完成，
     * 跳過旗標只在「當下未完成」時才決定顯示「已跳過」或「未完成」。
     */
    private var skippedCamera = false
    private var skippedCalibration = false
    private var skippedService = false

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
        skippedCamera = savedInstanceState?.getBoolean(KEY_SKIPPED_CAMERA, false) ?: false
        skippedCalibration =
            savedInstanceState?.getBoolean(KEY_SKIPPED_CALIBRATION, false) ?: false
        skippedService = savedInstanceState?.getBoolean(KEY_SKIPPED_SERVICE, false) ?: false

        binding.btnWizardPrimary.setOnClickListener { onPrimary() }
        binding.btnWizardBack.setOnClickListener { onBack() }
        binding.btnWizardSkip.setOnClickListener {
            // 記下「這一步是被跳過的」，完成頁才能誠實區分「已跳過」與「未完成」。
            when (currentStep) {
                STEP_CAMERA -> skippedCamera = true
                STEP_CALIBRATION -> skippedCalibration = true
                STEP_SERVICE -> skippedService = true
            }
            goToStep(currentStep + 1)
        }
        binding.btnWizardReguide.setOnClickListener {
            // D1-15「重新指引」：再開一次系統設定頁，跳過規則與返回重查不變。
            returnedFromSettings = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "請在清單中找到「凝視」並開啟", Toast.LENGTH_LONG).show()
        }

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
        outState.putBoolean(KEY_SKIPPED_CAMERA, skippedCamera)
        outState.putBoolean(KEY_SKIPPED_CALIBRATION, skippedCalibration)
        outState.putBoolean(KEY_SKIPPED_SERVICE, skippedService)
    }

    private fun onPrimary() {
        when (currentStep) {
            STEP_WELCOME -> goToStep(STEP_NOTICE)
            STEP_NOTICE -> goToStep(STEP_CAMERA)
            STEP_CAMERA -> {
                if (hasCameraPermission()) {
                    goToStep(STEP_CALIBRATION)
                } else if (isCameraPermanentlyDenied()) {
                    // 系統不會再彈詢問框：再點請求也沒用，改走系統設定。
                    openAppSettings()
                } else {
                    cameraAsked = true
                    requestCamera.launch(Manifest.permission.CAMERA)
                }
            }
            STEP_SERVICE -> {
                // D1-15：服務已開≠完成，必須驗證通過（快照新鮮＋有模型）才
                // 能下一步。已開但驗證未過時主鈕為「重試驗證」（當下重查，
                // 不設逾時自動判定失敗，使用者可能正在擺位）；「重新指引」
                // 另有專鈕，跳過規則不變。
                if (!isServiceEnabled()) {
                    returnedFromSettings = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this, "請在清單中找到「凝視」並開啟", Toast.LENGTH_LONG).show()
                } else if (GazeAccessibilityService.verifyServiceRunning()) {
                    goToStep(STEP_DONE)
                } else {
                    refreshStep()
                }
            }
            STEP_CALIBRATION -> {
                if (isCalibrated()) {
                    goToStep(STEP_SERVICE)
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
        binding.wizardStepLabel.text = "第 ${currentStep + 1} 步，共 6 步"
        binding.wizardProgress.progress = currentStep + 1
        binding.btnWizardBack.visibility = if (currentStep == STEP_WELCOME) View.GONE else View.VISIBLE
        binding.btnWizardSkip.visibility = View.GONE
        // 「重新指引」只在 STEP_SERVICE 驗證未過時出現，其餘步驟一律隱藏。
        binding.btnWizardReguide.visibility = View.GONE

        when (currentStep) {
            STEP_WELCOME -> {
                binding.wizardTitle.text = "歡迎使用凝視"
                binding.wizardDesc.text =
                    "接下來幾步，幫你把眼控設定好。不用趕，每一步都可以返回、可以重來。"
                setStatus(null)
                binding.btnWizardPrimary.text = "開始設定"
            }
            STEP_NOTICE -> {
                binding.wizardTitle.text = "先說清楚三件事"
                // 監控例外段按 flavor 補（與 docs/privacy.html 第一節例外段一致）：
                // 側載版預設開啟診斷紀錄與遠端監控、調校用、上架前移除；
                // Play 版無監控、不上傳診斷資料。
                binding.wizardDesc.text =
                    "一、影像只在手機上即時處理，不存照片、不上傳。\n\n" +
                        "二、眼控服務代替手指點按螢幕，不讀取、不記錄其他 App 的內容。\n\n" +
                        "三、更新方式：Play 商店版在 Play 商店更新；從 GitHub 下載的版本在 App 內檢查更新。從 GitHub 安裝時，Play 安全防護跳出警告是預期的，點「仍要安裝」即可。" +
                        if (BuildConfig.REMOTE_MONITOR_ENABLED) {
                            "\n\n唯一的例外是內部調校期的「診斷紀錄」與「遠端監控」（僅這個 GitHub 側載版提供，預設開啟）：App 會把游標診斷數字與眼區驗證裁切記錄在本機並上傳到開發者的調校服務，只作調校的作業依據；設定頁可以分別關閉，關閉即停止該管道的上傳與留存。調校完成、正式上架前，這段功能會連同程式、開關與監控專案一併完全移除。"
                        } else {
                            "\n\n這個版本（Play 商店版）不上傳任何診斷資料，更新一律透過 Play 商店。"
                        }
                setStatus(null)
                binding.btnWizardPrimary.text = "我知道了，繼續"
            }
            STEP_CAMERA -> {
                binding.wizardTitle.text = "允許使用相機"
                // K-12 殘餘（第 4 步遺留 (e)）：側載版依 flavor 修為準確文字
                // （與 docs/privacy.html 例外段一致：全臉影像不儲存，調校版
                // 另會留存與上傳眼區驗證裁切與診斷數字）；Play 版維持原意。
                // 只改文字，不改流程與版面。
                binding.wizardDesc.text =
                    if (BuildConfig.REMOTE_MONITOR_ENABLED) {
                        "前鏡頭用來看眼睛的位置。全臉影像只在手機上即時處理、不儲存；" +
                            "內部調校期的眼區驗證裁切與診斷數字會留存與上傳" +
                            "（詳見「先說清楚三件事」），正式上架前移除。"
                    } else {
                        "前鏡頭用來看眼睛的位置。影像只在手機上即時處理，不存照片、不上傳。"
                    }
                if (hasCameraPermission()) {
                    setStatus("相機權限：已允許。")
                    binding.btnWizardPrimary.text = "下一步"
                } else if (isCameraPermanentlyDenied()) {
                    // 永久拒絕：系統詢問框不會再出現，必須去系統設定手動開啟。
                    // 比照校正／服務兩步出現跳過出口，跳過不寫成已完成。
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
                if (!isServiceEnabled()) {
                    setStatus("眼控服務：尚未開啟。點下面按鈕會開啟系統設定。")
                    binding.btnWizardPrimary.text = "開啟系統設定"
                    if (returnedFromSettings) {
                        binding.btnWizardSkip.visibility = View.VISIBLE
                    }
                } else if (GazeAccessibilityService.verifyServiceRunning()) {
                    setStatus("眼控服務：已開啟。")
                    binding.btnWizardPrimary.text = "下一步"
                } else {
                    // D1-15：已開但驗證未過，不得直接認定完成。狀態卡用 §7
                    // 的原句；主鈕重試驗證、「重新指引」另開出口，跳過保留。
                    setStatus("服務已開啟，但尚未收到追蹤畫面。")
                    binding.btnWizardPrimary.text = "重試驗證"
                    binding.btnWizardReguide.visibility = View.VISIBLE
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
                // D1-16：逐項誠實列出（已完成／已跳過（之後再做）／未完成），
                // 跳過與未完成項可點回對應步驟；三項皆未完成時標題不得稱
                // 「設定完成」。服務項的「完成」＝已開啟且驗證通過（D1-15），
                // 不只看系統設定旗標。跳轉目標與 welcome_seen 語義不變：
                // welcome_seen 只在按「開始使用」時寫入（見 onPrimary）。
                val profileName =
                    CalibrationStore.profileName(CalibrationStore.getActiveProfileId(this))
                val camState = doneItemState(hasCameraPermission(), skippedCamera)
                val calState = doneItemState(isCalibrated(), skippedCalibration)
                val svcState = doneItemState(
                    isServiceEnabled() && GazeAccessibilityService.verifyServiceRunning(),
                    skippedService,
                )
                val allDone = camState == DoneItemState.DONE &&
                    calState == DoneItemState.DONE && svcState == DoneItemState.DONE
                binding.wizardTitle.text = if (allDone) "設定完成" else "設定尚未全部完成"
                binding.wizardDesc.text =
                    if (allDone) {
                        "三項都完成了。之後也可以在主畫面繼續調整。"
                    } else {
                        "目前狀態如下。沒完成或跳過的部分，點一下可以回到那一步；之後也可以在主畫面繼續。"
                    }
                setStatus(
                    doneSummaryText(
                        listOf(
                            "相機權限" to camState to STEP_CAMERA,
                            "「$profileName」校正" to calState to STEP_CALIBRATION,
                            "眼控服務" to svcState to STEP_SERVICE,
                        ),
                    ),
                )
                binding.wizardStatus.movementMethod = LinkMovementMethod.getInstance()
                binding.wizardStatus.highlightColor =
                    ContextCompat.getColor(this, R.color.ec_sage_soft)
                binding.btnWizardPrimary.text = "開始使用"
                binding.btnWizardBack.visibility = View.GONE
            }
        }
    }

    /** 完成頁單項狀態：當下已完成優先；未完成時再以是否被跳過區分。 */
    private enum class DoneItemState { DONE, SKIPPED, TODO }

    private fun doneItemState(done: Boolean, skipped: Boolean): DoneItemState =
        when {
            done -> DoneItemState.DONE
            skipped -> DoneItemState.SKIPPED
            else -> DoneItemState.TODO
        }

    /**
     * 完成頁彙總文字：每行「項目：狀態」。已跳過／未完成的行可點回對應步驟
     * （ClickableSpan，淺色暖系深鼠尾草綠＋底線）；已完成的行不設連結。
     */
    private fun doneSummaryText(
        items: List<Pair<Pair<String, DoneItemState>, Int>>,
    ): CharSequence {
        val builder = SpannableStringBuilder()
        items.forEachIndexed { index, (labelAndState, step) ->
            if (index > 0) builder.append("\n")
            val (label, state) = labelAndState
            val line = when (state) {
                DoneItemState.DONE -> "$label：已完成"
                DoneItemState.SKIPPED -> "$label：已跳過（之後再做）"
                DoneItemState.TODO -> "$label：未完成"
            }
            val start = builder.length
            builder.append(line)
            if (state != DoneItemState.DONE) {
                builder.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            goToStep(step)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = ContextCompat.getColor(
                                this@SetupWizardActivity, R.color.ec_sage_deep,
                            )
                            ds.isUnderlineText = true
                        }
                    },
                    start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return builder
    }

    private fun setStatus(text: CharSequence?) {
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
        // 精確比對：按 :／; 切分後逐項取「/」前的套件名等值比對。
        // 子字串比對會誤判邊界（例如套件名前綴相同的其他服務），
        // 精確比對的判定結果與舊行為一致，只修誤判邊界。
        return enabled.split(':', ';').any { entry ->
            val item = entry.trim()
            val sep = item.indexOf('/')
            sep > 0 && item.substring(0, sep) == packageName
        }
    }

    private fun isCalibrated(): Boolean =
        CalibrationStore.exists(this, CalibrationStore.getActiveProfileId(this))

    companion object {
        private const val STEP_WELCOME = 0
        private const val STEP_NOTICE = 1
        private const val STEP_CAMERA = 2
        // D-1 順序：校正在前（3）、服務在後（4）。
        private const val STEP_CALIBRATION = 3
        private const val STEP_SERVICE = 4
        private const val STEP_DONE = 5

        private const val KEY_STEP = "wizard_step"
        private const val KEY_FROM_SETTINGS = "wizard_from_settings"
        private const val KEY_FROM_CALIBRATION = "wizard_from_calibration"
        private const val KEY_CAMERA_ASKED = "wizard_camera_asked"
        private const val KEY_SKIPPED_CAMERA = "wizard_skipped_camera"
        private const val KEY_SKIPPED_CALIBRATION = "wizard_skipped_calibration"
        private const val KEY_SKIPPED_SERVICE = "wizard_skipped_service"
    }
}
