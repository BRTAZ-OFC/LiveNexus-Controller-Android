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
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Browser Source real: uma WebView acelerada desenha num display privado do Android.
 * Quadros do display são lidos pelo ImageReader e compostos no encoder e no editor.
 * Não adiciona views à interface do template nem usa APIs externas pagas.
 */
internal object BrowserSourceManager {
    private const val TAG = "LiveNEXUSBrowserSource"
    private const val SOURCE_WIDTH = 1920
    private const val SOURCE_HEIGHT = 1080
    private const val FRAME_INTERVAL_MS = 33L
    private val main = Handler(Looper.getMainLooper())
    private val sources = ConcurrentHashMap<String, BrowserSource>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private data class CaptureTicket(
        val requestId: String,
        val sourceId: String,
        val context: Context,
        val queuedAt: Long
    )
    @Volatile private var pendingCapture: CaptureTicket? = null
    private val renderingScene = ThreadLocal<Boolean>()
    // Estes dois campos são acessados apenas pela thread de composição.
    private var stagedOriginal: Bitmap? = null
    private var stagedTop: Bitmap? = null
    private var stagedBottom: Bitmap? = null
    private var stagedRequestId: String? = null

    fun armVisualComparison(context: Context, requestId: String, sourceId: String) {
        if (!requestId.matches(Regex("[a-f0-9-]{36}")) ||
            !sourceId.matches(Regex("source_[a-f0-9]{1,16}"))) return
        val source = sources.values.firstOrNull {
            "source_" + Integer.toHexString(it.id.hashCode()) == sourceId
        } ?: return
        pendingCapture = CaptureTicket(requestId, sourceId, context.applicationContext, SystemClock.uptimeMillis())
        // evaluateJavascript deve executar na thread principal da WebView.
        main.post {
            source.collectDomGeometry(context.applicationContext, requestId, sourceId)
        }
    }

    fun beginSceneFrame() {
        if (pendingCapture != null) renderingScene.set(true)
    }

    fun endSceneFrame() {
        renderingScene.remove()
    }

    fun completeSceneFrame(composed: Bitmap) {
        val ticket = pendingCapture ?: return
        if (SystemClock.uptimeMillis() - ticket.queuedAt > 35_000L) {
            pendingCapture = null
            stagedOriginal?.recycle()
            stagedTop?.recycle()
            stagedBottom?.recycle()
            stagedOriginal = null
            stagedTop = null
            stagedBottom = null
            stagedRequestId = null
            return
        }
        val original = stagedOriginal ?: return
        val top = stagedTop ?: return
        val bottom = stagedBottom ?: return
        if (stagedRequestId != ticket.requestId) {
            original.recycle()
            top.recycle()
            bottom.recycle()
            stagedOriginal = null
            stagedTop = null
            stagedBottom = null
            stagedRequestId = null
            return
        }
        stagedOriginal = null
        stagedTop = null
        stagedBottom = null
        stagedRequestId = null
        pendingCapture = null
        try {
            val largest = maxOf(composed.width, composed.height)
            val scale = min(640f / largest, 1f)
            val width = maxOf(1, (composed.width * scale).toInt())
            val height = maxOf(1, (composed.height * scale).toInt())
            val composedCopy = Bitmap.createScaledBitmap(composed, width, height, true)
            LiveNexusControllerApi.sendVisualComparison(
                ticket.context, ticket.requestId, ticket.sourceId, original, composedCopy, top, bottom
            )
        } catch (error: Exception) {
            original.recycle()
            top.recycle()
            bottom.recycle()
            Log.w(TAG, "Falha na cópia do quadro composto para diagnóstico", error)
        }
    }

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
        private var diagnosticContext: Context? = null
        @Volatile private var webViewMetrics: JSONObject? = null

