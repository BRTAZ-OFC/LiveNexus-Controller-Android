package com.livenexus.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.os.Message
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.media.projection.MediaProjectionManager

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("buttons", MODE_PRIVATE) }
    @Volatile private var authenticated = false
    private val loginBrowsers = mutableListOf<WebView>()
    private val loginDialogs = mutableListOf<AlertDialog>()

    // A tela de login nunca pode retirar comandos destinados ao navegador da live.
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
    private val cyan = NeonTheme.cyan
    private val background = Color.rgb(10, 15, 25)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, stroke: Int = Color.rgb(47, 67, 89)) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(17).toFloat(); setStroke(dp(1), stroke)
    }
    private fun count() = prefs.getInt("scenario_count", 0).coerceIn(0, 57)
    private fun controlType(i: Int) = prefs.getString("control_type_$i", "Cenário") ?: "Cenário"
    private fun name(i: Int) = if (i < 3) listOf("Música", "Câmera", "Reload RTC")[i]
        else prefs.getString("scenario_${i - 3}", controlType(i - 3)) ?: controlType(i - 3)
    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NeonTheme.ink
        window.navigationBarColor = NeonTheme.ink
        if (ControllerRuntime.signedIn) { authenticated = true; render() } else showLogin()
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
                } catch (_: Exception) {
                    // Sessao invalida: manter login.
                }
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
            } catch (_: Exception) {
                // Mantém a tela de login enquanto a sessão não for validada.
            }
        }.start()
    }

    private val screenCaptureRequest = 7819

    private fun startScreenCapture() {
        if (ScreenCaptureService.isCapturing) {
            Toast.makeText(this, "A captura de tela já está ativa.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), screenCaptureRequest)
        } catch (error: Exception) {
            Toast.makeText(this, "Falha ao solicitar captura: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != screenCaptureRequest) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "Captura de tela não autorizada.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(service)
        } catch (error: Exception) {
            Toast.makeText(this, "Não foi possível iniciar a captura: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var panel: ControllerPanel? = null
    private var page = 0
    private var resumed = false

    override fun onStart() {
        super.onStart()
        ControllerRuntime.foreground = true
        OverlayService.refreshVisibility()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (authenticated) prepareOverlay()
    }

    override fun onPause() {
        resumed = false
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
        if (authenticated) moveTaskToBack(true) else super.onBackPressed()
    }

    override fun onDestroy() {
        panel?.dispose()
        loginDialogs.forEach { it.setOnDismissListener(null) }
        closeLoginBrowsers()
        super.onDestroy()
    }

    private fun prepareOverlay() {
        if (!resumed || !authenticated || !Settings.canDrawOverlays(this)) return
        try { startForegroundService(Intent(this, OverlayService::class.java)) }
        catch (e: RuntimeException) {
            Toast.makeText(this, "Não foi possível preparar a sobreposição: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun permission() {
        if (Settings.canDrawOverlays(this)) {
            prepareOverlay()
            Toast.makeText(this, "Ao minimizar, o painel flutuante aparece automaticamente.", Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun render() {
        if (!authenticated || isDestroyed) return
        ControllerRuntime.signedIn = true
        closeLoginBrowsers()
        panel?.dispose()
        panel = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NeonTheme.backdrop()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(NeonHeader(this), LinearLayout.LayoutParams(-1, dp(88)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun option(label: String, onTap: () -> Unit) {
            body.addView(text(label, 16f).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12)); background = NeonTheme.card(true)
                setOnClickListener { onTap() }
            }, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(10) })
        }
        when (page) {
            0 -> {
                if (!Settings.canDrawOverlays(this)) option("Autorizar sobreposição automática") { permission() }
                panel = ControllerPanel(this, false)
                body.addView(panel)
            }
            1 -> {
                body.addView(text("Conta LiveNexus conectada.", 16f))
                option("Desconectar conta") {
                    ControllerRuntime.signedIn = false
                    stopService(Intent(this, OverlayService::class.java))
                    LiveNexusApi.logout()
                    panel?.dispose()
                    showLogin()
                }
            }
            else -> {
                option("Iniciar captura de tela") { startScreenCapture() }
                option("Parar captura de tela") {
                    stopService(Intent(this, ScreenCaptureService::class.java))
                    Toast.makeText(this, "Captura de tela encerrada.", Toast.LENGTH_SHORT).show()
                }
                option("Permissão de sobreposição") { permission() }
                body.addView(text("Minimize para mostrar o botão flutuante.\nAo voltar ao aplicativo, a sobreposição desaparece.", 15f).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                })
            }
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            background = NeonTheme.card()
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        listOf("Sobreposição", "Perfil", "Configuração").forEachIndexed { index, title ->
            nav.addView(text(title, 12f, if (page == index) cyan else Color.rgb(142, 143, 169)).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(5), dp(2), dp(12))
                if (page == index) background = NeonTheme.card(true)
                val icon = NeonIcon(listOf("▱", "♙", "⚙")[index]).apply { setBounds(0, 0, dp(26), dp(26)) }
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(5)
                setOnClickListener { page = index; render() }
            }, LinearLayout.LayoutParams(0, dp(72), 1f))
        }
        root.addView(nav)
        setContentView(root)
        prepareOverlay()
    }
}

