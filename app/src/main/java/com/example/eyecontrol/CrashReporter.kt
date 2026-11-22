package com.example.eyecontrol

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 閃退紀錄：未攔截的例外寫檔留存，下次開啟 App 時顯示，讓使用者能截圖回報定位問題。
 * 只存本機內部儲存，不上傳、不含影像與任何個人資料。
 */
object CrashReporter {
    private const val DIR = "crash-reports"
    private const val MAX_FILES = 5
    private const val PREFS = "crash_report"
    private const val KEY_SHOWN = "shown_"

    /** 接上預設的未攔截例外處理器：先寫檔，再交回原本的處理器（系統照常結束進程）。 */
    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { writeReport(context.applicationContext, "uncaught", thread.name, e) }
            prev?.uncaughtException(thread, e)
        }
    }

    /** 已攔截但仍需留存的錯誤（例如校正流程內部失敗）：寫檔但不結束進程。 */
    fun recordCaught(context: Context, where: String, e: Throwable) {
        runCatching { writeReport(context.applicationContext, "caught:$where", Thread.currentThread().name, e) }
    }

    private fun writeReport(context: Context, kind: String, thread: String, e: Throwable): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val file = File(dir, "crash-$ts.txt")
        file.writeText(
            "時間：$ts\n種類：$kind\n執行緒：$thread\n" +
                "App：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "裝置：${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n\n" +
                sw.toString(),
        )
        // 只留最近 5 份，避免佔用空間。
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
        return file
    }

    /** 尚未顯示過的最新一份報告；沒有則回 null。 */
    fun pendingReport(context: Context): File? {
        val dir = File(context.filesDir, DIR)
        val latest = dir.listFiles()?.maxByOrNull { it.name } ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_SHOWN + latest.name, false)) null else latest
    }

    fun markShown(context: Context, file: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOWN + file.name, true).apply()
    }

    fun clearAll(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
    }
}
