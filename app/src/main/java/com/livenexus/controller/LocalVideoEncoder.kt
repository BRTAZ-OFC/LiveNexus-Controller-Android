package com.livenexus.controller

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Encoder H.264 local por hardware via MediaCodec.
 * Recebe frames por Surface e entrega amostras codificadas e cabeçalhos SPS/PPS
 * para transmissão RTMP/RTMPS em tempo real.
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

    companion object {
        private const val TAG = "LiveNexusVideo"
        private const val KEYFRAME_INTERVAL_SECONDS = 2
        private const val SYNC_FRAME_INTERVAL_MS = 2000L
    }

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

    private fun buildFormat(includeAdvancedFlags: Boolean): MediaFormat {
        val repeatUs = (1_000_000L / fps.coerceAtLeast(1)).coerceAtLeast(16_666L)
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatUs)
            if (includeAdvancedFlags) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
        }
    }

    @Synchronized
    fun start() {
        check(codec == null) { "Encoder já iniciado." }
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "Dimensões H.264 devem ser positivas e pares."
        }
        require(fps == 30 || fps == 60) { "FPS deve ser 30 ou 60." }
        require(bitrateKbps in 1000..15000) {
            "Bitrate deve estar entre 1000 e 15000 Kbps."
        }

        var created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            try {
                created.configure(buildFormat(includeAdvancedFlags = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (configErr: Exception) {
                Log.w(TAG, "Fallback para formato H.264 básico: ${configErr.message}")
                try { created.release() } catch (_: Exception) {}
                created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                created.configure(buildFormat(includeAdvancedFlags = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            val createdSurface = created.createInputSurface()
            created.start()
            codec = created
            input = createdSurface
            running = true
            lastError = null

            val activeCodec = created
            worker = Thread({
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (_: Exception) {}
                val info = MediaCodec.BufferInfo()
                var emittedFrames = 0L
                try {
                    while (running) {
                        when (val index = activeCodec.dequeueOutputBuffer(info, 10000L)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFmt = activeCodec.outputFormat
                                onFormat(outFmt)
                                extractSpsPpsFromFormat(outFmt)
                            }
                            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                            else -> if (index >= 0) {
                                try {
                                    val buffer: ByteBuffer? = activeCodec.getOutputBuffer(index)
                                    if (buffer != null && info.size > 0) {
                                        buffer.position(info.offset)
                                        buffer.limit(info.offset + info.size)
                                        val bytes = ByteArray(info.size)
                                        buffer.get(bytes)

                                        // Sempre verifica SPS/PPS (tanto em CODEC_CONFIG quanto inline em keyframes)
                                        extractSpsPpsFromBytes(bytes)

                                        val nalus = splitAnnexBNalus(bytes)
                                        var hasVclNalu = false
                                        var hasIdrNalu = false
                                        for (nalu in nalus) {
                                            if (nalu.isEmpty()) continue
                                            val nalType = nalu[0].toInt() and 0x1F
                                            if (nalType in 1..5) {
                                                hasVclNalu = true
                                                if (nalType == 5) hasIdrNalu = true
                                            }
                                        }

                                        val isConfigOnly = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && !hasVclNalu
                                        if (!isConfigOnly && hasVclNalu) {
                                            var sampleFlags = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv()
                                            if (hasIdrNalu) {
                                                sampleFlags = sampleFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                                            }
                                            val copy = MediaCodec.BufferInfo().apply {
                                                set(0, bytes.size, info.presentationTimeUs, sampleFlags)
                                            }
                                            emittedFrames++
                                            if (emittedFrames == 1L) {
                                                Log.i(TAG, "Primeiro frame H.264 gerado (${bytes.size} bytes, keyframe=${(sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0})")
                                            }
                                            onSample(bytes, copy)
                                        }
                                    }
                                } finally {
                                    activeCodec.releaseOutputBuffer(index, false)
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (running) {
                        lastError = error.message ?: "Falha no encoder H.264."
                        Log.e(TAG, "Erro no loop do LocalVideoEncoder", error)
                        running = false
                    }
                }
            }, "LiveNexusH264Encoder").apply { start() }

            Log.i(TAG, "LocalVideoEncoder iniciado: ${width}x${height} @ ${fps}fps, ${bitrateKbps}kbps")
        } catch (error: Exception) {
            running = false
            try { created.stop() } catch (_: Exception) {}
            try { created.release() } catch (_: Exception) {}
            throw error
        }
    }

    fun requestKeyFrame() {
        val active = codec ?: return
        requestKeyFrameInternal(active)
    }

    private fun requestKeyFrameInternal(activeCodec: MediaCodec) {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            activeCodec.setParameters(params)
        } catch (_: Exception) {}
    }

    fun updateBitrate(bitrateBps: Int) {
        val activeCodec = codec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps.coerceIn(1_000_000, 15_000_000))
            }
            activeCodec.setParameters(params)
        } catch (_: Exception) {}
    }

    private fun extractSpsPpsFromFormat(format: MediaFormat) {
        val candidateNalus = mutableListOf<ByteArray>()
        val csd0 = format.getByteBuffer("csd-0")?.duplicate()
        val csd1 = format.getByteBuffer("csd-1")?.duplicate()

        if (csd0 != null && csd0.remaining() > 0) {
            val bytes0 = ByteArray(csd0.remaining())
            csd0.get(bytes0)
            candidateNalus.addAll(splitAnnexBNalus(bytes0))
        }
        if (csd1 != null && csd1.remaining() > 0) {
            val bytes1 = ByteArray(csd1.remaining())
            csd1.get(bytes1)
            candidateNalus.addAll(splitAnnexBNalus(bytes1))
        }

        updateSpsPpsFromNalus(candidateNalus)
    }

    private fun extractSpsPpsFromBytes(data: ByteArray) {
        val nalus = splitAnnexBNalus(data)
        updateSpsPpsFromNalus(nalus)
    }

    private fun updateSpsPpsFromNalus(nalus: List<ByteArray>) {
        var newSps: ByteArray? = null
        var newPps: ByteArray? = null
        for (nalu in nalus) {
            if (nalu.isEmpty()) continue
            val type = nalu[0].toInt() and 0x1F
            if (type == 7) newSps = nalu
            else if (type == 8) newPps = nalu
        }
        val finalSps = newSps ?: spsFound
        val finalPps = newPps ?: ppsFound
        if (finalSps != null && finalPps != null) {
            val changed = spsFound == null || ppsFound == null ||
                !finalSps.contentEquals(spsFound) || !finalPps.contentEquals(ppsFound)
            spsFound = finalSps
            ppsFound = finalPps
            if (changed) {
                Log.i(TAG, "SPS (${finalSps.size}B) e PPS (${finalPps.size}B) extraídos com sucesso.")
                onSpsPps?.invoke(finalSps, finalPps)
            }
        }
    }

    private fun splitAnnexBNalus(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var i = 0
        var lastStart = -1

        while (i <= data.size - 3) {
            val isStart4 = (i <= data.size - 4) &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isStart3 = !isStart4 &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()

            if (isStart4 || isStart3) {
                val startCodeLen = if (isStart4) 4 else 3
                if (lastStart >= 0) {
                    val len = i - lastStart
                    if (len > 0) {
                        result.add(data.copyOfRange(lastStart, i))
                    }
                }
                lastStart = i + startCodeLen
                i += startCodeLen
                continue
            }
            i++
        }

        if (lastStart >= 0 && lastStart < data.size) {
            result.add(data.copyOfRange(lastStart, data.size))
        } else if (lastStart == -1 && data.isNotEmpty()) {
            result.add(data)
        }
        return result
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
        try { input?.release() } catch (_: Exception) {}
        input = null
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
    }
}
