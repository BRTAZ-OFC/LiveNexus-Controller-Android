package com.livenexus.controller

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Encoder H.264 local por hardware via MediaCodec.
 * Recebe frames por Surface e entrega amostras codificadas e cabeçalhos SPS/PPS
 * para transmissão RTMP em tempo real.
 */
class LocalVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val onSample: (ByteArray, MediaCodec.BufferInfo) -> Unit,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSpsPps: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null
) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile var lastError: String? = null
        private set

    private var spsFound: ByteArray? = null
    private var ppsFound: ByteArray? = null

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
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFmt = created.outputFormat
                                onFormat(outFmt)
                                extractSpsPpsFromFormat(outFmt)
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (index >= 0) {
                                try {
                                    val buffer: ByteBuffer? = created.getOutputBuffer(index)
                                    if (buffer != null && info.size > 0) {
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        val bytes = ByteArray(info.size)
                                        buffer.get(bytes)

                                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                            extractSpsPpsFromBytes(bytes)
                                        } else {
                                            val copy = MediaCodec.BufferInfo().apply {
                                                set(0, bytes.size, info.presentationTimeUs, info.flags)
                                            }
                                            onSample(bytes, copy)
                                        }
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

    private fun extractSpsPpsFromFormat(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        if (csd0 != null && csd1 != null) {
            val sps = ByteArray(csd0.remaining())
            csd0.get(sps)
            csd0.rewind()
            val pps = ByteArray(csd1.remaining())
            csd1.get(pps)
            csd1.rewind()
            spsFound = cleanStartCode(sps)
            ppsFound = cleanStartCode(pps)
            onSpsPps?.invoke(spsFound!!, ppsFound!!)
        }
    }

    private fun extractSpsPpsFromBytes(data: ByteArray) {
        val spsList = mutableListOf<ByteArray>()
        var i = 0
        var lastStart = -1
        while (i < data.size - 3) {
            val isStart4 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isStart3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            if (isStart4 || isStart3) {
                val startCodeLen = if (isStart4) 4 else 3
                if (lastStart >= 0) {
                    val len = i - lastStart
                    if (len > 0) {
                        val nalu = ByteArray(len)
                        System.arraycopy(data, lastStart, nalu, 0, len)
                        spsList.add(nalu)
                    }
                }
                lastStart = i + startCodeLen
                i += startCodeLen
                continue
            }
            i++
        }
        if (lastStart >= 0 && lastStart < data.size) {
            val len = data.size - lastStart
            val nalu = ByteArray(len)
            System.arraycopy(data, lastStart, nalu, 0, len)
            spsList.add(nalu)
        }

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nalu in spsList) {
            val type = nalu[0].toInt() and 0x1F
            if (type == 7) sps = nalu
            if (type == 8) pps = nalu
        }
        if (sps != null && pps != null) {
            spsFound = sps
            ppsFound = pps
            onSpsPps?.invoke(sps, pps)
        }
    }

    private fun cleanStartCode(bytes: ByteArray): ByteArray {
        var start = 0
        if (bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) {
            start = 4
        } else if (bytes.size > 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) {
            start = 3
        }
        if (start == 0) return bytes
        val clean = ByteArray(bytes.size - start)
        System.arraycopy(bytes, start, clean, 0, clean.size)
        return clean
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