        // Diagnóstico: contadores de captura, sem ler conteúdo visual ou URLs.
        private var callbacksReceived = 0L
        private var framesAccepted = 0L
        private var framesThrottled = 0L
        private var framesInvalid = 0L
        private var frameErrors = 0L
        private var totalConversionMs = 0L
        private var maxConversionMs = 0L
        private var lastDiagnosticsAt = SystemClock.uptimeMillis()
        private val framesDrawn = AtomicLong(0L)

        private fun logCaptureMetrics() {
            val now = SystemClock.uptimeMillis()
            if (now - lastDiagnosticsAt < 10_000L) return
            val elapsed = now - lastDiagnosticsAt
            val avgMs = if (framesAccepted > 0L) totalConversionMs / framesAccepted else 0L
            val report = JSONObject().apply {
                put("sourceId", "source_" + Integer.toHexString(id.hashCode()))
                put("counters", JSONObject().apply {
                    put("callbacks", callbacksReceived)
                    put("accepted", framesAccepted)
                    put("throttled", framesThrottled)
                    put("invalid", framesInvalid)
                    put("errors", frameErrors)
                    put("draws", framesDrawn.get())
                    put("avgCopyMs", avgMs)
                    put("maxCopyMs", maxConversionMs)
                    put("windowMs", elapsed)
                    put("width", SOURCE_WIDTH)
                    put("height", SOURCE_HEIGHT)
                })
                webViewMetrics?.let { put("webView", it) }
            }
            diagnosticContext?.let { LiveNexusControllerApi.sendBrowserDiagnostics(it, report) }
            Log.i(TAG, "Browser Source frames: callbacks=$callbacksReceived accepted=$framesAccepted " +
                "throttled=$framesThrottled invalid=$framesInvalid errors=$frameErrors " +
                "draws=${framesDrawn.get()} avgCopyMs=$avgMs maxCopyMs=$maxConversionMs " +
                "windowMs=$elapsed size=${SOURCE_WIDTH}x$SOURCE_HEIGHT")
            callbacksReceived = 0L
            framesAccepted = 0L
            framesThrottled = 0L
            framesInvalid = 0L
            frameErrors = 0L
            totalConversionMs = 0L
            maxConversionMs = 0L
            framesDrawn.set(0L)
            lastDiagnosticsAt = now
        }

        private fun logWebViewMetrics(browser: WebView, stage: String) {
            if (closed) return
            val metrics = browser.resources.displayMetrics
            val packageVersion = WebView.getCurrentWebViewPackage()?.versionName ?: "indisponivel"
            webViewMetrics = JSONObject().apply {
                put("version", packageVersion)
                put("width", browser.width)
                put("height", browser.height)
                put("density", metrics.density.toDouble())
                put("densityDpi", metrics.densityDpi)
                put("displayWidth", metrics.widthPixels)
                put("displayHeight", metrics.heightPixels)
            }
            Log.i(TAG, "Browser Source WebView: stage=$stage package=$packageVersion " +
                "view=${browser.width}x${browser.height} density=${metrics.density} " +
                "densityDpi=${metrics.densityDpi} display=${metrics.widthPixels}x${metrics.heightPixels}")
            browser.evaluateJavascript(
                "(function(){var v=window.visualViewport;" +
                    "return JSON.stringify({width:window.innerWidth,height:window.innerHeight," +
                    "dpr:window.devicePixelRatio,visualWidth:v?v.width:null," +
                    "visualHeight:v?v.height:null,visualScale:v?v.scale:null});})()"
            ) { result ->
                if (closed) return@evaluateJavascript
                try {
                    val raw = JSONTokener(result).nextValue() as? String ?: return@evaluateJavascript
                    val viewport = JSONObject(raw)
                    val next = JSONObject(webViewMetrics?.toString() ?: "{}")
                    for ((key, target) in listOf(
                        "width" to "viewportWidth", "height" to "viewportHeight",
                        "dpr" to "dpr", "visualWidth" to "visualWidth",
                        "visualHeight" to "visualHeight", "visualScale" to "visualScale"
                    )) {
                        if (!viewport.isNull(key)) next.put(target, viewport.optDouble(key))
                    }
                    webViewMetrics = next
                } catch (_: Exception) {
                    // Não interferir na renderização quando as métricas JS estiverem indisponíveis.
                }
                Log.i(TAG, "Browser Source CSS viewport: stage=$stage metrics=$result")
            }
        }


