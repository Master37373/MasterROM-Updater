package com.masterrom.updater.data

/**
 * Kernel versions as stamped by the MasterROM builds: "ArtisanKRNL-v4.0.1-mr812.1"
 * (upstream version, number of kernel commits, MasterROM patch revision). The ROM build and the
 * kernel-only build stamp the same source the same way, so equal versions mean the same kernel.
 * A kernel without "-mr" (older ROM builds) counts as mr0.
 */
object KernelVersion {
    private val RE = Regex("""([A-Za-z]+KRNL)-v(\d+(?:\.\d+)*)(?:-mr(\d+)(?:\.(\d+))?)?""")

    private fun numbers(s: String): List<Long>? {
        val m = RE.find(s) ?: return null
        val base = m.groupValues[2].split('.').map { it.toLongOrNull() ?: 0L }
        val padded = base + List((4 - base.size).coerceAtLeast(0)) { 0L }
        return padded + (m.groupValues[3].toLongOrNull() ?: 0L) + (m.groupValues[4].toLongOrNull() ?: 0L)
    }

    private fun name(s: String): String? = RE.find(s)?.groupValues?.get(1)

    /** True when [offered] is the same kernel line as [installed] and newer. */
    fun isNewer(offered: String, installed: String): Boolean {
        if (name(offered) == null || name(offered) != name(installed)) return false
        val a = numbers(offered) ?: return false
        val b = numbers(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
    }

    /** "…-ArtisanKRNL-v4.0.1-mr812.1" -> "ArtisanKRNL 4.0.1 · mr812.1", null for other kernels. */
    fun label(s: String): String? {
        val m = RE.find(s) ?: return null
        val mr = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { r ->
            " · mr$r" + (m.groupValues[4].takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
        } ?: ""
        return "${m.groupValues[1]} ${m.groupValues[2]}$mr"
    }
}
