package com.masterrom.updater.data

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

/** Information about the installed ROM, read from system properties. */
object DeviceInfo {

    /** Reads a system property through `getprop` (works for normal apps, no hidden API needed). */
    fun prop(key: String): String = try {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val value = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() ?: "" }
        process.waitFor()
        value.trim()
    } catch (e: Exception) {
        ""
    }

    /** Installed ROM version. MasterROM props first, then the inherited ArtisanROM ones. */
    val romVersion: String by lazy {
        prop("ro.masterrom.version").ifEmpty { prop("ro.artisanrom.version") }
    }

    /** Device codename used for the update XML (e.g. "y2s"). */
    val codename: String by lazy {
        prop("ro.masterrom.target").ifEmpty { prop("ro.artisanrom.target") }.ifEmpty { Build.DEVICE }
    }

    val model: String get() = Build.MODEL

    /** Kernel release, e.g. "4.19.87-ArtisanKRNL-v1.2". */
    val kernel: String get() = System.getProperty("os.version") ?: ""
}
