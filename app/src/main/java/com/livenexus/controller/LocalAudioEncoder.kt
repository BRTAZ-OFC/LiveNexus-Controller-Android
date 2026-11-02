package com.livenexus.controller

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer

/**
 * Capturador e codificador de áudio local.
 * Captura áudio do microfone com AudioRecord (PCM 16-bit) e codifica em AAC com MediaCodec.
 * Entrega os pacotes AAC prontos para multiplexação RTMP.
 */
class LocalAudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2,
    private val bitrateBps: Int = 128000,
    private val volumeProvider: () -> Float = { 1.0f },
    private val onHeader: (ByteArray) -> Unit,
    private val onSample: (ByteArray, MediaCodec.BufferInfo) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "LiveNexusAudio"
    }

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var headerDispatched = false
    @Volatile var lastError: String? = null
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        check(codec == null) { "AudioEncoder já iniciado." }

        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        var record: AudioRecord? = null
        try {
            val candidate = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
            } else {
                candidate.release()
                Log.w(TAG, "AudioRecord não inicializou; usando gerador PCM silencioso de fallback.")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Permissão/hardware de microfone indisponível; usando áudio silencioso AAC.", e)
        }
        audioRecord = record

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }

        val createdCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            createdCodec.start()
            codec = createdCodec
        } catch (e: Exception) {
            try { record?.release() } catch (_: Exception) {}
            audioRecord = null
            try { createdCodec.release() } catch (_: Exception) {}
            throw e
        }

        // Gera e envia imediatamente o AudioSpecificConfig padrão para AAC-LC (44100Hz stereo: 0x12, 0x10)
        val asc = createAudioSpecificConfig(sampleRate, channelCount)
        headerDispatched = true
        onHeader(asc)

        running = true
        try {
            record?.startRecording()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha em startRecording; mantendo trilha AAC silenciosa.", e)
            try { record?.release() } catch (_: Exception) {}
            audioRecord = null
            record = null
        }

        val activeRecord = record
        worker = Thread({
            encodeLoop(activeRecord, createdCodec, bufferSize)
        }, "LiveNexusAudioEncoder").apply { start() }

        Log.i(TAG, "MediaCodec AAC iniciado ($sampleRate Hz, $channelCount canais, mic=${activeRecord != null})")
    }

    private fun createAudioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
        val freqIndex = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 4
        }
        // AAC-LC = 2 (5 bits: 00010)
        // FreqIndex (4 bits)
        // Channels (4 bits)
        val byte1 = (2 shl 3) or (freqIndex ushr 1)
        val byte2 = ((freqIndex and 1) shl 7) or (channels shl 3)
        return byteArrayOf(byte1.toByte(), byte2.toByte())
    }

    private fun applyVolumeInPlace(pcmBuffer: ByteArray, length: Int, gain: Float) {
        if (gain <= 0.001f) {
            java.util.Arrays.fill(pcmBuffer, 0, length, 0.toByte())
            return
        }
        if (gain in 0.99f..1.01f) return
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBuffer[i].toInt() and 0xFF
            val hi = pcmBuffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcmBuffer[i] = (scaled and 0xFF).toByte()
            pcmBuffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun encodeLoop(record: AudioRecord?, mediaCodec: MediaCodec, bufferSize: Int) {
        val frameChunkBytes = 4096.coerceAtMost(bufferSize)
        val pcmBuffer = ByteArray(frameChunkBytes)
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = (channelCount * 2).coerceAtLeast(2)
        var totalFramesSubmitted = 0L

        try {
            while (running) {
                val readBytes = if (record != null) {
                    val r = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (r > 0) {
                        val gain = try { volumeProvider().coerceIn(0f, 1f) } catch (_: Exception) { 1f }
                        applyVolumeInPlace(pcmBuffer, r, gain)
                        r
                    } else {
                        java.util.Arrays.fill(pcmBuffer, 0.toByte())
                        try { Thread.sleep(20L) } catch (_: InterruptedException) { break }
                        pcmBuffer.size
                    }
                } else {
                    java.util.Arrays.fill(pcmBuffer, 0.toByte())
                    val sleepMs = ((pcmBuffer.size / bytesPerFrame) * 1000L / sampleRate).coerceAtLeast(15L)
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                    pcmBuffer.size
                }

                if (readBytes > 0 && running) {
                    val inputIndex = mediaCodec.dequeueInputBuffer(10000L)
                    if (inputIndex >= 0) {
                        val inputBuffer: ByteBuffer? = mediaCodec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val toWrite = minOf(readBytes, inputBuffer.remaining())
                            inputBuffer.clear()
                            inputBuffer.put(pcmBuffer, 0, toWrite)
                            val ptsUs = (totalFramesSubmitted * 1_000_000L) / sampleRate
                            totalFramesSubmitted += (toWrite / bytesPerFrame)
                            mediaCodec.queueInputBuffer(inputIndex, 0, toWrite, ptsUs, 0)
                        }
                    }
                }

                var outputIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
                while ((outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) && running) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outFormat = mediaCodec.outputFormat
                        val csd0 = outFormat.getByteBuffer("csd-0")
                        if (csd0 != null && csd0.remaining() >= 2) {
                            val dup = csd0.duplicate()
                            val cfg = ByteArray(dup.remaining())
                            dup.get(cfg)
                            headerDispatched = true
                            onHeader(cfg)
                        }
                        outputIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
                        continue
                    }

                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        if (info.size >= 2) {
                            val outBuf = mediaCodec.getOutputBuffer(outputIndex)
                            if (outBuf != null) {
                                val dup = outBuf.duplicate()
                                dup.position(info.offset)
                                dup.limit(info.offset + info.size)
                                val cfg = ByteArray(info.size)
                                dup.get(cfg)
                                headerDispatched = true
                                onHeader(cfg)
                            }
                        }
                        mediaCodec.releaseOutputBuffer(outputIndex, false)
                        outputIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
                        continue
                    }

                    val outBuf: ByteBuffer? = mediaCodec.getOutputBuffer(outputIndex)
                    if (outBuf != null && info.size > 0) {
                        val dup = outBuf.duplicate()
                        dup.position(info.offset)
                        dup.limit(info.offset + info.size)
                        val aacData = ByteArray(info.size)
                        dup.get(aacData)

                        val copyInfo = MediaCodec.BufferInfo().apply {
                            set(0, aacData.size, info.presentationTimeUs, info.flags)
                        }
                        onSample(aacData, copyInfo)
                    }

                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                    outputIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
                }
            }
        } catch (e: Exception) {
            if (running) {
                lastError = e.message ?: "Falha na captura/codificação de áudio"
                Log.w(TAG, "Erro no loop de áudio", e)
            }
        }
    }

    @Synchronized
    override fun close() {
        running = false
        val t = worker
        worker = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            try { t.join(1000L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        Log.i(TAG, "AudioEncoder encerrado.")
    }
}
