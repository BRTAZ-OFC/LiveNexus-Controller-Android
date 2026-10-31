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
        private const val CHANNEL_ID = "livenexus_screen_capture"
        private const val NOTIFICATION_ID = 2

        @Volatile var isCapturing: Boolean = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
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
            val scale = minOf(1.0, 1280.0 / maxOf(sourceWidth, sourceHeight))
            val width = (sourceWidth * scale).toInt().coerceAtLeast(2)
            val height = (sourceHeight * scale).toInt().coerceAtLeast(2)
            val captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = captureReader
            // Nesta etapa os frames são descartados: nenhum dado é salvo ou enviado.
            captureReader.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.close()
                } catch (_: RuntimeException) {
                    // A sessão pode ter sido encerrada durante a leitura do frame.
                }
            }, null)
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
            isCapturing = true
        } catch (error: Exception) {
            android.util.Log.e("LiveNexusCapture", "Falha ao iniciar captura", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isCapturing = false
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
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
