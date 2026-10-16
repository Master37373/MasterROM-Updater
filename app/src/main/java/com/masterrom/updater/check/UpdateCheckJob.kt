package com.masterrom.updater.check

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.masterrom.updater.MainActivity
import com.masterrom.updater.R
import com.masterrom.updater.data.DeviceInfo
import com.masterrom.updater.data.UpdateRepository
import com.masterrom.updater.data.compareVersions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Daily background check (JobScheduler, needs a network). Posts one notification per new
 * version; it is not repeated for the same version.
 */
class UpdateCheckJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            try {
                checkOnce(applicationContext)
            } catch (e: Exception) {
                Log.i(TAG, "background check failed: $e")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    companion object {
        private const val TAG = "MasterROM"
        private const val JOB_ID = 4711
        private const val CHANNEL = "updates"
        private const val NOTIF_ID = 20
        private const val KEY_AUTO = "auto_check"
        private const val KEY_NOTIFIED = "notified_version"

        private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

        fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_AUTO, true)

        fun setEnabled(c: Context, on: Boolean) {
            prefs(c).edit().putBoolean(KEY_AUTO, on).apply()
            schedule(c)
        }

        /** Schedules (or cancels) the daily job according to the setting. Safe to call often. */
        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (!isEnabled(c)) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24L * 60 * 60 * 1000, 6L * 60 * 60 * 1000)
                .setPersisted(true)
                .build()
            js.schedule(job)
            Log.i(TAG, "daily update check scheduled")
        }

        /** One check; notifies when a newer ROM build is published. */
        suspend fun checkOnce(c: Context) {
            val codename = DeviceInfo.codename
            val info = UpdateRepository.fetch(codename)
            val installed = DeviceInfo.romVersion
            val fromArtisan = DeviceInfo.prop("ro.masterrom.version").isEmpty()
            val rom = info.rom?.takeIf { fromArtisan || compareVersions(it.version, installed) > 0 } ?: return
            prefs(c).edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (prefs(c).getString(KEY_NOTIFIED, null) == rom.version) return
            notify(c, rom.version)
            prefs(c).edit().putString(KEY_NOTIFIED, rom.version).apply()
        }

        private fun notify(c: Context, version: String) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, c.getString(R.string.notif_channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(c.getString(R.string.notif_update_title, version))
                .setContentText(c.getString(R.string.notif_update_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify(NOTIF_ID, n)
            } catch (e: SecurityException) {
                Log.i(TAG, "notification not allowed: $e")
            }
        }
    }
}
