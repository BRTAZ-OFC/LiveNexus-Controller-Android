package com.livenexus.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Base isolada de captura de tela. Não codifica, grava ou transmite frames.
 * O usuário precisa autorizar cada nova sessão pela interface do Android.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.livenexus.controller.capture.START"
        const val ACTION_STOP = "com.livenexus.controller.capture.STOP"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val EXTRA_MAX_DIMENSION = "capture_max_dimension"
        const val EXTRA_FPS = "capture_fps"
        const val EXTRA_BITRATE_KBPS = "capture_bitrate_kbps"
        @Volatile var previewFrame: Bitmap? = null
            private set
        @Volatile var lastError: String? = null
            private set
        private const val CHANNEL_ID = "livenexus_screen_capture"
        private const val NOTIFICATION_ID = 2

        @Volatile var isCapturing: Boolean = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderDisplay: VirtualDisplay? = null
    private var videoEncoder: LocalVideoEncoder? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Captura de tela LiveNEXUS",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || isCapturing) {
            stopSelf()
            return START_NOT_STICKY
        }
        val consent = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if (resultCode != android.app.Activity.RESULT_OK || consent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            lastError = null
            previewFrame = null
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("LiveNEXUS - captura de tela")
                .setContentText("Captura local ativa. Nenhuma transmissão iniciada.")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Parar captura", stopPendingIntent)
                .build()
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val activeProjection = projectionManager.getMediaProjection(resultCode, consent)
                ?: throw IllegalStateException("O Android não autorizou a captura.")
            projection = activeProjection
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    isCapturing = false
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
            val maxDimension = intent.getIntExtra(EXTRA_MAX_DIMENSION, 1280)
                .coerceIn(480, 1920)
            val scale = minOf(1.0, maxDimension.toDouble() / maxOf(sourceWidth, sourceHeight))
            val width = (sourceWidth * scale).toInt().coerceAtLeast(2)
            val height = (sourceHeight * scale).toInt().coerceAtLeast(2)
            val captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = captureReader
            // Prévia local em resolução reduzida; nenhum frame é transmitido ou gravado.
            val worker = HandlerThread("LiveNexusCapturePreview").also { it.start() }
            captureThread = worker
            var lastPreviewAt = 0L
            captureReader.setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: RuntimeException) { null }
                if (image != null) {
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPreviewAt >= 750L) {
                            lastPreviewAt = now
                            val plane = image.planes.firstOrNull()
                            if (plane != null && plane.pixelStride == 4) {
                                val paddedWidth = plane.rowStride / plane.pixelStride
                                if (paddedWidth >= image.width) {
                                    val bitmap = Bitmap.createBitmap(
                                        paddedWidth, image.height, Bitmap.Config.ARGB_8888
                                    )
                                    plane.buffer.rewind()
                                    bitmap.copyPixelsFromBuffer(plane.buffer)
                                    val trimmed = if (paddedWidth == image.width) bitmap else {
                                        Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                                    }
                                    val maxPreviewWidth = 480
                                    val ratio = minOf(
                                        1f, maxPreviewWidth.toFloat() / trimmed.width.toFloat()
                                    )
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
                    } catch (error: Exception) {
                        android.util.Log.w("LiveNexusCapture", "Prévia indisponível", error)
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
            if (virtualDisplay == null) throw IllegalStateException("Falha ao criar a tela virtual.")
            val fps = intent.getIntExtra(EXTRA_FPS, 30)
            val bitrate = intent.getIntExtra(EXTRA_BITRATE_KBPS, 4500)
            val evenWidth = width - width % 2
            val evenHeight = height - height % 2
            val encoder = LocalVideoEncoder(
                evenWidth, evenHeight, fps, bitrate,
                onSample = { _, _ -> },
                onFormat = { _ -> }
            )
            encoder.start()
            videoEncoder = encoder
            encoderDisplay = activeProjection.createVirtualDisplay(
                "LiveNEXUS H264 Encoder",
                evenWidth, evenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoder.surface, null, null
            ) ?: throw IllegalStateException("Falha ao conectar captura ao encoder.")
            isCapturing = true
        } catch (error: Exception) {
            lastError = error.message ?: "Falha ao iniciar a captura."
            android.util.Log.e("LiveNexusCapture", "Falha ao iniciar captura", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isCapturing = false
        encoderDisplay?.release()
        encoderDisplay = null
        videoEncoder?.close()
        videoEncoder = null
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
