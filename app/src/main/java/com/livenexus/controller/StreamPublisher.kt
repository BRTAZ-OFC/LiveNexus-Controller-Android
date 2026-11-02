package com.livenexus.controller

import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Gerenciador de distribuição de pacotes de transmissão ao vivo.
 * Conecta e publica fluxos RTMP/RTMPS sincronizados para YouTube, Twitch e Kick.
 */
class StreamPublisher(
    private val targets: List<StreamTarget>,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val videoBitrateKbps: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "LiveNexusPublisher"
    }

    data class StreamTarget(
        val platformName: String,
        val rtmpUrl: String,
        val streamKey: String
    )

    private val clients = CopyOnWriteArrayList<Pair<StreamTarget, RtmpClient>>()
    private val threadPool = Executors.newCachedThreadPool()
    private val sendExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var streamMediaStartMs: Long = -1L
    @Volatile private var baseVideoTimestampUs: Long = -1L
    @Volatile private var baseVideoOffsetMs: Long = 0L
    @Volatile private var lastVideoTimestampMs: Long = 0L

    @Volatile private var baseAudioTimestampUs: Long = -1L
    @Volatile private var baseAudioOffsetMs: Long = 0L
    @Volatile private var lastAudioTimestampMs: Long = 0L

    @Volatile private var active = false

    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null
    @Volatile private var cachedAsc: ByteArray? = null

    val connectedPlatforms: List<String>
        get() = clients.filter { it.second.isPublishing }.map { it.first.platformName }

    val hasActivePublishing: Boolean
        get() = clients.any { it.second.isPublishing }

    /**
     * Inicia a conexão com todas as plataformas configuradas em paralelo.
     * Retorna a lista de erros por plataforma caso alguma falhe.
     */
    fun start(): Map<String, String> {
        val errors = mutableMapOf<String, String>()
        val connectionTasks = mutableListOf<Pair<StreamTarget, Future<*>>>()

        for (target in targets) {
            val client = RtmpClient(
                rtmpUrl = target.rtmpUrl,
                streamKey = target.streamKey,
                width = width,
                height = height,
                fps = fps,
                videoBitrateKbps = videoBitrateKbps
            )
            val future = threadPool.submit {
                try {
                    client.connect()
                    clients.add(Pair(target, client))
                    Log.i(TAG, "Conexão estabelecida com ${target.platformName}")
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao conectar com ${target.platformName}", e)
                    client.close()
                    synchronized(errors) {
                        errors[target.platformName] = e.message ?: "Erro de conexão RTMP"
                    }
                }
            }
            connectionTasks.add(Pair(target, future))
        }

        // Aguarda conclusões
        for ((target, future) in connectionTasks) {
            try {
                future.get()
            } catch (e: Exception) {
                errors[target.platformName] = e.cause?.message ?: (e.message ?: "Falha ao conectar")
            }
        }

        streamMediaStartMs = -1L
        active = clients.any { it.second.isPublishing }
        return errors
    }

    /**
     * Atualiza as dimensões de vídeo anunciadas nos clientes RTMP ativos (ex: ao trocar entre 16:9 e 9:16).
     */
    fun updateVideoDimensions(newWidth: Int, newHeight: Int) {
        baseVideoTimestampUs = -1L
        baseVideoOffsetMs = lastVideoTimestampMs
        sendExecutor.execute {
            for ((_, client) in clients) {
                try {
                    client.updateDimensions(newWidth, newHeight)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao atualizar dimensões no cliente RTMP", e)
                }
            }
        }
    }

    /**
     * Envia o cabeçalho AVC (SPS e PPS) para todos os clientes ativos.
     */
    fun onVideoHeader(sps: ByteArray, pps: ByteArray) {
        cachedSps = sps
        cachedPps = pps
        val ts = lastVideoTimestampMs.coerceAtLeast(0L)
        sendExecutor.execute {
            for ((target, client) in clients) {
                try {
                    client.sendVideoHeader(sps, pps, ts)
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao enviar cabeçalho de vídeo para ${target.platformName}", e)
                }
            }
        }
    }

    /**
     * Envia um frame de vídeo codificado para todos os clientes ativos.
     */
    fun onVideoSample(naluBytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (!active) return

        val nowMs = SystemClock.elapsedRealtime()
        if (streamMediaStartMs < 0L) {
            streamMediaStartMs = nowMs
        }
        if (baseVideoTimestampUs < 0L) {
            baseVideoTimestampUs = info.presentationTimeUs
            baseVideoOffsetMs = maxOf(lastVideoTimestampMs, (nowMs - streamMediaStartMs).coerceAtLeast(0L))
        }
        val rawMs = baseVideoOffsetMs + ((info.presentationTimeUs - baseVideoTimestampUs) / 1000L).coerceAtLeast(0L)
        val timestampMs = maxOf(rawMs, lastVideoTimestampMs)
        lastVideoTimestampMs = timestampMs

        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val sps = cachedSps
        val pps = cachedPps

        sendExecutor.execute {
            for ((target, client) in clients) {
                if (!client.isPublishing) continue
                try {
                    if (!client.hasVideoHeaderSent && sps != null && pps != null) {
                        client.sendVideoHeader(sps, pps, timestampMs)
                    }
                    client.sendVideoSample(naluBytes, isKeyFrame, timestampMs)
                } catch (e: Exception) {
                    Log.w(TAG, "Erro no envio de vídeo para ${target.platformName}", e)
                }
            }
            active = clients.any { it.second.isPublishing }
        }
    }

    /**
     * Envia o cabeçalho de áudio AAC (AudioSpecificConfig) para todos os clientes ativos.
     */
    fun onAudioHeader(asc: ByteArray) {
        cachedAsc = asc
        sendExecutor.execute {
            for ((target, client) in clients) {
                try {
                    client.sendAudioHeader(asc)
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao enviar cabeçalho de áudio para ${target.platformName}", e)
                }
            }
        }
    }

    /**
     * Envia uma amostra de áudio codificada para todos os clientes ativos.
     */
    fun onAudioSample(aacBytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (!active) return

        val nowMs = SystemClock.elapsedRealtime()
        if (streamMediaStartMs < 0L) {
            streamMediaStartMs = nowMs
        }
        if (baseAudioTimestampUs < 0L) {
            baseAudioTimestampUs = info.presentationTimeUs
            baseAudioOffsetMs = maxOf(lastAudioTimestampMs, (nowMs - streamMediaStartMs).coerceAtLeast(0L))
        }
        val rawMs = baseAudioOffsetMs + ((info.presentationTimeUs - baseAudioTimestampUs) / 1000L).coerceAtLeast(0L)
        val timestampMs = maxOf(rawMs, lastAudioTimestampMs)
        lastAudioTimestampMs = timestampMs

        val asc = cachedAsc

        sendExecutor.execute {
            for ((target, client) in clients) {
                if (!client.isPublishing) continue
                try {
                    if (!client.hasAudioHeaderSent && asc != null) {
                        client.sendAudioHeader(asc)
                    }
                    client.sendAudioSample(aacBytes, timestampMs)
                } catch (e: Exception) {
                    Log.w(TAG, "Erro no envio de áudio para ${target.platformName}", e)
                }
            }
            active = clients.any { it.second.isPublishing }
        }
    }

    @Synchronized
    override fun close() {
        active = false
        sendExecutor.shutdown()
        try {
            sendExecutor.awaitTermination(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        sendExecutor.shutdownNow()
        for ((_, client) in clients) {
            try {
                client.close()
            } catch (_: Exception) {}
        }
        clients.clear()
        threadPool.shutdownNow()
        Log.i(TAG, "StreamPublisher finalizado.")
    }
}
