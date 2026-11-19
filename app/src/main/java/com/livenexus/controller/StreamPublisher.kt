package com.livenexus.controller

import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Gerenciador de distribuição de pacotes de transmissão ao vivo.
 * Conecta e publica fluxos RTMP/RTMPS sincronizados para YouTube, Twitch e Kick.
 */
class StreamPublisher(
    private val targets: List<StreamTarget>,
    width: Int,
    height: Int,
    private val fps: Int,
    private val videoBitrateKbps: Int,
    private val requestKeyFrame: () -> Unit = {}
) : AutoCloseable {

    companion object {
        private const val TAG = "LiveNexusPublisher"
        private const val MAX_QUEUE_AGE_NS = 500_000_000L
        private const val MAX_QUEUED_PACKETS = 128
        private const val MAX_QUEUED_BYTES = 4 * 1024 * 1024
        private const val WRITE_TIMEOUT_NS = 1_500_000_000L
        private const val MONITOR_INTERVAL_MS = 100L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 16_000L
        private const val KEY_FRAME_REQUEST_INTERVAL_MS = 2_000L
    }

    data class StreamTarget(
        val platformName: String,
        val rtmpUrl: String,
        val streamKey: String
    )

    private data class VideoHeader(val sps: ByteArray, val pps: ByteArray)

    private sealed class Sample(val data: ByteArray, val timestampMs: Long) {
        val queuedAtNs = System.nanoTime()

        class Video(data: ByteArray, timestampMs: Long, val keyFrame: Boolean, val header: VideoHeader?) :
            Sample(data, timestampMs) {
            val counted = AtomicBoolean(false)
        }

        class Audio(data: ByteArray, timestampMs: Long, val header: ByteArray?) : Sample(data, timestampMs)
    }

    private class Connection(val client: RtmpClient) {
        @Volatile var writeStartedNs: Long? = null
        var videoHeader: VideoHeader? = null
        var audioHeader: ByteArray? = null
        var baseTimestampMs: Long? = null
    }

    private val destinations = CopyOnWriteArrayList(targets.map { Destination(it) })
    private val threadPool = Executors.newCachedThreadPool()
    private val monitor = Executors.newSingleThreadScheduledExecutor()
    private val lastKeyFrameRequestMs = AtomicLong(-KEY_FRAME_REQUEST_INTERVAL_MS)
    val submittedFrames = AtomicLong(0)
    val sentFrames = AtomicLong(0)
    val sendWaitTotalNs = AtomicLong(0)
    val sendWaitMaxNs = AtomicLong(0)
    val rtmpVideoBytesTotal: Long
        get() = destinations.sumOf { it.videoBytesWritten }
    val rtmpVideoBytesPrimary: Long
        get() = destinations.firstOrNull { it.started }?.videoBytesWritten ?: 0L
    val rtmpDestinationCount: Int
        get() = destinations.count { it.started }

    private val mediaClockLock = Any()
    private var streamMediaStartMs = -1L
    private var baseVideoTimestampUs = -1L
    private var baseVideoOffsetMs = 0L
    private var lastVideoTimestampMs = 0L
    private var baseAudioTimestampUs = -1L
    private var baseAudioOffsetMs = 0L
    private var lastAudioTimestampMs = 0L

    @Volatile private var dimensions = width to height
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var cachedVideoHeader: VideoHeader? = null
    @Volatile private var cachedAsc: ByteArray? = null

    val connectedPlatforms: List<String>
        get() = destinations.filter { it.isPublishing }.map { it.target.platformName }

    val hasActivePublishing: Boolean
        get() = destinations.any { it.isPublishing }

    /**
     * Inicia a conexão com todas as plataformas configuradas em paralelo.
     * Retorna a lista de erros por plataforma caso alguma falhe.
     */
    fun start(): Map<String, String> {
        val connectionTasks: List<Pair<Destination, Future<*>>> = synchronized(this) {
            if (closed) return emptyMap()
            destinations.map { destination ->
                val task = threadPool.submit(Runnable { destination.connect() })
                destination to task
            }
        }

        val errors = mutableMapOf<String, String>()
        for ((destination, future) in connectionTasks) {
            try {
                future.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return errors
            } catch (error: Exception) {
                val platform = destination.target.platformName
                errors[platform] = error.cause?.message ?: error.message ?: "Falha ao conectar"
                Log.w(TAG, "Falha ao conectar com $platform", error.cause ?: error)
            }
        }

        synchronized(this) {
            if (closed) return errors
            active = hasActivePublishing
            if (active) {
                destinations.filter { it.started }.forEach { destination ->
                    threadPool.execute { destination.sendUntilClosed() }
                }
                monitor.scheduleWithFixedDelay(
                    { destinations.forEach { it.abortBlockedWrite() } },
                    MONITOR_INTERVAL_MS,
                    MONITOR_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
        return errors
    }

    /**
     * Atualiza as dimensões de vídeo anunciadas nos clientes RTMP ativos (ex: 16:9 para 9:16).
     */
    fun updateVideoDimensions(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        synchronized(mediaClockLock) {
            baseVideoTimestampUs = -1L
            baseVideoOffsetMs = lastVideoTimestampMs
            dimensions = newWidth to newHeight
        }
    }

    fun onVideoHeader(sps: ByteArray, pps: ByteArray) {
        cachedVideoHeader = VideoHeader(sps, pps)
    }

    fun onVideoSample(naluBytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (!active) return
        val timestampMs = synchronized(mediaClockLock) {
            val elapsedMs = elapsedMediaTimeMs()
            if (baseVideoTimestampUs < 0L) {
                baseVideoTimestampUs = info.presentationTimeUs
                baseVideoOffsetMs = maxOf(lastVideoTimestampMs, elapsedMs)
            }
            val rawMs = baseVideoOffsetMs + ((info.presentationTimeUs - baseVideoTimestampUs) / 1000L).coerceAtLeast(0L)
            lastVideoTimestampMs = maxOf(rawMs, lastVideoTimestampMs)
            lastVideoTimestampMs
        }

        val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val sample = Sample.Video(naluBytes, timestampMs, keyFrame, cachedVideoHeader)
        submittedFrames.incrementAndGet()
        var needsKeyFrame = false
        for (destination in destinations) {
            if (destination.offer(sample)) needsKeyFrame = true
        }
        if (needsKeyFrame) requestRecoveryKeyFrame()
    }

    fun onAudioHeader(asc: ByteArray) {
        cachedAsc = asc
    }

    fun onAudioSample(aacBytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (!active) return
        val timestampMs = synchronized(mediaClockLock) {
            val elapsedMs = elapsedMediaTimeMs()
            if (baseAudioTimestampUs < 0L) {
                baseAudioTimestampUs = info.presentationTimeUs
                baseAudioOffsetMs = maxOf(lastAudioTimestampMs, elapsedMs)
            }
            val rawMs = baseAudioOffsetMs + ((info.presentationTimeUs - baseAudioTimestampUs) / 1000L).coerceAtLeast(0L)
            lastAudioTimestampMs = maxOf(rawMs, lastAudioTimestampMs)
            lastAudioTimestampMs
        }

        val sample = Sample.Audio(aacBytes, timestampMs, cachedAsc)
        var needsKeyFrame = false
        for (destination in destinations) {
            if (destination.offer(sample)) needsKeyFrame = true
        }
        if (needsKeyFrame) requestRecoveryKeyFrame()
    }

    private fun elapsedMediaTimeMs(): Long {
        val nowMs = SystemClock.elapsedRealtime()
        if (streamMediaStartMs < 0) streamMediaStartMs = nowMs
        return nowMs - streamMediaStartMs
    }

    private fun requestRecoveryKeyFrame() {
        if (closed) return
        val lastRequest = lastKeyFrameRequestMs.get()
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequest >= KEY_FRAME_REQUEST_INTERVAL_MS &&
            lastKeyFrameRequestMs.compareAndSet(lastRequest, now)) {
            requestKeyFrame()
        }
    }

    private inner class Destination(val target: StreamTarget) {
        private val queueLock = Object()
        private val queue = ArrayDeque<Sample>()
        private var queuedBytes = 0
        private var waitingForKeyFrame = true
        private var audioFromTimestampMs = 0L
        private var accepting = false
        private var closedVideoBytes = 0L

        @Volatile private var connection: Connection? = null
        @Volatile var started = false
            private set

        val isPublishing: Boolean
            get() = connection?.client?.isPublishing == true

        val videoBytesWritten: Long
            get() = synchronized(queueLock) {
                closedVideoBytes + (connection?.client?.videoBytesWritten?.get() ?: 0L)
            }

        fun connect() {
            val (currentWidth, currentHeight) = dimensions
            val next = Connection(RtmpClient(
                rtmpUrl = target.rtmpUrl,
                streamKey = target.streamKey,
                width = currentWidth,
                height = currentHeight,
                fps = fps,
                videoBitrateKbps = videoBitrateKbps
            ))
            synchronized(queueLock) {
                if (closed) return
                connection = next
            }
            try {
                next.client.connect()
                synchronized(queueLock) {
                    if (!closed) {
                        accepting = true
                        started = true
                        Log.i(TAG, "Conexão estabelecida com ${target.platformName}")
                        return
                    }
                }
            } catch (error: Exception) {
                disconnect(next)
                throw error
            }
            disconnect(next)
        }

        // Retorna true quando falta um quadro-chave para recuperar este destino.
        fun offer(sample: Sample): Boolean = synchronized(queueLock) {
            if (closed || !accepting) return false
            val oldest = queue.firstOrNull()
            val queueFull = queue.size >= MAX_QUEUED_PACKETS || sample.data.size > MAX_QUEUED_BYTES - queuedBytes
            val queueExpired = oldest != null && sample.queuedAtNs - oldest.queuedAtNs >= MAX_QUEUE_AGE_NS
            if (queueFull || queueExpired) {
                resetQueue()
            }

            if (sample.data.size > MAX_QUEUED_BYTES) return true
            if (waitingForKeyFrame) {
                if (sample !is Sample.Video) return queueFull || queueExpired
                if (!sample.keyFrame) return true
                waitingForKeyFrame = false
                audioFromTimestampMs = sample.timestampMs
            }

            if (sample is Sample.Audio && sample.timestampMs < audioFromTimestampMs) return false
            queue.addLast(sample)
            queuedBytes += sample.data.size
            queueLock.notify()
            false
        }

        private fun resetQueue() {
            queue.clear()
            queuedBytes = 0
            waitingForKeyFrame = true
        }

        fun sendUntilClosed() {
            var retryDelayMs = RECONNECT_DELAY_MS
            try {
                while (!closed) {
                    val current = connection
                    if (current != null) {
                        try {
                            send(current)
                        } catch (error: InterruptedException) {
                            throw error
                        } catch (error: Exception) {
                            if (!closed) Log.w(TAG, "Envio RTMP interrompido em ${target.platformName}", error)
                        } finally {
                            disconnect(current)
                        }
                    }
                    if (closed) break

                    Thread.sleep(retryDelayMs)
                    if (closed) break
                    try {
                        connect()
                        requestRecoveryKeyFrame()
                    } catch (error: Exception) {
                        if (!closed) Log.w(TAG, "Falha ao reconectar com ${target.platformName}", error)
                    }
                    // Uma conexão que abre e trava imediatamente também entra no recuo.
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun send(current: Connection) {
            val client = current.client
            while (!closed && client.isPublishing) {
                var expired = false
                val sample = synchronized(queueLock) {
                    if (queue.isEmpty() && !closed && accepting && client.isPublishing) {
                        queueLock.wait(MONITOR_INTERVAL_MS)
                    }
                    if (closed || !accepting || !client.isPublishing) return
                    val next = queue.removeFirstOrNull() ?: return@synchronized null
                    queuedBytes -= next.data.size
                    if (System.nanoTime() - next.queuedAtNs >= MAX_QUEUE_AGE_NS) {
                        resetQueue()
                        expired = true
                        return@synchronized null
                    }
                    current.writeStartedNs = System.nanoTime()
                    next
                }
                if (expired) requestRecoveryKeyFrame()
                if (sample == null) continue

                try {
                    val startedAtNs = System.nanoTime()
                    val (currentWidth, currentHeight) = dimensions
                    client.updateDimensions(currentWidth, currentHeight)
                    sendSample(current, sample, startedAtNs)
                } finally {
                    current.writeStartedNs = null
                }
            }
        }

        private fun sendSample(current: Connection, sample: Sample, startedAtNs: Long) {
            val client = current.client
            val base = current.baseTimestampMs ?: sample.timestampMs.also { current.baseTimestampMs = it }
            val timestampMs = (sample.timestampMs - base).coerceAtLeast(0L)
            when (sample) {
                is Sample.Video -> {
                    val header = sample.header
                    if (header != null && current.videoHeader !== header) {
                        client.sendVideoHeader(header.sps, header.pps, timestampMs)
                        current.videoHeader = header
                    }
                    val bytesBefore = client.videoBytesWritten.get()
                    client.sendVideoSample(sample.data, sample.keyFrame, timestampMs)
                    // O LAB conta cada quadro uma vez, mesmo quando vários destinos o recebem.
                    if (client.videoBytesWritten.get() > bytesBefore && sample.counted.compareAndSet(false, true)) {
                        val waitNs = (startedAtNs - sample.queuedAtNs).coerceAtLeast(0L)
                        sendWaitTotalNs.addAndGet(waitNs)
                        sendWaitMaxNs.updateAndGet { maxOf(it, waitNs) }
                        sentFrames.incrementAndGet()
                    }
                }

                is Sample.Audio -> {
                    val header = sample.header
                    if (header != null && current.audioHeader !== header) {
                        client.sendAudioHeader(header)
                        current.audioHeader = header
                    }
                    client.sendAudioSample(sample.data, timestampMs)
                }
            }
        }

        fun abortBlockedWrite() {
            val blocked = synchronized(queueLock) {
                val current = connection ?: return
                val startedAt = current.writeStartedNs ?: return
                if (!accepting || System.nanoTime() - startedAt < WRITE_TIMEOUT_NS) return
                accepting = false
                resetQueue()
                current.client
            }
            Log.w(TAG, "Escrita RTMP excedeu o limite em ${target.platformName}; reconectando")
            blocked.close()
        }

        private fun disconnect(current: Connection) {
            current.client.close()
            synchronized(queueLock) {
                if (connection === current) {
                    accepting = false
                    resetQueue()
                    closedVideoBytes += current.client.videoBytesWritten.get()
                    connection = null
                }
            }
        }

        fun close() {
            val client = synchronized(queueLock) {
                accepting = false
                resetQueue()
                queueLock.notifyAll()
                connection?.client
            }
            client?.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        active = false
        monitor.shutdownNow()
        destinations.forEach { it.close() }
        destinations.clear()
        threadPool.shutdownNow()
        Log.i(TAG, "StreamPublisher finalizado.")
    }
}
