package com.livenexus.controller

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Encoder H.264 local. Recebe frames por Surface e entrega amostras codificadas
 * ao consumidor; não abre conexões de rede nem armazena chaves.
 */
class LocalVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val onSample: (ByteArray, MediaCodec.BufferInfo) -> Unit,
    private val onFormat: (MediaFormat) -> Unit
) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile var lastError: String? = null
        private set

    val surface: Surface
        get() = input ?: throw IllegalStateException("Encoder não iniciado.")

    @Synchronized
    fun start() {
        check(codec == null) { "Encoder já iniciado." }
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "Dimensões H.264 devem ser positivas e pares."
        }
        require(fps == 30 || fps == 60) { "FPS deve ser 30 ou 60." }
        require(bitrateKbps in 2000..10000 && bitrateKbps % 500 == 0) {
            "Bitrate deve estar entre 2000 e 10000 Kbps, em passos de 500."
        }
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val createdSurface = created.createInputSurface()
            created.start()
            codec = created
            input = createdSurface
            running = true
            lastError = null
            worker = Thread({
                val info = MediaCodec.BufferInfo()
                try {
                    while (running) {
                        when (val index = created.dequeueOutputBuffer(info, 10000L)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(created.outputFormat)
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (index >= 0) {
                                try {
                                    val buffer: ByteBuffer? = created.getOutputBuffer(index)
                                    if (buffer != null && info.size > 0) {
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        val bytes = ByteArray(info.size)
                                        buffer.get(bytes)
                                        val copy = MediaCodec.BufferInfo().apply {
                                            set(0, bytes.size, info.presentationTimeUs, info.flags)
                                        }
                                        onSample(bytes, copy)
                                    }
                                } finally {
                                    created.releaseOutputBuffer(index, false)
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    lastError = error.message ?: "Falha no encoder H.264."
                    running = false
                }
            }, "LiveNexusH264Encoder").apply { start() }
        } catch (error: Exception) {
            running = false
            try { created.stop() } catch (_: Exception) {}
            created.release()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        running = false
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(1500L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        input?.release()
        input = null
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        codec = null
    }
}
