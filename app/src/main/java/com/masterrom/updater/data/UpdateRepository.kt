package com.masterrom.updater.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class RomUpdate(
    val version: String,
    val date: String,
    val filename: String,
    val md5: String,
    val size: Long,
    val urls: List<String>,
    val cleanFlash: Boolean,
)

data class KernelUpdate(
    val version: String,
    val date: String,
    val filename: String,
    val md5: String,
    val size: Long,
    val link: String,
    val channel: String,
)

data class UpdateInfo(val rom: RomUpdate?, val kernel: KernelUpdate?)

class NoDeviceException(val codename: String) : IOException("No update XML for $codename")

/**
 * Reads updater/v2/<codename>.xml from the MasterROM repository.
 * The parser is tolerant: it looks for a <rom> and an optional <kernel> section and collects the
 * known child tags, so the format can grow without breaking older app versions.
 */
object UpdateRepository {
    const val REPO = "Master37373/MasterROM"
    private const val BASE = "https://raw.githubusercontent.com/$REPO/refs/heads/main/updater/v2/"
    private const val USER_AGENT = "MasterROMUpdater/1.0"

    suspend fun fetch(codename: String): UpdateInfo = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE$codename.xml").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Cache-Control", "no-cache")
            useCaches = false
        }
        try {
            val code = conn.responseCode
            if (code == 404) throw NoDeviceException(codename)
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { parse(it) }
        } catch (e: FileNotFoundException) {
            throw NoDeviceException(codename)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(input: InputStream): UpdateInfo {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var section: String? = null
        val rom = HashMap<String, String>()
        val kernel = HashMap<String, String>()
        val romUrls = ArrayList<String>()
        var tag: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "rom" || name == "kernel") section = name else tag = name
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    val t = tag
                    if (text.isNotEmpty() && t != null) {
                        when (section) {
                            "rom" -> if (t == "url" || t == "part" || t == "link") romUrls += text else rom[t] = text
                            "kernel" -> kernel[t] = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "rom" || name == "kernel") section = null
                    tag = null
                }
            }
            event = parser.next()
        }

        val romUpdate = rom["version"]?.let {
            RomUpdate(
                version = it,
                date = rom["date"].orEmpty(),
                filename = rom["filename"].orEmpty(),
                md5 = rom["md5"].orEmpty(),
                size = rom["size"]?.toLongOrNull() ?: 0L,
                urls = romUrls,
                cleanFlash = (rom["clean_flash"] ?: rom["cleanflash"] ?: rom["format_data"])
                    ?.equals("true", ignoreCase = true) == true,
            )
        }
        val kernelUpdate = kernel["version"]?.let {
            KernelUpdate(
                version = it,
                date = kernel["date"].orEmpty(),
                filename = kernel["filename"].orEmpty(),
                md5 = kernel["md5"].orEmpty(),
                size = kernel["size"]?.toLongOrNull() ?: 0L,
                link = kernel["link"] ?: kernel["url"].orEmpty(),
                channel = kernel["channel"].orEmpty(),
            )
        }
        return UpdateInfo(romUpdate, kernelUpdate)
    }
}

/** Compares dotted versions numerically ("4.0.21" > "4.0.9"). */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0L }
    val pb = b.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toLongOrNull() ?: 0L }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0L }
        val y = pb.getOrElse(i) { 0L }
        if (x != y) return x.compareTo(y)
    }
    return 0
}
