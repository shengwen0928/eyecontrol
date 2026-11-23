/*
 * Copyright (C) 2026 SuShengWen(ZhuZhu)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package com.example.eyecontrol

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 暫時性診斷紀錄（內部調校用，正式上架前整段移除——見 REMOVAL_CHECKLIST.md）：
 * 記游標數字 CSV（17 欄）到 App 內部儲存；遠端監控開啟時，關檔後把整份 CSV
 * 上傳到監控端點。驗證影格（眼區裁切 JPEG）走同一個端點、同一張表，
 * 由 [VerifyFrames] 經 [uploadFrame] 上傳（rows=0、csv 欄位放 JSON）。
 * 校正逐筆紀錄（[CalibLog]）亦走同一端點，由 [uploadCalibration] 上傳
 *（id 首碼 `calib-`、摘要 JSON 為 `calib-...-sum`、rows=0）。
 *
 * 用途：游標「聚在中間不動」或「每次都偏一邊」時，用真實數據判斷是模型輸出幅度
 * 太小、濾波吃掉移動、還是固定偏移——而不是憑感覺猜。每 120 秒自動關檔上傳並
 * 開新一段連續紀錄；舊檔只留最近 3 份。
 * 摘要由 [summarize] 算出，顯示在設定頁的對話框，可複製後傳給開發者分析。
 */
object GazeLog {
    private const val DIR = "gaze-logs"
    private const val MAX_FILES = 3
    private const val MAX_SECONDS = 120L
    private const val MIN_INTERVAL_MS = 100L
    private const val HEADER = "t,face,iod,pitch,yaw,rawX,rawY,gainX,gainY,curX,curY,sens,offX,offY,lux,imgQ,glassesHint"

    /**
     * 遠端監控端點（Supabase 專案 gaze-monitor，內部調校用，調適完連同此段程式一併移除）。
     * 收游標數字 CSV 與驗證影格 JSON（眼區裁切，不含全臉影像）。anon 金鑰是 Supabase
     * 設計上可放進 App 的公開金鑰，資料庫 RLS 只允許它 INSERT（不能讀、不能改、
     * 不能刪）；讀取只用 service_role 金鑰，那把絕不進 App。
     */
    private const val SUPABASE_URL = "https://uxkzxdwjzonpffpqjsoj.supabase.co"
    private const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InV4a3p4ZHdqem9ucGZmcHFqc29qIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTUzMjIwMjQsImV4cCI6MjExMDg5ODAyNH0.l5Xb3Vhx3R704s4IcQEvOr-uPMwXtOR_4rIDEo4aoTM"
    private val uploadExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var currentFile: File? = null

    /** 檔名流水號：同一秒內重開新錄製時段也不撞檔名（撞檔名會撞上傳主鍵、丟資料）。 */
    private var sessionCounter = 0

    /** 這份檔案期間是否曾開啟遠端監控：是才在關檔時上傳。 */
    private var pendingUpload = false

    private var writer: BufferedWriter? = null
    private var startedMs = 0L
    private var lastMs = 0L
    private var rows = 0

    /**
     * 記一筆（10 Hz 節流）。[enabled] 為 false 時關閉目前檔案並直接返回，
     * 故服務端每幀都呼叫此方法即可，不必自己管開關狀態。[remoteUpload] 為 true
     * 表示這幀期間遠端監控開著，關檔時把整份檔案上傳。
     * [lux]、[imageQuality]、[glassesHint] 為環境光（lux）、眼部影像對比、
     * 眼區反光比例：非有限值一律記為空欄（未知），絕不以 0 假裝量到。
     */
    fun record(
        context: Context,
        enabled: Boolean,
        remoteUpload: Boolean,
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
        lux: Float? = null,
        imageQuality: Double? = null,
        glassesHint: Double? = null,
    ) {
        if (!enabled) {
            close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (writer != null && now - lastMs < MIN_INTERVAL_MS) return
        if (writer == null) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
            val file = File(dir, "gaze-$ts-${sessionCounter++}.csv")
            val w = runCatching { file.bufferedWriter() }.getOrNull() ?: return
            runCatching { w.write(HEADER + "\n") }.onFailure {
                runCatching { w.close() }
                return
            }
            writer = w
            currentFile = file
            pendingUpload = false
            startedMs = now
            rows = 0
            dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
        }
        pendingUpload = pendingUpload || remoteUpload
        // 滿 120 秒：關檔（上傳）後直接返回；下一筆 record() 經 writer==null
        // 分支開新檔案（startedMs/rows/pendingUpload 重置、檔名流水號遞增），
        // 紀錄連續不中斷，最多掉一筆 10 Hz 樣本。
        if (now - startedMs > MAX_SECONDS * 1000L) {
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
            append(',').append(fmt(lux?.toDouble()))
            append(',').append(fmt(imageQuality))
            append(',').append(fmt(glassesHint))
        }
        runCatching {
            writer?.write(line + "\n")
            rows++
            lastMs = now
            if (rows % 50 == 0) writer?.flush()
        }.onFailure { close() }
    }

