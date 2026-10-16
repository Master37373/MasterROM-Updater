package com.masterrom.updater.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.masterrom.updater.R
import com.masterrom.updater.download.DownloadManager
import com.masterrom.updater.download.DownloadService
import com.masterrom.updater.download.Phase

/** Runs the alarms of [Schedule]: automatic download and night install. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Schedule.ACTION_DOWNLOAD -> download(context)
            Schedule.ACTION_INSTALL -> install(context)
        }
    }

    private fun download(c: Context) {
        val job = Schedule.loadJob(c) ?: return
        if (DownloadService.running) return
        DownloadManager.restore(c, job)
        if (DownloadManager.state.value.phase == Phase.DONE) return
        try {
            DownloadManager.start(c, job)
            Log.i(TAG, "automatic download of ${job.version} started")
        } catch (e: Exception) {
            Log.i(TAG, "automatic download could not start: $e")
        }
    }

    private fun install(c: Context) {
        val version = Schedule.nightInstallVersion(c)
        Schedule.cancelNightInstall(c)
        val job = Schedule.loadJob(c)?.takeIf { it.version == version } ?: return
        val zip = DownloadManager.file(c, job)
        if (!DownloadManager.okMarker(c, job).exists() || zip.length() != job.size) {
            Log.i(TAG, "night install skipped: download missing")
            return
        }
        val bm = c.getSystemService(android.os.BatteryManager::class.java)
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 30 && !bm.isCharging) {
            com.masterrom.updater.check.UpdateCheckJob.notify(
                c, 23,
                c.getString(R.string.night_skipped_title),
                c.getString(R.string.night_skipped_battery, level),
            )
            return
        }
        try {
            ContextCompat.startForegroundService(
                c,
                Intent(c, InstallService::class.java).putExtra(InstallService.EXTRA_VERSION, job.version),
            )
        } catch (e: Exception) {
            Log.i(TAG, "night install could not start: $e")
        }
    }

    private companion object {
        const val TAG = "MasterROM"
    }
}
