package com.example.eyecontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import android.view.View
import kotlin.math.min

/**
 * 9 點視線校正：依序顯示 3×3 目標點，使用者「頭保持不動、只用眼睛看」，
 * 每點先等視線穩定再收樣本，全部完成後訓練 GazeModel 並存檔。
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var view: CalibrationView
    private lateinit var engine: FaceTrackerEngine

    // 收集到的訓練資料
    private val allFeatures = mutableListOf<DoubleArray>()
    private val allTargets = mutableListOf<Pair<Float, Float>>()

    /** 與 allFeatures 一一對應的裝置角度（每筆樣本當下的 pitch / roll），用來算校正基準。 */
    private val allDeviceAngles = mutableListOf<Pair<Double, Double>>()

    // 目前狀態（僅在主執行緒讀寫；樣本從背景執行緒 post 過來）
    private var pointIndex = 0
    private var settleUntil = 0L
    private var points: List<Pair<Float, Float>> = emptyList()
    private var finished = false

    /** 引擎是否已啟動：onPause/onResume 交接時用，避免重複綁定或漏停。 */
    private var engineStarted = false

    /** 臉遺失回饋：最後一幀有效樣本的時間、提示是否已顯示、本點開始計時的時間。 */
    private var lastValidSampleAt = 0L
    private var faceLostHintShown = false
    private var pointStartedAt = 0L

    /** 目前目標點收到的原始樣本，收滿後剔除離群值再併入訓練集。 */
    private val pointBuffer = mutableListOf<DoubleArray>()

    /** 與 pointBuffer 一一對應的裝置角度，剔除離群值時跟著同一批樣本走。 */
    private val pointDeviceAngles = mutableListOf<Pair<Double, Double>>()

    /** 睜眼閾值：與服務共用 GazeSettings 的同一設定來源。 */
    private var blinkThreshold = GazeSettings.DEFAULT_BLINK_THRESHOLD

    /** 本次校正的情境（由主畫面傳入，預設為目前使用中的情境）。 */
    private var profileId = CalibrationStore.PROFILE_SITTING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blinkThreshold = GazeSettings.load(this).blinkThreshold
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
            ?: CalibrationStore.getActiveProfileId(this)
        view = CalibrationView(this)
        setContentView(view)

        // 隱藏系統列，確保校正座標涵蓋整個實體螢幕
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        engine = FaceTrackerEngine(this) { sample ->
            runOnUiThread { onSample(sample) }
        }

        view.post {
            points = buildPoints(view.width.toFloat(), view.height.toFloat())
            startPoint(0)
            // 先請無障礙服務放開相機，確認釋放後才綁定自己的——避免兩邊搶相機導致畫面串流被拔掉
            GazeAccessibilityService.requestPauseForCalibration {
                if (!isFinishing && !finished &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    engine.start(this)
                    engineStarted = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 從最近任務切回或被蓋過後回來：相機已在 onPause 放掉，重綁一次
        if (!finished && !engineStarted && points.isNotEmpty()) {
            GazeAccessibilityService.requestPauseForCalibration {
                if (!isFinishing && !finished && !engineStarted &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    engine.start(this)
                    engineStarted = true
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 被蓋住或切走時先放開相機，否則舊實例佔著相機、新實例收不到幀
        if (engineStarted) {
            engine.stop()
            engineStarted = false
        }
    }

    /** 3×3 網格，邊緣留 8% 邊距。 */
    private fun buildPoints(w: Float, h: Float): List<Pair<Float, Float>> {
        val xs = listOf(0.08f, 0.5f, 0.92f).map { it * w }
        val ys = listOf(0.08f, 0.5f, 0.92f).map { it * h }
        return ys.flatMap { y -> xs.map { x -> x to y } }
    }

    private fun startPoint(index: Int) {
        pointIndex = index
        pointBuffer.clear()
        pointDeviceAngles.clear()
        settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
        pointStartedAt = SystemClock.elapsedRealtime()
        val (x, y) = points[index]
        view.showTarget(x, y, 0f, "${index + 1} / ${points.size}")
    }

    private fun onSample(sample: GazeSample?) {
        if (finished || points.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (sample == null) {
            // 臉遺失不再靜默：先在畫面提示，單點等太久就重採該點，避免無限卡住
            if (!faceLostHintShown && lastValidSampleAt != 0L &&
                now - lastValidSampleAt > FACE_LOST_HINT_MS
            ) {
                faceLostHintShown = true
                view.setFaceLost(true)
                Toast.makeText(this, "找不到臉，請把臉移回畫面中央", Toast.LENGTH_SHORT).show()
            }
            if (pointStartedAt != 0L && now - pointStartedAt > POINT_TIMEOUT_MS) {
                Toast.makeText(this, "這一格等太久，重採一次", Toast.LENGTH_SHORT).show()
                startPoint(pointIndex)
            }
            return
        }
        lastValidSampleAt = now
        faceLostHintShown = false
        view.setFaceLost(false)
        // 眨眼/閉眼期間的樣本會污染訓練資料，丟棄
        if (sample.leftOpen < blinkThreshold || sample.rightOpen < blinkThreshold) return

        if (now < settleUntil) return // 等視線移過去、穩定下來

        val target = points[pointIndex]
        pointBuffer.add(sample.features)
        pointDeviceAngles.add(sample.devicePitch to sample.deviceRoll)
        view.showTarget(target.first, target.second,
            pointBuffer.size / SAMPLES_PER_POINT.toFloat(),
            "${pointIndex + 1} / ${points.size}")

        if (pointBuffer.size >= SAMPLES_PER_POINT) {
            commitPoint(target)
            if (pointIndex + 1 < points.size) {
                startPoint(pointIndex + 1)
            } else {
                finishCalibration()
            }
        }
    }

    /**
     * 剔除離群值後併入訓練集：以虹膜特徵（前 4 維）的樣本中心為基準，
     * 只保留距離最近的 KEEP_RATIO 比例——視線飄移、殘留眨眼幀都會被丟掉。
     */
    private fun commitPoint(target: Pair<Float, Float>) {
        val centroid = DoubleArray(4)
        for (f in pointBuffer) for (i in 0 until 4) centroid[i] += f[i] / pointBuffer.size

        val keep = (pointBuffer.size * KEEP_RATIO).toInt().coerceAtLeast(1)
        // 依同一排序取同一批樣本：特徵與裝置角度必須成對保留，否則基準會錯位
        pointBuffer.indices
            .sortedBy { i ->
                (0 until 4).sumOf { j ->
                    (pointBuffer[i][j] - centroid[j]) * (pointBuffer[i][j] - centroid[j])
                }
            }
            .take(keep)
            .forEach { i ->
                allFeatures.add(pointBuffer[i])
                allDeviceAngles.add(pointDeviceAngles[i])
                allTargets.add(target)
            }
    }

    private fun finishCalibration() {
        finished = true
        val screenW = view.width
        val screenH = view.height
        // 校正期間裝置姿勢的平均值 = 陀螺儀修正的基準（只取有量到角度的樣本；全無則不啟用修正）
        val finiteAngles = allDeviceAngles.filter { it.first.isFinite() && it.second.isFinite() }
        val deviceBasePitch = finiteAngles.map { it.first }.average().takeIf { finiteAngles.isNotEmpty() }
        val deviceBaseRoll = finiteAngles.map { it.second }.average().takeIf { finiteAngles.isNotEmpty() }
        val model = GazeModel.train(
            allFeatures, allTargets,
            screenW = screenW.toDouble(), screenH = screenH.toDouble(),
            deviceBasePitch = deviceBasePitch, deviceBaseRoll = deviceBaseRoll,
        )

        // 訓練集誤差偏樂觀，只列出來參考；精度等級以留一法交叉驗證為準
        val report = GazeEvaluator.evaluate(model, allFeatures, allTargets, screenW, screenH)
        val cvErr = GazeEvaluator.leaveOnePointOut(allFeatures, allTargets, screenW, screenH)
        val avgErr = report.inSampleMeanErrorPx.toInt()
        val gradeErr = if (cvErr.isNaN()) avgErr else cvErr.toInt()
        val rating = when {
            gradeErr < 60 -> "極佳 🌟"
            gradeErr < 120 -> "優良 ✨"
            gradeErr < 200 -> "普通 👍"
            else -> "偏差 ⚠️ (建議放穩手機重新校正)"
        }
        val worst = report.worstPoint
        val cvText = if (cvErr.isNaN()) "—" else "${cvErr.toInt()} px"

        CalibrationStore.saveModel(this, profileId, model, cvErr.takeIf { !it.isNaN() })
        Toast.makeText(
            this,
            "「${CalibrationStore.profileName(profileId)}」校正完成！\n精度等級：$rating\n訓練集平均誤差：$avgErr px\n" +
                "留一法交叉驗證誤差：$cvText\n" +
                "最差：第 ${worst.pointIndex + 1} 點（${worst.meanErrorPx.toInt()} px）",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        engineStarted = false
        // 通知服務重新載入校正結果並接回相機
        GazeAccessibilityService.resumeAfterCalibration()
    }

    /** 畫校正目標點：帶脈衝動畫的外圈進度環 + 呼吸內圈實心點。 */
    private class CalibrationView(context: Context) : View(context) {
        private var tx = -1f
        private var ty = -1f
        private var progress = 0f
        private var label = ""
        private var faceLost = false

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(79, 195, 247) }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 79, 195, 247)
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val bgRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 8f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 200, 200); textSize = 44f; textAlign = Paint.Align.CENTER
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 120, 120); textSize = 36f; textAlign = Paint.Align.CENTER
        }
        private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 150, 120); textSize = 40f; textAlign = Paint.Align.CENTER
        }

        fun showTarget(x: Float, y: Float, p: Float, text: String) {
            tx = x; ty = y; progress = p; label = text
            invalidate()
        }

        fun setFaceLost(lost: Boolean) {
            if (faceLost == lost) return
            faceLost = lost
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(18, 18, 24)) // 護眼深藍黑色背景
            if (tx < 0) return

            // 畫提示文字與進度文字
            canvas.drawText(label, width / 2f, height / 2f + 100f, textPaint)
            canvas.drawText("請保持頭部不動，用眼睛注視動態藍點", width / 2f, height / 2f + 160f, hintPaint)
            if (faceLost) {
                canvas.drawText("找不到臉，請把臉移回畫面中央", width / 2f, height / 2f + 220f, warnPaint)
            }

            val time = System.currentTimeMillis()
            
            // 呼吸脈衝動畫：內圈點大小會隨時間縮放
            val pulseScale = 1.0f + 0.18f * kotlin.math.sin(time / 160.0).toFloat()
            val baseRadius = 20f
            
            // 畫外圈淡脈衝光暈
            val pulseRadius = baseRadius * (pulseScale + 0.3f)
            canvas.drawCircle(tx, ty, pulseRadius, pulsePaint)
            
            // 畫實心目標點
            canvas.drawCircle(tx, ty, baseRadius * pulseScale, dotPaint)

            // 畫進度軌道（淡灰色背景環）
            val r = 40f
            canvas.drawCircle(tx, ty, r, bgRingPaint)

            // 進度環：收滿樣本時剛好畫滿一圈
            canvas.drawArc(
                tx - r, ty - r, tx + r, ty + r,
                -90f, 360f * min(progress, 1f), false, ringPaint,
            )

            // 觸發下一幀動畫重繪
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val SETTLE_MS = 900L        // 每點先等 0.9 秒讓視線穩定
        private const val SAMPLES_PER_POINT = 20  // 每點收 20 幀（後續剔除離群值）
        private const val FACE_LOST_HINT_MS = 1000L  // 連續 1 秒收不到臉就顯示提示
        private const val POINT_TIMEOUT_MS = 10_000L // 單點 10 秒採不滿就重採該點
        /** Intent extra：要校正的情境 id（見 CalibrationStore）。 */
        const val EXTRA_PROFILE_ID = "profile_id"

        private const val KEEP_RATIO = 0.7        // 每點保留最接近中心的 70% 樣本
        // 睜眼閾值改由 GazeSettings 提供（與服務共用），見 blinkThreshold 欄位。
    }
}
