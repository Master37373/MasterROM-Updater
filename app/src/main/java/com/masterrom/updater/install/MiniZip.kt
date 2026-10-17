package com.masterrom.updater.install

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * Minimal read-only ZIP reader on plain file reads.
 *
 * java.util.zip.ZipFile on Android memory-maps the file (libziparchive). SELinux does not allow
 * the updater (priv_app) to map files in /data/ota_package, only to read them, so ZipFile fails
 * there with "Permission denied". This reader only uses read() and is enough to pull a few small
 * text entries (build_info.txt, updater-script, metadata) out of a ROM or kernel zip.
 * Handles ZIP64 (ROM zips above 4 GB) and the long comment of an OTA-signed zip.
 */
class MiniZip(file: File) : Closeable {
    private class Entry(val method: Int, val compSize: Long, val size: Long, val localOffset: Long)

    private val f = RandomAccessFile(file, "r")
    private val entries = HashMap<String, Entry>()

    init {
        try {
            readCentralDirectory()
        } catch (e: Exception) {
            f.close()
            throw e
        }
    }

    fun has(name: String) = entries.containsKey(name)

    /** Text of a small entry (at most [max] bytes), or null when it is missing. */
    fun text(name: String, max: Int = 1 shl 20): String? {
        val e = entries[name] ?: return null
        if (e.size > max || e.compSize > max * 4L) throw IOException("entry too big: $name")
        f.seek(e.localOffset)
        val lh = ByteArray(30)
        f.readFully(lh)
        if (u32(lh, 0) != 0x04034b50L) throw IOException("bad local header: $name")
        val dataStart = e.localOffset + 30 + u16(lh, 26) + u16(lh, 28)
        f.seek(dataStart)
        val comp = ByteArray(e.compSize.toInt())
        f.readFully(comp)
        val out = when (e.method) {
            0 -> comp
            8 -> {
                val inf = Inflater(true)
                try {
                    inf.setInput(comp)
                    val o = ByteArray(e.size.toInt())
                    var n = 0
                    while (n < o.size) {
                        val r = inf.inflate(o, n, o.size - n)
                        if (r == 0 && (inf.finished() || inf.needsInput())) break
                        n += r
                    }
                    if (n != o.size) throw IOException("short entry: $name")
                    o
                } finally {
                    inf.end()
                }
            }
            else -> throw IOException("unsupported method ${e.method}: $name")
        }
        return String(out, Charsets.UTF_8)
    }

    override fun close() = f.close()

    private fun readCentralDirectory() {
        val len = f.length()
        if (len < 22) throw IOException("not a zip")
        // the end record sits in the last 22 + 65535 bytes (the comment may be up to 64 KB)
        val tailLen = minOf(len, 22L + 65535L).toInt()
        val tail = ByteArray(tailLen)
        f.seek(len - tailLen)
        f.readFully(tail)
        var eocd = -1
        for (i in tailLen - 22 downTo 0) {
            if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tailLen) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("no end of central directory")
        var count = u16(tail, eocd + 10).toLong()
        var cdSize = u32(tail, eocd + 12)
        var cdOffset = u32(tail, eocd + 16)
        // ZIP64: the locator sits right before the classic end record
        if ((count == 0xffffL || cdSize == 0xffffffffL || cdOffset == 0xffffffffL) && eocd >= 20 &&
            u32(tail, eocd - 20) == 0x07064b50L
        ) {
            val z64 = u64(tail, eocd - 20 + 8)
            val rec = ByteArray(56)
            f.seek(z64)
            f.readFully(rec)
            if (u32(rec, 0) != 0x06064b50L) throw IOException("bad zip64 end record")
            count = u64(rec, 32)
            cdSize = u64(rec, 40)
            cdOffset = u64(rec, 48)
        }
        if (cdSize > 64L * 1024 * 1024 || cdOffset + cdSize > len) throw IOException("bad central directory")
        val cd = ByteArray(cdSize.toInt())
        f.seek(cdOffset)
        f.readFully(cd)
        var p = 0
        var n = 0L
        while (n < count && p + 46 <= cd.size) {
            if (u32(cd, p) != 0x02014b50L) throw IOException("bad central directory entry")
            val method = u16(cd, p + 10)
            var comp = u32(cd, p + 20)
            var size = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            var local = u32(cd, p + 42)
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            // ZIP64 extra field: only the values set to 0xffffffff are there, in this order
            var x = p + 46 + nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd) {
                val id = u16(cd, x)
                val sz = u16(cd, x + 2)
                if (id == 0x0001) {
                    var q = x + 4
                    if (size == 0xffffffffL) { size = u64(cd, q); q += 8 }
                    if (comp == 0xffffffffL) { comp = u64(cd, q); q += 8 }
                    if (local == 0xffffffffL) { local = u64(cd, q) }
                }
                x += 4 + sz
            }
            entries[name] = Entry(method, comp, size, local)
            p += 46 + nameLen + extraLen + commentLen
            n++
        }
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)
}
