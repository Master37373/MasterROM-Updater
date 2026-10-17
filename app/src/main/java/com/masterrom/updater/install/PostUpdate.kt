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
    enum class Kind {
        ROM_OK, ROM_FAILED, KERNEL_OK, KERNEL_FAILED,
        /** A ZIP the user picked: worked / did not work / cannot be told (no version inside). */
        LOCAL_OK, LOCAL_FAILED, LOCAL_UNKNOWN,
    }

    data class Result(val kind: Kind, val version: String)

    private const val KEY_KIND = "post_kind"
    private const val KEY_VERSION = "post_version"
    private const val KEY_STAGED_KERNEL = "staged_kernel"
    private const val KEY_STAGED_LOCAL = "staged_local"

    private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun markKernel(c: Context, version: String) =
        prefs(c).edit().putString(KEY_STAGED_KERNEL, version).putLong("staged_at", System.currentTimeMillis()).commit()

    fun clearKernel(c: Context) = prefs(c).edit().remove(KEY_STAGED_KERNEL).apply()

    /** Before a picked ZIP is installed: what it contains (kind, version) and its file name. */
    fun markLocal(c: Context, kind: LocalZip.Kind, version: String?, name: String) =
        prefs(c).edit()
            .putString(KEY_STAGED_LOCAL, listOf(kind.name, version.orEmpty(), name).joinToString("\n"))
            .putLong("staged_at", System.currentTimeMillis())
            .commit()

    fun clearLocal(c: Context) = prefs(c).edit().remove(KEY_STAGED_LOCAL).apply()

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
        prefs(c).getString(KEY_STAGED_LOCAL, null)?.let { staged ->
            val parts = staged.split("\n")
            val kind = runCatching { LocalZip.Kind.valueOf(parts[0]) }.getOrNull()
            val v = parts.getOrNull(1).orEmpty()
            val name = parts.getOrNull(2).orEmpty()
            val ok: Boolean? = when {
                v.isEmpty() -> null
                kind == LocalZip.Kind.ROM -> DeviceInfo.romVersion == v
                kind == LocalZip.Kind.KERNEL ->
                    KernelVersion.label(v) != null && KernelVersion.label(v) == KernelVersion.label(DeviceInfo.kernel)
                else -> null
            }
            if (result == null) {
                result = Result(
                    when (ok) {
                        true -> Kind.LOCAL_OK
                        false -> Kind.LOCAL_FAILED
                        null -> Kind.LOCAL_UNKNOWN
                    },
                    // "MasterROM 1.0.2" / "ArtisanKRNL 4.0.1 · mr0.1" instead of the long file name
                    when {
                        kind == LocalZip.Kind.ROM && v.isNotEmpty() -> "MasterROM $v"
                        kind == LocalZip.Kind.KERNEL && v.isNotEmpty() -> KernelVersion.label(v) ?: v
                        else -> name
                    },
                )
            }
            clearLocal(c)
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
        Kind.KERNEL_OK, Kind.KERNEL_FAILED -> KernelVersion.label(r.version) ?: r.version
        else -> r.version   // version text of a picked ZIP, or its file name
    }

    fun isOk(r: Result) = r.kind == Kind.ROM_OK || r.kind == Kind.KERNEL_OK || r.kind == Kind.LOCAL_OK

    fun notify(c: Context, r: Result) {
        val ok = isOk(r)
        val unknown = r.kind == Kind.LOCAL_UNKNOWN
        com.masterrom.updater.check.UpdateCheckJob.notify(
            c, 25,
            c.getString(
                when {
                    unknown -> R.string.post_local_title
                    ok -> R.string.post_ok_title
                    else -> R.string.post_failed_title
                },
                label(r),
            ),
            c.getString(
                when {
                    unknown -> R.string.post_local_unknown_text
                    ok -> R.string.post_ok_notif
                    else -> R.string.post_failed_notif
                },
            ),
        )
    }
}
