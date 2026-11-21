package com.example.eyecontrol

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eyecontrol.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingDownloadId: Long = -1L
    private var installPendingAfterSettings = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != pendingDownloadId) return
            pendingDownloadId = -1L
            val apkFile = UpdateChecker.downloadedApkFile(this@MainActivity)
            if (!apkFile.exists()) {
                Toast.makeText(this@MainActivity, "下載失敗，請再試一次", Toast.LENGTH_SHORT).show()
                return
            }
            // 尚未允許安裝未知來源應用程式時，installApk 會先開系統設定；
            // 允許後回到 App，onResume 會再跳一次安裝畫面
            if (!packageManager.canRequestPackageInstalls()) {
                installPendingAfterSettings = true
            }
            UpdateChecker.installApk(this@MainActivity, apkFile)
        }
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "沒有相機權限就無法追蹤眼球", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCamera.setOnClickListener {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        binding.btnCalibrate.setOnClickListener {
            if (!hasCameraPermission()) {
                Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, CalibrationActivity::class.java)
                intent.putExtra(CalibrationActivity.EXTRA_PROFILE_ID, selectedProfileId())
                startActivity(intent)
            }
        }
        binding.btnAccessibility.setOnClickListener {
            if (!CalibrationStore.exists(this, selectedProfileId())) {
                Toast.makeText(
                    this,
                    "請先完成「${CalibrationStore.profileName(selectedProfileId())}」情境的校正",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                // 無障礙服務只能由使用者在系統設定中手動開啟
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "請在清單中找到「眼控 EyeControl」並開啟", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 情境選擇：先勾選目前情境，之後切換即時生效（服務會跟著換模型）
        checkProfileRadio(CalibrationStore.getActiveProfileId(this))
        binding.profileGroup.setOnCheckedChangeListener { _, _ ->
            CalibrationStore.setActiveProfileId(this, selectedProfileId())
            refreshStatus()
        }

        ContextCompat.registerReceiver(
            this, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 只有全新啟動時檢查更新（旋轉螢幕重建時不重複跳提示）
        if (savedInstanceState == null) checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // 從「允許安裝未知來源應用程式」設定頁回來，且下載好的 APK 還在：再跳一次安裝畫面
        if (installPendingAfterSettings) {
            installPendingAfterSettings = false
            val apkFile = UpdateChecker.downloadedApkFile(this)
            if (apkFile.exists() && packageManager.canRequestPackageInstalls()) {
                UpdateChecker.installApk(this, apkFile)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(downloadReceiver) }
    }

    private fun checkForUpdate() {
        UpdateChecker.checkForUpdate(this) { info ->
            AlertDialog.Builder(this)
                .setTitle("有新版本 ${info.versionTag}")
                .setMessage(
                    "目前版本 ${BuildConfig.VERSION_NAME}，最新版本 ${info.versionTag}。" +
                        "要下載更新嗎？下載完會直接跳安裝畫面。",
                )
                .setPositiveButton("下載更新") { _, _ ->
                    pendingDownloadId = UpdateChecker.startDownload(this, info)
                    Toast.makeText(this, "開始下載更新…", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("稍後再說", null)
                .show()
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun refreshStatus() {
        val cam = if (hasCameraPermission()) "✅" else "❌"
        val svc = if (isServiceEnabled()) "✅ 運作中" else "❌ 未開啟"
        val lines = CalibrationStore.PROFILES.joinToString("\n") { profile ->
            val state = if (CalibrationStore.exists(this, profile.id)) {
                val err = CalibrationStore.getMeta(this, profile.id)?.looErrorPx
                    ?.let { "（留一法誤差 ${"%.0f".format(it)} px）" } ?: ""
                "✅ 已校正$err"
            } else {
                "❌ 未校正"
            }
            "  ${profile.name} $state"
        }
        binding.statusText.text =
            "狀態：\n  相機權限 $cam\n  眼控服務 $svc\n$lines"
    }

    private fun selectedProfileId(): String = when (binding.profileGroup.checkedRadioButtonId) {
        R.id.radioLying -> CalibrationStore.PROFILE_LYING
        R.id.radioFlat -> CalibrationStore.PROFILE_FLAT
        else -> CalibrationStore.PROFILE_SITTING
    }

    private fun checkProfileRadio(id: String) {
        binding.profileGroup.check(
            when (id) {
                CalibrationStore.PROFILE_LYING -> R.id.radioLying
                CalibrationStore.PROFILE_FLAT -> R.id.radioFlat
                else -> R.id.radioSitting
            },
        )
    }
}
