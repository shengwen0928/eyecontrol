/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eyecontrol.databinding.ActivityMainBinding

/**
 * Dashboard（眼控中心）：狀態卡＋眼控操作＋追蹤品質＋設定摘要。
 *
 * 狀態來源全部是真實檢查：相機權限、無障礙服務啟用狀態、CalibrationStore、
 * GazeAccessibilityService.trackingSnapshot()（3 秒內無樣本視為未知）。
 * 追蹤品質不編造數字：沒有逐眼／穩定度分數這種資料來源，只有狀態文字；
 * 雙眼列反映的是「臉部偵測到 → 虹膜追蹤才有資料」這個前提，真正的逐眼信心
 * 指標留待 Phase 4 的信心引擎。
 *
 * 首次啟動先跳歡迎頁（WelcomeActivity），之後直接進 Dashboard，不產生返回鍵迴圈。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingDownloadId: Long = -1L
    private var installPendingAfterSettings = false

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != pendingDownloadId) return
            pendingDownloadId = -1L
            // 只看「檔案存在」不夠：下載失敗也可能留下舊版／殘缺檔案，必須查 DownloadManager 的狀態
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var downloadOk = false
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status =
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded =
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total =
                        cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    downloadOk = status == DownloadManager.STATUS_SUCCESSFUL &&
                        (total <= 0L || downloaded >= total)
                }
            }
            val apkFile = UpdateChecker.downloadedApkFile(this@MainActivity)
            if (!downloadOk) {
                // 刪掉固定檔名的殘檔，免得之後誤裝舊版或壞檔
                runCatching { apkFile.delete() }
                Toast.makeText(this@MainActivity, "下載失敗，請再試一次", Toast.LENGTH_SHORT).show()
                return
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次啟動先看歡迎頁；Welcome 結束後回來不再重跳，不產生返回鍵迴圈。
        if (WelcomeActivity.isFirstRun(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showCrashReportIfPending()

        binding.btnCalibrate.setOnClickListener {
            if (!hasCameraPermission()) {
                Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, CalibrationActivity::class.java)
                intent.putExtra(CalibrationActivity.EXTRA_PROFILE_ID, selectedProfileId())
                startActivity(intent)
            }
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnTraining.setOnClickListener {
            // 練習頁自己 host 相機與模型；無模型時它會導引去校正，不顯示假游標。
            if (!hasCameraPermission()) {
                Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, TrainingActivity::class.java))
            }
        }

        binding.btnStartTracking.setOnClickListener {
            // D2-6：RECOVERY_MODE 時「開始眼控」鈕為「已可注視重啟」提示態，
            // 手點恢復走 D2-3 同一驗證序列（resumeFromRecovery），不直接
            // resumeTracking；驗證失敗時由服務端顯示 ERROR 原因。
            if (GazeAccessibilityService.trackingSnapshot()?.inRecoveryMode == true) {
                GazeAccessibilityService.resumeFromRecovery()
                Toast.makeText(this, "正在重啟眼控…", Toast.LENGTH_SHORT).show()
                refreshStatus()
                return@setOnClickListener
            }
            // D1-14 啟動閘門：先以 canStartTracking() 判定，全過才恢復／開始；
            // 未過時依序診斷缺哪一項（未校正→導引校正；已校正但服務未開→
            // 系統設定；引擎失敗→原因文字），Toast＋狀態列（refreshStatus）
            // 皆明示，不得只說含糊提示。順序與第 2 步「未校正先導引去校正」
            // 銜接：未校正不論服務開否，一律先導引去校正。
            if (GazeAccessibilityService.canStartTracking(this)) {
                GazeAccessibilityService.resumeTracking()
                Toast.makeText(this, "眼控已開始", Toast.LENGTH_SHORT).show()
                refreshStatus()
                return@setOnClickListener
            }
            val activeId = selectedProfileId()
            val profileName = CalibrationStore.profileName(activeId)
            if (!CalibrationStore.exists(this, activeId)) {
                Toast.makeText(
                    this,
                    "請先完成「$profileName」情境的校正，再開始眼控",
                    Toast.LENGTH_SHORT,
                ).show()
                if (!hasCameraPermission()) {
                    Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(this, CalibrationActivity::class.java)
                    intent.putExtra(CalibrationActivity.EXTRA_PROFILE_ID, activeId)
                    startActivity(intent)
                }
                refreshStatus()
                return@setOnClickListener
            }
            if (!isServiceEnabled()) {
                Toast.makeText(this, "請先開啟無障礙服務", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                refreshStatus()
                return@setOnClickListener
            }
            val snap = GazeAccessibilityService.trackingSnapshot()
            if (snap == null) {
                Toast.makeText(
                    this,
                    "眼控服務尚未啟動，請確認已在系統設定開啟「凝視」",
                    Toast.LENGTH_SHORT,
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                refreshStatus()
                return@setOnClickListener
            }
            if (snap.flowState == AppFlowState.ERROR) {
                val reason = snap.flowErrorReason ?: "發生錯誤"
                Toast.makeText(this, "無法開始眼控：$reason", Toast.LENGTH_LONG).show()
                refreshStatus()
                return@setOnClickListener
            }
            // 閘門未過、但以上三項皆通過（狀態在兩次讀取間變化）：不靜默，
            // 以狀態列文字為準，請使用者看上方狀態說明。
            Toast.makeText(this, "目前無法開始眼控，請看上方狀態說明", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        binding.btnPauseTracking.setOnClickListener {
            if (!isServiceEnabled()) {
                Toast.makeText(this, "眼控服務尚未開啟", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GazeAccessibilityService.pauseTracking()
            Toast.makeText(this, "眼控已暫停，游標隱藏", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        binding.btnStopTracking.setOnClickListener {
            if (!isServiceEnabled()) {
                Toast.makeText(this, "眼控服務尚未開啟", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("停止眼控？")
                .setMessage("停止眼控後，相機會繼續開著等你用注視重啟；要完全關閉相機，請選「完全停止」。")
                .setPositiveButton("停止眼控") { _, _ ->
                    GazeAccessibilityService.stopTracking()
                    Toast.makeText(this, "眼控已停止，注視中央目標可重啟", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
                .setNeutralButton("完全停止") { _, _ ->
                    GazeAccessibilityService.stopCompletely()
                    Toast.makeText(this, "已完全停止，相機已關閉", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 情境選擇：先勾選目前情境，之後切換即時生效（服務會跟著換模型）
        checkProfileRadio(CalibrationStore.getActiveProfileId(this))
        binding.profileGroup.setOnCheckedChangeListener { _, _ ->
            CalibrationStore.setActiveProfileId(this, selectedProfileId())
            refreshStatus()
        }

        binding.footerText.text = "凝視 ${BuildConfig.VERSION_NAME}"

        ContextCompat.registerReceiver(
            this, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 只有全新啟動時檢查更新（旋轉螢幕重建時不重複跳提示）
        if (savedInstanceState == null) checkForUpdate()
    }

    /**
     * 上次閃退留下的紀錄：只顯示一次，內容可複製、可清除。沒有紀錄時什麼都不做。
     * 對話框在 onCreate 顯示、Activity 恢復後才真正貼到視窗，不影響正常流程。
     */
    private fun showCrashReportIfPending() {
        val report = CrashReporter.pendingReport(this) ?: return
        val trace = runCatching { report.readText() }.getOrNull()?.take(4000) ?: return
        CrashReporter.markShown(this, report)
        val textView = TextView(this).apply {
            text = "上次開啟時 App 閃退了，紀錄如下。把這頁截圖或複製文字傳給開發者，就能定位問題。\n\n$trace"
            setTextIsSelectable(true)
            textSize = 13f
            setPadding(48, 32, 48, 32)
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle("上次閃退紀錄")
            .setView(scroll)
            .setPositiveButton("複製紀錄") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("閃退紀錄", textView.text))
                Toast.makeText(this, "已複製", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清除紀錄") { _, _ ->
                CrashReporter.clearAll(this)
                Toast.makeText(this, "紀錄已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        statusHandler.postDelayed(statusPoller, STATUS_POLL_MS)
        // 從「允許安裝未知來源應用程式」設定頁回來，且下載好的 APK 還在：再跳一次安裝畫面
        if (installPendingAfterSettings) {
            installPendingAfterSettings = false
            val apkFile = UpdateChecker.downloadedApkFile(this)
            if (apkFile.exists() && packageManager.canRequestPackageInstalls()) {
                UpdateChecker.installApk(this, apkFile)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusPoller)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(downloadReceiver) }
    }

    /** 操作是經 mainHandler 非同步生效的，短暫延遲後再刷一次狀態才準。 */
    private fun checkForUpdate() {
        // Play 商店版：更新走 Play 商店，App 內不查 GitHub（Play 政策禁止自行下載 APK 更新）。
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
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

    /**
     * 系統設定中的真實啟用狀態：共用服務端的精確比對實作（第 4 步遺留 (b)，
     * 與引導內版本同一算法），不再子字串比對。唯讀，不寫入、不繞過授權。
     */
    private fun isServiceEnabled(): Boolean =
        GazeAccessibilityService.isServiceEnabled(this)

    private fun refreshStatus() {
        val camOk = hasCameraPermission()
        val svcOk = isServiceEnabled()
        val activeId = selectedProfileId()
        val calibrated = CalibrationStore.exists(this, activeId)
        val snap = GazeAccessibilityService.trackingSnapshot()

        // ---- 副標題：一句話總覽（吃 snapshot.flowState，文字見 STATE_DESIGN §7） ----
        // 優先序：停止／錯誤／重啟／校正等流程態優先於「未校正／服務未開」的
        // 靜態檢查；未校正優先於服務未開（D-1 順序：先校正、後啟用服務）。
        // RECOVERY_MODE 由第 5 步 D2-1 接線（stopTracking→RECOVERY_MODE、
        // stopCompletely→STOPPED，stopped 僅完全停止時為真，不得混用）；
        // WAITING_ACCESSIBILITY
        // 的兩句 §7 文字由「服務尚未開啟」與「尚未收到追蹤畫面」兩分支覆蓋。
        val flowState = snap?.flowState
        binding.statusText.text = when {
            !camOk -> "先授予相機權限，即可開始校正。"
            snap?.stopped == true || flowState == AppFlowState.STOPPED ->
                "已完全停止，相機已關閉。"
            flowState == AppFlowState.ERROR ->
                "${snap?.flowErrorReason ?: "發生錯誤"}。已完成項目保留。"
            flowState == AppFlowState.RECOVERY_MODE ->
                "眼控已停止，注視中央目標可重啟（相機運作中）。"
            flowState == AppFlowState.CALIBRATING -> "校正進行中，服務已暫停。"
            flowState == AppFlowState.CALIBRATED -> calibratedStatusText(activeId)
            flowState == AppFlowState.NOT_CALIBRATED || !calibrated ->
                "尚未校正「${CalibrationStore.profileName(activeId)}」情境。先完成校正，才能開始眼控。"
            !svcOk -> "服務尚未開啟，點「開啟無障礙服務」。"
            snap?.paused == true -> "眼控已暫停，游標隱藏中。"
            snap == null -> "服務已開啟，但尚未收到追蹤畫面。"
            !snap.sampleFresh -> "服務已開啟，但尚未收到追蹤畫面。"
            snap.facePresent -> "眼控運作中。"
            else -> "眼控運作中，等待臉部出現在前鏡頭前。"
        }

        // ---- 系統狀態卡 ----
        setRow(binding.dotCamera, binding.valCamera, camOk, "已允許", "尚未允許", unknown = false)
        setRow(
            binding.dotService, binding.valService, svcOk,
            "運作中", "未開啟", unknown = false,
        )
        setRow(
            binding.dotCalibration, binding.valCalibration, calibrated,
            "已校正", "未校正", unknown = false,
        )

        val faceKnown = snap != null && snap.sampleFresh
        setRow(
            binding.dotFace, binding.valFace,
            faceKnown && snap.facePresent,
            "已偵測到臉部",
            when {
                snap == null -> "未知（服務未開啟）"
                !snap.sampleFresh -> "未知（無新畫面）"
                else -> "找不到臉部"
            },
            unknown = !faceKnown,
        )
        val trackingOk = faceKnown && snap.facePresent && snap.cursorValid && snap.hasModel &&
            !snap.paused && !snap.stopped && !snap.inRecoveryMode
        setRow(
            binding.dotTracking, binding.valTracking,
            trackingOk,
            "追蹤中",
            when {
                snap == null -> "未知（服務未開啟）"
                !snap.sampleFresh -> "未知（無新畫面）"
                snap.stopped -> "已停止"
                snap.inRecoveryMode -> "眼控已停止"
                snap.paused -> "已暫停"
                !snap.facePresent -> "等待臉部"
                !snap.hasModel -> "尚未載入校正模型"
                else -> "定位中"
            },
            unknown = !faceKnown,
        )

        binding.scenarioChip.text = "情境：${CalibrationStore.profileName(activeId)}"
        binding.profileStatusText.text =
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

        // ---- 追蹤品質：只有狀態文字，不編造分數 ----
        // 雙眼列沒有獨立的逐眼狀態來源；虹膜追蹤的前提就是臉部被偵測到，
        // 因此如實反映臉部狀態，不宣稱量到個別眼睛的信心值。
        setRow(
            binding.dotEyes, binding.valEyes,
            faceKnown && snap.facePresent,
            "偵測中（隨臉部狀態）",
            when {
                snap == null -> "未知（服務未開啟）"
                !snap.sampleFresh -> "未知（無新畫面）"
                else -> "找不到臉部"
            },
            unknown = !faceKnown,
        )
        setRow(
            binding.dotQuality, binding.valQuality,
            trackingOk,
            // 無獨立的即時品質量測（見類別註解），只描述追蹤狀態，不顯示分數、
            // 不用「穩定」二字（K-31）。
            "追蹤中",
            when {
                snap == null -> "未知（服務未開啟）"
                !snap.sampleFresh -> "未知（無新畫面）"
                snap.stopped -> "已停止"
                snap.inRecoveryMode -> "眼控已停止"
                snap.paused -> "已暫停"
                !snap.facePresent -> "等待臉部"
                !snap.hasModel -> "尚未載入校正模型"
                else -> "定位中"
            },
            unknown = !faceKnown,
        )
        val clickable = svcOk && calibrated && trackingOk
        setRow(
            binding.dotClickable, binding.valClickable,
            clickable,
            "可以點擊",
            when {
                !svcOk -> "目前不行（服務未開啟）"
                !calibrated -> "目前不行（尚未校正）"
                snap?.stopped == true -> "目前不行（已停止追蹤）"
                snap?.inRecoveryMode == true -> "目前不行（眼控已停止，注視中央目標可重啟）"
                snap?.paused == true -> "目前不行（已暫停）"
                !faceKnown -> "目前不行（狀態未知）"
                !snap.facePresent -> "目前不行（找不到臉部）"
                else -> "目前不行（定位中）"
            },
            unknown = false,
        )
        // 校正品質：顯示校正時真實算出的留一法誤差，等級門檻見 docs/evaluation.md。
        val loo = CalibrationStore.getMeta(this, activeId)?.looErrorPx
        binding.valLooError.text = if (loo != null && loo.isFinite()) {
            val rating = when {
                loo < 60 -> "優秀"
                loo < 120 -> "良好"
                loo < 200 -> "普通"
                else -> "較差，建議重校"
            }
            "留一法誤差 ${"%.0f".format(loo)} px（$rating）"
        } else {
            "尚未校正，校正後會顯示留一法誤差"
        }

        // ---- 設定摘要：即時讀 GazeSettings，不新增 key ----
        val s = GazeSettings.load(this)
        binding.valDwellSummary.text = "${s.dwellTimeMs} 毫秒"
        binding.valConditionSummary.text = if (s.conditionCorrection) "開啟" else "關閉"
        binding.valDistanceSummary.text = if (s.distanceCompensation) "開啟" else "關閉"

        // ---- 操作按鈕狀態：服務實例不存在或狀態不適用時直接 disable，不留空轉的假按鈕 ----
        // 開始（D1-14）：只在「眼控運作中」時禁用；閘門未過（未校正／服務未開／
        // 引擎失敗／尚未收到畫面）時保持可按，點按後由閘門診斷並導引——第 2 步
        // 「只在已暫停／已停止時啟用」的規則會讓閘門診斷分支永遠按不到，
        // 本步改為「運作中以外皆可按」。暫停／停止鈕維持既有規則。
        val trackingRunning = snap != null && !snap.stopped && !snap.paused &&
            !snap.inRecoveryMode &&
            snap.flowState == AppFlowState.SERVICE_RUNNING
        // D2-6：RECOVERY_MODE 時「開始眼控」鈕改為「已可注視重啟」提示態
        // （保持可按，手點走同一驗證序列）；其餘狀態恢復原文字。
        if (snap?.inRecoveryMode == true) {
            binding.btnStartTracking.text = "已可注視重啟"
            binding.btnStartTracking.contentDescription = "已可注視重啟，點按以手動驗證重啟"
        } else {
            binding.btnStartTracking.text = "開始眼控"
            binding.btnStartTracking.contentDescription = "開始眼控"
        }
        binding.btnStartTracking.isEnabled = !trackingRunning
        binding.btnPauseTracking.isEnabled = svcOk &&
            snap != null && !snap.paused && !snap.stopped && !snap.inRecoveryMode
        binding.btnStopTracking.isEnabled = svcOk && snap != null && !snap.stopped
    }

    /**
     * CALIBRATED 態的狀態列文字（STATE_DESIGN §7）：留一法誤差＋等級＋上次校正天數，
     * 數字全部來自 CalibrationStore 的真實存檔。calibratedAt 為 0（舊版遷移）或
     * 留一法誤差未知時，如實寫「未知」，不編造。
     */
    private fun calibratedStatusText(activeId: String): String {
        val profileName = CalibrationStore.profileName(activeId)
        val meta = CalibrationStore.getMeta(this, activeId)
        val loo = meta?.looErrorPx
        val looText = if (loo != null && loo.isFinite()) {
            val rating = when {
                loo < 60 -> "優秀"
                loo < 120 -> "良好"
                loo < 200 -> "普通"
                else -> "較差，建議重校"
            }
            "留一法誤差 ${"%.0f".format(loo)} px，$rating"
        } else {
            "留一法誤差未知"
        }
        val whenText = if (meta != null && meta.calibratedAt > 0L) {
            val days = ((System.currentTimeMillis() - meta.calibratedAt) / 86_400_000L)
                .coerceAtLeast(0L)
            "上次校正：${days} 天前。"
        } else {
            "上次校正時間未知。"
        }
        return "已校正「$profileName」情境（$looText）。$whenText"
    }

    /**
     * 狀態列著色：綠 = 正常、紅 = 異常、灰 = 未知。永遠同時有文字，不只靠顏色。
     * dot_circle drawable 是白底圓形，以 backgroundTint 著色。
     */
    private fun setRow(
        dot: android.view.View,
        value: android.widget.TextView,
        ok: Boolean,
        okText: String,
        badText: String,
        unknown: Boolean,
    ) {
        value.text = if (unknown) badText else if (ok) okText else badText
        val color = when {
            unknown -> R.color.ec_text_muted
            ok -> R.color.ec_success
            else -> R.color.ec_error
        }
        dot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, color),
        )
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

    companion object {
        private const val STATUS_POLL_MS = 1000L
    }
}
