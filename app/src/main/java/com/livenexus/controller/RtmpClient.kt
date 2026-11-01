package com.livenexus.controller

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente RTMP / RTMPS nativo em Kotlin.
 * Estabelece conexão direta, realiza handshake, desmultiplexa chunks RTMP (fmt 0..3),
 * cria stream e publica pacotes multiplexados de vídeo (H.264) e áudio (AAC)
 * para YouTube, Twitch e Kick.
 */
class RtmpClient(
    private val rtmpUrl: String,
    private val streamKey: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val videoBitrateKbps: Int,
    private val sampleRate: Int = 44100,
    private val audioChannels: Int = 2
) : AutoCloseable {

    companion object {
        private const val TAG = "LiveNexusRtmp"
        private const val OUT_CHUNK_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 6000
        private const val HANDSHAKE_TIMEOUT_MS = 10000

        // Message types
        private const val TYPE_SET_CHUNK_SIZE = 0x01
        private const val TYPE_ABORT_MESSAGE = 0x02
        private const val TYPE_ACKNOWLEDGEMENT = 0x03
        private const val TYPE_USER_CONTROL = 0x04
        private const val TYPE_WINDOW_ACK_SIZE = 0x05
        private const val TYPE_SET_PEER_BANDWIDTH = 0x06
        private const val TYPE_AUDIO = 0x08
        private const val TYPE_VIDEO = 0x09
        private const val TYPE_DATA_AMF0 = 0x12
        private const val TYPE_COMMAND_AMF0 = 0x14

        // Chunk Stream IDs
        private const val CSID_PROTOCOL = 2
        private const val CSID_COMMAND = 3
        private const val CSID_DATA = 4
        private const val CSID_AUDIO = 5
        private const val CSID_VIDEO = 6
        private const val CSID_PUBLISH = 8
    }

    private class ChunkStreamState {
        var timestamp: Long = 0
        var timestampDelta: Long = 0
        var messageLength: Int = 0
        var messageTypeId: Int = 0
        var messageStreamId: Int = 0
        var hasExtendedTimestamp: Boolean = false
        val buffer = ByteArrayOutputStream()
    }

    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null
    private var readerThread: Thread? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isPublishing: Boolean = false
        private set

    private var streamId: Int = 1
    private var inChunkSize: Int = 128
    private var windowAckSize: Int = 2500000
    private var totalBytesRead: Long = 0L
    private var lastAckSentAtBytes: Long = 0L
    private val chunkStates = HashMap<Int, ChunkStreamState>()

    private var videoHeaderSent = false
    private var audioHeaderSent = false

    private val writeLock = Any()

    /**
     * Conecta ao servidor RTMP/RTMPS, realiza handshake e publicação.
     * Possui fallback automático RTMP <-> RTMPS para YouTube e Twitch caso a porta 1935 esteja bloqueada.
     */
    fun connect() {
        val cleanKey = sanitizeStreamKey(streamKey)
        if (cleanKey.isBlank()) {
            throw IllegalArgumentException("Chave de transmissão (Stream Key) vazia.")
        }

        val candidateUrls = buildCandidateUrls(rtmpUrl, streamKey)
        var lastError: Throwable? = null

        for (candidateUrl in candidateUrls) {
            try {
                connectSingleEndpoint(candidateUrl, cleanKey)
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Tentativa falhou em $candidateUrl: ${e.message}")
                lastError = e
                closeQuietly()
                // Se o servidor recusou explicitamente a Stream Key, não precisa testar outro endpoint
                val msg = e.message ?: ""
                if (msg.contains("Chave de transmissão") || msg.contains("Stream Key") || msg.contains("recusada")) {
                    throw e
                }
            }
        }

        throw IllegalStateException(
            lastError?.message ?: "Não foi possível conectar ao servidor RTMP ($rtmpUrl)."
        )
    }

    private fun sanitizeStreamKey(rawKey: String): String {
        var key = rawKey.trim()
        if (key.startsWith("rtmp://", ignoreCase = true) || key.startsWith("rtmps://", ignoreCase = true)) {
            val withoutScheme = key.substringAfter("://")
            val pathPart = withoutScheme.substringAfter('/', "").trim('/')
            if (pathPart.contains('/')) {
                key = pathPart.substringAfterLast('/').trim()
            } else if (pathPart.isNotEmpty() && !pathPart.equals("live2", true) && !pathPart.equals("app", true)) {
                key = pathPart
            }
        }
        if (key.startsWith("/live2/")) {
            key = key.removePrefix("/live2/").trim()
        } else if (key.startsWith("live2/")) {
            key = key.removePrefix("live2/").trim()
        } else if (key.startsWith("/app/")) {
            key = key.removePrefix("/app/").trim()
        } else if (key.startsWith("app/")) {
            key = key.removePrefix("app/").trim()
        }
        return key.trim('/')
    }

    private fun buildCandidateUrls(baseUrl: String, rawKey: String): List<String> {
        val candidates = mutableListOf<String>()
        var primary = baseUrl.trim()

        // Se o usuário colou uma URL completa no campo de chave, aproveita o host dela
        val trimmedKey = rawKey.trim()
        if (trimmedKey.startsWith("rtmp://", ignoreCase = true) || trimmedKey.startsWith("rtmps://", ignoreCase = true)) {
            val lastSlash = trimmedKey.lastIndexOf('/')
            if (lastSlash > 8) {
                val extractedUrl = trimmedKey.substring(0, lastSlash)
                if (extractedUrl.contains("youtube.com") || extractedUrl.contains("twitch.tv") || extractedUrl.contains("live-video.net")) {
                    primary = extractedUrl
                }
            }
        }

        val lower = primary.lowercase()
        when {
            lower.contains("youtube.com") -> {
                // Prioriza RTMPS (porta 443 passa por qualquer firewall/operadora) e RTMP (porta 1935)
                if (lower.startsWith("rtmps://")) {
                    candidates.add(primary)
                    candidates.add(StreamingConfig.RTMP_YOUTUBE)
                } else {
                    candidates.add(StreamingConfig.RTMPS_YOUTUBE)
                    candidates.add(primary)
                }
                candidates.add("rtmps://b.rtmps.youtube.com:443/live2?backup=1")
            }
            lower.contains("twitch.tv") -> {
                candidates.add(primary)
                if (!lower.startsWith("rtmps://")) {
                    candidates.add(StreamingConfig.RTMPS_TWITCH)
                }
            }
            else -> {
                candidates.add(primary)
            }
        }
        return candidates.distinct()
    }

    private fun connectSingleEndpoint(targetUrl: String, cleanKey: String) {
        Log.i(TAG, "Conectando ao endpoint RTMP: $targetUrl...")
        inChunkSize = 128
        windowAckSize = 2500000
        totalBytesRead = 0L
        lastAckSentAtBytes = 0L
        chunkStates.clear()
        videoHeaderSent = false
        audioHeaderSent = false

        val parsed = parseUrl(targetUrl)
        val host = parsed.host
        val port = parsed.port
        val app = parsed.app
        val isTls = parsed.isTls

        val rawSocket = if (isTls) {
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = HANDSHAKE_TIMEOUT_MS
            plain.tcpNoDelay = true
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(plain, host, port, true) as SSLSocket
            sslSocket.useClientMode = true
            sslSocket.startHandshake()
            sslSocket
        } else {
            val plainSocket = Socket()
            plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plainSocket
        }

        rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true
        socket = rawSocket

        val bis = BufferedInputStream(rawSocket.getInputStream(), 65536)
        val bos = BufferedOutputStream(rawSocket.getOutputStream(), 65536)
        inStream = bis
        outStream = bos

        doHandshake(bis, bos)
        Log.i(TAG, "Handshake concluído com sucesso em $host:$port")

        sendSetChunkSize(bos, OUT_CHUNK_SIZE)
        sendConnect(bos, app, parsed.tcUrl)

        val connectResult = readUntilConnectResult(bis, bos)
        if (!connectResult) {
            throw IllegalStateException("Falha na resposta connect do servidor RTMP ($host).")
        }
        Log.i(TAG, "Connect aprovado pelo servidor RTMP ($host)")

        // Sequência oficial FMLE / OBS exigida pelo YouTube Live e servidores compatíveis
        sendWindowAckSize(bos, windowAckSize)
        sendReleaseStream(bos, 2.0, cleanKey)
        sendFCPublish(bos, 3.0, cleanKey)
        sendCreateStream(bos, 4.0)

        val activeStreamId = readCreateStreamResult(bis, bos, 4.0)
        streamId = activeStreamId
        Log.i(TAG, "Stream criado com ID: $streamId")

        val publishAppType = if (app.contains("backup=1")) "backup" else "live"
        sendPublish(bos, streamId, cleanKey, publishAppType)

        // Aguarda confirmação real do servidor (NetStream.Publish.Start)
        verifyPublishStatus(bis, bos, rawSocket)

        sendMetadata(bos, streamId)

        isConnected = true
        isPublishing = true
        startBackgroundReader(bis, bos, rawSocket)
        Log.i(TAG, "Transmissão RTMP publicada e ativa em $host:$port/$app!")
    }

    private data class ParsedUrl(
        val host: String,
        val port: Int,
        val app: String,
        val tcUrl: String,
        val isTls: Boolean
    )

    private fun parseUrl(url: String): ParsedUrl {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase() ?: "rtmp"
        val isTls = scheme == "rtmps"
        val defaultPort = if (isTls) 443 else 1935
        val host = uri.host ?: throw IllegalArgumentException("Host inválido em $url")
        val port = if (uri.port != -1) uri.port else defaultPort
        var path = uri.path?.trim('/') ?: "app"
        if (path.isEmpty()) path = "app"
        val query = uri.rawQuery
        val fullApp = if (!query.isNullOrBlank()) "$path?$query" else path
        val tcUrl = "$scheme://$host:$port/$fullApp"
        return ParsedUrl(host, port, fullApp, tcUrl, isTls)
    }

    private fun doHandshake(input: InputStream, output: OutputStream) {
        val random = Random()
        val c1 = ByteArray(1536)
        random.nextBytes(c1)
        for (i in 0 until 8) c1[i] = 0

        // Envia C0 (0x03) + C1 (1536 bytes)
        output.write(0x03)
        output.write(c1)
        output.flush()

        // Lê S0 (1 byte)
        val s0 = readByteCounted(input)
        if (s0 != 0x03) {
            throw IllegalStateException("Versão RTMP incompatível retornada pelo servidor: $s0")
        }

        // Lê S1 (1536 bytes)
        val s1 = ByteArray(1536)
        readFully(input, s1)

        // Lê S2 (1536 bytes)
        val s2 = ByteArray(1536)
        readFully(input, s2)

        // Envia C2 (eco de S1)
        val c2 = s1.copyOf()
        c2[4] = 0; c2[5] = 0; c2[6] = 0; c2[7] = 0
        output.write(c2)
        output.flush()
    }

    private fun readByteCounted(input: InputStream): Int {
        val b = input.read()
        if (b == -1) throw EOFException("Conexão encerrada pelo servidor RTMP.")
        totalBytesRead++
        return b
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        var readTotal = 0
        while (readTotal < length) {
            val count = input.read(buffer, offset + readTotal, length - readTotal)
            if (count == -1) throw EOFException("Conexão fechada pelo servidor RTMP.")
            readTotal += count
            totalBytesRead += count
        }
    }

    private fun sendSetChunkSize(out: OutputStream, size: Int) {
        val payload = ByteArray(4)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(size)
        sendPacket(out, CSID_PROTOCOL, 0, TYPE_SET_CHUNK_SIZE, 0, payload)
    }

    private fun sendWindowAckSize(out: OutputStream, size: Int) {
        val payload = ByteArray(4)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(size)
        sendPacket(out, CSID_PROTOCOL, 0, TYPE_WINDOW_ACK_SIZE, 0, payload)
    }

    private fun sendAcknowledgement(out: OutputStream, bytesReceived: Int) {
        val payload = ByteArray(4)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(bytesReceived)
        sendPacket(out, CSID_PROTOCOL, 0, TYPE_ACKNOWLEDGEMENT, 0, payload)
    }

    private fun sendPong(out: OutputStream, timestamp: Int) {
        val payload = ByteArray(6)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(7) // Event 7 = PingResponse
            putInt(timestamp)
        }
        sendPacket(out, CSID_PROTOCOL, 0, TYPE_USER_CONTROL, 0, payload)
    }

    private fun sendConnect(out: OutputStream, app: String, tcUrl: String) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "connect")
        writeAmf0Number(baos, 1.0)

        // Command Object
        baos.write(0x03)
        writeAmf0Property(baos, "app", app)
        writeAmf0Property(baos, "type", "nonprivate")
        writeAmf0Property(baos, "flashVer", "FMLE/3.0 (compatible; FMSc/1.0)")
        writeAmf0Property(baos, "swfUrl", tcUrl)
        writeAmf0Property(baos, "tcUrl", tcUrl)
        writeAmf0Property(baos, "fpad", false)
        writeAmf0Property(baos, "capabilities", 15.0)
        writeAmf0Property(baos, "audioCodecs", 3191.0)
        writeAmf0Property(baos, "videoCodecs", 252.0)
        writeAmf0Property(baos, "videoFunction", 1.0)
        // End Object (0x00 0x00 0x09)
        baos.write(0x00); baos.write(0x00); baos.write(0x09)

        sendPacket(out, CSID_COMMAND, 0, TYPE_COMMAND_AMF0, 0, baos.toByteArray())
    }

    private fun sendReleaseStream(out: OutputStream, transId: Double, key: String) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "releaseStream")
        writeAmf0Number(baos, transId)
        writeAmf0Null(baos)
        writeAmf0String(baos, key)
        sendPacket(out, CSID_COMMAND, 0, TYPE_COMMAND_AMF0, 0, baos.toByteArray())
    }

    private fun sendFCPublish(out: OutputStream, transId: Double, key: String) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "FCPublish")
        writeAmf0Number(baos, transId)
        writeAmf0Null(baos)
        writeAmf0String(baos, key)
        sendPacket(out, CSID_COMMAND, 0, TYPE_COMMAND_AMF0, 0, baos.toByteArray())
    }

    private fun sendCreateStream(out: OutputStream, transId: Double) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "createStream")
        writeAmf0Number(baos, transId)
        writeAmf0Null(baos)
        sendPacket(out, CSID_COMMAND, 0, TYPE_COMMAND_AMF0, 0, baos.toByteArray())
    }

    private fun sendPublish(out: OutputStream, streamId: Int, key: String, mode: String = "live") {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "publish")
        writeAmf0Number(baos, 5.0)
        writeAmf0Null(baos)
        writeAmf0String(baos, key)
        writeAmf0String(baos, mode)
        sendPacket(out, CSID_PUBLISH, 0, TYPE_COMMAND_AMF0, streamId, baos.toByteArray())
    }

    private fun sendMetadata(out: OutputStream, streamId: Int) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "@setDataFrame")
        writeAmf0String(baos, "onMetaData")

        // ECMA array (0x08)
        baos.write(0x08)
        val countBytes = ByteArray(4)
        ByteBuffer.wrap(countBytes).order(ByteOrder.BIG_ENDIAN).putInt(12)
        baos.write(countBytes)

        writeAmf0Property(baos, "duration", 0.0)
        writeAmf0Property(baos, "width", width.toDouble())
        writeAmf0Property(baos, "height", height.toDouble())
        writeAmf0Property(baos, "videocodecid", 7.0) // 7 = AVC (H.264)
        writeAmf0Property(baos, "videodatarate", videoBitrateKbps.toDouble())
        writeAmf0Property(baos, "framerate", fps.toDouble())
        writeAmf0Property(baos, "audiocodecid", 10.0) // 10 = AAC
        writeAmf0Property(baos, "audiodatarate", 128.0)
        writeAmf0Property(baos, "audiosamplerate", sampleRate.toDouble())
        writeAmf0Property(baos, "audiosamplesize", 16.0)
        writeAmf0Property(baos, "audiochannels", audioChannels.toDouble())
        writeAmf0Property(baos, "stereo", audioChannels > 1)
        baos.write(0x00); baos.write(0x00); baos.write(0x09)

        sendPacket(out, CSID_DATA, 0, TYPE_DATA_AMF0, streamId, baos.toByteArray())
    }

    private fun readUntilConnectResult(input: InputStream, output: OutputStream): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 10000) {
            val packet = readNextMessage(input, output)
            if (packet.type == TYPE_COMMAND_AMF0) {
                val str = String(packet.payload, Charsets.ISO_8859_1)
                if (str.contains("NetConnection.Connect.Success") || str.contains("_result")) {
                    return true
                }
                if (str.contains("_error") || str.contains("NetConnection.Connect.Rejected") || str.contains("NetConnection.Connect.Failed")) {
                    throw IllegalStateException("Servidor rejeitou conexão RTMP.")
                }
            }
        }
        throw IllegalStateException("Tempo esgotado aguardando confirmação connect do servidor RTMP.")
    }

    private fun readCreateStreamResult(input: InputStream, output: OutputStream, expectedTransId: Double): Int {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 10000) {
            val packet = readNextMessage(input, output)
            if (packet.type == TYPE_COMMAND_AMF0) {
                val str = String(packet.payload, Charsets.ISO_8859_1)
                if (str.contains("_result")) {
                    // Extrai o número AMF0 final que representa o streamId (geralmente 1.0)
                    val payload = packet.payload
                    if (payload.size >= 9 && payload[payload.size - 9].toInt() == 0x00) {
                        val idDouble = ByteBuffer.wrap(payload, payload.size - 8, 8)
                            .order(ByteOrder.BIG_ENDIAN)
                            .double
                        val parsedId = idDouble.toInt()
                        if (parsedId > 0) return parsedId
                    }
                    return 1
                }
                if (str.contains("_error")) {
                    throw IllegalStateException("Erro ao criar stream no servidor RTMP.")
                }
            }
        }
        return 1
    }

    private fun verifyPublishStatus(input: InputStream, output: OutputStream, sock: Socket) {
        val prevTimeout = sock.soTimeout
        try {
            sock.soTimeout = 3500
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 6000) {
                val packet = readNextMessage(input, output)
                if (packet.type == TYPE_COMMAND_AMF0) {
                    val str = String(packet.payload, Charsets.ISO_8859_1)
                    if (str.contains("NetStream.Publish.BadName") ||
                        str.contains("NetStream.Publish.Rejected") ||
                        str.contains("NetStream.Publish.Failed") ||
                        str.contains("NetStream.Publish.Unauthorized") ||
                        str.contains("_error")
                    ) {
                        throw IllegalStateException("Chave de transmissão (Stream Key) recusada pelo servidor RTMP.")
                    }
                    if (str.contains("NetStream.Publish.Start")) {
                        Log.i(TAG, "NetStream.Publish.Start confirmado pelo servidor!")
                        return
                    }
                }
            }
        } catch (timeout: SocketTimeoutException) {
            // Alguns servidores RTMP só enviam onStatus após receber os primeiros frames; conexão segue aberta
            Log.i(TAG, "Publish enviado; conexão ativa aguardando pacotes de mídia.")
        } catch (eof: EOFException) {
            throw IllegalStateException(
                "Chave de transmissão (Stream Key) inválida ou transmissão ao vivo não habilitada na plataforma.",
                eof
            )
        } finally {
            try { sock.soTimeout = prevTimeout } catch (_: Exception) {}
        }
    }

    private fun startBackgroundReader(input: InputStream, output: OutputStream, sock: Socket) {
        try {
            sock.soTimeout = 0 // Leitura bloqueante na thread dedicada
        } catch (_: Exception) {}

        readerThread = Thread({
            try {
                while (isPublishing) {
                    val packet = readNextMessage(input, output)
                    if (packet.type == TYPE_COMMAND_AMF0) {
                        val str = String(packet.payload, Charsets.ISO_8859_1)
                        if (str.contains("NetStream.Publish.BadName") ||
                            str.contains("NetStream.Publish.Rejected") ||
                            str.contains("NetStream.Publish.Failed")
                        ) {
                            Log.e(TAG, "Servidor encerrou publicação RTMP: $str")
                            isPublishing = false
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (isPublishing) {
                    Log.w(TAG, "Leitor RTMP encerrado: ${e.message}")
                }
            }
        }, "LiveNexusRtmpReader").apply {
            isDaemon = true
            start()
        }
    }

    private data class RtmpPacket(val type: Int, val streamId: Int, val timestamp: Long, val payload: ByteArray)

    /**
     * Lê chunks do fluxo de entrada RTMP respeitando o inChunkSize e remontando mensagens multi-chunk.
     * Trata automaticamente mensagens de controle de protocolo (SetChunkSize, WindowAckSize, PingRequest).
     */
    private fun readNextMessage(input: InputStream, output: OutputStream): RtmpPacket {
        while (true) {
            val firstByte = readByteCounted(input)
            val fmt = (firstByte ushr 6) and 0x03
            var csid = firstByte and 0x3F
            if (csid == 0) {
                csid = 64 + readByteCounted(input)
            } else if (csid == 1) {
                val b1 = readByteCounted(input)
                val b2 = readByteCounted(input)
                csid = 64 + b1 + (b2 shl 8)
            }

            val state = chunkStates.getOrPut(csid) { ChunkStreamState() }

            when (fmt) {
                0 -> {
                    val ts = readInt24(input).toLong()
                    val length = readInt24(input)
                    val type = readByteCounted(input)
                    val msgStreamId = readInt32LE(input)
                    state.timestamp = ts
                    state.timestampDelta = 0
                    state.messageLength = length
                    state.messageTypeId = type
                    state.messageStreamId = msgStreamId
                    state.hasExtendedTimestamp = (ts == 0xFFFFFFL)
                    state.buffer.reset()
                }
                1 -> {
                    val delta = readInt24(input).toLong()
                    val length = readInt24(input)
                    val type = readByteCounted(input)
                    state.timestampDelta = delta
                    state.messageLength = length
                    state.messageTypeId = type
                    state.hasExtendedTimestamp = (delta == 0xFFFFFFL)
                    state.buffer.reset()
                }
                2 -> {
                    val delta = readInt24(input).toLong()
                    state.timestampDelta = delta
                    state.hasExtendedTimestamp = (delta == 0xFFFFFFL)
                    state.buffer.reset()
                }
                3 -> {
                    // Continuation chunk (se buffer estiver vazio, inicia nova mensagem com mesmos parâmetros)
                }
            }

            if (state.hasExtendedTimestamp) {
                val extTs = readInt32(input).toLong() and 0xFFFFFFFFL
                if (fmt == 0) {
                    state.timestamp = extTs
                } else {
                    state.timestampDelta = extTs
                }
            }

            val remaining = state.messageLength - state.buffer.size()
            if (remaining > 0) {
                val toRead = minOf(inChunkSize, remaining)
                val chunkBuf = ByteArray(toRead)
                readFully(input, chunkBuf, 0, toRead)
                state.buffer.write(chunkBuf)
            }

            // Envia Acknowledgement se necessário
            if (totalBytesRead - lastAckSentAtBytes >= windowAckSize) {
                lastAckSentAtBytes = totalBytesRead
                try {
                    sendAcknowledgement(output, totalBytesRead.toInt())
                } catch (_: Exception) {}
            }

            if (state.buffer.size() >= state.messageLength) {
                val fullPayload = state.buffer.toByteArray()
                state.buffer.reset()
                val msgType = state.messageTypeId
                val msgStreamId = state.messageStreamId
                val msgTs = state.timestamp

                // Processa mensagens de controle da camada de protocolo RTMP
                when (msgType) {
                    TYPE_SET_CHUNK_SIZE -> {
                        if (fullPayload.size >= 4) {
                            val newSize = ByteBuffer.wrap(fullPayload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                            if (newSize in 1..16777216) {
                                inChunkSize = newSize
                            }
                        }
                    }
                    TYPE_WINDOW_ACK_SIZE -> {
                        if (fullPayload.size >= 4) {
                            val newWindow = ByteBuffer.wrap(fullPayload, 0, 4).order(ByteOrder.BIG_ENDIAN).int
                            if (newWindow > 0) {
                                windowAckSize = newWindow
                            }
                        }
                    }
                    TYPE_USER_CONTROL -> {
                        if (fullPayload.size >= 6) {
                            val eventType = ((fullPayload[0].toInt() and 0xFF) shl 8) or (fullPayload[1].toInt() and 0xFF)
                            if (eventType == 6) {
                                // PingRequest -> envia PingResponse (7)
                                val pingTs = ByteBuffer.wrap(fullPayload, 2, 4).order(ByteOrder.BIG_ENDIAN).int
                                try {
                                    sendPong(output, pingTs)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                return RtmpPacket(msgType, msgStreamId, msgTs, fullPayload)
            }
        }
    }

    private fun readInt24(input: InputStream): Int {
        val b0 = readByteCounted(input)
        val b1 = readByteCounted(input)
        val b2 = readByteCounted(input)
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun readInt32(input: InputStream): Int {
        val b0 = readByteCounted(input)
        val b1 = readByteCounted(input)
        val b2 = readByteCounted(input)
        val b3 = readByteCounted(input)
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readInt32LE(input: InputStream): Int {
        val b0 = readByteCounted(input)
        val b1 = readByteCounted(input)
        val b2 = readByteCounted(input)
        val b3 = readByteCounted(input)
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun sendPacket(
        out: OutputStream,
        csid: Int,
        timestampMs: Long,
        type: Int,
        messageStreamId: Int,
        payload: ByteArray
    ) {
        val length = payload.size
        val fmt0Header = ByteArray(12)
        var pos = 0
        fmt0Header[pos++] = (csid and 0x3F).toByte()

        val ts = if (timestampMs >= 0xFFFFFFL) 0xFFFFFF else timestampMs.toInt().coerceAtLeast(0)
        fmt0Header[pos++] = ((ts ushr 16) and 0xFF).toByte()
        fmt0Header[pos++] = ((ts ushr 8) and 0xFF).toByte()
        fmt0Header[pos++] = (ts and 0xFF).toByte()

        fmt0Header[pos++] = ((length ushr 16) and 0xFF).toByte()
        fmt0Header[pos++] = ((length ushr 8) and 0xFF).toByte()
        fmt0Header[pos++] = (length and 0xFF).toByte()

        fmt0Header[pos++] = (type and 0xFF).toByte()

        // Stream ID (4 bytes little-endian)
        fmt0Header[pos++] = (messageStreamId and 0xFF).toByte()
        fmt0Header[pos++] = ((messageStreamId ushr 8) and 0xFF).toByte()
        fmt0Header[pos++] = ((messageStreamId ushr 16) and 0xFF).toByte()
        fmt0Header[pos++] = ((messageStreamId ushr 24) and 0xFF).toByte()

        val hasExtendedTimestamp = timestampMs >= 0xFFFFFFL

        synchronized(writeLock) {
            out.write(fmt0Header)
            if (hasExtendedTimestamp) {
                val ext = ByteArray(4)
                ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).putInt(timestampMs.toInt())
                out.write(ext)
            }

            var offset = 0
            val chunkSize = OUT_CHUNK_SIZE
            val firstChunk = minOf(chunkSize, length)
            out.write(payload, 0, firstChunk)
            offset += firstChunk

            val fmt3Header = byteArrayOf((0xC0 or (csid and 0x3F)).toByte())
            while (offset < length) {
                out.write(fmt3Header)
                if (hasExtendedTimestamp) {
                    val ext = ByteArray(4)
                    ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).putInt(timestampMs.toInt())
                    out.write(ext)
                }
                val chunk = minOf(chunkSize, length - offset)
                out.write(payload, offset, chunk)
                offset += chunk
            }
            out.flush()
        }
    }

    /**
     * Envia o cabeçalho de configuração AVC (SPS e PPS).
     * Deve ser enviado antes de qualquer frame de vídeo.
     */
    fun sendVideoHeader(sps: ByteArray, pps: ByteArray) {
        if (!isPublishing) return
        val out = outStream ?: return
        val baos = ByteArrayOutputStream()

        // FLV Video Tag Header: FrameType=1 (Keyframe), CodecID=7 (AVC) -> 0x17
        baos.write(0x17)
        // AVCPacketType: 0 = AVC sequence header
        baos.write(0x00)
        // Composition time (3 bytes): 0
        baos.write(0x00); baos.write(0x00); baos.write(0x00)

        // AVCDecoderConfigurationRecord (ISO 14496-15)
        baos.write(0x01) // configurationVersion
        baos.write(if (sps.size > 1) sps[1].toInt() and 0xFF else 0x42) // AVCProfileIndication
        baos.write(if (sps.size > 2) sps[2].toInt() and 0xFF else 0xC0) // profile_compatibility
        baos.write(if (sps.size > 3) sps[3].toInt() and 0xFF else 0x1F) // AVCLevelIndication
        baos.write(0xFF) // lengthSizeMinusOne | 0xFC = 0xFF (4 bytes)
        baos.write(0xE1) // numOfSequenceParameterSets | 0xE0 = 1

        // SPS length (2 bytes big endian) + SPS
        val spsLen = ByteArray(2)
        spsLen[0] = ((sps.size ushr 8) and 0xFF).toByte()
        spsLen[1] = (sps.size and 0xFF).toByte()
        baos.write(spsLen)
        baos.write(sps)

        // PPS count (1) + PPS length (2 bytes) + PPS
        baos.write(0x01)
        val ppsLen = ByteArray(2)
        ppsLen[0] = ((pps.size ushr 8) and 0xFF).toByte()
        ppsLen[1] = (pps.size and 0xFF).toByte()
        baos.write(ppsLen)
        baos.write(pps)

        val packet = baos.toByteArray()
        sendPacket(out, CSID_VIDEO, 0, TYPE_VIDEO, streamId, packet)
        videoHeaderSent = true
        Log.i(TAG, "AVC Sequence Header enviado com sucesso (${packet.size} bytes)")
    }

    /**
     * Envia frame de vídeo codificado em H.264 multiplexado em FLV Video Tag.
     */
    fun sendVideoSample(naluBytes: ByteArray, isKeyFrame: Boolean, timestampMs: Long) {
        if (!isPublishing) return
        val out = outStream ?: return

        val nalus = splitAnnexBNalus(naluBytes)
        if (!videoHeaderSent) {
            var inlineSps: ByteArray? = null
            var inlinePps: ByteArray? = null
            for (nalu in nalus) {
                if (nalu.isNotEmpty()) {
                    val nalType = nalu[0].toInt() and 0x1F
                    if (nalType == 7) inlineSps = nalu
                    else if (nalType == 8) inlinePps = nalu
                }
            }
            if (inlineSps != null && inlinePps != null) {
                sendVideoHeader(inlineSps, inlinePps)
            } else {
                return
            }
        }

        val baos = ByteArrayOutputStream(naluBytes.size + 16)
        // Byte 0: FrameType (1=Key, 2=Inter) + CodecID (7=AVC)
        val headerByte = if (isKeyFrame) 0x17 else 0x27
        baos.write(headerByte)
        // Byte 1: AVCPacketType (1 = AVC NALU)
        baos.write(0x01)
        // Bytes 2-4: Composition time = 0
        baos.write(0x00); baos.write(0x00); baos.write(0x00)

        var wroteVclNalu = false
        for (nalu in nalus) {
            if (nalu.isEmpty()) continue
            val nalType = nalu[0].toInt() and 0x1F
            // SPS (7) e PPS (8) já são enviados no AVCDecoderConfigurationRecord
            if (nalType == 7 || nalType == 8) continue
            val len = nalu.size
            baos.write((len ushr 24) and 0xFF)
            baos.write((len ushr 16) and 0xFF)
            baos.write((len ushr 8) and 0xFF)
            baos.write(len and 0xFF)
            baos.write(nalu)
            wroteVclNalu = true
        }

        if (!wroteVclNalu) return
        val packet = baos.toByteArray()
        sendPacket(out, CSID_VIDEO, timestampMs, TYPE_VIDEO, streamId, packet)
    }

    private fun splitAnnexBNalus(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var i = 0
        var lastStart = -1

        while (i <= data.size - 3) {
            val isStart4 = i <= data.size - 4 &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isStart3 = !isStart4 &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()

            if (isStart4 || isStart3) {
                val startCodeLen = if (isStart4) 4 else 3
                if (lastStart >= 0) {
                    val naluLen = i - lastStart
                    if (naluLen > 0) {
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

    /**
     * Envia o cabeçalho AAC Sequence Header (AudioSpecificConfig).
     */
    fun sendAudioHeader(config: ByteArray) {
        if (!isPublishing) return
        val out = outStream ?: return
        val baos = ByteArrayOutputStream()

        // 0xAF = SoundFormat 10 (AAC), SoundRate 3 (44kHz), SoundSize 1 (16bit), SoundType 1 (Stereo)
        baos.write(0xAF)
        // AACPacketType: 0 = AAC sequence header
        baos.write(0x00)
        baos.write(config)

        val packet = baos.toByteArray()
        sendPacket(out, CSID_AUDIO, 0, TYPE_AUDIO, streamId, packet)
        audioHeaderSent = true
        Log.i(TAG, "AAC Sequence Header enviado com sucesso")
    }

    /**
     * Envia amostra de áudio codificada em AAC multiplexada em FLV Audio Tag.
     */
    fun sendAudioSample(aacBytes: ByteArray, timestampMs: Long) {
        if (!isPublishing || !audioHeaderSent) return
        val out = outStream ?: return
        val baos = ByteArrayOutputStream(aacBytes.size + 2)

        baos.write(0xAF)
        baos.write(0x01) // 1 = Raw AAC frame

        // Se houver cabeçalho ADTS (7 bytes com sincronismo 0xFFF), remove o cabeçalho
        if (aacBytes.size > 7 && (aacBytes[0].toInt() and 0xFF) == 0xFF && (aacBytes[1].toInt() and 0xF0) == 0xF0) {
            val hasCrc = (aacBytes[1].toInt() and 0x01) == 0
            val headerLen = if (hasCrc) 9 else 7
            if (aacBytes.size > headerLen) {
                baos.write(aacBytes, headerLen, aacBytes.size - headerLen)
            } else {
                return
            }
        } else {
            baos.write(aacBytes)
        }

        val packet = baos.toByteArray()
        sendPacket(out, CSID_AUDIO, timestampMs, TYPE_AUDIO, streamId, packet)
    }

    // --- Helpers de escrita AMF0 ---

    private fun writeAmf0String(out: OutputStream, value: String) {
        out.write(0x02)
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun writeAmf0Number(out: OutputStream, value: Double) {
        out.write(0x00)
        val bytes = ByteArray(8)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putDouble(value)
        out.write(bytes)
    }

    private fun writeAmf0Null(out: OutputStream) {
        out.write(0x05)
    }

    private fun writeAmf0Property(out: OutputStream, key: String, value: Any) {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        out.write((keyBytes.size ushr 8) and 0xFF)
        out.write(keyBytes.size and 0xFF)
        out.write(keyBytes)

        when (value) {
            is String -> writeAmf0String(out, value)
            is Number -> writeAmf0Number(out, value.toDouble())
            is Boolean -> {
                out.write(0x01)
                out.write(if (value) 1 else 0)
            }
        }
    }

    private fun closeQuietly() {
        isPublishing = false
        isConnected = false
        try { readerThread?.interrupt() } catch (_: Exception) {}
        readerThread = null
        try { outStream?.flush() } catch (_: Exception) {}
        try { inStream?.close() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inStream = null
        outStream = null
    }

    @Synchronized
    override fun close() {
        closeQuietly()
        Log.i(TAG, "Conexão RTMP encerrada.")
    }
}
