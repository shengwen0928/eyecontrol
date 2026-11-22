package com.example.eyecontrol

import android.content.Context
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 暫時性診斷紀錄：只記數字（特徵與座標），不含影像、不上傳，存在 App 內部儲存。
 *
 * 用途：游標「聚在中間不動」或「每次都偏一邊」時，用真實數據判斷是模型輸出幅度
 * 太小、濾波吃掉移動、還是固定偏移——而不是憑感覺猜。開啟開關後才記錄，
 * 最多 120 秒自動停止；關閉開關或下次開啟時，舊檔只留最近 3 份。
 * 摘要由 [summarize] 算出，顯示在設定頁的對話框，可複製後傳給開發者分析。
 */
object GazeLog {
    private const val DIR = "gaze-logs"
    private const val MAX_FILES = 3
    private const val MAX_SECONDS = 120L
    private const val MIN_INTERVAL_MS = 100L
    private const val HEADER = "t,face,iod,pitch,yaw,rawX,rawY,gainX,gainY,curX,curY,sens,offX,offY"

    private var writer: BufferedWriter? = null
    private var startedMs = 0L
    private var lastMs = 0L
    private var rows = 0

    /**
     * 120 秒期限已到：期限內不再重開新檔（否則關檔後下一幀又會開新檔、永遠錄不停，
     * 違背「暫時」的前提）。關掉開關再重開才重新計時。
     */
    private var autoStopped = false

    /**
     * 記一筆（10 Hz 節流）。[enabled] 為 false 時關閉目前檔案並直接返回，
     * 故服務端每幀都呼叫此方法即可，不必自己管開關狀態。
     */
    fun record(
        context: Context,
        enabled: Boolean,
        face: Boolean,
        features: DoubleArray?,
        rawX: Double?,
        rawY: Double?,
        gainedX: Double?,
        gainedY: Double?,
        curX: Float?,
        curY: Float?,
        sensitivity: Float,
        offsetX: Float,
        offsetY: Float,
    ) {
        if (!enabled) {
            autoStopped = false
            close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (writer != null && now - lastMs < MIN_INTERVAL_MS) return
        if (writer == null) {
            if (autoStopped) return
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(dir, "gaze-$ts.csv")
            val w = runCatching { file.bufferedWriter() }.getOrNull() ?: return
            runCatching { w.write(HEADER + "\n") }.onFailure {
                runCatching { w.close() }
                return
            }
            writer = w
            startedMs = now
            rows = 0
            dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
        }
        if (now - startedMs > MAX_SECONDS * 1000L) {
            autoStopped = true
            close()
            return
        }
        val f = features
        val line = buildString {
            append(now - startedMs)
            append(',').append(if (face) 1 else 0)
            append(',').append(fmt(f?.getOrNull(6)))
            append(',').append(fmt(f?.getOrNull(7)))
            append(',').append(fmt(f?.getOrNull(8)))
            append(',').append(fmt(rawX)).append(',').append(fmt(rawY))
            append(',').append(fmt(gainedX)).append(',').append(fmt(gainedY))
            append(',').append(fmt(curX?.toDouble())).append(',').append(fmt(curY?.toDouble()))
            append(',').append("%.2f".format(Locale.US, sensitivity))
            append(',').append("%.1f".format(Locale.US, offsetX))
            append(',').append("%.1f".format(Locale.US, offsetY))
        }
        runCatching {
            writer?.write(line + "\n")
            rows++
            lastMs = now
            if (rows % 50 == 0) writer?.flush()
        }.onFailure { close() }
    }

    fun close() {
        // flush 失敗時也要 close，兩個步驟分開包，免得前者拋出就跳過後者。
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        rows = 0
    }

    /** 把緩衝區寫出（摘要計算前呼叫，免得看到 5 秒前的舊數據）。 */
    fun flush() {
        runCatching { writer?.flush() }
    }

    fun latestFile(context: Context): File? {
        val dir = File(context.filesDir, DIR)
        return dir.listFiles()?.maxByOrNull { it.name }
    }

    fun clearAll(context: Context) {
        close()
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * 讀最新一份紀錄算摘要：筆數、臉部出現率、原始預測與濾波後游標的 X/Y 範圍、
     * 平均修正偏移。範圍能直接回答「幅度太小」與「固定偏差」兩個問題：
     * 原始範圍小 = 模型輸出保守；原始範圍大但游標範圍小 = 濾波／靈敏度吃掉移動；
     * 兩者中心都偏一邊 = 固定偏移。
     */
    fun summarize(context: Context): String? {
        flush()
        val file = latestFile(context) ?: return null
        val lines = runCatching { file.readLines() }.getOrNull()
            ?.drop(1)?.filter { it.isNotBlank() } ?: return null
        if (lines.isEmpty()) return null
        var faceCount = 0
        var rawMinX = Double.MAX_VALUE; var rawMaxX = -Double.MAX_VALUE
        var rawMinY = Double.MAX_VALUE; var rawMaxY = -Double.MAX_VALUE
        var curMinX = Double.MAX_VALUE; var curMaxX = -Double.MAX_VALUE
        var curMinY = Double.MAX_VALUE; var curMaxY = -Double.MAX_VALUE
        var offSumX = 0.0; var offSumY = 0.0
        var sens = 0f
        for (line in lines) {
            val c = line.split(',')
            if (c.size < 14) continue
            if (c[1] == "1") faceCount++
            c[5].toDoubleOrNull()?.let { rawMinX = minOf(rawMinX, it); rawMaxX = maxOf(rawMaxX, it) }
            c[6].toDoubleOrNull()?.let { rawMinY = minOf(rawMinY, it); rawMaxY = maxOf(rawMaxY, it) }
            c[9].toDoubleOrNull()?.let { curMinX = minOf(curMinX, it); curMaxX = maxOf(curMaxX, it) }
            c[10].toDoubleOrNull()?.let { curMinY = minOf(curMinY, it); curMaxY = maxOf(curMaxY, it) }
            c[12].toDoubleOrNull()?.let { offSumX += it }
            c[13].toDoubleOrNull()?.let { offSumY += it }
            c[11].toFloatOrNull()?.let { sens = it }
        }
        val n = lines.size
        fun range(min: Double, max: Double) =
            if (min == Double.MAX_VALUE) "無資料" else "${"%.0f".format(min)}–${"%.0f".format(max)}"
        return buildString {
            append("診斷摘要（${file.name}）\n")
            append("筆數：$n，臉部出現率：${faceCount * 100 / n}%\n")
            append("原始預測 X：${range(rawMinX, rawMaxX)}，Y：${range(rawMinY, rawMaxY)}\n")
            append("濾波後游標 X：${range(curMinX, curMaxX)}，Y：${range(curMinY, curMaxY)}\n")
            append("平均修正偏移：X ${"%.1f".format(offSumX / n)}，Y ${"%.1f".format(offSumY / n)}\n")
            append("靈敏度：${"%.1f".format(sens)}")
        }
    }

    private fun fmt(v: Double?): String =
        if (v == null || !v.isFinite()) "" else "%.3f".format(Locale.US, v)
}
