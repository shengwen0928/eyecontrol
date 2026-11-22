package com.example.eyecontrol

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import kotlin.math.hypot
import kotlin.math.min

/**
 * 眼控操作訓練：7 個練習依序進行，成功判定一律用真實注視停留
 *（dwell 計時＋停留半徑），進度只記當次 session，不存成績、不宣稱療效。
 *
 * 相機取用走與 CalibrationActivity 相同的交接協議
 *（requestPauseForCalibration／resumeAfterCalibration），絕不與服務搶相機；
 *無校正模型時不顯示假游標，先導引去校正。
 */
class TrainingActivity : AppCompatActivity() {

    private lateinit var view: TrainingView
    private lateinit var engine: FaceTrackerEngine
    private lateinit var btnAction: MaterialButton
    private lateinit var btnLeave: MaterialButton

    private var model: GazeModel? = null
    private var settings = GazeSettings()
    private var profileId = CalibrationStore.PROFILE_SITTING

    private val filterX = OneEuroFilter(minCutoff = 0.2, beta = 0.015)
    private val filterY = OneEuroFilter(minCutoff = 0.2, beta = 0.015)

    /** 相機／服務交接狀態（與 CalibrationActivity 相同守衛）。 */
    private var engineStarted = false
    private var pausedService = false
    private var leaving = false
    /** 已發出相機交接請求、服務回呼尚未回來：擋住 view.post 與 onResume 的重複請求。 */
    private var cameraRequestPending = false

    // 練習狀態機（只在主執行緒讀寫；樣本從背景執行緒 post 過來）
    private var exerciseIndex = 0
    private var targetIndex = 0
    private var targets: List<Pair<Float, Float>> = emptyList()
    private var anchor: Pair<Float, Float>? = null
    private var anchorStart = 0L
    private var successUntil = 0L
    private var retryUntil = 0L
    private var scrollsDone = 0
    private var scrollOffset = 0
    private var scrollAnchorStart = 0L
    private var completedCount = 0
    private var allDone = false
    private var cursor: Pair<Float, Float>? = null

    // 按鈕注視停留狀態（M1：純眼控使用者不靠手指也能跳過／離開）
    private var buttonDwellTarget: MaterialButton? = null
    private var buttonDwellStart = 0L
    /** 觸發後須先把視線移開按鈕才重新計時，避免停留不動連續誤觸。 */
    private var buttonDwellArmed = true

    /** 目標點是否已按實際畫面尺寸換算（onCreate 時 View 尚未量測，須延後到有尺寸時）。 */
    private var targetsLaidOut = false

