package com.masterrom.updater.install

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs a downloaded ROM ZIP through TWRP, the same way the old updater did:
 *
 * 1. The ZIP is moved to /data/media/TWRP/ota_update.zip. /data/media itself is not encrypted,
 *    while the app's own folder and /sdcard are (FBE), so TWRP can always read it there.
 * 2. An OpenRecoveryScript ("install …", "reboot") is written to /cache/recovery and /sdcard.
 * 3. The phone reboots to recovery; TWRP installs the ZIP and boots the new system.
 *
 * Needs root (KernelSU). After the next boot [cleanup] removes the scripts and the ZIP.
 */
object Installer {
    private const val TAG = "MasterROM"
    const val TARGET = "/data/media/TWRP/ota_update.zip"
    private const val ORS = "/cache/recovery/openrecoveryscript"
    private const val COMMAND = "/cache/recovery/command"
    private const val SDCARD_ORS = "/sdcard/openrecoveryscript"

    class InstallException(val reason: Reason, message: String) : Exception(message)

    enum class Reason { NO_ROOT, MOVE_FAILED, SCRIPT_FAILED, REBOOT_FAILED, SYSTEM_FAILED }

    /**
     * Without root: when the updater is a privileged system app (RECOVERY + REBOOT granted), the
     * update is handed to Android's own OTA path. Android maps the file's blocks (uncrypt, works
     * with FBE), writes "--update_package=@/cache/recovery/block.map" for the recovery and reboots.
     */
    fun canInstallWithoutRoot(context: Context, zip: File): Boolean {
        val granted = { p: String -> context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        return granted("android.permission.RECOVERY") && granted("android.permission.REBOOT") &&
            zip.absolutePath.startsWith("/storage/emulated/")
    }

    /**
     * 1. Android's uncrypt service writes the ZIP's data in plain form to its blocks on the data
     *    partition and records them in /cache/recovery/block.map. The recovery reads those blocks
     *    directly, so it does not need to decrypt /data (TWRP cannot decrypt it on this ROM).
     * 2. An OpenRecoveryScript "install @/cache/recovery/block.map" + "reboot" is written.
     * 3. Reboot to recovery.
     * [onProgress] gets 0..1 while uncrypt runs (takes a minute or two for 7 GB).
     */
    suspend fun installWithoutRoot(context: Context, zip: File, onProgress: (Float) -> Unit): Nothing =
        withContext(Dispatchers.IO) {
            // uncrypt only handles real /data paths, not the /storage/emulated view
            val dataPath = File(zip.absolutePath.replaceFirst(Regex("^/storage/emulated/(\\d+)/"), "/data/media/$1/"))
            Log.i(TAG, "processPackage: $dataPath")
            try {
                val listener = android.os.RecoverySystem.ProgressListener { p -> onProgress(p / 100f) }
                android.os.RecoverySystem::class.java
                    .getMethod("processPackage", Context::class.java, File::class.java, android.os.RecoverySystem.ProgressListener::class.java)
                    .invoke(null, context, dataPath, listener)
            } catch (e: Exception) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                Log.i(TAG, "processPackage failed: $cause")
                throw InstallException(Reason.SYSTEM_FAILED, cause.toString())
            }
            Log.i(TAG, "block map ready: ${File(BLOCK_MAP).exists()}")

            try {
                val script = "install @$BLOCK_MAP\nreboot\n"
                File(ORS).writeText(script)
                File(COMMAND).writeText("boot-recovery\n--openrecoveryscript=$ORS\n")
                Log.i(TAG, "recovery script written")
            } catch (e: Exception) {
                Log.i(TAG, "writing recovery script failed: $e")
                throw InstallException(Reason.SCRIPT_FAILED, e.toString())
            }

            try {
                context.getSystemService(android.os.PowerManager::class.java).reboot("recovery")
            } catch (e: Exception) {
                Log.i(TAG, "reboot failed: $e")
                throw InstallException(Reason.REBOOT_FAILED, e.toString())
            }
            throw InstallException(Reason.REBOOT_FAILED, "reboot returned")
        }

    private const val BLOCK_MAP = "/cache/recovery/block.map"

    data class Result(val code: Int, val output: String)

    private fun su(cmd: String): Result = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        Log.i(TAG, "su[$code]: ${cmd.take(120)} -> ${out.trim().take(300)}")
        Result(code, out)
    } catch (e: Exception) {
        Log.i(TAG, "su failed: ${e.message}")
        Result(-1, e.message.orEmpty())
    }

    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        su("id").let { it.code == 0 && it.output.contains("uid=0") }
    }

    /** Moves [zip] into place, writes the recovery scripts and reboots. Does not return on success. */
    suspend fun installAndReboot(zip: File): Nothing = withContext(Dispatchers.IO) {
        if (!hasRoot()) throw InstallException(Reason.NO_ROOT, "no root")

        val q = shellQuote(zip.absolutePath)
        val move = su("mkdir -p /data/media/TWRP && rm -f $TARGET && mv -f $q $TARGET && chmod 644 $TARGET && sync")
        if (move.code != 0) throw InstallException(Reason.MOVE_FAILED, move.output)

        val script = "install $TARGET\\nreboot\\n"
        val write = su(
            "mkdir -p /cache/recovery && " +
                "printf \"$script\" > $ORS && " +
                "printf \"boot-recovery\\n--openrecoveryscript=$ORS\\n\" > $COMMAND && " +
                "{ printf \"$script\" > $SDCARD_ORS 2>/dev/null; sync; }",
        )
        if (write.code != 0) throw InstallException(Reason.SCRIPT_FAILED, write.output)

        val reboot = su("svc power reboot recovery || reboot recovery")
        // On success the phone is already going down; reaching this point means it did not reboot.
        throw InstallException(Reason.REBOOT_FAILED, reboot.output)
    }

    /** Puts the ZIP back into the app folder after a failed start, so it can be tried again. */
    suspend fun moveBack(zip: File) = withContext(Dispatchers.IO) {
        su("[ -f $TARGET ] && mv -f $TARGET ${shellQuote(zip.absolutePath)}; rm -f $ORS $COMMAND $SDCARD_ORS; sync")
    }

    /** Removes the recovery scripts and the installed ZIP (after the update has booted). */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        su("rm -f $SDCARD_ORS $ORS $COMMAND $TARGET")
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ---- remembers that an install was started, so the files are cleaned up afterwards ----

    private fun prefs(context: Context) = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    fun markStaged(context: Context, version: String) =
        prefs(context).edit().putString("staged_version", version).commit()

    fun stagedVersion(context: Context): String? = prefs(context).getString("staged_version", null)

    fun clearStaged(context: Context) = prefs(context).edit().remove("staged_version").apply()
}
