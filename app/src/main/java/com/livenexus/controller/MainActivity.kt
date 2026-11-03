package com.livenexus.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.view.Gravity
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("buttons", MODE_PRIVATE) }
    private val capturePrefs by lazy { getSharedPreferences("livenexus_capture_settings", MODE_PRIVATE) }
    @Volatile private var authenticated = false
    private val loginBrowsers = mutableListOf<WebView>()
    private val loginDialogs = mutableListOf<AlertDialog>()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var panel: ControllerPanel? = null
    // page: 0 = Controlador, 1 = Captura, 2 = Transmissão, 3 = Conexão, 4 = Configuração
    private var page = 0
    private var resumed = false
    private var isTransmitting = false

    companion object {
        private const val CAPTURE_REQUEST = 7820
        private const val AUDIO_PERMISSION_REQUEST = 7821
    }

    private var lastMediaProjectionResultCode: Int = 0
    private var lastMediaProjectionData: Intent? = null
    private var transmissionStartTime = 0L
    @Volatile private var isRequestingMediaProjection = false
    @Volatile private var startStreamAfterCaptureAuth = false
    @Volatile private var capturePermissionDeclinedForSession = false

    private val platformConnected = mutableMapOf(
        "youtube" to false,
        "twitch" to false,
        "kick" to false
    )

    data class Scene(val id: String, var name: String, val sources: MutableList<CaptureLayer>)
    private val captureScenes = mutableListOf<Scene>()
    private var activeCaptureSceneId = ""
    private var activeCaptureLayerId: String? = null
    private var captureCanvas: CaptureSceneCanvas? = null
    private var captureCanvasHost: FrameLayout? = null
    private var captureBrowserView: WebView? = null
    private var captureBrowserUrl: String? = null

    private open inner class LoginWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val destination = request?.url ?: return null
            val root = Uri.parse(LiveNexusApi.baseUrl)
            if (request.method == "GET" && destination.scheme == root.scheme &&
                destination.host == root.host && destination.port == root.port &&
                destination.path?.trimEnd('/') == "/api/controller/commands") {
                return WebResourceResponse(
                    "application/json", "UTF-8",
                    """{"ok":true,"commands":[]}""".byteInputStream(Charsets.UTF_8)
                )
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    private fun releaseLoginBrowser(browser: WebView) {
        if (!loginBrowsers.remove(browser)) return
        browser.stopLoading()
        browser.removeJavascriptInterface("AndroidLogin")
        (browser.parent as? ViewGroup)?.removeView(browser)
        browser.destroy()
    }

    private fun closeLoginBrowsers() {
        loginDialogs.toList().forEach { it.dismiss() }
        loginDialogs.clear()
        loginBrowsers.toList().forEach { releaseLoginBrowser(it) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, stroke: Int = Color.rgb(47, 67, 89), radiusDp: Int = 16) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NeonTheme.ink
        window.navigationBarColor = NeonTheme.ink
        val targetPage = intent?.getIntExtra("target_page", -1) ?: -1
        if (targetPage >= 0) page = targetPage
        authenticated = true
        ControllerRuntime.signedIn = true
        cleanStaleNullPreferences()
        loadCaptureScenes()
        ScreenCaptureService.updateCaptureOrientation(isVerticalCaptureMode())
        syncActivityOrientationWithCaptureMode()
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val targetPage = intent?.getIntExtra("target_page", -1) ?: -1
        if (targetPage >= 0 && targetPage != page) {
            page = targetPage
            if (page == 1) capturePermissionDeclinedForSession = false
            syncActivityOrientationWithCaptureMode()
            render()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ScreenCaptureService.updateCaptureOrientation(isVerticalCaptureMode())
        if (authenticated && !isDestroyed) {
            render()
        }
    }

    private inner class LoginBridge {
        @JavascriptInterface fun authenticated(token: String) {
            if (token.isBlank() || authenticated) return
            Thread {
                try {
                    LiveNexusApi.acceptWebToken(token)
                    runOnUiThread {
                        if (!authenticated) {
                            authenticated = true
                            render()
                        }
                    }
                } catch (_: Exception) {}
            }.start()
        }
    }

    private fun showLogin() {
        authenticated = false
        ControllerRuntime.signedIn = false
        stopService(Intent(this, OverlayService::class.java))
        val browser = WebView(this)
        loginBrowsers.add(browser)
        browser.settings.javaScriptEnabled = true
        browser.addJavascriptInterface(LoginBridge(), "AndroidLogin")
        browser.settings.domStorageEnabled = true
        browser.settings.javaScriptCanOpenWindowsAutomatically = true
        browser.settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val popup = WebView(this@MainActivity)
                loginBrowsers.add(popup)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                popup.isFocusable = true
                popup.isFocusableInTouchMode = true
                popup.webViewClient = LoginWebViewClient()
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        releaseLoginBrowser(popup)
                        verifyLogin()
                    }
                }
                val dialog = AlertDialog.Builder(this@MainActivity).setView(popup)
                    .setNegativeButton("Fechar") { _, _ -> verifyLogin() }.create()
                loginDialogs.add(dialog)
                dialog.setOnDismissListener {
                    loginDialogs.remove(dialog)
                    releaseLoginBrowser(popup)
                    verifyLogin()
                }
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                )
                dialog.show()
                dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                popup.requestFocus(View.FOCUS_DOWN)
                popup.setOnTouchListener { web, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        web.requestFocus()
                        web.postDelayed({
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                        }, 120)
                    }
                    false
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        browser.webViewClient = object : LoginWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val destination = request?.url ?: return false
                val root = Uri.parse(LiveNexusApi.baseUrl)
                if (destination.scheme == root.scheme && destination.host == root.host &&
                    destination.path == "/" && !authenticated) {
                    view?.evaluateJavascript("window.localStorage.getItem('livenexus_token')", null)
                    return true
                }
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.startsWith(LiveNexusApi.baseUrl) != true) return
                view?.evaluateJavascript(
                    """(function(){
                      if(location.pathname.startsWith('/login')){
                        const css=document.createElement('style');
                        css.textContent='.auth-footer,.auth-back-link{display:none!important} body{background:linear-gradient(135deg,#03051b,#0c0325)!important;color:#fff!important} input{background:#050b26!important;color:#fff!important;border:1px solid #283cab!important;border-radius:12px!important} button{border-radius:14px!important;box-shadow:0 0 12px #7424d966!important}';
                        document.head.appendChild(css);
                      }
                      if(!window.__lnAuthCheck){
                        window.__lnAuthCheck=setInterval(function(){
                          try {
                            const token=localStorage.getItem('livenexus_token') ||
                              sessionStorage.getItem('livenexus_token');
                            if(token) AndroidLogin.authenticated(token);
                            if(location.pathname==='/' && !token) location.replace('/login');
                          } catch(e) {}
                        },250);
                      }
                    })();""", null
                )
            }
        }
        setContentView(browser)
        browser.loadUrl(LiveNexusApi.baseUrl + "/login")
    }

    private fun verifyLogin() {
        if (authenticated) return
        Thread {
            try {
                LiveNexusApi.checkSession()
                runOnUiThread {
                    if (!authenticated) {
                        authenticated = true
                        render()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    override fun onStart() {
        super.onStart()
        ControllerRuntime.foreground = true
        OverlayService.refreshVisibility()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (authenticated) prepareOverlay()
        resumeCaptureRefresh()
        checkStreamingPlatformsStatus()
    }

    override fun onPause() {
        resumed = false
        uiHandler.removeCallbacks(captureRefreshRunnable)
        uiHandler.removeCallbacks(transmissionTimerRunnable)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            ControllerRuntime.foreground = false
            OverlayService.refreshVisibility()
        }
    }

    override fun onBackPressed() {
        if (authenticated && page != 0) {
            page = 0
            render()
        } else if (authenticated) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(captureRefreshRunnable)
        uiHandler.removeCallbacks(transmissionTimerRunnable)
        captureBrowserView?.let { captureCanvasHost?.removeView(it); it.destroy() }
        captureBrowserView = null
        panel?.dispose()
        loginDialogs.forEach { it.setOnDismissListener(null) }
        closeLoginBrowsers()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQUEST) {
            isRequestingMediaProjection = false
            if (resultCode != RESULT_OK || data == null) {
                capturePermissionDeclinedForSession = true
                startStreamAfterCaptureAuth = false
                ScreenCaptureService.reportError("Captura de tela não autorizada")
                Toast.makeText(this, "Captura de tela não autorizada.", Toast.LENGTH_SHORT).show()
                return
            }
            capturePermissionDeclinedForSession = false
            lastMediaProjectionResultCode = resultCode
            lastMediaProjectionData = data
            try {
                val vertical = isVerticalCaptureMode()
                ScreenCaptureService.updateCaptureOrientation(vertical)
                ScreenCaptureService.updateActiveSceneLayers(activeCaptureScene()?.sources)
                val size = capturePrefs.getInt("max_dimension", 1280)
                val fps = capturePrefs.getInt("capture_fps", 30)
                val bitrate = capturePrefs.getInt("capture_bitrate_kbps", 4500)
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    putExtra(ScreenCaptureService.EXTRA_MAX_DIMENSION, size)
                    putExtra(ScreenCaptureService.EXTRA_FPS, fps)
                    putExtra(ScreenCaptureService.EXTRA_BITRATE_KBPS, bitrate)
                    putExtra(ScreenCaptureService.EXTRA_VERTICAL_MODE, vertical)
                }
                startForegroundService(intent)
                syncActivityOrientationWithCaptureMode()
                refreshCaptureCanvas()
                resumeCaptureRefresh()
                if (startStreamAfterCaptureAuth) {
                    startStreamAfterCaptureAuth = false
                    uiHandler.postDelayed({ startLiveTransmission() }, 250L)
                }
            } catch (e: Throwable) {
                startStreamAfterCaptureAuth = false
                ScreenCaptureService.reportError("Captura de tela indisponível")
                Toast.makeText(this, "Captura de tela indisponível.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            val hasScreenSource = activeCaptureScene()?.sources?.any { it.type == "screen" && it.visible } == true
            if (hasScreenSource && !ScreenCaptureService.isCapturing) {
                startStreamAfterCaptureAuth = true
                capturePermissionDeclinedForSession = false
                requestScreenCaptureForSource(allowFromAnyPage = true)
            } else {
                startLiveTransmission()
            }
        }
    }

    // ==========================================
    // CONEXÕES E TRANSMISSÃO EM MODO REAL
    // ==========================================

    private val transmissionTimerRunnable = object : Runnable {
        override fun run() {
            if (!isTransmitting) return
            if (page == 2) render()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private fun formatElapsedTime(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun checkStreamingPlatformsStatus(onComplete: (() -> Unit)? = null) {
        val platforms = listOf("youtube", "twitch", "kick")
        platforms.forEach { p ->
            val hasKey = isValidStreamKey(capturePrefs.getString("key_$p", ""))
            platformConnected[p] = hasKey
        }
        onComplete?.invoke()
        if (page == 2) render()
    }

    private fun cleanStaleNullPreferences() {
        val keysToClean = listOf(
            "key_youtube", "key_twitch", "key_kick",
            "youtube_username", "twitch_username", "kick_username",
            "url_youtube", "url_twitch", "url_kick"
        )
        val edit = capturePrefs.edit()
        var changed = false
        for (k in keysToClean) {
            val v = capturePrefs.getString(k, null)
            if (v != null && (v.trim().isEmpty() || v.equals("null", true) || v.equals("undefined", true) || v.equals("none", true))) {
                edit.remove(k)
                changed = true
            }
        }
        val ytUser = capturePrefs.getString("youtube_username", null)?.trim()
        if (!ytUser.isNullOrBlank() && !ytUser.startsWith("@")) {
            edit.putString("youtube_username", "@${ytUser.removePrefix("@").trim()}")
            changed = true
        }
        if (changed) edit.apply()
    }

    private fun openPlatformAuthDialog(platform: String) {
        val name = when (platform) {
            "youtube" -> "YouTube"
            "twitch" -> "Twitch"
            "kick" -> "Kick"
            else -> platform.replaceFirstChar { it.uppercase() }
        }
        openPlatformRtmpDialog(platform, name)
    }

    private fun openTwitchAuthDialog() {
        openPlatformRtmpDialog("twitch", "Twitch")
    }

    private fun openYouTubeAuthDialog() {
        openPlatformRtmpDialog("youtube", "YouTube")
    }

    private fun openYouTubeStudioDialog() {
        openPlatformRtmpDialog("youtube", "YouTube")
    }

    private fun openKickAuthDialog() {
        openPlatformRtmpDialog("kick", "Kick")
    }



    private fun openKickRtmpDialog() {
        openPlatformRtmpDialog("kick", "Kick")
    }

    private fun openStreamKeyDialog(platform: String, platformName: String) {
        openPlatformRtmpDialog(platform, platformName)
    }

    private fun isValidStreamKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val trimmed = key.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.equals("null", ignoreCase = true) &&
            !trimmed.equals("undefined", ignoreCase = true) &&
            !trimmed.equals("none", ignoreCase = true)
    }

    private fun openPlatformRtmpDialog(platform: String, platformName: String) {
        val currentKey = capturePrefs.getString("key_$platform", "")?.trim() ?: ""
        val initialKey = if (isValidStreamKey(currentKey)) currentKey else ""
        val currentUser = capturePrefs.getString("${platform}_username", "")?.trim() ?: ""
        val sanitizedUser = if (currentUser.equals("null", ignoreCase = true) || currentUser.equals("undefined", ignoreCase = true)) "" else currentUser
        val initialUser = if (platform == "youtube") {
            val body = sanitizedUser.removePrefix("@").trim()
            if (body.isNotBlank()) "@$body" else "@"
        } else {
            sanitizedUser
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val userLabel = when (platform) {
            "youtube" -> "Nome ou @handle / Hash do Canal:"
            else -> "Nome ou Hash do Canal ($platformName):"
        }
        layout.addView(text(userLabel, 12f, Color.rgb(200, 210, 240)).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val userInput = EditText(this).apply {
            hint = when (platform) {
                "youtube" -> "Ex: @MeuCanalAoVivo"
                "twitch" -> "Ex: MeuCanalTwitch"
                "kick" -> "Ex: MeuCanalKick"
                else -> "Ex: MeuCanal$platformName"
            }
            setText(initialUser)
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = shape(Color.rgb(14, 18, 44), NeonTheme.purple, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSelection(text.length)
            if (platform == "youtube") {
                addTextChangedListener(object : android.text.TextWatcher {
                    private var editing = false
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        if (editing || s == null) return
                        val current = s.toString()
                        if (!current.startsWith("@")) {
                            editing = true
                            val fixed = "@" + current.replace("@", "")
                            s.replace(0, s.length, fixed)
                            setSelection(s.length)
                            editing = false
                        }
                    }
                })
            }
        }
        layout.addView(userInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        layout.addView(text("Chave de Transmissão (Stream Key):", 12f, Color.rgb(200, 210, 240)).apply {
            setPadding(0, 0, 0, dp(4))
        })
        val keyInput = EditText(this).apply {
            hint = when (platform) {
                "youtube" -> "Cole sua chave de transmissão aqui (xxxx-xxxx-xxxx-xxxx-xxxx)"
                "twitch" -> "Cole sua chave de transmissão aqui (live_...)"
                "kick" -> "Cole sua chave de transmissão aqui (sk_...)"
                else -> "Cole sua chave de transmissão aqui"
            }
            setText(initialKey)
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = shape(Color.rgb(14, 18, 44), NeonTheme.purple, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        layout.addView(keyInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("Chave de Transmissão - $platformName")
            .setView(scrollView)
            .setPositiveButton("Salvar") { _, _ ->
                val key = keyInput.text.toString().trim()
                if (!isValidStreamKey(key)) {
                    Toast.makeText(this, "A Chave de Transmissão é obrigatória.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val rawUser = userInput.text.toString().trim()
                val cleanBody = rawUser.removePrefix("@").trim()
                val finalUsername = if (platform == "youtube") {
                    if (cleanBody.isNotBlank()) "@$cleanBody" else "@CanalYouTube"
                } else {
                    if (cleanBody.isNotBlank()) rawUser else "Canal $platformName"
                }
                capturePrefs.edit()
                    .putString("key_$platform", key)
                    .putString("${platform}_username", finalUsername)
                    .putBoolean("stream_$platform", true)
                    .apply()
                platformConnected[platform] = true
                Toast.makeText(this, "Chave de transmissão de $platformName salva!", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNeutralButton("Remover") { _, _ ->
                capturePrefs.edit()
                    .remove("key_$platform")
                    .remove("url_$platform")
                    .remove("${platform}_username")
                    .remove("${platform}_token")
                    .remove("${platform}_login")
                    .remove("${platform}_user_id")
                    .putBoolean("stream_$platform", false)
                    .apply()
                platformConnected[platform] = false
                Toast.makeText(this, "Chave de $platformName removida.", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun buildActiveTargetsJson(): String = StreamingConfig.buildActiveTargetsJson(this)

    private fun startLiveTransmission() {
        val targetsJson = buildActiveTargetsJson()
        if (targetsJson == "[]") {
            Toast.makeText(this, "Nenhuma plataforma com chave de transmissão válida.", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = AlertDialog.Builder(this)
            .setTitle("Iniciando Transmissão")
            .setMessage("Estabelecendo conexão RTMP/RTMPS e codificando vídeo H.264 e áudio AAC...")
            .setCancelable(false)
            .create()
        progress.show()

        try {
            ScreenCaptureService.updateActiveSceneLayers(activeCaptureScene()?.sources)
            val size = capturePrefs.getInt("max_dimension", 1280)
            val fps = capturePrefs.getInt("capture_fps", 30)
            val bitrate = capturePrefs.getInt("capture_bitrate_kbps", 4500)
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_STREAM
                putExtra(ScreenCaptureService.EXTRA_MAX_DIMENSION, size)
                putExtra(ScreenCaptureService.EXTRA_FPS, fps)
                putExtra(ScreenCaptureService.EXTRA_BITRATE_KBPS, bitrate)
                putExtra(ScreenCaptureService.EXTRA_TARGETS_JSON, targetsJson)
                putExtra(ScreenCaptureService.EXTRA_VERTICAL_MODE, isVerticalCaptureMode())
            }
            startForegroundService(intent)

            val pollStart = System.currentTimeMillis()
            val pollRunnable = object : Runnable {
                override fun run() {
                    if (isFinishing || isDestroyed) {
                        try { progress.dismiss() } catch (_: Exception) {}
                        return
                    }
                    val elapsed = System.currentTimeMillis() - pollStart
                    // Aguarda pelo menos 400ms para o Service iniciar e enquanto isConnecting for true (até 18s)
                    if ((elapsed < 400L || ScreenCaptureService.isConnecting) && elapsed < 18000L) {
                        uiHandler.postDelayed(this, 250L)
                        return
                    }

                    try { progress.dismiss() } catch (_: Exception) {}
                    if (ScreenCaptureService.isLiveStreaming) {
                        isTransmitting = true
                        transmissionStartTime = System.currentTimeMillis()
                        ControllerRuntime.isTransmitting = true
                        ControllerRuntime.transmissionStartTime = transmissionStartTime
                        OverlayService.refreshVisibility()
                        uiHandler.removeCallbacks(transmissionTimerRunnable)
                        uiHandler.post(transmissionTimerRunnable)
                        val activeNames = ScreenCaptureService.activePlatforms.joinToString(", ")
                        Toast.makeText(this@MainActivity, "Transmissão ao vivo iniciada para: $activeNames!", Toast.LENGTH_LONG).show()
                    } else {
                        isTransmitting = false
                        val errMsg = ScreenCaptureService.lastError ?: "Falha ao conectar com servidor RTMP"
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Falha na Transmissão")
                            .setMessage("Não foi possível publicar ao vivo:\n\n$errMsg\n\nVerifique sua conexão e a chave de transmissão.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    render()
                }
            }
            uiHandler.postDelayed(pollRunnable, 300L)
        } catch (e: Throwable) {
            try { progress.dismiss() } catch (_: Exception) {}
            ScreenCaptureService.reportError(e.message ?: "Falha ao iniciar transmissão")
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLiveTransmission() {
        isTransmitting = false
        transmissionStartTime = 0L
        ControllerRuntime.isTransmitting = false
        ControllerRuntime.transmissionStartTime = 0L
        OverlayService.refreshVisibility()
        uiHandler.removeCallbacks(transmissionTimerRunnable)

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_STREAM
        }
        startService(stopIntent)

        Toast.makeText(this, "Transmissão encerrada com sucesso.", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun handleStartTransmissionClick() {
        val selectedPlatforms = listOf(
            Triple("stream_youtube", "YouTube", "youtube"),
            Triple("stream_twitch", "Twitch", "twitch"),
            Triple("stream_kick", "Kick", "kick")
        ).filter { capturePrefs.getBoolean(it.first, false) }

        if (selectedPlatforms.isEmpty()) {
            Toast.makeText(this, "Selecione ao menos uma plataforma de transmissão.", Toast.LENGTH_SHORT).show()
            return
        }

        // Verifica se todas as plataformas marcadas possuem Stream Key real cadastrada
        val missingKey = selectedPlatforms.firstOrNull {
            !isValidStreamKey(capturePrefs.getString("key_${it.third}", ""))
        }
        if (missingKey != null) {
            AlertDialog.Builder(this)
                .setTitle("Chave Necessária - ${missingKey.second}")
                .setMessage("A plataforma ${missingKey.second} está selecionada, mas não possui chave de transmissão (Stream Key) configurada.\n\nPor favor, insira a chave RTMP da ${missingKey.second} para transmitir.")
                .setPositiveButton("Inserir Chave") { _, _ ->
                    if (missingKey.third == "youtube") openYouTubeStudioDialog()
                    else openStreamKeyDialog(missingKey.third, missingKey.second)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        // Solicita permissão de áudio do microfone antes de transmitir a cena
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            return
        }

        // Se a cena ativa possui fonte "Captura de tela" visível e ainda não está capturando, solicita autorização do Android e inicia em seguida
        val hasScreenSource = activeCaptureScene()?.sources?.any { it.type == "screen" && it.visible } == true
        if (hasScreenSource && !ScreenCaptureService.isCapturing) {
            startStreamAfterCaptureAuth = true
            capturePermissionDeclinedForSession = false
            requestScreenCaptureForSource(allowFromAnyPage = true)
            return
        }

        startLiveTransmission()
    }

    private fun requestScreenCaptureForSource(allowFromAnyPage: Boolean = false) {
        if (!allowFromAnyPage && page != 1) return
        val hasScreenSource = activeCaptureScene()?.sources?.any { it.type == "screen" && it.visible } == true
        if (!hasScreenSource) return
        if (ScreenCaptureService.isCapturing || isRequestingMediaProjection) return
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            val captureIntent = manager?.createScreenCaptureIntent()
            if (captureIntent != null && captureIntent.resolveActivity(packageManager) != null) {
                isRequestingMediaProjection = true
                startActivityForResult(captureIntent, CAPTURE_REQUEST)
                return
            }
        } catch (_: Throwable) {
            isRequestingMediaProjection = false
        }
        startStreamAfterCaptureAuth = false
        Toast.makeText(this, "Não foi possível solicitar permissão de captura de tela.", Toast.LENGTH_SHORT).show()
    }

    private fun ensureScreenCaptureStartedForActiveScene(forcePrompt: Boolean = false) {
        if (forcePrompt) {
            capturePermissionDeclinedForSession = false
        }
        val hasVisibleScreenSource = activeCaptureScene()?.sources?.any { it.type == "screen" && it.visible } == true
        if (!hasVisibleScreenSource) return
        if (ScreenCaptureService.isCapturing || isRequestingMediaProjection) return
        if (capturePermissionDeclinedForSession && !forcePrompt) return
        uiHandler.post {
            if (!isFinishing && !isDestroyed && !ScreenCaptureService.isCapturing && !isRequestingMediaProjection) {
                requestScreenCaptureForSource(allowFromAnyPage = true)
            }
        }
    }

    private fun stopScreenCaptureForSource() {
        lastMediaProjectionResultCode = 0
        lastMediaProjectionData = null
        isRequestingMediaProjection = false
        try {
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP_CAPTURE
            }
            startService(stopIntent)
        } catch (_: Throwable) {}
        captureCanvas?.frame = null
        refreshCaptureCanvas()
    }

    // ==========================================
    // GESTÃO DE CENAS E FONTES DA CAPTURA
    // ==========================================

    private fun activeCaptureScene(): Scene? = captureScenes.find { it.id == activeCaptureSceneId }

    private fun loadCaptureScenes() {
        captureScenes.clear()
        try {
            val saved = JSONArray(capturePrefs.getString("capture_scenes", "[]"))
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isBlank() || name.isBlank() || captureScenes.any { it.id == id }) continue
                val sources = mutableListOf<CaptureLayer>()
                val list = item.optJSONArray("sources")
                for (j in 0 until (list?.length() ?: 0)) {
                    val raw = list?.opt(j)
                    if (raw is String && raw == "screen") {
                        sources.add(CaptureLayer(java.util.UUID.randomUUID().toString(), "screen", "Captura de tela"))
                    } else if (raw is JSONObject) {
                        val type = raw.optString("type")
                        if (type != "screen" && type != "text" && type != "browser") continue
                        val sourceId = raw.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                        if (sources.any { it.id == sourceId }) continue
                        sources.add(CaptureLayer(
                            sourceId, type,
                            raw.optString("name").ifBlank { if (type == "screen") "Captura de tela" else "Texto" },
                            raw.optDouble("x", 0.1).toFloat().coerceIn(-2f, 2f),
                            raw.optDouble("y", 0.1).toFloat().coerceIn(-2f, 2f),
                            raw.optDouble("width", .8).toFloat().coerceIn(.08f, 3f),
                            raw.optDouble("height", .65).toFloat().coerceIn(.08f, 3f),
                            raw.optBoolean("visible", true),
                            raw.optBoolean("locked", false),
                            raw.optString("url", "")
                        ))
                    }
                }
                captureScenes.add(Scene(id, name, sources))
            }
        } catch (_: org.json.JSONException) {}
        if (captureScenes.isEmpty()) {
            captureScenes.add(Scene("default", "Cena 1",
                mutableListOf(CaptureLayer("default-screen", "screen", "Captura de tela", 0f, 0f, 1f, 1f))))
        }
        captureScenes.forEach { scene ->
            scene.sources.filter { it.id == "default-screen" && it.type == "screen" }.forEach { layer ->
                if (layer.x == 0.1f && layer.y == 0.1f &&
                    layer.width == 0.8f && layer.height == 0.65f) {
                    layer.x = 0f
                    layer.y = 0f
                    layer.width = 1f
                    layer.height = 1f
                }
            }
        }
        val savedId = capturePrefs.getString("active_capture_scene", "")
        activeCaptureSceneId = captureScenes.find { it.id == savedId }?.id ?: captureScenes.first().id
        activeCaptureLayerId = activeCaptureScene()?.sources?.lastOrNull()?.id
    }

    private fun persistCaptureScenes() {
        val array = JSONArray()
        captureScenes.forEach { scene ->
            val sources = JSONArray()
            scene.sources.forEach { layer ->
                sources.put(JSONObject()
                    .put("id", layer.id)
                    .put("type", layer.type)
                    .put("name", layer.name)
                    .put("x", layer.x)
                    .put("y", layer.y)
                    .put("width", layer.width)
                    .put("height", layer.height)
                    .put("visible", layer.visible)
                    .put("locked", layer.locked)
                    .put("url", layer.url))
            }
            array.put(JSONObject().put("id", scene.id).put("name", scene.name)
                .put("sources", sources))
        }
        capturePrefs.edit()
            .putString("capture_scenes", array.toString())
            .putString("active_capture_scene", activeCaptureSceneId)
            .apply()
        ScreenCaptureService.updateActiveSceneLayers(activeCaptureScene()?.sources)
    }

    private fun isVerticalCaptureMode(): Boolean =
        capturePrefs.getString("capture_orientation", "horizontal") == "vertical"

    private fun syncActivityOrientationWithCaptureMode() {
        val shouldAlignOrientation = (page == 1 || page == 2 || ScreenCaptureService.isCapturing || ScreenCaptureService.isLiveStreaming)
        val desiredOrientation = if (shouldAlignOrientation) {
            if (isVerticalCaptureMode()) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != desiredOrientation) {
            requestedOrientation = desiredOrientation
        }
    }

    private fun toggleCaptureOrientationMode() {
        val nextVertical = !isVerticalCaptureMode()
        val nextValue = if (nextVertical) "vertical" else "horizontal"
        capturePrefs.edit().putString("capture_orientation", nextValue).apply()
        ScreenCaptureService.updateCaptureOrientation(nextVertical)
        syncActivityOrientationWithCaptureMode()
        captureCanvas?.isVertical = nextVertical
        updateCaptureBrowserSource()
        val label = if (nextVertical) "Vertical (9:16)" else "Horizontal (16:9)"
        Toast.makeText(this, "Modelo de captura alterado para: $label", Toast.LENGTH_SHORT).show()
        if (page == 1) render()
    }

    private fun refreshCaptureCanvas() {
        val scene = activeCaptureScene()
        val vertical = isVerticalCaptureMode()
        ScreenCaptureService.updateCaptureOrientation(vertical)
        captureCanvas?.isVertical = vertical
        captureCanvas?.layers = scene?.sources ?: emptyList()
        captureCanvas?.selectedLayerId = activeCaptureLayerId
        captureCanvas?.frame = if (ScreenCaptureService.isCapturing) ScreenCaptureService.previewFrame else null
        captureCanvas?.invalidate()
        ScreenCaptureService.updateActiveSceneLayers(scene?.sources)
        updateCaptureBrowserSource()
    }

    private fun updateCaptureBrowserSource() {
        val host = captureCanvasHost ?: return
        val layer = activeCaptureScene()?.sources?.lastOrNull {
            it.type == "browser" && it.visible && it.url.startsWith("https://")
        }
        if (layer == null) {
            captureBrowserView?.let { host.removeView(it); it.destroy() }
            captureBrowserView = null
            captureBrowserUrl = null
            return
        }
        val web = captureBrowserView ?: WebView(this).also { view ->
            view.setBackgroundColor(Color.TRANSPARENT)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = WebViewClient()
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            view.setOnTouchListener { _, event ->
                captureCanvas?.dispatchTouchEvent(event)
                true
            }
            host.addView(view)
            captureBrowserView = view
        }
        val width = host.width
        val height = host.height
        if (width > 0 && height > 0) {
            val stage = captureCanvas?.computeStageRect(width, height)
            val stageLeft = stage?.left ?: 0f
            val stageTop = stage?.top ?: 0f
            val stageW = stage?.width() ?: width.toFloat()
            val stageH = stage?.height() ?: height.toFloat()
            val w = (stageW * layer.width).toInt().coerceAtLeast(1)
            val h = (stageH * layer.height).toInt().coerceAtLeast(1)
            web.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                leftMargin = (stageLeft + stageW * layer.x).toInt()
                topMargin = (stageTop + stageH * layer.y).toInt()
            }
        }
        if (captureBrowserUrl != layer.url) {
            captureBrowserUrl = layer.url
            web.loadUrl(layer.url)
        }
    }

    private val captureRefreshRunnable = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (page == 1) {
                val running = ScreenCaptureService.isCapturing
                captureCanvas?.frame = if (running) ScreenCaptureService.previewFrame else null
            }
            uiHandler.postDelayed(this, 150L)
        }
    }

    private fun resumeCaptureRefresh() {
        uiHandler.removeCallbacks(captureRefreshRunnable)
        uiHandler.post(captureRefreshRunnable)
    }

    private fun prepareOverlay() {
        if (!resumed || !authenticated || !Settings.canDrawOverlays(this)) return
        try {
            startForegroundService(Intent(this, OverlayService::class.java))
        } catch (e: RuntimeException) {
            Toast.makeText(this, "Não foi possível preparar a sobreposição: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun permission() {
        if (Settings.canDrawOverlays(this)) {
            prepareOverlay()
            Toast.makeText(this, "Ao minimizar, o botão flutuante aparece automaticamente.", Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    // ==========================================
    // TOP BARS (CONFORME CADA TELA E ORIENTAÇÃO)
    // ==========================================

    private fun openQuickNavigationMenu() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NeonTheme.actionPanelBackground()
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        heading.addView(text("Navegação", 17f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val closeBtn = text("✕", 16f, Color.rgb(180, 175, 215)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        heading.addView(closeBtn)
        dialogView.addView(heading)

        val navTabs = listOf(
            Triple(0, "Controlador", "gamepad"),
            Triple(1, "Captura", "monitor"),
            Triple(2, "Transmissão", "broadcast"),
            Triple(3, "Conexão", "link"),
            Triple(4, "Configuração", "gear")
        )
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        closeBtn.setOnClickListener { dialog.dismiss() }

        navTabs.forEach { (id, title, iconSymbol) ->
            val active = page == id
            val itemBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                if (active) {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(104, 24, 168), Color.rgb(45, 12, 92))
                    ).apply {
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), NeonTheme.purple)
                    }
                } else {
                    background = NeonTheme.card(false)
                }
                val icon = ImageView(this@MainActivity).apply {
                    setImageDrawable(NeonIcon(iconSymbol, if (active) Color.WHITE else NeonTheme.cyan))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    rightMargin = dp(12)
                })
                addView(text(title, 14f, Color.WHITE).apply {
                    typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                })
                setOnClickListener {
                    page = id
                    render()
                    dialog.dismiss()
                }
            }
            dialogView.addView(itemBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(8)
            })
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun buildTopBar(isLandscape: Boolean): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }

        when (page) {
            0 -> {
                // Tela Controlador
                val menuBtn = text("☰", 22f, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Menu de navegação"
                    setOnClickListener {
                        openQuickNavigationMenu()
                    }
                }
                bar.addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(42)).apply {
                    rightMargin = dp(4)
                })

                val headerView = NeonHeader(this).apply {
                    contentDescription = "LiveNexus Controller"
                    setOnClickListener {
                        page = 0
                        render()
                    }
                }
                bar.addView(headerView, LinearLayout.LayoutParams(0, dp(if (isLandscape) 46 else 54), 1f))

                if (isLandscape) {
                    val statusBadge = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = shape(Color.rgb(7, 25, 32), Color.rgb(0, 180, 100), 20)
                        setPadding(dp(12), dp(5), dp(14), dp(5))
                        setOnClickListener {
                            page = 3
                            render()
                        }
                    }
                    val dot = View(this).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(NeonTheme.green)
                        }
                    }
                    statusBadge.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                        rightMargin = dp(8)
                    })
                    statusBadge.addView(text("Conectado ao Estúdio", 11.5f, Color.WHITE).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    bar.addView(statusBadge, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
                    ).apply { rightMargin = dp(10) })

                    // Ícone Wifi
                    val wifiIcon = ImageView(this).apply {
                        setImageDrawable(NeonIcon("wifi", Color.WHITE))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(5), dp(5), dp(5), dp(5))
                    }
                    bar.addView(wifiIcon, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                        rightMargin = dp(8)
                    })

                    // Ícone Engrenagem
                    val gearBtn = ImageView(this).apply {
                        setImageDrawable(NeonIcon("gear", Color.WHITE))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        background = shape(Color.rgb(14, 18, 42), Color.rgb(55, 65, 110), 18)
                        contentDescription = "Configuração"
                        setOnClickListener {
                            page = 4
                            render()
                        }
                    }
                    bar.addView(gearBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
                } else {
                    val dotContainer = FrameLayout(this).apply {
                        background = shape(Color.rgb(7, 28, 34), Color.rgb(0, 160, 95), 20)
                        contentDescription = "Status da conexão"
                        setOnClickListener {
                            page = 3
                            render()
                        }
                    }
                    val dot = View(this).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(NeonTheme.green)
                        }
                    }
                    dotContainer.addView(dot, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
                    bar.addView(dotContainer, LinearLayout.LayoutParams(dp(36), dp(36)))
                }
            }
            1 -> {
                // Tela Captura: ← [Logo] Captura \n Editor de Cena  [ ]  ⋮
                val backBtn = ImageView(this).apply {
                    setImageDrawable(NeonIcon("back", Color.WHITE))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = "Voltar ao Controlador"
                    setOnClickListener {
                        page = 0
                        render()
                    }
                }
                bar.addView(backBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    rightMargin = dp(6)
                })

                val titleBlock = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nLogo = View(this).apply {
                    background = object : android.graphics.drawable.Drawable() {
                        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        override fun draw(canvas: android.graphics.Canvas) {
                            val w = bounds.width().toFloat()
                            val h = bounds.height().toFloat()
                            drawStylizedN(canvas, 0f, 0f, minOf(w, h), p)
                        }
                        override fun setAlpha(alpha: Int) {}
                        override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
                        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                    }
                }
                titleBlock.addView(nLogo, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    rightMargin = dp(8)
                })
                val titleTexts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("Captura", 16f, Color.WHITE).apply {
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    })
                    addView(text("Editor de Cena", 11.5f, Color.rgb(180, 175, 210)))
                }
                titleBlock.addView(titleTexts)
                bar.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                // Botão Alternar Modelo Vertical / Horizontal na barra superior do Editor de Cena
                val isVertTop = isVerticalCaptureMode()
                val fullBtn = ImageView(this).apply {
                    setImageDrawable(NeonIcon(if (isVertTop) "mode_vertical" else "mode_horizontal", NeonTheme.cyan))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = shape(Color.rgb(14, 18, 42), NeonTheme.cyan, 16)
                    contentDescription = "Alternar modelo de captura Vertical / Horizontal"
                    setOnClickListener {
                        toggleCaptureOrientationMode()
                    }
                }
                bar.addView(fullBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    rightMargin = dp(8)
                })

                // Botão 3 pontos ⋮
                val dotsBtn = ImageView(this).apply {
                    setImageDrawable(NeonIcon("dots", Color.WHITE))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = shape(Color.rgb(14, 18, 42), Color.rgb(55, 65, 110), 16)
                    contentDescription = "Mais opções"
                    setOnClickListener {
                        openCaptureMoreDialog()
                    }
                }
                bar.addView(dotsBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
            }
            2 -> {
                // Tela Transmissão: ← [Logo] Transmissão \n Plataformas
                val backBtn = ImageView(this).apply {
                    setImageDrawable(NeonIcon("back", Color.WHITE))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = "Voltar ao Controlador"
                    setOnClickListener {
                        page = 0
                        render()
                    }
                }
                bar.addView(backBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    rightMargin = dp(6)
                })

                val titleBlock = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nLogo = View(this).apply {
                    background = object : android.graphics.drawable.Drawable() {
                        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        override fun draw(canvas: android.graphics.Canvas) {
                            val w = bounds.width().toFloat()
                            val h = bounds.height().toFloat()
                            drawStylizedN(canvas, 0f, 0f, minOf(w, h), p)
                        }
                        override fun setAlpha(alpha: Int) {}
                        override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
                        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                    }
                }
                titleBlock.addView(nLogo, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    rightMargin = dp(8)
                })
                val titleTexts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("Transmissão", 16f, Color.WHITE).apply {
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    })
                    addView(text("Plataformas", 11.5f, Color.rgb(180, 175, 210)))
                }
                titleBlock.addView(titleTexts)
                bar.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            else -> {
                // Conexão ou Configuração
                val backBtn = ImageView(this).apply {
                    setImageDrawable(NeonIcon("back", Color.WHITE))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    contentDescription = "Voltar ao Controlador"
                    setOnClickListener {
                        page = 0
                        render()
                    }
                }
                bar.addView(backBtn, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    rightMargin = dp(6)
                })

                val title = if (page == 3) "Conexão" else "Configuração"
                bar.addView(text(title, 18f, Color.WHITE).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        return bar
    }

    // ==========================================
    // TELA 1: CAPTURA (PREVIEW + CENAS, FONTES, VÍDEO, ÁUDIO)
    // ==========================================

    private fun buildCapturaScreen(isLandscape: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val isVerticalCapture = isVerticalCaptureMode()

        // 1. Preview Card contendo o CaptureSceneCanvas real (Vertical 9:16 ou Horizontal 16:9)
        val previewCard = FrameLayout(this).apply {
            background = NeonTheme.previewCardBackground()
            contentDescription = "Prévia da Captura"
        }

        val canvas = CaptureSceneCanvas(this).apply {
            contentDescription = "Prévia do cenário: mover com um dedo; redimensionar com dois."
            isVertical = isVerticalCapture
            layers = activeCaptureScene()?.sources ?: emptyList()
            selectedLayerId = activeCaptureLayerId
            frame = if (ScreenCaptureService.isCapturing) ScreenCaptureService.previewFrame else null
            onSelection = { id ->
                activeCaptureLayerId = id
                refreshCaptureCanvas()
            }
            onChange = {
                persistCaptureScenes()
                refreshCaptureCanvas()
            }
        }
        captureCanvas = canvas
        ensureScreenCaptureStartedForActiveScene(forcePrompt = false)

        val host = FrameLayout(this).apply {
            addView(canvas, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        captureCanvasHost = host
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCaptureBrowserSource() }
        previewCard.addView(host, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Badge "● Preview" no canto superior esquerdo
        val previewBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.argb(210, 7, 28, 32), Color.rgb(0, 180, 100), 12)
            setPadding(dp(8), dp(4), dp(10), dp(4))
        }
        val greenDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(NeonTheme.green)
            }
        }
        previewBadge.addView(greenDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            rightMargin = dp(6)
        })
        val previewLabel = text(if (ScreenCaptureService.isCapturing) "Ao Vivo" else "Preview", 11f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        previewBadge.addView(previewLabel)
        previewCard.addView(previewBadge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            setMargins(dp(12), dp(12), 0, 0)
        })

        // Botão no Editor de Cena (canto superior direito) para alternar o modelo de captura Vertical (9:16) / Horizontal (16:9)
        val orientationBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.argb(225, 18, 12, 48), NeonTheme.cyan, 12)
            setPadding(dp(9), dp(5), dp(10), dp(5))
            isClickable = true
            isFocusable = true
            contentDescription = "Alternar modelo de captura entre Vertical e Horizontal"
            setOnClickListener {
                toggleCaptureOrientationMode()
            }
        }
        val orientationIcon = ImageView(this).apply {
            setImageDrawable(NeonIcon(if (isVerticalCapture) "mode_vertical" else "mode_horizontal", NeonTheme.cyan))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        orientationBtn.addView(orientationIcon, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            rightMargin = dp(6)
        })
        val orientationLabel = text(
            if (isVerticalCapture) "Vertical 9:16" else "Horizontal 16:9",
            11f,
            Color.WHITE
        ).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        orientationBtn.addView(orientationLabel)
        previewCard.addView(orientationBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(10), dp(10), 0)
        })

        // 2. Os quatro botões: Cenas, Fontes, Vídeo, Áudio em grade 2x2
        val buttonsGrid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        val tileHeight = dp(if (isLandscape) 70 else 88)

        fun addCaptureMenuTile(title: String, iconName: String, cardKind: String, accent: Int, col: Int, row: Int, onClick: () -> Unit) {
            val btn = TextView(this).apply {
                text = title
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = NeonTheme.tileCard(cardKind)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                val iconSize = dp(28)
                val icon = NeonIcon(iconName, accent).apply {
                    setBounds(0, 0, iconSize, iconSize)
                }
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(6)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            buttonsGrid.addView(btn, GridLayout.LayoutParams().apply {
                width = 0
                height = tileHeight
                columnSpec = GridLayout.spec(col, 1f)
                rowSpec = GridLayout.spec(row)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }

        addCaptureMenuTile("Cenas", "▱", "cenario", NeonTheme.cyan, 0, 0) { openCenasDialog() }
        addCaptureMenuTile("Fontes", "🖼", "overlay", NeonTheme.blue, 1, 0) { openFontesDialog() }
        addCaptureMenuTile("Vídeo", "video", "camera", NeonTheme.purple, 0, 1) { openVideoDialog() }
        addCaptureMenuTile("Áudio", "audio", "camera", NeonTheme.purple, 1, 1) { openAudioDialog() }

        if (isLandscape) {
            val previewHeight = dp(210)
            container.addView(previewCard, LinearLayout.LayoutParams(0, previewHeight, 1.15f).apply {
                rightMargin = dp(12)
            })
            container.addView(buttonsGrid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            val previewHeight = dp(if (isVerticalCapture) 240 else 200)
            container.addView(previewCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, previewHeight).apply {
                bottomMargin = dp(14)
            })
            container.addView(buttonsGrid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        return container
    }

    private fun createGamerDialog(title: String, buildContent: (LinearLayout, AlertDialog) -> Unit) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(18, 14, 42), Color.rgb(8, 10, 26))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), NeonTheme.purple)
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(text(title, 16f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val closeBtn = text("✕", 17f, Color.rgb(200, 190, 230)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        header.addView(closeBtn)
        root.addView(header)

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(contentContainer)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()
        closeBtn.setOnClickListener { dialog.dismiss() }

        buildContent(contentContainer, dialog)

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        dialog.window?.setLayout(width, maxHeight)
    }

    private fun openCenasDialog() {
        createGamerDialog("Cenas") { contentContainer, _ ->
            fun populate() {
                contentContainer.removeAllViews()

                val activeScene = activeCaptureScene()
                val headerInfo = text("Cena ativa: ${activeScene?.name ?: "Nenhuma"}", 12f, Color.rgb(180, 175, 215)).apply {
                    setPadding(0, 0, 0, dp(10))
                }
                contentContainer.addView(headerInfo)

                captureScenes.forEach { scene ->
                    val isSelected = scene.id == activeCaptureSceneId
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(8), dp(10))
                        background = if (isSelected) {
                            GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                intArrayOf(Color.rgb(85, 18, 140), Color.rgb(38, 10, 75))
                            ).apply {
                                cornerRadius = dp(12).toFloat()
                                setStroke(dp(1), NeonTheme.cyan)
                            }
                        } else {
                            shape(Color.rgb(14, 18, 44), Color.rgb(45, 52, 90), 12)
                        }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            activeCaptureSceneId = scene.id
                            activeCaptureLayerId = scene.sources.lastOrNull()?.id
                            persistCaptureScenes()
                            refreshCaptureCanvas()
                            ensureScreenCaptureStartedForActiveScene(forcePrompt = true)
                            populate()
                        }
                    }

                    val indicator = text(if (isSelected) "●" else "○", 16f, if (isSelected) NeonTheme.cyan else Color.rgb(130, 135, 170)).apply {
                        setPadding(0, 0, dp(10), 0)
                    }
                    item.addView(indicator)

                    val texts = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(scene.name, 14f, Color.WHITE).apply {
                            typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        })
                        addView(text("${scene.sources.size} fontes", 11f, Color.rgb(165, 160, 200)))
                    }
                    item.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                    // Botão Renomear
                    val renameBtn = text("✎", 16f, NeonTheme.cyan).apply {
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        contentDescription = "Renomear ${scene.name}"
                        setOnClickListener {
                            val input = EditText(this@MainActivity).apply {
                                setSingleLine(true)
                                setText(scene.name)
                                selectAll()
                                setTextColor(Color.WHITE)
                                setHintTextColor(Color.GRAY)
                                background = shape(Color.rgb(14, 18, 44), NeonTheme.purple, 10)
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Renomear cena")
                                .setView(input)
                                .setPositiveButton("Salvar") { _, _ ->
                                    val newName = input.text.toString().trim()
                                    if (newName.isNotBlank()) {
                                        scene.name = newName.take(50)
                                        persistCaptureScenes()
                                        refreshCaptureCanvas()
                                        populate()
                                    }
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }
                    item.addView(renameBtn)

                    // Botão Excluir
                    val deleteBtn = text("✕", 16f, Color.rgb(250, 70, 95)).apply {
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        contentDescription = "Excluir ${scene.name}"
                        setOnClickListener {
                            if (captureScenes.size <= 1) {
                                Toast.makeText(this@MainActivity, "Mantenha ao menos uma cena.", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Remover cena?")
                                .setMessage("Tem certeza que deseja remover '${scene.name}'?")
                                .setPositiveButton("Remover") { _, _ ->
                                    captureScenes.removeAll { it.id == scene.id }
                                    if (activeCaptureSceneId == scene.id) {
                                        activeCaptureSceneId = captureScenes.first().id
                                        activeCaptureLayerId = activeCaptureScene()?.sources?.lastOrNull()?.id
                                    }
                                    persistCaptureScenes()
                                    refreshCaptureCanvas()
                                    populate()
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }
                    item.addView(deleteBtn)

                    contentContainer.addView(item, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8)
                    })
                }

                // Botão + Nova Cena
                val addBtn = TextView(this).apply {
                    text = "+ Nova Cena"
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = NeonTheme.tileCard("add_primary")
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val input = EditText(this@MainActivity).apply {
                            setSingleLine(true)
                            setText("Nova cena")
                            selectAll()
                            setTextColor(Color.WHITE)
                            setHintTextColor(Color.GRAY)
                            background = shape(Color.rgb(14, 18, 44), NeonTheme.purple, 10)
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                        }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Criar cena")
                            .setView(input)
                            .setPositiveButton("Salvar") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotBlank()) {
                                    val created = Scene(java.util.UUID.randomUUID().toString(), name.take(50), mutableListOf())
                                    captureScenes.add(created)
                                    activeCaptureSceneId = created.id
                                    activeCaptureLayerId = null
                                    persistCaptureScenes()
                                    refreshCaptureCanvas()
                                    populate()
                                }
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
                contentContainer.addView(addBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                ).apply {
                    topMargin = dp(6)
                })
            }
            populate()
        }
    }

    private fun openFontesDialog() {
        createGamerDialog("Fontes / Camadas") { contentContainer, _ ->
            fun populate() {
                contentContainer.removeAllViews()
                val scene = activeCaptureScene() ?: return

                val subHeader = text("Cena: ${scene.name}", 12f, Color.rgb(180, 175, 215)).apply {
                    setPadding(0, 0, 0, dp(10))
                }
                contentContainer.addView(subHeader)

                // Botão + Adicionar Fonte
                val addSourceBtn = TextView(this).apply {
                    text = "+ Adicionar Fonte"
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = NeonTheme.tileCard("add_primary")
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val types = arrayOf("Captura de tela", "Texto", "Browser Source")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Adicionar fonte")
                            .setItems(types) { _, which ->
                                when (which) {
                                    0 -> {
                                        val existingScreen = scene.sources.firstOrNull { it.type == "screen" }
                                        if (existingScreen != null) {
                                            existingScreen.visible = true
                                            activeCaptureLayerId = existingScreen.id
                                            persistCaptureScenes()
                                            refreshCaptureCanvas()
                                            populate()
                                            ensureScreenCaptureStartedForActiveScene(forcePrompt = true)
                                        } else {
                                            val layer = CaptureLayer(java.util.UUID.randomUUID().toString(), "screen", "Captura de tela", 0f, 0f, 1f, 1f)
                                            scene.sources.add(layer)
                                            activeCaptureLayerId = layer.id
                                            persistCaptureScenes()
                                            refreshCaptureCanvas()
                                            populate()
                                            ensureScreenCaptureStartedForActiveScene(forcePrompt = true)
                                        }
                                    }
                                    1 -> {
                                        val input = EditText(this@MainActivity).apply {
                                            setSingleLine(true)
                                            setText("Texto")
                                            selectAll()
                                            setTextColor(Color.WHITE)
                                            setHintTextColor(Color.GRAY)
                                            background = shape(Color.rgb(14, 18, 44), NeonTheme.purple, 10)
                                            setPadding(dp(12), dp(10), dp(12), dp(10))
                                        }
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle("Conteúdo do texto")
                                            .setView(input)
                                            .setPositiveButton("Adicionar") { _, _ ->
                                                val content = input.text.toString().trim()
                                                if (content.isNotEmpty()) {
                                                    val layer = CaptureLayer(java.util.UUID.randomUUID().toString(), "text", content.take(80), 0.15f, 0.15f, 0.6f, 0.25f)
                                                    scene.sources.add(layer)
                                                    activeCaptureLayerId = layer.id
                                                    persistCaptureScenes()
                                                    refreshCaptureCanvas()
                                                    populate()
                                                }
                                            }
                                            .setNegativeButton("Cancelar", null)
                                            .show()
                                        input.requestFocus()
                                    }
                                    2 -> {
                                        val input = EditText(this@MainActivity).apply {
                                            hint = "https://.../cenarios/receiver/..."
                                            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                                            setSingleLine(true)
                                            setTextColor(Color.WHITE)
                                            setHintTextColor(Color.GRAY)
                                            background = shape(Color.rgb(14, 18, 44), NeonTheme.purple, 10)
                                            setPadding(dp(12), dp(10), dp(12), dp(10))
                                        }
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle("Browser Source - URL")
                                            .setView(input)
                                            .setPositiveButton("Adicionar") { _, _ ->
                                                val url = input.text.toString().trim()
                                                val uri = Uri.parse(url)
                                                if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
                                                    Toast.makeText(this@MainActivity, "Informe uma URL HTTPS válida.", Toast.LENGTH_LONG).show()
                                                } else {
                                                    val layer = CaptureLayer(
                                                        java.util.UUID.randomUUID().toString(), "browser", "Browser Source",
                                                        0f, 0f, 1f, 1f, url = url
                                                    )
                                                    scene.sources.add(layer)
                                                    activeCaptureLayerId = layer.id
                                                    persistCaptureScenes()
                                                    refreshCaptureCanvas()
                                                    populate()
                                                }
                                            }
                                            .setNegativeButton("Cancelar", null)
                                            .show()
                                        input.requestFocus()
                                    }
                                }
                            }
                            .show()
                    }
                }
                contentContainer.addView(addSourceBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                ).apply {
                    bottomMargin = dp(12)
                })

                val sources = scene.sources
                if (sources.isEmpty()) {
                    val emptyLabel = text("Nenhuma fonte adicionada à cena.", 12f, Color.rgb(160, 155, 190)).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(16), 0, dp(16))
                    }
                    contentContainer.addView(emptyLabel)
                } else {
                    sources.asReversed().forEach { layer ->
                        val isSelected = layer.id == activeCaptureLayerId
                        val item = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            background = if (isSelected) {
                                shape(Color.rgb(38, 14, 68), NeonTheme.cyan, 10)
                            } else {
                                shape(Color.rgb(14, 18, 42), Color.rgb(45, 52, 90), 10)
                            }
                        }

                        val icon = when (layer.type) {
                            "screen" -> "▣"
                            "text" -> "T"
                            "browser" -> "▤"
                            else -> "▤"
                        }
                        val nameLabel = text("$icon  ${layer.name}", 13f, Color.WHITE).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER_VERTICAL
                            setOnClickListener {
                                activeCaptureLayerId = layer.id
                                refreshCaptureCanvas()
                                populate()
                                if (layer.type == "screen" && layer.visible) {
                                    ensureScreenCaptureStartedForActiveScene(forcePrompt = true)
                                }
                            }
                        }
                        item.addView(nameLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                        // Visibilidade
                        val visBtn = text(if (layer.visible) "◉" else "⊘", 16f, if (layer.visible) NeonTheme.purple else Color.GRAY).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(6), dp(4), dp(6), dp(4))
                            setOnClickListener {
                                layer.visible = !layer.visible
                                persistCaptureScenes()
                                refreshCaptureCanvas()
                                populate()
                                if (layer.type == "screen" && layer.visible) {
                                    ensureScreenCaptureStartedForActiveScene(forcePrompt = true)
                                }
                            }
                        }
                        item.addView(visBtn)

                        // Bloqueio
                        val lockBtn = text(if (layer.locked) "▣" else "♧", 16f, if (layer.locked) NeonTheme.cyan else Color.rgb(180, 175, 215)).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(6), dp(4), dp(6), dp(4))
                            setOnClickListener {
                                layer.locked = !layer.locked
                                persistCaptureScenes()
                                refreshCaptureCanvas()
                                populate()
                            }
                        }
                        item.addView(lockBtn)

                        // Subir (↑)
                        val upBtn = text("↑", 16f, Color.WHITE).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(6), dp(4), dp(6), dp(4))
                            setOnClickListener {
                                val idx = sources.indexOfFirst { it.id == layer.id }
                                if (idx in 0 until sources.size - 1) {
                                    val itemToMove = sources.removeAt(idx)
                                    sources.add(idx + 1, itemToMove)
                                    persistCaptureScenes()
                                    refreshCaptureCanvas()
                                    populate()
                                }
                            }
                        }
                        item.addView(upBtn)

                        // Descer (↓)
                        val downBtn = text("↓", 16f, Color.WHITE).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(6), dp(4), dp(6), dp(4))
                            setOnClickListener {
                                val idx = sources.indexOfFirst { it.id == layer.id }
                                if (idx > 0) {
                                    val itemToMove = sources.removeAt(idx)
                                    sources.add(idx - 1, itemToMove)
                                    persistCaptureScenes()
                                    refreshCaptureCanvas()
                                    populate()
                                }
                            }
                        }
                        item.addView(downBtn)

                        // Excluir (✕)
                        val delBtn = text("✕", 16f, Color.rgb(250, 70, 95)).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(6), dp(4), dp(6), dp(4))
                            setOnClickListener {
                                val removedType = layer.type
                                sources.remove(layer)
                                if (activeCaptureLayerId == layer.id) {
                                    activeCaptureLayerId = sources.lastOrNull()?.id
                                }
                                persistCaptureScenes()
                                if (removedType == "screen" && captureScenes.none { s -> s.sources.any { it.type == "screen" } }) {
                                    stopScreenCaptureForSource()
                                } else {
                                    refreshCaptureCanvas()
                                }
                                populate()
                            }
                        }
                        item.addView(delBtn)

                        contentContainer.addView(item, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                        ).apply {
                            bottomMargin = dp(6)
                        })
                    }
                }
            }
            populate()
        }
    }

    private fun openVideoDialog() {
        createGamerDialog("Configurações de Vídeo") { contentContainer, _ ->
            fun populate() {
                contentContainer.removeAllViews()

                // 1. Resolução
                val sec1 = text("RESOLUÇÃO DA CAPTURA", 12f, NeonTheme.cyan).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setPadding(0, 0, 0, dp(8))
                }
                contentContainer.addView(sec1)

                val resolutions = listOf(
                    Pair(854, "480p (econômico)"),
                    Pair(1280, "720p (padrão)"),
                    Pair(1920, "1080p (alta resolução)")
                )
                val currentRes = capturePrefs.getInt("max_dimension", 1280)

                resolutions.forEach { (size, label) ->
                    val isSelected = currentRes == size
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = if (isSelected) {
                            shape(Color.rgb(38, 14, 68), NeonTheme.cyan, 10)
                        } else {
                            shape(Color.rgb(14, 18, 42), Color.rgb(45, 52, 90), 10)
                        }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            capturePrefs.edit().putInt("max_dimension", size).apply()
                            populate()
                        }
                    }
                    item.addView(text(if (isSelected) "● " else "○ ", 15f, if (isSelected) NeonTheme.cyan else Color.GRAY))
                    item.addView(text(label, 13f, Color.WHITE).apply {
                        typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    })
                    contentContainer.addView(item, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(6)
                    })
                }

                // 2. Taxa de Quadros (FPS)
                val sec2 = text("TAXA DE QUADROS (FPS)", 12f, NeonTheme.purple).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setPadding(0, dp(12), 0, dp(8))
                }
                contentContainer.addView(sec2)

                val currentFps = capturePrefs.getInt("capture_fps", 30)
                val fpsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                listOf(30, 60).forEachIndexed { index, fps ->
                    val isSelected = currentFps == fps
                    val fpsBtn = TextView(this).apply {
                        text = "$fps FPS"
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        typeface = Typeface.create("sans-serif-medium", if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        background = if (isSelected) {
                            shape(Color.rgb(38, 14, 68), NeonTheme.purple, 10)
                        } else {
                            shape(Color.rgb(14, 18, 42), Color.rgb(45, 52, 90), 10)
                        }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            if (fps == 60 && currentFps != 60) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Confirmar 60 FPS")
                                    .setMessage("Essa qualidade consome muita bateria e requer muito desempenho. Tem certeza que quer essa qualidade mesmo?")
                                    .setPositiveButton("Sim") { _, _ ->
                                        capturePrefs.edit().putInt("capture_fps", 60).apply()
                                        populate()
                                    }
                                    .setNegativeButton("Cancelar", null)
                                    .show()
                            } else {
                                capturePrefs.edit().putInt("capture_fps", fps).apply()
                                populate()
                            }
                        }
                    }
                    fpsRow.addView(fpsBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (index > 0) leftMargin = dp(8)
                    })
                }
                contentContainer.addView(fpsRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                // 3. Taxa de Bits (Bitrate)
                val sec3 = text("TAXA DE BITS (BITRATE)", 12f, NeonTheme.cyan).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setPadding(0, dp(12), 0, dp(8))
                }
                contentContainer.addView(sec3)

                val bitrates = (2000..10000 step 1000).toList()
                val currentBitrate = capturePrefs.getInt("capture_bitrate_kbps", 4500)
                val bitrateSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        bitrates.map { "$it Kbps" }
                    )
                    val sel = bitrates.indexOfFirst { it >= currentBitrate }.coerceAtLeast(0)
                    setSelection(sel)
                    background = shape(Color.rgb(14, 18, 42), NeonTheme.purple, 10)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    var skipFirst = true
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            if (skipFirst) { skipFirst = false; return }
                            capturePrefs.edit().putInt("capture_bitrate_kbps", bitrates[position]).apply()
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }
                contentContainer.addView(bitrateSpinner, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                ))
            }
            populate()
        }
    }

    private fun openAudioDialog() {
        createGamerDialog("Áudio da Captura") { contentContainer, _ ->
            fun populate() {
                contentContainer.removeAllViews()

                fun audioChannel(name: String, key: String, symbol: String) {
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = shape(Color.rgb(14, 18, 44), Color.rgb(45, 52, 90), 12)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    val topRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    topRow.addView(text("$symbol  $name", 13.5f, Color.WHITE).apply {
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                    val isEnabled = capturePrefs.getBoolean("${key}_enabled", true)
                    val switch = Switch(this).apply {
                        isChecked = isEnabled
                        setOnCheckedChangeListener { _, checked ->
                            capturePrefs.edit().putBoolean("${key}_enabled", checked).apply()
                        }
                    }
                    topRow.addView(switch)
                    card.addView(topRow)

                    val sliderRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, 0)
                    }
                    val savedVol = capturePrefs.getInt("${key}_volume", if (key == "mic") 75 else 85)
                    val volLabel = text("$savedVol%", 12.5f, NeonTheme.cyan).apply {
                        gravity = Gravity.CENTER
                    }
                    val slider = SeekBar(this).apply {
                        max = 100
                        progress = savedVol
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                volLabel.text = "$progress%"
                                if (fromUser) {
                                    capturePrefs.edit().putInt("${key}_volume", progress).apply()
                                }
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                        })
                    }
                    sliderRow.addView(slider, LinearLayout.LayoutParams(0, dp(34), 1f))
                    sliderRow.addView(volLabel, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))

                    card.addView(sliderRow)
                    contentContainer.addView(card, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(12)
                    })
                }

                audioChannel("Microfone", "mic", "♩")
                audioChannel("Áudio interno", "internal", "♫")

                val nsCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = shape(Color.rgb(14, 18, 44), NeonTheme.cyan, 12)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                val nsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nsTitles = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                nsTitles.addView(text("🔇  Supressão de Ruídos", 13.5f, Color.WHITE).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                nsTitles.addView(text("Remove chiados, eco e ruídos de fundo do microfone", 11f, Color.rgb(170, 185, 220)).apply {
                    setPadding(0, dp(2), 0, 0)
                })
                nsRow.addView(nsTitles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val nsEnabled = capturePrefs.getBoolean("noise_suppression_enabled", true)
                val nsSwitch = Switch(this).apply {
                    isChecked = nsEnabled
                    setOnCheckedChangeListener { _, checked ->
                        capturePrefs.edit().putBoolean("noise_suppression_enabled", checked).apply()
                    }
                }
                nsRow.addView(nsSwitch)
                nsCard.addView(nsRow)
                contentContainer.addView(nsCard, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            populate()
        }
    }

    private fun openCaptureMoreDialog() {
        val currentMode = if (isVerticalCaptureMode()) "Vertical (9:16)" else "Horizontal (16:9)"
        val nextMode = if (isVerticalCaptureMode()) "Horizontal (16:9)" else "Vertical (9:16)"
        val options = arrayOf(
            "Mudar modelo para $nextMode (Atual: $currentMode)",
            "Recarregar prévia",
            "Sobre o editor"
        )
        AlertDialog.Builder(this)
            .setTitle("Opções de Captura")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleCaptureOrientationMode()
                    1 -> {
                        refreshCaptureCanvas()
                        Toast.makeText(this, "Prévia atualizada.", Toast.LENGTH_SHORT).show()
                    }
                    2 -> Toast.makeText(this, "Prévia interativa: arraste para mover ou redimensionar camadas.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ==========================================
    // TELA 2: TRANSMISSÃO (PLATAFORMAS + CONTROLES)
    // ==========================================

    private fun buildTransmissaoScreen(isLandscape: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. Container Selecionar Plataformas
        val platformsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NeonTheme.actionPanelBackground()
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(text("Selecionar Plataformas", 14f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val refreshConnBtn = text("🔄 Atualizar", 11f, NeonTheme.cyan).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = shape(Color.rgb(12, 24, 52), NeonTheme.cyan, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Verificando chaves RTMP salvas...", Toast.LENGTH_SHORT).show()
                checkStreamingPlatformsStatus {
                    Toast.makeText(this@MainActivity, "Chaves RTMP verificadas!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        headerRow.addView(refreshConnBtn)
        platformsCard.addView(headerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        val platformsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makePlatformTile(slug: String, name: String, iconSymbol: String): View {
            val prefKey = "stream_$slug"
            val username = capturePrefs.getString("${slug}_username", "")?.trim() ?: ""
            val cleanUsername = if (username.equals("null", ignoreCase = true) || username.equals("undefined", ignoreCase = true)) "" else username
            val hasCustomKey = isValidStreamKey(capturePrefs.getString("key_$slug", ""))
            val isConn = hasCustomKey
            val selected = capturePrefs.getBoolean(prefKey, false) && isConn

            val tile = FrameLayout(this).apply {
                background = NeonTheme.platformCardBackground(selected)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!isConn) {
                        openPlatformAuthDialog(slug)
                    } else {
                        capturePrefs.edit().putBoolean(prefKey, !selected).apply()
                        render()
                    }
                }
                setOnLongClickListener {
                    val options = arrayOf(
                        if (isConn) "Alternar Seleção (${if (selected) "Selecionado" else "Desmarcado"})" else "Inserir Chave de Transmissão",
                        "Configurar Hash e Chave de Transmissão",
                        "Remover Chave e Desconectar"
                    )
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Conta - $name")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    if (!isConn) openPlatformAuthDialog(slug)
                                    else {
                                        capturePrefs.edit().putBoolean(prefKey, !selected).apply()
                                        render()
                                    }
                                }
                                1 -> openPlatformAuthDialog(slug)
                                2 -> {
                                    capturePrefs.edit()
                                        .remove("key_$slug")
                                        .remove("url_$slug")
                                        .remove("${slug}_username")
                                        .remove("${slug}_token")
                                        .remove("${slug}_login")
                                        .remove("${slug}_user_id")
                                        .putBoolean(prefKey, false)
                                        .apply()
                                    platformConnected[slug] = false
                                    Toast.makeText(this@MainActivity, "Conta $name desconectada.", Toast.LENGTH_SHORT).show()
                                    render()
                                }
                            }
                        }
                        .setNegativeButton("Fechar", null)
                        .show()
                    true
                }
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val iconView = ImageView(this).apply {
                setImageDrawable(NeonIcon(iconSymbol, Color.WHITE))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            content.addView(iconView, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                bottomMargin = dp(4)
            })
            content.addView(text(name, 12f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })

            val displayHandle = if (cleanUsername.startsWith("@")) cleanUsername else "@$cleanUsername"
            val statusLabel = if (isConn) {
                if (cleanUsername.isNotBlank()) "● $displayHandle" else "● RTMP Pronto"
            } else {
                "○ Inserir Chave"
            }
            val statusColor = if (isConn) NeonTheme.green else Color.rgb(180, 185, 215)
            val statusText = text(statusLabel, 10f, statusColor).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            content.addView(statusText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })

            tile.addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // Checkbox no canto superior direito
            val chk = ImageView(this).apply {
                setImageDrawable(NeonIcon(
                    if (selected) "checkbox_on" else "checkbox_off",
                    if (selected) NeonTheme.purple else (if (isConn) Color.rgb(130, 140, 180) else Color.rgb(80, 90, 120))
                ))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(chk, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            })

            return tile
        }

        val tileH = dp(if (isLandscape) 82 else 92)
        platformsRow.addView(makePlatformTile("youtube", "YouTube", "youtube"),
            LinearLayout.LayoutParams(0, tileH, 1f).apply { rightMargin = dp(6) })
        platformsRow.addView(makePlatformTile("twitch", "Twitch", "twitch"),
            LinearLayout.LayoutParams(0, tileH, 1f).apply { rightMargin = dp(6) })
        platformsRow.addView(makePlatformTile("kick", "Kick", "kick"),
            LinearLayout.LayoutParams(0, tileH, 1f))
        platformsCard.addView(platformsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Barra inferior de opções rápidas de chave RTMP
        val rtmpActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val rtmpBtn = text("🔑 Inserir Chaves RTMP", 11f, Color.rgb(200, 205, 235)).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = shape(Color.rgb(16, 12, 36), NeonTheme.purple, 10)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val platforms = arrayOf("YouTube (RTMP)", "Twitch (RTMP)", "Kick (RTMP)")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Configurar Chave de Transmissão")
                    .setItems(platforms) { _, which ->
                        when (which) {
                            0 -> openStreamKeyDialog("youtube", "YouTube")
                            1 -> openStreamKeyDialog("twitch", "Twitch")
                            2 -> openStreamKeyDialog("kick", "Kick")
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        rtmpActionsRow.addView(rtmpBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })
        val realModeBadge = text("● Modo Real Ativo", 11f, NeonTheme.green).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        rtmpActionsRow.addView(realModeBadge, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        platformsCard.addView(rtmpActionsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 2. Container Transmissão (Status e Ações)
        val controlsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (isTransmitting) {
            // Painel Ao Vivo com Telemetria e Cronômetro
            val livePanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = NeonTheme.actionPanelBackground()
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }

            val liveHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val liveBadge = text("● AO VIVO", 11f, Color.WHITE).apply {
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = shape(Color.rgb(220, 20, 60), Color.rgb(255, 64, 92), 10)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            liveHeader.addView(liveBadge)
            val elapsed = if (transmissionStartTime > 0) formatElapsedTime(System.currentTimeMillis() - transmissionStartTime) else "00:00"
            val timerText = text(elapsed, 20f, Color.WHITE).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            liveHeader.addView(timerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            livePanel.addView(liveHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })

            // Plataformas ativas
            val activePlatforms = mutableListOf<String>()
            if (capturePrefs.getBoolean("stream_youtube", false)) activePlatforms.add("YouTube")
            if (capturePrefs.getBoolean("stream_twitch", false)) activePlatforms.add("Twitch")
            if (capturePrefs.getBoolean("stream_kick", false)) activePlatforms.add("Kick")

            val activePillsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            activePillsRow.addView(text("Transmitindo para:", 11.5f, Color.rgb(180, 185, 215)).apply {
                setPadding(0, 0, dp(6), 0)
            })
            activePlatforms.forEach { p ->
                val rawUser = capturePrefs.getString("${p.lowercase()}_username", "")?.trim() ?: ""
                val username = if (rawUser.equals("null", ignoreCase = true) || rawUser.equals("undefined", ignoreCase = true)) "" else rawUser
                val displayHandle = if (username.startsWith("@")) username else "@$username"
                val label = if (username.isNotBlank()) "$p ($displayHandle)" else p
                val pill = text(label, 10.5f, NeonTheme.cyan).apply {
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = shape(Color.rgb(8, 24, 48), NeonTheme.cyan, 8)
                }
                activePillsRow.addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(4)
                })
            }
            livePanel.addView(activePillsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })

            // Telemetria técnica da transmissão de tela
            val resDim = capturePrefs.getInt("max_dimension", 1280)
            val fpsVal = capturePrefs.getInt("capture_fps", 30)
            val bitrateVal = capturePrefs.getInt("capture_bitrate_kbps", 4500)
            val telemetryText = text("Captura: ${resDim}p • $fpsVal FPS • ${bitrateVal} kbps • H.264 / AVC", 10.5f, Color.rgb(140, 220, 180))
            livePanel.addView(telemetryText)

            controlsColumn.addView(livePanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })

            // Botão Encerrar Transmissão destacado
            val stopBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = NeonTheme.stopTransmitButtonBackground()
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    stopLiveTransmission()
                }
            }
            val stopSquare = ImageView(this).apply {
                setImageDrawable(NeonIcon("checkbox_on", Color.rgb(255, 64, 92)))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            stopBtn.addView(stopSquare, LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                rightMargin = dp(10)
            })
            stopBtn.addView(text("Encerrar Transmissão", 14f, Color.rgb(255, 200, 210)).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            controlsColumn.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
        } else {
            val infoCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = NeonTheme.actionPanelBackground()
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val broadcastCircle = ImageView(this).apply {
                setImageDrawable(NeonIcon("broadcast", NeonTheme.purple))
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = shape(Color.rgb(36, 12, 60), NeonTheme.purple, 24)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            infoCard.addView(broadcastCircle, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(12)
            })
            val infoText = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Transmissão", 14f, Color.WHITE).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
                addView(text("Inicie a transmissão nas plataformas selecionadas.", 11.5f, Color.rgb(185, 175, 215)))
            }
            infoCard.addView(infoText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            controlsColumn.addView(infoCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })

            // Botão "((•)) Iniciar Transmissão >"
            val startBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = NeonTheme.transmitButtonBackground()
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    handleStartTransmissionClick()
                }
            }
            val transmitIcon = ImageView(this).apply {
                setImageDrawable(NeonIcon("broadcast", Color.WHITE))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            startBtn.addView(transmitIcon, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                rightMargin = dp(10)
            })
            startBtn.addView(text("Iniciar Transmissão", 14f, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val arrowRight = ImageView(this).apply {
                setImageDrawable(NeonIcon("arrow_right", Color.WHITE))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            startBtn.addView(arrowRight, LinearLayout.LayoutParams(dp(18), dp(18)))
            controlsColumn.addView(startBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(8)
            })

            // Botão "[ ] Encerrar Transmissão"
            val stopBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = NeonTheme.stopTransmitButtonBackground()
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Toast.makeText(this@MainActivity, "Nenhuma transmissão ativa.", Toast.LENGTH_SHORT).show()
                }
            }
            val stopSquare = ImageView(this).apply {
                setImageDrawable(NeonIcon("checkbox_off", Color.rgb(180, 185, 215)))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            stopBtn.addView(stopSquare, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                rightMargin = dp(10)
            })
            stopBtn.addView(text("Encerrar Transmissão", 13.5f, Color.rgb(210, 215, 235)).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            controlsColumn.addView(stopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        }

        if (isLandscape) {
            container.addView(platformsCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                rightMargin = dp(12)
            })
            container.addView(controlsColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            container.addView(platformsCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
            container.addView(controlsColumn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        return container
    }

    // ==========================================
    // NAVEGAÇÃO E LAYOUT PRINCIPAL (VERTICAL / HORIZONTAL)
    // ==========================================

    private fun render() {
        if (!authenticated || isDestroyed) return
        ControllerRuntime.signedIn = true
        closeLoginBrowsers()
        panel?.dispose()
        panel = null
        syncActivityOrientationWithCaptureMode()

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = NeonTheme.backdrop()
            val hPad = dp(if (isLandscape) 10 else 10)
            val vPad = dp(if (isLandscape) 8 else 8)
            setPadding(hPad, vPad, hPad, vPad)
        }

        data class NavTab(val id: Int, val title: String, val iconSymbol: String)
        val navTabs = listOf(
            NavTab(0, "Controlador", "gamepad"),
            NavTab(1, "Captura", "monitor"),
            NavTab(2, "Transmissão", "broadcast"),
            NavTab(3, "Conexão", "link"),
            NavTab(4, "Configuração", "gear")
        )

        // No modo Paisagem (Horizontal): Left Navigation Rail
        if (isLandscape) {
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
            }

            // Topo do Rail: Logo LiveNexus Controller
            val railLogo = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(10))
            }
            val logoIcon = View(this).apply {
                background = object : android.graphics.drawable.Drawable() {
                    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    override fun draw(canvas: android.graphics.Canvas) {
                        val w = bounds.width().toFloat()
                        val h = bounds.height().toFloat()
                        drawStylizedN(canvas, 0f, 0f, minOf(w, h), p)
                    }
                    override fun setAlpha(alpha: Int) {}
                    override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
                    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                }
            }
            railLogo.addView(logoIcon, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                rightMargin = dp(6)
            })
            val logoText = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("LiveNexus", 13.5f, Color.WHITE).apply {
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                })
                addView(text("Controller", 10.5f, Color.rgb(180, 175, 210)))
            }
            railLogo.addView(logoText)
            rail.addView(railLogo)

            navTabs.forEach { tab ->
                val active = page == tab.id
                val tabBtn = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    if (active) {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(Color.rgb(104, 24, 168), Color.rgb(45, 12, 92))
                        ).apply {
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), NeonTheme.purple)
                        }
                    } else {
                        background = null
                    }
                    val icon = ImageView(this@MainActivity).apply {
                        setImageDrawable(NeonIcon(tab.iconSymbol, if (active) Color.WHITE else Color.rgb(190, 190, 220)))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        rightMargin = dp(8)
                    })
                    addView(text(tab.title, 12f, if (active) Color.WHITE else Color.rgb(190, 190, 220)).apply {
                        typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                    })
                    setOnClickListener {
                        page = tab.id
                        render()
                    }
                }
                rail.addView(tabBtn, LinearLayout.LayoutParams(dp(136), dp(38)).apply {
                    bottomMargin = dp(4)
                })
            }

            root.addView(rail, LinearLayout.LayoutParams(dp(144), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                rightMargin = dp(8)
            })
        }

        // Área de Conteúdo da Direita (ou Principal no Retrato)
        val contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Top Bar
        contentArea.addView(buildTopBar(isLandscape), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(if (isLandscape) 46 else 56)
        ).apply {
            bottomMargin = dp(if (isLandscape) 4 else 6)
        })

        // Body de acordo com a página selecionada
        val bodyScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val bodyWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        when (page) {
            0 -> {
                // Tela Controlador
                panel = ControllerPanel(this, false)
                bodyWrapper.addView(panel)
            }
            1 -> {
                // Tela Captura
                bodyWrapper.addView(buildCapturaScreen(isLandscape))
            }
            2 -> {
                // Tela Transmissão
                bodyWrapper.addView(buildTransmissaoScreen(isLandscape))
            }
            3 -> {
                // Tela Conexão
                bodyWrapper.addView(text("CONEXÃO", 16f, NeonTheme.cyan).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(8), dp(4), dp(8), dp(6))
                })
                bodyWrapper.addView(
                    text("Servidor: ${LiveNexusApi.baseUrl}\nStatus: Conectado ao LiveNexus.", 14f, Color.rgb(210, 215, 235)).apply {
                        setPadding(dp(8), dp(2), dp(8), dp(12))
                    }
                )
                fun option(label: String, onTap: () -> Unit) {
                    bodyWrapper.addView(text(label, 15f).apply {
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        background = NeonTheme.card(false)
                        setOnClickListener { onTap() }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply {
                        bottomMargin = dp(8)
                    })
                }
                option("Voltar ao painel do Controlador") { page = 0; render() }
                option("Abrir Editor de Captura de Tela") { page = 1; render() }
                option("Abrir Plataformas de Transmissão") { page = 2; render() }
            }
            else -> {
                // Tela Configuração (page == 4)
                bodyWrapper.addView(text("CONFIGURAÇÃO", 16f, NeonTheme.cyan).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(dp(8), dp(4), dp(8), dp(8))
                })
                fun option(label: String, onTap: () -> Unit) {
                    bodyWrapper.addView(text(label, 15f).apply {
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        background = NeonTheme.card(true)
                        setOnClickListener { onTap() }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply {
                        bottomMargin = dp(8)
                    })
                }
                option("Voltar ao painel do Controlador") { page = 0; render() }
                option("Permissão de sobreposição") { permission() }
                option("Desconectar conta") {
                    ControllerRuntime.signedIn = false
                    stopService(Intent(this, OverlayService::class.java))
                    LiveNexusApi.logout()
                    panel?.dispose()
                    showLogin()
                }
            }
        }

        bodyScroll.addView(bodyWrapper)
        contentArea.addView(bodyScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // No modo Retrato (Vertical): Barra Inferior com 5 itens
        if (!isLandscape) {
            val nav = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(Color.rgb(8, 10, 28), Color.rgb(48, 38, 92), 18)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }

            navTabs.forEach { tab ->
                val active = page == tab.id
                val tabView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    if (active) {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(Color.rgb(104, 24, 168), Color.rgb(45, 12, 92))
                        ).apply {
                            cornerRadius = dp(14).toFloat()
                            setStroke(dp(1), NeonTheme.purple)
                        }
                    }
                    val iconView = ImageView(this@MainActivity).apply {
                        setImageDrawable(NeonIcon(tab.iconSymbol, if (active) Color.WHITE else Color.rgb(205, 205, 230)))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    val iconDim = dp(20)
                    addView(iconView, LinearLayout.LayoutParams(iconDim, iconDim).apply {
                        bottomMargin = dp(2)
                    })
                    addView(text(tab.title, 10.5f, Color.WHITE).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        typeface = Typeface.create("sans-serif", if (active) Typeface.BOLD else Typeface.NORMAL)
                    })
                    setOnClickListener {
                        page = tab.id
                        render()
                    }
                }
                nav.addView(tabView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }

            contentArea.addView(nav, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(4) })
        }

        if (isLandscape) {
            root.addView(contentArea, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ))
        } else {
            root.addView(contentArea, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }

        setContentView(root)
        prepareOverlay()
    }
}
