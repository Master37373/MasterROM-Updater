package com.masterrom.updater.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.masterrom.updater.data.RomUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The ROM build that is being downloaded. */
data class DownloadJob(
    val version: String,
    val filename: String,
    val size: Long,
    val md5: String,
    val urls: List<String>,
) {
    companion object {
        fun from(rom: RomUpdate) = DownloadJob(rom.version, rom.filename, rom.size, rom.md5, rom.urls)
    }
}

enum class Phase { IDLE, PREPARING, RUNNING, PAUSED, VERIFYING, DONE, FAILED }

enum class DlError { NETWORK, NO_MIRROR, NO_SPACE, CHECKSUM, WIFI_REQUIRED, TIME_LIMIT, STORAGE }

data class DlState(
    val phase: Phase = Phase.IDLE,
    val job: DownloadJob? = null,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val mirrors: Int = 0,
    val verify: Float = 0f,
    val error: DlError? = null,
) {
    val progress: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val etaSeconds: Long get() = if (speed > 0) (total - done) / speed else -1
}

/** Shared download state between [DownloadService] and the UI. */
object DownloadManager {
    private val _state = MutableStateFlow(DlState())
    val state: StateFlow<DlState> = _state.asStateFlow()

    /** Job handed to the service with the next START intent. */
    @Volatile internal var pendingJob: DownloadJob? = null

    internal fun update(transform: (DlState) -> DlState) {
        _state.value = transform(_state.value)
    }

    /**
     * Download folder. When the primary storage is the internal one (/data/media/0), the app's
     * folder there is used: Android's own OTA path (uncrypt) can read it, so the update can be
     * installed without root. With the SD card as primary storage the private app folder is used.
     */
    fun dir(context: Context): File {
        // /data/ota_package is Android's own folder for OTA files: the uncrypt service may read it,
        // so the update can be installed without root. Writable for the system app (cache group).
        otaPackageDir()?.let { return it }
        val primary = context.getSystemService(android.os.storage.StorageManager::class.java).primaryStorageVolume
        val external = if (primary.isEmulated && primary.uuid == null) context.getExternalFilesDir("ota") else null
        return (external ?: File(context.filesDir, "ota")).apply { mkdirs() }
    }

    private var otaDirChecked = false
    private var otaDir: File? = null

    private fun otaPackageDir(): File? {
        if (otaDirChecked) return otaDir
        otaDirChecked = true
        val d = File("/data/ota_package")
        otaDir = try {
            val probe = File(d, ".masterrom_probe")
            probe.writeText("ok")
            probe.delete()
            d
        } catch (e: Exception) {
            null
        }
        android.util.Log.i("MasterROM", "download folder: ${otaDir ?: "app folder"}")
        return otaDir
    }

    /** True when downloads land on internal shared storage (needed for installing without root). */
    fun onInternalStorage(context: Context): Boolean = dir(context).absolutePath.startsWith("/storage/emulated/")
    fun file(context: Context, job: DownloadJob) = File(dir(context), job.filename)
    fun stateFile(context: Context, job: DownloadJob) = File(dir(context), job.filename + ".state")
    fun okMarker(context: Context, job: DownloadJob) = File(dir(context), job.filename + ".ok")

    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, true)
    fun setWifiOnly(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /**
     * Shows what is already on disk for [job]: a finished download, a paused one or nothing.
     * Does nothing while the service is working on a download.
     */
    fun restore(context: Context, job: DownloadJob) {
        val s = _state.value
        if (DownloadService.running) return
        if (s.job == job && s.phase in setOf(Phase.DONE, Phase.FAILED, Phase.PAUSED)) return
        val file = file(context, job)
        val stateFile = stateFile(context, job)
        _state.value = when {
            okMarker(context, job).exists() && file.length() == job.size ->
                DlState(Phase.DONE, job, job.size, job.size)
            stateFile.exists() && file.exists() ->
                DlState(Phase.PAUSED, job, DownloadEngine.savedBytes(file, stateFile, job.size), job.size)
            else -> DlState(Phase.IDLE, job, 0, job.size)
        }
    }

    fun start(context: Context, job: DownloadJob) {
        pendingJob = job
        _state.value = DlState(Phase.PREPARING, job, _state.value.takeIf { it.job == job }?.done ?: 0, job.size)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_START))
    }

    fun pause(context: Context) {
        if (DownloadService.running) {
            context.startService(Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE))
        }
    }

    /** Stops the download and deletes everything that was downloaded for it. */
    fun cancel(context: Context) {
        val job = _state.value.job
        if (DownloadService.running) {
            context.startService(Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
        } else {
            deleteFiles(context)
            _state.value = DlState(Phase.IDLE, job, 0, job?.size ?: 0)
        }
    }

    internal fun deleteFiles(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private const val KEY_WIFI_ONLY = "wifi_only"

    // mirrors found for a job, reused when the download is resumed within a few minutes
    private var mirrorCache: Triple<String, Long, List<com.masterrom.updater.data.Mirror>>? = null
    private const val MIRROR_CACHE_MS = 10L * 60 * 1000

    fun cachedMirrors(job: DownloadJob): List<com.masterrom.updater.data.Mirror>? {
        val c = mirrorCache ?: return null
        return c.third.takeIf { c.first == job.filename && System.currentTimeMillis() - c.second < MIRROR_CACHE_MS && it.isNotEmpty() }
    }

    fun cacheMirrors(job: DownloadJob, mirrors: List<com.masterrom.updater.data.Mirror>) {
        mirrorCache = Triple(job.filename, System.currentTimeMillis(), mirrors)
    }

    fun forgetMirrors() {
        mirrorCache = null
    }
}
