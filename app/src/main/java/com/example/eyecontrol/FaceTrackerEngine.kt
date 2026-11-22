package com.example.eyecontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
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
                    onSample(null)
                } else {
                    val sample = GazeFeatureExtractor.extract(face, result.timestampMs())
                    if (sample == null) {
                        onSample(null)
                    } else {
                        val orientation = orientationTracker.snapshot()
                        val lux = lightSensorTracker.snapshot()
                        onSample(
                            if (orientation == null && lux == null) sample
                            else sample.copy(
                                devicePitch = orientation?.pitch ?: Double.NaN,
                                deviceRoll = orientation?.roll ?: Double.NaN,
                                ambientLux = lux ?: Float.NaN,
                            )
                        )
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
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "相機綁定失敗", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        proxy.use {
            if (closed) return
            val lm = landmarker ?: return
            val bitmap = toBitmap(it) ?: return
            try {
                // LIVE_STREAM 要求單調遞增的時間戳（毫秒）
                lm.detectAsync(BitmapImageBuilder(bitmap).build(), it.imageInfo.timestamp / 1_000_000)
            } catch (e: Exception) {
                // stop() 競態下 landmarker 可能已關閉；丟掉這幀即可
                if (!closed) Log.e(TAG, "detectAsync 失敗", e)
            }
        }
    }

    /** RGBA_8888 ImageProxy → 已擺正方向的 Bitmap（處理 rowStride padding 與旋轉）。 */
    private fun toBitmap(proxy: ImageProxy): Bitmap? {
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

        return rotatedBmp
    }

    fun stop() {
        // 順序很重要：先立旗標擋新幀 → 停相機 → 等分析執行緒清空 → 最後才釋放原生物件
        closed = true
        orientationTracker.stop()
        lightSensorTracker.stop()
        unbindOnMainThread()
        cameraProvider = null
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
    }
}
