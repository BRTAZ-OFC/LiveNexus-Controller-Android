package com.livenexus.controller

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Browser Source real: uma WebView acelerada desenha num display privado do Android.
 * Quadros do display são lidos pelo ImageReader e compostos no encoder e no editor.
 * Não adiciona views à interface do template nem usa APIs externas pagas.
 */
internal object BrowserSourceManager {
    private const val TAG = "LiveNEXUSBrowserSource"
    private const val SOURCE_WIDTH = 960
    private const val SOURCE_HEIGHT = 540
    private const val FRAME_INTERVAL_MS = 66L
    private val main = Handler(Looper.getMainLooper())
    private val sources = ConcurrentHashMap<String, BrowserSource>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun validUrl(input: String): String? {
        val url = input.trim()
        if (url.length > 2048) return null
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        // O manifesto bloqueia HTTP não criptografado: preservar a política.
        return if (uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null) url else null
    }

    fun sync(context: Context, layers: List<CaptureLayer>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { sync(context.applicationContext, layers) }
            return
        }
        val desired = layers.asSequence()
            .filter { it.type == "browser" }
            .mapNotNull { layer -> validUrl(layer.url)?.let { layer.id to it } }
            .toMap()
        for ((id, source) in sources.entries.toList()) {
            if (desired[id] != source.url) {
                sources.remove(id, source)
                source.close()
            }
        }
        for ((id, url) in desired) {
            if (sources.containsKey(id)) continue
            val source = BrowserSource(id, url)
            sources[id] = source
            try {
                source.start(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir Browser Source '$id'", e)
                sources.remove(id, source)
                source.close()
            }
        }
    }

    fun draw(id: String, canvas: Canvas, target: RectF): Boolean {
        return sources[id]?.draw(canvas, target) ?: false
    }

    private class BrowserSource(val id: String, val url: String) {
        private val frameLock = Any()
        @Volatile private var closed = false
        private var lastFrameAt = 0L
        private var latestFrame: Bitmap? = null
        private var imageReader: ImageReader? = null
        private var imageThread: HandlerThread? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var webView: WebView? = null

        fun start(context: Context) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val reader = ImageReader.newInstance(
                SOURCE_WIDTH, SOURCE_HEIGHT, PixelFormat.RGBA_8888, 3
            )
            imageReader = reader
            val thread = HandlerThread("BrowserFrames-${id.hashCode()}").apply { start() }
            imageThread = thread
            reader.setOnImageAvailableListener({ ir ->
                val image = try { ir.acquireLatestImage() } catch (_: Exception) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    if (closed) return@setOnImageAvailableListener
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                    val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val stride = plane.pixelStride
                    val rowStride = plane.rowStride
                    if (stride != 4 || rowStride < SOURCE_WIDTH * stride) return@setOnImageAvailableListener
                    val paddedWidth = rowStride / stride
                    val padded = Bitmap.createBitmap(paddedWidth, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
                    var frame: Bitmap? = null
                    var transferred = false
                    try {
                        plane.buffer.rewind()
                        padded.copyPixelsFromBuffer(plane.buffer)
                        frame = if (paddedWidth == SOURCE_WIDTH) {
                            padded
                        } else {
                            Bitmap.createBitmap(padded, 0, 0, SOURCE_WIDTH, SOURCE_HEIGHT)
                        }
                        synchronized(frameLock) {
                            if (!closed) {
                                val old = latestFrame
                                latestFrame = frame
                                transferred = true
                                if (old != null && old !== frame && !old.isRecycled) old.recycle()
                            }
                        }
                        lastFrameAt = now
                    } finally {
                        if (!transferred && frame != null && !frame.isRecycled) frame.recycle()
                        if (frame !== padded && !padded.isRecycled) padded.recycle()
                        if (frame == null && !padded.isRecycled) padded.recycle()
                    }
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "Falha ao obter quadro Browser Source '$id'", e)
                } finally {
                    try { image.close() } catch (_: Exception) {}
                }
            }, Handler(thread.looper))

            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.createVirtualDisplay(
                "LiveNEXUS-Browser-${id.hashCode()}",
                SOURCE_WIDTH, SOURCE_HEIGHT, 160, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: throw IllegalStateException("Display virtual indisponível")
            virtualDisplay = display

            val screen = object : Presentation(context, display.display) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window?.setDimAmount(0f)

                    val browser = WebView(this.context)
                    webView = browser
                    browser.setBackgroundColor(Color.TRANSPARENT)
                    browser.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    browser.webChromeClient = WebChromeClient()
                    browser.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean = validUrl(request.url.toString()) == null

                        override fun onRenderProcessGone(
                            view: WebView, detail: android.webkit.RenderProcessGoneDetail
                        ): Boolean {
                            Log.e(TAG, "Renderizador WebView encerrado para '$id'")
                            main.post {
                                if (sources.remove(id, this@BrowserSource)) close()
                            }
                            return true
                        }
                    }
                    setContentView(
                        browser,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    browser.loadUrl(url)
                }
            }
            presentation = screen
            screen.show()
            Log.i(TAG, "Browser Source iniciado: $id")
        }

        fun draw(canvas: Canvas, target: RectF): Boolean {
            synchronized(frameLock) {
                val bitmap = latestFrame ?: return false
                if (bitmap.isRecycled || target.width() <= 0f || target.height() <= 0f) return false
                val scale = min(target.width() / bitmap.width, target.height() / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val left = target.left + (target.width() - w) / 2f
                val top = target.top + (target.height() - h) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), paint)
                return true
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try { presentation?.dismiss() } catch (_: Exception) {}
            presentation = null
            try { webView?.stopLoading() } catch (_: Exception) {}
            try { webView?.destroy() } catch (_: Exception) {}
            webView = null
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = null
            imageThread?.quitSafely()
            imageThread = null
            synchronized(frameLock) {
                try { latestFrame?.recycle() } catch (_: Exception) {}
                latestFrame = null
            }
        }
    }
}
