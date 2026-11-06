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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "livenexus_capture_channel"
        private const val NOTIFICATION_ID = 2002

        const val ACTION_START_STREAM = "com.livenexus.controller.ACTION_START_STREAM"
        const val ACTION_STOP_STREAM = "com.livenexus.controller.ACTION_STOP_STREAM"
        const val ACTION_RELOAD_SCENE = "com.livenexus.controller.ACTION_RELOAD_SCENE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_PLATFORM_NAMES = "extra_platform_names"
        const val EXTRA_PLATFORM_URLS = "extra_platform_urls"
        const val EXTRA_PLATFORM_KEYS = "extra_platform_keys"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isConnecting: Boolean = false
            private set

        @Volatile
        var isLiveStreaming: Boolean = false
            private set

        @Volatile
        var streamStartTimeMs: Long = 0L
            private set

        @Volatile
        var lastError: String? = null
            private set

        @Volatile
        var currentWidth: Int = 1920
            private set

        @Volatile
        var currentHeight: Int = 1080
            private set

        @Volatile
        var currentFps: Int = 60
            private set

        @Volatile
        var currentBitrateKbps: Int = 6000
            private set

        val activePlatforms = CopyOnWriteArraySet<String>()

        @Volatile
        private var activeInstance: ScreenCaptureService? = null

        private val stateListeners = CopyOnWriteArraySet<() -> Unit>()

        fun addStateListener(listener: () -> Unit) {
            stateListeners.add(listener)
        }

        fun removeStateListener(listener: () -> Unit) {
            stateListeners.remove(listener)
        }

        private fun notifyListeners() {
            for (listener in stateListeners) {
                try {
                    listener.invoke()
                } catch (_: Throwable) {}
            }
        }

        fun clearError() {
            lastError = null
        }

        fun reloadCurrentScene(): Boolean {
            val instance = activeInstance ?: return false
            return instance.triggerSceneReload()
        }

        // Prévia de baixa resolução exclusivamente para o editor, se a transmissão estiver ativa.
        fun getPreviewSnapshot(maxDimension: Int = 480): Bitmap? {
            if (!isLiveStreaming) return null
            return activeInstance?.copyPreviewSnapshot(maxDimension.coerceIn(160, 640))
        }

        fun updateDynamicBitrate(kbps: Int) {
            val clamped = kbps.coerceIn(2000, 10000)
            currentBitrateKbps = clamped
            activeInstance?.videoEncoder?.updateBitrate(clamped * 1000)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private var videoEncoder: LocalVideoEncoder? = null
    private var audioEncoder: LocalAudioEncoder? = null
    private var streamPublisher: StreamPublisher? = null
    private var sceneCompositor: SceneCompositor? = null

    @Volatile
    private var latestScreenBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private fun copyPreviewSnapshot(maxDimension: Int): Bitmap? = synchronized(bitmapLock) {
        val current = latestScreenBitmap ?: return@synchronized null
        if (current.isRecycled || current.width <= 0 || current.height <= 0) return@synchronized null
        try {
            val targetWidth = minOf(current.width, maxDimension)
            val targetHeight = ((current.height.toLong() * targetWidth) / current.width).toInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
            if (resized === current) current.copy(Bitmap.Config.ARGB_8888, false) else resized
        } catch (e: RuntimeException) {
            Log.w(TAG, "Falha ao gerar prévia da captura: ${e.message}")
            null
        }
    }

    private val stopping = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection finalizada pelo sistema.")
            stopStreamingInternal("Captura de tela encerrada pelo sistema.")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_STREAM

        if (action == ACTION_STOP_STREAM) {
            stopStreamingInternal(null)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_RELOAD_SCENE) {
            triggerSceneReload()
            return START_NOT_STICKY
        }

        // Prevent duplicate encoder initialization
        if (isLiveStreaming || starting.get()) {
            Log.w(TAG, "Transmissão já está ativa ou inicializando; ignorando chamada duplicada.")
            return START_NOT_STICKY
        }

        val notification = buildNotification("Iniciando codificador H.264/AAC e conexão RTMP...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasMicPerm) {
                        fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                }
                startForeground(NOTIFICATION_ID, notification, fgType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar Foreground Service: ${e.message}", e)
            lastError = "Não foi possível iniciar o serviço de captura de tela: ${e.message}"
            notifyListeners()
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        stopping.set(false)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, ProjectionState.resultCode)
            ?: ProjectionState.resultCode
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: ProjectionState.cloneConsentIntent()

        val names = intent?.getStringArrayExtra(EXTRA_PLATFORM_NAMES) ?: emptyArray()
        val urls = intent?.getStringArrayExtra(EXTRA_PLATFORM_URLS) ?: emptyArray()
        val keys = intent?.getStringArrayExtra(EXTRA_PLATFORM_KEYS) ?: emptyArray()
        val targets = mutableListOf<StreamPublisher.StreamTarget>()
        for (i in names.indices) {
            val u = urls.getOrNull(i)
            val k = keys.getOrNull(i)
            if (!u.isNullOrBlank() && !k.isNullOrBlank()) {
                targets.add(
                    StreamPublisher.StreamTarget(
                        platformName = names[i],
                        rtmpUrl = u,
                        streamKey = k
                    )
                )
            }
        }
        if (targets.isEmpty()) {
            targets.addAll(StreamingConfig.getConfiguredStreamTargets(applicationContext))
        }

        if (resultCode == 0 || resultData == null) {
            lastError = "Permissão de captura de tela (MediaProjection) não concedida."
            isRunning = false
            notifyListeners()
            stopSelf()
            return START_NOT_STICKY
        }

        if (targets.isEmpty()) {
            lastError = "Nenhuma plataforma com Stream Key ativa configurada."
            isRunning = false
            notifyListeners()
            stopSelf()
            return START_NOT_STICKY
        }

        startStreamingPipeline(resultCode, resultData, targets)
        return START_NOT_STICKY
    }

    private fun startStreamingPipeline(
        resultCode: Int,
        resultData: Intent,
        targets: List<StreamPublisher.StreamTarget>
    ) {
        if (!starting.compareAndSet(false, true)) return
        isConnecting = true
        lastError = null
        notifyListeners()

        Thread({
            try {
                // 1. Obtain MediaProjection
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: throw IllegalStateException("MediaProjection retornou nulo.")
                mediaProjection = projection

                val callbackHandler = Handler(mainLooper)
                projection.registerCallback(projectionCallback, callbackHandler)

                // 2. Read user encoder settings from SharedPreferences
                val prefs = getSharedPreferences(StreamingConfig.CAPTURE_PREFS, Context.MODE_PRIVATE)
                val baseResolution = prefs.getInt("resolution", 1920)
                val fps = if (prefs.getInt("fps", 60) <= 30) 30 else 60
                val bitrateKbps = prefs.getInt("bitrate", 6000).coerceIn(2000, 10000)
                val aspectRatio = prefs.getString("aspect_ratio", "16:9") ?: "16:9"

                val longEdge = if (baseResolution <= 1280) 1280 else 1920
                val shortEdge = if (baseResolution <= 1280) 720 else 1080

                val width = if (aspectRatio == "9:16") shortEdge else longEdge
                val height = if (aspectRatio == "9:16") longEdge else shortEdge
                val densityDpi = resources.displayMetrics.densityDpi

                currentWidth = width
                currentHeight = height
                currentFps = fps
                currentBitrateKbps = bitrateKbps

                // 3. Connect RTMP / RTMPS transport first
                val publisher = StreamPublisher(
                    targets = targets,
                    width = width,
                    height = height,
                    fps = fps,
                    videoBitrateKbps = bitrateKbps
                )
                streamPublisher = publisher

                activePlatforms.clear()
                val errorsByPlatform = publisher.start()
                activePlatforms.addAll(publisher.connectedPlatforms)

                if (!publisher.hasActivePublishing) {
                    val errorSummary = errorsByPlatform.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                    throw IllegalStateException(
                        if (errorSummary.isNotBlank()) "Falha RTMP/RTMPS ($errorSummary)"
                        else "Falha ao conectar ao servidor RTMP/RTMPS. Verifique a Stream Key e sua conexão."
                    )
                }

                // 4. Start H.264 MediaCodec Video Encoder
                val vEncoder = LocalVideoEncoder(
                    width = width,
                    height = height,
                    fps = fps,
                    bitrateKbps = bitrateKbps,
                    onSample = { naluBytes, info ->
                        publisher.onVideoSample(naluBytes, info)
                    },
                    onFormat = { _ -> },
                    onSpsPps = { sps, pps ->
                        publisher.onVideoHeader(sps, pps)
                    }
                )
                videoEncoder = vEncoder
                vEncoder.start()
                val encoderInputSurface = vEncoder.surface

                // 5. Start MediaProjection VirtualDisplay + ImageReader + SceneCompositor
                val imgThread = HandlerThread("LiveNexusScreenCaptureThread").apply { start() }
                imageThread = imgThread
                val imgHandler = Handler(imgThread.looper)
                imageHandler = imgHandler

                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                imageReader = reader
                reader.setOnImageAvailableListener({ ir ->
                    val image = try {
                        ir.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    } ?: return@setOnImageAvailableListener

                    try {
                        val planes = image.planes
                        if (planes.isNotEmpty()) {
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width
                            val bmpWidth = width + rowPadding / pixelStride

                            val tempBmp = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888)
                            tempBmp.copyPixelsFromBuffer(buffer)
                            val finalBmp = if (bmpWidth != width) {
                                val cropped = Bitmap.createBitmap(tempBmp, 0, 0, width, height)
                                tempBmp.recycle()
                                cropped
                            } else {
                                tempBmp
                            }

                            synchronized(bitmapLock) {
                                val old = latestScreenBitmap
                                latestScreenBitmap = finalBmp
                                if (old != null && old !== finalBmp && !old.isRecycled) {
                                    old.recycle()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao processar quadro da captura de tela: ${e.message}")
                    } finally {
                        try {
                            image.close()
                        } catch (_: Exception) {}
                    }
                }, imgHandler)

                virtualDisplay = projection.createVirtualDisplay(
                    "LiveNexusScreenCapture",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    imgHandler
                )

                val compositor = SceneCompositor(
                    outputSurface = encoderInputSurface,
                    width = width,
                    height = height,
                    fps = fps
                )
                sceneCompositor = compositor
                compositor.start { canvas, cWidth, cHeight ->
                    val layersSnapshot = StreamingConfig.activeSceneLayers.toList()
                    synchronized(bitmapLock) {
                        SceneRenderer.renderScene(canvas, cWidth, cHeight, layersSnapshot, latestScreenBitmap)
                    }
                }

                // 6. Start AAC MediaCodec Audio Encoder + Microphone AudioRecord + Internal Audio
                val audioPrefs = getSharedPreferences(StreamingConfig.AUDIO_PREFS, Context.MODE_PRIVATE)
                val aEncoder = LocalAudioEncoder(
                    sampleRate = 44100,
                    channelCount = 2,
                    bitrateBps = 128_000,
                    micVolumeProvider = {
                        val enabled = audioPrefs.getBoolean("mic_enabled", true)
                        if (!enabled) 0f else (audioPrefs.getInt("mic_volume", 100) / 100f).coerceIn(0f, 1f)
                    },
                    internalVolumeProvider = {
                        val enabled = audioPrefs.getBoolean("internal_enabled", true)
                        if (!enabled) 0f else (audioPrefs.getInt("internal_volume", 100) / 100f).coerceIn(0f, 1f)
                    },
                    noiseSuppressionProvider = {
                        audioPrefs.getBoolean("noise_suppression", false)
                    },
                    mediaProjectionProvider = {
                        mediaProjection
                    },
                    onHeader = { asc ->
                        publisher.onAudioHeader(asc)
                    },
                    onSample = { aacBytes, info ->
                        publisher.onAudioSample(aacBytes, info)
                    }
                )
                audioEncoder = aEncoder
                aEncoder.start()

                streamStartTimeMs = System.currentTimeMillis()
                isConnecting = false
                isLiveStreaming = true
                updateNotification("Transmitindo ao vivo (${width}x${height} @ ${fps}fps • ${bitrateKbps}kbps)")
                notifyListeners()
                Log.i(TAG, "Pipeline nativo H.264 + AAC + RTMP iniciado com sucesso: ${width}x${height} @ ${fps}fps, ${bitrateKbps}kbps")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao inicializar transmissão: ${e.message}", e)
                isConnecting = false
                isLiveStreaming = false
                stopStreamingInternal(e.message ?: "Erro ao iniciar o encoder/transmissão.")
                stopSelf()
            } finally {
                starting.set(false)
            }
        }, "LiveNexusStreamStarter").start()
    }

    fun triggerSceneReload(): Boolean {
        return try {
            videoEncoder?.requestKeyFrame()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopStreamingInternal(errorReason: String?) {
        if (!stopping.compareAndSet(false, true)) return

        isConnecting = false
        isLiveStreaming = false
        isRunning = false
        streamStartTimeMs = 0L
        activePlatforms.clear()
        if (!errorReason.isNullOrBlank()) {
            lastError = errorReason
        }

        try {
            sceneCompositor?.close()
        } catch (_: Exception) {}
        sceneCompositor = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        try {
            imageThread?.quitSafely()
        } catch (_: Exception) {}
        imageThread = null
        imageHandler = null

        try {
            audioEncoder?.close()
        } catch (_: Exception) {}
        audioEncoder = null

        try {
            videoEncoder?.close()
        } catch (_: Exception) {}
        videoEncoder = null

        try {
            streamPublisher?.close()
        } catch (_: Exception) {}
        streamPublisher = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {}
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        synchronized(bitmapLock) {
            try {
                latestScreenBitmap?.recycle()
            } catch (_: Exception) {}
            latestScreenBitmap = null
        }

        notifyListeners()
    }

    override fun onDestroy() {
        stopStreamingInternal(null)
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LiveNEXUS Transmissão Ao Vivo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Serviço de codificação H.264/AAC e transmissão RTMP/RTMPS"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("LiveNEXUS Controller")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