    private val launchCalibration =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 校正頁不回傳結果碼：回來一律重查 CalibrationStore 才算數。
            if (CalibrationStore.exists(this, profileId)) {
                setupModelAndStart()
            } else {
                refreshUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = GazeSettings.load(this)
        profileId = CalibrationStore.getActiveProfileId(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "請先授予相機權限", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        view = TrainingView(this)
        engine = FaceTrackerEngine(this) { sample ->
            runOnUiThread { onSample(sample) }
        }

        // 下方按鈕列：手指可點，也可用眼控注視停留觸發（見 handleButtonDwell），
        // 純眼控使用者不靠手指也能跳過／離開。
        val density = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                val m = (16 * density).toInt()
                setMargins(m, 0, m, m)
            }
        }
        btnAction = MaterialButton(this).apply {
            text = "跳過這項"
            contentDescription = "跳過目前這一項練習"
            cornerRadius = (12 * density).toInt()
            strokeWidth = (1 * density).toInt()
            strokeColor = ColorStateList.valueOf(Color.rgb(238, 237, 232))
            backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            setTextColor(Color.rgb(32, 37, 34))
            layoutParams = LinearLayout.LayoutParams(0, (52 * density).toInt(), 1f).apply {
                marginEnd = (10 * density).toInt()
            }
            setOnClickListener { onActionButton() }
        }
        btnLeave = MaterialButton(this).apply {
            text = "離開練習"
            contentDescription = "離開操作訓練"
            cornerRadius = (12 * density).toInt()
            backgroundTintList = ColorStateList.valueOf(Color.rgb(82, 111, 92))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, (52 * density).toInt(), 1f)
            setOnClickListener { finish() }
        }
        bar.addView(btnAction)
        bar.addView(btnLeave)

        val root = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(bar)
        }
        setContentView(root)

        // 隱藏系統列，確保預測座標涵蓋整個實體螢幕（與校正頁相同做法）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (CalibrationStore.exists(this, profileId)) {
            setupModelAndStart()
        } else {
            refreshUi()
        }
    }

    /** 載入模型、重置濾波器，並走交接協議啟動相機（只在有模型時呼叫）。 */
    private fun setupModelAndStart() {
        model = CalibrationStore.loadModel(this, profileId)
        if (model == null) {
            refreshUi()
            return
        }
        filterX.updateParams(minCutoff = settings.filterMinCutoff, beta = settings.filterBeta)
        filterY.updateParams(minCutoff = settings.filterMinCutoff, beta = settings.filterBeta)
        filterX.reset()
        filterY.reset()
        startExercise(0)
        view.post {
            requestCameraAndStart()
        }
        refreshUi()
    }

    private fun requestCameraAndStart() {
        // 擋重複請求：view.post 與 onResume 可能各觸發一次；服務回呼前不再重發。
        if (cameraRequestPending) return
        cameraRequestPending = true
        // 先請無障礙服務放開相機，確認釋放後才綁定自己的——避免兩邊搶相機
        pausedService = true
        GazeAccessibilityService.requestPauseForCalibration {
            cameraRequestPending = false
            if (!isFinishing && !leaving &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                engine.start(this)
                engineStarted = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 從校正頁回來或被蓋過後回來：相機已在 onPause 放掉，重綁一次
        if (model != null && !engineStarted && !leaving && !cameraRequestPending && view.width > 0) {
            requestCameraAndStart()
        }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        if (engineStarted) {
            engine.stop()
            engineStarted = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        leaving = true
        if (engineStarted) {
            engine.stop()
            engineStarted = false
        }
        // 只有真的請服務讓過相機時，才通知它接回相機。
        if (pausedService) {
            GazeAccessibilityService.resumeAfterCalibration()
        }
    }

    private fun onActionButton() {
        if (model == null) {
            // 無模型：導引去校正，回來重查，不顯示假游標。
            val intent = Intent(this, CalibrationActivity::class.java)
            intent.putExtra(CalibrationActivity.EXTRA_PROFILE_ID, profileId)
            launchCalibration.launch(intent)
            return
        }
        if (allDone) {
            // 全部練完後，此鈕變成「再練一次」。
            startExercise(0)
            completedCount = 0
            allDone = false
        } else {
            advanceExercise()
        }
        refreshUi()
    }

    private fun startExercise(index: Int) {
        exerciseIndex = index
        targetIndex = 0
        anchor = null
        scrollsDone = 0
        scrollOffset = 0
        scrollAnchorStart = 0L
        filterX.reset()
        filterY.reset()
        targetsLaidOut = false
        targets = if (view.width > 0) {
            targetsFor(EXERCISES[index], view.width.toFloat(), view.height.toFloat())
        } else {
            emptyList()
        }
        refreshUi()
    }

    private fun advanceExercise() {
        if (exerciseIndex + 1 < EXERCISES.size) {
            startExercise(exerciseIndex + 1)
        } else {
            allDone = true
            anchor = null
            refreshUi()
        }
    }

    /** 練習目標：以畫面比例定義，實際 px 在開始練習時換算（旋轉後重算不適用：本頁鎖直向）。 */
    private fun targetsFor(def: ExerciseDef, w: Float, h: Float): List<Pair<Float, Float>> =
        def.targetFractions.map { (fx, fy) -> fx * w to fy * h }

    private fun requiredHoldMs(def: ExerciseDef): Long = when (def.kind) {
        Kind.ENTER -> ENTER_HOLD_MS
        Kind.DWELL, Kind.CANCEL -> settings.dwellTimeMs
        Kind.DWELL_LONG -> settings.dwellTimeMs * 2
        Kind.SCROLL -> SCROLL_HOLD_MS
    }

    private fun hitRadiusPx(): Float {
        val minRadius = 28f * resources.displayMetrics.density
        return maxOf(settings.dwellRadiusPx, minRadius)
    }

    private fun onSample(sample: GazeSample?) {
        if (leaving) return
        val now = SystemClock.elapsedRealtime()
        if (model == null || allDone) {
            // 無模型時服務未暫停，眼控點擊仍由服務手勢處理，本頁不做按鈕停留判定；
            // 全部練完後服務已暫停，改由本頁的按鈕停留判定接手「再練一次／離開」。
            if (sample == null) {
                resetButtonDwell()
                refreshUi()
                return
            }
            if (model != null) {
                updateCursor(sample)
                val pos = cursor
                if (pos != null && handleButtonDwell(pos, now)) {
                    refreshUi()
                    return
                }
            }
            refreshUi()
            return
        }
        if (sample == null) {
            // 臉遺失：凍結，不累積 dwell、不推進狀態。
            cursor = null
            anchor = null
            resetButtonDwell()
            view.faceLost = true
            view.dwellProgress = 0f
            refreshUi()
            return
        }
        view.faceLost = false
        // 閉眼期間凍結游標、重新累積 dwell（與服務相同的處理：閉眼幀不採信）。
        if (sample.leftOpen < settings.blinkThreshold ||
            sample.rightOpen < settings.blinkThreshold
        ) {
            anchor = null
            anchorStart = now
            resetButtonDwell()
            view.dwellProgress = 0f
            refreshUi()
            return
        }
        updateCursor(sample)

        // 成功提示顯示中：只更新游標，時間到自動進下一項。
        if (now < successUntil) {
            refreshUi()
            return
        }
        if (view.successText != null) {
            view.successText = null
            advanceExercise()
            refreshUi()
            return
        }

        val def = EXERCISES[exerciseIndex]
        val pos = cursor ?: run { refreshUi(); return }
        // 按鈕注視停留：先於練習目標判定；看著按鈕時不推進練習狀態。
        if (handleButtonDwell(pos, now)) {
            refreshUi()
            return
        }
        if (def.kind == Kind.SCROLL) {
            onScrollGaze(pos, now, def)
        } else {
            onTargetGaze(pos, now, def)
        }
        refreshUi()
    }

    /**
     * 按鈕注視停留（M1）：對下方兩顆按鈕的矩形做停留觸發，讓純眼控使用者
     * 不靠手指也能跳過／去校正／再練一次／離開。回傳 gaze 是否落在任一按鈕上
     * （落在按鈕上時呼叫端應跳過練習目標判定）。
     * 判定用與練習目標同一座標系的濾波後游標點；按鈕尚未量測時不判定。
     */
    private fun handleButtonDwell(pos: Pair<Float, Float>, now: Long): Boolean {
        val hovered = buttonAt(pos) ?: run {
            resetButtonDwell()
            return false
        }
        // 看著按鈕時不推進練習錨點，免得游標回到目標時進度亂跳。
        anchor = null
        scrollAnchorStart = 0L
        if (!buttonDwellArmed) {
            view.dwellProgress = 0f
            return true
        }
        if (buttonDwellTarget !== hovered) {
            buttonDwellTarget = hovered
            buttonDwellStart = now
            view.dwellProgress = 0f
            return true
        }
        val progress = min((now - buttonDwellStart) / settings.dwellTimeMs.toFloat(), 1f)
        view.dwellProgress = progress
        if (progress < 1f) return true
        // 觸發：走與手指點擊同一條路徑（performClick），4 種按鈕狀態全覆蓋。
        resetButtonDwell()
        buttonDwellArmed = false
        hovered.performClick()
        return true
    }

    private fun resetButtonDwell() {
        buttonDwellTarget = null
        buttonDwellStart = 0L
        view.dwellProgress = 0f
    }

    /** 游標點落在某顆按鈕上時回傳該按鈕，否則回傳 null（並重新待命）。 */
    private fun buttonAt(pos: Pair<Float, Float>): MaterialButton? {
        val target = listOf(btnAction, btnLeave).firstOrNull { btn ->
            buttonRectInView(btn)?.contains(pos.first, pos.second) == true
        }
        if (target == null) buttonDwellArmed = true
        return target
    }

    /** 按鈕矩形換算到 TrainingView 座標系；按鈕尚未量測時回傳 null。 */
    private fun buttonRectInView(btn: MaterialButton): RectF? {
        if (btn.width <= 0 || btn.height <= 0) return null
        val btnLoc = IntArray(2)
        btn.getLocationOnScreen(btnLoc)
        val viewLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)
        val left = (btnLoc[0] - viewLoc[0]).toFloat()
        val top = (btnLoc[1] - viewLoc[1]).toFloat()
        return RectF(left, top, left + btn.width, top + btn.height)
    }

    private fun updateCursor(sample: GazeSample) {
        val m = model ?: return
        val w = view.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = view.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val (rawX, rawY) = m.predict(
            sample.features, w, h,
            sample.devicePitch, sample.deviceRoll,
            settings.gyroCompensation, settings.distanceCompensation,
        )
        val t = sample.timestampMs / 1000.0
        val x = filterX.filter(rawX, t).toFloat().coerceIn(0f, w - 1f)
        val y = filterY.filter(rawY, t).toFloat().coerceIn(0f, h - 1f)
        cursor = x to y
    }

    private fun onTargetGaze(pos: Pair<Float, Float>, now: Long, def: ExerciseDef) {
        val target = targets.getOrNull(targetIndex) ?: return
        val inside = hypot(pos.first - target.first, pos.second - target.second) <= hitRadiusPx()
        if (!inside) {
            // 取消練習：曾經進入、停留 ≥150ms 後在畫滿前移開＝取消成功。
            if (def.kind == Kind.CANCEL && anchor != null &&
                now - anchorStart >= CANCEL_MIN_ENTER_MS
            ) {
                showSuccess("已取消！移開視線就取消，不會誤觸。")
            }
            anchor = null
            view.dwellProgress = 0f
            return
        }
        if (anchor != target) {
            anchor = target
            anchorStart = now
            view.dwellProgress = 0f
            return
        }
        val required = requiredHoldMs(def)
        val progress = min((now - anchorStart) / required.toFloat(), 1f)
        view.dwellProgress = progress
        if (progress < 1f) return
        if (def.kind == Kind.CANCEL) {
            // 停留滿了＝真的點到了：不是失敗，提示重試一次取消。
            anchor = null
            view.dwellProgress = 0f
            view.retryText = "這次點到了。再試一次：圓圈快畫滿之前，把視線移開。"
            retryUntil = now + RETRY_TEXT_MS
            return
        }
        targetIndex++
        anchor = null
        view.dwellProgress = 0f
        if (targetIndex >= targets.size) {
            val msg = if (def.kind == Kind.DWELL_LONG) "長按成功！" else "完成！"
            showSuccess(msg)
        }
    }

    private fun onScrollGaze(pos: Pair<Float, Float>, now: Long, def: ExerciseDef) {
        val zone = scrollZoneRect() ?: return
        if (!zone.contains(pos.first, pos.second)) {
            scrollAnchorStart = 0L
            view.dwellProgress = 0f
            return
        }
        if (scrollAnchorStart == 0L) {
            scrollAnchorStart = now
            view.dwellProgress = 0f
            return
        }
        val progress = min((now - scrollAnchorStart) / SCROLL_HOLD_MS.toFloat(), 1f)
        view.dwellProgress = progress
        if (progress < 1f) return
        scrollAnchorStart = 0L
        view.dwellProgress = 0f
        if (scrollOffset < SCROLL_MAX_OFFSET) scrollOffset++
        scrollsDone++
        if (scrollsDone >= SCROLL_NEED) {
            showSuccess("捲動完成！")
        } else {
            view.retryText = "已往下捲動（$scrollsDone / $SCROLL_NEED）"
            retryUntil = now + RETRY_TEXT_MS
        }
    }

    private fun showSuccess(text: String) {
        completedCount++
        view.successText = text
        successUntil = SystemClock.elapsedRealtime() + SUCCESS_SHOW_MS
    }

    private fun scrollZoneRect(): RectF? {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val d = resources.displayMetrics.density
        return RectF(w * 0.15f, h - 280 * d, w * 0.85f, h - 110 * d)
    }

    /** 把目前狀態同步到 View 與按鈕（全部取自真實狀態，不寫死）。 */
    private fun refreshUi() {
        val def = EXERCISES.getOrNull(exerciseIndex)
        view.noModel = model == null
        view.profileName = CalibrationStore.profileName(profileId)
        if (model == null) {
            view.title = "尚未校正"
            view.instruction = "先做一次 9 點校正，才能開始練習。練習時不會顯示假游標。"
            view.progressLabel = ""
            view.targets = emptyList()
            view.scrollRows = emptyList()
            view.scrollZone = null
            btnAction.text = "去校正"
            btnAction.contentDescription = "前往 9 點眼球校正"
        } else if (allDone) {
            view.title = "練習完成"
            view.instruction = "7 項都練完了，辛苦了！$completedCount 項在這次練習中完成。"
            view.progressLabel = "全部完成"
            view.targets = emptyList()
            view.scrollRows = emptyList()
            view.scrollZone = null
            btnAction.text = "再練一次"
            btnAction.contentDescription = "從第一項重新練習"
        } else if (def != null) {
            view.title = def.name
            view.instruction = def.instruction
            view.progressLabel = "第 ${exerciseIndex + 1} / ${EXERCISES.size} 項"
            // View 尚未量測時目標點無法換算：等有尺寸的第一幀再算，之後不再重算。
            if (def.kind != Kind.SCROLL && !targetsLaidOut && view.width > 0) {
                targets = targetsFor(def, view.width.toFloat(), view.height.toFloat())
                targetsLaidOut = true
            }
            view.targets = targets
            view.activeTargetIndex = targetIndex
            if (def.kind == Kind.SCROLL) {
                view.scrollRows = SCROLL_ROWS
                view.scrollOffset = scrollOffset
                view.scrollZone = scrollZoneRect()
            } else {
                view.scrollRows = emptyList()
                view.scrollZone = null
            }
            btnAction.text = "跳過這項"
            btnAction.contentDescription = "跳過目前這一項練習"
        }
        view.cursor = cursor
        if (retryUntil != 0L && SystemClock.elapsedRealtime() >= retryUntil) {
            view.retryText = null
            retryUntil = 0L
        }
        view.refresh()
    }

    /**
     * 程式繪製的練習畫面：淺色系、目標點、游標、凝視進度環、捲動列表。
     * 只負責繪製；成功判定與狀態機全部在 Activity。
     */
    private class TrainingView(context: Context) : View(context) {
        var title = ""
        var instruction = ""
        var progressLabel = ""
        var profileName = ""
        var targets: List<Pair<Float, Float>> = emptyList()
        var activeTargetIndex = 0
        var cursor: Pair<Float, Float>? = null
        var dwellProgress = 0f
        var faceLost = false
        var successText: String? = null
        var retryText: String? = null
        var noModel = false
        var scrollRows: List<String> = emptyList()
        var scrollOffset = 0
        var scrollZone: RectF? = null

        private val density = context.resources.displayMetrics.density
        private val scaledDensity = context.resources.displayMetrics.scaledDensity
        private fun dp(v: Float) = v * density

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(247, 246, 242)
        }
        private val targetHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 231, 238, 232)
            style = Paint.Style.FILL
        }
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 111, 92)
        }
        private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 111, 92)
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
        }
        private val doneTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(129, 155, 137)
        }
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 111, 92)
        }
        private val cursorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(129, 155, 137)
            style = Paint.Style.STROKE
            strokeWidth = dp(4f)
        }
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 237, 232)
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(231, 238, 232)
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 37, 34)
            textSize = 22f * scaledDensity
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val instructionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(116, 122, 117)
            textSize = 15f * scaledDensity
            textAlign = Paint.Align.CENTER
        }
        private val progressPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(116, 122, 117)
            textSize = 13f * scaledDensity
            textAlign = Paint.Align.CENTER
        }
        private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(193, 138, 72)
            textSize = 17f * scaledDensity
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val successPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(67, 134, 107)
            textSize = 26f * scaledDensity
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val rowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 37, 34)
            textSize = 16f * scaledDensity
            textAlign = Paint.Align.LEFT
        }
        private val zoneTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 37, 34)
            textSize = 16f * scaledDensity
            textAlign = Paint.Align.CENTER
        }

        fun refresh() {
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            val cx = width / 2f
            // 標題與說明置頂，避開系統列（本頁隱藏系統列，以頂部邊距留白）。
            canvas.drawText(title, cx, dp(56f), titlePaint)
            // 說明文字可能超過一行：用 StaticLayout 自動換行（中文按字斷行），
            // 避免小螢幕或大字級下被裁切；下方各行隨換行高度下移。
            val instructionWidth = (width - dp(48f)).toInt().coerceAtLeast(1)
            val instructionLayout = StaticLayout.Builder.obtain(
                instruction, 0, instruction.length, instructionPaint, instructionWidth,
            ).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            canvas.save()
            canvas.translate((width - instructionWidth) / 2f, dp(68f))
            instructionLayout.draw(canvas)
            canvas.restore()
            val belowInstructionY = dp(68f) + instructionLayout.height + dp(8f)
            if (progressLabel.isNotEmpty()) {
                canvas.drawText(progressLabel, cx, belowInstructionY, progressPaint2)
            }
            if (profileName.isNotEmpty() && !noModel) {
                canvas.drawText(
                    "情境：$profileName", cx, belowInstructionY + dp(20f), progressPaint2,
                )
            }

            if (noModel) {
                canvas.drawText(
                    "校正完成後，回到這裡開始練習。", cx, height / 2f, instructionPaint,
                )
                return
            }

            // 捲動練習：真實的列清單（捲動改變可見列，不是示意圖）。
            // 先畫捲動區底色，再畫列清單，標籤最後畫在最上層，避免底色蓋住列文字。
            val zone = scrollZone
            if (scrollRows.isNotEmpty() && zone != null) {
                canvas.drawRoundRect(zone, dp(12f), dp(12f), zonePaint)
                val rowH = dp(56f)
                val listTop = dp(150f)
                for (i in 0 until 4) {
                    val rowIndex = scrollOffset + i
                    if (rowIndex >= scrollRows.size) break
                    val top = listTop + i * (rowH + dp(8f))
                    val rect = RectF(dp(24f), top, width - dp(24f), top + rowH)
                    canvas.drawRoundRect(rect, dp(12f), dp(12f), cardPaint)
                    canvas.drawRoundRect(rect, dp(12f), dp(12f), cardBorderPaint)
                    canvas.drawText(
                        scrollRows[rowIndex], dp(40f),
                        top + rowH / 2f + 16f * scaledDensity / 2f, rowTextPaint,
                    )
                }
                canvas.drawText("看這裡往下捲", cx, zone.centerY() + 6f * scaledDensity, zoneTextPaint)
            } else {
                // 一般練習：已完成目標填實、當前目標帶光暈與進度環。
                for (i in targets.indices) {
                    val (tx, ty) = targets[i]
                    if (i < activeTargetIndex) {
                        canvas.drawCircle(tx, ty, dp(10f), doneTargetPaint)
                    } else if (i == activeTargetIndex) {
                        canvas.drawCircle(tx, ty, dp(22f), targetHaloPaint)
                        canvas.drawCircle(tx, ty, dp(14f), targetPaint)
                        canvas.drawCircle(tx, ty, dp(30f), targetRingPaint)
                    } else {
                        canvas.drawCircle(tx, ty, dp(10f), targetRingPaint)
                    }
                }
            }

            // 臉遺失提示（M6 同款文字）：游標不顯示，不累積進度。
            if (faceLost) {
                canvas.drawText(
                    "找不到臉，請把臉移回畫面中央", cx, height / 2f + dp(120f), warnPaint,
                )
            }

            // 游標：Deep Sage 實心點＋白描邊環；凝視進度環繞著游標畫。
            cursor?.let { (x, y) ->
                canvas.drawCircle(x, y, dp(14f), cursorPaint)
                canvas.drawCircle(x, y, dp(16f), cursorRingPaint)
                if (dwellProgress > 0f) {
                    val r = dp(26f)
                    canvas.drawArc(
                        x - r, y - r, x + r, y + r,
                        -90f, 360f * min(dwellProgress, 1f), false, progressPaint,
                    )
                }
            }

            // 成功回饋（只在真實達標後顯示）與 transient 提示。
            successText?.let {
                canvas.drawText(it, cx, height / 2f + dp(150f), successPaint)
            }
            retryText?.let {
                canvas.drawText(it, cx, height - dp(120f), instructionPaint)
            }
        }
    }

    private data class ExerciseDef(
        val name: String,
        val instruction: String,
        val kind: Kind,
        val targetFractions: List<Pair<Float, Float>>,
    )

    private enum class Kind { ENTER, DWELL, DWELL_LONG, CANCEL, SCROLL }

    companion object {
        private const val ENTER_HOLD_MS = 300L
        private const val SCROLL_HOLD_MS = 800L
        private const val CANCEL_MIN_ENTER_MS = 150L
        private const val SUCCESS_SHOW_MS = 1500L
        private const val RETRY_TEXT_MS = 2500L
        private const val SCROLL_NEED = 2
        private const val SCROLL_MAX_OFFSET = 4

        private val SCROLL_ROWS = listOf(
            "第 1 列：練習捲動", "第 2 列：往下看", "第 3 列：繼續往下",
            "第 4 列：快到底了", "第 5 列：再捲一次", "第 6 列：到底了",
            "第 7 列：多捲的", "第 8 列：最後一列",
        )

        private val EXERCISES = listOf(
            ExerciseDef(
                "看向不同方向",
                "依序看向四個角落的圓點，視線停一下就好。",
                Kind.ENTER,
                listOf(0.2f to 0.22f, 0.8f to 0.22f, 0.2f to 0.72f, 0.8f to 0.72f),
            ),
            ExerciseDef(
                "移動視線至目標",
                "跟著圓點移動視線，從左到右。",
                Kind.ENTER,
                listOf(0.15f to 0.45f, 0.5f to 0.45f, 0.85f to 0.45f),
            ),
            ExerciseDef(
                "停留在指定位置",
                "看著中央圓點，保持不動，直到圓圈畫滿。",
                Kind.DWELL,
                listOf(0.5f to 0.45f),
            ),
            ExerciseDef(
                "凝視點擊",
                "看著圓點直到圓圈畫滿，就像真的點了一下。",
                Kind.DWELL,
                listOf(0.5f to 0.35f),
            ),
            ExerciseDef(
                "練習取消操作",
                "先看著圓點，圓圈快畫滿之前把視線移開，就算取消成功。",
                Kind.CANCEL,
                listOf(0.5f to 0.45f),
            ),
            ExerciseDef(
                "練習捲動",
                "看著底部的捲動區，內容會真的往下捲，捲兩次就完成。",
                Kind.SCROLL,
                emptyList(),
            ),
            ExerciseDef(
                "練習長按",
                "看著圓點，比平常停留久一倍，就是長按。",
                Kind.DWELL_LONG,
                listOf(0.5f to 0.45f),
            ),
        )
    }
}