    fun close(upload: Boolean = true) {
        val file = currentFile
        val doUpload = upload && pendingUpload && file != null && rows > 0
        val rowCount = rows
        // flush 失敗時也要 close，兩個步驟分開包，免得前者拋出就跳過後者。
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        currentFile = null
        pendingUpload = false
        rows = 0
        // 關檔當下就把內容快照進記憶體再交給背景上傳：檔名可能被下一時段
        // 覆寫或被清檔刪掉，上傳執行緒事後再讀檔會讀到錯的內容。
        // （doUpload 為 true 只代表 file 曾非空，編譯器無法由此推斷非空，須顯式判空。）
        val targetFile = file
        val csvText =
            if (doUpload && targetFile != null) runCatching { targetFile.readText() }.getOrNull() else null
        if (csvText != null && targetFile != null) {
            uploadExecutor.submit { uploadFile(csvText, targetFile.nameWithoutExtension, rowCount) }
        }
    }

    /** 目前開啟中的 CSV 紀錄 id（檔名去副檔名），供驗證影格 meta 關聯；未錄製時回 null。 */
    fun currentSessionId(): String? = currentFile?.nameWithoutExtension

    /**
     * 上傳一列驗證影格（VerifyFrames 用）：同一張 gaze_logs 表，rows=0、csv 欄位
     * 放影格 JSON（含 base64 JPEG）。走既有上傳執行緒與端點，失敗只寫 Logcat、
     * 不打擾使用者、不重試——與 CSV 上傳同一語意。
     */
    internal fun uploadFrame(id: String, json: String) {
        uploadExecutor.submit { uploadFile(json, id, 0) }
    }

    /**
     * 上傳一份校正逐筆紀錄（CalibrationActivity 用）：同一張 gaze_logs 表，
     * id 首碼 `calib-`；摘要 JSON 另以 `calib-...-sum`、rows=0 上傳。
     * 走既有上傳執行緒、端點與 flavor 守衛，失敗只寫 Logcat、不重試——
     * 與 CSV／影格上傳同一語意。呼叫端負責開關閘門（診斷紀錄＋遠端監控皆開才傳）。
     */
    internal fun uploadCalibration(id: String, csvText: String, rowCount: Int) {
        uploadExecutor.submit { uploadFile(csvText, id, rowCount) }
    }