        fun collectDomGeometry(context: Context, requestId: String, sourceId: String) {
            if (closed) return
            val browser = webView ?: return
            // Apenas metadados geométricos e estilos: sem texto, conteúdo HTML, IDs ou URLs.
            val script = """
                (function () {
                  try {
                    var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
                    var list = document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,span,small,strong,b,em,label,time,output,marquee');
                    var rows = [];
                    for (var i = 0; i < list.length && i < 4000; i++) {
                      var el = list[i];
                      if (!el || !el.getBoundingClientRect) continue;
                      var r = el.getBoundingClientRect();
                      if (r.width <= 0 || r.height <= 0 || r.right <= 0 || r.left >= vw ||
                          r.bottom <= 0 || r.top >= vh) continue;
                      var region = r.top < 200 ? 'top' : (r.bottom > vh - 340 ? 'bottom' : '');
                      if (!region) continue;
                      var hasDirectText = Array.prototype.some.call(el.childNodes, function (n) {
                        return n.nodeType === 3 && !!(n.textContent || '').trim();
                      });
                      if (!hasDirectText) continue;
                      var s = window.getComputedStyle(el);
                      if (!s || s.display === 'none' || s.visibility === 'hidden') continue;
                      var n = function (v) {
                        return Math.round((Number(v) || 0) * 100) / 100;
                      };
                      var t = function (v) {
                        return String(v || '').substring(0, 140);
                      };
                      var row = {
                        index: i, region: region, tag: el.tagName,
                        x: n(r.left), y: n(r.top), width: n(r.width), height: n(r.height),
                        clientWidth: n(el.clientWidth), clientHeight: n(el.clientHeight),
                        scrollWidth: n(el.scrollWidth), scrollHeight: n(el.scrollHeight),
                        fontFamily: t(s.fontFamily), fontSize: t(s.fontSize),
                        fontWeight: t(s.fontWeight), lineHeight: t(s.lineHeight),
                        overflowX: t(s.overflowX), overflowY: t(s.overflowY),
                        transform: t(s.transform)
                      };
                      row.exceeds = row.scrollWidth > row.clientWidth + 2 ||
                          row.scrollHeight > row.clientHeight + 2 ? 1 : 0;
                      rows.push(row);
                    }
                    // Prioriza ocorrências de transbordamento e mantém amostras do topo/base.
                    rows.sort(function (a, b) {
                      return b.exceeds - a.exceeds || a.index - b.index;
                    });
                    rows = rows.slice(0, 80).map(function (row) {
                      delete row.exceeds;
                      return row;
                    });
                    return JSON.stringify({
                      fontStatus: document.fonts ? document.fonts.status : 'unavailable',
                      viewportWidth: vw, viewportHeight: vh, elements: rows
                    });
                  } catch (e) {
                    return '';
                  }
                })()
            """.trimIndent()
            try {
                browser.evaluateJavascript(script) { result ->
                    // A captura visual pode terminar antes do callback JS assíncrono.
                    // A API autentica e valida o requestId e sua expiração.
                    if (closed) return@evaluateJavascript
                    try {
                        val raw = JSONTokener(result).nextValue() as? String ?: return@evaluateJavascript
                        if (raw.isEmpty() || raw.length > 30_000) return@evaluateJavascript
                        val dom = JSONObject(raw)
                        LiveNexusControllerApi.sendDomGeometry(context, requestId, sourceId, dom)
                    } catch (error: Exception) {
                        Log.w(TAG, "Falha na leitura de geometria HTML para diagnóstico", error)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "WebView indisponível para diagnóstico geométrico", error)
            }
        }

        fun start(context: Context) {
            check(Looper.myLooper() == Looper.getMainLooper())
            diagnosticContext = context.applicationContext
            val reader = ImageReader.newInstance(
                SOURCE_WIDTH, SOURCE_HEIGHT, PixelFormat.RGBA_8888, 3
            )
            imageReader = reader
            val thread = HandlerThread("BrowserFrames-${id.hashCode()}").apply { start() }
            imageThread = thread
            reader.setOnImageAvailableListener({ ir ->
                callbacksReceived++
                val image = try { ir.acquireLatestImage() } catch (e: Exception) {
                    frameErrors++
                    null
                } ?: run {
                    logCaptureMetrics()
                    return@setOnImageAvailableListener
                }
                try {
                    if (closed) return@setOnImageAvailableListener
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                        framesThrottled++
                        return@setOnImageAvailableListener
                    }
                    val plane = image.planes.firstOrNull() ?: run {
                        framesInvalid++
                        return@setOnImageAvailableListener
                    }
                    val stride = plane.pixelStride
                    val rowStride = plane.rowStride
                    if (stride != 4 || rowStride < SOURCE_WIDTH * stride) {
                        framesInvalid++
                        return@setOnImageAvailableListener
                    }
                    val copyStartedAt = SystemClock.uptimeMillis()
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
                        if (transferred) {
                            framesAccepted++
                            val copyMs = SystemClock.uptimeMillis() - copyStartedAt
                            totalConversionMs += copyMs
                            maxConversionMs = maxOf(maxConversionMs, copyMs)
                        }
                    } finally {
                        if (!transferred && frame != null && !frame.isRecycled) frame.recycle()
                        if (frame !== padded && !padded.isRecycled) padded.recycle()
                        if (frame == null && !padded.isRecycled) padded.recycle()
                    }
                } catch (e: Exception) {
                    frameErrors++
                    if (!closed) Log.w(TAG, "Falha ao obter quadro Browser Source '$id'", e)
                } finally {
                    try { image.close() } catch (_: Exception) {}
                    if (!closed) logCaptureMetrics()
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

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            logWebViewMetrics(view, "page-finished")
                            view.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    if (!closed) logWebViewMetrics(view, "visual-state-ready")
                                }
                            })
                            view.postDelayed({
                                if (!closed) logWebViewMetrics(view, "after-3s")
                            }, 3000L)
                        }

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
                // Obtém o mesmo Bitmap que acabou de ser desenhado no compositor,
                // nunca o quadro do editor, e somente após solicitação explícita.
                val ticket = pendingCapture
                if (renderingScene.get() == true && ticket != null &&
                    ticket.sourceId == "source_" + Integer.toHexString(id.hashCode()) &&
                    stagedOriginal == null) {
                    try {
                        // Recortes copiados do Bitmap 1920×1080, sem redimensionar pixels.
                        // Copiar antes de qualquer escala aplicada ao relatório A/B.
                        val originalCopy = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
                        var topCopy: Bitmap? = null
                        var bottomCopy: Bitmap? = null
                        try {
                            topCopy = Bitmap.createBitmap(bitmap, 0, 0, 1920, 160)
                            bottomCopy = Bitmap.createBitmap(bitmap, 0, 780, 960, 300)
                            stagedOriginal = originalCopy
                            stagedTop = topCopy
                            stagedBottom = bottomCopy
                            stagedRequestId = ticket.requestId
                        } catch (error: Exception) {
                            originalCopy.recycle()
                            topCopy?.recycle()
                            bottomCopy?.recycle()
                            throw error
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Falha na cópia do quadro original para diagnóstico", error)
                    }
                }
                framesDrawn.incrementAndGet()
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
            diagnosticContext = null
            webViewMetrics = null
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
