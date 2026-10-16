package com.masterrom.updater.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.masterrom.updater.R
import com.masterrom.updater.download.DownloadManager
import com.masterrom.updater.install.Installer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Installs the downloaded update without the app being open (night install). */
class InstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.notif_installing, version))
            .setContentText(getString(R.string.install_preparing_text))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val job = Schedule.loadJob(this)?.takeIf { it.version == version }
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val zip = DownloadManager.file(this, job)
        scope.launch {
            Installer.markStaged(this@InstallService, job.version)
            try {
                if (Installer.canInstallWithoutRoot(this@InstallService, zip)) {
                    Installer.installWithoutRoot(this@InstallService, zip) { }
                } else {
                    Installer.installAndReboot(zip)
                }
            } catch (e: Exception) {
                Log.i("MasterROM", "night install failed: $e")
                Installer.clearStaged(this@InstallService)
                if (e is Installer.InstallException &&
                    (e.reason == Installer.Reason.SCRIPT_FAILED || e.reason == Installer.Reason.REBOOT_FAILED)
                ) Installer.moveBack(zip)
                com.masterrom.updater.check.UpdateCheckJob.notify(
                    this@InstallService, 24,
                    getString(R.string.night_failed_title),
                    getString(R.string.night_failed_text),
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_VERSION = "version"
        private const val CHANNEL = "download"
        private const val NOTIF_ID = 12
    }
}
