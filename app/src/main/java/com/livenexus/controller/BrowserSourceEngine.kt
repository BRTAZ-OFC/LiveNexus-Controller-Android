package com.livenexus.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Motor de Browser Source em segundo plano para o LiveNEXUS.
 * Mantém instâncias de WebView renderizadas offscreen e extrai bitmaps
 * periódicos para compor diretamente no stream ao vivo (RTMP).
 */
class BrowserSourceEngine(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViews = ConcurrentHashMap<String, WebView>()
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val activeUrls = ConcurrentHashMap<String, String>()
    private val dimensions = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val frameLock = Any()
    @Volatile private var isRunning = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            captureAllFrames()
            mainHandler.postDelayed(this, 66L) // ~15 FPS para overlays/alertas/chat
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        mainHandler.post {
            mainHandler.post(captureRunnable)
        }
        Log.i("BrowserSourceEngine", "Engine iniciado para renderizar Browser Sources na live")
    }

    fun stop() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            destroyAll()
        }
        Log.i("BrowserSourceEngine", "Engine finalizado")
    }

    fun updateLayers(layers: List<CaptureLayer>, stageWidth: Int, stageHeight: Int) {
        val browserLayers = layers.filter { it.type == "browser" && it.visible && it.url.isNotBlank() }
        mainHandler.post {
            val currentIds = browserLayers.map { it.id }.toSet()

            // Remove layers que foram deletadas ou ocultadas
            val toRemove = webViews.keys.filter { it !in currentIds }
            for (id in toRemove) {
                removeWebView(id)
            }

            for (layer in browserLayers) {
                val targetW = ((layer.width).coerceIn(0.05f, 1f) * stageWidth).toInt().coerceIn(64, 3840)
                val targetH = ((layer.height).coerceIn(0.05f, 1f) * stageHeight).toInt().coerceIn(64, 3840)
                val existing = webViews[layer.id]
                val prevUrl = activeUrls[layer.id]
                val prevDim = dimensions[layer.id]

                if (existing == null) {
                    createAndLoadWebView(layer, targetW, targetH)
                } else {
                    if (prevDim == null || prevDim.first != targetW || prevDim.second != targetH) {
                        dimensions[layer.id] = Pair(targetW, targetH)
                        existing.measure(
                            View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(targetH, View.MeasureSpec.EXACTLY)
                        )
                        existing.layout(0, 0, targetW, targetH)
                    }
                    val normalized = normalizeUrl(layer.url)
                    if (prevUrl != normalized) {
                        activeUrls[layer.id] = normalized
                        existing.loadUrl(normalized)
                    }
                }
            }
        }
    }

    private fun normalizeUrl(raw: String): String {
        var trimmed = raw.trim().replace("\n", "").replace("\r", "")
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return "https://$trimmed"
    }

    private fun createAndLoadWebView(layer: CaptureLayer, w: Int, h: Int) {
        try {
            val url = normalizeUrl(layer.url)
            if (url.isBlank()) return

            val webView = WebView(context.applicationContext)
            webView.setBackgroundColor(Color.TRANSPARENT)
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    captureFrame(layer.id, webView, w, h)
                }
            }
            webView.webChromeClient = WebChromeClient()

            webView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, w, h)

            activeUrls[layer.id] = url
            dimensions[layer.id] = Pair(w, h)
            webViews[layer.id] = webView
            webView.loadUrl(url)
            Log.i("BrowserSourceEngine", "Carregando Browser Source (${layer.id}): $url (${w}x${h})")
        } catch (e: Throwable) {
            Log.e("BrowserSourceEngine", "Erro ao inicializar WebView para Browser Source ${layer.id}", e)
        }
    }

    private fun captureAllFrames() {
        for ((id, webView) in webViews) {
            val dim = dimensions[id] ?: Pair(webView.width, webView.height)
            captureFrame(id, webView, dim.first, dim.second)
        }
    }

    private fun captureFrame(layerId: String, webView: WebView, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        try {
            var currentBitmap = bitmaps[layerId]
            if (currentBitmap == null || currentBitmap.isRecycled || currentBitmap.width != w || currentBitmap.height != h) {
                currentBitmap?.recycle()
                currentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmaps[layerId] = currentBitmap
            }
            synchronized(frameLock) {
                currentBitmap.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(currentBitmap)
                webView.draw(canvas)
            }
        } catch (e: Throwable) {
            // falhas pontuais de frame são ignoradas
        }
    }

    fun getFrame(layerId: String): Bitmap? {
        val bmp = bitmaps[layerId]
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    private fun removeWebView(id: String) {
        try {
            webViews.remove(id)?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
            activeUrls.remove(id)
            dimensions.remove(id)
            bitmaps.remove(id)?.recycle()
        } catch (_: Exception) {}
    }

    fun destroyAll() {
        for (id in webViews.keys.toList()) {
            removeWebView(id)
        }
        bitmaps.clear()
        activeUrls.clear()
        dimensions.clear()
    }
}
