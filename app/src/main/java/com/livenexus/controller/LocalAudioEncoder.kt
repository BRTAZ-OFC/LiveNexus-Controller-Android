package com.livenexus.controller

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Capturador e codificador de áudio local com Supressão de Ruídos (Hardware + DSP).
 * Captura áudio do microfone com AudioRecord (PCM 16-bit), aplica supressão de ruído,
 * cancelamento de eco e filtro passa-alta + noise gate suave, e codifica em AAC com MediaCodec.
 */
class LocalAudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2,
    private val bitrateBps: Int = 128000,
    private val volumeProvider: () -> Float = { 1.0f },
    private val noiseSuppressionProvider: () -> Boolean = { true },
    private val onHeader: (ByteArray) -> Unit,
    private val onSample: (ByteArray, MediaCodec.BufferInfo) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "LiveNexusAudio"
        // Limiar do Noise Gate suave (~-38 dBFS em PCM 16-bit)
        private const val NOISE_GATE_THRESHOLD = 420f
        private const val NOISE_GATE_FLOOR_GAIN = 0.08f
        // Coeficiente do filtro passa-alta (~85 Hz em 44.1 kHz) contra graves de vento/rede elétrica
        private const val HPF_ALPHA = 0.988f
    }

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var autoGainControl: AutomaticGainControl? = null
    private var codec: MediaCodec? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var headerDispatched = false
    @Volatile var lastError: String? = null
        private set

    // Estados contínuos do DSP de áudio (filtro passa-alta por canal e envelope do noise gate)
    private var hpfPrevInL = 0f
    private var hpfPrevOutL = 0f
    private var hpfPrevInR = 0f
    private var hpfPrevOutR = 0f
    private var gateEnvelopeGain = 1.0f

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        check(codec == null) { "AudioEncoder já iniciado." }

        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        var record: AudioRecord? = null
        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sourcesToTry) {
            try {
                val candidate = AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    Log.i(TAG, "AudioRecord inicializado com fonte=$source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Falha ao tentar AudioSource $source: ${e.message}")
            }
        }
        if (record == null) {
            Log.w(TAG, "AudioRecord não inicializou; usando gerador PCM silencioso de fallback.")
        }
        audioRecord = record

        if (record != null) {
            attachHardwareAudioEffects(record.audioSessionId)
        }

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
            releaseAudioEffects()
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
            releaseAudioEffects()
            try { record?.release() } catch (_: Exception) {}
            audioRecord = null
            record = null
        }

        val activeRecord = record
        worker = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            encodeLoop(activeRecord, createdCodec, bufferSize)
        }, "LiveNexusAudioEncoder").apply { start() }

        Log.i(TAG, "MediaCodec AAC iniciado ($sampleRate Hz, $channelCount canais, mic=${activeRecord != null})")
    }

    private fun attachHardwareAudioEffects(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        val initialNs = try { noiseSuppressionProvider() } catch (_: Exception) { true }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = initialNs
                }
                Log.i(TAG, "NoiseSuppressor de hardware ativado: ${noiseSuppressor?.enabled == true}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "NoiseSuppressor de hardware indisponível: ${e.message}")
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = initialNs
                }
                Log.i(TAG, "AcousticEchoCanceler de hardware ativado: ${echoCanceler?.enabled == true}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AcousticEchoCanceler indisponível: ${e.message}")
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                autoGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = initialNs
                }
            }
        } catch (_: Throwable) {}
    }

    private fun releaseAudioEffects() {
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        noiseSuppressor = null
        try { echoCanceler?.release() } catch (_: Throwable) {}
        echoCanceler = null
        try { autoGainControl?.release() } catch (_: Throwable) {}
        autoGainControl = null
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
        val byte1 = (2 shl 3) or (freqIndex ushr 1)
        val byte2 = ((freqIndex and 1) shl 7) or (channels shl 3)
        return byteArrayOf(byte1.toByte(), byte2.toByte())
    }

    /**
     * Processa o buffer PCM 16-bit in-place aplicando:
     * 1. Filtro Passa-Alta (HPF ~85Hz) para cortar ruído grave, sopro e zumbido elétrico.
     * 2. Expander / Noise Gate suave baseado em RMS para eliminar chiado de fundo em silêncio.
     * 3. Controle de volume e limitador suave (anti-clipping).
     */
    private fun processAudioDspInPlace(
        pcmBuffer: ByteArray,
        length: Int,
        userGain: Float,
        suppressNoise: Boolean
    ) {
        if (userGain <= 0.001f) {
            java.util.Arrays.fill(pcmBuffer, 0, length, 0.toByte())
            return
        }

        try {
            val ns = noiseSuppressor
            if (ns != null && ns.enabled != suppressNoise) {
                ns.enabled = suppressNoise
            }
            val aec = echoCanceler
            if (aec != null && aec.enabled != suppressNoise) {
                aec.enabled = suppressNoise
            }
        } catch (_: Throwable) {}

        val sampleCount = length / 2
        if (sampleCount <= 0) return

        // Se a supressão de ruído estiver ativa, calcula o RMS da janela para o Noise Gate suave
        val targetGateGain = if (suppressNoise) {
            var sumSq = 0.0
            var idx = 0
            while (idx + 1 < length) {
                val lo = pcmBuffer[idx].toInt() and 0xFF
                val hi = pcmBuffer[idx + 1].toInt()
                val s = ((hi shl 8) or lo).toDouble()
                sumSq += s * s
                idx += 2
            }
            val rms = sqrt(sumSq / sampleCount.toDouble()).toFloat()
            when {
                rms >= NOISE_GATE_THRESHOLD * 1.6f -> 1.0f
                rms <= NOISE_GATE_THRESHOLD * 0.65f -> NOISE_GATE_FLOOR_GAIN
                else -> {
                    val ratio = (rms - NOISE_GATE_THRESHOLD * 0.65f) / (NOISE_GATE_THRESHOLD * 0.95f)
                    NOISE_GATE_FLOOR_GAIN + ratio.coerceIn(0f, 1f) * (1.0f - NOISE_GATE_FLOOR_GAIN)
                }
            }
        } else {
            1.0f
        }

        // Suavização temporal de ataque rápido e release suave para nunca cortar sílabas da fala
        val smoothing = if (targetGateGain > gateEnvelopeGain) 0.45f else 0.12f
        gateEnvelopeGain += (targetGateGain - gateEnvelopeGain) * smoothing
        val effectiveGain = userGain * (if (suppressNoise) gateEnvelopeGain else 1.0f)

        if (!suppressNoise && abs(effectiveGain - 1.0f) < 0.01f) return

        var i = 0
        var ch = 0
        while (i + 1 < length) {
            val lo = pcmBuffer[i].toInt() and 0xFF
            val hi = pcmBuffer[i + 1].toInt()
            var sample = ((hi shl 8) or lo).toFloat()

            if (suppressNoise) {
                if (ch == 0 || channelCount == 1) {
                    val filtered = HPF_ALPHA * (hpfPrevOutL + sample - hpfPrevInL)
                    hpfPrevInL = sample
                    hpfPrevOutL = filtered
                    sample = filtered
                } else {
                    val filtered = HPF_ALPHA * (hpfPrevOutR + sample - hpfPrevInR)
                    hpfPrevInR = sample
                    hpfPrevOutR = filtered
                    sample = filtered
                }
                ch = (ch + 1) % channelCount
            }

            var scaled = sample * effectiveGain
            // Soft-clipping nos extremos para evitar distorção estourada
            if (scaled > 28000f) {
                scaled = 28000f + (scaled - 28000f) * 0.25f
            } else if (scaled < -28000f) {
                scaled = -28000f + (scaled + 28000f) * 0.25f
            }
            val outInt = scaled.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcmBuffer[i] = (outInt and 0xFF).toByte()
            pcmBuffer[i + 1] = ((outInt shr 8) and 0xFF).toByte()
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
                        val suppressNoise = try { noiseSuppressionProvider() } catch (_: Exception) { true }
                        processAudioDspInPlace(pcmBuffer, r, gain, suppressNoise)
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

        releaseAudioEffects()

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
