package com.masterrom.updater.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.masterrom.updater.download.DownloadJob
import java.util.Calendar

/**
 * Samsung-style automation:
 * - automatic download of a new ROM build over Wi-Fi (started from the daily check)
 * - "install tonight": the downloaded update is installed at [NIGHT_HOUR]:00
 *
 * Both run through exact alarms ([AlarmReceiver]): an exact alarm may start a foreground service
 * from the background, which a job or a plain broadcast may not.
 */
object Schedule {
    private const val TAG = "MasterROM"
    const val ACTION_DOWNLOAD = "com.masterrom.updater.action.AUTO_DOWNLOAD"
    const val ACTION_INSTALL = "com.masterrom.updater.action.NIGHT_INSTALL"
    /** Hour of the night install (local time). */
    const val NIGHT_HOUR = 2

    private const val KEY_AUTO_DOWNLOAD = "auto_download"
    private const val KEY_NIGHT_AT = "night_install_at"
    private const val KEY_NIGHT_VERSION = "night_install_version"
    private const val KEY_JOB = "scheduled_job"

    private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    // ---- automatic download ----

    fun autoDownload(c: Context) = prefs(c).getBoolean(KEY_AUTO_DOWNLOAD, false)

    fun setAutoDownload(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_AUTO_DOWNLOAD, on).apply()

    /** True on Wi-Fi or another network that is not metered. */
    fun unmetered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts the download of [job] in a moment (through an exact alarm, see class comment). */
    fun downloadSoon(c: Context, job: DownloadJob) {
        saveJob(c, job)
        setAlarm(c, ACTION_DOWNLOAD, System.currentTimeMillis() + 3_000)
        Log.i(TAG, "automatic download of ${job.version} scheduled")
    }

    // ---- install tonight ----

    /** Time of the planned night install in ms, 0 when none is planned. */
    fun nightInstallAt(c: Context): Long = prefs(c).getLong(KEY_NIGHT_AT, 0L)

    fun nightInstallVersion(c: Context): String? = prefs(c).getString(KEY_NIGHT_VERSION, null)

    /** Plans the install of the downloaded [job] for the coming night. Returns the time. */
    fun planNightInstall(c: Context, job: DownloadJob): Long {
        val at = nextNight(System.currentTimeMillis())
        saveJob(c, job)
        prefs(c).edit().putLong(KEY_NIGHT_AT, at).putString(KEY_NIGHT_VERSION, job.version).apply()
        setAlarm(c, ACTION_INSTALL, at)
        Log.i(TAG, "night install of ${job.version} planned for $at")
        return at
    }

    fun cancelNightInstall(c: Context) {
        prefs(c).edit().remove(KEY_NIGHT_AT).remove(KEY_NIGHT_VERSION).apply()
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, ACTION_INSTALL))
    }

    /** Alarms do not survive a reboot: plans the night install again (called after boot). */
    fun restore(c: Context) {
        val at = nightInstallAt(c)
        if (at == 0L) return
        if (at > System.currentTimeMillis()) setAlarm(c, ACTION_INSTALL, at) else cancelNightInstall(c)
    }

    /** Next [NIGHT_HOUR]:00 after [now]. */
    fun nextNight(now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, NIGHT_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // ---- the job (what to download / install) ----

    internal fun saveJob(c: Context, job: DownloadJob) {
        val text = listOf(job.version, job.filename, job.size.toString(), job.md5).joinToString("\n") +
            "\n" + job.urls.joinToString("\n")
        prefs(c).edit().putString(KEY_JOB, text).apply()
    }

    internal fun loadJob(c: Context): DownloadJob? {
        val lines = prefs(c).getString(KEY_JOB, null)?.lines() ?: return null
        if (lines.size < 4) return null
        return DownloadJob(lines[0], lines[1], lines[2].toLongOrNull() ?: 0L, lines[3], lines.drop(4).filter { it.isNotBlank() })
    }

    // ---- alarms ----

    private fun pending(c: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        c,
        if (action == ACTION_INSTALL) 2 else 1,
        Intent(c, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAlarm(c: Context, action: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, action))
        } catch (e: SecurityException) {
            Log.i(TAG, "exact alarm not allowed, using an inexact one: $e")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, action))
        }
    }
}
