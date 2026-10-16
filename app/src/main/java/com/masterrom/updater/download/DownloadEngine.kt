package com.masterrom.updater.download

import com.masterrom.updater.data.Mirror
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.BitSet
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class WifiRequiredException : IOException("Wi-Fi required")

/**
 * Segmented download over several mirrors at once (like aria2c).
 *
 * The file is split into 16 MiB chunks. [connections] workers take chunks from a queue and fetch
 * them with HTTP range requests, spreading the work over all healthy mirrors. Finished chunks are
 * recorded in [stateFile], so a paused or interrupted download continues where it stopped.
 */
class DownloadEngine(
    private val file: File,
    private val stateFile: File,
    private val size: Long,
    mirrors: List<Mirror>,
    private val connections: Int = 8,
    private val mayContinue: () -> Boolean = { true },
) {
    private class Slot(val mirror: Mirror) {
        val failures = AtomicInteger(0)
        @Volatile var dead = false
    }

    private val slots = mirrors.map { Slot(it) }
    private val chunkCount = ((size + CHUNK - 1) / CHUNK).toInt()
    private val done: BitSet = loadState()
    private val stateLock = Any()
    private val next = AtomicInteger(0)
    private val active = Collections.synchronizedSet(HashSet<HttpURLConnection>())
    @Volatile private var stopped = false

    /** Bytes on disk that belong to finished chunks or to the running part of open chunks. */
    val downloaded = AtomicLong(doneBytes())

    val mirrorCount: Int get() = slots.count { !it.dead }

    suspend fun run() {
        if (slots.isEmpty()) throw IOException("No mirror")
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() != size) raf.setLength(size)
            val channel = raf.channel
            val queue = ConcurrentLinkedQueue((0 until chunkCount).filter { !done.get(it) })
            try {
                coroutineScope {
                    launch {
                        while (isActive) {
                            delay(2_000)
                            saveState()
                        }
                    }
                    val workers = List(connections) { worker ->
                        launch {
                            while (true) {
                                val idx = queue.poll() ?: break
                                fetchChunk(idx, worker, channel)
                            }
                        }
                    }
                    workers.forEach { it.join() }
                    coroutineContext.cancelChildren()
                }
            } finally {
                channel.force(false)
                saveState()
            }
        }
        if (stopped) throw CancellationException("stopped")
        if (synchronized(stateLock) { done.cardinality() } < chunkCount) throw IOException("Download incomplete")
    }

    /** Stops all running requests at once (a blocked read would otherwise wait for its timeout). */
    fun stop() {
        stopped = true
        disconnectAll()
    }

    private fun disconnectAll() {
        synchronized(active) { active.toList() }.forEach { runCatching { it.disconnect() } }
    }

    private suspend fun fetchChunk(idx: Int, worker: Int, channel: FileChannel) {
        val start = idx * CHUNK
        val end = minOf(size, start + CHUNK) - 1
        var pos = start
        var attempt = 0
        val buffer = ByteBuffer.allocate(256 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            if (stopped) throw CancellationException("stopped")
            if (!mayContinue()) throw WifiRequiredException()
            val slot = pickSlot(worker + attempt) ?: throw IOException("All mirrors failed")
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(slot.mirror.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    setRequestProperty("User-Agent", "Wget/1.21.4")
                    setRequestProperty("Range", "bytes=$pos-$end")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                active += conn
                val code = conn.responseCode
                if (code != 206) throw IOException("HTTP $code from ${slot.mirror.name}")
                val rangeStart = conn.getHeaderField("Content-Range")
                    ?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (rangeStart != pos) throw IOException("Wrong range from ${slot.mirror.name}")
                conn.inputStream.use { input ->
                    val bytes = buffer.array()
                    while (pos <= end) {
                        if (stopped) throw CancellationException("stopped")
                        coroutineContext.ensureActive()
                        val want = minOf(bytes.size.toLong(), end - pos + 1).toInt()
                        val n = input.read(bytes, 0, want)
                        if (n < 0) break
                        buffer.clear()
                        buffer.limit(n)
                        var written = 0
                        while (written < n) written += channel.write(buffer, pos + written)
                        pos += n
                        downloaded.addAndGet(n.toLong())
                    }
                }
                if (pos <= end) throw IOException("Connection closed early by ${slot.mirror.name}")
                slot.failures.set(0)
                synchronized(stateLock) { done.set(idx) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: WifiRequiredException) {
                disconnectAll()
                throw e
            } catch (e: IOException) {
                if (stopped) throw CancellationException("stopped")
                attempt++
                if (slot.failures.incrementAndGet() >= MAX_MIRROR_FAILURES && slots.count { !it.dead } > 1) {
                    slot.dead = true
                }
                if (attempt >= MAX_ATTEMPTS) {
                    disconnectAll()
                    throw e
                }
                delay(minOf(30_000L, 1_000L shl minOf(attempt, 5)))
            } finally {
                conn?.let { active -= it; it.disconnect() }
            }
        }
    }

    private fun pickSlot(seed: Int): Slot? {
        val alive = slots.filter { !it.dead }
        if (alive.isEmpty()) return null
        return alive[(next.getAndIncrement() + seed).mod(alive.size)]
    }

    private fun doneBytes(): Long {
        var sum = 0L
        var i = done.nextSetBit(0)
        while (i >= 0 && i < chunkCount) {
            sum += minOf(size, (i + 1) * CHUNK) - i * CHUNK
            i = done.nextSetBit(i + 1)
        }
        return sum
    }

    private fun loadState(): BitSet {
        if (!stateFile.exists() || !file.exists()) return BitSet(chunkCount)
        return try {
            val lines = stateFile.readLines()
            if (lines.size < 3 || lines[0].toLong() != size || lines[1].toLong() != CHUNK) return BitSet(chunkCount)
            BitSet.valueOf(java.util.Base64.getDecoder().decode(lines[2]))
        } catch (e: Exception) {
            BitSet(chunkCount)
        }
    }

    private fun saveState() {
        val bits = synchronized(stateLock) { done.clone() as BitSet }
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText("$size\n$CHUNK\n" + java.util.Base64.getEncoder().encodeToString(bits.toByteArray()) + "\n")
        tmp.renameTo(stateFile)
    }

    companion object {
        const val CHUNK = 16L * 1024 * 1024
        private const val MAX_ATTEMPTS = 12
        private const val MAX_MIRROR_FAILURES = 4

        /** Progress of a paused download, read from its state file (0 when there is none). */
        fun savedBytes(file: File, stateFile: File, size: Long): Long {
            if (size <= 0) return 0
            return DownloadEngine(file, stateFile, size, emptyList()).downloaded.get()
        }
    }
}
