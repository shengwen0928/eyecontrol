package com.example.eyecontrol

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 驗證影格的完整紀錄（內部調校用，正式上架前整段移除——見 REMOVAL_CHECKLIST.md）。
 *
 * 目的：調校期間要有一份「作業依據」，能完整回答某個當下是什麼角度、什麼亮度、
 * 有沒有戴眼鏡反光、是真瞳孔還是反光、是真抖動還是雜訊——只靠數字分不出來，
 * 所以要留眼區裁切影像，與數字紀錄互相對照。App 尚未開放給別人用，此功能
 * 只在內部調校期存在；調校完成、上架前，程式、開關、伺服器端紀錄與
 * Supabase 專案全部移除。
 *
 * 只留「眼區裁切」（雙眼聯集，絕不含全臉、絕不含螢幕截圖），由背景執行緒
 * 壓成 JPEG 後處理，絕不阻塞影像分析執行緒。取樣有界：追蹤中每秒一張，
 * 加上臉遺失／重現、退化事件、停留點擊、邊緣手勢觸發／被擋等事件觸發；
 * 每 120 秒時段最多 60 張，額滿即停；本機只留最近 3 個時段。
 * 本機留存跟診斷紀錄開關，上傳跟遠端監控開關（Play 商店版無網路，上傳
 * 不可能發生）；開關關掉就停止該管道，絕不偷傳。
 */
class VerifyFrames(private val appContext: Context) {

    @Volatile
    var localEnabled: Boolean = false

    @Volatile
    var uploadEnabled: Boolean = false

    val isActive: Boolean
        get() = localEnabled || uploadEnabled

