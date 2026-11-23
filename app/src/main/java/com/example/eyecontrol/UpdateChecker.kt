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

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * App 內更新檢查（僅側載版）：開 App 時問 GitHub 最新 release，有新版就讓使用者一鍵下載，
 * 下載完直接跳安裝畫面。側載 App 不能完全靜默更新，安裝那一下一定要使用者親自點；
 * 但不用再開瀏覽器找連結、不用手動下載。檢查失敗（沒網路、API 限流）就靜靜不打擾。
 *
 * Play 商店版不走這條路：Play 政策禁止商店版 App 自行下載 APK 更新，MainActivity
 * 以 BuildConfig.SELF_UPDATE_ENABLED 把檢查關掉，更新一律走 Play 商店
 *（見 openStoreListing）。
 */
object UpdateChecker {

    private const val REPO = "shengwen0928/eyecontrol"

    /** Attach workflow 上傳的固定檔名（不含版本號，最新 release 永遠是這個名字）。 */
    const val ASSET_NAME = "eyecontrol-debug.apk"

    data class UpdateInfo(val versionTag: String, val downloadUrl: String)

    /** 背景執行緒查最新 release；有新版才在主執行緒回呼，否則不回呼。 */
    fun checkForUpdate(activity: Activity, onUpdate: (UpdateInfo) -> Unit) {
        thread(name = "update-check", isDaemon = true) {
            val info = runCatching { fetchLatest() }.getOrNull() ?: return@thread
            if (!isNewer(info.versionTag, BuildConfig.VERSION_NAME)) return@thread
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onUpdate(info)
            }
        }
    }

    private fun fetchLatest(): UpdateInfo {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "eyecontrol-updater")
        }
        try {
            conn.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val tag = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name") == ASSET_NAME) {
                        return UpdateInfo(tag, asset.getString("browser_download_url"))
                    }
                }
                throw IllegalStateException("asset $ASSET_NAME not found in latest release")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 語意化版本比較：v 前綴可有可無，缺段補 0。 */
    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        val a = parseVersion(latestTag)
        val b = parseVersion(currentVersion)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").split(".", "-", "+").mapNotNull { it.toIntOrNull() }

    /** 開啟 Play 商店的本 App 頁面（Play 版更新入口）。先試 market:// 直達 Play App，失敗時退回網頁版。 */
    fun openStoreListing(activity: Activity) {
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${activity.packageName}"),
        )
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}"),
        )
        runCatching { activity.startActivity(market) }
            .onFailure { activity.startActivity(web) }
    }

    /** 用 DownloadManager 下載 APK；回傳 download id，下載完成時由呼叫端接手安裝。 */
    fun startDownload(context: Context, info: UpdateInfo): Long {
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
            setTitle("eyecontrol ${info.versionTag} 更新下載中")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, ASSET_NAME,
            )
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /** 下載目的檔（固定位置，與 startDownload 一致）。 */
    fun downloadedApkFile(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), ASSET_NAME)

    /**
     * 跳安裝畫面。尚未允許「安裝未知來源應用程式」時，先開系統設定請使用者允許一次；
     * 允許後回到 App，MainActivity 會再跳一次安裝畫面。
     */
    fun installApk(activity: Activity, apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
            activity.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}
