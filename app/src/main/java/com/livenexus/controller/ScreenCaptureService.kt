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
    private var transformedFrameBitmapA: Bitmap? = null
    private var transformedFrameBitmapB: Bitmap? = null
    private var useBufferA: Boolean = true
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
                if (!isCapturing) {
                    stopSelf()
                } else {
                    updateForegroundNotification()
                }
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
                    isLiveStream = true
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

            val (targetW, targetH) = computeTargetDimensions(configuredMaxDimension, isVerticalMode)
            captureWidth = targetW
            captureHeight = targetH

            val newReader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2)
            newReader.setOnImageAvailableListener({ source ->
                processCapturedImage(source, targetW, targetH)
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

            Log.i(TAG, "Captura configurada em ${targetW}x${targetH} (vertical=$isVerticalMode, display=${metrics.widthPixels}x${metrics.heightPixels})")
        }
    }

    private fun reconfigureCaptureForCurrentOrientation(force: Boolean) {
        val activeProjection = projection ?: return
        val workerHandler = captureHandler ?: return
        if (!isCapturing) return

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val currentRotation = windowManager.defaultDisplay.rotation
        val (targetW, targetH) = computeTargetDimensions(configuredMaxDimension, isVerticalMode)

        if (!force && currentRotation == lastDisplayRotation && captureWidth == targetW && captureHeight == targetH) {
            return
        }

        try {
            setupOrResizeVirtualDisplay(activeProjection, workerHandler)
        } catch (e: Throwable) {
            Log.w(TAG, "Erro ao reconfigurar captura para nova orientação", e)
        }
    }

    private fun processCapturedImage(source: ImageReader, targetW: Int, targetH: Int) {
        val image = try { source.acquireLatestImage() } catch (_: RuntimeException) { null } ?: return
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
                    val rawFrame = if (paddedWidth == image.width) buffer else {
                        Bitmap.createBitmap(buffer, 0, 0, image.width, image.height)
                    }

                    // Extrai a região útil real da tela removendo barras de letterbox/pillarbox do SurfaceFlinger
                    // e formata exatamente na proporção selecionada (16:9 Horizontal ou 9:16 Vertical)
                    val contentRect = extractActiveContentRect(rawFrame)
                    val formattedFrame = transformToTargetAspect(rawFrame, contentRect, targetW, targetH)
                    latestCapturedScreenFrame = formattedFrame

                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastPreviewAt >= 120L || previewFrame == null) {
                        lastPreviewAt = now
                        val maxPreviewSide = 640
                        val maxSrcSide = maxOf(formattedFrame.width, formattedFrame.height).toFloat().coerceAtLeast(1f)
                        val ratio = minOf(1f, maxPreviewSide.toFloat() / maxSrcSide)
                        previewFrame = if (ratio < 1f) {
                            Bitmap.createScaledBitmap(
                                formattedFrame,
                                (formattedFrame.width * ratio).toInt().coerceAtLeast(2),
                                (formattedFrame.height * ratio).toInt().coerceAtLeast(2),
                                true
                            )
                        } else {
                            formattedFrame
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
     * Detecta e remove barras pretas ou claras uniformes (pillarbox / letterbox) inseridas pelo SurfaceFlinger
     * quando a orientação física da tela difere do VirtualDisplay, preservando 100% da área útil.
     */
    private fun extractActiveContentRect(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height
        val full = Rect(0, 0, w, h)
        if (w < 64 || h < 64) return full

        fun isUniformBorderPixel(color: Int, refColor: Int): Boolean {
            val dr = abs(Color.red(color) - Color.red(refColor))
            val dg = abs(Color.green(color) - Color.green(refColor))
            val db = abs(Color.blue(color) - Color.blue(refColor))
            if (dr > 8 || dg > 8 || db > 8) return false
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val isDarkBar = maxOf(r, g, b) <= 14
            val isLightBar = minOf(r, g, b) >= 240 && (maxOf(r, g, b) - minOf(r, g, b) <= 12)
            return isDarkBar || isLightBar
        }

        val sampleFractions = floatArrayOf(0.18f, 0.35f, 0.50f, 0.65f, 0.82f)

        fun isColumnUniformBorder(x: Int): Boolean {
            val clampedX = x.coerceIn(0, w - 1)
            val ref = bitmap.getPixel(clampedX, (h * 0.5f).toInt())
            for (f in sampleFractions) {
                val y = (h * f).toInt().coerceIn(0, h - 1)
                if (!isUniformBorderPixel(bitmap.getPixel(clampedX, y), ref)) return false
            }
            return true
        }

        fun isRowUniformBorder(y: Int): Boolean {
            val clampedY = y.coerceIn(0, h - 1)
            val ref = bitmap.getPixel((w * 0.5f).toInt(), clampedY)
            for (f in sampleFractions) {
                val x = (w * f).toInt().coerceIn(0, w - 1)
                if (!isUniformBorderPixel(bitmap.getPixel(x, clampedY), ref)) return false
            }
            return true
        }

        var left = 0
        var right = w - 1
        if (isColumnUniformBorder(1) && isColumnUniformBorder(w - 2) && !isColumnUniformBorder(w / 2)) {
            val maxCropX = (w * 0.38f).toInt()
            while (left < maxCropX && isColumnUniformBorder(left)) left += 2
            while (right > w - 1 - maxCropX && isColumnUniformBorder(right)) right -= 2
        }

        var top = 0
        var bottom = h - 1
        if (isRowUniformBorder(1) && isRowUniformBorder(h - 2) && !isRowUniformBorder(h / 2)) {
            val maxCropY = (h * 0.38f).toInt()
            while (top < maxCropY && isRowUniformBorder(top)) top += 2
            while (bottom > h - 1 - maxCropY && isRowUniformBorder(bottom)) bottom -= 2
        }

        val rectW = right - left + 1
        val rectH = bottom - top + 1
        if (rectW < w / 4 || rectH < h / 4) return full
        return Rect(left, top, right + 1, bottom + 1)
    }

    /**
     * Compõe o conteúdo capturado para um Bitmap nas dimensões exatas de destino (16:9 ou 9:16),
     * sem distorcer, esticar, girar ou cortar o conteúdo.
     */
    private fun transformToTargetAspect(source: Bitmap, srcRect: Rect, targetW: Int, targetH: Int): Bitmap {
        if (srcRect.left == 0 && srcRect.top == 0 &&
            srcRect.width() == source.width && srcRect.height() == source.height &&
            source.width == targetW && source.height == targetH
        ) {
            return source
        }

        val dstBitmap = if (useBufferA) {
            var b = transformedFrameBitmapA
            if (b == null || b.width != targetW || b.height != targetH || b.isRecycled) {
                b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                transformedFrameBitmapA = b
            }
            b
        } else {
            var b = transformedFrameBitmapB
            if (b == null || b.width != targetW || b.height != targetH || b.isRecycled) {
                b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                transformedFrameBitmapB = b
            }
            b
        }
        useBufferA = !useBufferA

        val canvas = Canvas(dstBitmap)
        canvas.drawColor(Color.rgb(6, 10, 24))

        val srcW = srcRect.width().toFloat().coerceAtLeast(1f)
        val srcH = srcRect.height().toFloat().coerceAtLeast(1f)
        val dstW = targetW.toFloat()
        val dstH = targetH.toFloat()

        val srcAspect = srcW / srcH
        val dstAspect = dstW / dstH

        val dstRect = if (abs(srcAspect - dstAspect) / dstAspect <= 0.06f) {
            RectF(0f, 0f, dstW, dstH)
        } else {
            val scale = minOf(dstW / srcW, dstH / srcH)
            val drawW = srcW * scale
            val drawH = srcH * scale
            val left = (dstW - drawW) / 2f
            val top = (dstH - drawH) / 2f
            RectF(left, top, left + drawW, top + drawH)
        }

        canvas.drawBitmap(source, srcRect, dstRect, transformPaint)
        return dstBitmap
    }

    /**
     * Inicia a transmissão ao vivo do cenário do Editor de Cena.
     * Se a fonte "Captura de tela" estiver ativa na cena, ela será composta em 16:9 (Horizontal) ou 9:16 (Vertical).
     */
    private fun handleStartStream(intent: Intent) {
        val targetsJson = intent.getStringExtra(EXTRA_TARGETS_JSON)
        val streamTargets = parseTargets(targetsJson)
        if (streamTargets.isEmpty()) {
            lastError = "Nenhuma plataforma configurada para transmissão."
            isConnecting = false
            if (!isCapturing) stopSelf()
            return
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
                updateForegroundNotification()

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
                        isLiveStream = true
                    )
                }
                compositor = sceneComp

                // Encoder de Áudio AAC (Microfone)
                try {
                    val audioPrefs = getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
                    val aEncoder = LocalAudioEncoder(
                        sampleRate = 44100,
                        channelCount = 2,
                        bitrateBps = 128000,
                        volumeProvider = {
                            val micOn = audioPrefs.getBoolean("mic_enabled", true)
                            if (!micOn) 0f else (audioPrefs.getInt("mic_volume", 75).coerceIn(0, 100) / 100f)
                        },
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
        transformedFrameBitmapA = null
        transformedFrameBitmapB = null
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