    fun setEnabled(local: Boolean, upload: Boolean) {
        localEnabled = local
        uploadEnabled = upload
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicInteger(0)

    // ---- 時段（session）狀態：120 秒一窗，與數字紀錄的 120 秒節奏一致 ----
    private val sessionLock = Any()

    @Volatile
    private var sessionId: String? = null
    private var sessionStartMs: Long = 0L
    private var submittedInSession: Int = 0
    private var capLogged: Boolean = false
    private var sessionCounter: Int = 0

    @Volatile
    private var pruneNeeded: Boolean = false

    /** 最後一張好的眼區裁切（已壓好的 JPEG 位元組）：臉遺失時當作遺失前證據。 */
    private val lastGoodStash = AtomicReference<StashedFrame?>(null)

    /**
     * 接受一張裁切（呼叫端把 Bitmap 所有權交出來，本物件負責回收）。
     * 開關全關、額滿、或背景佇列已滿時直接丟棄（回收 Bitmap，不阻塞呼叫端）。
     */
    fun submit(crop: Bitmap, meta: VerifyFrameMeta) {
        if (!isActive) {
            recycleQuietly(crop)
            return
        }
        val slot = reserveSlot(meta.captureElapsedMs) ?: run {
            recycleQuietly(crop)
            return
        }
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            recycleQuietly(crop)
            return
        }
        val (id, index) = slot
        worker.submit {
            try {
                encodeCrop(crop)?.let { bytes -> storeAndUpload(bytes, meta, id, index) }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * 臉遺失時，用最後一張好的裁切當作遺失前證據發出（不需新 Bitmap）。
     * 庫存超過 [STASH_MAX_AGE_MS] 就視為過期證據，不發。
     */
    fun emitLastGoodAsFaceLost(lossElapsedMs: Long) {
        val stashed = lastGoodStash.get() ?: return
        if (!isActive) return
        if (lossElapsedMs - stashed.meta.captureElapsedMs > STASH_MAX_AGE_MS) return
        val slot = reserveSlot(lossElapsedMs) ?: return
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
            inFlight.decrementAndGet()
            return
        }
        val (id, index) = slot
        val meta = stashed.meta.copy(
            captureKind = CAPTURE_FACE_LOST,
            requestedElapsedMs = lossElapsedMs,
            lastGood = true,
        )
        worker.submit {
            try {
                storeAndUpload(stashed.jpegBytes, meta, id, index)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** 結束目前時段：清庫存、刪舊時段目錄（只留最近 3 個）。引擎停止或開關全關時呼叫。 */
    fun endSession() {
        synchronized(sessionLock) {
            sessionId = null
            submittedInSession = 0
            capLogged = false
        }
        lastGoodStash.set(null)
        pruneSessions()
    }

    /**
     * 保留一個影格名額（回傳時段 id 與序號）。時段未開始或滿 120 秒就開新時段；
     * 額滿回 null（只記一次 log）。呼叫端在背景執行緒也安全（內部同步）。
     */
    private fun reserveSlot(nowMs: Long): Pair<String, Int>? {
        synchronized(sessionLock) {
            var id = sessionId
            if (id == null || nowMs - sessionStartMs >= SESSION_WINDOW_MS) {
                id = newSessionId()
                sessionId = id
                sessionStartMs = nowMs
                submittedInSession = 0
                capLogged = false
                pruneNeeded = true
            }
            if (submittedInSession >= MAX_FRAMES_PER_SESSION) {
                if (!capLogged) {
                    capLogged = true
                    Log.w(TAG, "驗證影格已達每時段上限（$MAX_FRAMES_PER_SESSION 張），停止擷取")
                }
                return null
            }
            val index = submittedInSession++
            return id to index
        }
    }

    private fun newSessionId(): String {
        // 檔名時間戳用 wall clock（與 GazeLog 檔名一致）；時段長度計算仍用
        // elapsedRealtime（reserveSlot 的 nowMs），兩種時鐘不混用。
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        return "vfy-$ts-${sessionCounter++}"
    }

    /**
     * 壓 JPEG、寫本機、上傳（背景執行緒）。寫本機只在時段仍有效時；
     * 上傳只在上傳開關「當下」仍開著時——開關關掉後排隊中的影格也不會偷傳。
     */
    private fun storeAndUpload(bytes: ByteArray, meta: VerifyFrameMeta, id: String, index: Int) {
        if (pruneNeeded) {
            pruneNeeded = false
            pruneSessions()
        }
        // 記住最後一張好的裁切（臉遺失證據用）；臉遺失發出的那張本身不刷新庫存。
        if (!meta.lastGood) {
            lastGoodStash.set(StashedFrame(bytes, meta))
        }
        if (localEnabled && sessionId == id) {
            runCatching {
                val dir = File(appContext.filesDir, "$FRAMES_DIR/$id").apply { mkdirs() }
                FileOutputStream(File(dir, "frame-$index.jpg")).use { it.write(bytes) }
            }.onFailure { Log.w(TAG, "verify frame local write failed", it) }
        }
        if (uploadEnabled) {
            val json = buildFrameJson(id, meta, Base64.encodeToString(bytes, Base64.NO_WRAP))
            GazeLog.uploadFrame("frame-$id-$index", json)
        }
    }

    /** 刪舊時段目錄，只留最近 3 個（檔名時間戳記排序）。 */
    private fun pruneSessions() {
        runCatching {
            val root = File(appContext.filesDir, FRAMES_DIR)
            val dirs = root.listFiles()?.filter { it.isDirectory }
                ?.sortedByDescending { it.name } ?: return@runCatching
            dirs.drop(MAX_SESSIONS_KEPT).forEach { runCatching { it.deleteRecursively() } }
        }.onFailure { Log.w(TAG, "verify frame prune failed", it) }
    }

    /**
     * 裁切圖壓成 JPEG（先縮到 320px 寬；超過 32KB 再縮到 240px 一次；
     * 仍超過就放棄）。傳入的 Bitmap 一律由本函式回收。
     */
    private fun encodeCrop(crop: Bitmap): ByteArray? {
        var current: Bitmap = crop
        try {
            current = scaleToMaxWidth(current, MAX_FRAME_WIDTH_PX)
            var bytes = toJpeg(current)
            if (bytes != null && bytes.size > MAX_JPEG_BYTES) {
                val smaller = scaleToMaxWidth(current, MAX_FRAME_WIDTH_SECOND_PASS)
                if (smaller !== current) {
                    // scaleToMaxWidth 已在建新圖時回收原圖；這裡只作
                    // recycleQuietly 兜底，絕不對已回收的 Bitmap 二次 recycle。
                    recycleQuietly(current)
                    current = smaller
                }
                bytes = toJpeg(current)
            }
            if (bytes == null || bytes.size > MAX_JPEG_BYTES) return null
            return bytes
        } finally {
            recycleQuietly(current)
        }
    }

    /** 等比縮到 maxWidth 寬；已夠小就回傳原圖。新建縮圖時回收原圖。 */
    private fun scaleToMaxWidth(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val h = (src.height * maxWidth.toDouble() / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, maxWidth, h, true)
        src.recycle()
        return scaled
    }

    private fun toJpeg(bitmap: Bitmap): ByteArray? {
        return runCatching {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            out.toByteArray()
        }.getOrNull()
    }

    private fun recycleQuietly(bitmap: Bitmap) {
        runCatching {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 影格 JSON：與數字 CSV 共用同一張表（id 固定前綴 frame-，rows=0、csv 放此 JSON）。 */
    private fun buildFrameJson(sessionId: String, meta: VerifyFrameMeta, jpegB64: String): String {
        fun num(v: Double?): String =
            if (v == null || !v.isFinite()) "null" else "%.3f".format(Locale.US, v)
        fun numF(v: Float?): String =
            if (v == null || !v.isFinite()) "null" else "%.3f".format(Locale.US, v)
        fun str(v: String?): String = if (v == null) "null" else GazeLog.jsonStr(v)
        return buildString {
            append("{\"kind\":\"frame\",\"session\":").append(GazeLog.jsonStr(sessionId))
            append(",\"capture\":").append(GazeLog.jsonStr(meta.captureKind))
            append(",\"jpeg_b64\":").append(GazeLog.jsonStr(jpegB64))
            append(",\"meta\":{")
            append("\"t\":").append(meta.captureElapsedMs)
            append(",\"lux\":").append(numF(meta.lux))
            append(",\"iod\":").append(num(meta.iod))
            append(",\"pitch\":").append(num(meta.pitch))
            append(",\"yaw\":").append(num(meta.yaw))
            append(",\"glassesHint\":").append(num(meta.specularRatio))
            append(",\"degraded\":").append(meta.degraded)
            append(",\"dwellProgress\":").append(numF(meta.dwellProgress))
            append(",\"deviceRoll\":").append(num(meta.deviceRoll))
            append(",\"contrast\":").append(num(meta.eyeContrast))
            append(",\"frameTs\":").append(meta.frameTimestampMs)
            append(",\"reqT\":").append(meta.requestedElapsedMs ?: "null")
            append(",\"csvSession\":").append(str(meta.csvSessionId))
            append(",\"lastGood\":").append(meta.lastGood)
            append("}}")
        }
    }

    private data class StashedFrame(val jpegBytes: ByteArray, val meta: VerifyFrameMeta)

    companion object {
        private const val TAG = "VerifyFrames"

        /** 本機影格根目錄（filesDir 下）。 */
        const val FRAMES_DIR = "verify-frames"

        /** 本機只留最近幾個時段。 */
        private const val MAX_SESSIONS_KEPT = 3

        /** 每 120 秒時段最多幾張影格（含事件觸發）。 */
        const val MAX_FRAMES_PER_SESSION = 60

        /** 時段長度（ms），與數字紀錄的 120 秒節奏一致。 */
        private const val SESSION_WINDOW_MS = 120_000L

        /** 單張 JPEG 上限（位元組）：超過就再縮小一次，仍超過就放棄該張。 */
        private const val MAX_JPEG_BYTES = 32 * 1024

        /** 影格寬度上限（像素）：先縮到 320，超過大小上限再縮到 240。 */
        private const val MAX_FRAME_WIDTH_PX = 320
        private const val MAX_FRAME_WIDTH_SECOND_PASS = 240

        /** JPEG 品質：診斷用，夠看清瞳孔與反光即可，不必高畫質。 */
        private const val JPEG_QUALITY = 70

        /** 背景佇列上限：超過就丟棄新影格，絕不讓待處理影格無限堆積。 */
        private const val MAX_IN_FLIGHT = 4

        /** 臉遺失庫存的最長有效年齡（ms）：太舊的「最後一張好裁切」不是遺失前證據。 */
        private const val STASH_MAX_AGE_MS = 10_000L

        // 擷取原因（capture 欄位值，監看端以此分類）。
        const val CAPTURE_PERIODIC = "periodic"
        const val CAPTURE_FACE_LOST = "face-lost"
        const val CAPTURE_FACE_REGAINED = "face-regained"
        const val CAPTURE_DEGRADED = "degraded"
        const val CAPTURE_DWELL_FIRE = "dwell-fire"
        const val CAPTURE_EDGE_FIRE = "edge-fire"
        const val CAPTURE_EDGE_DENIED = "edge-denied"
    }
}

/**
 * 一張驗證影格的完整紀錄欄位：與數字 CSV 欄位合起來，要能回答「什麼角度、
 * 什麼亮度、有沒有反光、是真瞳孔還是反光、是真抖動還是雜訊」。未知值一律
 * null（JSON 裡記 null），絕不以 0 假裝量到。
 */
data class VerifyFrameMeta(
    val captureKind: String,
    /** 擷取當下的 elapsedRealtime（ms），與數字 CSV 的 t 欄同一時鐘。 */
    val captureElapsedMs: Long,
    /** 事件請求當下的 elapsedRealtime（服務端事件觸發才有；週期／引擎事件為 null）。 */
    val requestedElapsedMs: Long?,
    /** 該幀的 MediaPipe 時間戳（ms）。 */
    val frameTimestampMs: Long,
    val lux: Float?,
    /** 特徵 6：臉距倒數代理。 */
    val iod: Double?,
    /** 特徵 7：pitch 旋轉量。 */
    val pitch: Double?,
    /** 特徵 8：yaw 旋轉量。 */
    val yaw: Double?,
    /** 裝置 roll（弧度，旋轉向量）。 */
    val deviceRoll: Double?,
    /** 眼部影像對比（imgQ）：瞳孔 vs 反光的判斷依據之一。 */
    val eyeContrast: Double?,
    /** 眼區反光比例（glassesHint）。 */
    val specularRatio: Double?,
    /** 該幀是否被判為影像降級（過暗／過平／反光）。 */
    val degraded: Boolean,
    /** 停留進度（服務端事件觸發才有；其餘為 null）。 */
    val dwellProgress: Float?,
    /** 同期數字 CSV 的時段 id（有關聯時才有，供對照）。 */
    val csvSessionId: String?,
    /** 是否為臉遺失時發出的「遺失前最後一張好裁切」。 */
    val lastGood: Boolean = false,
)
