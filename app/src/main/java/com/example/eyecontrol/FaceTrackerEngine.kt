/* SPDX-License-Identifier: AGPL-3.0-or-later */


package com.example.eyecontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前鏡頭 → MediaPipe Face Landmarker → GazeSample 的串流引擎。
 * CalibrationActivity 與 GazeAccessibilityService 共用；呼叫端只需提供
 * LifecycleOwner（Activity 本身，或 Service 自建的 LifecycleRegistry）。
 *
 * onSample 會在背景執行緒被呼叫；偵測不到臉時傳 null。
 */
class FaceTrackerEngine(
    private val context: Context,
    private val onSample: (GazeSample?) -> Unit,
) {
    private var landmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var cachedBmp: Bitmap? = null
    private val cachedRotatedBmps = arrayOfNulls<Bitmap>(3)
    private var rotatedBmpIndex = 0

    /** 綁定後的相機（曝光補償用）：bindToLifecycle 的回傳值，之前被丟棄。 */
    @Volatile
    private var camera: Camera? = null

    /**
     * 幀槽表：detectAsync 是非同步的，結果回呼到達時當下那幀的 Bitmap 早已不在
     * analyze() 的作用域。analyze() 每幀把（時間戳、Bitmap、世代計數）寫進
     * 對應的三緩衝槽；回呼端只在槽的時間戳吻合、且量測前後世代計數不變時，
     * 才用該 Bitmap 做眼部影像量測——否則度量為未知（NaN），絕不拿舊幀／半寫入
     * 的影像降級或精修。世代計數在 Bitmap 繪製完成後才遞增，繪製中的讀取
     * 一定會被事後驗證抓到而作廢。
     */
    private class FrameSlot {
        @Volatile var timestampMs: Long = -1L
        @Volatile var bitmap: Bitmap? = null
        @Volatile var generation: Int = 0
    }

    private val frameSlots = Array(3) { FrameSlot() }

    /**
     * 驗證影格（內部調校用，見 VerifyFrames）：眼區裁切的留存與上傳。
     * 開關由服務端經 [setVerifyCapture] 傳入；校正頁的引擎實例不設開關，
     * 故校正流程不產生影格。以下狀態只在結果回呼執行緒觸及（開關旗標在
     * VerifyFrames 內部，以 volatile 保護）。
     */
    private val verifyFrames = VerifyFrames(context.applicationContext)

    private data class PendingCapture(
        val kind: String,
        val requestedMs: Long,
        val dwellProgress: Float?,
    )

    /** 服務端事件觸發的影格請求（停留點擊／邊緣手勢）：下一幀有臉時取裁切。 */
    private val pendingCaptures = ConcurrentLinkedQueue<PendingCapture>()

    @Volatile
    private var lastPeriodicCaptureMs: Long = 0L

    private var wasFacePresent: Boolean = false
    private var wasDegraded: Boolean = false

    @Volatile
    private var lastDegradedCaptureMs: Long = 0L

    /** 虹膜影像精修開關（實驗功能）：start() 時讀一次設定，運作中改設定下次啟動生效。 */
    private var irisRefinementEnabled = false

    // 低亮度自動曝光補償狀態（只在主執行緒／回呼執行緒經由 camera 欄位存取，欄位皆 volatile 保護）
    @Volatile
    private var baseExposureIndex = 0

    @Volatile
    private var exposureBoosted = false

    @Volatile
    private var exposureAtMax = false

    private var lowLuxSinceMs = 0L
    private var lastExposureEvalMs = 0L

    /** 裝置姿勢追蹤：每幀樣本附上當下裝置角度，供預測時做相對校正姿勢的修正。 */
    private val orientationTracker = DeviceOrientationTracker(context)

    /** 環境光追蹤：每幀樣本附上當下亮度，供條件感知漂移修正判斷亮度變化。 */
    private val lightSensorTracker = LightSensorTracker(context)

    /** 關閉旗標：stop() 之後分析執行緒不得再碰 landmarker（否則對已釋放的原生物件送資料會 SIGSEGV）。 */
    @Volatile
    private var closed = false

    fun start(lifecycleOwner: LifecycleOwner) {
        // 防呆：同一實例重複 start 時先停舊的，否則 landmarker 與執行緒會被覆蓋洩漏；
        // 且 stop() 置起的 closed 旗標必須重置，否則新綁定的分析收不到任何幀。
        if (landmarker != null || analysisExecutor != null) stop()
        closed = false
        irisRefinementEnabled = GazeSettings.load(context).irisRefinement
        orientationTracker.start()
        lightSensorTracker.start()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setResultListener { result: FaceLandmarkerResult, _ ->
                val face = result.faceLandmarks().firstOrNull()
                if (face == null) {
                    if (wasFacePresent) {
                        // 臉遺失：以最後一張好的眼區裁切當遺失前證據（此幀已無臉、無新裁切可用）。
                        wasFacePresent = false
                        wasDegraded = false
                        verifyFrames.emitLastGoodAsFaceLost(SystemClock.elapsedRealtime())
                    }
                    onSample(null)
                } else {
                    val faceRegained = !wasFacePresent
                    wasFacePresent = true
                    // 眼部影像度量：只有幀槽驗證通過才有值，否則全為 NaN（未知，不降級）。
                    val metrics = lookupFrameMetrics(face, result.timestampMs())
                    val refined = metrics?.let {
                        if (it.refinedLeftIris == null || it.refinedRightIris == null) null
                        else RefinedIris(
                            leftX = it.refinedLeftIris.x.toDouble(),
                            leftY = it.refinedLeftIris.y.toDouble(),
                            rightX = it.refinedRightIris.x.toDouble(),
                            rightY = it.refinedRightIris.y.toDouble(),
                        )
                    }
                    // 感測器快照先於特徵擷取：環境光是連續模型輸入，必須在
                    // extract 內寫入光特徵（索引 22–24），不能事後才附加。
                    val orientation = orientationTracker.snapshot()
                    val lux = lightSensorTracker.snapshot()
                    val sample = GazeFeatureExtractor.extract(
                        face, result.timestampMs(), refined,
                        ambientLux = lux ?: Float.NaN,
                    )
                    if (sample == null) {
                        onSample(null)
                    } else {
                        updateExposure(lux)
                        // 一律以 copy 附加感測器與影像度量：原本「兩者皆 null 就原樣回傳」
                        // 的分支與 copy 預設值等價（全為 NaN），合併後度量不會在該分支遺失。
                        val outSample = sample.copy(
                            devicePitch = orientation?.pitch ?: Double.NaN,
                            deviceRoll = orientation?.roll ?: Double.NaN,
                            ambientLux = lux ?: Float.NaN,
                            eyeMeanLum = metrics?.meanLum ?: Double.NaN,
                            eyeContrast = metrics?.contrast ?: Double.NaN,
                            specularRatio = metrics?.specularRatio ?: Double.NaN,
                            rawPitch = orientation?.rawPitch ?: Double.NaN,
                            rawRoll = orientation?.rawRoll ?: Double.NaN,
                            rawYaw = orientation?.rawYaw ?: Double.NaN,
                            rvX = orientation?.rvX ?: Double.NaN,
                            rvY = orientation?.rvY ?: Double.NaN,
                            rvZ = orientation?.rvZ ?: Double.NaN,
                            rvW = orientation?.rvW ?: Double.NaN,
                        )
                        onSample(outSample)
                        // 驗證影格（有臉幀才取裁切）：週期、退化事件、臉部重現、服務端事件請求。
                        maybeCaptureVerifyFrame(face, result.timestampMs(), outSample, faceRegained)
                    }
                }
            }
            .setErrorListener { e -> Log.e(TAG, "FaceLandmarker 錯誤", e) }
            .build()
        try {
            landmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            // 模型建立失敗（檔案損壞、記憶體不足等）：記下原因後往上拋，
            // 由呼叫端決定降級處理（校正頁會提示後退回，不直接閃退）。
            Log.e(TAG, "FaceLandmarker 建立失敗", e)
            throw e
        }

        analysisExecutor = Executors.newSingleThreadExecutor()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            // 1280×720：虹膜在影像中的位移只有幾個像素，解析度太低會讓量化雜訊
            // 被回歸模型放大成全螢幕的游標亂飄
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor!!) { proxy -> analyze(proxy) }

            try {
                provider.unbindAll()
                // 留住 Camera 物件：低亮度自動曝光補償需要 CameraControl，
                // 之前回傳值被丟棄、曝光調不了。
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
                baseExposureIndex = runCatching {
                    camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
                }.getOrDefault(0)
            } catch (e: Exception) {
                Log.e(TAG, "相機綁定失敗", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 驗證影格開關（服務端設定變更時呼叫）：local=本機留存，upload=上傳監控端。
     * 兩者皆由開轉關時結束目前影格時段（清庫存、刪舊目錄）。
     */
    fun setVerifyCapture(local: Boolean, upload: Boolean) {
        val wasActive = verifyFrames.isActive
        verifyFrames.setEnabled(local, upload)
        if (wasActive && !local && !upload) {
            verifyFrames.endSession()
        }
    }

    /**
     * 服務端事件觸發的驗證影格請求（停留點擊／邊緣手勢觸發或被擋）：
     * 只排隊，下一幀有臉時才取裁切；排隊超過 2 秒沒臉就丟棄。
     * 佇列有界，避免無臉期間請求無限堆積。
     */
    fun requestVerifyCapture(kind: String, dwellProgress: Float? = null) {
        if (!verifyFrames.isActive) return
        if (pendingCaptures.size >= MAX_PENDING_CAPTURES) {
            pendingCaptures.poll()
        }
        pendingCaptures.offer(
            PendingCapture(kind, SystemClock.elapsedRealtime(), dwellProgress)
        )
    }

    private fun analyze(proxy: ImageProxy) {
        proxy.use {
            if (closed) return
            val lm = landmarker ?: return
            val (bitmap, slotIndex) = toBitmap(it) ?: return
            // 幀槽登記：時間戳是 LIVE_STREAM 傳給 detectAsync 的同一毫秒值，
            // 回呼端以它找回這幀的 Bitmap；世代計數在繪製完成後遞增。
            val tsMs = it.imageInfo.timestamp / 1_000_000
            val slot = frameSlots[slotIndex]
            slot.timestampMs = tsMs
            slot.bitmap = bitmap
            slot.generation++
            try {
                // LIVE_STREAM 要求單調遞增的時間戳（毫秒）
                lm.detectAsync(BitmapImageBuilder(bitmap).build(), tsMs)
            } catch (e: Exception) {
                // stop() 競態下 landmarker 可能已關閉；丟掉這幀即可
                if (!closed) Log.e(TAG, "detectAsync 失敗", e)
            }
        }
    }

    /**
     * 以結果時間戳找回該幀的 Bitmap、做眼部影像量測。槽的時間戳不符、
     * Bitmap 已回收、或量測前後世代計數改變（Bitmap 已被新幀覆寫），
     * 一律回 null——度量未知，絕不拿錯幀的數字。
     */
    private fun lookupFrameMetrics(
        face: List<NormalizedLandmark>,
        timestampMs: Long,
    ): EyeImageMetrics.EyeMetrics? {
        if (closed) return null
        val slot = frameSlots.firstOrNull { it.timestampMs == timestampMs } ?: return null
        val bitmap = slot.bitmap ?: return null
        if (bitmap.isRecycled) return null
        val generationBefore = slot.generation
        val geometry = GazeFeatureExtractor.eyeGeometry(face) ?: return null
        val metrics = EyeImageMetrics.measure(bitmap, geometry.first, geometry.second, irisRefinementEnabled)
            ?: return null
        if (slot.generation != generationBefore || slot.timestampMs != timestampMs) return null
        return metrics
    }

    /**
     * 驗證影格取樣（結果回呼執行緒）：一幀最多一張。優先序：退化事件（邊緣觸發、
     * 錯過就沒了）＞服務端事件請求＞臉部重現＞週期 1Hz。裁切失敗（幀槽已被
     * 覆寫）就跳過，絕不拿錯幀。
     */
    private fun maybeCaptureVerifyFrame(
        face: List<NormalizedLandmark>,
        timestampMs: Long,
        sample: GazeSample,
        faceRegained: Boolean,
    ) {
        if (!verifyFrames.isActive) return
        val now = SystemClock.elapsedRealtime()
        val degraded = EyeImageMetrics.isDegraded(sample)
        var kind: String? = null
        var dwellProgress: Float? = null
        var requestedMs: Long? = null
        if (degraded && !wasDegraded &&
            now - lastDegradedCaptureMs >= DEGRADED_CAPTURE_MIN_INTERVAL_MS
        ) {
            kind = VerifyFrames.CAPTURE_DEGRADED
            lastDegradedCaptureMs = now
        }
        wasDegraded = degraded
        if (kind == null) {
            // 服務端事件請求：取最舊一筆；逾時 2 秒的丟棄（請求時的人臉狀態已過時）。
            while (true) {
                val pending = pendingCaptures.poll() ?: break
                if (now - pending.requestedMs <= PENDING_CAPTURE_MAX_AGE_MS) {
                    kind = pending.kind
                    dwellProgress = pending.dwellProgress
                    requestedMs = pending.requestedMs
                    break
                }
            }
        }
        if (kind == null && faceRegained) {
            kind = VerifyFrames.CAPTURE_FACE_REGAINED
        }
        if (kind == null && now - lastPeriodicCaptureMs >= PERIODIC_CAPTURE_INTERVAL_MS) {
            kind = VerifyFrames.CAPTURE_PERIODIC
            lastPeriodicCaptureMs = now
        }
        if (kind == null) return
        val crop = captureEyeCrop(face, timestampMs) ?: return
        val f = sample.features
        verifyFrames.submit(
            crop,
            VerifyFrameMeta(
                captureKind = kind,
                captureElapsedMs = now,
                requestedElapsedMs = requestedMs,
                frameTimestampMs = timestampMs,
                lux = sample.ambientLux.takeIf { it.isFinite() && it >= 0f },
                iod = f.getOrNull(6)?.takeIf { it.isFinite() },
                pitch = f.getOrNull(7)?.takeIf { it.isFinite() },
                yaw = f.getOrNull(8)?.takeIf { it.isFinite() },
                deviceRoll = sample.deviceRoll.takeIf { it.isFinite() },
                eyeContrast = sample.eyeContrast.takeIf { it.isFinite() },
                specularRatio = sample.specularRatio.takeIf { it.isFinite() },
                degraded = degraded,
                dwellProgress = dwellProgress,
                csvSessionId = GazeLog.currentSessionId(),
            ),
        )
    }

    /**
     * 以結果時間戳找回該幀的 Bitmap、裁出雙眼區（驗證影格用）。與
     * [lookupFrameMetrics] 相同的世代守衛：槽的時間戳不符、Bitmap 已回收、
     * 或裁切前後世代改變，一律回 null。回傳的 Bitmap 由呼叫端擁有
     * （VerifyFrames 用完 recycle）。
     */
    private fun captureEyeCrop(
        face: List<NormalizedLandmark>,
        timestampMs: Long,
    ): Bitmap? {
        if (closed) return null
        val slot = frameSlots.firstOrNull { it.timestampMs == timestampMs } ?: return null
        val bitmap = slot.bitmap ?: return null
        if (bitmap.isRecycled) return null
        val generationBefore = slot.generation
        val geometry = GazeFeatureExtractor.eyeGeometry(face) ?: return null
        val crop = EyeImageMetrics.cropEyes(bitmap, geometry.first, geometry.second) ?: return null
        if (slot.generation != generationBefore || slot.timestampMs != timestampMs) {
            crop.recycle()
            return null
        }
        return crop
    }

    /**
     * 低亮度自動曝光補償：環境光持續低於門檻約 2 秒，就把曝光補償調高一檔
     * （夾在裝置支援範圍內）；亮度回升後調回基準。裝置不支援曝光補償、
     * 或已在上限時靜默不動（不重試、不報錯）。評估最多每 0.5 秒一次。
     * 只調 CameraX 曝光補償，不動螢幕亮度。
     */
    private fun updateExposure(lux: Float?) {
        val cam = camera ?: return
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastExposureEvalMs < EXPOSURE_EVAL_INTERVAL_MS) return
        lastExposureEvalMs = now
        if (lux == null || !lux.isFinite() || lux < 0f) return
        if (exposureBoosted || exposureAtMax) {
            if (lux > EXPOSURE_RESTORE_LUX) {
                runCatching { cam.cameraControl.setExposureCompensationIndex(baseExposureIndex) }
                exposureBoosted = false
                exposureAtMax = false
                lowLuxSinceMs = 0L
            }
            return
        }
        if (lux < EXPOSURE_LOW_LUX) {
            if (lowLuxSinceMs == 0L) {
                lowLuxSinceMs = now
                return
            }
            if (now - lowLuxSinceMs < EXPOSURE_LOW_SUSTAIN_MS) return
            val state = runCatching { cam.cameraInfo.exposureState }.getOrNull() ?: return
            if (!state.isExposureCompensationSupported) {
                exposureAtMax = true
                return
            }
            val range = state.exposureCompensationRange
            val target = (baseExposureIndex + 1).coerceIn(range.lower, range.upper)
            if (target == baseExposureIndex) {
                exposureAtMax = true
                return
            }
            runCatching { cam.cameraControl.setExposureCompensationIndex(target) }
            exposureBoosted = true
        } else {
            lowLuxSinceMs = 0L
        }
    }

    /**
     * RGBA_8888 ImageProxy → 已擺正方向的 Bitmap（處理 rowStride padding 與旋轉）。
     * 回傳 Bitmap 與它所屬的三緩衝槽編號，供 analyze() 登記幀槽表。
     */
    private fun toBitmap(proxy: ImageProxy): Pair<Bitmap, Int>? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * proxy.width
        val paddedWidth = proxy.width + rowPadding / pixelStride

        // 複用中間的 padded 緩衝 Bitmap，避免每幀重複配置
        var tempBmp = cachedBmp
        if (tempBmp == null || tempBmp.width != paddedWidth || tempBmp.height != proxy.height) {
            tempBmp = Bitmap.createBitmap(paddedWidth, proxy.height, Bitmap.Config.ARGB_8888)
            cachedBmp = tempBmp
        }

        plane.buffer.rewind() // 確保 buffer 指針在開頭
        tempBmp.copyPixelsFromBuffer(plane.buffer)

        val rotation = proxy.imageInfo.rotationDegrees
        val targetWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        val targetHeight = if (rotation == 90 || rotation == 270) proxy.width else proxy.height

        // 採用三緩衝循環機制，防止 detectAsync 異步讀取時與寫入產生衝突
        val rotatedIndex = rotatedBmpIndex
        rotatedBmpIndex = (rotatedBmpIndex + 1) % 3

        var rotatedBmp = cachedRotatedBmps[rotatedIndex]
        if (rotatedBmp == null || rotatedBmp.width != targetWidth || rotatedBmp.height != targetHeight) {
            rotatedBmp?.recycle()
            rotatedBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            cachedRotatedBmps[rotatedIndex] = rotatedBmp
        }

        val canvas = android.graphics.Canvas(rotatedBmp!!)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val m = Matrix()
        // 1. 將 (0,0) 移動到原圖中心
        m.postTranslate(-proxy.width / 2f, -proxy.height / 2f)
        // 2. 進行旋轉
        if (rotation != 0) {
            m.postRotate(rotation.toFloat())
        }
        // 3. 移動到目標圖中心
        m.postTranslate(targetWidth / 2f, targetHeight / 2f)

        // 4. 只裁剪繪製有效範圍，排除 Row Padding
        val srcRect = android.graphics.Rect(0, 0, proxy.width, proxy.height)
        val dstRect = android.graphics.RectF(0f, 0f, proxy.width.toFloat(), proxy.height.toFloat())

        canvas.save()
        canvas.concat(m)
        canvas.drawBitmap(tempBmp, srcRect, dstRect, null)
        canvas.restore()

        return rotatedBmp!! to rotatedIndex
    }

    fun stop() {
        // 順序很重要：先立旗標擋新幀 → 停相機 → 等分析執行緒清空 → 最後才釋放原生物件
        closed = true
        orientationTracker.stop()
        lightSensorTracker.stop()
        unbindOnMainThread()
        cameraProvider = null
        // 先清幀槽與相機參照，再回收 Bitmap：回呼端不得再拿到已回收的 Bitmap。
        frameSlots.forEach {
            it.bitmap = null
            it.timestampMs = -1L
            it.generation++
        }
        // 驗證影格時段結束：排隊中的事件請求一併丟棄，清庫存、刪舊目錄。
        pendingCaptures.clear()
        verifyFrames.endSession()
        camera = null
        exposureBoosted = false
        exposureAtMax = false
        lowLuxSinceMs = 0L
        analysisExecutor?.let {
            it.shutdown()
            runCatching { it.awaitTermination(500, TimeUnit.MILLISECONDS) }
        }
        analysisExecutor = null
        landmarker?.close()
        landmarker = null
        
        // 釋放複用的 Bitmap 緩衝
        cachedBmp?.recycle()
        cachedBmp = null

        // 釋放三緩衝
        for (i in cachedRotatedBmps.indices) {
            cachedRotatedBmps[i]?.recycle()
            cachedRotatedBmps[i] = null
        }
    }

    /**
     * 解綁相機用例。ProcessCameraProvider.unbindAll() 只能在主執行緒呼叫，
     * 但 stop() 也會從背景執行緒被呼叫（服務交接校正時，見
     * GazeAccessibilityService.requestPauseForCalibration）——在主執行緒
     * 之外直接呼叫會拋 IllegalStateException，導致按下校正就閃退。
     * 因此非主執行緒時把 unbind 排到主執行緒並等它完成（最多 2 秒，逾時放行，
     * 避免主執行緒被卡住時背景執行緒無限等待）；主執行緒不會等這支背景執行緒，
     * 故不會死結。
     */
    private fun unbindOnMainThread() {
        val provider = cameraProvider ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { provider.unbindAll() }
            return
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching { provider.unbindAll() }
            latch.countDown()
        }
        // await 可能被中斷：中斷時也要繼續往下釋放執行緒與模型，否則交接
        // 永遠等不到 onReleased、校正頁會卡住。
        runCatching { latch.await(2, TimeUnit.SECONDS) }
    }

    companion object {
        private const val TAG = "FaceTrackerEngine"

        /** 曝光補償評估間隔（ms）。 */
        private const val EXPOSURE_EVAL_INTERVAL_MS = 500L

        /** 低亮度門檻（lux）：低於此值開始計時。暗室 <10、夜間室內 30–150。 */
        private const val EXPOSURE_LOW_LUX = 30f

        /** 低亮度持續多久才調曝光（ms）：避免亮度短暫波動就反覆調曝光。 */
        private const val EXPOSURE_LOW_SUSTAIN_MS = 2000L

        /** 亮度回升門檻（lux）：高於此值才把曝光調回基準，避免在門檻附近震盪。 */
        private const val EXPOSURE_RESTORE_LUX = 80f

        /** 驗證影格週期取樣間隔（ms）：追蹤中有臉時每秒一張。 */
        private const val PERIODIC_CAPTURE_INTERVAL_MS = 1_000L

        /** 退化事件影格的最短間隔（ms）：同一段退化只拍一張，5 秒內不重複。 */
        private const val DEGRADED_CAPTURE_MIN_INTERVAL_MS = 5_000L

        /** 服務端事件影格請求的最長等待（ms）：逾時沒臉就丟棄，避免用過時狀態取樣。 */
        private const val PENDING_CAPTURE_MAX_AGE_MS = 2_000L

        /** 服務端事件影格請求佇列上限：超過就丟最舊的，避免無臉期間無限堆積。 */
        private const val MAX_PENDING_CAPTURES = 8
    }
}
