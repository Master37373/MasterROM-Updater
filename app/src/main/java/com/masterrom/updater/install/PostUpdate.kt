package com.masterrom.updater.install

import android.content.Context
import com.masterrom.updater.R
import com.masterrom.updater.data.DeviceInfo
import com.masterrom.updater.data.KernelVersion
import com.masterrom.updater.data.compareVersions

/**
 * What happened to the last install: after the reboot the staged version is compared with the
 * running system. The result is shown once on the start screen (and as a notification after boot).
 */
object PostUpdate {
    enum class Kind { ROM_OK, ROM_FAILED, KERNEL_OK, KERNEL_FAILED }

    data class Result(val kind: Kind, val version: String)

    private const val KEY_KIND = "post_kind"
    private const val KEY_VERSION = "post_version"
    private const val KEY_STAGED_KERNEL = "staged_kernel"

    private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun markKernel(c: Context, version: String) =
        prefs(c).edit().putString(KEY_STAGED_KERNEL, version).putLong("staged_at", System.currentTimeMillis()).commit()

    fun clearKernel(c: Context) = prefs(c).edit().remove(KEY_STAGED_KERNEL).apply()

    /**
     * Compares what was staged before the reboot with the running system and stores the result.
     * Returns the new result, or null when nothing was staged. Clears the staged ROM version only
     * when it failed (on success the caller cleans up the files first, then clears it).
     */
    fun evaluate(c: Context): Result? {
        // started in this boot (the phone has not restarted yet, e.g. uncrypt is still running): nothing to tell
        val bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        if (Installer.stagedAt(c) > bootAt) return null
        var result: Result? = null
        Installer.stagedVersion(c)?.let { v ->
            val ok = compareVersions(DeviceInfo.romVersion, v) >= 0
            result = Result(if (ok) Kind.ROM_OK else Kind.ROM_FAILED, v)
            if (!ok) Installer.clearStaged(c)
        }
        prefs(c).getString(KEY_STAGED_KERNEL, null)?.let { k ->
            val ok = !KernelVersion.isNewer(k, DeviceInfo.kernel)
            if (result == null) result = Result(if (ok) Kind.KERNEL_OK else Kind.KERNEL_FAILED, k)
            clearKernel(c)
        }
        result?.let { prefs(c).edit().putString(KEY_KIND, it.kind.name).putString(KEY_VERSION, it.version).apply() }
        return result
    }

    /** The result that has not been dismissed on the start screen yet. */
    fun pending(c: Context): Result? {
        val kind = prefs(c).getString(KEY_KIND, null)?.let { runCatching { Kind.valueOf(it) }.getOrNull() } ?: return null
        return Result(kind, prefs(c).getString(KEY_VERSION, null).orEmpty())
    }

    fun dismiss(c: Context) = prefs(c).edit().remove(KEY_KIND).remove(KEY_VERSION).apply()

    /** Version text for notifications and the card. */
    fun label(r: Result): String = when (r.kind) {
        Kind.ROM_OK, Kind.ROM_FAILED -> "MasterROM ${r.version}"
        else -> KernelVersion.label(r.version) ?: r.version
    }

    fun notify(c: Context, r: Result) {
        val ok = r.kind == Kind.ROM_OK || r.kind == Kind.KERNEL_OK
        com.masterrom.updater.check.UpdateCheckJob.notify(
            c, 25,
            c.getString(if (ok) R.string.post_ok_title else R.string.post_failed_title, label(r)),
            c.getString(if (ok) R.string.post_ok_notif else R.string.post_failed_notif),
        )
    }
}
