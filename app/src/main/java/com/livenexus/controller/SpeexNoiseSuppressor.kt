package com.livenexus.controller

import android.util.Log

/**
 * SpeexDSP local no APK: supressão de ruído somente no microfone,
 * antes de misturar com o áudio interno e codificar AAC.
 * Frame de 20 ms; mantém o volume manual e não aplica AGC.
 */
internal class SpeexNoiseSuppressor private constructor(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val samplesPerFrame: Int
) : AutoCloseable {
    companion object {
        private const val TAG = "LiveNexusSpeex"
        val available: Boolean by lazy {
            try {
                System.loadLibrary("livenexus_speex")
                true
            } catch (error: UnsatisfiedLinkError) {
                Log.w(TAG, "SpeexDSP nativo indisponível, usando supressor Android", error)
                false
            } catch (error: SecurityException) {
                Log.w(TAG, "Biblioteca SpeexDSP bloqueada", error)
                false
            }
        }

        fun create(sampleRate: Int, channelCount: Int, samplesPerFrame: Int): SpeexNoiseSuppressor? {
            if (!available) return null
            return try {
                SpeexNoiseSuppressor(sampleRate, channelCount, samplesPerFrame).takeIf { it.handle != 0L }
            } catch (error: RuntimeException) {
                Log.w(TAG, "Não foi possível iniciar o SpeexDSP", error)
                null
            }
        }
    }

    private var handle: Long = nativeCreate(sampleRate, channelCount, samplesPerFrame)

    fun process(pcm: ByteArray, length: Int): Boolean {
        if (handle == 0L || length != samplesPerFrame * channelCount * 2) return false
        return try {
            nativeProcess(handle, pcm, length)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Falha no processamento SpeexDSP", error)
            false
        }
    }

    override fun close() {
        val old = handle
        handle = 0L
        if (old != 0L) nativeDestroy(old)
    }

    private external fun nativeCreate(rate: Int, channels: Int, frameSamples: Int): Long
    private external fun nativeProcess(ptr: Long, pcm: ByteArray, length: Int): Boolean
    private external fun nativeDestroy(ptr: Long)
}
