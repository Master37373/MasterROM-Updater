package com.masterrom.updater.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * "What's new" for an update, read from CHANGELOG.md of the ROM repo (branch sixteen-qpr2).
 * The section whose heading contains the version is used; its lines are turned into plain
 * bullet points (Markdown markers are stripped). Returns an empty list when nothing is found.
 */
object Changelog {
    private const val URL_MD =
        "https://raw.githubusercontent.com/${UpdateRepository.REPO}/refs/heads/sixteen-qpr2/CHANGELOG.md"

    /** The updater's own changelog in its repo (for "what's new" of a self-update). */
    const val APP_URL_MD = "https://raw.githubusercontent.com/Master37373/MasterROM-Updater/refs/heads/main/CHANGELOG.md"

    suspend fun forVersion(version: String, url: String = URL_MD): List<String> = withContext(Dispatchers.IO) {
        val text = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (conn.responseCode != 200) return@withContext emptyList()
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
        parse(text, version)
    }

    internal fun parse(markdown: String, version: String): List<String> {
        val lines = markdown.lines()
        val versionRe = Regex("(^|[^0-9.])" + Regex.escape(version) + "($|[^0-9])")
        val start = lines.indexOfFirst { it.trimStart().startsWith("#") && versionRe.containsMatchIn(it) }
        if (start < 0) return emptyList()
        val level = lines[start].trimStart().takeWhile { it == '#' }.length
        val out = ArrayList<String>()
        for (i in start + 1 until lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("#")) {
                val lv = l.takeWhile { it == '#' }.length
                if (lv <= level) break
                out += l.trimStart('#').trim().let { clean(it) }.let { if (it.isNotEmpty()) "§$it" else it }
                continue
            }
            if (l.isEmpty()) continue
            out += clean(l.removePrefix("- ").removePrefix("* ").removePrefix("+ "))
        }
        return out.filter { it.isNotBlank() && it != "§" }
    }

    /** All versions of a changelog, newest first: version heading + its lines. */
    data class Section(val title: String, val lines: List<String>)

    fun parseAll(markdown: String): List<Section> {
        val out = ArrayList<Section>()
        var title: String? = null
        var lines = ArrayList<String>()
        for (raw in markdown.lines()) {
            val l = raw.trim()
            if (l.startsWith("# ") || l.startsWith("## ")) {
                title?.let { out += Section(it, lines) }
                title = clean(l.trimStart('#').trim())
                lines = ArrayList()
            } else if (l.startsWith("#")) {
                lines += "§" + clean(l.trimStart('#').trim())
            } else if (l.isNotEmpty() && title != null) {
                lines += clean(l.removePrefix("- ").removePrefix("* ").removePrefix("+ "))
            }
        }
        title?.let { out += Section(it, lines) }
        return out
    }

    /** The updater's own changelog, bundled into the APK at build time. */
    fun ofApp(context: android.content.Context): List<Section> = try {
        parseAll(context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        emptyList()
    }

    /** Removes **bold**, `code` and [link](url) markup. */
    private fun clean(s: String): String = s
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
}
