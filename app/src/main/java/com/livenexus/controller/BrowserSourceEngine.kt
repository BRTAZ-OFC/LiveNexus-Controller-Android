package com.livenexus.controller

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renderiza fontes HTML em uma janela real da tela virtual privada.
 *
 * WebView.draw(Canvas(bitmap)) fora de uma janela perde partes do conteúdo
 * acelerado por hardware. A Presentation mantém o compositor do Chromium
 * ativo e o ImageReader entrega os frames para o compositor RTMP existente.
 *
 * Não usa MediaProjection, rede adicional ou bibliotecas externas.
 */
class BrowserSourceEngine(private val context: Context) {
    private companion object {
        const val TAG = "BrowserSourceEngine"
        const val FRAME_INTERVAL_MS = 66L
        const val MAX_PIXELS = 1920 * 1080
        // Cor-base incomum usada quando a tela virtual entrega pixels opacos.
        // O fundo de HTML/BODY é transparente; imagens e elementos não são alterados.
        const val MATTE_R = 13
        const val MATTE_G = 4
        const val MATTE_B = 27
        const val KEY_TOLERANCE = 2
        val MATTE = Color.rgb(MATTE_R, MATTE_G, MATTE_B)
    }

    private class Frames(val width: Int, val height: Int) {
        var front: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var back: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var padded: Bitmap? = null
        val pixels = IntArray(width * height)

        fun recycle() {
            front.recycle()
            back.recycle()
            padded?.recycle()
            padded = null
        }
    }

    private class BrowserLayer(
        val id: String,
        var url: String,
        val width: Int,
        val height: Int,
        val reader: ImageReader,
        val virtualDisplay: VirtualDisplay,
        val presentation: Presentation,
        val webView: WebView
    ) {
        @Volatile var active = true
        var lastFrameAt = 0L // lido/escrito somente na thread de ImageReader
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    // As Presentation/WebView e este mapa são acessados somente na thread principal.
    private val layers = HashMap<String, BrowserLayer>()
    // O compositor RTMP só lê o frame dianteiro enquanto o lock está mantido.
    private val frameLock = Any()
    private val frames = HashMap<String, Frames>()
    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val thread = HandlerThread("LiveNexusBrowserFrames").apply { start() }
        readerThread = thread
        readerHandler = Handler(thread.looper)
    }

    fun stop() {
        running = false
        mainHandler.post {
            destroyAll()
            readerHandler = null
            readerThread?.quitSafely()
            readerThread = null
        }
    }

    fun updateLayers(sources: List<CaptureLayer>, stageWidth: Int, stageHeight: Int) {
        val wanted = sources.filter { it.type == "browser" && it.visible && it.url.isNotBlank() }.toList()
        mainHandler.post {
            if (!running) return@post
            val wantedIds = wanted.map { it.id }.toSet()
            layers.keys.toList().filter { it !in wantedIds }.forEach { removeLayer(it) }

            for (source in wanted) {
                val url = normalizeUrl(source.url)
                if (url.isEmpty()) {
                    removeLayer(source.id)
                    continue
                }
                val rawWidth = (source.width.coerceIn(0.05f, 1f) * stageWidth).toInt().coerceAtLeast(64)
                val rawHeight = (source.height.coerceIn(0.05f, 1f) * stageHeight).toInt().coerceAtLeast(64)
                val scale = minOf(1.0, sqrt(MAX_PIXELS.toDouble() / (rawWidth.toDouble() * rawHeight)))
                val width = (rawWidth * scale).toInt().coerceAtLeast(64)
                val height = (rawHeight * scale).toInt().coerceAtLeast(64)
                val existing = layers[source.id]

                // A tela virtual deve ser reconstruída apenas quando a resolução muda.
                if (existing == null || existing.width != width || existing.height != height) {
                    if (existing != null) removeLayer(source.id)
                    createLayer(source.id, url, width, height)
                } else if (existing.url != url) {
                    existing.url = url
                    synchronized(frameLock) {
                        frames[source.id]?.let {
                            it.front.eraseColor(Color.TRANSPARENT)
                            it.back.eraseColor(Color.TRANSPARENT)
                        }
                    }
                    existing.webView.loadUrl(url)
                }
            }
        }
    }

    private fun normalizeUrl(raw: String): String {
        val url = raw.trim().replace("\n", "").replace("\r", "")
        if (url.isBlank()) return ""
        return if (url.startsWith("https://", true) || url.startsWith("http://", true)) url else "https://$url"
    }