    /** 把一份關好的紀錄上傳到監控端點（Supabase REST insert）。失敗只寫 Logcat，不打擾使用者、不重試。 */
    private fun uploadFile(csvText: String, id: String, rowCount: Int) {
        // 顯式 flavor 守衛（STEP 3，R-2／K-45）：Play 版不上傳的防護不再
        // 只靠呼叫端閘門。側載版 REMOTE_MONITOR_ENABLED 為 true，行為不變；
        // Play 版為 false，直接返回、不發出任何請求。全部呼叫點皆在本檔
        // 內（close() 與 uploadFrame()），守衛覆蓋 CSV 與影格兩路徑。
        if (!BuildConfig.REMOTE_MONITOR_ENABLED) return
        if (SUPABASE_URL.isBlank()) return
        runCatching {
            val body = """{"id":${jsonStr(id)},"rows":$rowCount,"csv":${jsonStr(csvText)}}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            val conn = (URL("$SUPABASE_URL/rest/v1/gaze_logs").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                setRequestProperty("Prefer", "return=minimal")
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) Log.w("GazeLog", "monitor upload failed: HTTP $code")
        }.onFailure { Log.w("GazeLog", "monitor upload failed", it) }
    }

    /** 最小 JSON 字串跳脫：CSV 內含換行，必須跳脫才能包進 JSON。VerifyFrames 的影格 JSON 共用。 */
    internal fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
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
        close(upload = false)
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * 讀最新一份紀錄算摘要：筆數、臉部出現率、原始預測與濾波後游標的 X/Y 範圍、
     * 平均修正偏移、平均亮度與低亮度佔比、眼部影像品質與反光指標。範圍能直接回答
     * 「幅度太小」與「固定偏差」兩個問題：原始範圍小 = 模型輸出保守；原始範圍大但
     * 游標範圍小 = 濾波／靈敏度吃掉移動；兩者中心都偏一邊 = 固定偏移。
     *
     * 欄位以表頭名稱定位（舊版 14 欄檔案沒有 lux/imgQ/glassesHint，以舊位置 fallback，
     * 新欄位顯示「無資料」）；新檔案一律 17 欄。
     */
    fun summarize(context: Context): String? {
        flush()
        val file = latestFile(context) ?: return null
        val allLines = runCatching { file.readLines() }.getOrNull()
            ?.filter { it.isNotBlank() } ?: return null
        if (allLines.size < 2) return null
        // 欄位索引：有表頭就按名稱，否則按舊版 14 欄位置（相容升級前的檔案）。
        val header = allLines[0].split(',')
        val colIndex = header.withIndex().associate { it.value.trim() to it.index }
        fun idx(name: String, legacyPos: Int): Int = colIndex[name] ?: legacyPos
        val iFace = idx("face", 1)
        val iRawX = idx("rawX", 5)
        val iRawY = idx("rawY", 6)
        val iCurX = idx("curX", 9)
        val iCurY = idx("curY", 10)
        val iSens = idx("sens", 11)
        val iOffX = idx("offX", 12)
        val iOffY = idx("offY", 13)
        val iLux = colIndex["lux"] ?: -1
        val iImgQ = colIndex["imgQ"] ?: -1
        val iGlasses = colIndex["glassesHint"] ?: -1
        val lines = allLines.drop(1)
        if (lines.isEmpty()) return null
        var faceCount = 0
        var rawMinX = Double.MAX_VALUE; var rawMaxX = -Double.MAX_VALUE
        var rawMinY = Double.MAX_VALUE; var rawMaxY = -Double.MAX_VALUE
        var curMinX = Double.MAX_VALUE; var curMaxX = -Double.MAX_VALUE
        var curMinY = Double.MAX_VALUE; var curMaxY = -Double.MAX_VALUE
        var offSumX = 0.0; var offSumY = 0.0
        var sens = 0f
        var luxSum = 0.0; var luxCount = 0; var lowLuxCount = 0
        var imgQSum = 0.0; var imgQCount = 0
        var glassesSum = 0.0; var glassesCount = 0
        fun num(c: List<String>, i: Int): Double? =
            if (i < 0 || i >= c.size) null else c[i].toDoubleOrNull()
        for (line in lines) {
            val c = line.split(',')
            if (c.size < 14) continue
            if (c.getOrNull(iFace) == "1") faceCount++
            num(c, iRawX)?.let { rawMinX = minOf(rawMinX, it); rawMaxX = maxOf(rawMaxX, it) }
            num(c, iRawY)?.let { rawMinY = minOf(rawMinY, it); rawMaxY = maxOf(rawMaxY, it) }
            num(c, iCurX)?.let { curMinX = minOf(curMinX, it); curMaxX = maxOf(curMaxX, it) }
            num(c, iCurY)?.let { curMinY = minOf(curMinY, it); curMaxY = maxOf(curMaxY, it) }
            num(c, iOffX)?.let { offSumX += it }
            num(c, iOffY)?.let { offSumY += it }
            c.getOrNull(iSens)?.toFloatOrNull()?.let { sens = it }
            num(c, iLux)?.let {
                luxSum += it; luxCount++
                if (it < LOW_LUX_THRESHOLD) lowLuxCount++
            }
            num(c, iImgQ)?.let { imgQSum += it; imgQCount++ }
            num(c, iGlasses)?.let { glassesSum += it; glassesCount++ }
        }
        val n = lines.size
        fun range(min: Double, max: Double) =
            if (min == Double.MAX_VALUE) "無資料" else "${"%.0f".format(min)}–${"%.0f".format(max)}"
        fun avg(sum: Double, count: Int, digits: Int = 1): String =
            if (count == 0) "無資料" else "%.${digits}f".format(sum / count)
        return buildString {
            append("診斷摘要（${file.name}）\n")
            append("筆數：$n，臉部出現率：${faceCount * 100 / n}%\n")
            append("原始預測 X：${range(rawMinX, rawMaxX)}，Y：${range(rawMinY, rawMaxY)}\n")
            append("濾波後游標 X：${range(curMinX, curMaxX)}，Y：${range(curMinY, curMaxY)}\n")
            append("平均修正偏移：X ${"%.1f".format(offSumX / n)}，Y ${"%.1f".format(offSumY / n)}\n")
            append("靈敏度：${"%.1f".format(sens)}\n")
            append("平均亮度：${avg(luxSum, luxCount)} lux，低亮度（<${LOW_LUX_THRESHOLD.toInt()} lux）佔比：")
            append(if (luxCount == 0) "無資料" else "${lowLuxCount * 100 / luxCount}%")
            append("\n眼部影像對比：${avg(imgQSum, imgQCount, 1)}，平均反光指標：${avg(glassesSum, glassesCount, 2)}")
        }
    }

    /** 低亮度門檻（lux）：摘要統計用，與 FaceTrackerEngine 的曝光門檻一致。 */
    private const val LOW_LUX_THRESHOLD = 30f

    private fun fmt(v: Double?): String =
        if (v == null || !v.isFinite()) "" else "%.3f".format(Locale.US, v)
}
