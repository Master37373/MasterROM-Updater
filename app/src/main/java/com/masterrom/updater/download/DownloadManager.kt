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

    fun dir(context: Context): File = File(context.filesDir, "ota").apply { mkdirs() }
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
}
