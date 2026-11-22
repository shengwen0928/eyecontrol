package com.example.eyecontrol

import android.graphics.RectF
import android.widget.Toast
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
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
            settings = GazeSettings.load(this)
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
            mainHandler.post { reloadConditionState() }
        }

    private val filterX = OneEuroFilter(minCutoff = 0.2, beta = 0.015)
    private val filterY = OneEuroFilter(minCutoff = 0.2, beta = 0.015)

    /** 校正頁開啟期間暫停追蹤（相機同程序只有一路，兩邊搶會互踢）。 */
    @Volatile
    private var pausedForCalibration = false

    /** 使用者從 Dashboard 按「停止追蹤」：引擎停表且不再自動重啟，直到按「開始眼控」。 */
    @Volatile
    private var userStopped = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 模式與手勢狀態
    private var currentMode = ActionMode.TAP
    private var swipeStartPoint: Pair<Float, Float>? = null

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
        startEngine()
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
        engine?.start(this)
        applyVerifyCaptureFlags()
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
                // 提醒顯示期間不觸發點擊（提醒狀態機接管這一幀的後續流程）
                if (blinkDuration in settings.blinkMinMs..settings.blinkMaxMs && hasValidCursor && now >= cooldownUntil && currentMode != ActionMode.PAUSED && !reminderActive) {
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

        val (rawX, rawY) = m.predict(
            sample.features, screenW, screenH,
            sample.devicePitch, sample.deviceRoll,
            settings.gyroCompensation, settings.distanceCompensation,
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

        val edgeSize = 50f
        val isAtLeft = x < edgeSize
        val isAtRight = x > screenW - edgeSize
        val isAtTop = y < edgeSize
        val isAtBottom = y > screenH - edgeSize
        val isAtEdge = isAtLeft || isAtRight || isAtTop || isAtBottom

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
                screenDwell.reset()
                cursor.setCursor(null)
                return
            }

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
                    if (isAtEdge) {
                        // 邊緣手勢門檻：模型外推時游標會被箝在邊緣（實測壞時段
                        // 5–6 成時間卡在邊緣），只看濾波後位置會讓「看旁邊」誤觸
                        // 返回／首頁。必須同時滿足：原始預測在螢幕範圍附近（放寬
                        // 15% 邊界容忍模型小偏差，拒絕數千像素的外推值），且穩定
                        // 錨點確實落在邊緣區。任一不符就吞掉這次觸發（給冷卻、
                        // 不動作），絕不拿被箝制的邊緣位置當注視。
                        val rawMarginX = screenW * EDGE_RAW_MARGIN_FRACTION
                        val rawMarginY = screenH * EDGE_RAW_MARGIN_FRACTION
                        val rawInScreen = rawX >= -rawMarginX && rawX <= screenW + rawMarginX &&
                            rawY >= -rawMarginY && rawY <= screenH + rawMarginY
                        val anchorAtEdge = anchorX < edgeSize || anchorX > screenW - edgeSize ||
                            anchorY < edgeSize || anchorY > screenH - edgeSize
                        if (rawInScreen && anchorAtEdge) {
                            performEdgeAction(isAtLeft, isAtRight, isAtTop, isAtBottom)
                            // 驗證影格：邊緣手勢觸發時的實際眼區狀態（下一幀有臉時取）。
                            engine?.requestVerifyCapture(
                                VerifyFrames.CAPTURE_EDGE_FIRE, progress
                            )
                        } else {
                            Log.d(
                                TAG,
                                "邊緣手勢被擋下：原始預測超出螢幕或錨點不在邊緣，視為不可信注視",
                            )
                            // 驗證影格：被擋下的觸發也要留證據，才分得出誤擋還是真擋。
                            engine?.requestVerifyCapture(
                                VerifyFrames.CAPTURE_EDGE_DENIED, progress
                            )
                        }
                    } else {
                        triggerActionAt(anchorX, anchorY)
                        // 驗證影格：停留點擊觸發時的實際眼區狀態（下一幀有臉時取）。
                        engine?.requestVerifyCapture(
                            VerifyFrames.CAPTURE_DWELL_FIRE, progress
                        )
                    }
                    cooldownUntil = now + settings.cooldownMs
                    dwellStart = now
                    progress = 0f
                    screenDwell.reset()
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

    private fun currentCondition(): ConditionHistory.Condition =
        ConditionHistory.Condition(
            luxBucket = ConditionHistory.luxBucket(smoothedLux),
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
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                cursorView?.post { cursorView?.flashClick() }
            }
        }, null)
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                cursorView?.post { cursorView?.flashClick() }
            }
        }, null)
    }

    private fun performDoubleTap(x: Float, y: Float) {
        performTap(x, y)
        mainHandler.postDelayed({
            performTap(x, y)
        }, 150)
    }

    private fun performLongPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                cursorView?.post { cursorView?.flashClick() }
            }
        }, null)
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                cursorView?.post { cursorView?.flashClick() }
            }
        }, null)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
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
    override fun onInterrupt() = Unit


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
                    // 模式面板是常駐 overlay：暫停期間把它藏起來，否則會蓋住
                    // 校正／訓練頁右側的目標點（面板看得見但已停止反應）。
                    // 面板只在 onServiceConnected 建立一次、沒有重建路徑，
                    // 恢復時在 resumeAfterCalibration 設回 VISIBLE 即可。
                    svc.controlView?.visibility = View.GONE
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
                svc.startEngine()
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
            val hasModel: Boolean,
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
                hasModel = lastHasModel,
            )
        }

        /** Dashboard「暫停眼控」：切到 PAUSED 模式（游標隱藏，只剩面板「重啟」鍵）。 */
        fun pauseTracking() {
            val svc = instance ?: return
            svc.mainHandler.post {
                svc.currentMode = ActionMode.PAUSED
                svc.controlView?.activeMode = ActionMode.PAUSED
                svc.controlView?.invalidate()
                // 暫停時把面板展開，否則「重啟」鍵藏在把手後面看不到。
                svc.setPanelExpanded(true)
                svc.cursorView?.setCursor(null)
            }
        }

        /** Dashboard「開始眼控」：解除暫停／解除停止，引擎未跑就啟動。 */
        fun resumeTracking() {
            val svc = instance ?: return
            svc.mainHandler.post {
                svc.userStopped = false
                if (svc.currentMode == ActionMode.PAUSED) {
                    svc.currentMode = ActionMode.TAP
                    svc.controlView?.activeMode = ActionMode.TAP
                    svc.controlView?.invalidate()
                    svc.setPanelExpanded(false)
                }
                if (svc.engine == null) svc.startEngine()
            }
        }

        /** Dashboard「停止追蹤」：停引擎、關相機、藏游標；直到按「開始眼控」才恢復。 */
        fun stopTracking() {
            val svc = instance ?: return
            // 先立旗標擋住 startEngine，避免停表期間被重啟；停表本身放背景執行緒
            // （engine.stop() 會阻塞等分析執行緒排空，不可卡主執行緒）。
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
                    svc.reloadConditionState()
                    // 停止期間按了開始、userStopped 已被清掉：引擎已被置 null，在此重啟，
                    // 否則狀態會停在「未停止、無引擎、無路徑重啟」的死角。
                    if (!svc.userStopped && svc.engine == null) svc.startEngine()
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
        // 邊緣手勢的原始預測容忍邊界：原始預測超出螢幕此比例以外，視為外推失準、
        // 不得觸發邊緣手勢（見螢幕停留觸發處的門檻說明）。
        private const val EDGE_RAW_MARGIN_FRACTION = 0.15
        // 行為參數（停留時間、冷卻、眨眼、平滑）統一由 GazeSettings 提供，此處不再寫死。
    }
}
