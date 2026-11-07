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
import android.graphics.Point
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
import android.view.Display
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

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
    private var displayManager: DisplayManager? = null
    private var captureDisplayListener: DisplayManager.DisplayListener? = null
    private var captureDensityDpi = 0

    private fun currentDisplaySize(): Pair<Int, Int> {
        // A resolucao do smartphone e independente da resolucao do encoder.
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealSize(size)
        } else {
            val metrics = resources.displayMetrics
            size.set(metrics.widthPixels, metrics.heightPixels)
        }
        // Limita custo de memoria em aparelhos QHD/4K, conservando a proporcao.
        val longest = max(size.x, size.y).coerceAtLeast(1)
        val scale = minOf(1.0, 2560.0 / longest)
        val w = ((size.x * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((size.y * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return w to h
    }

    private fun createCaptureReader(width: Int, height: Int, handler: Handler): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val frameWidth = image.width
                val frameHeight = image.height
                if (pixelStride != 4 || rowStride < frameWidth * pixelStride) return@setOnImageAvailableListener

                val paddedWidth = rowStride / pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, frameHeight, Bitmap.Config.ARGB_8888)
                var frame: Bitmap? = null
                try {
                    padded.copyPixelsFromBuffer(plane.buffer)
                    frame = if (paddedWidth == frameWidth) padded
                        else Bitmap.createBitmap(padded, 0, 0, frameWidth, frameHeight)
                    synchronized(bitmapLock) {
                        val previous = latestScreenBitmap
                        latestScreenBitmap = frame
                        if (previous !== frame && previous != null && !previous.isRecycled) previous.recycle()
                    }
                } finally {
                    if (frame !== padded && !padded.isRecycled) padded.recycle()
                }
            } catch (error: Exception) {
                Log.w(TAG, "Falha ao ler quadro da MediaProjection: ${error.message}")
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
        }, handler)
        return reader
    }

    private fun resizeCapture(width: Int, height: Int) {
        if (width < 2 || height < 2 || stopping.get()) return
        val handler = imageHandler ?: return
        handler.post {
            if (stopping.get()) return@post
            val display = virtualDisplay ?: return@post
            val oldReader = imageReader ?: return@post
            if (oldReader.width == width && oldReader.height == height) return@post

            var newReader: ImageReader? = null
            try {
                newReader = createCaptureReader(width, height, handler)
                // Desacoplar antes de trocar a Surface evita frames obsoletos.
                display.setSurface(null)
                display.resize(width, height, captureDensityDpi)
                display.setSurface(newReader.surface)
                imageReader = newReader
                oldReader.setOnImageAvailableListener(null, null)
                oldReader.close()
                Log.i(TAG, "Captura ajustada para ${width}x${height}; encoder inalterado")
            } catch (error: Exception) {
                Log.e(TAG, "Falha ao ajustar dimensoes da captura", error)
                try {
                    display.resize(oldReader.width, oldReader.height, captureDensityDpi)
                    display.setSurface(oldReader.surface)
                } catch (rollbackError: Exception) {
                    Log.e(TAG, "Falha ao restaurar a Surface da captura", rollbackError)
                }
                try { newReader?.close() } catch (_: Exception) {}
            }
        }
    }

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

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT >= 34) {
                // A14+: a regiao compartilhada pode ser uma janela, nao a tela toda.
                val longest = max(width, height).coerceAtLeast(1)
                val scale = minOf(1.0, 2560.0 / longest)
                resizeCapture(
                    ((width * scale).toInt() / 2 * 2).coerceAtLeast(2),
                    ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
                )
            }
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
        if (stopping.get()) {
            starting.set(false)
            return
        }
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
                if (stopping.get()) return@Thread
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
                if (stopping.get()) return@Thread
                val encoderInputSurface = vEncoder.surface

                // 5. Start MediaProjection VirtualDisplay + ImageReader + SceneCompositor
                val imgThread = HandlerThread("LiveNexusScreenCaptureThread").apply { start() }
                imageThread = imgThread
                val imgHandler = Handler(imgThread.looper)
                imageHandler = imgHandler

                captureDensityDpi = resources.configuration.densityDpi
                val (captureWidth, captureHeight) = currentDisplaySize()
                val reader = createCaptureReader(captureWidth, captureHeight, imgHandler)
                imageReader = reader

                virtualDisplay = projection.createVirtualDisplay(
                    "LiveNexusScreenCapture",
                    captureWidth,
                    captureHeight,
                    captureDensityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    imgHandler
                )

                // Android 10-13 nao fornece onCapturedContentResize.
                // Acompanhar rotacao do display sem recriar a MediaProjection.
                if (Build.VERSION.SDK_INT < 34) {
                    val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val listener = object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {}
                        override fun onDisplayRemoved(displayId: Int) {}
                        override fun onDisplayChanged(displayId: Int) {
                            if (displayId != Display.DEFAULT_DISPLAY || stopping.get()) return
                            Handler(mainLooper).postDelayed({
                                if (!stopping.get()) {
                                    val (w, h) = currentDisplaySize()
                                    resizeCapture(w, h)
                                }
                            }, 200L)
                        }
                    }
                    displayManager = manager
                    captureDisplayListener = listener
                    manager.registerDisplayListener(listener, Handler(mainLooper))
                    val (w, h) = currentDisplaySize()
                    resizeCapture(w, h)
                }
                if (stopping.get()) return@Thread

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
                if (stopping.get()) return@Thread

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
                        // Preferência lida durante a transmissão: switch funciona sem reiniciar.
                        audioPrefs.getBoolean("noise_suppression", true)
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
                if (stopping.get()) return@Thread

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

        captureDisplayListener?.let { listener ->
            try { displayManager?.unregisterDisplayListener(listener) } catch (_: Exception) {}
        }
        captureDisplayListener = null
        displayManager = null

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
