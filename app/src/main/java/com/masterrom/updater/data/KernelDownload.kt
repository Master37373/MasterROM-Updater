package com.masterrom.updater.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a kernel zip (a few MB, so one plain stream is enough). Tries the fastest
 * SourceForge mirrors one after another and checks the MD5 while writing.
 */
object KernelDownload {
    const val PREFIX = "MasterROM-Kernel"
    private const val TAG = "MasterROM"

    suspend fun download(k: KernelUpdate, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val name = k.filename.takeIf { it.startsWith(PREFIX) } ?: "${PREFIX}_${k.version}.zip"
        val target = File(dir, name)
        val urls = try {
            Mirrors.resolve(listOf(k.link), k.size, max = 3).map { it.url }
        } catch (e: Exception) {
            emptyList()
        }.ifEmpty { listOf(k.link) }

        var last: Exception? = null
        for (url in urls) {
            ensureActive()
            try {
                val md5 = fetch(url, target, k.size, onProgress)
                if (k.md5.isNotEmpty() && !md5.equals(k.md5, ignoreCase = true)) throw IOException("MD5 mismatch")
                Log.i(TAG, "kernel downloaded: $target")
                return@withContext target
            } catch (e: Exception) {
                Log.i(TAG, "kernel download from ${URL(url).host} failed: $e")
                last = e
                target.delete()
            }
        }
        throw last ?: IOException("no server")
    }

    private fun fetch(url: String, target: File, size: Long, onProgress: (Float) -> Unit): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Wget/1.21.4")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: size
            val digest = MessageDigest.getInstance("MD5")
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                    out.fd.sync()
                }
            }
            if (size > 0 && done != size) throw IOException("size $done != $size")
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Removes downloaded kernel zips (after the kernel was flashed, or a failed try). */
    fun deleteAll(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith(PREFIX) }?.forEach { it.delete() }
    }
}
