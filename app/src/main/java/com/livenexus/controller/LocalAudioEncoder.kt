package com.livenexus.controller

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Capturador e codificador de áudio local com mixagem de Microfone e Áudio Interno (Jogo/Sistema).
 * - Microfone: AudioRecord (PCM 16-bit) com ganho manual e supressão de ruído do Android ativada.
 * - Áudio Interno: AudioPlaybackCaptureConfiguration via MediaProjection (Android 10+ / API 29+).
 * - Mixagem em tempo real com controle de volume independente e codificação AAC via MediaCodec.
 */
class LocalAudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2,
    private val bitrateBps: Int = 128000,
    private val micVolumeProvider: () -> Float = { 1.0f },
    private val internalVolumeProvider: () -> Float = { 1.0f },
    private val noiseSuppressionProvider: () -> Boolean = { true },
    private val mediaProjectionProvider: (() -> MediaProjection?)? = null,
    private val onHeader: (ByteArray) -> Unit,
    private val onSample: (ByteArray, MediaCodec.BufferInfo) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "LiveNexusAudio"
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var internalRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var autoGainControl: AutomaticGainControl? = null
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

        // 1. Inicializa AudioRecord do Microfone
        var record: AudioRecord? = null
        // Prioriza o microfone normal com supressor de hardware.
        // Sem supressor nativo, tenta o pipeline de comunicação do Android.
        val hardwareNsAvailable = try { NoiseSuppressor.isAvailable() } catch (_: Throwable) { false }
        val sourcesToTry = if (hardwareNsAvailable) {
            intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        } else {
            Log.w(TAG, "NoiseSuppressor indisponível: tentando VOICE_COMMUNICATION")
            intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
        }
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
                    Log.i(TAG, "AudioRecord (mic) inicializado com fonte=$source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Falha ao tentar AudioSource $source: ${e.message}")
            }
        }
        audioRecord = record

        if (record != null) {
            attachHardwareAudioEffects(record.audioSessionId)
        }

        // 2. Inicializa AudioRecord de Áudio Interno (Android 10+)
        initInternalAudioRecord(bufferSize, channelConfig, audioFormat)

        // 3. Inicializa MediaCodec AAC
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }

        // No AOSP, os encoders AAC de software C2/OMX.google usam FDK-AAC.
        // Preferir o componente fornecido pelo Android, sem incorporar bibliotecas
        // com licenças de patentes externas. Não altera AAC/RTMP nem o vídeo.
        val preferredCodec = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder &&
                    (info.name == "c2.android.aac.encoder" || info.name == "OMX.google.aac.encoder") &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
            }?.name
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível consultar codecs AAC instalados", error)
            null
        }
        var createdCodec: MediaCodec? = null
        var codecError: Exception? = null
        for (codecName in listOfNotNull(preferredCodec) + listOf<String?>(null)) {
            var candidate: MediaCodec? = null
            try {
                candidate = if (codecName == null) {
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                } else {
                    MediaCodec.createByCodecName(codecName)
                }
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                candidate.start()
                createdCodec = candidate
                Log.i(TAG, "Encoder AAC em uso: ${candidate.name}")
                break
            } catch (error: Exception) {
                codecError = error
                Log.w(TAG, "Falha ao abrir encoder AAC ${codecName ?: "padrão"}, tentando alternativa", error)
                try { candidate?.release() } catch (_: Exception) {}
            }
        }
        val readyCodec = createdCodec ?: run {
            releaseAudioEffects()
            try { record?.release() } catch (_: Exception) {}
            audioRecord = null
            try { internalRecord?.release() } catch (_: Exception) {}
            internalRecord = null
            throw IllegalStateException("Nenhum encoder AAC disponível", codecError)
        }
        codec = readyCodec

        // Gera e despacha o cabeçalho ASC para RTMP imediatamente
        val asc = createAudioSpecificConfig(sampleRate, channelCount)
        headerDispatched = true
        onHeader(asc)

        running = true
        try {
            record?.startRecording()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha em startRecording do mic; prosseguindo sem microfone.", e)
            releaseAudioEffects()
            try { record?.release() } catch (_: Exception) {}
            audioRecord = null
            record = null
        }

        try {
            internalRecord?.startRecording()
        } catch (e: Throwable) {
            Log.w(TAG, "Falha em startRecording do áudio interno.", e)
            try { internalRecord?.release() } catch (_: Exception) {}
            internalRecord = null
        }

        val activeRecord = record
        worker = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            encodeLoop(activeRecord, readyCodec, bufferSize)
        }, "LiveNexusAudioEncoder").apply { start() }

        Log.i(TAG, "MediaCodec AAC iniciado ($sampleRate Hz, $channelCount canais, mic=${activeRecord != null}, interno=${internalRecord != null})")
    }

    @SuppressLint("MissingPermission")
    private fun initInternalAudioRecord(bufferSize: Int, channelConfig: Int, audioFormat: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = try { mediaProjectionProvider?.invoke() } catch (_: Throwable) { null }
            if (projection != null) {
                try {
                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val candidate = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        internalRecord = candidate
                        Log.i(TAG, "AudioPlaybackCapture (áudio interno) inicializado com sucesso!")
                    } else {
                        candidate.release()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Falha ao inicializar AudioPlaybackCapture: ${e.message}")
                }
            }
        }
    }

    /**
     * Atualiza a captura de áudio interno se o MediaProjection foi ativado após o início da live.
     */
    @SuppressLint("MissingPermission")
    fun updateMediaProjection(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && internalRecord == null && running) {
            try {
                val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)
                val bufferSize = maxOf(minBufferSize * 2, 4096)

                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val candidate = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    candidate.startRecording()
                    internalRecord = candidate
                    Log.i(TAG, "AudioPlaybackCapture conectado via updateMediaProjection!")
                } else {
                    candidate.release()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Erro ao conectar AudioPlaybackCapture: ${e.message}")
            }
        }
    }

    private fun attachHardwareAudioEffects(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        val initialNs = try { noiseSuppressionProvider() } catch (_: Exception) { true }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = initialNs && !SpeexNoiseSuppressor.available
                }
                Log.i(TAG, "NoiseSuppressor de hardware ativo: ${noiseSuppressor?.enabled == true}")
                if (noiseSuppressor == null) Log.w(TAG, "Supressor não criado para a sessão de áudio.")
            } else {
                Log.w(TAG, "Supressão de ruído não implementada pelo dispositivo.")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "NoiseSuppressor de hardware indisponível: ${e.message}")
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    // Streaming não é chamada telefônica: evita tratamento de voz artificial.
                    enabled = false
                }
                Log.i(TAG, "AcousticEchoCanceler de hardware ativado: ${echoCanceler?.enabled == true}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AcousticEchoCanceler indisponível: ${e.message}")
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                autoGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    // Mantém o ganho manual do usuário, sem compressor automático do dispositivo.
                    enabled = false
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
     * Preserva a dinâmica natural do microfone. Evita combinar filtros agressivos
     * (AEC, AGC, gate e passa-alta) que podem deixar a voz abafada ou intermitente.
     * A supressão de hardware é opcional; o ganho permanece manual.
     */
    private fun processMicDspInPlace(
        pcmBuffer: ByteArray,
        length: Int,
        userGain: Float,
        suppressNoise: Boolean,
        speex: SpeexNoiseSuppressor?
    ) {
        val speexProcessed = suppressNoise && (speex?.process(pcmBuffer, length) == true)
        // Nunca empilhar dois supressores, para evitar voz robótica.
        val hardwareEnabled = suppressNoise && !speexProcessed
        try {
            noiseSuppressor?.let { ns ->
                if (ns.enabled != hardwareEnabled) ns.enabled = hardwareEnabled
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Não foi possível alternar a supressão de ruído", error)
        }
        applyGainInPlace(pcmBuffer, length, userGain)
    }

    private fun applyGainInPlace(pcmBuffer: ByteArray, length: Int, gain: Float) {
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
        val frameChunkBytes = (sampleRate / 50 * channelCount * 2).coerceAtMost(bufferSize)
        val micBuffer = ByteArray(frameChunkBytes)
        val internalBuffer = ByteArray(frameChunkBytes)
        val mixedBuffer = ByteArray(frameChunkBytes)
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = (channelCount * 2).coerceAtLeast(2)
        var totalFramesSubmitted = 0L
        val speex = SpeexNoiseSuppressor.create(sampleRate, channelCount, sampleRate / 50)
        if (speex != null) Log.i(TAG, "SpeexDSP ativo para supressão de ruído do microfone")
        else Log.i(TAG, "SpeexDSP indisponível; fallback para NoiseSuppressor do Android")

        try {
            while (running) {
                val micGain = try { micVolumeProvider().coerceIn(0f, 1f) } catch (_: Exception) { 1f }
                val internalGain = try { internalVolumeProvider().coerceIn(0f, 1f) } catch (_: Exception) { 1f }
                val suppressNoise = try { noiseSuppressionProvider() } catch (_: Exception) { true }

                // Leitura do Microfone
                val micRead = if (record != null && micGain > 0.001f) {
                    val r = record.read(micBuffer, 0, micBuffer.size)
                    if (r > 0) {
                        processMicDspInPlace(micBuffer, r, micGain, suppressNoise, speex)
                        r
                    } else 0
                } else 0

                // Leitura do Áudio Interno (Jogo/Sistema)
                val intRec = internalRecord
                val internalRead = if (intRec != null && internalGain > 0.001f) {
                    val r = intRec.read(internalBuffer, 0, internalBuffer.size)
                    if (r > 0) {
                        applyGainInPlace(internalBuffer, r, internalGain)
                        r
                    } else 0
                } else 0

                // Mixagem dos dois fluxos PCM 16-bit
                val maxRead = maxOf(micRead, internalRead)
                val toWrite: Int

                if (maxRead > 0) {
                    toWrite = maxRead
                    var idx = 0
                    while (idx + 1 < maxRead) {
                        val sMic = if (idx + 1 < micRead) {
                            val lo = micBuffer[idx].toInt() and 0xFF
                            val hi = micBuffer[idx + 1].toInt()
                            (hi shl 8) or lo
                        } else 0

                        val sInt = if (idx + 1 < internalRead) {
                            val lo = internalBuffer[idx].toInt() and 0xFF
                            val hi = internalBuffer[idx + 1].toInt()
                            (hi shl 8) or lo
                        } else 0

                        var mixed = sMic + sInt
                        // Limitador suave para evitar clipping estourado
                        if (mixed > 30000) {
                            mixed = 30000 + ((mixed - 30000) * 0.2f).toInt()
                        } else if (mixed < -30000) {
                            mixed = -30000 + ((mixed + 30000) * 0.2f).toInt()
                        }
                        val finalSample = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        mixedBuffer[idx] = (finalSample and 0xFF).toByte()
                        mixedBuffer[idx + 1] = ((finalSample shr 8) and 0xFF).toByte()
                        idx += 2
                    }
                } else {
                    // Trilha silenciosa quando ambos estão mutados ou indisponíveis
                    java.util.Arrays.fill(mixedBuffer, 0.toByte())
                    val sleepMs = ((mixedBuffer.size / bytesPerFrame) * 1000L / sampleRate).coerceAtLeast(15L)
                    try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                    toWrite = mixedBuffer.size
                }

                if (toWrite > 0 && running) {
                    val inputIndex = mediaCodec.dequeueInputBuffer(10000L)
                    if (inputIndex >= 0) {
                        val inputBuffer: ByteBuffer? = mediaCodec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val len = minOf(toWrite, inputBuffer.remaining())
                            inputBuffer.clear()
                            inputBuffer.put(mixedBuffer, 0, len)
                            val ptsUs = (totalFramesSubmitted * 1_000_000L) / sampleRate
                            totalFramesSubmitted += (len / bytesPerFrame)
                            mediaCodec.queueInputBuffer(inputIndex, 0, len, ptsUs, 0)
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
        } finally {
            speex?.close()
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
            internalRecord?.stop()
        } catch (_: Exception) {}
        try {
            internalRecord?.release()
        } catch (_: Exception) {}
        internalRecord = null

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
