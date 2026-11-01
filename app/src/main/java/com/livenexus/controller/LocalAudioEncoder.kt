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
    @Volatile var lastError: String? = null
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        check(codec == null) { "AudioEncoder já iniciado." }

        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Falha ao inicializar AudioRecord do microfone.")
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
            record.release()
            audioRecord = null
            createdCodec.release()
            throw e
        }

        // Gera o AudioSpecificConfig padrão para AAC-LC (44100Hz stereo: 0x12, 0x10)
        val asc = createAudioSpecificConfig(sampleRate, channelCount)
        onHeader(asc)

        running = true
        record.startRecording()

        worker = Thread({
            encodeLoop(record, createdCodec, bufferSize)
        }, "LiveNexusAudioEncoder").apply { start() }

        Log.i(TAG, "AudioRecord e MediaCodec AAC iniciados com sucesso ($sampleRate Hz, $channelCount canais)")
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

    private fun encodeLoop(record: AudioRecord, mediaCodec: MediaCodec, bufferSize: Int) {
        val pcmBuffer = ByteArray(bufferSize)
        val info = MediaCodec.BufferInfo()

        try {
            while (running) {
                val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (readBytes > 0) {
                    val inputIndex = mediaCodec.dequeueInputBuffer(10000L)
                    if (inputIndex >= 0) {
                        val inputBuffer: ByteBuffer? = mediaCodec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(pcmBuffer, 0, readBytes)
                            val ptsUs = System.nanoTime() / 1000L
                            mediaCodec.queueInputBuffer(inputIndex, 0, readBytes, ptsUs, 0)
                        }
                    }
                }

                var outputIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
                while (outputIndex >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Configuração do codec já enviada
                        mediaCodec.releaseOutputBuffer(outputIndex, false)
                        outputIndex = mediaCodec.dequeueOutputBuffer(info, 0L)
                        continue
                    }

                    val outBuf: ByteBuffer? = mediaCodec.getOutputBuffer(outputIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val aacData = ByteArray(info.size)
                        outBuf.get(aacData)

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
            lastError = e.message ?: "Falha na captura/codificação de áudio"
            Log.w(TAG, "Erro no loop de áudio", e)
        }
    }

    @Synchronized
    override fun close() {
        running = false
        val t = worker
        worker = null
        if (t != null && t !== Thread.currentThread()) {
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
