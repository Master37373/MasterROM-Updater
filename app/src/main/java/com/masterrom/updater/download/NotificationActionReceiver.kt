package com.masterrom.updater.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.masterrom.updater.MainActivity
import com.masterrom.updater.R
import com.masterrom.updater.schedule.Schedule

/**
 * "Continue" and "Cancel" in the download notifications. Works also when the app was closed:
 * the job is remembered by [DownloadManager.start] (see [Schedule.loadJob]).
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESUME -> {
                val job = Schedule.loadJob(context) ?: return
                cancelPaused(context)
                DownloadManager.restore(context, job)
                try {
                    DownloadManager.start(context, job)
                } catch (e: Exception) {
                    android.util.Log.i("MasterROM", "resume from notification failed: $e")
                }
            }
            ACTION_CANCEL -> {
                cancelPaused(context)
                Schedule.cancelNightInstall(context)
                DownloadManager.cancel(context)
            }
        }
    }

    companion object {
        const val ACTION_RESUME = "com.masterrom.updater.action.NOTIF_RESUME"
        const val ACTION_CANCEL = "com.masterrom.updater.action.NOTIF_CANCEL"
        private const val CHANNEL = "download"
        private const val NOTIF_PAUSED_ID = 13

        fun pending(c: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            c,
            if (action == ACTION_RESUME) 3 else 4,
            Intent(c, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun notifyPaused(c: Context, s: DlState) {
            val version = s.job?.version.orEmpty()
            val text = c.getString(
                R.string.progress_of,
                Formatter.formatShortFileSize(c, s.done),
                Formatter.formatShortFileSize(c, s.total),
            )
            val open = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(
                    c.getString(if (s.error == DlError.WIFI_REQUIRED) R.string.notif_waiting_wifi else R.string.notif_paused, version),
                )
                .setContentText(text)
                .setProgress(1000, (s.progress * 1000).toInt(), false)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(0, c.getString(R.string.resume), pending(c, ACTION_RESUME))
                .addAction(0, c.getString(R.string.cancel), pending(c, ACTION_CANCEL))
                .build()
            try {
                c.getSystemService(NotificationManager::class.java).notify(NOTIF_PAUSED_ID, n)
            } catch (e: SecurityException) {
                // notifications not allowed
            }
        }

        fun cancelPaused(c: Context) {
            c.getSystemService(NotificationManager::class.java).cancel(NOTIF_PAUSED_ID)
        }
    }
}
