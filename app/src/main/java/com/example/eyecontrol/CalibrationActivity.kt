/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.pow

/**
 * 9 點視線校正：依序顯示 3×3 目標點，使用者「頭保持不動、只用眼睛看」，
 * 每點先等視線穩定再收樣本，全部完成後訓練 GazeModel 並存檔。
 *
 * Phase 3 步驟 3（D1-7…D1-13，D1-9 僅預留）：
 * - 校正頁分五個階段（Phase）：HANDOVER（等相機交接）／SAMPLING（9 點採樣）／
 *   REPORT（常駐報告頁）／ASK（單點逾時詢問）／ERROR（失敗原因＋重試）。
 *   同一時刻只有一個階段接受樣本：採樣階段的幀只進 pointBuffer，選擇階段的幀
 *   只做注視選擇判定，兩者絕不混用同一幀——這是報告／詢問／重試三種注視目標
 *   與採樣目標互斥的唯一機制（狀態閘門），見 onSample 的階段分派。
 * - 選擇階段的注視判定複用 TrainingActivity 既有的按鈕注視停留規則
 *   （handleButtonDwell／updateCursor）：模型預測＋OneEuroFilter（設定參數）＋
 *   按鈕矩形命中＋停留秒數取 settings.dwellTimeMs＋觸發後武裝規則＋臉遺失／
 *   閉眼時重置計時。本頁沒有新寫一套 dwell；判定成立的前提是當下有可用模型
 *   （報告頁用剛訓練好的模型；詢問／重試頁用已存檔的舊模型）。沒有任何可用
 *   模型時（例如首次校正），注視選擇無法判讀：按鈕仍可手指點按，畫面會明說
 *   注視無法判讀，絕不假裝兩個目標可以同時分辨（見 D4_OPTIONS §1.3 的前提）。
 * - D1-9 取消目標：本步只留介面預留（見 companion 的 CANCEL_TARGET_RESERVED
 *   常數與註解），未完成功能、待 D-4 決策；不繪製、不註冊事件、不觸發取消。
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var view: CalibrationView
    private lateinit var engine: FaceTrackerEngine

    /** 選擇階段的按鈕列（真實 MaterialButton，手指可點、注視可觸發）。 */
    private lateinit var choiceBar: LinearLayout

    // 收集到的訓練資料
    private val allFeatures = mutableListOf<DoubleArray>()
    private val allTargets = mutableListOf<Pair<Float, Float>>()

    /** 與 allFeatures 一一對應的裝置角度（每筆樣本當下的 pitch / roll），用來算校正基準。 */
    private val allDeviceAngles = mutableListOf<Pair<Double, Double>>()

    /**
     * 與 allFeatures 一一對應的每筆 log10(lux)（NaN = 當時無感測器資料），
     * 用來算模型的光跨度與報告的光線範圍；與 allPassIds 一一對應的輪次 id
     * （多光源校正的第幾輪，0 起），用來算留一輪交叉驗證。
     */
    private val allLightLogs = mutableListOf<Double>()
    private val allPassIds = mutableListOf<Int>()

    /** 目前輪次（0 起）：多光源校正的第幾輪 9 點；每輪同一組目標點。 */
    private var passIndex = 0

    // 目前狀態（僅在主執行緒讀寫；樣本從背景執行緒 post 過來）
    private var pointIndex = 0
    private var settleUntil = 0L
    private var points: List<Pair<Float, Float>> = emptyList()

    /** 校正頁階段：同一時刻只有一個階段接受樣本（互斥閘門，見 onSample）。
     * PASS_CHOICE 為多光源校正的輪次選擇頁（一輪 9 點採完後，注視選擇
     * 繼續下一輪／完成／退出；不因光線變化暫停或警告）。 */
    private enum class Phase { HANDOVER, SAMPLING, REPORT, ASK, ERROR, PASS_CHOICE }
    private var phase = Phase.HANDOVER

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

    /** 與 pointBuffer 一一對應的每筆 log10(lux)，剔除離群值時跟著同一批樣本走。 */
    private val pointLightLogs = mutableListOf<Double>()

    /** 與 pointBuffer 一一對應的相對時間（毫秒，相對本次校正首筆樣本），供逐筆紀錄分析跨輪與晃動。 */
    private val pointSampleTimes = mutableListOf<Long>()

    /**
     * 校正逐筆紀錄（內部調校用）：每點全部原始樣本（含被離群值剔除的），附輪次、
     * 目標、相對時間、裝置角度與光線；存檔成功後經 [uploadCalibLog] 上傳。
     * 樣本只在記憶體，校正頁結束即丟棄，不留檔。
     */
    private val calibRows = mutableListOf<CalibLog.Row>()
    private var calibT0Ms = 0L
    private var calibUploaded = false
    private var calibUploadCounter = 0

    /** 眼區反光指標樣本（有效幀），作為本次校正的眼鏡／反光基準。 */
    private val calibrationGlassesSamples = mutableListOf<Double>()

    /** 使用者設定（單一來源）：睜眼閾值、停留秒數、濾波參數皆取自此處。 */
    private var settings = GazeSettings()

    /** 睜眼閾值：與服務共用 GazeSettings 的同一設定來源。 */
    private var blinkThreshold = GazeSettings.DEFAULT_BLINK_THRESHOLD

    /** 本次校正的情境（由主畫面傳入，預設為目前使用中的情境）。 */
    private var profileId = CalibrationStore.PROFILE_SITTING

    // ---- D1-11：相機交接逾時（校正頁自設逾時，協議本體不動） ----
    /** 已發出交接請求、服務回呼尚未回來：擋住重複請求（沿用 TrainingActivity 的守衛模式）。 */
    private var handoverPending = false
    /** 服務已回呼確認釋放相機：onResume 可直接啟動引擎，不必重新請求交接。 */
    private var handoverDone = false
    /** 交接世代計數：逾時重試／晚到回呼以此識別，過期回呼一律忽略。 */
    private var handoverGeneration = 0
    private val handoverHandler = Handler(Looper.getMainLooper())
    private var handoverTimeoutRunnable: Runnable? = null

    // ---- D1-10：單點逾時計數（每點獨立，成功採樣即歸零） ----
    private var pointTimeoutCount = 0

    /** 已 commit 的點數（只供 D1-12 檢查點記錄用；特徵本身只在記憶體串列中）。 */
    private var committedPoints = 0

    // ---- D1-7／D1-10／D1-11：選擇階段的注視判定（複用 TrainingActivity 規則） ----
    private val filterX = OneEuroFilter()
    private val filterY = OneEuroFilter()
    /** 選擇階段用來預測落點的模型：報告頁為剛訓練好的模型，其餘為已存檔舊模型（可為 null）。 */
    private var choiceModel: GazeModel? = null
    private data class Choice(
        val id: String,
        val label: String,
        val primary: Boolean,
        val onSelect: () -> Unit,
    )
    private var currentChoices: List<Choice> = emptyList()
    private var currentButtons: List<MaterialButton> = emptyList()
    /** 注視停留狀態（與 TrainingActivity.buttonDwell* 同一語義）。 */
    private var choiceDwellButton: MaterialButton? = null
    private var choiceDwellStart = 0L
    /** 觸發後須先把視線移開全部按鈕才重新計時，避免停留不動連續誤觸。 */
    private var choiceDwellArmed = true

    // ---- D1-7／D1-8：報告頁資料（全部來自 GazeEvaluator 回傳值，不編造） ----
    private data class ReportData(
        val model: GazeModel,
        val ratingLabel: String,
        val isPoorGrade: Boolean,
        val inSampleErrorPx: Int,
        val looErrorPx: Double,
        val looText: String,
        val worstPointIndex: Int,
        val worstErrorPx: Int,
        val perPoint: List<GazeEvaluator.PointError>,
        val calibratedAtMs: Long,
        /** 本次校正覆蓋的光線範圍文字（由平行 lux 記錄算出；無感測器資料時明說）。 */
        val lightSpanText: String,
        /** 留一輪交叉驗證誤差文字（輪次少於 2 個時為 "—"）。 */
        val leavePassOutText: String,
        /** 本次校正的輪數。 */
        val passCount: Int,
    )
    private var report: ReportData? = null
    private var reportExpanded = false

    // ---- D1-11：ERROR 態的失敗原因與重試動作 ----
    private var errorRetry: (() -> Unit)? = null

    /** D1-12：重建後進度遺失的誠實告知（特徵幀無法由 Bundle 還原，見 onCreate 註解）。 */
    private var restoreNotice: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = GazeSettings.load(this)
        blinkThreshold = settings.blinkThreshold
        filterX.updateParams(minCutoff = settings.filterMinCutoff, beta = settings.filterBeta)
        filterY.updateParams(minCutoff = settings.filterMinCutoff, beta = settings.filterBeta)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
            ?: CalibrationStore.getActiveProfileId(this)
        view = CalibrationView(this)

        // 選擇按鈕列：平時隱藏，進入報告／詢問／ERROR 階段時才顯示（見 showChoices）。
        val density = resources.displayMetrics.density
        choiceBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
                val m = (16 * density).toInt()
                setMargins(m, 0, m, m)
            }
        }
        val root = FrameLayout(this).apply {
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(choiceBar)
        }
        setContentView(root)

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

        // D1-12 檢查點：只記錄 pointIndex＋已採點數，不存特徵原始幀。
        // 本樹沒有名為 collectFeatures 的函式：已採點的特徵只存在記憶體串列
        // （allFeatures／allTargets／allDeviceAngles）中，Activity 重建後新實例
        // 的串列是空的，Bundle 裡的兩個整數無法重建它們。因此重建後只能誠實地
        // 從第 1 點重新採樣（舊模型與條件歷史皆不受影響），絕不以殘缺的訓練集
        // 假裝完成 9 點。若中斷前已有已 commit 的點，以文字明說進度已遺失。
        val restoredCommitted = savedInstanceState?.getInt(KEY_COMMITTED_POINTS, 0) ?: 0
        if (restoredCommitted > 0) {
            restoreNotice = "上次校正中斷，已採的 $restoredCommitted 點無法保留，從第 1 點重新開始。"
        }

        view.post {
            points = buildPoints(view.width.toFloat(), view.height.toFloat())
            view.setPoints(points)
            if (restoreNotice != null) {
                view.showMessage(
                    "校正重新開始",
                    listOfNotNull(restoreNotice, "頭保持不動，用眼睛注視圓點。", "相機啟動中"),
                )
            }
            startPoint(0)
            // 先請無障礙服務放開相機，確認釋放後才綁定自己的——避免兩邊搶相機導致畫面串流被拔掉
            requestCameraHandover()
        }
    }

    override fun onResume() {
        super.onResume()
        // 從最近任務切回或被蓋過後回來：相機已在 onPause 放掉，重綁一次。
        // 交接已完成時直接啟動引擎（服務仍為我們暫停著）；交接未完成且在等待
        // 階段時，若請求仍在進行中只重掛逾時計時，不重複發請求。
        if (isFinishing || engineStarted || points.isEmpty()) return
        if (handoverDone) {
            startEngineSafely()
        } else if (phase == Phase.HANDOVER && !handoverPending) {
            requestCameraHandover()
        } else if (phase == Phase.HANDOVER && handoverPending) {
            armHandoverTimeout(handoverGeneration)
        }
        // ERROR 態（交接逾時）不自動重試：由使用者注視／點按「重試」明確發起。
    }

    override fun onPause() {
        super.onPause()
        // 被蓋住或切走時先放開相機，否則舊實例佔著相機、新實例收不到幀
        if (engineStarted) {
            engine.stop()
            engineStarted = false
        }
        cancelHandoverTimeout()
        resetChoiceDwell()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 進度檢查點只存整數（見 onCreate 註解：特徵原始幀不存、也無法還原）。
        outState.putInt(KEY_POINT_INDEX, pointIndex)
        outState.putInt(KEY_COMMITTED_POINTS, committedPoints)
    }

    /**
     * D1-11：發起相機交接（requestPauseForCalibration 協議本體不動）。
     * 自設逾時取 POINT_TIMEOUT_MS（與單點逾時同級，不新編數字）：逾時未回呼即
     * 停止等待、顯示真實原因並提供重試／退出；重試即重新發起交接（世代計數
     * 遞增，舊請求的晚到回呼會被忽略，不會疊加）。
     */
    private fun requestCameraHandover() {
        if (handoverPending) return
        handoverPending = true
        handoverDone = false
        handoverGeneration++
        val generation = handoverGeneration
        phase = Phase.HANDOVER
        view.setWaiting(true)
        armHandoverTimeout(generation)
        GazeAccessibilityService.requestPauseForCalibration {
            onHandoverReleased(generation)
        }
    }

    private fun onHandoverReleased(generation: Int) {
        if (generation != handoverGeneration) return // 過期重試的晚到回呼，忽略
        cancelHandoverTimeout()
        handoverPending = false
        handoverDone = true
        view.setWaiting(false)
        if (isFinishing) return
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !engineStarted && phase == Phase.HANDOVER
        ) {
            startEngineSafely()
        }
        // 若回呼時頁面尚未 resumed，onResume 會以 handoverDone 直接啟動引擎。
    }

    private fun armHandoverTimeout(generation: Int) {
        cancelHandoverTimeout()
        val runnable = Runnable {
            if (generation != handoverGeneration || isFinishing) return@Runnable
            if (phase != Phase.HANDOVER || handoverDone) return@Runnable
            handoverPending = false
            enterError(
                message = "相機交接逾時：無障礙服務沒有在 ${POINT_TIMEOUT_MS / 1000} 秒內放開相機。",
                retryLabel = "重試",
                retry = { requestCameraHandover() },
            )
        }
        handoverTimeoutRunnable = runnable
        handoverHandler.postDelayed(runnable, POINT_TIMEOUT_MS)
    }

    private fun cancelHandoverTimeout() {
        handoverTimeoutRunnable?.let { handoverHandler.removeCallbacks(it) }
        handoverTimeoutRunnable = null
    }

    /**
     * 啟動相機引擎：模型或相機建立失敗時不讓 App 直接閃退，改留紀錄、顯示
     * 真實原因（D1-11：寫入 ERROR 態報告頁，不 finish() 丟棄），由使用者重試。
     */
    private fun startEngineSafely() {
        try {
            engine.start(this)
            engineStarted = true
            if (phase == Phase.HANDOVER) {
                phase = Phase.SAMPLING
                view.clearScreen()
            }
        } catch (e: Exception) {
            CrashReporter.recordCaught(this, "engine.start", e)
            enterError(
                message = "相機啟動失敗：${e.message}",
                retryLabel = "重試",
                retry = { requestCameraHandover() },
            )
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
        view.setCompleted(index)
        pointBuffer.clear()
        pointDeviceAngles.clear()
        pointLightLogs.clear()
        pointSampleTimes.clear()
        settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
        pointStartedAt = SystemClock.elapsedRealtime()
        val (x, y) = points[index]
        view.showTarget(x, y, 0f, "${index + 1} / ${points.size}")
    }

    /**
     * 階段分派（互斥閘門）：採樣階段的幀只做採樣，選擇階段的幀只做注視判定，
     * 交接等待期不接受任何樣本。結束中的頁面不接受樣本，避免 finish 後的晚到
     * 幀觸發選擇動作。
     */
    private fun onSample(sample: GazeSample?) {
        if (isFinishing || points.isEmpty()) return
        when (phase) {
            Phase.HANDOVER -> return
            Phase.REPORT, Phase.ASK, Phase.ERROR, Phase.PASS_CHOICE -> {
                onChoiceSample(sample)
                return
            }
            Phase.SAMPLING -> onSamplingSample(sample)
        }
    }

    private fun onSamplingSample(sample: GazeSample?) {
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
            checkPointTimeout(now)
            return
        }
        lastValidSampleAt = now
        faceLostHintShown = false
        view.setFaceLost(false)
        // 眼區反光指標同為條件：有效幀都收，作為本次校正的眼鏡／反光基準
        sample.specularRatio.takeIf { it.isFinite() && it >= 0.0 }?.let { calibrationGlassesSamples.add(it) }
        // 眨眼/閉眼期間的樣本會污染訓練資料，丟棄；但逾時計時照走（否則
        // 持續閉眼會讓單點永遠等不到逾時詢問）。
        if (sample.leftOpen < blinkThreshold || sample.rightOpen < blinkThreshold) {
            checkPointTimeout(now)
            return
        }

        if (now < settleUntil) return // 等視線移過去、穩定下來

        val target = points[pointIndex]
        if (calibT0Ms == 0L) calibT0Ms = now
        pointBuffer.add(sample.features)
        pointSampleTimes.add(now - calibT0Ms)
        pointDeviceAngles.add(sample.devicePitch to sample.deviceRoll)
        // 光線與特徵平行記錄：剔除離群值時跟著同一批樣本走，永不脫鉤。
        pointLightLogs.add(GazeFeatureExtractor.lightLogLux(sample.ambientLux))
        view.showTarget(target.first, target.second,
            pointBuffer.size / SAMPLES_PER_POINT.toFloat(),
            "${pointIndex + 1} / ${points.size}")

        if (pointBuffer.size >= SAMPLES_PER_POINT) {
            commitPoint(target)
            if (pointIndex + 1 < points.size) {
                startPoint(pointIndex + 1)
            } else {
                // 一輪 9 點採完：不直接結束，進入輪次選擇——注視選擇繼續下一輪
                // （換個光線）或完成。多光源校正的核心步驟；不因光線變化暫停或警告。
                enterPassChoice()
            }
        }
    }

    /**
     * D1-10：單點逾時 3 次後主動詢問是否退出（不動 POINT_TIMEOUT_MS 單次逾時值）。
     * 計數器每點獨立：commit 成功即歸零（見 commitPoint）；詢問時選「繼續重採」
     * 亦歸零。回傳 true 表示本次逾時已處理（重採或已進入詢問）。
     */
    private fun checkPointTimeout(now: Long): Boolean {
        if (pointStartedAt == 0L || now - pointStartedAt <= POINT_TIMEOUT_MS) return false
        pointTimeoutCount++
        if (pointTimeoutCount >= POINT_TIMEOUTS_BEFORE_ASK) {
            enterAsk()
            return true
        }
        Toast.makeText(this, "這一格等太久，重採一次", Toast.LENGTH_SHORT).show()
        startPoint(pointIndex)
        return true
    }

    /** D1-10：逾時詢問（注視目標「繼續重採」「退出校正」，同報告頁的選擇機制）。 */
    private fun enterAsk() {
        phase = Phase.ASK
        choiceModel = CalibrationStore.loadModel(this, profileId)
        view.setFaceLost(false)
        view.showMessage(
            "這一格等太久",
            listOf(
                "已經重採 $POINT_TIMEOUTS_BEFORE_ASK 次，還是採不滿這一格。",
                "要繼續重採這一格，還是退出校正？",
                gazeHintLine(),
            ),
        )
        showChoices(
            listOf(
                Choice("continue", "繼續重採", primary = true) {
                    pointTimeoutCount = 0
                    phase = Phase.SAMPLING
                    hideChoices()
                    view.clearScreen()
                    startPoint(pointIndex)
                },
                Choice("exit", "退出校正", primary = false) {
                    exitCalibration()
                },
            ),
        )
    }

    /**
     * 多光源校正的輪次選擇（一輪 9 點採完後）：注視選擇「換個光線再採一輪」、
     * 「直接完成校正」或「退出校正」。繼續時下一輪重新採同一組 9 點；
     * 建議但不強制：換個光線（開燈／關燈／換位置）再採，讓模型跨過光線範圍。
     * 本頁不因光線變化暫停、警告或阻擋——光線是連續模型輸入，不是守衛條件。
     * 至少一輪即可儲存；單輪校正與舊流程等價（模型仍含光特徵，跨度退化為
     * 單一值時執行期光特徵恆為該值，見 GazeModel 的光跨度說明）。
     */
    private fun enterPassChoice() {
        phase = Phase.PASS_CHOICE
        choiceModel = CalibrationStore.loadModel(this, profileId)
        view.setFaceLost(false)
        val passesDone = passIndex + 1
        view.showMessage(
            "第 $passesDone 輪採完了",
            listOf(
                "這一輪的光線範圍：${lightSpanText(allLightLogs)}。",
                "要換個光線再採一輪（讓模型看過更多光線），還是直接完成？",
                "換光線的方式：開燈、關燈、走到窗邊或較暗處都可以。",
                gazeHintLine(),
            ),
        )
        showChoices(
            listOf(
                Choice("next", "換個光線再採一輪", primary = true) {
                    passIndex++
                    phase = Phase.SAMPLING
                    hideChoices()
                    view.clearScreen()
                    startPoint(0)
                },
                Choice("finish", "直接完成校正", primary = false) {
                    finishCalibration()
                },
                Choice("exit", "退出校正", primary = false) {
                    exitCalibration()
                },
            ),
        )
    }

    /** 由平行 lux 記錄算出的光線範圍文字（lux 整數顯示；無感測器資料時明說）。 */
    private fun lightSpanText(logs: List<Double>): String {
        val finite = logs.filter { it.isFinite() }
        if (finite.isEmpty()) return "未量到光線（無光感測器資料）"
        val minLux = Math.pow(10.0, finite.min()).toLong()
        val maxLux = Math.pow(10.0, finite.max()).toLong()
        return if (minLux == maxLux) "約 $minLux lux" else "$minLux–$maxLux lux"
    }

    /**
     * D1-11：ERROR 態報告頁——顯示真實失敗原因＋「重試」「退出」兩個注視目標，
     * 不 finish() 丟棄。重試動作由呼叫端傳入（交接重試或重新存檔）。
     * 沒有可用模型時注視選擇無法判讀，按鈕仍可手指點按（見 gazeHintLine）。
     */
    private fun enterError(message: String, retryLabel: String, retry: () -> Unit) {
        phase = Phase.ERROR
        errorRetry = retry
        choiceModel = CalibrationStore.loadModel(this, profileId)
        view.setFaceLost(false)
        view.showMessage(
            "校正無法繼續",
            listOf(message, "可以重試一次，或退出校正。", gazeHintLine()),
        )
        showChoices(
            listOf(
                Choice("retry", retryLabel, primary = true) {
                    hideChoices()
                    view.clearScreen()
                    errorRetry?.invoke()
                },
                Choice("exit", "退出", primary = false) {
                    exitCalibration()
                },
            ),
        )
    }

    /** 退出語義（同取消）：不刪舊模型、不清條件歷史；服務接回由 onDestroy 既有的 resumeAfterCalibration() 完成。 */
    private fun exitCalibration() {
        Toast.makeText(this, "已退出校正，舊模型保留", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 沒有可用模型時，注視選擇無法判讀——明說，不假裝可分辨。 */
    private fun gazeHintLine(): String =
        if (choiceModel == null) {
            "尚無可用的舊校正模型，注視選擇無法判讀；可用手指點按鈕，或按系統返回離開。"
        } else {
            "注視按鈕直到轉滿一圈即可選擇，也可用手指點按。"
        }

    /**
     * 選擇階段的注視判定：複用 TrainingActivity 的按鈕注視停留規則
     * （模型預測＋濾波＋按鈕矩形命中＋dwellTimeMs＋武裝規則＋臉遺失／閉眼重置）。
     * 臉遺失或閉眼時重置計時（不推進亦不保留進度）；游標離開全部按鈕後重新武裝。
     */
    private fun onChoiceSample(sample: GazeSample?) {
        val model = choiceModel
        if (model == null || currentChoices.isEmpty()) {
            resetChoiceDwell()
            return
        }
        if (sample == null) {
            resetChoiceDwell()
            return
        }
        if (sample.leftOpen < blinkThreshold || sample.rightOpen < blinkThreshold) {
            resetChoiceDwell()
            return
        }
        val cursor = choiceCursor(model, sample) ?: run {
            resetChoiceDwell()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val hovered = buttonAt(cursor) ?: run {
            resetChoiceDwell()
            choiceDwellArmed = true
            return
        }
        if (!choiceDwellArmed) {
            view.clearChoiceProgress()
            return
        }
        if (choiceDwellButton !== hovered) {
            choiceDwellButton = hovered
            choiceDwellStart = now
            view.clearChoiceProgress()
            return
        }
        val progress = min((now - choiceDwellStart) / settings.dwellTimeMs.toFloat(), 1f)
        val rect = buttonRectInView(hovered)
        if (rect != null) {
            view.setChoiceProgress(rect.centerX(), rect.centerY(), progress)
        }
        if (progress < 1f) return
        // 觸發：走與手指點擊同一條路徑（performClick），與 TrainingActivity 相同。
        resetChoiceDwell()
        choiceDwellArmed = false
        hovered.performClick()
    }

    /** 與 TrainingActivity.updateCursor 相同的預測＋濾波；預測非有限時回 null（不判讀）。 */
    private fun choiceCursor(model: GazeModel, sample: GazeSample): Pair<Float, Float>? {
        val w = view.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = view.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // 連續光輸入：與服務主路徑相同，每幀的當前 lux 餵給模型。
        val (rawX, rawY) = model.predict(
            sample.features, w, h,
            sample.devicePitch, sample.deviceRoll,
            settings.gyroCompensation, settings.distanceCompensation,
            logLux = GazeFeatureExtractor.lightLogLux(sample.ambientLux),
        )
        if (!rawX.isFinite() || !rawY.isFinite()) return null
        val t = sample.timestampMs / 1000.0
        val x = filterX.filter(rawX, t).toFloat().coerceIn(0f, w - 1f)
        val y = filterY.filter(rawY, t).toFloat().coerceIn(0f, h - 1f)
        return x to y
    }

    /** 游標點落在某顆按鈕上時回傳該按鈕，否則回傳 null（並重新待命）。 */
    private fun buttonAt(pos: Pair<Float, Float>): MaterialButton? {
        return currentButtons.firstOrNull { btn ->
            buttonRectInView(btn)?.contains(pos.first, pos.second) == true
        }
    }

    /** 按鈕矩形換算到 CalibrationView 座標系；按鈕尚未量測時回傳 null（與 TrainingActivity 相同做法）。 */
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

    /** 顯示選擇按鈕列（真實按鈕）：進入選擇階段時重置濾波器與停留狀態。 */
    private fun showChoices(choices: List<Choice>) {
        currentChoices = choices
        choiceBar.removeAllViews()
        val density = resources.displayMetrics.density
        val buttons = choices.map { choice ->
            MaterialButton(this).apply {
                text = choice.label
                contentDescription = choice.label
                cornerRadius = (12 * density).toInt()
                if (choice.primary) {
                    backgroundTintList = ColorStateList.valueOf(Color.rgb(82, 111, 92))
                    setTextColor(Color.WHITE)
                } else {
                    strokeWidth = (1 * density).toInt()
                    strokeColor = ColorStateList.valueOf(Color.rgb(238, 237, 232))
                    backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                    setTextColor(Color.rgb(32, 37, 34))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (52 * density).toInt(),
                ).apply {
                    val m = (4 * density).toInt()
                    setMargins(0, m, 0, m)
                }
                setOnClickListener {
                    if (!isFinishing) choice.onSelect()
                }
            }
        }
        buttons.forEach { choiceBar.addView(it) }
        currentButtons = buttons
        choiceBar.visibility = View.VISIBLE
        filterX.reset()
        filterY.reset()
        resetChoiceDwell()
    }

    private fun hideChoices() {
        choiceBar.visibility = View.GONE
        choiceBar.removeAllViews()
        currentChoices = emptyList()
        currentButtons = emptyList()
        resetChoiceDwell()
    }

    private fun resetChoiceDwell() {
        choiceDwellButton = null
        choiceDwellStart = 0L
        view.clearChoiceProgress()
    }

    /**
     * 剔除離群值後併入訓練集：以虹膜特徵（前 4 維）的樣本中心為基準，
     * 只保留距離最近的 KEEP_RATIO 比例——視線飄移、殘留眨眼幀都會被丟掉。
     */
    private fun commitPoint(target: Pair<Float, Float>) {
        val centroid = DoubleArray(4)
        for (f in pointBuffer) for (i in 0 until 4) centroid[i] += f[i] / pointBuffer.size

        val keep = (pointBuffer.size * KEEP_RATIO).toInt().coerceAtLeast(1)
        // 依同一排序取同一批樣本：特徵、裝置角度、光線、輪次必須成對保留，否則基準與光跨度會錯位
        val order = pointBuffer.indices
            .sortedBy { i ->
                (0 until 4).sumOf { j ->
                    (pointBuffer[i][j] - centroid[j]) * (pointBuffer[i][j] - centroid[j])
                }
            }
        val keptSet = order.take(keep).toSet()
        order.take(keep)
            .forEach { i ->
                allFeatures.add(pointBuffer[i])
                allDeviceAngles.add(pointDeviceAngles[i])
                allLightLogs.add(pointLightLogs[i])
                allPassIds.add(passIndex)
                allTargets.add(target)
            }
        // 逐筆紀錄：全部樣本都記（含被剔除的，kept 標示），供分析跨輪不一致
        // 與手機晃動——訓練集只留排序後的前 keep 筆，紀錄必須在剔除前取全量。
        for (i in pointBuffer.indices) {
            calibRows.add(
                CalibLog.Row(
                    pass = passIndex,
                    targetIndex = pointIndex,
                    sampleIndex = i,
                    tMs = pointSampleTimes[i],
                    targetX = target.first,
                    targetY = target.second,
                    kept = i in keptSet,
                    luxLog = pointLightLogs[i],
                    devicePitch = pointDeviceAngles[i].first,
                    deviceRoll = pointDeviceAngles[i].second,
                    features = pointBuffer[i],
                ),
            )
        }
        committedPoints++
        // 成功採樣即歸零：本點已完成，逾時計數不再累積。
        pointTimeoutCount = 0
    }

    private fun finishCalibration() {
        try {
            finishCalibrationInternal()
        } catch (e: Exception) {
            // 訓練或存檔失敗也不閃退：留紀錄、顯示真實原因（ERROR 態＋重試），
            // 不以 finish() 帶過；已採的訓練資料仍在記憶體中，重試可直接重跑存檔。
            CrashReporter.recordCaught(this, "finishCalibration", e)
            enterError(
                message = "校正儲存失敗：${e.message}",
                retryLabel = "重試儲存",
                retry = { finishCalibration() },
            )
        }
    }

    private fun finishCalibrationInternal() {
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
            lightLogLux = allLightLogs,
        )

        // 訓練集誤差偏樂觀，只列出來參考；精度等級以留一法交叉驗證為準。
        // 多光源校正另算留一輪交叉驗證：等級取兩種留一法中較差者——
        // 光線沒跨過範圍時，留一輪誤差會誠實地比留一點誤差差。
        val report = GazeEvaluator.evaluate(model, allFeatures, allTargets, screenW, screenH)
        val cvErr = GazeEvaluator.leaveOnePointOut(allFeatures, allTargets, screenW, screenH)
        val passErr = GazeEvaluator.leaveOnePassOut(
            allFeatures, allTargets, allPassIds, screenW, screenH,
            lightLogLux = allLightLogs,
        )
        val avgErr = report.inSampleMeanErrorPx.toInt()
        val gradeErr = listOf(cvErr, passErr).filter { it.isFinite() }
            .maxOrNull()?.toInt() ?: avgErr
        // 等級門檻 60／120／200 px 與 GazeEvaluator 計算皆不動（D-12 未決）；
        // 門檻為經驗值，報告頁會保留此性質說明，不得包裝為精度保證。
        val rating = when {
            gradeErr < 60 -> "極佳 🌟"
            gradeErr < 120 -> "優良 ✨"
            gradeErr < 200 -> "普通 👍"
            else -> "偏差 ⚠️ (建議放穩手機重新校正)"
        }
        val worst = report.worstPoint
        val cvText = if (cvErr.isNaN()) "—" else "${cvErr.toInt()} px"
        val passText = if (passErr.isNaN()) "—" else "${passErr.toInt()} px"

        CalibrationStore.saveModel(this, profileId, model, cvErr.takeIf { !it.isNaN() })

        // 完整重校：該情境的條件歷史作廢（舊偏移配新模型沒有意義），
        // 並以本次校正時的條件為新基準，避免服務一恢復就跳提醒。
        // 順序不變：先存檔成功，才清歷史、寫基準；存檔失敗時歷史原樣保留。
        val calibrationIod = allFeatures
            .mapNotNull { it.getOrNull(6)?.takeIf { v -> v.isFinite() && v > 0.0 } }
            .average().takeIf { allFeatures.isNotEmpty() } ?: Double.NaN
        val calibrationGlasses = calibrationGlassesSamples
            .average().takeIf { calibrationGlassesSamples.isNotEmpty() } ?: Double.NaN
        ConditionHistory.clearHistory(this, profileId)
        ConditionHistory.saveReference(
            this, profileId,
            ConditionHistory.Condition(
                // v3 模型契約：光線是連續模型輸入，不再寫亮度基準分桶。
                // luxBucket 寫 -1（未知），使光線永不觸發提醒、永不進入內插距離；
                // 歷史紀錄只代表非光線殘差（姿勢／距離／反光），不與模型重複計算光線。
                luxBucket = -1,
                pitch = deviceBasePitch ?: Double.NaN,
                roll = deviceBaseRoll ?: Double.NaN,
                iod = calibrationIod,
                glassesHint = calibrationGlasses,
            ),
        )
        val calibratedAtMs = CalibrationStore.getMeta(this, profileId)?.calibratedAt ?: 0L
        uploadCalibLog(
            screenW = screenW,
            screenH = screenH,
            model = model,
            inSampleErrorPx = report.inSampleMeanErrorPx,
            looErrorPx = cvErr,
            leavePassOutErrorPx = passErr,
            worstPointIndex = worst.pointIndex,
            worstErrorPx = worst.meanErrorPx,
            perPoint = report.perPoint,
            calibratedAtMs = calibratedAtMs,
        )
        showReport(
            ReportData(
                model = model,
                ratingLabel = rating,
                isPoorGrade = gradeErr >= 200,
                inSampleErrorPx = avgErr,
                looErrorPx = cvErr,
                looText = cvText,
                worstPointIndex = worst.pointIndex,
                worstErrorPx = worst.meanErrorPx.toInt(),
                perPoint = report.perPoint,
                calibratedAtMs = calibratedAtMs,
                lightSpanText = lightSpanText(allLightLogs),
                leavePassOutText = passText,
                passCount = passIndex + 1,
            ),
        )
    }

    /**
     * 上傳校正逐筆紀錄（暫時性診斷，調校完成後整段移除）。只在存檔成功後呼叫一次：
     * 診斷紀錄與遠端監控兩開關皆開才傳（與游標紀錄同一閘門語意），Play 版再由
     * [GazeLog] 內的 flavor 守衛擋下。失敗只走 Logcat（見 [GazeLog]），不打擾使用者。
     * 重試儲存時 [calibUploaded] 擋重複上傳；重新校正時由 [recalibrate] 重置。
     */
    private fun uploadCalibLog(
        screenW: Int,
        screenH: Int,
        model: GazeModel,
        inSampleErrorPx: Double,
        looErrorPx: Double,
        leavePassOutErrorPx: Double,
        worstPointIndex: Int,
        worstErrorPx: Double,
        perPoint: List<GazeEvaluator.PointError>,
        calibratedAtMs: Long,
    ) {
        if (calibUploaded || calibRows.isEmpty()) return
        if (!settings.diagnosticLogging || !settings.remoteMonitor) return
        calibUploaded = true
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val id = "calib-$profileId-$ts-${calibUploadCounter++}"
        val finiteLogs = allLightLogs.filter { it.isFinite() }
        val lightMinLux = finiteLogs.minOrNull()?.let { 10.0.pow(it) } ?: Double.NaN
        val lightMaxLux = finiteLogs.maxOrNull()?.let { 10.0.pow(it) } ?: Double.NaN
        val perPointErrors = List(points.size) { idx ->
            perPoint.firstOrNull { it.pointIndex == idx }?.meanErrorPx ?: Double.NaN
        }
        val summary = CalibLog.buildSummaryJson(
            profileId = profileId,
            passCount = passIndex + 1,
            screenW = screenW,
            screenH = screenH,
            featureDim = calibRows.first().features.size,
            inSampleErrorPx = inSampleErrorPx,
            looErrorPx = looErrorPx,
            leavePassOutErrorPx = leavePassOutErrorPx,
            worstPointIndex = worstPointIndex,
            worstErrorPx = worstErrorPx,
            perPointErrors = perPointErrors,
            lightMinLux = lightMinLux,
            lightMaxLux = lightMaxLux,
            lightMinLog = model.lightMinLog,
            lightMaxLog = model.lightMaxLog,
            calibratedAtMs = calibratedAtMs,
            rowCount = calibRows.size,
            keptRowCount = calibRows.count { it.kept },
        )
        GazeLog.uploadCalibration(id, CalibLog.buildCsv(calibRows), calibRows.size)
        GazeLog.uploadCalibration("$id-sum", summary, 0)
    }

    /** D1-7：9 點完成後不立即 finish()，改顯示常駐報告頁；按「完成」才 finish()。 */
    private fun showReport(data: ReportData) {
        report = data
        reportExpanded = false
        phase = Phase.REPORT
        choiceModel = data.model
        view.setCompleted(points.size)
        view.setFaceLost(false)
        view.showReport(reportTitle(data), reportLines(data), perPointLines(data), expanded = false)
        showReportChoices()
    }

    private fun reportTitle(data: ReportData): String =
        "「${CalibrationStore.profileName(profileId)}」校正完成"

    private fun reportLines(data: ReportData): List<String> {
        val calibratedAtText = if (data.calibratedAtMs > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(java.util.Date(data.calibratedAtMs))
        } else {
            "未記錄"
        }
        val lines = mutableListOf(
            "精度等級：${data.ratingLabel}",
            "訓練集平均誤差：${data.inSampleErrorPx} px（僅供參考，偏樂觀）",
            "留一法交叉驗證誤差：${data.looText}",
            "留一輪交叉驗證誤差：${data.leavePassOutText}（${data.passCount} 輪；少於 2 輪時為 —）",
            "校正光線範圍：${data.lightSpanText}",
            "最差：第 ${data.worstPointIndex + 1} 點（${data.worstErrorPx} px）",
            "校正時間：$calibratedAtText",
            "等級門檻（60／120／200 px）為經驗值，不是精度保證。",
        )
        if (data.isPoorGrade) {
            lines.add("這次結果較差：建議放穩手機、重新校正一次。舊模型仍保留，直到新模型存檔成功才會取代。")
        }
        return lines
    }

    private fun perPointLines(data: ReportData): List<String> =
        data.perPoint.map { point ->
            "第 ${point.pointIndex + 1} 點：${point.meanErrorPx.toInt()} px（${point.sampleCount} 筆）"
        }

    /** D1-8：等級較差（最差一級「偏差」）時主推「重新校正」；不合格不自動重跑、不刪舊模型。 */
    private fun showReportChoices() {
        val data = report ?: return
        val expandLabel = if (reportExpanded) "收起逐點誤差" else "逐點誤差"
        showChoices(
            listOf(
                Choice("recalibrate", "重新校正", primary = data.isPoorGrade) {
                    recalibrate()
                },
                Choice("training", "去練習", primary = false) {
                    goToTraining()
                },
                Choice("done", "完成", primary = !data.isPoorGrade) {
                    finish()
                },
                Choice("expand", expandLabel, primary = false) {
                    reportExpanded = !reportExpanded
                    view.showReport(
                        reportTitle(data),
                        reportLines(data),
                        perPointLines(data),
                        expanded = reportExpanded,
                    )
                    showReportChoices()
                },
            ),
        )
    }

    /**
     * D1-7「重新校正」：重跑 9 點，且不清條件歷史、不覆蓋舊模型，直到新模型
     * 存檔成功（finishCalibrationInternal 成功時才會清歷史、寫基準、存檔）。
     * 本函式不呼叫任何存檔／清除，舊模型與條件歷史原樣保留。
     */
    private fun recalibrate() {
        allFeatures.clear()
        allTargets.clear()
        allDeviceAngles.clear()
        allLightLogs.clear()
        allPassIds.clear()
        passIndex = 0
        pointBuffer.clear()
        pointDeviceAngles.clear()
        pointLightLogs.clear()
        pointSampleTimes.clear()
        calibRows.clear()
        calibT0Ms = 0L
        calibUploaded = false
        calibrationGlassesSamples.clear()
        committedPoints = 0
        pointTimeoutCount = 0
        report = null
        reportExpanded = false
        phase = Phase.SAMPLING
        hideChoices()
        view.clearScreen()
        filterX.reset()
        filterY.reset()
        startPoint(0)
    }

    /** D1-7「去練習」：開啟訓練頁後結束本頁（回來時由引導／主畫面重查存檔為準）。 */
    private fun goToTraining() {
        startActivity(Intent(this, TrainingActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelHandoverTimeout()
        if (::engine.isInitialized) {
            engine.stop()
            engineStarted = false
        }
        // 通知服務重新載入校正結果並接回相機
        GazeAccessibilityService.resumeAfterCalibration()
    }

    /** 畫校正目標點：帶脈衝動畫的外圈進度環 + 呼吸內圈實心點。設計系統淺色系。 */
    private class CalibrationView(context: Context) : View(context) {
        private var tx = -1f
        private var ty = -1f
        private var progress = 0f
        private var label = ""
        private var faceLost = false

        /** 全部目標點位置（用來畫已完成的點）與已完成點數：只影響繪製，不參與採樣。 */
        private var allPoints: List<Pair<Float, Float>> = emptyList()
        private var completedCount = 0

        /** 交接等待期（D1-13）：顯示「相機啟動中」文字，採樣目標繪製本身不變。 */
        private var waitingShown = false

        /** 非採樣畫面（報告／訊息）：標題＋內文行＋逐點誤差（可展開）＋選擇進度環。 */
        private enum class Screen { SAMPLING, REPORT, MESSAGE }
        private var screen = Screen.SAMPLING
        private var screenTitle = ""
        private var screenLines: List<String> = emptyList()
        private var perPointLines: List<String> = emptyList()
        private var perPointExpanded = false

        /** 選擇進度環（畫在當前注視的按鈕中心）：複用採樣進度環的畫筆。 */
        private var choiceProgress = 0f
        private var choiceProgressX = -1f
        private var choiceProgressY = -1f

        // 尺寸一律以 dp/sp 換算（density / scaledDensity），不寫死 px。
        private val density = context.resources.displayMetrics.density
        private val scaledDensity = context.resources.displayMetrics.scaledDensity
        private fun dp(v: Float) = v * density

        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(82, 111, 92) }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 231, 238, 232)
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 111, 92); style = Paint.Style.STROKE; strokeWidth = dp(3f)
        }
        private val bgRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 237, 232); style = Paint.Style.STROKE; strokeWidth = dp(3f)
        }
        private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(82, 111, 92) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 37, 34); textSize = 20f * scaledDensity; textAlign = Paint.Align.CENTER
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(116, 122, 117); textSize = 15f * scaledDensity; textAlign = Paint.Align.CENTER
        }
        private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(193, 138, 72)
            textSize = 17f * scaledDensity
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(32, 37, 34)
            textSize = 20f * scaledDensity
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        fun setPoints(pts: List<Pair<Float, Float>>) {
            allPoints = pts
            invalidate()
        }

        fun setCompleted(count: Int) {
            completedCount = count
            invalidate()
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

        fun setWaiting(waiting: Boolean) {
            if (waitingShown == waiting) return
            waitingShown = waiting
            invalidate()
        }

        fun showReport(title: String, lines: List<String>, perPoint: List<String>, expanded: Boolean) {
            screen = Screen.REPORT
            screenTitle = title
            screenLines = lines
            perPointLines = perPoint
            perPointExpanded = expanded
            invalidate()
        }

        fun showMessage(title: String, lines: List<String>) {
            screen = Screen.MESSAGE
            screenTitle = title
            screenLines = lines
            perPointLines = emptyList()
            perPointExpanded = false
            invalidate()
        }

        fun clearScreen() {
            screen = Screen.SAMPLING
            screenTitle = ""
            screenLines = emptyList()
            perPointLines = emptyList()
            perPointExpanded = false
            waitingShown = false
            clearChoiceProgress()
            invalidate()
        }

        fun setChoiceProgress(x: Float, y: Float, p: Float) {
            choiceProgressX = x; choiceProgressY = y; choiceProgress = p
            invalidate()
        }

        fun clearChoiceProgress() {
            if (choiceProgress == 0f && choiceProgressX < 0f) return
            choiceProgress = 0f
            choiceProgressX = -1f
            choiceProgressY = -1f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(247, 246, 242)) // Warm Ivory 背景
            // 已完成的點：填實小圓點，讓進度留在畫面上
            for (i in 0 until completedCount) {
                val p = allPoints.getOrNull(i) ?: continue
                canvas.drawCircle(p.first, p.second, dp(8f), donePaint)
            }
            if (screen != Screen.SAMPLING) {
                drawScreen(canvas)
                return
            }
            if (tx < 0) return

            // 畫提示文字與進度文字
            canvas.drawText(label, width / 2f, height / 2f + dp(40f), textPaint)
            canvas.drawText("請保持頭部不動，用眼睛注視圓點", width / 2f, height / 2f + dp(64f), hintPaint)
            if (faceLost) {
                canvas.drawText("找不到臉，請把臉移回畫面中央", width / 2f, height / 2f + dp(88f), warnPaint)
            }
            // D1-13：交接等待期間顯示「相機啟動中」；臉遺失提示沿用現況（兩者不會同時出現）。
            if (waitingShown) {
                canvas.drawText("相機啟動中", width / 2f, height / 2f + dp(88f), textPaint)
            }
            // D1-9 預留位置（待 D-4 決策的未完成功能）：此處刻意不繪製任何取消
            // 目標、不註冊任何取消事件，九點目標的座標與可用空間不受影響。
            // 預留標示見外層 companion 的 CANCEL_TARGET_RESERVED 常數與註解。

            val time = System.currentTimeMillis()

            // 呼吸脈衝動畫：內圈點大小會隨時間縮放
            val pulseScale = 1.0f + 0.18f * kotlin.math.sin(time / 160.0).toFloat()
            val baseRadius = dp(14f)

            // 畫外圈淡脈衝光暈
            val pulseRadius = baseRadius * (pulseScale + 0.3f)
            canvas.drawCircle(tx, ty, pulseRadius, pulsePaint)

            // 畫實心目標點
            canvas.drawCircle(tx, ty, baseRadius * pulseScale, dotPaint)

            // 畫進度軌道（淺色背景環）
            // 進度環直徑 60dp（≥56dp 有效注視區）
            val r = dp(30f)
            canvas.drawCircle(tx, ty, r, bgRingPaint)

            // 進度環：收滿樣本時剛好畫滿一圈
            canvas.drawArc(
                tx - r, ty - r, tx + r, ty + r,
                -90f, 360f * min(progress, 1f), false, ringPaint,
            )

            // 觸發下一幀動畫重繪
            postInvalidateOnAnimation()
        }

        /** 報告／訊息畫面：標題＋內文行（逐點誤差展開時接在內文之後）。 */
        private fun drawScreen(canvas: Canvas) {
            var y = height * 0.14f
            canvas.drawText(screenTitle, width / 2f, y, titlePaint)
            y += dp(36f)
            for (line in screenLines) {
                canvas.drawText(line, width / 2f, y, hintPaint)
                y += dp(26f)
            }
            if (perPointExpanded) {
                y += dp(8f)
                for (line in perPointLines) {
                    canvas.drawText(line, width / 2f, y, hintPaint)
                    y += dp(22f)
                }
            }
            // 選擇進度環：畫在當前注視的按鈕中心（複用採樣進度環畫筆）。
            if (choiceProgress > 0f && choiceProgressX >= 0f && choiceProgressY >= 0f) {
                val r = dp(30f)
                canvas.drawCircle(choiceProgressX, choiceProgressY, r, bgRingPaint)
                canvas.drawArc(
                    choiceProgressX - r, choiceProgressY - r,
                    choiceProgressX + r, choiceProgressY + r,
                    -90f, 360f * min(choiceProgress, 1f), false, ringPaint,
                )
            }
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

        /** D1-10：單點逾時達此次數後主動詢問是否退出（PLAN.md D1-10 定稿數字，非新編）。 */
        private const val POINT_TIMEOUTS_BEFORE_ASK = 3

        /** D1-12 檢查點的 Bundle key：只存整數進度，不存特徵原始幀。 */
        private const val KEY_POINT_INDEX = "p3_point_index"
        private const val KEY_COMMITTED_POINTS = "p3_committed_points"

        // ---- D1-9 預留（待 D-4 決策的未完成功能，絕對不得視為已完成） ----
        // 取消目標的形式、注視秒數與位置皆待 D-4 定稿。本步只保留介面配置
        // 位置的標示：以下常數僅為文字／概念標示，程式中沒有任何繪製、命中
        // 判定、事件註冊或取消／退出行為引用它們；九點校正目標、座標計算與
        // 畫面可用空間完全不受影響。D-4 定稿前不得依此實作任何取消功能。
        /** 預留狀態標示：UNIMPLEMENTED＝待 D-4 決策的未完成功能。 */
        const val CANCEL_TARGET_RESERVED = "UNIMPLEMENTED: 待 D-4 決策"
        /** 預留位置的概念標示（PLAN D1-9 預留方向為「校正頁角落」；僅標示，非座標計算輸入）。 */
        const val CANCEL_TARGET_RESERVED_CORNER = "corner (待 D-4 決定是哪一個角落)"
        /** 預留位置的比例標示（僅標示概念位置，從未用於任何繪製或命中計算）。 */
        const val CANCEL_TARGET_RESERVED_X_FRACTION = 0.92f
        /** 預留位置的比例標示（僅標示概念位置，從未用於任何繪製或命中計算）。 */
        const val CANCEL_TARGET_RESERVED_Y_FRACTION = 0.08f
    }
}
