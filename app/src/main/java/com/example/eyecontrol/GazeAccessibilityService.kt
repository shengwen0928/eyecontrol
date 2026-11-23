package com.example.eyecontrol

import android.graphics.RectF
import android.widget.Toast
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.math.hypot
import kotlin.math.min
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat

/**
 * 眼控核心服務：
 *   前鏡頭(FaceTrackerEngine) → GazeModel 對應螢幕座標 → OneEuro 平滑
 *   → 全螢幕覆蓋層畫游標 → 凝視停留(dwell)觸發 dispatchGesture 點擊。
 *
 * 覆蓋層使用 TYPE_ACCESSIBILITY_OVERLAY 且不可觸控，注入的手勢會直接穿透到底下的 App。
 * 實作 LifecycleOwner 是為了讓 CameraX 能綁定在 Service 上。
 */
class GazeAccessibilityService : AccessibilityService(), LifecycleOwner {

    enum class ActionMode {
        TAP, DOUBLE_TAP, LONG_PRESS, SWIPE, PAUSED
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var engine: FaceTrackerEngine? = null

    /** 情境模型：profileListener 可能在非主執行緒寫入，主執行緒讀取須可見。 */
    @Volatile
    private var model: GazeModel? = null

    /** 上一次記錄的臉轉向（只在變化時寫 log，避免每幀刷 log）。 */
    private var lastFaceTurn: FaceTurn? = null
    private var cursorView: CursorOverlayView? = null
    private var controlView: ControlPanelView? = null

    /** 使用者可調參數；偏好變更時即時重載，服務運作中也會生效。 */
    @Volatile
    private var settings = GazeSettings()

    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val wasRemoteUploadEnabled = remoteUploadEnabled()
            settings = GazeSettings.load(this)
            // 撤回遠端監控同意（STEP 3，R-1）：只有「開 → 關」的轉換才
            // 丟棄當下檔案——close(upload = false) 關檔且不提交上傳、
            // 並重置 pendingUpload；開啟狀態下的其他設定變更不得觸發
            // 關檔。影格路徑不動（排隊中影格執行時重查開關，
            // 見 VerifyFrames.storeAndUpload）。
            if (GuardDecisions.shouldDiscardCurrentFileOnRevoke(
                    wasRemoteUploadEnabled, remoteUploadEnabled(),
                )
            ) {
                GazeLog.close(upload = false)
            }
            applyVerifyCaptureFlags()
        }

    /**
     * 遠端上傳是否生效：開關開著，且目前是側載版（Play 商店版無網路權限，
     * BuildConfig.REMOTE_MONITOR_ENABLED 為 false，上傳不可能發生）。
     */
    private fun remoteUploadEnabled(): Boolean =
        settings.remoteMonitor && BuildConfig.REMOTE_MONITOR_ENABLED

    /** 把目前的診斷／監控開關同步給追蹤引擎的驗證影格（引擎重建與設定變更時呼叫）。 */
    private fun applyVerifyCaptureFlags() {
        engine?.setVerifyCapture(
            local = settings.diagnosticLogging,
            upload = remoteUploadEnabled(),
        )
    }

