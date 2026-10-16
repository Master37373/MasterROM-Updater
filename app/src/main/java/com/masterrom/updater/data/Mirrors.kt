package com.masterrom.updater.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One download server for the ROM file. */
data class Mirror(val url: String, val name: String)

/**
 * Turns the links from the update XML into a list of working download servers.
 *
 * A SourceForge link (".../projects/<project>/files/<path>/download") is expanded to the
 * mirror SourceForge picks itself plus a list of known mirrors. Every candidate is probed with a
 * 1-byte range request; only servers that answer with 206 and the expected file size are kept,
 * fastest first. Other links are probed and used as they are.
 */
object Mirrors {
    private val SF = Regex("""https?://(?:www\.)?sourceforge\.net/projects/([^/]+)/files/(.+?)/download/?$""")

    /** Known SourceForge mirrors, European ones first. Unreachable ones are dropped by the probe. */
    private val SF_HOSTS = listOf(
        "netix", "altushost-swe", "freefr", "kumisystems", "deac-fra", "deac-ams", "deac-riga",
        "netcologne", "onboardcloud", "unlimited", "phoenixnap", "cfhcable", "gigenet",
        "liquidtelecom", "zenlayer", "master",
    )

    private const val USER_AGENT = "Wget/1.21.4"
    private const val PROBE_TIMEOUT = 7_000

    suspend fun resolve(urls: List<String>, size: Long, max: Int = 6): List<Mirror> = withContext(Dispatchers.IO) {
        val candidates = LinkedHashMap<String, String>()
        for (u in urls) {
            val m = SF.find(u.trim())
            if (m != null) {
                val project = m.groupValues[1]
                val path = m.groupValues[2]
                followSourceForge(u.trim())?.let { candidates.putIfAbsent(it, hostLabel(it)) }
                for (h in SF_HOSTS) {
                    candidates.putIfAbsent("https://$h.dl.sourceforge.net/project/$project/$path?viasf=1", h)
                }
            } else {
                candidates.putIfAbsent(u.trim(), hostLabel(u))
            }
        }
        val probed = coroutineScope {
            candidates.map { (url, name) -> async { probe(url, size)?.let { ms -> Triple(url, name, ms) } } }.awaitAll()
        }
        probed.filterNotNull()
            .sortedBy { it.third }
            .distinctBy { it.second }
            .take(max)
            .map { Mirror(it.first, it.second) }
    }

    /** Follows the SourceForge redirects by hand and returns the mirror URL SourceForge chose. */
    private fun followSourceForge(start: String): String? {
        var url = start
        repeat(6) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = PROBE_TIMEOUT
                readTimeout = PROBE_TIMEOUT
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    url = URL(URL(url), location).toString()
                    if (URL(url).host.endsWith(".dl.sourceforge.net")) return url
                } else {
                    return null
                }
            } catch (e: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** Returns the answer time in ms, or null when the server does not serve the expected file. */
    private fun probe(url: String, size: Long): Long? {
        val started = System.nanoTime()
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = PROBE_TIMEOUT
                readTimeout = PROBE_TIMEOUT
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=0-0")
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            if (conn.responseCode != 206) return null
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            if (size > 0 && total != size) return null
            conn.inputStream.use { it.read() }
            (System.nanoTime() - started) / 1_000_000
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun hostLabel(url: String): String = try {
        URL(url).host.removeSuffix(".dl.sourceforge.net")
    } catch (e: Exception) {
        url
    }
}
