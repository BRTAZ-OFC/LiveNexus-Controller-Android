package com.livenexus.controller

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente RTMP / RTMPS nativo em Kotlin.
 * Estabelece conexão direta, realiza handshake, cria stream e publica
 * pacotes multiplexados de vídeo (H.264) e áudio (AAC) para YouTube, Twitch e Kick.
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
        private const val CHUNK_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 12000
        private const val SO_TIMEOUT_MS = 15000

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
    }

    private var socket: Socket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isPublishing: Boolean = false
        private set

    private var streamId: Int = 1
    private var transactionId: Double = 1.0
    private var videoHeaderSent = false
    private var audioHeaderSent = false

    private val writeLock = Any()

    /**
     * Conecta ao servidor RTMP/RTMPS, realiza handshake e publicação.
     * Deve ser executado em thread de segundo plano.
     */
    fun connect() {
        Log.i(TAG, "Conectando ao RTMP: $rtmpUrl...")
        val parsed = parseUrl(rtmpUrl)
        val host = parsed.host
        val port = parsed.port
        val app = parsed.app
        val isTls = parsed.isTls

        val rawSocket = if (isTls) {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket()
            sslSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sslSocket
        } else {
            val plainSocket = Socket()
            plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plainSocket
        }

        rawSocket.soTimeout = SO_TIMEOUT_MS
        rawSocket.tcpNoDelay = true
        socket = rawSocket

        val bis = BufferedInputStream(rawSocket.getInputStream(), 65536)
        val bos = BufferedOutputStream(rawSocket.getOutputStream(), 65536)
        inStream = bis
        outStream = bos

        doHandshake(bis, bos)
        Log.i(TAG, "Handshake concluído com sucesso")

        sendSetChunkSize(bos, CHUNK_SIZE)
        bos.flush()

        sendConnect(bos, app, parsed.tcUrl)
        bos.flush()

        val connectResult = readUntilResult(bis, 1.0)
        if (!connectResult) {
            throw IllegalStateException("Falha na resposta connect do servidor RTMP.")
        }
        Log.i(TAG, "Connect aprovado pelo servidor RTMP")

        transactionId = 2.0
        sendCreateStream(bos, transactionId)
        bos.flush()

        val activeStreamId = readCreateStreamResult(bis, transactionId)
        streamId = activeStreamId
        Log.i(TAG, "Stream criado com ID: $streamId")

        sendPublish(bos, streamId, streamKey)
        bos.flush()

        sendMetadata(bos, streamId)
        bos.flush()

        isConnected = true
        isPublishing = true
        Log.i(TAG, "Transmissão RTMP publicada com sucesso para $host!")
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
        val tcUrl = "$scheme://$host:$port/$path"
        return ParsedUrl(host, port, path, tcUrl, isTls)
    }

    private fun doHandshake(input: InputStream, output: OutputStream) {
        val random = Random()
        val c1 = ByteArray(1536)
        random.nextBytes(c1)
        c1[0] = 0; c1[1] = 0; c1[2] = 0; c1[3] = 0
        c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0

        // Envia C0 (0x03) + C1 (1536 bytes)
        output.write(0x03)
        output.write(c1)
        output.flush()

        // Lê S0 (1 byte)
        val s0 = input.read()
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
        output.write(s1)
        output.flush()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var readTotal = 0
        while (readTotal < buffer.size) {
            val count = input.read(buffer, readTotal, buffer.size - readTotal)
            if (count == -1) throw IllegalStateException("Conexão fechada prematuramente pelo servidor RTMP.")
            readTotal += count
        }
    }

    private fun sendSetChunkSize(out: OutputStream, size: Int) {
        val payload = ByteArray(4)
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putInt(size)
        sendPacket(out, CSID_PROTOCOL, 0, TYPE_SET_CHUNK_SIZE, 0, payload)
    }

    private fun sendConnect(out: OutputStream, app: String, tcUrl: String) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "connect")
        writeAmf0Number(baos, 1.0)

        // Command Object
        baos.write(0x03)
        writeAmf0Property(baos, "app", app)
        writeAmf0Property(baos, "flashVer", "FMLE/3.0 (compatible; LiveNexus)")
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

    private fun sendCreateStream(out: OutputStream, transId: Double) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "createStream")
        writeAmf0Number(baos, transId)
        writeAmf0Null(baos)
        sendPacket(out, CSID_COMMAND, 0, TYPE_COMMAND_AMF0, 0, baos.toByteArray())
    }

    private fun sendPublish(out: OutputStream, streamId: Int, streamKey: String) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "publish")
        writeAmf0Number(baos, 0.0)
        writeAmf0Null(baos)
        writeAmf0String(baos, streamKey)
        writeAmf0String(baos, "live")
        sendPacket(out, CSID_COMMAND, 0, TYPE_COMMAND_AMF0, streamId, baos.toByteArray())
    }

    private fun sendMetadata(out: OutputStream, streamId: Int) {
        val baos = ByteArrayOutputStream()
        writeAmf0String(baos, "@setDataFrame")
        writeAmf0String(baos, "onMetaData")

        // ECMA array (0x08)
        baos.write(0x08)
        // Count
        val countBytes = ByteArray(4)
        ByteBuffer.wrap(countBytes).order(ByteOrder.BIG_ENDIAN).putInt(10)
        baos.write(countBytes)

        writeAmf0Property(baos, "duration", 0.0)
        writeAmf0Property(baos, "width", width.toDouble())
        writeAmf0Property(baos, "height", height.toDouble())
        writeAmf0Property(baos, "videocodecid", 7.0) // 7 = AVC
        writeAmf0Property(baos, "videodatarate", videoBitrateKbps.toDouble())
        writeAmf0Property(baos, "framerate", fps.toDouble())
        writeAmf0Property(baos, "audiocodecid", 10.0) // 10 = AAC
        writeAmf0Property(baos, "audiodatarate", 128.0)
        writeAmf0Property(baos, "audiosamplerate", sampleRate.toDouble())
        writeAmf0Property(baos, "stereo", audioChannels > 1)
        baos.write(0x00); baos.write(0x00); baos.write(0x09)

        sendPacket(out, CSID_DATA, 0, TYPE_DATA_AMF0, streamId, baos.toByteArray())
    }

    private fun readUntilResult(input: InputStream, expectedTransId: Double): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 10000) {
            val packet = readPacket(input) ?: continue
            if (packet.type == TYPE_COMMAND_AMF0) {
                val str = String(packet.payload, Charsets.ISO_8859_1)
                if (str.contains("_result") || str.contains("onStatus") || str.contains("NetConnection.Connect.Success")) {
                    return true
                }
                if (str.contains("_error") || str.contains("NetConnection.Connect.Rejected") || str.contains("NetConnection.Connect.Failed")) {
                    throw IllegalStateException("Servidor rejeitou conexão RTMP.")
                }
            }
        }
        return true
    }

    private fun readCreateStreamResult(input: InputStream, expectedTransId: Double): Int {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 10000) {
            val packet = readPacket(input) ?: continue
            if (packet.type == TYPE_COMMAND_AMF0) {
                val str = String(packet.payload, Charsets.ISO_8859_1)
                if (str.contains("_result")) {
                    return 1
                }
            }
        }
        return 1
    }

    private data class RtmpPacket(val type: Int, val streamId: Int, val timestamp: Long, val payload: ByteArray)

    private fun readPacket(input: InputStream): RtmpPacket? {
        val firstByte = input.read()
        if (firstByte == -1) return null
        val fmt = (firstByte ushr 6) and 0x03
        var csid = firstByte and 0x3F
        if (csid == 0) {
            csid = 64 + input.read()
        } else if (csid == 1) {
            val b1 = input.read()
            val b2 = input.read()
            csid = 64 + b1 + (b2 shl 8)
        }

        var timestamp: Long = 0
        var length = 0
        var type = 0
        var streamId = 0

        when (fmt) {
            0 -> {
                timestamp = readInt24(input).toLong()
                length = readInt24(input)
                type = input.read()
                streamId = readInt32LE(input)
            }
            1 -> {
                timestamp = readInt24(input).toLong()
                length = readInt24(input)
                type = input.read()
            }
            2 -> {
                timestamp = readInt24(input).toLong()
            }
            3 -> {
                // Herda tamanho e tipo anteriores
            }
        }

        if (timestamp == 0xFFFFFFL) {
            timestamp = readInt32(input).toLong()
        }

        if (length > 0 && length < 1048576) {
            val payload = ByteArray(length)
            readFully(input, payload)
            return RtmpPacket(type, streamId, timestamp, payload)
        }
        return null
    }

    private fun readInt24(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        return (b0 shl 16) or (b1 shl 8) or b2
    }

    private fun readInt32(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readInt32LE(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
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

        val ts = if (timestampMs >= 0xFFFFFFL) 0xFFFFFF else timestampMs.toInt()
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
            val chunkSize = CHUNK_SIZE
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
        baos.write(if (sps.size > 1) sps[1].toInt() else 0x42) // AVCProfileIndication
        baos.write(if (sps.size > 2) sps[2].toInt() else 0xC0) // profile_compatibility
        baos.write(if (sps.size > 3) sps[3].toInt() else 0x1F) // AVCLevelIndication
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
        out.flush()
        videoHeaderSent = true
        Log.i(TAG, "AVC Sequence Header enviado com sucesso (${packet.size} bytes)")
    }

    /**
     * Envia frame de vídeo codificado em H.264 multiplexado em FLV Video Tag.
     */
    fun sendVideoSample(naluBytes: ByteArray, isKeyFrame: Boolean, timestampMs: Long) {
        if (!isPublishing || !videoHeaderSent) return
        val out = outStream ?: return
        val baos = ByteArrayOutputStream(naluBytes.size + 9)

        // Byte 0: FrameType (1=Key, 2=Inter) + CodecID (7=AVC)
        val headerByte = if (isKeyFrame) 0x17 else 0x27
        baos.write(headerByte)
        // Byte 1: AVCPacketType (1 = AVC NALU)
        baos.write(0x01)
        // Bytes 2-4: Composition time = 0
        baos.write(0x00); baos.write(0x00); baos.write(0x00)

        // Empacota NALUs em formato AVCC (tamanho de 4 bytes big endian antes de cada NALU)
        writeAnnexBAsAvcc(baos, naluBytes)

        val packet = baos.toByteArray()
        sendPacket(out, CSID_VIDEO, timestampMs, TYPE_VIDEO, streamId, packet)
    }

    private fun writeAnnexBAsAvcc(out: ByteArrayOutputStream, data: ByteArray) {
        var i = 0
        var lastStart = -1

        while (i < data.size - 3) {
            val isStart4 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            val isStart3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()

            if (isStart4 || isStart3) {
                val startCodeLen = if (isStart4) 4 else 3
                if (lastStart >= 0) {
                    val naluLen = i - lastStart
                    if (naluLen > 0) {
                        out.write((naluLen ushr 24) and 0xFF)
                        out.write((naluLen ushr 16) and 0xFF)
                        out.write((naluLen ushr 8) and 0xFF)
                        out.write(naluLen and 0xFF)
                        out.write(data, lastStart, naluLen)
                    }
                }
                lastStart = i + startCodeLen
                i += startCodeLen
                continue
            }
            i++
        }

        if (lastStart >= 0 && lastStart < data.size) {
            val naluLen = data.size - lastStart
            if (naluLen > 0) {
                out.write((naluLen ushr 24) and 0xFF)
                out.write((naluLen ushr 16) and 0xFF)
                out.write((naluLen ushr 8) and 0xFF)
                out.write(naluLen and 0xFF)
                out.write(data, lastStart, naluLen)
            }
        } else if (lastStart == -1) {
            // Se já não possui start code, escreve o buffer com 4 bytes de tamanho
            val len = data.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(data)
        }
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
        out.flush()
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
            baos.write(aacBytes, headerLen, aacBytes.size - headerLen)
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

    @Synchronized
    override fun close() {
        isPublishing = false
        isConnected = false
        try {
            outStream?.flush()
        } catch (_: Exception) {}
        try {
            inStream?.close()
        } catch (_: Exception) {}
        try {
            outStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        inStream = null
        outStream = null
        Log.i(TAG, "Conexão RTMP encerrada.")
    }
}
