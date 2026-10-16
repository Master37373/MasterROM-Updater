package com.masterrom.updater.data

import com.masterrom.updater.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer MasterROM Updater, published as GitHub release of Master37373/MasterROM-Updater. */
data class AppRelease(val version: String, val apkUrl: String, val size: Long, val changes: List<String>)

object AppUpdate {
    private const val LATEST = "https://api.github.com/repos/Master37373/MasterROM-Updater/releases/latest"

    /** The latest release when it is newer than the installed app, else null. */
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MasterROMUpdater/${BuildConfig.VERSION_NAME}")
        }
        val json = try {
            if (conn.responseCode != 200) return@withContext null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        val version = json.optString("tag_name").removePrefix("v")
        if (version.isEmpty() || compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                val changes = Changelog.forVersion(version, Changelog.APP_URL_MD)
                return@withContext AppRelease(version, a.optString("browser_download_url"), a.optLong("size"), changes)
            }
        }
        null
    }

    /** Downloads the APK into the app's cache; [onProgress] gets 0..1. */
    suspend fun download(release: AppRelease, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(dir, "MasterROM-Updater-${release.version}.apk")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "MasterROMUpdater/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        target
    }
}
