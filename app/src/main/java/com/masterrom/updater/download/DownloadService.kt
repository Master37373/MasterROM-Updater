package com.masterrom.updater.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.masterrom.updater.MainActivity
import com.masterrom.updater.R
import com.masterrom.updater.data.Mirrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Foreground service that runs the ROM download, so it keeps going with the screen off. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var engine: DownloadEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopReason: Phase? = null
    @Volatile private var pauseError: DlError? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                NotificationActionReceiver.cancelPaused(this)
                val job = DownloadManager.pendingJob
                startForeground(NOTIF_ID, buildProgress(DownloadManager.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                if (job == null) {
                    finish()
                } else if (work?.isActive != true) {
                    stopReason = null
                    pauseError = null
                    work = scope.launch { runJob(job) }
                }
            }
            ACTION_PAUSE -> stopWork(Phase.PAUSED, null)
            ACTION_CANCEL -> stopWork(Phase.IDLE, null)
            else -> if (work?.isActive != true) finish()
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits dataSync services to 6 h per day; pause cleanly when the limit is hit. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopWork(Phase.PAUSED, DlError.TIME_LIMIT)
    }

    private fun stopWork(phase: Phase, error: DlError?) {
        stopReason = phase
        pauseError = error
        engine?.stop()
        val w = work
        if (w == null || !w.isActive) {
            applyStop()
            finish()
        } else {
            w.cancel()
        }
    }

    private fun applyStop() {
        val reason = stopReason ?: return
        if (reason == Phase.IDLE) {
            DownloadManager.deleteFiles(this)
            DownloadManager.update { DlState(Phase.IDLE, it.job, 0, it.job?.size ?: 0) }
        } else {
            DownloadManager.update { it.copy(phase = Phase.PAUSED, speed = 0, error = pauseError) }
        }
    }

    private suspend fun runJob(job: DownloadJob) {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MasterROM:download").apply { acquire(8 * 60 * 60 * 1000L) }
        try {
            val dir = DownloadManager.dir(this)
            dir.listFiles()?.forEach { if (!it.name.startsWith(job.filename)) it.delete() }
            val file = DownloadManager.file(this, job)
            val stateFile = DownloadManager.stateFile(this, job)
            if (!stateFile.exists() && file.exists()) file.delete()

            val already = if (file.exists()) DownloadEngine.savedBytes(file, stateFile, job.size) else 0L
            DownloadManager.update { DlState(Phase.PREPARING, job, already, job.size) }

            if (StatFs(dir.path).availableBytes < job.size - already + SPACE_MARGIN) {
                fail(DlError.NO_SPACE); return
            }
            if (!networkAllowed()) {
                DownloadManager.update { it.copy(phase = Phase.PAUSED, error = DlError.WIFI_REQUIRED) }
                return
            }

            // after a short pause the servers found a moment ago are still good: no new search
            val cached = DownloadManager.cachedMirrors(job)
            val mirrors = cached ?: Mirrors.resolve(job.urls, job.size).also { DownloadManager.cacheMirrors(job, it) }
            if (mirrors.isEmpty()) {
                fail(DlError.NO_MIRROR); return
            }
            val e = DownloadEngine(file, stateFile, job.size, mirrors, mayContinue = ::networkAllowed)
            engine = e
            DownloadManager.update { it.copy(phase = Phase.RUNNING, done = e.downloaded.get(), mirrors = e.mirrorCount) }

            val sampler = scope.launch { sample(e) }
            try {
                e.run()
            } finally {
                sampler.cancel()
            }

            verify(job, file, stateFile)
        } catch (c: CancellationException) {
            applyStop()
        } catch (w: WifiRequiredException) {
            DownloadManager.update { it.copy(phase = Phase.PAUSED, speed = 0, error = DlError.WIFI_REQUIRED) }
        } catch (io: IOException) {
            DownloadManager.forgetMirrors()
            if (stopReason != null) applyStop()
            else fail(if (io.message?.contains("ENOSPC") == true) DlError.NO_SPACE else DlError.NETWORK)
        } finally {
            engine = null
            wakeLock?.takeIf { it.isHeld }?.release()
            finish()
        }
    }

    private suspend fun sample(e: DownloadEngine) {
        var last = e.downloaded.get()
        var lastTime = System.nanoTime()
        var speed = 0.0
        var tick = 0
        while (scope.isActive) {
            delay(1_000)
            val now = System.nanoTime()
            val bytes = e.downloaded.get()
            val inst = (bytes - last) * 1e9 / (now - lastTime).coerceAtLeast(1)
            speed = if (speed == 0.0) inst else speed * 0.75 + inst * 0.25
            last = bytes
            lastTime = now
            DownloadManager.update { it.copy(phase = Phase.RUNNING, done = bytes, speed = speed.toLong(), mirrors = e.mirrorCount, error = null) }
            if (tick++ % 2 == 0) notifyProgress()
        }
    }

    private fun verify(job: DownloadJob, file: File, stateFile: File) {
        DownloadManager.update { it.copy(phase = Phase.VERIFYING, done = job.size, speed = 0, verify = 0f) }
        notifyProgress()
        if (job.md5.isNotBlank()) {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1024 * 1024)
            var read = 0L
            var lastUpdate = 0L
            file.inputStream().use { input ->
                while (true) {
                    if (stopReason != null) throw CancellationException("stopped")
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    read += n
                    if (read - lastUpdate > 64L * 1024 * 1024) {
                        lastUpdate = read
                        val p = read.toFloat() / job.size
                        DownloadManager.update { it.copy(verify = p) }
                    }
                }
            }
            val md5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!md5.equals(job.md5.trim(), ignoreCase = true)) {
                file.delete()
                stateFile.delete()
                fail(DlError.CHECKSUM)
                return
            }
        }
        stateFile.delete()
        DownloadManager.okMarker(this, job).writeText(job.md5)
        DownloadManager.update { DlState(Phase.DONE, job, job.size, job.size) }
        notifyDone(job)
    }

    private fun fail(error: DlError) {
        DownloadManager.update { it.copy(phase = Phase.FAILED, speed = 0, error = error) }
    }

    private fun networkAllowed(): Boolean {
        if (!DownloadManager.wifiOnly(this)) return true
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // paused (by the user, no Wi-Fi, time limit): a notification to continue or cancel from there
        val s = DownloadManager.state.value
        if (s.phase == Phase.PAUSED && s.job != null) NotificationActionReceiver.notifyPaused(this, s)
    }

    override fun onDestroy() {
        running = false
        engine?.stop()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        val s = DownloadManager.state.value
        if (s.phase == Phase.RUNNING || s.phase == Phase.PREPARING) {
            DownloadManager.update { it.copy(phase = Phase.PAUSED, speed = 0) }
        }
        super.onDestroy()
    }

    // ---- notifications ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildProgress(s: DlState): Notification {
        val version = s.job?.version.orEmpty()
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        when (s.phase) {
            Phase.VERIFYING -> b.setContentTitle(getString(R.string.notif_verifying, version))
                .setProgress(100, (s.verify * 100).toInt(), false)
            Phase.RUNNING -> {
                val text = getString(
                    R.string.progress_of,
                    Formatter.formatShortFileSize(this, s.done),
                    Formatter.formatShortFileSize(this, s.total),
                ) + " · " + Formatter.formatShortFileSize(this, s.speed) + "/s"
                b.setContentTitle(getString(R.string.notif_downloading, version))
                    .setContentText(text)
                    .setProgress(1000, (s.progress * 1000).toInt(), false)
                    .addAction(0, getString(R.string.pause), servicePending(ACTION_PAUSE, 1))
                    .addAction(0, getString(R.string.cancel), NotificationActionReceiver.pending(this, NotificationActionReceiver.ACTION_CANCEL))
            }
            else -> b.setContentTitle(getString(R.string.notif_downloading, version)).setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun servicePending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, DownloadService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyProgress() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildProgress(DownloadManager.state.value))
    }

    private fun notifyDone(job: DownloadJob) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.notif_done, job.version))
            .setContentText(getString(R.string.notif_done_text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, n)
    }

    companion object {
        const val ACTION_START = "com.masterrom.updater.action.START"
        const val ACTION_PAUSE = "com.masterrom.updater.action.PAUSE"
        const val ACTION_CANCEL = "com.masterrom.updater.action.CANCEL"
        private const val CHANNEL = "download"
        private const val NOTIF_ID = 10
        private const val NOTIF_DONE_ID = 11
        private const val SPACE_MARGIN = 512L * 1024 * 1024

        @Volatile var running = false
            private set
    }
}
