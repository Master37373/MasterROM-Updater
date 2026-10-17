package com.masterrom.updater.install

import android.content.Context
import android.net.Uri
import android.os.RecoverySystem
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import com.masterrom.updater.data.DeviceInfo
import com.masterrom.updater.data.KernelVersion
import com.masterrom.updater.data.UpdateRepository
import com.masterrom.updater.data.compareVersions
import com.masterrom.updater.download.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A ZIP the user picked (ROM, kernel or another TWRP ZIP). It is copied into the update folder,
 * because Android's install path (uncrypt) can only read files there, checked, then installed
 * like an update.
 *
 * The check tells the user what the file is:
 * - signed with the MasterROM OTA key (the ROM puts that key into /system/etc/security/otacerts.zip,
 *   so Android's own RecoverySystem.verifyPackage checks it), or byte-identical to a published
 *   build (MD5 from the update XML)
 * - signed with another key ("re-signed": changed by someone else) or not signed at all
 * - which version it contains (build_info.txt of a ROM zip, the version line of a kernel zip)
 *   compared with the installed one, and whether it is for this device
 */
object LocalZip {
    const val PREFIX = "Local-"
    private const val TAG = "MasterROM"

    class LocalZipException(val reason: Reason) : Exception(reason.name)

    enum class Reason { NO_SPACE, NOT_FLASHABLE, READ_FAILED, WRONG_DEVICE }

    data class Info(val name: String, val size: Long)

    enum class Kind { ROM, KERNEL, OTHER }

    enum class Trust {
        /** Signed with the MasterROM key, or identical to a published MasterROM build. */
        OFFICIAL,
        /** Has a signature, but not from MasterROM: re-signed or changed. */
        RESIGNED,
        /** No signature at all. */
        UNSIGNED,
    }

    enum class Age { NEWER, SAME, OLDER, UNKNOWN }

    data class Check(
        val kind: Kind,
        val trust: Trust,
        /** "1.0.2" for a ROM, "ArtisanKRNL-v4.0.1-mr3.1" for a kernel, null when unknown. */
        val version: String?,
        val age: Age,
        /** Installed version to compare with ("1.0.1" or the kernel label). */
        val installed: String,
    )

    fun info(c: Context, uri: Uri): Info {
        var name = "update.zip"
        var size = -1L
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getString(0)?.let { name = it }
                if (!cur.isNull(1)) size = cur.getLong(1)
            }
        }
        return Info(name, size)
    }

    /** Copies [uri] into the update folder (computing its MD5 on the way). Returns the file and its MD5. */
    suspend fun copy(c: Context, uri: Uri, info: Info, onProgress: (Float) -> Unit): Pair<File, String> = withContext(Dispatchers.IO) {
        val dir = DownloadManager.dir(c)
        dir.listFiles()?.forEach { if (it.name.startsWith(PREFIX)) it.delete() }
        if (info.size > 0 && StatFs(dir.path).availableBytes < info.size + 256L * 1024 * 1024) {
            throw LocalZipException(Reason.NO_SPACE)
        }
        val safe = info.name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val dest = File(dir, PREFIX + safe)
        val md = MessageDigest.getInstance("MD5")
        try {
            val input = c.contentResolver.openInputStream(uri) ?: throw LocalZipException(Reason.READ_FAILED)
            input.use { ins ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        copied += n
                        if (info.size > 0) onProgress((copied.toFloat() / info.size).coerceIn(0f, 1f))
                    }
                    out.fd.sync()
                }
            }
        } catch (e: LocalZipException) {
            dest.delete(); throw e
        } catch (e: java.io.IOException) {
            dest.delete()
            throw LocalZipException(if (e.message.orEmpty().contains("ENOSPC")) Reason.NO_SPACE else Reason.READ_FAILED)
        } catch (e: Exception) {
            dest.delete(); throw e
        }
        dest to md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks the copied ZIP: flashable, for this device, signature, version.
     * [onProgress] gets 0..1 while the signature is verified (reads the whole file).
     */
    suspend fun check(c: Context, zip: File, md5: String, onProgress: (Float) -> Unit): Check = withContext(Dispatchers.IO) {
        // what is inside
        var kind = Kind.OTHER
        var version: String? = null
        var device: String? = null
        try {
            MiniZip(zip).use { z ->
                if (!z.has("META-INF/com/google/android/update-binary")) throw LocalZipException(Reason.NOT_FLASHABLE)
                z.text("build_info.txt")?.let { txt ->
                    val props = txt.lines()
                        .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                        .toMap()
                    version = props["version"]?.takeIf { it.isNotEmpty() }
                    device = props["device"]?.takeIf { it.isNotEmpty() }
                    if (version != null) kind = Kind.ROM
                }
                if (device == null) {
                    z.text("META-INF/com/android/metadata")?.let { txt ->
                        device = txt.lines()
                            .firstOrNull { it.startsWith("pre-device=") }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
                if (kind == Kind.OTHER) {
                    z.text("META-INF/com/google/android/updater-script")?.let { script ->
                        Regex("""MasterROM kernel ([^"\s)]+)""").find(script)?.let {
                            kind = Kind.KERNEL
                            version = it.groupValues[1]
                        }
                    }
                }
            }
        } catch (e: LocalZipException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "local zip unreadable: $e")
            throw LocalZipException(Reason.NOT_FLASHABLE)
        }
        val codename = DeviceInfo.codename
        val d = device
        if (d != null && !d.split(',').any { it.trim().equals(codename, true) || it.trim().removeSuffix("lte").equals(codename, true) }) {
            throw LocalZipException(Reason.WRONG_DEVICE)
        }

        // who signed it
        var trust = if (verified(zip, onProgress)) Trust.OFFICIAL else if (hasSignature(zip)) Trust.RESIGNED else Trust.UNSIGNED
        if (trust != Trust.OFFICIAL && md5.isNotEmpty()) {
            // identical to a published build (kernel zips are not OTA-signed yet)
            val published = try { UpdateRepository.fetch(codename) } catch (e: Exception) { null }
            if (published != null && (md5.equals(published.rom?.md5, true) || md5.equals(published.kernel?.md5, true))) {
                trust = Trust.OFFICIAL
            }
        }

        // which version, compared with the installed one
        val v = version
        val installed: String
        val age: Age
        when {
            kind == Kind.ROM && v != null -> {
                installed = DeviceInfo.romVersion
                val cmp = compareVersions(v, installed)
                age = if (installed.isEmpty()) Age.UNKNOWN else if (cmp > 0) Age.NEWER else if (cmp == 0) Age.SAME else Age.OLDER
            }
            kind == Kind.KERNEL && v != null -> {
                val k = DeviceInfo.kernel
                installed = DeviceInfo.shortKernel(k)
                age = when {
                    KernelVersion.label(v) == null || KernelVersion.label(k) == null -> Age.UNKNOWN
                    KernelVersion.isNewer(v, k) -> Age.NEWER
                    KernelVersion.isNewer(k, v) -> Age.OLDER
                    else -> Age.SAME
                }
            }
            else -> {
                installed = ""
                age = Age.UNKNOWN
            }
        }
        Check(kind, trust, v, age, installed)
    }

    /** True when the whole-file signature verifies with the ROM's OTA certificates (MasterROM key). */
    private fun verified(zip: File, onProgress: (Float) -> Unit): Boolean = try {
        RecoverySystem.verifyPackage(zip, { p -> onProgress(p / 100f) }, null)
        true
    } catch (e: Exception) {
        Log.i(TAG, "signature check: $e")
        false
    }

    /**
     * True when the ZIP carries a whole-file (OTA) signature at all: signapk -w writes a footer
     * into the ZIP comment whose last 6 bytes are <signature start> 0xff 0xff <comment size>.
     */
    private fun hasSignature(zip: File): Boolean = try {
        RandomAccessFile(zip, "r").use { f ->
            if (f.length() < 6) return@use false
            f.seek(f.length() - 6)
            val b = ByteArray(6)
            f.readFully(b)
            b[2] == 0xff.toByte() && b[3] == 0xff.toByte()
        }
    } catch (e: Exception) {
        false
    }

    fun deleteAll(c: Context) {
        DownloadManager.dir(c).listFiles()?.forEach { if (it.name.startsWith(PREFIX)) it.delete() }
    }
}