    private fun createLayer(id: String, url: String, width: Int, height: Int) {
        val handler = readerHandler ?: return
        var reader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        var presentation: Presentation? = null
        var webView: WebView? = null
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            virtualDisplay = displayManager.createVirtualDisplay(
                "LiveNEXUS Browser " + id, width, height,
                context.resources.configuration.densityDpi.coerceAtLeast(1),
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            ) ?: throw IllegalStateException("Tela virtual não disponível para Browser Source")

            presentation = Presentation(context, virtualDisplay.display)
            val root = FrameLayout(presentation.context).apply {
                setBackgroundColor(MATTE)
            }
            webView = WebView(presentation.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webChromeClient = WebChromeClient()
            }
            val browser = webView
            root.addView(browser, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            presentation.setContentView(root)
            presentation.window?.apply {
                setBackgroundDrawable(ColorDrawable(MATTE))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            presentation.show()
            presentation.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )

            val layer = BrowserLayer(id, url, width, height, reader, virtualDisplay, presentation, browser)
            browser.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    if (layer.active && running && view === layer.webView) {
                        BrowserSourceTransparency.apply(layer.webView)
                    }
                }
            }
            layers[id] = layer
            reader.setOnImageAvailableListener({ source ->
                onImageAvailable(layer, source)
            }, handler)
            browser.loadUrl(url)
            Log.i(TAG, "Browser Source na tela virtual: " + id + " (" + width + "x" + height + ")")
        } catch (error: Throwable) {
            Log.e(TAG, "Falha ao criar Browser Source: " + id, error)
            layers.remove(id)?.active = false
            try { reader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
            try { webView?.stopLoading() } catch (_: Throwable) {}
            try { presentation?.dismiss() } catch (_: Throwable) {}
            try { webView?.destroy() } catch (_: Throwable) {}
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            try { reader?.close() } catch (_: Throwable) {}
            synchronized(frameLock) {
                frames.remove(id)?.recycle()
            }
        }
    }

    private fun onImageAvailable(layer: BrowserLayer, reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null // Reader já fechado ou todos os buffers ocupados.
        }
        if (image == null) return

        try {
            if (!running || !layer.active) return
            val now = SystemClock.uptimeMillis()
            if (now - layer.lastFrameAt < FRAME_INTERVAL_MS) return
            layer.lastFrameAt = now
            publishImage(layer, image)
        } catch (error: Throwable) {
            Log.w(TAG, "Frame descartado do Browser Source: " + layer.id, error)
        } finally {
            image.close()
        }
    }

    private fun publishImage(layer: BrowserLayer, image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        val width = layer.width
        val height = layer.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4 || rowStride < width * pixelStride || rowStride % pixelStride != 0) {
            Log.w(TAG, "Formato de imagem incompatível para Browser Source: " + layer.id)
            return
        }
        val paddedWidth = rowStride / pixelStride

        synchronized(frameLock) {
            if (!running || !layer.active) return
            val pair = frames[layer.id] ?: Frames(width, height).also { frames[layer.id] = it }
            val buffer = plane.buffer
            buffer.rewind()

            if (paddedWidth == width) {
                pair.back.copyPixelsFromBuffer(buffer)
            } else {
                if (pair.padded?.width != paddedWidth || pair.padded?.height != height) {
                    pair.padded?.recycle()
                    pair.padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                }
                val padded = pair.padded ?: return
                padded.copyPixelsFromBuffer(buffer)
                Canvas(pair.back).drawBitmap(
                    padded,
                    Rect(0, 0, width, height),
                    Rect(0, 0, width, height),
                    null
                )
            }

            // Mantém objetos/fotos reais. Remove exclusivamente o matte do fundo
            // quando o display retorna uma imagem opaca apesar do HTML transparente.
            val pixels = pair.pixels
            pair.back.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val color = pixels[i]
                if (Color.alpha(color) != 255) continue
                if (abs(Color.red(color) - MATTE_R) <= KEY_TOLERANCE &&
                    abs(Color.green(color) - MATTE_G) <= KEY_TOLERANCE &&
                    abs(Color.blue(color) - MATTE_B) <= KEY_TOLERANCE) {
                    pixels[i] = Color.TRANSPARENT
                }
            }
            pair.back.setPixels(pixels, 0, width, 0, 0, width, height)

            val completed = pair.back
            pair.back = pair.front
            pair.front = completed
        }
    }

    /** Executa o desenho sob o mesmo lock usado para publicar os frames. */
    fun drawFrame(id: String, canvas: Canvas, destination: RectF, paint: Paint): Boolean {
        synchronized(frameLock) {
            val front = frames[id]?.front ?: return false
            if (front.isRecycled) return false
            return try {
                canvas.drawBitmap(front, null, destination, paint)
                true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Falha ao desenhar Browser Source: " + id, error)
                false
            }
        }
    }

    private fun removeLayer(id: String) {
        val layer = layers.remove(id) ?: return
        layer.active = false
        try { layer.reader.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { layer.webView.stopLoading() } catch (_: Throwable) {}
        try { layer.presentation.dismiss() } catch (_: Throwable) {}
        try { layer.webView.destroy() } catch (_: Throwable) {}
        try { layer.virtualDisplay.release() } catch (_: Throwable) {}
        try { layer.reader.close() } catch (_: Throwable) {}
        synchronized(frameLock) {
            frames.remove(id)?.recycle()
        }
    }

    fun destroyAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { destroyAll() }
            return
        }
        for (id in layers.keys.toList()) removeLayer(id)
        synchronized(frameLock) {
            frames.values.forEach { it.recycle() }
            frames.clear()
        }
    }
}
