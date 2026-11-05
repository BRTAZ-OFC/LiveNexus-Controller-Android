package com.livenexus.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
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
import kotlin.math.abs

/**
 * Serviço de captura da fonte "Captura de tela" (Editor de Cena) e transmissão ao vivo do cenário em RTMP/RTMPS.
 */
class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "LiveNexusCapture"
        const val ACTION_START = "com.livenexus.controller.capture.START"
        const val ACTION_STOP_CAPTURE = "com.livenexus.controller.capture.STOP_CAPTURE"
        const val ACTION_START_STREAM = "com.livenexus.controller.capture.START_STREAM"
        const val ACTION_STOP_STREAM = "com.livenexus.controller.capture.STOP_STREAM"
        const val ACTION_STOP = "com.livenexus.controller.capture.STOP"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_MAX_DIMENSION = "capture_max_dimension"
        const val EXTRA_FPS = "capture_fps"
        const val EXTRA_BITRATE_KBPS = "capture_bitrate_kbps"
        const val EXTRA_TARGETS_JSON = "capture_targets_json"
        const val EXTRA_VERTICAL_MODE = "capture_vertical_mode"

        @Volatile private var activeInstance: ScreenCaptureService? = null

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
        @Volatile var isVerticalMode: Boolean = false

        fun updateActiveSceneLayers(layers: List<CaptureLayer>?) {
            activeSceneLayers = layers?.toList()
            activeInstance?.let { service ->
                val currentCompositor = service.compositor
                if (currentCompositor != null) {
                    service.browserEngine?.updateLayers(activeSceneLayers ?: emptyList(), currentCompositor.width, currentCompositor.height)
                }
            }
        }

        /** Fonte de preview ao vivo sem segunda WebView / segundo Consumer. */
        fun drawBrowserPreviewFrame(id: String, canvas: Canvas, rect: RectF, paint: Paint): Boolean {
            val service = activeInstance ?: return false
            if (!isConnecting && !isLiveStreaming) return false
            return service.browserEngine?.drawFrame(id, canvas, rect, paint) == true
        }

        fun updateCaptureOrientation(vertical: Boolean) {
            val changed = (isVerticalMode != vertical)
            isVerticalMode = vertical
            if (changed) {
                activeInstance?.onOrientationModeChanged(vertical)
            }
        }

        fun reportError(message: String?) {
            lastError = message
        }

        fun computeTargetDimensions(maxDimension: Int, vertical: Boolean): Pair<Int, Int> {
            val clampedMax = maxDimension.coerceIn(480, 1920)
            val longSide = (clampedMax - (clampedMax % 2)).coerceAtLeast(480)
            val rawShort = (longSide * 9) / 16
            val shortSide = (rawShort - (rawShort % 2)).coerceAtLeast(270)
            return if (vertical) {
                Pair(shortSide, longSide) // 9:16
            } else {
                Pair(longSide, shortSide) // 16:9
            }
        }

        private const val CHANNEL_ID = "livenexus_screen_capture"
        private const val NOTIFICATION_ID = 2
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var compositor: SceneCompositor? = null
    private var browserEngine: BrowserSourceEngine? = null
    private var videoEncoder: LocalVideoEncoder? = null
    private var audioEncoder: LocalAudioEncoder? = null
    private var publisher: StreamPublisher? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    @Volatile private var latestCapturedScreenFrame: Bitmap? = null
    private var screenBufferBitmap: Bitmap? = null
    private val ringBitmaps = arrayOfNulls<Bitmap>(3)
    private var ringIndex: Int = 0
    private val previewBitmaps = arrayOfNulls<Bitmap>(2)
    private var previewIndex: Int = 0
    private val transformPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var captureWidth: Int = 1280
    private var captureHeight: Int = 720
    private var configuredMaxDimension: Int = 1280
    private var configuredFps: Int = 30
    private var configuredBitrateKbps: Int = 4500
    private var lastDisplayRotation: Int = -1
    private var lastPreviewAt: Long = 0L

    private val captureLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transmissão e Captura LiveNEXUS",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        registerDisplayRotationListener()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        captureHandler?.post {
            reconfigureCaptureForCurrentOrientation(force = false)
        }
    }

    private fun registerDisplayRotationListener() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                        captureHandler?.post {
                            reconfigureCaptureForCurrentOrientation(force = false)
                        }
                    }
                }
            }
            displayListener = listener
            dm.registerDisplayListener(listener, Handler(mainLooper))
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível registrar DisplayListener", e)
        }
    }

    private fun unregisterDisplayRotationListener() {
        val listener = displayListener ?: return
        displayListener = null
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(listener)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_CAPTURE -> {
                stopCaptureOnly()
                if (!isLiveStreaming && !isConnecting) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_STREAM -> {
                stopStreamingOnly()
                stopCaptureOnly()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_STREAM -> {
                handleStartStream(intent)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                handleStartCapture(intent)
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun buildStopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateForegroundNotification() {
        val title = if (isLiveStreaming || isConnecting) {
            "LiveNEXUS - Transmissão Ao Vivo"
        } else {
            "LiveNEXUS - Fonte: Captura de Tela"
        }
        val text = when {
            isLiveStreaming -> "Transmitindo cenário para: ${activePlatforms.joinToString(", ")}"
            isConnecting -> "Conectando transmissão ao vivo..."
            isCapturing -> "Fonte 'Captura de tela' ativa no Editor de Cena."
            else -> "Serviço ativo."
        }
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Parar", buildStopPendingIntent())
            .build()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, notif)
    }

    /**
     * Chamado quando o usuário alterna entre Horizontal (16:9) e Vertical (9:16) no cenário.
     */
    private fun onOrientationModeChanged(vertical: Boolean) {
        isVerticalMode = vertical
        captureHandler?.post {
            reconfigureCaptureForCurrentOrientation(force = true)
        }
        if (isLiveStreaming) {
            Thread({
                reconfigureStreamingOrientation(vertical)
            }, "LiveNexusStreamOrientationUpdater").start()
        }
    }

    private fun reconfigureStreamingOrientation(vertical: Boolean) {
        val activePub = publisher ?: return
        if (!isLiveStreaming) return
        val (newW, newH) = computeTargetDimensions(configuredMaxDimension, vertical)
        val fps = configuredFps
        val bitrate = configuredBitrateKbps

        try {
            val oldCompositor = compositor
            val oldEncoder = videoEncoder
            compositor = null
            videoEncoder = null
            try { oldCompositor?.close() } catch (_: Exception) {}
            try { oldEncoder?.close() } catch (_: Exception) {}

            activePub.updateVideoDimensions(newW, newH)

            val newEncoder = LocalVideoEncoder(
                width = newW,
                height = newH,
                fps = fps,
                bitrateKbps = bitrate,
                onSample = { bytes, info -> activePub.onVideoSample(bytes, info) },
                onFormat = { _ -> },
                onSpsPps = { sps, pps -> activePub.onVideoHeader(sps, pps) }
            )
            newEncoder.start()
            videoEncoder = newEncoder

            val initialLayers = activeSceneLayers ?: loadPersistedSceneLayers(this)
            browserEngine?.updateLayers(initialLayers, newW, newH)
            val newCompositor = SceneCompositor(
                outputSurface = newEncoder.surface,
                width = newW,
                height = newH,
                fps = fps,
                onFrameRendered = null
            )
            newCompositor.start { canvas, w, h ->
                val layers = activeSceneLayers ?: initialLayers
                val screenSourceBitmap = if (isCapturing) latestCapturedScreenFrame else null
                SceneRenderer.render(
                    canvas = canvas,
                    width = w,
                    height = h,
                    layers = layers,
                    screenFrame = screenSourceBitmap,
                    isLiveStream = true,
                    browserFrameDrawer = { id, targetCanvas, targetRect, paint ->
                        browserEngine?.drawFrame(id, targetCanvas, targetRect, paint) == true
                    }
                )
            }
            compositor = newCompositor
            Log.i(TAG, "Encoder e Compositor reconfigurados para ${newW}x${newH} (vertical=$vertical)")
        } catch (e: Throwable) {
            Log.e(TAG, "Erro ao reconfigurar orientação do stream", e)
        }
    }

    /**
     * Inicia a fonte "Captura de tela" respeitando a orientação selecionada (16:9 Horizontal ou 9:16 Vertical).
     */
    private fun handleStartCapture(intent: Intent) {
        if (intent.hasExtra(EXTRA_VERTICAL_MODE)) {
            isVerticalMode = intent.getBooleanExtra(EXTRA_VERTICAL_MODE, isVerticalMode)
        } else {
            val prefs = getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
            isVerticalMode = prefs.getString("capture_orientation", "horizontal") == "vertical"
        }
        configuredMaxDimension = intent.getIntExtra(EXTRA_MAX_DIMENSION, configuredMaxDimension).coerceIn(480, 1920)
        configuredFps = intent.getIntExtra(EXTRA_FPS, configuredFps).coerceIn(15, 60)
        configuredBitrateKbps = intent.getIntExtra(EXTRA_BITRATE_KBPS, configuredBitrateKbps).coerceIn(500, 20000)

        if (isCapturing && projection != null && virtualDisplay != null) {
            captureHandler?.post {
                reconfigureCaptureForCurrentOrientation(force = true)
            }
            return
        }

        val consent = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (resultCode != android.app.Activity.RESULT_OK || consent == null) {
            lastError = "Captura de tela indisponível neste ambiente"
            if (!isLiveStreaming) stopSelf()
            return
        }

        try {
            lastError = null
            previewFrame = null

            val notif = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("LiveNEXUS - Fonte: Captura de Tela")
                .setContentText("Fonte 'Captura de tela' ativa no Editor de Cena.")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Parar", buildStopPendingIntent())
                .build()

            val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val fgType = if ((isLiveStreaming || isConnecting) && hasMicPerm) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notif, fgType)

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = projectionManager.getMediaProjection(resultCode, consent)
                ?: throw IllegalStateException("Captura de tela indisponível neste ambiente")
            projection = activeProjection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCaptureOnly()
                    if (!isLiveStreaming && !isConnecting) {
                        stopSelf()
                    }
                }
            }
            projectionCallback = callback
            activeProjection.registerCallback(callback, null)

            val worker = HandlerThread("LiveNexusCapturePreview").also { it.start() }
            captureThread = worker
            val workerHandler = Handler(worker.looper)
            captureHandler = workerHandler

            setupOrResizeVirtualDisplay(activeProjection, workerHandler)

            isCapturing = true
            updateForegroundNotification()
        } catch (error: Throwable) {
            lastError = error.message ?: "Falha ao iniciar fonte de captura de tela"
            Log.e(TAG, "Falha ao iniciar captura de tela", error)
            stopCaptureOnly()
            if (!isLiveStreaming && !isConnecting) stopSelf()
        }
    }

    private fun computePhysicalCaptureDimensions(dispW: Int, dispH: Int, maxDimension: Int): Pair<Int, Int> {
        val safeW = dispW.coerceAtLeast(2)
        val safeH = dispH.coerceAtLeast(2)
        val maxDim = maxDimension.coerceIn(720, 1920)
        return if (safeW >= safeH) {
            val w = (maxDim - (maxDim % 2)).coerceAtLeast(480)
            val rawH = ((w.toLong() * safeH) / safeW).toInt()
            val h = (rawH - (rawH % 2)).coerceAtLeast(270)
            Pair(w, h)
        } else {
            val h = (maxDim - (maxDim % 2)).coerceAtLeast(480)
            val rawW = ((h.toLong() * safeW) / safeH).toInt()
            val w = (rawW - (rawW % 2)).coerceAtLeast(270)
            Pair(w, h)
        }
    }

    private fun setupOrResizeVirtualDisplay(activeProjection: MediaProjection, workerHandler: Handler) {
        synchronized(captureLock) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            @Suppress("DEPRECATION")
            lastDisplayRotation = display.rotation

            val (targetW, targetH) = computePhysicalCaptureDimensions(
                metrics.widthPixels,
                metrics.heightPixels,
                configuredMaxDimension
            )
            captureWidth = targetW
            captureHeight = targetH

            val newReader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 3)
            newReader.setOnImageAvailableListener({ source ->
                processCapturedImage(source)
            }, workerHandler)

            val oldReader = reader
            reader = newReader

            val existingVd = virtualDisplay
            if (existingVd == null) {
                virtualDisplay = activeProjection.createVirtualDisplay(
                    "LiveNEXUS Screen Capture",
                    targetW,
                    targetH,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newReader.surface,
                    null,
                    workerHandler
                )
                if (virtualDisplay == null) {
                    newReader.close()
                    throw IllegalStateException("Captura de tela indisponível neste ambiente")
                }
            } else {
                try {
                    existingVd.resize(targetW, targetH, metrics.densityDpi)
                    existingVd.surface = newReader.surface
                } catch (e: Throwable) {
                    Log.w(TAG, "Falha em resize do VirtualDisplay; recriando...", e)
                    try { existingVd.release() } catch (_: Exception) {}
                    virtualDisplay = activeProjection.createVirtualDisplay(
                        "LiveNEXUS Screen Capture",
                        targetW,
                        targetH,
                        metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        newReader.surface,
                        null,
                        workerHandler
                    )
                }
            }

            if (oldReader != null && oldReader !== newReader) {
                try {
                    oldReader.setOnImageAvailableListener(null, null)
                    oldReader.close()
                } catch (_: Exception) {}
            }

            Log.i(TAG, "Captura nativa configurada em ${targetW}x${targetH} (display=${metrics.widthPixels}x${metrics.heightPixels})")
            try {
                audioEncoder?.updateMediaProjection(activeProjection)
            } catch (_: Throwable) {}
        }
    }

    private fun reconfigureCaptureForCurrentOrientation(force: Boolean) {
        val activeProjection = projection ?: return
        val workerHandler = captureHandler ?: return
        if (!isCapturing) return

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        val currentRotation = display.rotation
        val (targetW, targetH) = computePhysicalCaptureDimensions(
            metrics.widthPixels,
            metrics.heightPixels,
            configuredMaxDimension
        )

        if (!force && currentRotation == lastDisplayRotation && captureWidth == targetW && captureHeight == targetH) {
            return
        }

        try {
            setupOrResizeVirtualDisplay(activeProjection, workerHandler)
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao reconfigurar captura para nova orientação", e)
        }
    }

    private fun processCapturedImage(source: ImageReader) {
        val image = try { source.acquireLatestImage() } catch (_: RuntimeException) { null } ?: return
        try {
            val plane = image.planes.firstOrNull()
            if (plane != null && plane.pixelStride == 4) {
                val imgW = image.width
                val imgH = image.height
                val paddedWidth = plane.rowStride / plane.pixelStride
                if (paddedWidth >= imgW && imgW > 0 && imgH > 0) {
                    val idx = ringIndex
                    ringIndex = (ringIndex + 1) % ringBitmaps.size
                    var dstBitmap = ringBitmaps[idx]
                    if (dstBitmap == null || dstBitmap.width != imgW || dstBitmap.height != imgH || dstBitmap.isRecycled) {
                        dstBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                        ringBitmaps[idx] = dstBitmap
                    }

                    plane.buffer.rewind()
                    if (paddedWidth == imgW) {
                        dstBitmap.copyPixelsFromBuffer(plane.buffer)
                    } else {
                        var strideBuf = screenBufferBitmap
                        if (strideBuf == null || strideBuf.width != paddedWidth || strideBuf.height != imgH || strideBuf.isRecycled) {
                            strideBuf = Bitmap.createBitmap(paddedWidth, imgH, Bitmap.Config.ARGB_8888)
                            screenBufferBitmap = strideBuf
                        }
                        strideBuf.copyPixelsFromBuffer(plane.buffer)
                        val canvas = Canvas(dstBitmap)
                        val srcRect = Rect(0, 0, imgW, imgH)
                        val dstRect = Rect(0, 0, imgW, imgH)
                        canvas.drawBitmap(strideBuf, srcRect, dstRect, null)
                    }

                    latestCapturedScreenFrame = dstBitmap

                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastPreviewAt >= 180L || previewFrame == null) {
                        lastPreviewAt = now
                        val maxPreviewSide = 640
                        val maxSrcSide = maxOf(imgW, imgH).toFloat().coerceAtLeast(1f)
                        val ratio = minOf(1f, maxPreviewSide.toFloat() / maxSrcSide)
                        if (ratio < 1f) {
                            val pw = (imgW * ratio).toInt().coerceAtLeast(2)
                            val ph = (imgH * ratio).toInt().coerceAtLeast(2)
                            val pIdx = previewIndex
                            previewIndex = (previewIndex + 1) % previewBitmaps.size
                            var pBmp = previewBitmaps[pIdx]
                            if (pBmp == null || pBmp.width != pw || pBmp.height != ph || pBmp.isRecycled) {
                                pBmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                                previewBitmaps[pIdx] = pBmp
                            }
                            val pCanvas = Canvas(pBmp)
                            pCanvas.drawBitmap(
                                dstBitmap,
                                null,
                                RectF(0f, 0f, pw.toFloat(), ph.toFloat()),
                                transformPaint
                            )
                            previewFrame = pBmp
                        } else {
                            previewFrame = dstBitmap
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

    /**
     * Inicia a transmissão ao vivo do cenário do Editor de Cena.
     * Se a fonte "Captura de tela" estiver ativa na cena, ela será composta em 16:9 (Horizontal) ou 9:16 (Vertical).
     */
    private fun handleStartStream(intent: Intent) {
        // Uma solicitação duplicada nunca deve reinicializar a captura,
        // a WebView do Browser Source ou os encoders de uma live em andamento.
        if (isConnecting || isLiveStreaming) {
            Log.w(TAG, "Início duplicado de transmissão ignorado")
            return
        }
        val targetsJson = intent.getStringExtra(EXTRA_TARGETS_JSON)
        val streamTargets = parseTargets(targetsJson)
        if (streamTargets.isEmpty()) {
            lastError = "Nenhuma plataforma configurada para transmissão."
            isConnecting = false
            stopCaptureOnly()
            stopSelf()
            return
        }

        // Inicia captura de tela automaticamente se os dados de projeção foram fornecidos
        if (!isCapturing && intent.hasExtra(EXTRA_RESULT_CODE)) {
            try {
                handleStartCapture(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "Falha ao iniciar captura automática de tela com a transmissão: ${e.message}")
            }
        }

        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(15, 60)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE_KBPS, 4500).coerceIn(500, 20000)
        val maxDimension = intent.getIntExtra(EXTRA_MAX_DIMENSION, 1280).coerceIn(480, 1920)
        if (intent.hasExtra(EXTRA_VERTICAL_MODE)) {
            isVerticalMode = intent.getBooleanExtra(EXTRA_VERTICAL_MODE, isVerticalMode)
        } else {
            val prefs = getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
            isVerticalMode = prefs.getString("capture_orientation", "horizontal") == "vertical"
        }

        configuredMaxDimension = maxDimension
        configuredFps = fps
        configuredBitrateKbps = bitrate

        val (evenWidth, evenHeight) = computeTargetDimensions(maxDimension, isVerticalMode)

        // Garante que a captura de tela ativa também esteja nas dimensões da orientação selecionada
        if (isCapturing) {
            captureHandler?.post {
                reconfigureCaptureForCurrentOrientation(force = true)
            }
        }

        try {
            val notif = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("LiveNEXUS - Transmissão Ao Vivo")
                .setContentText("Conectando transmissão ao vivo...")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Parar", buildStopPendingIntent())
                .build()

            val hasMicPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val fgType = when {
                isCapturing && hasMicPerm ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                isCapturing -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                hasMicPerm -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            }
            if (fgType != 0) {
                startForeground(NOTIFICATION_ID, notif, fgType)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Aviso ao atualizar foreground service", e)
        }

        startStreamingPipelineAsync(
            streamTargets = streamTargets,
            evenWidth = evenWidth,
            evenHeight = evenHeight,
            fps = fps,
            bitrate = bitrate,
            stopServiceOnFailure = !isCapturing
        )
    }

    private fun startStreamingPipelineAsync(
        streamTargets: List<StreamPublisher.StreamTarget>,
        evenWidth: Int,
        evenHeight: Int,
        fps: Int,
        bitrate: Int,
        stopServiceOnFailure: Boolean
    ) {
        // CRÍTICO: stopStreamingOnly() encerra a MediaProjection e a captura
        // de tela autorizada. Iniciar uma live NÃO pode passar por essa rotina.
        // Os recursos só devem ser liberados ao parar ou falhar a transmissão.
        isConnecting = true
        lastError = null
        isLiveStreaming = false
        activePlatforms = emptyList()

        // PRISM-like: preparar a fonte web ANTES de esperar RTMP conectar.
        // O encoder e o publicador continuam exatamente no fluxo original.
        val earlyLayers = activeSceneLayers ?: loadPersistedSceneLayers(this)
        if (earlyLayers.any { it.type == "browser" && it.visible && it.url.isNotBlank() }) {
            try {
                val engine = BrowserSourceEngine(this)
                engine.start()
                browserEngine = engine
                engine.updateLayers(earlyLayers, evenWidth, evenHeight)
                Log.i(TAG, "Browser Source inicializado em paralelo à conexão RTMP")
            } catch (error: Throwable) {
                Log.w(TAG, "Pré-inicialização de Browser Source indisponível", error)
                try { browserEngine?.stop() } catch (_: Throwable) {}
                browserEngine = null
            }
        }
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

                // Compositor de Cenário do LiveNEXUS: transmite o canvas do Editor de Cena.
                val initialLayers = activeSceneLayers ?: loadPersistedSceneLayers(this)
                // Reutiliza a mesma WebView e sessão RTC já iniciadas, sem recarga.
                if (browserEngine == null && initialLayers.any {
                        it.type == "browser" && it.visible && it.url.isNotBlank()
                    }) {
                    browserEngine = BrowserSourceEngine(this@ScreenCaptureService).also { it.start() }
                }
                browserEngine?.updateLayers(initialLayers, evenWidth, evenHeight)

                val sceneComp = SceneCompositor(
                    outputSurface = encoder.surface,
                    width = evenWidth,
                    height = evenHeight,
                    fps = fps,
                    onFrameRendered = null
                )
                sceneComp.start { canvas, w, h ->
                    val layers = activeSceneLayers ?: initialLayers
                    val screenSourceBitmap = if (isCapturing) latestCapturedScreenFrame else null
                    SceneRenderer.render(
                        canvas = canvas,
                        width = w,
                        height = h,
                        layers = layers,
                        screenFrame = screenSourceBitmap,
                        isLiveStream = true,
                        browserFrameDrawer = { id, targetCanvas, targetRect, paint ->
                        browserEngine?.drawFrame(id, targetCanvas, targetRect, paint) == true
                    }
                    )
                }
                compositor = sceneComp

                // Encoder de Áudio AAC (Microfone e Áudio Interno do Sistema/Jogo)
                try {
                    val audioPrefs = getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
                    val aEncoder = LocalAudioEncoder(
                        sampleRate = 44100,
                        channelCount = 2,
                        bitrateBps = 128000,
                        micVolumeProvider = {
                            val micOn = audioPrefs.getBoolean("mic_enabled", true)
                            if (!micOn) 0f else (audioPrefs.getInt("mic_volume", 75).coerceIn(0, 100) / 100f)
                        },
                        internalVolumeProvider = {
                            val intOn = audioPrefs.getBoolean("internal_enabled", true)
                            if (!intOn) 0f else (audioPrefs.getInt("internal_volume", 85).coerceIn(0, 100) / 100f)
                        },
                        noiseSuppressionProvider = {
                            audioPrefs.getBoolean("noise_suppression_enabled", true)
                        },
                        mediaProjectionProvider = { projection },
                        onHeader = { asc -> streamPub.onAudioHeader(asc) },
                        onSample = { bytes, info -> streamPub.onAudioSample(bytes, info) }
                    )
                    aEncoder.start()
                    audioEncoder = aEncoder
                } catch (audioErr: Throwable) {
                    Log.w(TAG, "Falha ao iniciar captura de áudio", audioErr)
                }

                // A conexão e o compositor estão prontos. Só agora autorizar
                // a Activity a minimizar e exibir o controle flutuante.
                isLiveStreaming = true
                activePlatforms = streamPub.connectedPlatforms
                ControllerRuntime.isTransmitting = true
                if (ControllerRuntime.transmissionStartTime <= 0L) {
                    ControllerRuntime.transmissionStartTime = System.currentTimeMillis()
                }
                OverlayService.refreshVisibility()
                updateForegroundNotification()
            } catch (error: Throwable) {
                val msg = error.message ?: "Falha ao conectar ao servidor RTMP"
                lastError = msg
                isLiveStreaming = false
                activePlatforms = emptyList()
                Log.e(TAG, "Falha ao iniciar pipeline de transmissão RTMP", error)
                stopStreamingOnly()
                if (stopServiceOnFailure && !isCapturing) {
                    stopSelf()
                }
            } finally {
                isConnecting = false
            }
        }, "LiveNexusStreamStarter").start()
    }

    private fun stopCaptureOnly() {
        isCapturing = false
        latestCapturedScreenFrame = null
        screenBufferBitmap = null
        for (i in ringBitmaps.indices) ringBitmaps[i] = null
        for (i in previewBitmaps.indices) previewBitmaps[i] = null
        previewFrame = null
        captureHandler = null

        synchronized(captureLock) {
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {}
            virtualDisplay = null

            try {
                reader?.setOnImageAvailableListener(null, null)
                reader?.close()
            } catch (_: Exception) {}
            reader = null
        }

        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {}
        captureThread = null

        val currentProjection = projection
        val callback = projectionCallback
        projectionCallback = null
        projection = null
        if (currentProjection != null) {
            try {
                if (callback != null) currentProjection.unregisterCallback(callback)
                currentProjection.stop()
            } catch (_: Exception) {}
        }
    }

    private fun stopStreamingOnly() {
        isLiveStreaming = false
        activePlatforms = emptyList()
        ControllerRuntime.isTransmitting = false
        ControllerRuntime.transmissionStartTime = 0L
        OverlayService.refreshVisibility()
        stopCaptureOnly()
        try {
            compositor?.close()
        } catch (_: Exception) {}
        compositor = null

        try {
            browserEngine?.stop()
        } catch (_: Exception) {}
        browserEngine = null

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

    private fun isValidStreamKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val trimmed = key.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.equals("null", ignoreCase = true) &&
            !trimmed.equals("undefined", ignoreCase = true) &&
            !trimmed.equals("none", ignoreCase = true)
    }

    private fun parseTargets(json: String?): List<StreamPublisher.StreamTarget> {
        if (json.isNullOrBlank()) return emptyList()
        val result = mutableListOf<StreamPublisher.StreamTarget>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val platform = if (!obj.has("platform") || obj.isNull("platform")) "Live" else obj.optString("platform", "Live")
                val url = if (!obj.has("url") || obj.isNull("url")) "" else obj.optString("url", "").trim()
                val key = if (!obj.has("key") || obj.isNull("key")) "" else obj.optString("key", "").trim()
                if (url.isNotBlank() && !url.equals("null", ignoreCase = true) && isValidStreamKey(key)) {
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
                            raw.optString("url", ""),
                            raw.optBoolean("desktop_mode", true)
                        ))
                    }
                }
                if (sources.isNotEmpty()) return sources
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        unregisterDisplayRotationListener()
        isConnecting = false
        stopStreamingOnly()
        stopCaptureOnly()
        super.onDestroy()
    }
}
