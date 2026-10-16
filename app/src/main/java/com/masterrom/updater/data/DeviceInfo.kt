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

    /**
     * Real device name. The ROM spoofs Build.MODEL (e.g. SM-S926B), so the name comes
     * from the codename instead. Falls back to Build.MODEL for unknown codenames.
     */
    val model: String get() = deviceName(codename) ?: Build.MODEL

    private val names = mapOf(
        "x1s" to "Galaxy S20",
        "y2s" to "Galaxy S20+",
        "z3s" to "Galaxy S20 Ultra",
        "c1s" to "Galaxy Note20",
        "c2s" to "Galaxy Note20 Ultra",
        "r8s" to "Galaxy S20 FE",
    )

    /** "y2s" / "y2slte" -> "Samsung Galaxy S20+", null if unknown. */
    fun deviceName(codename: String): String? =
        names[codename.lowercase().removeSuffix("lte").removeSuffix("xx")]?.let { "Samsung $it" }

    /** "…-ArtisanKRNL-v4.0.1-mr812.1" -> "ArtisanKRNL 4.0.1 · mr812.1"; other kernels: text after the first '-'. */
    fun shortKernel(release: String): String =
        KernelVersion.label(release) ?: release.substringAfter('-', release)

    /** Kernel release, e.g. "4.19.87-ArtisanKRNL-v1.2". */
    val kernel: String get() = System.getProperty("os.version") ?: ""
}
