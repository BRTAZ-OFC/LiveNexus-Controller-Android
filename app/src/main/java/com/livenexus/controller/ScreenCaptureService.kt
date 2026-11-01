package com.livenexus.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONArray

/**
 * Serviço de captura de tela e áudio com transmissão ao vivo multiplexada em RTMP/RTMPS.
 */
class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "LiveNexusCapture"
        const val ACTION_START = "com.livenexus.controller.capture.START"
        const val ACTION_STOP = "com.livenexus.controller.capture.STOP"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_MAX_DIMENSION = "capture_max_dimension"
        const val EXTRA_FPS = "capture_fps"
        const val EXTRA_BITRATE_KBPS = "capture_bitrate_kbps"
        const val EXTRA_TARGETS_JSON = "capture_targets_json"

        @Volatile var previewFrame: Bitmap? = null
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var isCapturing: Boolean = false
            private set
        @Volatile var isConnecting: Boolean = false
            private set
        @Volatile var isLiveStreaming: Boolean = false
            private set
        @Volatile var activePlatforms: List<String> = emptyList()
            private set
        @Volatile var activeSceneLayers: List<CaptureLayer>? = null

        fun updateActiveSceneLayers(layers: List<CaptureLayer>?) {
            activeSceneLayers = layers?.toList()
        }

        fun reportError(message: String?) {
            lastError = message
        }

        private const val CHANNEL_ID = "livenexus_screen_capture"
        private const val NOTIFICATION_ID = 2
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var compositor: SceneCompositor? = null
    private var videoEncoder: LocalVideoEncoder? = null
    private var audioEncoder: LocalAudioEncoder? = null
    private var publisher: StreamPublisher? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var latestCapturedScreenFrame: Bitmap? = null
    private var screenBufferBitmap: Bitmap? = null
    private var captureWidth: Int = 1280
    private var captureHeight: Int = 720

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transmissão e Captura LiveNEXUS",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        val targetsJson = intent.getStringExtra(EXTRA_TARGETS_JSON)
        val streamTargets = parseTargets(targetsJson)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE_KBPS, 4500)

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Se a captura de tela já estiver ativa (ex.: iniciada na aba Captura), reutiliza a sessão atual
        if (isCapturing && projection != null && virtualDisplay != null) {
            if (streamTargets.isNotEmpty()) {
                val evenW = (captureWidth - captureWidth % 2).coerceAtLeast(2)
                val evenH = (captureHeight - captureHeight % 2).coerceAtLeast(2)
                startStreamingPipelineAsync(
                    streamTargets = streamTargets,
                    evenWidth = evenW,
                    evenHeight = evenH,
                    fps = fps,
                    bitrate = bitrate,
                    stopPendingIntent = stopPendingIntent,
                    stopServiceOnFailure = false
                )
            }
            return START_NOT_STICKY
        }

        val consent = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (resultCode != android.app.Activity.RESULT_OK || consent == null) {
            lastError = "Captura de tela indisponível neste ambiente"
            isConnecting = false
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            lastError = null
            previewFrame = null
            isLiveStreaming = false
            activePlatforms = emptyList()
            isConnecting = streamTargets.isNotEmpty()

            val notificationTitle = if (streamTargets.isNotEmpty()) {
                "LiveNEXUS - Transmissão Ao Vivo"
            } else {
                "LiveNEXUS - Captura de Tela"
            }

            val notificationText = if (streamTargets.isNotEmpty()) {
                "Transmissão ao vivo conectando..."
            } else {
                "Captura local ativa. Nenhuma transmissão iniciada."
            }

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Parar transmissão", stopPendingIntent)
                .build()

            val fgType = if (streamTargets.isNotEmpty()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

            startForeground(NOTIFICATION_ID, notification, fgType)

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = projectionManager.getMediaProjection(resultCode, consent)
                ?: throw IllegalStateException("Captura de tela indisponível neste ambiente")
            projection = activeProjection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    isCapturing = false
                    isConnecting = false
                    isLiveStreaming = false
                    stopSelf()
                }
            }
            projectionCallback = callback
            activeProjection.registerCallback(callback, null)

            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val sourceWidth = metrics.widthPixels.coerceAtLeast(2)
            val sourceHeight = metrics.heightPixels.coerceAtLeast(2)
            val maxDimension = intent.getIntExtra(EXTRA_MAX_DIMENSION, 1280).coerceIn(480, 1920)
            val scale = minOf(1.0, maxDimension.toDouble() / maxOf(sourceWidth, sourceHeight))
            val width = (sourceWidth * scale).toInt().coerceAtLeast(2)
            val height = (sourceHeight * scale).toInt().coerceAtLeast(2)
            captureWidth = width
            captureHeight = height

            // Prévia local na UI do editor de cenas
            val captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = captureReader
            val worker = HandlerThread("LiveNexusCapturePreview").also { it.start() }
            captureThread = worker
            var lastPreviewAt = 0L

            captureReader.setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: RuntimeException) { null }
                if (image != null) {
                    try {
                        val plane = image.planes.firstOrNull()
                        if (plane != null && plane.pixelStride == 4) {
                            val paddedWidth = plane.rowStride / plane.pixelStride
                            if (paddedWidth >= image.width) {
                                var buffer = screenBufferBitmap
                                if (buffer == null || buffer.width != paddedWidth || buffer.height != image.height) {
                                    buffer = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                                    screenBufferBitmap = buffer
                                }
                                plane.buffer.rewind()
                                buffer.copyPixelsFromBuffer(plane.buffer)
                                val trimmed = if (paddedWidth == image.width) buffer else {
                                    Bitmap.createBitmap(buffer, 0, 0, image.width, image.height)
                                }
                                latestCapturedScreenFrame = trimmed

                                // Atualiza prévia local quando a transmissão não estiver ativa
                                if (compositor == null) {
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - lastPreviewAt >= 500L) {
                                        lastPreviewAt = now
                                        val maxPreviewWidth = 480
                                        val ratio = minOf(1f, maxPreviewWidth.toFloat() / trimmed.width.toFloat())
                                        previewFrame = if (ratio < 1f) {
                                            Bitmap.createScaledBitmap(
                                                trimmed,
                                                (trimmed.width * ratio).toInt().coerceAtLeast(1),
                                                (trimmed.height * ratio).toInt().coerceAtLeast(1),
                                                true
                                            )
                                        } else trimmed
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Falha ao processar frame de tela", error)
                    } finally {
                        image.close()
                    }
                }
            }, Handler(worker.looper))

            virtualDisplay = activeProjection.createVirtualDisplay(
                "LiveNEXUS Screen Capture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                captureReader.surface,
                null,
                null
            )
            if (virtualDisplay == null) throw IllegalStateException("Captura de tela indisponível neste ambiente")

            isCapturing = true
            val evenWidth = (width - width % 2).coerceAtLeast(2)
            val evenHeight = (height - height % 2).coerceAtLeast(2)

            if (streamTargets.isNotEmpty()) {
                startStreamingPipelineAsync(
                    streamTargets = streamTargets,
                    evenWidth = evenWidth,
                    evenHeight = evenHeight,
                    fps = fps,
                    bitrate = bitrate,
                    stopPendingIntent = stopPendingIntent,
                    stopServiceOnFailure = true
                )
            }
        } catch (error: Throwable) {
            val msg = error.message ?: "Falha ao iniciar transmissão"
            lastError = msg
            isConnecting = false
            Log.e(TAG, "Falha ao iniciar captura/transmissão", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startStreamingPipelineAsync(
        streamTargets: List<StreamPublisher.StreamTarget>,
        evenWidth: Int,
        evenHeight: Int,
        fps: Int,
        bitrate: Int,
        stopPendingIntent: PendingIntent,
        stopServiceOnFailure: Boolean
    ) {
        stopStreamingOnly()
        lastError = null
        isLiveStreaming = false
        activePlatforms = emptyList()
        isConnecting = true

        Thread({
            try {
                val streamPub = StreamPublisher(
                    targets = streamTargets,
                    width = evenWidth,
                    height = evenHeight,
                    fps = fps,
                    videoBitrateKbps = bitrate
                )
                val connErrors = streamPub.start()
                if (!streamPub.hasActivePublishing) {
                    val errMsg = connErrors.entries.firstOrNull()?.let { "${it.key}: ${it.value}" }
                        ?: "Falha ao conectar aos servidores RTMP"
                    lastError = errMsg
                    streamPub.close()
                    throw IllegalStateException(errMsg)
                }

                publisher = streamPub
                isLiveStreaming = true
                activePlatforms = streamPub.connectedPlatforms

                // Atualiza notificação com as plataformas conectadas
                val updatedNotif = Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("LiveNEXUS - Transmissão Ao Vivo")
                    .setContentText("Transmitindo ao vivo para: ${activePlatforms.joinToString(", ")}")
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_media_pause, "Parar transmissão", stopPendingIntent)
                    .build()
                val notificationsMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationsMgr.notify(NOTIFICATION_ID, updatedNotif)

                // Encoder de Vídeo H.264
                val encoder = LocalVideoEncoder(
                    width = evenWidth,
                    height = evenHeight,
                    fps = fps,
                    bitrateKbps = bitrate,
                    onSample = { bytes, info -> streamPub.onVideoSample(bytes, info) },
                    onFormat = { _ -> },
                    onSpsPps = { sps, pps -> streamPub.onVideoHeader(sps, pps) }
                )
                encoder.start()
                videoEncoder = encoder

                // Compositor de Cenário do LiveNEXUS: transmite o canvas renderizado (tela, texto, overlays)
                val initialLayers = activeSceneLayers ?: loadPersistedSceneLayers(this)
                val sceneComp = SceneCompositor(
                    outputSurface = encoder.surface,
                    width = evenWidth,
                    height = evenHeight,
                    fps = fps,
                    onFrameRendered = { liveBitmap ->
                        val maxPreviewWidth = 480
                        val ratio = minOf(1f, maxPreviewWidth.toFloat() / liveBitmap.width.toFloat())
                        previewFrame = if (ratio < 1f) {
                            Bitmap.createScaledBitmap(
                                liveBitmap,
                                (liveBitmap.width * ratio).toInt().coerceAtLeast(1),
                                (liveBitmap.height * ratio).toInt().coerceAtLeast(1),
                                true
                            )
                        } else {
                            Bitmap.createBitmap(liveBitmap)
                        }
                    }
                )
                sceneComp.start { canvas, w, h ->
                    val layers = activeSceneLayers ?: initialLayers
                    SceneRenderer.render(
                        canvas = canvas,
                        width = w,
                        height = h,
                        layers = layers,
                        screenFrame = latestCapturedScreenFrame,
                        isLiveStream = true
                    )
                }
                compositor = sceneComp

                // Encoder de Áudio AAC (Microfone)
                try {
                    val aEncoder = LocalAudioEncoder(
                        sampleRate = 44100,
                        channelCount = 2,
                        bitrateBps = 128000,
                        onHeader = { asc -> streamPub.onAudioHeader(asc) },
                        onSample = { bytes, info -> streamPub.onAudioSample(bytes, info) }
                    )
                    aEncoder.start()
                    audioEncoder = aEncoder
                } catch (audioErr: Throwable) {
                    Log.w(TAG, "Falha ao iniciar captura de áudio", audioErr)
                }
            } catch (error: Throwable) {
                val msg = error.message ?: "Falha ao conectar ao servidor RTMP"
                lastError = msg
                isLiveStreaming = false
                activePlatforms = emptyList()
                Log.e(TAG, "Falha ao iniciar pipeline de transmissão RTMP", error)
                stopStreamingOnly()
                if (stopServiceOnFailure) {
                    stopSelf()
                }
            } finally {
                isConnecting = false
            }
        }, "LiveNexusStreamStarter").start()
    }

    private fun stopStreamingOnly() {
        isLiveStreaming = false
        activePlatforms = emptyList()
        try {
            compositor?.close()
        } catch (_: Exception) {}
        compositor = null

        try {
            audioEncoder?.close()
        } catch (_: Exception) {}
        audioEncoder = null

        try {
            videoEncoder?.close()
        } catch (_: Exception) {}
        videoEncoder = null

        try {
            publisher?.close()
        } catch (_: Exception) {}
        publisher = null
    }

    private fun parseTargets(json: String?): List<StreamPublisher.StreamTarget> {
        if (json.isNullOrBlank()) return emptyList()
        val result = mutableListOf<StreamPublisher.StreamTarget>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val platform = obj.optString("platform", "Live")
                val url = obj.optString("url", "").trim()
                val key = obj.optString("key", "").trim()
                if (url.isNotBlank() && key.isNotBlank()) {
                    result.add(StreamPublisher.StreamTarget(platform, url, key))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao decodificar destinos RTMP", e)
        }
        return result
    }

    private fun loadPersistedSceneLayers(context: Context): List<CaptureLayer> {
        try {
            val prefs = context.getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
            val saved = JSONArray(prefs.getString("capture_scenes", "[]"))
            val activeId = prefs.getString("active_capture_scene", "") ?: ""
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                if (activeId.isNotBlank() && id != activeId && saved.length() > 1 && i < saved.length() - 1) continue
                val sources = mutableListOf<CaptureLayer>()
                val list = item.optJSONArray("sources")
                for (j in 0 until (list?.length() ?: 0)) {
                    val raw = list?.opt(j)
                    if (raw is org.json.JSONObject) {
                        val type = raw.optString("type")
                        if (type != "screen" && type != "text" && type != "browser") continue
                        sources.add(CaptureLayer(
                            raw.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            type,
                            raw.optString("name").ifBlank { if (type == "screen") "Captura de tela" else "Texto" },
                            raw.optDouble("x", 0.0).toFloat().coerceIn(-2f, 2f),
                            raw.optDouble("y", 0.0).toFloat().coerceIn(-2f, 2f),
                            raw.optDouble("width", 1.0).toFloat().coerceIn(0.08f, 3f),
                            raw.optDouble("height", 1.0).toFloat().coerceIn(0.08f, 3f),
                            raw.optBoolean("visible", true),
                            raw.optBoolean("locked", false),
                            raw.optString("url", "")
                        ))
                    }
                }
                if (sources.isNotEmpty()) return sources
            }
        } catch (_: Exception) {}
        return listOf(CaptureLayer("default-screen", "screen", "Captura de tela", 0f, 0f, 1f, 1f))
    }

    override fun onDestroy() {
        isCapturing = false
        isConnecting = false
        stopStreamingOnly()
        latestCapturedScreenFrame = null
        screenBufferBitmap = null

        virtualDisplay?.release()
        virtualDisplay = null

        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null

        captureThread?.quitSafely()
        captureThread = null
        previewFrame = null

        val currentProjection = projection
        val callback = projectionCallback
        projectionCallback = null
        projection = null
        if (currentProjection != null) {
            if (callback != null) currentProjection.unregisterCallback(callback)
            currentProjection.stop()
        }
        super.onDestroy()
    }
}