    /** 情境或情境模型變更時即時換模型，服務運作中切換情境也會生效。 */
    private val profileListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            model = CalibrationStore.loadModel(this, CalibrationStore.getActiveProfileId(this))
            // reloadConditionState 觸及 overlay view，必須在主執行緒執行；
            // 此 listener 可能在非主執行緒回呼，故 post 到主執行緒。
            mainHandler.post {
                reloadConditionState()
                // 第 4 步遺留 (a)：換情境／換模型後 flowState 必須重推導，
                // 否則切到未校正情境仍顯示 SERVICE_RUNNING。推導走同一
                // syncFlowState（事實優先，見 (c) 的說明）：模型出現／失效
                // 事件在部分來源態無合法邊時以推導結果和解，不改事件語義。
                val hasModel = CalibrationStore.exists(
                    this, CalibrationStore.getActiveProfileId(this),
                )
                syncFlowState(
                    if (hasModel) FlowEvent.ModelAppeared else FlowEvent.ModelInvalidated,
                    deriveFlowState(hasModel),
                )
            }
        }

    private val filterX = OneEuroFilter(minCutoff = 0.2, beta = 0.015)
    private val filterY = OneEuroFilter(minCutoff = 0.2, beta = 0.015)

    /** 校正頁開啟期間暫停追蹤（相機同程序只有一路，兩邊搶會互踢）。 */
    @Volatile
    private var pausedForCalibration = false

    /** 僅 `stopCompletely` 使用的完全停止旗標：引擎停表且不再自動重啟，直到按「開始眼控」。
     * 一般「停止眼控」不立此旗標，改走 [inRecoveryMode]（D2-1 語義分離）。 */
    @Volatile
    private var userStopped = false

    /**
     * RECOVERY_MODE 旗標（D2-1）：「停止眼控」後立此旗標，引擎續跑、相機
     * 開著，只等注視重啟目標。僅 `stopTracking` 立、重啟成功／驗證失敗／
     * `stopCompletely` 清。
     *
     * 已知缺口 K-30：本旗標與 [userStopped] 皆未持久化，行程重啟即丟失，
     * `onServiceConnected` 不檢查舊旗標、現況自動恢復語義維持不變；
     * 持久化與否待 D-8 決定，不得宣稱為已修復。
     */
    @Volatile
    private var inRecoveryMode = false

    /**
     * 重啟目標的獨立線性 dwell 計時狀態（D2-2，與面板把手同一模式，
     * 秒數取 `settings.dwellTimeMs`）。臉遺失／閉眼時進度凍結不清零，
     * 沿用現況閉眼凍結語義。只在主執行緒讀寫。
     */
    private var recoveryDwellAccumMs = 0L
    private var recoveryLastFrameAt = 0L

    /**
     * 重啟目標武裝旗標（D2-4，沿用 `handleArmed` 模式）：重啟成功後設 false，
     * 視線須先離開目標區一次才重新武裝（下次進入 RECOVERY_MODE 前、
     * 或在 RECOVERY_MODE 內離開目標區時武裝）。
     */
    private var recoveryArmed = true

    /** 重啟驗證序列進行中（D2-4）：進行中忽略新的目標 dwell，避免重複觸發。 */
    private var recoveryVerifying = false

    /**
     * 邊緣手勢控制器（Phase 3 D3-1…D3-8，純 Kotlin）：每幀由主路徑餵同一幀
     * 的已算數據，只做判決、不執行動作。提醒／RECOVERY／PAUSED／校正交接
     * 期間不餵幀（各分支提早返回），中斷時以 [cancelEdgeGesture] 強制回 IDLE。
     */
    private val edgeController = EdgeGestureController()

    /** 確認目標是否正在顯示（服務端互斥保證：提醒／重啟／確認同一時間只顯示一種）。 */
    private var edgeConfirmShowing = false

    /** 邊緣被擋擷取是否已在本次進入帶內發過（避免每幀重複擷取）。 */
    private var edgeDeniedCaptured = false

    /**
     * 流程層狀態（Phase 3 D1-2）：狀態機持有者＋對外可讀的現態鏡像。
     * 寫入只在主執行緒（onServiceConnected／startEngine／交接／停止的
     * mainHandler 回呼內）；@Volatile 保證 Dashboard 讀取端可見。
     * 底層事實來源仍是 userStopped／inRecoveryMode／pausedForCalibration 等旗標，
     * flowState 只做同步鏡像，不取代旗標語義、不改變任何旗標行為。
     */
    private val flowLogger = FlowStateLogger { message -> Log.w(TAG, message) }

    private var flowMachine = AppFlowStateMachine(logger = flowLogger)

    @Volatile
    var flowState: AppFlowState = AppFlowState.NOT_CALIBRATED
        private set

    /**
     * 以事實推導流程態（STATE_DESIGN §2.2，優先序：停止 ＞ 恢復 ＞ 校正交接 ＞ 模型存在）。
     * D2-1 語義分離後：`userStopped`（僅 `stopCompletely`）→ STOPPED；
     * `inRecoveryMode`（`stopTracking`）→ RECOVERY_MODE（引擎仍在跑、可注視重啟）。
     */
    private fun deriveFlowState(hasModel: Boolean): AppFlowState = when {
        userStopped -> AppFlowState.STOPPED
        inRecoveryMode -> AppFlowState.RECOVERY_MODE
        pausedForCalibration -> AppFlowState.CALIBRATING
        !hasModel -> AppFlowState.NOT_CALIBRATED
        else -> AppFlowState.SERVICE_RUNNING
    }

    /**
     * 初始態推導（onServiceConnected 專用）：以推導結果為狀態機初始態，
     * 這是初始推導、不是轉換事件。模型存在即 SERVICE_RUNNING（行程重啟不
     * 檢查舊旗標、現況自動恢復語義，STATE_DESIGN §2.2 註）；無模型即
     * NOT_CALIBRATED。startEngine 會再依成敗同步為 SERVICE_RUNNING／ERROR。
     */
    private fun deriveInitialFlowState() {
        val initial = deriveFlowState(
            CalibrationStore.exists(this, CalibrationStore.getActiveProfileId(this)),
        )
        flowMachine = AppFlowStateMachine(initial, flowLogger)
        flowState = initial
    }

    /**
     * 在同步點推進狀態機：先試自然事件；事件不合法時（轉換表無此邊，
     * 例如 CALIBRATING 直接回 SERVICE_RUNNING），以當下事實推導結果和解：
     * 重新以推導結果為初始態建立狀態機，確保 flowState 永不與旗標脫節。
     * 和解時記一筆警告，供審查追蹤轉換表缺邊。Failure 事件不走和解：
     * 它可由任一態進 ERROR，且須更新原因（即使已在 ERROR 態）。
     *
     * 第 4 步遺留 (c) 的決定（維持推導和解，不補邊）：SERVICE_RUNNING→
     * CALIBRATING、CALIBRATING→SERVICE_RUNNING、CALIBRATED→SERVICE_RUNNING
     * 三對來源／目標在轉換表上無邊，是刻意的——服務端的 flowState 是旗標
     * 與模型事實的推導鏡像（STATE_DESIGN §2.2），不是引導旅程的事件序列；
     * 交接（requestPause／resume）與情境切換都是「事實變了，重新推導」，
     * 沒有既有事件能不扭曲語義地表達（ServiceVerified 是引導返回驗證、
     * RestartVerified 是 RECOVERY 重啟、CalibrationSucceeded 是九點存檔成功，
     * 三者皆非「引擎重啟」）。補邊須新事件名或挪用舊事件語義，兩者皆在本步
     * 紅線之外；和解路徑已保證狀態永不脫節且留 Log.w 痕跡，故保留現況，
     * 理由亦寫入 AppFlowState.kt 檔頭。D1-2 的 syncFlowState 行為與此決定一致。
     */
    private fun syncFlowState(event: FlowEvent, target: AppFlowState) {
        if (event is FlowEvent.Failure) {
            flowMachine.transition(event)
            flowState = flowMachine.state
            return
        }
        if (flowState == target) return
        if (flowMachine.transition(event) && flowMachine.state == target) {
            flowState = flowMachine.state
            return
        }
        Log.w(
            TAG,
            "流程態和解：$flowState + ${event::class.simpleName} 無合法轉換，依事實推導為 $target",
        )
        flowMachine = AppFlowStateMachine(target, flowLogger)
        flowState = target
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 模式與手勢狀態
    private var currentMode = ActionMode.TAP
    private var swipeStartPoint: Pair<Float, Float>? = null

    /**
     * 待發的雙擊第二下（STEP 3 薄守衛，K-07）：具名持有，方可取消。
     * 舊版為匿名 postDelayed，模式切換／暫停／停止／提醒介入時無法
     * 取消、會在背景排隊執行。只在主執行緒讀寫。
     */
    private var pendingDoubleTapSecond: Runnable? = null

    /** 集中式手勢派發守衛（DCL-04，STEP 3）：四個 perform* 的 dispatchGesture
     *  皆經此守衛配對唯一 ID，過期回呼隔離、清理單次、不重播。 */
    private val dispatchGuard = GestureDispatchGuard()

    // 控制面板注視狀態
    private var hoverBtnIndex = -1
    private var hoverStart = 0L

    // 控制面板收合狀態：平時只留右緣小把手，注視把手才展開整列；
    // 選完模式或閒置數秒自動收回，暫停模式下保持展開（要看得到「重啟」）。
    private var panelExpanded = false
    private var handleHoverStart = 0L
    private var panelCollapseAt = 0L
    private var lastPanelHoverAt = 0L

    /**
     * 把手是否已武裝：收起後視線必須先離開把手區一次，下次注視才會展開。
     * 避免選完模式、視線還停在原地時立刻又把面板展開。
     */
    private var handleArmed = false

    /**
     * 面板按鈕是否已武裝：選完一個模式後，視線必須先離開面板區一次，
     * 下次注視同一鍵才會重新累積。否則選了「暫停」後視線還停在原地，
     * 滿一個 dwell 就會立刻「重啟」，暫停黏不住。
     */
    private var panelButtonArmed = true

    // 凝視停留狀態
    private var anchorX = 0f
    private var anchorY = 0f
    private var dwellStart = 0L
    private var cooldownUntil = 0L

    /** 螢幕點擊的自適應停留狀態機（錨點跟漂、進度衰減、低速幀才累積）。 */
    private val screenDwell = DwellTracker()

    // 眨眼檢測狀態
    private var isBlinking = false
    private var blinkStartTime = 0L
    private var lastValidX = 0f
    private var lastValidY = 0f
    private var lastCursorX = 0f

    /** 是否已收到至少一幀有效游標位置：沒有之前，眨眼不得點擊（否則會點在 (0,0)）。 */
    private var hasValidCursor = false
    private var lastCursorY = 0f

    private var screenW = 0
    private var screenH = 0

    // 條件感知漂移修正狀態（只在主執行緒讀寫，與 onSampleOnMainThread 同執行緒）
    private var appliedOffsetX = 0f
    private var appliedOffsetY = 0f
    private var smoothedPitch = Double.NaN
    private var smoothedRoll = Double.NaN
    private var smoothedIod = Double.NaN
    private var smoothedLux = Float.NaN
    private var smoothedGlassesHint = Double.NaN
    private var conditionSeeded = false
    private var lastConditionCheckAt = 0L
    private var reminderCooldownUntil = 0L

    // 提醒（單點快速校正）狀態
    private var reminderActive = false
    private var reminderShownAt = 0L
    private var reminderDwellAccumMs = 0L
    private var reminderLastFrameAt = 0L
    private var reminderAwaySince = 0L
    private val reminderRawPoints = mutableListOf<Pair<Double, Double>>()

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        instance = this

        // 啟動前台服務通知，防止被系統記憶體回收
        startForegroundService()

        // 載入使用者參數，並監聽變更即時套用
        settings = GazeSettings.load(this)
        getSharedPreferences(GazeSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(settingsListener)
        CalibrationStore.prefs(this)
            .registerOnSharedPreferenceChangeListener(profileListener)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()

        addCursorOverlay(wm)
        addControlPanel(wm)
        // 流程態初始推導（D1-2）：見 deriveInitialFlowState 註解。
        deriveInitialFlowState()
        // 重啟恢復重查（STEP 3，DCL-05 最小部分）：行程重建後自動恢復前，
        // 重新檢查相機權限與服務啟用狀態；未通過時不啟動引擎、進 ERROR
        // 並帶原因，不得無條件恢復。旗標持久化屬 P-03，不在此做。
        if (!isServiceEnabled(this) ||
            checkSelfPermission(android.Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "重啟恢復重查未通過：相機權限或服務啟用狀態異常，不自動恢復追蹤")
            syncFlowState(
                FlowEvent.Failure("無法恢復追蹤：請確認相機權限已授予且凝視服務已開啟"),
                AppFlowState.ERROR,
            )
        } else {
            startEngine()
        }
    }

    /**
     * 螢幕旋轉時螢幕尺寸變了：預測、clamp 與面板判定全吃 screenW/screenH，
     * 不更新會把游標卡在半邊螢幕；舊濾波狀態是舊座標系的，一併重置。
     * 面板與游標 overlay 分別靠 gravity 與 MATCH_PARENT 跟著轉，不必重建。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()
        filterX.reset()
        filterY.reset()
        // 旋轉後座標系全變：提醒收掉、條件狀態重置，等下次評估重新推算
        reloadConditionState()
        // 方向改變：邊緣手勢強制重置（D-3 防止重複觸發段），確認目標收掉；
        // 控制器亦以幾何比對為第二道防線（下一幀幾何不同即取消進行中手勢）。
        cancelEdgeGesture()
    }

    private fun startForegroundService() {
        val channelId = "gaze_service_channel"
        val channelName = "眼控服務通知"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("眼控輔助服務已啟動")
            .setContentText("正在使用前鏡頭進行眼動追蹤")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1001, notification)
        }
    }

    /** 建立並啟動追蹤引擎；同時（重新）載入目前情境的校正模型。校正後恢復也走這裡。 */
    private fun startEngine() {
        if (pausedForCalibration || userStopped) return
        // 冪等重啟：先停舊引擎，否則新舊兩台會搶相機（舊的在背景執行緒 stop 時也可能誤殺新的）
        engine?.stop()
        engine = null
        val activeId = CalibrationStore.getActiveProfileId(this)
        model = CalibrationStore.loadModel(this, activeId)
        if (model == null) {
            Log.w(TAG, "情境「${CalibrationStore.profileName(activeId)}」尚未校正，游標無法運作——請先在 App 內完成該情境的校正")
        }
        filterX.reset()
        filterY.reset()
        reloadConditionState()
        engine = FaceTrackerEngine(this) { sample -> onSample(sample) }
        try {
            engine?.start(this)
        } catch (e: Exception) {
            // 引擎同步啟動失敗（例如 FaceLandmarker 建立失敗）：記檔、清引擎、
            // 流程態進 ERROR 並帶真實原因（校正頁 startEngineSafely 的同一模式）。
            // 第 4 步遺留 (d) 已知缺口：相機非同步綁定失敗在引擎內部只記 Log、
            // 不拋出、無任何可觀測信號（FaceTrackerEngine.start 的綁定回呼
            // catch 後只 Log.e「相機綁定失敗」，無回呼、無狀態旗標；landmarker
            // 的 ErrorListener 同樣只記 Log），本步無法以此路徑偵測，不得宣稱
            // 已覆蓋。綁定失敗時唯一的間接徵兆是再也收不到樣本，由 D1-15 的
            // verifyServiceRunning（快照新鮮度）擋下「驗證通過」，不進 ERROR。
            CrashReporter.recordCaught(this, "engine.start", e)
            Log.e(TAG, "引擎啟動失敗", e)
            engine = null
            syncFlowState(
                FlowEvent.Failure("引擎啟動失敗：${e.message ?: e::class.simpleName}"),
                AppFlowState.ERROR,
            )
            return
        }
        applyVerifyCaptureFlags()
        syncFlowState(
            FlowEvent.ServiceVerified,
            deriveFlowState(hasModel = model != null),
        )
    }

    private fun addCursorOverlay(wm: WindowManager) {
        val view = CursorOverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 不可觸控、不可聚焦：注入的手勢與使用者的手指都能穿透
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(view, lp)
        cursorView = view
    }

    private fun addControlPanel(wm: WindowManager) {
        val view = ControlPanelView(this)
        val lp = WindowManager.LayoutParams(
            180, // 寬度 180 像素
            700, // 高度 700 像素
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 不可觸控、不可聚焦
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        wm.addView(view, lp)
        controlView = view
        // 預設收合：只留右緣小把手，注視把手才展開整列面板。
        view.setPanelCollapsed(true)
    }

    /** 展開／收起模式面板，並同步 view 的繪製狀態與注視暫存。 */
    private fun setPanelExpanded(expanded: Boolean) {
        panelExpanded = expanded
        panelCollapseAt = 0L
        hoverBtnIndex = -1
        handleHoverStart = 0L
        handleArmed = false
        lastPanelHoverAt = SystemClock.elapsedRealtime()
        controlView?.setPanelCollapsed(!expanded)
        controlView?.setHover(-1, 0f)
    }

    // 上一幀的停留進度
    private var currentProgress = 0f

    /** 每一幀的處理（背景執行緒進來，UI 更新丟回主執行緒）。 */
    private fun onSample(sample: GazeSample?) {
        if (pausedForCalibration || userStopped) return
        mainHandler.post {
            onSampleOnMainThread(sample)
        }
    }

    private fun onSampleOnMainThread(sample: GazeSample?) {
        if (pausedForCalibration) return
        // Dashboard 即時狀態：只記錄「有無收到樣本／有無臉／有無有效游標／有無模型」，
        // 不存影像與特徵；新鮮度由讀取端以 lastSampleAtMs 判斷。
        lastSampleAtMs = SystemClock.elapsedRealtime()
        lastFacePresent = sample != null
        lastCursorValid = hasValidCursor
        lastHasModel = model != null

        // RECOVERY_MODE（D2-1）：單一拒絕分支，位於入口後、主路徑前。
        // 引擎續跑、樣本續進，但一般 dwell／眨眼／邊緣／面板輸入全部拒絕；
        // 只跑重啟目標分支（D2-2／D2-3），沿用現況提早返回模式。
        if (inRecoveryMode) {
            updateRecoveryTarget(sample, SystemClock.elapsedRealtime())
            return
        }

        val m = model ?: return
        val cursor = cursorView ?: return

        if (sample == null) {
            // 偵測不到臉：游標留在原地變灰，讓使用者知道是「臉不見了」而不是當機
            cursor.setFaceLost()
            // 把手與面板的注視進度一併清掉：臉遺失可能持續數秒，殘留的
            // 起始時間會讓恢復後的第一幀直接展開面板或誤觸按鈕。
            handleHoverStart = 0L
            controlView?.setHandleProgress(0f)
            hoverBtnIndex = -1
            hoverStart = 0L
            controlView?.setHover(-1, 0f)
            // 螢幕停留進度也清掉：臉遺失期間的進度不得在恢復後沿用。
            screenDwell.reset()
            // 邊緣手勢強制重置（D3-8）：追蹤遺失後不執行舊手勢，須重新累積。
            cancelEdgeGesture()
            // 臉遺失殘留清除（STEP 3，DCL-11／T-06）：眨眼狀態、滑動起點、
            // 濾波歷史不得沿用至臉部重現後的第一幀；待發的雙擊第二下一併
            // 取消。reset() 只清濾波器狀態、不改濾波參數（見 OneEuroFilter）。
            // hasValidCursor 不清：游標留原地變灰為有意顯示語義，待 P-08 決定。
            isBlinking = false
            blinkStartTime = 0L
            swipeStartPoint = null
            controlView?.swipeState = 0
            controlView?.invalidate()
            filterX.reset()
            filterY.reset()
            cancelPendingDoubleTap()
            // 診斷紀錄也要記臉遺失幀：邊緣看不到臉時，游標不動可能是追蹤丟失而非模型問題。
            // 偏移記目前實際套用的值（與主路徑同一算法），免得摘要的平均偏移被 0 拉偏。
            // 亮度記目前平滑值（臉遺失與光線有關時，摘要看得到）；影像度量無樣本，記空欄。
            GazeLog.record(
                this, settings.diagnosticLogging || settings.remoteMonitor,
                remoteUpload = remoteUploadEnabled(),
                face = false, features = null,
                rawX = null, rawY = null, gainedX = null, gainedY = null,
                curX = null, curY = null, sensitivity = settings.sensitivity,
                offsetX = if (settings.conditionCorrection) appliedOffsetX else 0f,
                offsetY = if (settings.conditionCorrection) appliedOffsetY else 0f,
                lux = smoothedLux.takeIf { it.isFinite() && it >= 0f },
            )
            // 提醒顯示中也要推進狀態機（逾時／移開邏輯），否則臉遺失期間提醒永遠不消失
            if (reminderActive) {
                updateReminder(null, null, null, null, SystemClock.elapsedRealtime())
            }
            return
        }

        val now = SystemClock.elapsedRealtime()

        // 偵測雙眼是否都閉合（眨眼點擊檢測）
        val leftClosed = sample.leftOpen < settings.blinkThreshold
        val rightClosed = sample.rightOpen < settings.blinkThreshold
        val bothClosed = leftClosed && rightClosed

        if (bothClosed) {
            if (!isBlinking) {
                isBlinking = true
                blinkStartTime = now
                // 記錄閉眼前最後一個有效的游標位置
                lastValidX = lastCursorX
                lastValidY = lastCursorY
                // 閉眼期間的停留進度作廢，睜眼後重新累積。
                screenDwell.reset()
            }
            // 閉眼期間凍結游標、暫停 dwell 累積，且不進行後續預測
            return
        } else {
            if (isBlinking) {
                isBlinking = false
                val blinkDuration = now - blinkStartTime
                // 故意眨眼點擊：閉眼時間在 150ms 到 600ms 之間，且非暫停模式；
                // 提醒顯示期間不觸發點擊（提醒狀態機接管這一幀的後續流程）。
                // 獨立開關預設關閉（DCL-13，STEP 3）：判定收斂至
                // GuardDecisions，開關關閉時永不觸發；意圖校正閘門待 P-05。
                if (GuardDecisions.shouldTriggerBlinkClick(
                        blinkClickEnabled = settings.blinkClickEnabled,
                        blinkDurationMs = blinkDuration,
                        blinkMinMs = settings.blinkMinMs,
                        blinkMaxMs = settings.blinkMaxMs,
                        hasValidCursor = hasValidCursor,
                        cooldownElapsed = now >= cooldownUntil,
                        paused = currentMode == ActionMode.PAUSED,
                        reminderActive = reminderActive,
                    )
                ) {
                    triggerActionAt(lastValidX, lastValidY)
                    cooldownUntil = now + settings.cooldownMs
                    dwellStart = now
                    currentProgress = 0f
                    screenDwell.reset()
                    return
                }
            }
        }

        // 單眼閉合（或雙眼閉合後的釋放幀不穩定），凍結游標不處理
        if (leftClosed || rightClosed) {
            screenDwell.reset()
            return
        }

        // 動態平滑調整：若上一幀在進行停留點擊，則極大化濾波平滑度以鎖定游標；若在移動，則提高反應跟手度。
        if (currentProgress > 0f) {
            filterX.updateParams(minCutoff = 0.04, beta = 0.005)
            filterY.updateParams(minCutoff = 0.04, beta = 0.005)
        } else {
            filterX.updateParams(minCutoff = settings.filterMinCutoff, beta = settings.filterBeta)
            filterY.updateParams(minCutoff = settings.filterMinCutoff, beta = settings.filterBeta)
        }

        // 對照實驗（review 分支限定，2026-11-23）：關閉 kx/ky 頭部姿態補償，
        // 比較仰角漂移是否消失；合併前須經決定，不得視為定稿行為。
        // 連續光輸入：每幀的當前 lux 餵給模型（夾取到訓練跨度內），絕不以固定值打發。
        val (rawX, rawY) = m.predict(
            sample.features, screenW, screenH,
            sample.devicePitch, sample.deviceRoll,
            settings.gyroCompensation, settings.distanceCompensation,
            headPoseCompensation = false,
            logLux = GazeFeatureExtractor.lightLogLux(sample.ambientLux),
        )

        // 條件取樣：每幀平滑亮度／角度／臉距，供變化偵測與歷史紀錄用
        updateSmoothedCondition(sample)

        // 條件感知漂移修正的偏移：預測之後、濾波之前加上；開關關閉時不套用
        val effOffsetX = if (settings.conditionCorrection) appliedOffsetX else 0f
        val effOffsetY = if (settings.conditionCorrection) appliedOffsetY else 0f
        val correctedX = rawX + effOffsetX
        val correctedY = rawY + effOffsetY

        // 靈敏度：預測偏移相對螢幕中心放大，濾波之前套用；1.0 = 不變。
        // 模型輸出偏保守時，游標會聚在中間附近、眼球要移到底才到得了邊緣，
        // 放大偏移讓小幅眼球移動就能覆蓋全螢幕。
        val gain = settings.sensitivity.toDouble()
        val centerX = screenW / 2.0
        val centerY = screenH / 2.0
        val gainedX = centerX + (correctedX - centerX) * gain
        val gainedY = centerY + (correctedY - centerY) * gain

        // 臉轉向辨識（只記錄變化）：方向補償本身在模型裡以帶符號差值處理，這裡僅讓轉向可見
        val faceTurn = GazeFeatureExtractor.faceTurnDirection(sample.features, m.basePitch, m.baseYaw)
        if (faceTurn != lastFaceTurn) {
            lastFaceTurn = faceTurn
            Log.d(TAG, "臉轉向：$faceTurn")
        }

        // 眼部影像降級幀（過暗、過平、鏡片反光）：預測不可信，游標凍在上一位置、
        // 不驅動停留累積、不碰濾波狀態；提醒顯示中則以「不在目標上」推進提醒狀態機。
        // 度量未知（NaN）不等於降級，照常處理。
        if (EyeImageMetrics.isDegraded(sample)) {
            // 影像降級幀不可信：邊緣候選／確認一併中斷，不得沿用舊進度。
            cancelEdgeGesture()
            GazeLog.record(
                this, settings.diagnosticLogging || settings.remoteMonitor,
                remoteUpload = remoteUploadEnabled(),
                face = true, features = sample.features,
                rawX = rawX, rawY = rawY, gainedX = gainedX, gainedY = gainedY,
                curX = lastCursorX.takeIf { hasValidCursor },
                curY = lastCursorY.takeIf { hasValidCursor },
                sensitivity = settings.sensitivity,
                offsetX = effOffsetX, offsetY = effOffsetY,
                lux = sample.ambientLux.takeIf { it.isFinite() && it >= 0f },
                imageQuality = sample.eyeContrast.takeIf { it.isFinite() },
                glassesHint = sample.specularRatio.takeIf { it.isFinite() },
            )
            screenDwell.reset()
            currentProgress = 0f
            if (reminderActive) {
                cursor.setCursor(
                    if (hasValidCursor) Triple(lastCursorX, lastCursorY, 0f) else null
                )
                updateReminder(null, null, null, null, now)
                return
            }
            cursor.setCursor(
                if (hasValidCursor) Triple(lastCursorX, lastCursorY, 0f) else null
            )
            return
        }

        val t = sample.timestampMs / 1000.0
        val x = filterX.filter(gainedX, t).toFloat().coerceIn(0f, screenW - 1f)
        val y = filterY.filter(gainedY, t).toFloat().coerceIn(0f, screenH - 1f)

        // 更新最後的有效位置
        lastCursorX = x
        lastCursorY = y
        hasValidCursor = true

        // 診斷紀錄（開關關閉時此呼叫只負責關檔，不寫入）：記原始預測、放大後、
        // 濾波後三段座標，才能分清是哪一段把移動吃掉或推偏。
        GazeLog.record(
            this, settings.diagnosticLogging || settings.remoteMonitor,
            remoteUpload = remoteUploadEnabled(),
            face = true, features = sample.features,
            rawX = rawX, rawY = rawY, gainedX = gainedX, gainedY = gainedY,
            curX = x, curY = y, sensitivity = settings.sensitivity,
            offsetX = effOffsetX, offsetY = effOffsetY,
            lux = sample.ambientLux.takeIf { it.isFinite() && it >= 0f },
            imageQuality = sample.eyeContrast.takeIf { it.isFinite() },
            glassesHint = sample.specularRatio.takeIf { it.isFinite() },
        )

        // 條件變化評估（約每 0.5 秒一次）：變化大時查歷史內插，沒遇過才跳提醒
        if (now - lastConditionCheckAt >= CONDITION_CHECK_INTERVAL_MS) {
            lastConditionCheckAt = now
            evaluateConditions(now)
        }

        // 提醒顯示期間：只跑提醒狀態機，不做面板／停留／邊緣點擊，避免誤觸
        if (reminderActive) {
            cursor.setCursor(Triple(x, y, 0f))
            updateReminder(x, y, rawX, rawY, now)
            currentProgress = 0f
            return
        }

        var progress = 0f

        // 控制面板：展開時是 180×700 整列；收合時只剩右緣把手判定區，
        // 其餘右緣恢復成一般螢幕（凝視點擊與右緣下滑手勢都回來了）。
        val panelWidth = 180f
        val panelHeight = 700f
        val panelLeft = screenW - panelWidth
        val panelTop = (screenH - panelHeight) / 2f
        val panelBottom = panelTop + panelHeight

        val handleLeft = screenW - ControlPanelView.HANDLE_HIT_WIDTH
        val handleTop = (screenH - ControlPanelView.HANDLE_HIT_HEIGHT) / 2f
        val handleBottom = handleTop + ControlPanelView.HANDLE_HIT_HEIGHT
        val gazeInHandleZone = x >= handleLeft && y >= handleTop && y <= handleBottom
        val inHandleZone = !panelExpanded && gazeInHandleZone
        val inPanel = panelExpanded && x >= panelLeft && y >= panelTop && y <= panelBottom

        // 自動收回：選完模式 1.2 秒後，或展開後閒置 4 秒且視線不在面板上；
        // 暫停模式不自動收（要看得到「重啟」）。收回的這一幀走一般螢幕邏輯，
        // 避免沿用展開態的面板命中判定多跑一次。
        var panelJustCollapsed = false
        if (panelExpanded && currentMode != ActionMode.PAUSED) {
            if ((panelCollapseAt != 0L && now >= panelCollapseAt) ||
                (!inPanel && now - lastPanelHoverAt > PANEL_AUTO_COLLAPSE_MS)
            ) {
                setPanelExpanded(false)
                panelJustCollapsed = true
            }
        }

        if (!panelJustCollapsed && inHandleZone) {
            // 進入把手區：面板接管，邊緣手勢中斷（把手命中區優先於邊緣分支，現況優先序保留）。
            cancelEdgeGesture()
            // 注視把手展開面板：同一套 dwell 節奏，視線離開就重算。
            // 未武裝或冷卻期間不累積進度，但同樣不觸發螢幕點擊——
            // 把手區是面板的地盤，視線停在這裡不該點到下層 App。
            if (handleArmed && now >= cooldownUntil) {
                if (handleHoverStart == 0L) handleHoverStart = now
                val hp = min((now - handleHoverStart) / settings.dwellTimeMs.toFloat(), 1f)
                controlView?.setHandleProgress(hp)
                if (hp >= 1f) {
                    setPanelExpanded(true)
                    cooldownUntil = now + settings.cooldownMs
                }
            } else {
                handleHoverStart = 0L
                controlView?.setHandleProgress(0f)
            }
            // 在把手上時，不累積螢幕點擊的 dwell 進度
            anchorX = x; anchorY = y; dwellStart = now
            screenDwell.reset()
        } else if (!panelJustCollapsed && inPanel) {
            // 進入面板區：面板接管，邊緣手勢中斷（面板分支優先於邊緣分支，現況優先序保留）。
            cancelEdgeGesture()
            val relativeY = y - panelTop
            val btnHeight = panelHeight / 5f
            val btnIndex = (relativeY / btnHeight).toInt().coerceIn(0, 4)

            // 如果是暫停狀態，只有注視最後一個按鈕（重啟）才有反應
            if (currentMode == ActionMode.PAUSED && btnIndex != 4) {
                controlView?.setHover(-1, 0f)
            } else if (!panelButtonArmed || now < cooldownUntil) {
                // 選完模式後視線還停在同一鍵上、或仍在冷卻期間：不累積，
                // hoverStart 每幀重置，避免冷卻一過立刻誤觸（例如暫停後
                // 立刻重啟）。要觸發，視線須先離開面板區再回來。
                hoverStart = now
                controlView?.setHover(-1, 0f)
            } else {
                if (btnIndex != hoverBtnIndex) {
                    hoverBtnIndex = btnIndex
                    hoverStart = now
                }
                lastPanelHoverAt = now
                val hp = min((now - hoverStart) / settings.dwellTimeMs.toFloat(), 1f)
                controlView?.setHover(btnIndex, hp)
                if (hp >= 1f) {
                    // 觸發切換
                    val targetItem = controlView?.items?.get(btnIndex)
                    if (targetItem != null) {
                        if (currentMode == ActionMode.PAUSED) {
                            currentMode = ActionMode.TAP
                        } else {
                            if (targetItem.mode == ActionMode.PAUSED) {
                                currentMode = ActionMode.PAUSED
                            } else {
                                currentMode = targetItem.mode
                            }
                        }
                        controlView?.activeMode = currentMode
                        controlView?.invalidate()
                    }
                    // 切到暫停保持展開（要看得到「重啟」）；選了其他模式則短暫
                    // 顯示選中態後自動收回。
                    if (currentMode != ActionMode.PAUSED) {
                        panelCollapseAt = now + PANEL_COLLAPSE_AFTER_SELECT_MS
                    }
                    cooldownUntil = now + settings.cooldownMs
                    hoverBtnIndex = -1
                    hoverStart = now
                    // 解除武裝：視線離開面板區一次後才重新累積，避免停在
                    // 同一鍵上立刻重複觸發。
                    panelButtonArmed = false
                }
            }
            // 在面板上時，不累積螢幕點擊的 dwell 進度
            anchorX = x; anchorY = y; dwellStart = now
            screenDwell.reset()
        } else {
            // 不在面板／把手上，清除面板與把手的 hover 狀態
            if (hoverBtnIndex != -1) {
                hoverBtnIndex = -1
                controlView?.setHover(-1, 0f)
            }
            if (handleHoverStart != 0L) {
                handleHoverStart = 0L
                controlView?.setHandleProgress(0f)
            }
            // 視線確實離開把手區：把手重新武裝，下次注視才能展開。
            // 收回當幀視線可能還停在把手區（選完模式），那時不武裝，
            // 否則會立刻又展開。
            if (!panelExpanded && !gazeInHandleZone) handleArmed = true
            // 視線確實離開面板區：面板按鈕重新武裝，下次注視同一鍵才累積。
            val gazeInPanelRect = x >= panelLeft && y >= panelTop && y <= panelBottom
            if (!gazeInPanelRect) panelButtonArmed = true

            // 如果目前是暫停狀態，游標不累積點擊，且不顯示游標（或者顯示半透明灰色）
            if (currentMode == ActionMode.PAUSED) {
                // PAUSED 期間邊緣手勢不得觸發（D3-8）：進行中的手勢中斷。
                cancelEdgeGesture()
                screenDwell.reset()
                cursor.setCursor(null)
                return
            }

            // 邊緣手勢控制器（D3-2）：同一幀餵控制器，判決優先於一般 dwell。
            // 邊緣帶幾何集中在控制器（dp 換算，見 EdgeGestureController.edgeBandPx）；
            // 服務只保留呼叫點，四邊動作對應不變（頂＝返回、底＝首頁、左＝上捲、
            // 右＝下捲，見 performEdgeAction）。提醒／RECOVERY／校正交接期間
            // 本分支根本到不了（上方提早返回），天然互斥（D3-8）。
            val density = resources.displayMetrics.density
            val edgeZoneNow = edgeController.zoneFor(x, y, screenW, screenH, density)
            val edgeDecision = edgeController.update(
                edgeFrame(sample, x, y, rawX, rawY, now), edgeConfig(),
            )
            var edgeHandled = false
            when (edgeDecision) {
                is EdgeDecision.Execute -> {
                    cursor.hideConfirmTarget()
                    edgeConfirmShowing = false
                    edgeDeniedCaptured = false
                    performEdgeAction(
                        edgeDecision.edge == Edge.LEFT,
                        edgeDecision.edge == Edge.RIGHT,
                        edgeDecision.edge == Edge.TOP,
                        edgeDecision.edge == Edge.BOTTOM,
                    )
                    // 驗證影格：邊緣手勢觸發時的實際眼區狀態（下一幀有臉時取）。
                    engine?.requestVerifyCapture(VerifyFrames.CAPTURE_EDGE_FIRE, 1f)
                    cooldownUntil = now + settings.cooldownMs
                    screenDwell.reset()
                    anchorX = x
                    anchorY = y
                    progress = 0f
                    edgeHandled = true
                }
                is EdgeDecision.EnterConfirm -> {
                    showEdgeConfirm(edgeDecision.edge, 0f)
                    edgeDeniedCaptured = false
                    engine?.requestVerifyCapture(VerifyFrames.CAPTURE_EDGE_CONFIRM_SHOW, 0f)
                    screenDwell.reset()
                    anchorX = x
                    anchorY = y
                    progress = 0f
                    edgeHandled = true
                }
                is EdgeDecision.Confirming -> {
                    showEdgeConfirm(edgeDecision.edge, edgeDecision.progress)
                    screenDwell.reset()
                    anchorX = x
                    anchorY = y
                    progress = 0f
                    edgeHandled = true
                }
                is EdgeDecision.Cancelled -> {
                    // 手勢已取消／已中斷（D3-7）：清除進行中狀態、不執行動作、
                    // 不留殘留提示；確認期間取消時留一張驗證影格供漏觸分析。
                    val wasConfirming = edgeConfirmShowing
                    cursor.hideConfirmTarget()
                    edgeConfirmShowing = false
                    edgeDeniedCaptured = false
                    Log.d(TAG, "邊緣手勢已取消／已中斷：${edgeDecision.reason}")
                    if (wasConfirming) {
                        engine?.requestVerifyCapture(VerifyFrames.CAPTURE_EDGE_CONFIRM_CANCELLED)
                    }
                    screenDwell.reset()
                    anchorX = x
                    anchorY = y
                    progress = 0f
                    edgeHandled = true
                }
                is EdgeDecision.Candidate -> {
                    // 候選累積中：游標進度環顯示控制器進度，一般 dwell 不累積
                    // （避免兩套計時打架）；錨點跟著當下注視點，顯示不漂移。
                    screenDwell.reset()
                    anchorX = x
                    anchorY = y
                    progress = edgeDecision.progress
                    edgeHandled = true
                }
                is EdgeDecision.Blocked -> {
                    logEdgeCheckFailures(edgeDecision.report)
                    if (!edgeDeniedCaptured) {
                        edgeDeniedCaptured = true
                        engine?.requestVerifyCapture(VerifyFrames.CAPTURE_EDGE_DENIED, 0f)
                    }
                    screenDwell.reset()
                    anchorX = x
                    anchorY = y
                    progress = 0f
                    edgeHandled = true
                }
                EdgeDecision.NoAction -> {
                    // 離開邊緣區後，被擋擷取的「本次進入帶」標記歸零。
                    if (edgeZoneNow == EdgeZone.NONE) edgeDeniedCaptured = false
                    // 落下跑一般 dwell；但游標仍在邊緣帶內時（重新觸發鎖定／
                    // 開關關閉），一般 dwell 不得代為觸發（見下方帶內守衛）。
                }
            }

            if (!edgeHandled) {
                // 螢幕點擊的自適應停留（DwellTracker）：錨點跟著緩漂、短暫離開只衰減、
                // 低速幀才累積。冷卻期間不累積、狀態重置，避免冷卻一過立即重觸。
                if (now < cooldownUntil) {
                    screenDwell.reset()
                } else {
                    progress = screenDwell.update(
                        x, y, now, settings.dwellTimeMs, settings.dwellRadiusPx,
                    )
                    // 錨點同步回服務欄位：點擊落點與游標顯示的「鎖定」效果沿用舊變數。
                    anchorX = screenDwell.anchorX
                    anchorY = screenDwell.anchorY
                    if (progress >= 1f) {
                        if (edgeZoneNow != EdgeZone.NONE) {
                            // 控制器擁有邊緣判決：此幀未執行（鎖定中／開關關閉／
                            // 檢查未達），一般 dwell 不得代為觸發或點擊邊緣位置。
                            // 吞掉這次觸發（給冷卻、不動作），絕不拿被箝制的邊緣
                            // 位置當注視（現況 v0.5.8 守衛語義，由控制器承接）。
                            if (!edgeDeniedCaptured) {
                                edgeDeniedCaptured = true
                                engine?.requestVerifyCapture(
                                    VerifyFrames.CAPTURE_EDGE_DENIED, progress,
                                )
                            }
                        } else {
                            triggerActionAt(anchorX, anchorY)
                            // 驗證影格：停留點擊觸發時的實際眼區狀態（下一幀有臉時取）。
                            engine?.requestVerifyCapture(
                                VerifyFrames.CAPTURE_DWELL_FIRE, progress,
                            )
                        }
                        cooldownUntil = now + settings.cooldownMs
                        dwellStart = now
                        progress = 0f
                        screenDwell.reset()
                    }
                }
            }
        }

        currentProgress = progress
        // 當進度累積時，視覺上將游標往錨點吸引/鎖定，降低因為微動引起的游標擺動
        val displayX = if (progress > 0f) {
            anchorX * progress + x * (1f - progress)
        } else {
            x
        }
        val displayY = if (progress > 0f) {
            anchorY * progress + y * (1f - progress)
        } else {
            y
        }
        cursor.setCursor(Triple(displayX, displayY, progress))
    }

    // ---- 條件感知漂移修正 ----

    /** 情境／引擎重啟／旋轉時重置條件狀態：提醒收掉、偏移歸零，等下次評估重新推算。 */
    private fun reloadConditionState() {
        dismissReminder(markSkipped = false)
        appliedOffsetX = 0f
        appliedOffsetY = 0f
        smoothedPitch = Double.NaN
        smoothedRoll = Double.NaN
        smoothedIod = Double.NaN
        smoothedLux = Float.NaN
        smoothedGlassesHint = Double.NaN
        screenDwell.reset()
        conditionSeeded = false
        lastConditionCheckAt = 0L
        reminderCooldownUntil = 0L
    }

    /** 每幀平滑條件分量：未知分量（NaN）跳過，不污染已有的平滑值。 */
    private fun updateSmoothedCondition(sample: GazeSample) {
        if (sample.devicePitch.isFinite()) {
            smoothedPitch = smoothDouble(smoothedPitch, sample.devicePitch)
        }
        if (sample.deviceRoll.isFinite()) {
            smoothedRoll = smoothDouble(smoothedRoll, sample.deviceRoll)
        }
        val iod = sample.features.getOrNull(6) ?: Double.NaN
        if (iod.isFinite() && iod > 0.0) {
            smoothedIod = smoothDouble(smoothedIod, iod)
        }
        if (sample.ambientLux.isFinite() && sample.ambientLux >= 0f) {
            smoothedLux = if (smoothedLux.isNaN()) sample.ambientLux
            else smoothedLux + (sample.ambientLux - smoothedLux) * CONDITION_SMOOTH_ALPHA.toFloat()
        }
        if (sample.specularRatio.isFinite() && sample.specularRatio >= 0.0) {
            smoothedGlassesHint = smoothDouble(smoothedGlassesHint, sample.specularRatio)
        }
    }

    private fun smoothDouble(prev: Double, current: Double): Double =
        if (prev.isNaN()) current else prev + (current - prev) * CONDITION_SMOOTH_ALPHA

    // v3 模型契約：光線是連續模型輸入，不再走 ConditionHistory 的分桶修正
    // （否則同一光效應會被模型與歷史偏移算兩次）。luxBucket 寫 -1（未知），
    // 既有「未知分量不參與判定」邏輯使光線永不觸發提醒、永不進入內插距離；
    // 歷史紀錄只代表非光線殘差（姿勢／距離／反光）。見設計文件 §2.5。
    // smoothedLux 仍每幀平滑、記入診斷 CSV（欄位不變），只不進條件判定。
    private fun currentCondition(): ConditionHistory.Condition =
        ConditionHistory.Condition(
            luxBucket = -1,
            pitch = smoothedPitch,
            roll = smoothedRoll,
            iod = smoothedIod,
            glassesHint = smoothedGlassesHint,
        )

    /** 條件變化評估：變化大時先查歷史內插，沒遇過的條件才跳提醒。 */
    private fun evaluateConditions(now: Long) {
        if (!settings.conditionCorrection) return
        if (model == null || pausedForCalibration) return
        if (reminderActive || now < reminderCooldownUntil) return
        if (currentMode == ActionMode.PAUSED) return
        val profileId = CalibrationStore.getActiveProfileId(this)
        val cur = currentCondition()
        val ref = ConditionHistory.loadReference(this, profileId)
        if (ref == null) {
            // 還沒有基準（例如升級後首次使用）：靜默建立，不提醒
            ConditionHistory.saveReference(this, profileId, cur)
            conditionSeeded = true
            return
        }
        val changed = ConditionHistory.hasSignificantChange(ref, cur)
        if (!changed && conditionSeeded) return
        // 首次評估或條件變了：查歷史
        val inferred = ConditionHistory.interpolate(
            ConditionHistory.loadRecords(this, profileId), cur,
        )
        conditionSeeded = true
        if (inferred != null) {
            appliedOffsetX = inferred.first
            appliedOffsetY = inferred.second
            ConditionHistory.saveReference(this, profileId, cur)
            Log.d(TAG, "條件變化，以歷史紀錄內插偏移=($appliedOffsetX, $appliedOffsetY)")
            return
        }
        if (!changed) return // 首次評估、條件沒變、又沒歷史：不用提醒
        showReminder(now)
    }

    private fun showReminder(now: Long) {
        // 提醒 preempt 最高優先（STATE_DESIGN §5）：邊緣確認一併取消，
        // 提醒結束後重新累積，不得沿用提醒前進度。
        cancelEdgeGesture()
        reminderActive = true
        reminderShownAt = now
        reminderDwellAccumMs = 0L
        reminderLastFrameAt = now
        reminderAwaySince = 0L
        reminderRawPoints.clear()
        // 清掉面板 hover、把手進度、待滑動起點與 dwell 進度，避免提醒期間誤觸
        hoverBtnIndex = -1
        controlView?.setHover(-1, 0f)
        handleHoverStart = 0L
        controlView?.setHandleProgress(0f)
        swipeStartPoint = null
        controlView?.swipeState = 0
        controlView?.invalidate()
        anchorX = screenW / 2f
        anchorY = screenH / 2f
        dwellStart = now
        currentProgress = 0f
        screenDwell.reset()
        cursorView?.showReminderTarget(
            screenW / 2f, screenH / 2f, "注視 1 秒，自動校正游標偏移",
        )
        Log.d(TAG, "條件變化，顯示快速校正提醒")
    }

    /**
     * 收掉提醒。markSkipped = true 表示使用者略過（移開視線／逾時）：
     * 以目前條件為新基準、並冷卻 60 秒不再提醒，避免同一條件反覆跳提醒。
     * 內部原因（切換情境、關開關、旋轉）收掉時不記略過。
     */
    private fun dismissReminder(markSkipped: Boolean) {
        if (!reminderActive) return
        reminderActive = false
        reminderRawPoints.clear()
        cursorView?.hideReminderTarget()
        // 重置 dwell 起點：避免提醒收掉後，沿用提醒顯示時的 dwellStart 立即誤觸
        dwellStart = SystemClock.elapsedRealtime()
        currentProgress = 0f
        screenDwell.reset()
        if (markSkipped) {
            ConditionHistory.saveReference(
                this, CalibrationStore.getActiveProfileId(this), currentCondition(),
            )
            reminderCooldownUntil =
                SystemClock.elapsedRealtime() + REMINDER_SKIP_COOLDOWN_MS
        }
    }

    /**
     * 提醒狀態機：有效幀與臉遺失幀都會推進（臉遺失時 gaze 為 null）。
     * 目標注視 1 秒完成單點校正；移開視線持續 0.5 秒或 5 秒逾時＝略過。
     */
    private fun updateReminder(
        gazeX: Float?, gazeY: Float?,
        rawX: Double?, rawY: Double?,
        now: Long,
    ) {
        if (!settings.conditionCorrection) {
            dismissReminder(markSkipped = false)
            return
        }
        if (now - reminderShownAt > REMINDER_TIMEOUT_MS) {
            Log.d(TAG, "提醒逾時，略過快速校正")
            dismissReminder(markSkipped = true)
            return
        }
        val tx = screenW / 2f
        val ty = screenH / 2f
        if (gazeX == null || gazeY == null) {
            // 臉遺失：視為不在目標上（不收樣本），逾時／移開邏輯照走
            noteReminderAway(now)
            return
        }
        val dt = (now - reminderLastFrameAt).coerceIn(0L, 200L)
        reminderLastFrameAt = now
        if (hypot(gazeX - tx, gazeY - ty) <= REMINDER_DWELL_RADIUS_PX) {
            reminderAwaySince = 0L
            reminderDwellAccumMs += dt
            if (rawX != null && rawY != null) reminderRawPoints.add(rawX to rawY)
            if (reminderDwellAccumMs >= REMINDER_DWELL_MS &&
                reminderRawPoints.size >= REMINDER_MIN_SAMPLES
            ) {
                completeQuickCalibration(now)
            }
        } else {
            reminderDwellAccumMs = 0L
            reminderRawPoints.clear()
            noteReminderAway(now)
        }
    }

    /** 視線離開提醒目標：持續超過 0.5 秒視為略過。 */
    private fun noteReminderAway(now: Long) {
        if (reminderAwaySince == 0L) {
            reminderAwaySince = now
        } else if (now - reminderAwaySince > REMINDER_AWAY_MS) {
            Log.d(TAG, "移開視線，略過快速校正")
            dismissReminder(markSkipped = true)
        }
    }

    /** 單點快速校正完成：偏移 = 中央目標 − 注視期間預測點平均，寫入歷史並套用。 */
    private fun completeQuickCalibration(now: Long) {
        val tx = screenW / 2f
        val ty = screenH / 2f
        val meanX = reminderRawPoints.map { it.first }.average()
        val meanY = reminderRawPoints.map { it.second }.average()
        val ox = (tx - meanX).toFloat()
            .coerceIn(-ConditionHistory.MAX_OFFSET_PX, ConditionHistory.MAX_OFFSET_PX)
        val oy = (ty - meanY).toFloat()
            .coerceIn(-ConditionHistory.MAX_OFFSET_PX, ConditionHistory.MAX_OFFSET_PX)
        val profileId = CalibrationStore.getActiveProfileId(this)
        val cond = currentCondition()
        ConditionHistory.addRecord(
            this, profileId,
            ConditionHistory.Record(cond, ox, oy, now),
        )
        ConditionHistory.saveReference(this, profileId, cond)
        appliedOffsetX = ox
        appliedOffsetY = oy
        reminderActive = false
        reminderRawPoints.clear()
        cursorView?.hideReminderTarget()
        // 避免校正完立刻在中央誤觸：重置 dwell 狀態並給一次冷卻
        anchorX = tx
        anchorY = ty
        dwellStart = now
        currentProgress = 0f
        screenDwell.reset()
        cooldownUntil = now + settings.cooldownMs
        Log.d(TAG, "單點快速校正完成：偏移=($ox, $oy)，條件=$cond")
        mainHandler.post {
            Toast.makeText(this, "已快速校正", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- 邊緣手勢控制器接線（Phase 3 D3-2…D3-8） ----

    /** 由現況設定組裝控制器設定（每幀新組，設定頁變更即時生效，不另起設定系統）。 */
    private fun edgeConfig(): EdgeGestureConfig = EdgeGestureConfig(
        enabled = settings.edgeGesturesEnabled,
        topEnabled = settings.edgeTopEnabled,
        bottomEnabled = settings.edgeBottomEnabled,
        leftEnabled = settings.edgeLeftEnabled,
        rightEnabled = settings.edgeRightEnabled,
        dwellTimeMs = settings.dwellTimeMs,
    )

    /** 由同一幀的已算數據組裝控制器輸入（不自建樣本來源；特徵契約 25 維，含連續光特徵）。 */
    private fun edgeFrame(
        sample: GazeSample,
        x: Float,
        y: Float,
        rawX: Double,
        rawY: Double,
        now: Long,
    ): EdgeGestureFrame = EdgeGestureFrame(
        filteredX = x,
        filteredY = y,
        rawX = rawX,
        rawY = rawY,
        facePresent = true,
        eyesOpen = sample.leftOpen >= settings.blinkThreshold &&
            sample.rightOpen >= settings.blinkThreshold,
        featuresFinite = sample.features.size == GazeFeatureExtractor.DIM &&
            sample.features.all { it.isFinite() },
        imageDegraded = EyeImageMetrics.isDegraded(sample),
        nowMs = now,
        screenW = screenW,
        screenH = screenH,
        density = resources.displayMetrics.density,
    )

    /**
     * 中斷進行中的邊緣手勢（D3-7／D3-8）：控制器強制回 IDLE，確認目標收掉、
     * 不執行動作、不留殘留提示。呼叫點：臉遺失、影像降級、提醒上升沿、
     * 面板／把手接管、PAUSED、RECOVERY／完全停止、校正交接、方向改變。
     * 重新觸發鎖定不受影響（控制器 [EdgeGestureController.reset] 保留鎖定）。
     */
    private fun cancelEdgeGesture() {
        val wasConfirming = edgeConfirmShowing
        val decision = edgeController.reset()
        cursorView?.hideConfirmTarget()
        edgeConfirmShowing = false
        if (decision is EdgeDecision.Cancelled) {
            Log.d(TAG, "邊緣手勢已取消／已中斷：${decision.reason}")
            if (wasConfirming) {
                engine?.requestVerifyCapture(VerifyFrames.CAPTURE_EDGE_CONFIRM_CANCELLED)
            }
        }
    }

    /**
     * 清除待執行意圖（STEP 3 薄守衛，DCL-18／DCL-11）：待發的雙擊第二下、
     * 滑動起點、眨眼狀態、停留進度與進行中的邊緣手勢。只清「意圖」，
     * 不停引擎、不關相機、不改流程態——中斷／暫停後恢復仍走既有路徑。
     * 濾波歷史不在此列（臉遺失／恢復路徑另行重置，見臉遺失分支）。
     * 只在主執行緒呼叫（全部呼叫點皆為主執行緒或 mainHandler 回呼）。
     */
    private fun cancelPendingGesture() {
        cancelPendingDoubleTap()
        // 集中派發守衛（DCL-04）：進行中手勢標記中斷，後到的舊回呼忽略；
        // 本 helper 覆蓋暫停／停止／中斷／校正交接全部呼叫點。
        dispatchGuard.onInterrupted()
        swipeStartPoint = null
        controlView?.swipeState = 0
        controlView?.invalidate()
        isBlinking = false
        blinkStartTime = 0L
        screenDwell.reset()
        currentProgress = 0f
        cancelEdgeGesture()
    }

    /** 取消待發的雙擊第二下（K-07）：具名 Runnable 持有，方可 removeCallbacks。 */
    private fun cancelPendingDoubleTap() {
        val pending = pendingDoubleTapSecond ?: return
        pendingDoubleTapSecond = null
        mainHandler.removeCallbacks(pending)
    }

    /** 顯示／更新二階段確認目標（中央偏下，淺色暖系；與提醒／重啟目標互斥）。 */
    private fun showEdgeConfirm(edge: Edge, progress: Float) {
        val target = edgeController.confirmTarget(
            screenW, screenH, resources.displayMetrics.density,
        )
        val hint = if (edge == Edge.TOP) "注視這裡確認返回" else "注視這裡確認回首頁"
        cursorView?.updateConfirmTarget(target.centerX, target.centerY, progress, hint)
        edgeConfirmShowing = true
    }

    /** 檢查失敗原因只寫 Log（供診斷），絕不顯示數字／分數給使用者。 */
    private fun logEdgeCheckFailures(report: EdgeCheckReport) {
        val failures = listOf(
            "Gaze Confidence" to report.gazeConfidence,
            "Gaze Stability" to report.stability,
            "Intent Confidence" to report.intentConfidence,
        ).filter { it.second is EdgeCheckResult.Fail }
            .joinToString("；") { (name, result) ->
                "$name(${(result as EdgeCheckResult.Fail).reason})"
            }
        if (failures.isNotEmpty()) Log.d(TAG, "邊緣手勢檢查未通過：$failures")
    }

    // ---- RECOVERY_MODE：重啟目標與重啟驗證（D2-2／D2-3／D2-4） ----

    /**
     * 重啟目標的注視半徑：沿用提醒目標的既有常數（中央目標同一量級），
     * 不新編數字。目標固定在螢幕中央。
     */
    private fun recoveryTargetRadiusPx(): Float = REMINDER_DWELL_RADIUS_PX

    /**
     * RECOVERY_MODE 每幀分支（只在主執行緒呼叫）。命中判定用獨立線性
     * dwell 計時（秒數取 `settings.dwellTimeMs`），命中期間不跑一般 dwell、
     * 不跑 `DwellTracker`（避免錨點污染）。目標只在臉 present 且雙眼睜開
     * 時接受注視；臉遺失／閉眼時進度凍結不清零（沿用現況閉眼凍結語義）。
     * 視線離開目標區即重設累積並重新武裝。
     */
    private fun updateRecoveryTarget(sample: GazeSample?, now: Long) {
        val cursor = cursorView ?: return
        val tx = screenW / 2f
        val ty = screenH / 2f
        val frozenProgress =
            min(recoveryDwellAccumMs / settings.dwellTimeMs.toFloat(), 1f)
        if (recoveryVerifying) {
            cursor.updateRecoveryTarget(tx, ty, 1f, faceLost = false)
            return
        }
        if (sample == null) {
            // 臉遺失：進度凍結不清零，目標切為「看不到臉」文案（D-2 驗收 4，
            // 不得顯示虛假可恢復狀態）；診斷紀錄沿用臉遺失幀格式，寫入同一段。
            recoveryLastFrameAt = now
            GazeLog.record(
                this, settings.diagnosticLogging || settings.remoteMonitor,
                remoteUpload = remoteUploadEnabled(),
                face = false, features = null,
                rawX = null, rawY = null, gainedX = null, gainedY = null,
                curX = null, curY = null, sensitivity = settings.sensitivity,
                offsetX = if (settings.conditionCorrection) appliedOffsetX else 0f,
                offsetY = if (settings.conditionCorrection) appliedOffsetY else 0f,
                lux = smoothedLux.takeIf { it.isFinite() && it >= 0f },
            )
            cursor.updateRecoveryTarget(tx, ty, frozenProgress, faceLost = true)
            return
        }
        val m = model
        if (m == null) {
            // 無模型時算不出注視座標，目標永遠無法完成 dwell——不得讓使用者
            // 對著一個不可能成功的目標注視：直接走驗證失敗路徑（ERROR＋原因）。
            // 模型存在時的完整驗證仍在 dwell 滿時由 attemptRecovery 執行。
            recoveryVerifying = true
            failRecovery("校正模型無效，請重新校正")
            return
        }
        val leftClosed = sample.leftOpen < settings.blinkThreshold
        val rightClosed = sample.rightOpen < settings.blinkThreshold
        if (leftClosed || rightClosed) {
            // 閉眼（含單眼閉合）：凍結，不推進亦不取消，沿用主路徑語義。
            recoveryLastFrameAt = now
            cursor.updateRecoveryTarget(tx, ty, frozenProgress, faceLost = false)
            return
        }

        // 注視座標沿用主路徑同一管線（預測→條件偏移→靈敏度增益→濾波→箝制），
        // 只用來判定是否在目標區內，不驅動游標、不碰 hasValidCursor。
        // 對照實驗（review 分支限定，2026-11-23）：關閉 kx/ky 頭部姿態補償，
        // 比較仰角漂移是否消失；合併前須經決定，不得視為定稿行為。
        // 連續光輸入：與主路徑相同，每幀的當前 lux 餵給模型。
        val (rawX, rawY) = m.predict(
            sample.features, screenW, screenH,
            sample.devicePitch, sample.deviceRoll,
            settings.gyroCompensation, settings.distanceCompensation,
            headPoseCompensation = false,
            logLux = GazeFeatureExtractor.lightLogLux(sample.ambientLux),
        )
        val effOffsetX = if (settings.conditionCorrection) appliedOffsetX else 0f
        val effOffsetY = if (settings.conditionCorrection) appliedOffsetY else 0f
        val gain = settings.sensitivity.toDouble()
        val centerX = screenW / 2.0
        val centerY = screenH / 2.0
        val gainedX = centerX + (rawX + effOffsetX - centerX) * gain
        val gainedY = centerY + (rawY + effOffsetY - centerY) * gain
        val t = sample.timestampMs / 1000.0
        val x = filterX.filter(gainedX, t).toFloat().coerceIn(0f, screenW - 1f)
        val y = filterY.filter(gainedY, t).toFloat().coerceIn(0f, screenH - 1f)

        // 診斷紀錄寫入同一段（沿用主路徑格式），不新增假數據欄位。
        GazeLog.record(
            this, settings.diagnosticLogging || settings.remoteMonitor,
            remoteUpload = remoteUploadEnabled(),
            face = true, features = sample.features,
            rawX = rawX, rawY = rawY, gainedX = gainedX, gainedY = gainedY,
            curX = x, curY = y, sensitivity = settings.sensitivity,
            offsetX = effOffsetX, offsetY = effOffsetY,
            lux = sample.ambientLux.takeIf { it.isFinite() && it >= 0f },
            imageQuality = sample.eyeContrast.takeIf { it.isFinite() },
            glassesHint = sample.specularRatio.takeIf { it.isFinite() },
        )

        val dt = (now - recoveryLastFrameAt).coerceIn(0L, 200L)
        recoveryLastFrameAt = now
        if (hypot(x - tx, y - ty) > recoveryTargetRadiusPx()) {
            // 離開目標區：累積重設，並重新武裝（D2-4 武裝規則）。
            recoveryDwellAccumMs = 0L
            recoveryArmed = true
            cursor.updateRecoveryTarget(tx, ty, 0f, faceLost = false)
            return
        }
        if (!recoveryArmed) {
            // 未武裝（重啟後視線未曾離開目標區）：凍結，不累積。
            cursor.updateRecoveryTarget(tx, ty, frozenProgress, faceLost = false)
            return
        }
        recoveryDwellAccumMs += dt
        val progress = min(recoveryDwellAccumMs / settings.dwellTimeMs.toFloat(), 1f)
        cursor.updateRecoveryTarget(tx, ty, progress, faceLost = false)
        if (progress >= 1f) {
            recoveryVerifying = true
            attemptRecovery()
        }
    }

    /**
     * 重啟驗證序列（D2-3，D-2 驗收 6）。只在主執行緒呼叫；眼控 dwell 滿與
     * Dashboard 手點恢復共用同一入口函式（D2-6）。
     *
     * 依序驗證：(a) 服務實例仍在、(b) 引擎仍在跑（否則 `startEngine()`）、
     * (c) 校正模型有效——判斷部分走純函式 [RecoveryGate]，任一失敗進 ERROR
     * 含原因；(d) 濾波器重置、錨點／進度／冷卻重設沿用 `startEngine` 內的
     * 重置（引擎已在跑時顯式重設，不得沿用舊進度）。全部通過→清
     * `inRecoveryMode`、模式回 TAP、面板恢復收合態、流程態 SERVICE_RUNNING。
     * 不動 `startEngine` 本體、不動模型載入格式、不重新校正。
     */
    private fun attemptRecovery() {
        val now = SystemClock.elapsedRealtime()
        val activeId = CalibrationStore.getActiveProfileId(this)
        val decision = RecoveryGate.evaluate(
            RecoveryGateInput(
                serviceAlive = instance === this,
                engineRunning = engine != null,
                modelValid = CalibrationStore.loadModel(this, activeId) != null,
            ),
        )
        if (decision is RecoveryGateDecision.Deny) {
            failRecovery(decision.reason)
            return
        }
        val engineWasRunning = engine != null
        if (!engineWasRunning) {
            startEngine()
        }
        // 重啟後再驗一次：startEngine 可能同步失敗（引擎清為 null、進 ERROR）
        // 或模型在兩次載入間失效；任一為真即失敗，不得靜默回到 TRACKING。
        if (engine == null || model == null) {
            failRecovery(
                if (engine == null) "引擎啟動失敗" else "校正模型無效，請重新校正",
            )
            return
        }
        if (engineWasRunning) {
            // 引擎本就跑著：顯式做 (d) 重置，與 startEngine 內的重置同一組。
            filterX.reset()
            filterY.reset()
            reloadConditionState()
        }
        inRecoveryMode = false
        recoveryVerifying = false
        recoveryDwellAccumMs = 0L
        // 武裝規則：重啟後視線須先離開目標區一次，下次進入 RECOVERY_MODE
        // 才重新武裝（D2-4）。
        recoveryArmed = false
        currentMode = ActionMode.TAP
        controlView?.activeMode = ActionMode.TAP
        swipeStartPoint = null
        controlView?.swipeState = 0
        controlView?.invalidate()
        controlView?.visibility = View.VISIBLE
        setPanelExpanded(false)
        cursorView?.hideRecoveryTarget()
        screenDwell.reset()
        currentProgress = 0f
        anchorX = screenW / 2f
        anchorY = screenH / 2f
        dwellStart = now
        cooldownUntil = now + settings.cooldownMs
        syncFlowState(FlowEvent.RestartVerified, AppFlowState.SERVICE_RUNNING)
    }

    /** 重啟驗證任一失敗：清恢復旗標、藏目標、流程態 ERROR＋真實原因。 */
    private fun failRecovery(reason: String) {
        inRecoveryMode = false
        recoveryVerifying = false
        recoveryDwellAccumMs = 0L
        cursorView?.hideRecoveryTarget()
        cursorView?.setCursor(null)
        syncFlowState(FlowEvent.Failure(reason), AppFlowState.ERROR)
        mainHandler.post {
            Toast.makeText(this, "無法重啟眼控：$reason", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerActionAt(x: Float, y: Float) {
        when (currentMode) {
            ActionMode.TAP -> {
                performTap(x, y)
            }
            ActionMode.DOUBLE_TAP -> {
                performDoubleTap(x, y)
                currentMode = ActionMode.TAP
                controlView?.activeMode = ActionMode.TAP
                controlView?.invalidate()
            }
            ActionMode.LONG_PRESS -> {
                performLongPress(x, y)
                currentMode = ActionMode.TAP
                controlView?.activeMode = ActionMode.TAP
                controlView?.invalidate()
            }
            ActionMode.SWIPE -> {
                val start = swipeStartPoint
                if (start == null) {
                    swipeStartPoint = x to y
                    mainHandler.post {
                        Toast.makeText(this, "起點已選取，請注視滑動終點", Toast.LENGTH_SHORT).show()
                    }
                    controlView?.swipeState = 1 // 1 代表已選起點
                    controlView?.invalidate()
                } else {
                    performSwipe(start.first, start.second, x, y)
                    swipeStartPoint = null
                    currentMode = ActionMode.TAP
                    controlView?.swipeState = 0
                    controlView?.activeMode = ActionMode.TAP
                    controlView?.invalidate()
                }
            }
            else -> {}
        }
    }

    /** 執行邊緣區域觸發的特殊手勢：返回、首頁與向上/下捲動 */
    private fun performEdgeAction(left: Boolean, right: Boolean, top: Boolean, bottom: Boolean) {
        when {
            top -> {
                // 頂部邊緣停留：返回
                performGlobalAction(GLOBAL_ACTION_BACK)
                cursorView?.post { cursorView?.flashClick() }
            }
            bottom -> {
                // 底部邊緣停留：首頁
                performGlobalAction(GLOBAL_ACTION_HOME)
                cursorView?.post { cursorView?.flashClick() }
            }
            left -> {
                // 左側邊緣停留：向上捲動
                performScroll(up = true)
            }
            right -> {
                // 右側邊緣停留：向下捲動
                performScroll(up = false)
            }
        }
    }

    private fun performScroll(up: Boolean) {
        val startY = if (up) screenH * 0.75f else screenH * 0.25f
        val endY = if (up) screenH * 0.25f else screenH * 0.75f
        val midX = screenW / 2f

        val path = Path().apply {
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        // 集中派發守衛（DCL-04）：ID 配對＋回傳值處理；過期 onCompleted 不閃光。
        val token = dispatchGuard.begin()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (dispatchGuard.onCompleted(token)) {
                    cursorView?.post { cursorView?.flashClick() }
                }
            }

            override fun onCancelled(g: GestureDescription?) {
                dispatchGuard.onCancelled(token)
            }
        }, null)
        dispatchGuard.onDispatchReturned(token, accepted)
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        // 集中派發守衛（DCL-04）：同 performScroll。
        val token = dispatchGuard.begin()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (dispatchGuard.onCompleted(token)) {
                    cursorView?.post { cursorView?.flashClick() }
                }
            }

            override fun onCancelled(g: GestureDescription?) {
                dispatchGuard.onCancelled(token)
            }
        }, null)
        dispatchGuard.onDispatchReturned(token, accepted)
    }

    private fun performDoubleTap(x: Float, y: Float) {
        performTap(x, y)
        // 第二下以具名 Runnable 持有（STEP 3 薄守衛，K-07）：中斷／暫停／
        // 停止／臉遺失／校正交接時可經 cancelPendingDoubleTap 取消，不再
        // 以匿名延遲在背景排隊執行。150 ms 間隔沿用現況值，不新編數字；
        // 若 150 ms 內又有新的雙擊，舊的第二下以新的為準（先取消再排隊）。
        cancelPendingDoubleTap()
        val secondTap = Runnable {
            pendingDoubleTapSecond = null
            performTap(x, y)
        }
        pendingDoubleTapSecond = secondTap
        mainHandler.postDelayed(secondTap, 150)
    }

    private fun performLongPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        // 集中派發守衛（DCL-04）：同 performScroll。
        val token = dispatchGuard.begin()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (dispatchGuard.onCompleted(token)) {
                    cursorView?.post { cursorView?.flashClick() }
                }
            }

            override fun onCancelled(g: GestureDescription?) {
                dispatchGuard.onCancelled(token)
            }
        }, null)
        dispatchGuard.onDispatchReturned(token, accepted)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        // 集中派發守衛（DCL-04）：同 performScroll。
        val token = dispatchGuard.begin()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (dispatchGuard.onCompleted(token)) {
                    cursorView?.post { cursorView?.flashClick() }
                }
            }

            override fun onCancelled(g: GestureDescription?) {
                dispatchGuard.onCancelled(token)
            }
        }, null)
        dispatchGuard.onDispatchReturned(token, accepted)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cancelPendingDoubleTap()
        // 服務銷毀：進行中手勢標記中斷，後到的舊回呼一律忽略、不重播。
        dispatchGuard.onInterrupted()
        runCatching {
            getSharedPreferences(GazeSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(settingsListener)
            CalibrationStore.prefs(this)
                .unregisterOnSharedPreferenceChangeListener(profileListener)
        }
        engine?.stop()
        engine = null
        GazeLog.close()
        cursorView?.let {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        cursorView = null
        controlView?.let {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        controlView = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        // 系統中斷（STEP 3，DCL-18）：只清除待執行意圖，不停引擎、
        // 不關相機、不改流程態；引擎／overlay 的生命週期仍由 onDestroy 管。
        cancelPendingGesture()
    }


    companion object {
        /** 目前存活的服務實例（無障礙服務為單例）。校正頁用它做相機交接。 */
        @Volatile
        private var instance: GazeAccessibilityService? = null

        /**
         * 校正頁啟動前呼叫：請服務放開相機，完全釋放後在主執行緒回呼 onReleased。
         * 服務未啟用時直接回呼。這是根除「服務與校正搶相機」競態的關鍵——
         * 校正頁務必等到 onReleased 才綁定自己的相機。
         */
        fun requestPauseForCalibration(onReleased: () -> Unit) {
            val svc = instance
            if (svc == null) {
                onReleased()
                return
            }
            svc.pausedForCalibration = true
            // engine.stop() 會阻塞等待 analysis 執行緒排空，丟到背景執行緒避免卡主執行緒
            Thread {
                // 只停「此刻」這台引擎：若停機期間已有新引擎接手（過期恢復），絕不碰它——
                // 曾有競態讓背景執行緒把剛建好的新引擎停掉、游標凍結到重開服務
                val target = svc.engine
                target?.stop()
                if (target != null && svc.engine === target) svc.engine = null
                svc.mainHandler.post {
                    svc.cursorView?.setCursor(null)
                    // 提醒目標蓋在最上層，校正期間一併收掉，免得蓋住校正畫面
                    svc.cursorView?.hideReminderTarget()
                    // 重啟目標同理：校正交接期間不得蓋住校正頁；累積重設，
                    // 免得交接回來沿用舊進度立刻觸發重啟（協議本體不動，
                    // 只收 overlay 與重設計時，與提醒目標同一處理）。
                    svc.cursorView?.hideRecoveryTarget()
                    // 校正交接期間邊緣手勢不得運作（D3-8）：進行中的手勢中斷，
                    // 確認目標收掉；協議本體不動，只收 overlay 與重設計時。
                    svc.cancelEdgeGesture()
                    svc.recoveryDwellAccumMs = 0L
                    svc.recoveryVerifying = false
                    // 校正交接亦為中斷：待發的雙擊第二下與其他待執行意圖
                    // 不得在校正期間排隊執行（STEP 3 薄守衛）。
                    svc.cancelPendingGesture()
                    // 模式面板是常駐 overlay：暫停期間把它藏起來，否則會蓋住
                    // 校正／訓練頁右側的目標點（面板看得見但已停止反應）。
                    // 面板只在 onServiceConnected 建立一次、沒有重建路徑，
                    // 恢復時在 resumeAfterCalibration 設回 VISIBLE 即可。
                    svc.controlView?.visibility = View.GONE
                    // 交接完成（回呼後）才進 CALIBRATING，見 STATE_DESIGN §2.1。
                    svc.syncFlowState(FlowEvent.StartCalibration, AppFlowState.CALIBRATING)
                    onReleased()
                }
            }.start()
        }

        /** 校正頁結束後呼叫：服務重新載入校正結果並接回相機。 */
        fun resumeAfterCalibration() {
            val svc = instance ?: return
            svc.mainHandler.post {
                svc.pausedForCalibration = false
                // 把暫停期間藏起來的模式面板恢復顯示：暫停中保持展開，
                // 否則「重啟」看不見；其餘狀態回到預設的收合。
                svc.controlView?.visibility = View.VISIBLE
                svc.setPanelExpanded(svc.currentMode == ActionMode.PAUSED)
                // RECOVERY_MODE 中去校正、交接回來仍是恢復態：面板維持隱藏，
                // 只剩中央重啟目標（下一幀 RECOVERY 分支會重新顯示目標）。
                if (svc.inRecoveryMode) svc.controlView?.visibility = View.GONE
                svc.startEngine()
                // 回原態：startEngine 已依成敗同步（SERVICE_RUNNING／ERROR／
                // NOT_CALIBRATED）；只有它在 userStopped 時直接返回、未同步，
                // 此時以事實推導補同步為 STOPPED。
                if (svc.userStopped) {
                    svc.syncFlowState(
                        FlowEvent.StopCompletely,
                        svc.deriveFlowState(hasModel = svc.model != null),
                    )
                }
            }
        }

        // ---- Dashboard 即時狀態與操作（只讀寫狀態旗標，不動追蹤管線） ----

        /** 最近一幀是否有臉（由 onSampleOnMainThread 更新）。僅服務內部寫入。 */
        @Volatile
        var lastFacePresent: Boolean = false

        /** 最近一幀收到的時間（elapsedRealtime）；用來判斷狀態是否新鮮。僅服務內部寫入。 */
        @Volatile
        var lastSampleAtMs: Long = 0L

        /** 是否曾收到有效游標位置。僅服務內部寫入。 */
        @Volatile
        var lastCursorValid: Boolean = false

        /** 最近一幀時是否有載入模型。僅服務內部寫入。 */
        @Volatile
        var lastHasModel: Boolean = false

        /** 樣本新鮮度門檻：超過此時間沒收到樣本，狀態視為未知。 */
        const val SAMPLE_FRESH_MS = 3000L

        /** Dashboard 讀取的追蹤快照；服務未啟用時回 null。 */
        data class TrackingSnapshot(
            val facePresent: Boolean,
            val sampleFresh: Boolean,
            val cursorValid: Boolean,
            val paused: Boolean,
            val stopped: Boolean,
            /**
             * RECOVERY_MODE（D2-6 新增）：`stopTracking` 後、引擎續跑、
             * 可注視重啟的狀態。與 [stopped] 嚴格區分：`stopped` 僅
             * `stopCompletely`／`userStopped` 時為真（相機已關閉）。
             */
            val inRecoveryMode: Boolean,
            val hasModel: Boolean,
            /** 流程層現態（Phase 3 D1-2 新增；舊欄位原樣保留）。 */
            val flowState: AppFlowState,
            /** ERROR 態的真實原因；非 ERROR 態時為 null。 */
            val flowErrorReason: String?,
        )

        fun trackingSnapshot(): TrackingSnapshot? {
            val svc = instance ?: return null
            val fresh = SystemClock.elapsedRealtime() - lastSampleAtMs <= SAMPLE_FRESH_MS
            return TrackingSnapshot(
                facePresent = lastFacePresent && fresh,
                sampleFresh = fresh,
                cursorValid = lastCursorValid,
                paused = svc.currentMode == ActionMode.PAUSED,
                stopped = svc.userStopped,
                inRecoveryMode = svc.inRecoveryMode,
                hasModel = lastHasModel,
                flowState = svc.flowState,
                flowErrorReason =
                    if (svc.flowState == AppFlowState.ERROR) svc.flowMachine.errorReason else null,
            )
        }

        /**
         * 系統設定中的真實啟用狀態（唯讀，不寫入、不繞過授權）。
         * 精確比對：按 :／; 切分後逐項取「/」前的套件名等值比對，與引導內
         * 版本同一算法（第 4 步遺留 (b)：MainActivity 改為共用此函式，不再
         * 子字串比對）。子字串比對會誤判套件名前綴相同的其他服務。
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':', ';').any { entry ->
                val item = entry.trim()
                val sep = item.indexOf('/')
                sep > 0 && item.substring(0, sep) == context.packageName
            }
        }

        /**
         * 服務啟動閘門（D1-14，純判斷、無副作用）：模型載入成功，且系統設定
         * 真實啟用，且引擎上次同步啟動未失敗。
         *
         * 「引擎啟動成功」以「服務實例存在且流程態非 ERROR」判定：startEngine
         * 同步失敗（例外／landmarker 建置失敗）會清引擎並進 ERROR（見
         * startEngine 的 catch）；已停止／已暫停時引擎為 null 是預期狀態，
         * 不視為失敗，resumeTracking 會重啟引擎。相機非同步綁定失敗無可觀測
         * 信號（見 (d) 已知缺口），本閘門不宣稱覆蓋，改由 verifyServiceRunning
         * 以樣本新鮮度擋下。呼叫端須依序診斷缺哪一項（見 MainActivity 開始鈕），
         * 不得只憑此布林值顯示含糊提示。
         */
        fun canStartTracking(context: Context): Boolean {
            if (!CalibrationStore.exists(
                    context, CalibrationStore.getActiveProfileId(context),
                )
            ) return false
            if (!isServiceEnabled(context)) return false
            val snap = trackingSnapshot() ?: return false
            return snap.flowState != AppFlowState.ERROR
        }

        /**
         * 驗證服務實際運作（D1-15，純判斷、無副作用）：快照非 null，且 3 秒內
         * 有樣本（現況 SAMPLE_FRESH_MS），且有模型。臉移出畫面時引擎仍持續
         * 送 null 樣本、時間戳照樣更新，故「有新鮮樣本」不等於「看得到臉」；
         * 本函式只驗「服務在跑」，不驗臉部狀態（定義見 PLAN D1-15，不得加
         * facePresent 改寫定義）。等待快照新鮮不設逾時：呼叫端以手動重試
         * 驗證，不得自動判定失敗。
         */
        fun verifyServiceRunning(): Boolean {
            val snap = trackingSnapshot() ?: return false
            return snap.sampleFresh && snap.hasModel
        }

        /** Dashboard「暫停眼控」：切到 PAUSED 模式（游標隱藏，只剩面板「重啟」鍵）。 */
        fun pauseTracking() {
            val svc = instance ?: return
            svc.mainHandler.post {
                svc.currentMode = ActionMode.PAUSED
                svc.controlView?.activeMode = ActionMode.PAUSED
                svc.controlView?.invalidate()
                // 暫停即清除待執行意圖（STEP 3，DCL-18）：舊 dwell／滑動
                // 起點／眨眼狀態／待發雙擊第二下不得在恢復後沿用。
                svc.cancelPendingGesture()
                // 暫停時把面板展開，否則「重啟」鍵藏在把手後面看不到。
                svc.setPanelExpanded(true)
                svc.cursorView?.setCursor(null)
            }
        }

        /** Dashboard「開始眼控」：解除暫停／解除完全停止，引擎未跑就啟動。
         *
         * RECOVERY_MODE 時不走本函式的旗標語義：改走 D2-3 同一驗證序列
         *（[resumeFromRecovery]，見下），避免手點與眼控兩條恢復路徑語義打架；
         * 此處的轉送只是入口共用，恢復本身由 [GazeAccessibilityService.attemptRecovery]
         * 執行。面板可見性一併恢復（RECOVERY_MODE 入口曾將面板藏起）。 */
        fun resumeTracking() {
            val svc = instance ?: return
            svc.mainHandler.post {
                if (svc.inRecoveryMode) {
                    if (!svc.recoveryVerifying && !svc.pausedForCalibration) {
                        svc.recoveryVerifying = true
                        svc.attemptRecovery()
                    }
                    return@post
                }
                svc.userStopped = false
                svc.controlView?.visibility = View.VISIBLE
                if (svc.currentMode == ActionMode.PAUSED) {
                    svc.currentMode = ActionMode.TAP
                    svc.controlView?.activeMode = ActionMode.TAP
                    svc.controlView?.invalidate()
                    svc.setPanelExpanded(false)
                }
                if (svc.engine == null) svc.startEngine()
            }
        }

        /**
         * Dashboard 手點恢復（RECOVERY_MODE，D2-6）：走 D2-3 同一驗證序列，
         * 與眼控 dwell 滿的入口函式共用（[GazeAccessibilityService.attemptRecovery]）。
         * 校正交接期間不嘗試（相機歸校正頁所有）。
         */
        fun resumeFromRecovery() {
            val svc = instance ?: return
            svc.mainHandler.post {
                if (!svc.inRecoveryMode || svc.recoveryVerifying ||
                    svc.pausedForCalibration || svc.userStopped
                ) return@post
                svc.recoveryVerifying = true
                svc.attemptRecovery()
            }
        }

        /**
         * Dashboard「停止眼控」（D2-1 改語義）：立 `inRecoveryMode`、不停止
         * 引擎、不清 `userStopped`；游標隱藏、面板隱藏、顯示重啟目標
         * （D2-2）；一般輸入由 `onSampleOnMainThread` 的 RECOVERY 分支拒絕。
         * 相機續跑，隱私告知見 D2-5 三處文案（對話框／狀態列／目標副文字）。
         * 已完全停止／已在恢復態／校正交接期間呼叫時不做事。
         */
        fun stopTracking() {
            val svc = instance ?: return
            svc.mainHandler.post {
                if (svc.userStopped || svc.inRecoveryMode || svc.pausedForCalibration) return@post
                svc.inRecoveryMode = true
                svc.recoveryDwellAccumMs = 0L
                svc.recoveryLastFrameAt = SystemClock.elapsedRealtime()
                svc.recoveryVerifying = false
                // recoveryArmed 沿用現值：追蹤期間視線已離開目標區即為 true；
                // 若仍停在目標區內，RECOVERY 分支的未武裝凍結會等它先離開。
                svc.hasValidCursor = false
                svc.cursorView?.setCursor(null)
                svc.dismissReminder(markSkipped = false)
                // 進入 RECOVERY_MODE：進行中的邊緣手勢中斷（D3-8），確認目標收掉。
                svc.cancelEdgeGesture()
                // 停止即清除待執行意圖（STEP 3，DCL-20）：雙擊第二下、
                // 滑動起點、眨眼狀態不得在重啟後重播。
                svc.cancelPendingGesture()
                svc.controlView?.visibility = View.GONE
                svc.controlView?.setHover(-1, 0f)
                svc.controlView?.setHandleProgress(0f)
                svc.hoverBtnIndex = -1
                svc.hoverStart = 0L
                svc.handleHoverStart = 0L
                svc.screenDwell.reset()
                svc.currentProgress = 0f
                svc.cursorView?.updateRecoveryTarget(
                    svc.screenW / 2f, svc.screenH / 2f, 0f, faceLost = false,
                )
                svc.syncFlowState(FlowEvent.StopEyeControl, AppFlowState.RECOVERY_MODE)
            }
        }

        /**
         * Dashboard「完全停止」（D2-1 新增）：現況 `stopTracking` 的完整本體
         * 原樣搬入（立 `userStopped`、背景停引擎、清狀態），另加
         * `inRecoveryMode = false` 與重啟目標收掉。引擎停止、相機釋放，
         * 眼睛無法恢復，須手點「開始眼控」。
         *
         * 已知缺口 K-30：`userStopped`／`inRecoveryMode` 未持久化，行程重啟
         * 即丟失，維持現況並在旗標宣告處明示，不得宣稱為已修復。
         */
        fun stopCompletely() {
            val svc = instance ?: return
            // 先立旗標擋住 startEngine，避免停表期間被重啟；停表本身放背景執行緒
            // （engine.stop() 會阻塞等分析執行緒排空，不可卡主執行緒）。
            svc.inRecoveryMode = false
            svc.userStopped = true
            Thread {
                val target = svc.engine
                target?.stop()
                if (target != null && svc.engine === target) svc.engine = null
                svc.mainHandler.post {
                    svc.hasValidCursor = false
                    lastFacePresent = false
                    lastCursorValid = false
                    lastSampleAtMs = 0L
                    svc.cursorView?.setCursor(null)
                    svc.cursorView?.hideReminderTarget()
                    svc.cursorView?.hideRecoveryTarget()
                    // 完全停止：進行中的邊緣手勢中斷（D3-8），確認目標收掉。
                    svc.cancelEdgeGesture()
                    // 完全停止亦清除待執行意圖（STEP 3）：雙擊第二下、
                    // 滑動起點、眨眼狀態不得殘留至下次啟動。
                    svc.cancelPendingGesture()
                    svc.recoveryVerifying = false
                    svc.recoveryDwellAccumMs = 0L
                    svc.reloadConditionState()
                    // 停止期間按了開始、userStopped 已被清掉：引擎已被置 null，在此重啟，
                    // 否則狀態會停在「未停止、無引擎、無路徑重啟」的死角。
                    if (!svc.userStopped && svc.engine == null) svc.startEngine()
                    // 停止完成才同步為 STOPPED。停止期間已按開始且引擎重啟時，
                    // startEngine 已同步狀態，此處不再覆蓋（ERROR／SERVICE_RUNNING
                    // 皆以其為準）。
                    if (svc.userStopped) {
                        svc.syncFlowState(FlowEvent.StopCompletely, AppFlowState.STOPPED)
                    }
                }
            }.start()
        }

        private const val TAG = "GazeService"

        // 條件感知漂移修正的節奏常數（變化閾值本身集中在 ConditionHistory）
        private const val CONDITION_CHECK_INTERVAL_MS = 500L
        private const val CONDITION_SMOOTH_ALPHA = 0.15
        private const val REMINDER_DWELL_MS = 1000L
        private const val REMINDER_TIMEOUT_MS = 5000L
        private const val REMINDER_AWAY_MS = 500L
        // 注視半徑必須大於常見的未修正漂移量（否則永遠完不成校正）；偏移取預測點平均，
        // 半徑只決定「是不是在看目標」，放寬不影響量測精度
        private const val REMINDER_DWELL_RADIUS_PX = 200f
        private const val REMINDER_SKIP_COOLDOWN_MS = 60_000L
        private const val REMINDER_MIN_SAMPLES = 5
        // 模式面板自動收合：選完模式後顯示選中態 1.2 秒再收回；
        // 展開後閒置 4 秒且視線不在面板上也收回（暫停模式除外）。
        private const val PANEL_COLLAPSE_AFTER_SELECT_MS = 1200L
        private const val PANEL_AUTO_COLLAPSE_MS = 4000L
        // 邊緣手勢門檻（含原始預測 ±15% 容忍邊界）已集中至 EdgeGestureController
        // 的 companion 常數（D-3「門檻集中管理」），服務端不再留副本。
        // 行為參數（停留時間、冷卻、眨眼、平滑）統一由 GazeSettings 提供，此處不再寫死。
    }
}
