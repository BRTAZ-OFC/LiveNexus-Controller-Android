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
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.os.Message
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("buttons", MODE_PRIVATE) }
    @Volatile private var authenticated = false
    private val cyan = Color.rgb(43, 205, 239)
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
        showLogin()
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
        val browser = WebView(this)
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
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                popup.isFocusable = true
                popup.isFocusableInTouchMode = true
                popup.webViewClient = WebViewClient()
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        popup.destroy()
                        verifyLogin()
                    }
                }
                val dialog = AlertDialog.Builder(this@MainActivity).setView(popup)
                    .setNegativeButton("Fechar") { _, _ -> verifyLogin() }.create()
                dialog.setOnDismissListener { verifyLogin() }
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
        browser.webViewClient = object : WebViewClient() {
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
                        css.textContent='.auth-footer,.auth-back-link{display:none!important}';
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
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }
    private lateinit var status: TextView
    private fun updateStatus() { status.text = if (Settings.canDrawOverlays(this)) "● ON" else "○ OFF" }
    private val purple = Color.rgb(162, 95, 255)
    private val red = Color.rgb(245, 65, 105)
    private var page = 0
    private fun accent(i: Int) = if (i >= 2) purple else cyan
    private fun tileStyle(accent: Int): android.graphics.drawable.Drawable {
        val outer = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(45, 65, 89), Color.rgb(9, 16, 27))).apply {
            cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(58, 83, 112))
        }
        val inner = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(22, 36, 54), Color.rgb(9, 16, 27))).apply {
            cornerRadius = dp(15).toFloat(); setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        return LayerDrawable(arrayOf(outer, InsetDrawable(inner, dp(5), dp(5), dp(5), dp(7))))
    }
    private fun render() {
        if (!authenticated) { showLogin(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(17), dp(17), dp(17), dp(17)) }
        header.addView(text("☰", 29f, Color.LTGRAY).apply {
            setOnClickListener { showOptions() }
        }, LinearLayout.LayoutParams(dp(46), dp(55)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text("LiveNexus Controller", 21f).apply { setTypeface(null, Typeface.BOLD) })
        heading.addView(text("Painel ativo", 13f, Color.rgb(44, 210, 185)))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        status = text("●", 13f, cyan).apply { gravity = Gravity.CENTER; background = shape(Color.rgb(20, 31, 48)) }
        header.addView(status, LinearLayout.LayoutParams(dp(52), dp(42)))
        root.addView(header)
        val content = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(10), dp(13), dp(16)) }
        when (page) {
            0 -> {
                val grid = GridLayout(this).apply { columnCount = 3 }
                val total = 3 + count()
                val w = ((resources.displayMetrics.widthPixels - dp(32)) / 3) - dp(6)
                for (i in 0 until total) {
                    val color = accent(i)
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = tileStyle(color)
                        elevation = dp(5).toFloat(); isClickable = true
                        setOnClickListener {
                            if (i == 0) showPlayer()
                            else if (i == 2) sendRtcReload()
                            else if (i < 3) showConnectionMessage("Ação ainda não conectada: ${name(i)}")
                            else showConfiguredControl(i)
                        }
                        setOnLongClickListener { if (i >= 3) configureControl(i) else if (i == 2) showConnectionMessage("Reload RTC: aguardando mapeamento do comando existente."); true }
                    }
                    val icon = if (i < 3) listOf("♫", "▣", "↻")[i] else when (controlType(i - 3)) {
                        "Contador +1" -> "+"
                        "Contador -1" -> "−"
                        "Zerar contador" -> "⟲"
                        "Chat" -> "💬"
                        else -> "▱"
                    }
                    tile.addView(text(icon, 41f, color).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(65)))
                    tile.addView(text(name(i), 13f).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); maxLines = 2 })
                    grid.addView(tile, GridLayout.LayoutParams().apply {
                        width = w; height = w + dp(12); columnSpec = GridLayout.spec(i % 3)
                        rowSpec = GridLayout.spec(i / 3); setMargins(dp(3), dp(5), dp(3), dp(5))
                    })
                }
                val add = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = tileStyle(cyan)
                    setOnClickListener {
                        showAddMenu()
                    }
                }
                add.addView(text("+", 49f, cyan).apply { gravity = Gravity.CENTER })
                add.addView(text("Adicionar botão", 12f, cyan).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) })
                grid.addView(add, GridLayout.LayoutParams().apply {
                    width = w; height = w + dp(12); columnSpec = GridLayout.spec(total % 3)
                    rowSpec = GridLayout.spec(total / 3); setMargins(dp(3), dp(5), dp(3), dp(5))
                })
                body.addView(grid)
            }
            1 -> {
                body.addView(text("PERFIS", 19f, cyan).apply { setPadding(dp(8), dp(15), 0, dp(20)) })
                body.addView(text(if (LiveNexusApi.isConnected()) "Conta LiveNexus conectada nesta sessão." else "Conecte sua conta LiveNexus em Configurações.", 15f))
            }
            else -> {
                body.addView(text("CONFIGURAÇÕES", 19f, cyan).apply { setPadding(dp(8), dp(15), 0, dp(20)) })
                fun setting(label: String, onTap: () -> Unit) {
                    body.addView(text(label, 16f).apply {
                        gravity = Gravity.CENTER; background = tileStyle(cyan); setOnClickListener { onTap() }
                    }, LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(12) })
                }
                setting("Conta LiveNexus") { showConnectionMessage("Conta conectada ao LiveNexus.") }
                setting("Consultar cenários e overlays") { showLiveNexusItems() }
                setting("Desconectar conta") {
                    LiveNexusApi.logout()
                    showLogin()
                }
                setting("Autorizar sobreposição") {
                    if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    else Toast.makeText(this, "Sobreposição já autorizada", Toast.LENGTH_SHORT).show()
                }
                setting("Ativar painel flutuante") {
                    if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, OverlayService::class.java))
                    else Toast.makeText(this, "Autorize a sobreposição primeiro", Toast.LENGTH_SHORT).show()
                }
                setting("Desativar controlador") { stopService(Intent(this, OverlayService::class.java)) }
            }
        }
        content.addView(body)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(6), dp(10), dp(6), dp(12))
            setBackgroundColor(Color.rgb(12, 21, 32))
        }
        listOf("◇\nSobreposição", "◎\nPerfis", "⚙\nConfigurações").forEachIndexed { index, label ->
            nav.addView(text(label, 13f, if (page == index) cyan else Color.GRAY).apply {
                gravity = Gravity.CENTER; setTypeface(null, if (page == index) Typeface.BOLD else Typeface.NORMAL)
                setOnClickListener { page = index; render() }
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(nav)
        setContentView(root)
        updateStatus()
    }
    private fun showOptions() {
        AlertDialog.Builder(this).setTitle("LiveNexus Controller")
            .setItems(arrayOf("Sobreposição", "Perfis", "Configurações")) { _, which -> page = which; render() }.show()
    }
    private fun showAddMenu() {
        AlertDialog.Builder(this).setTitle("Adicionar controle")
            .setItems(arrayOf("Cenário", "Overlay", "LivePix", "Contador +1", "Contador -1", "Zerar contador", "Chat", "Teste contador +1")) { _, selected ->
                if (count() >= 57) Toast.makeText(this, "Limite de 60 controles", Toast.LENGTH_SHORT).show()
                else {
                    prefs.edit().putString("control_type_${count()}", arrayOf("Cenário", "Overlay", "LivePix", "Contador +1", "Contador -1", "Zerar contador", "Chat", "Teste contador +1")[selected])
                        .putInt("scenario_count", count() + 1).apply()
                    render()
                }
            }.setNegativeButton("Cancelar", null).show()
    }
    private fun configureControl(i: Int) {
        val kind = controlType(i - 3)
        if (kind != "Cenário" && kind != "Overlay") {
            editLabel(i)
            return
        }
        Thread {
            try {
                val items = if (kind == "Cenário") LiveNexusApi.scenarios() else LiveNexusApi.overlays()
                val records = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                val labels = records.map { item ->
                    item.optString("nome").ifBlank { item.optString("name").ifBlank { item.optString("id", "Sem nome") } }
                }.toTypedArray()
                runOnUiThread {
                    if (records.isEmpty()) {
                        showConnectionMessage("Nenhum registro de $kind encontrado para esta conta.")
                    } else {
                        AlertDialog.Builder(this).setTitle("Selecionar $kind")
                            .setItems(labels) { _, position ->
                                val record = records[position]
                                val id = record.optString("id")
                                if (id.isBlank()) {
                                    showConnectionMessage("Cenário sem identificador.")
                                } else if (kind == "Cenário") {
                                    val subs = record.optJSONArray("subScenarios")
                                    val choices = (0 until (subs?.length() ?: 0)).mapNotNull { index ->
                                        subs?.optJSONObject(index)?.takeIf {
                                            it.optString("id").isNotBlank() && it.optString("name").isNotBlank()
                                        }
                                    }
                                    if (choices.isEmpty()) {
                                        showConnectionMessage("Este cenário não possui subcenários salvos.")
                                    } else {
                                        AlertDialog.Builder(this).setTitle("Selecionar subcenário")
                                            .setItems(choices.map { it.optString("name") }.toTypedArray()) { _, subIndex ->
                                                val sub = choices[subIndex]
                                                prefs.edit().putString("control_record_${i - 3}", id)
                                                    .putString("control_subscenario_${i - 3}", sub.optString("id"))
                                                    .putString("control_user_${i - 3}", record.optString("user_id"))
                                                    .putString("scenario_${i - 3}", sub.optString("name")).apply()
                                                render()
                                            }.setNegativeButton("Cancelar", null).show()
                                    }
                                } else {
                                    prefs.edit().putString("control_record_${i - 3}", id)
                                        .remove("control_subscenario_${i - 3}")
                                        .putString("control_user_${i - 3}", record.optString("user_id"))
                                        .putString("scenario_${i - 3}", labels[position]).apply()
                                    render()
                                }
                            }.setNegativeButton("Cancelar", null).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showConnectionMessage(e.message ?: "Falha ao consultar registros.") }
            }
        }.start()
    }

    private fun sendRtcReload() {
        Thread {
            try {
                LiveNexusApi.sendCommand("rtc-reload")
                runOnUiThread { showConnectionMessage("Comando de atualização RTC enviado.") }
            } catch (e: Exception) {
                runOnUiThread { showConnectionMessage(e.message ?: "Falha ao enviar comando RTC.") }
            }
        }.start()
    }

    private fun showConfiguredControl(i: Int) {
        val kind = controlType(i - 3)
        val recordId = prefs.getString("control_record_${i - 3}", null)
        if ((kind == "Cenário" || kind == "Overlay") && recordId.isNullOrBlank()) {
            configureControl(i)
        } else if (kind == "Cenário" || kind == "Overlay") {
            val userId = prefs.getString("control_user_${i - 3}", "").orEmpty()
            if (userId.isBlank()) {
                showConnectionMessage("Registro sem user_id. Segure o botão e selecione novamente.")
            } else {
                val path = if (kind == "Cenário") "/cenarios/receiver/" else "/overlay/receiver/"
                val subId = if (kind == "Cenário") prefs.getString("control_subscenario_${i - 3}", "").orEmpty() else ""
                val address = Uri.parse(LiveNexusApi.baseUrl + path + Uri.encode(recordId))
                    .buildUpon().appendQueryParameter("user_id", userId)
                    .apply { if (subId.isNotBlank()) appendQueryParameter("subscenario_id", subId) }.build()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, address))
                } catch (e: Exception) {
                    showConnectionMessage("Não foi possível abrir o Receiver: " + (e.message ?: "erro"))
                }
            }
        } else if (kind in setOf("Contador +1", "Contador -1", "Zerar contador", "Teste contador +1")) {
            val action = when (kind) {
                "Contador -1" -> "death-decrement"
                "Zerar contador" -> "death-reset"
                else -> "death-increment"
            }
            Thread {
                try {
                    LiveNexusApi.sendCommand(action)
                    runOnUiThread { showConnectionMessage("Comando enviado ao LiveNEXUS: $kind") }
                } catch (e: Exception) {
                    runOnUiThread { showConnectionMessage(e.message ?: "Falha ao enviar comando.") }
                }
            }.start()
        } else {
            showConnectionMessage("Ação ainda não conectada: ${name(i)}")
        }
    }

    private fun editLabel(i: Int) {
        val field = EditText(this).apply { setSingleLine(true); setText(name(i)); selectAll() }
        val holder = FrameLayout(this).apply { setPadding(dp(16), 0, dp(16), 0); addView(field) }
        AlertDialog.Builder(this).setTitle("Editar botão").setView(holder)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val value = field.text.toString().trim()
                if (value.length in 1..30) { prefs.edit().putString("scenario_${i - 3}", value).apply(); render() }
                else Toast.makeText(this, "Use de 1 a 30 caracteres", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showLiveNexusLogin() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val server = EditText(this).apply {
            hint = "https://seu-livenexus.com"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("livenexus_url", ""))
        }
        val user = EditText(this).apply {
            hint = "Usuário do LiveNexus"
            setSingleLine(true)
        }
        val password = EditText(this).apply {
            hint = "Senha"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fields.addView(server)
        fields.addView(user)
        fields.addView(password)
        AlertDialog.Builder(this).setTitle("Conectar ao LiveNexus")
            .setView(fields)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                val url = server.text.toString().trim()
                val username = user.text.toString().trim()
                val secret = password.text.toString()
                password.text.clear()
                if (username.isBlank() || secret.isBlank()) {
                    showConnectionMessage("Informe usuário e senha.")
                } else {
                    try {
                        LiveNexusApi.configure(url)
                        prefs.edit().putString("livenexus_url", url.trimEnd('/')).apply()
                        Thread {
                            try {
                                val account = LiveNexusApi.login(username, secret)
                                runOnUiThread {
                                    showConnectionMessage("Conectado: " + account.optString("user", "Conta LiveNexus"))
                                    render()
                                }
                            } catch (e: Exception) {
                                runOnUiThread { showConnectionMessage(e.message ?: "Falha ao conectar.") }
                            }
                        }.start()
                    } catch (e: Exception) {
                        showConnectionMessage(e.message ?: "Endereço inválido.")
                    }
                }
            }.show()
    }

    private fun showConnectionMessage(message: String) {
        AlertDialog.Builder(this).setTitle("LiveNexus").setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    private fun showLiveNexusItems() {
        if (!authenticated) {
            showConnectionMessage("Entre na conta LiveNexus primeiro.")
            return
        }
        Thread {
            try {
                val scenarios = LiveNexusApi.scenarios()
                val overlays = LiveNexusApi.overlays()
                fun describe(items: JSONArray): String {
                    if (items.length() == 0) return "(nenhum)"
                    return (0 until items.length()).joinToString("\n") { i ->
                        val item = items.optJSONObject(i)
                        val label = item?.optString("nome", "")?.takeIf { it.isNotBlank() }
                            ?: item?.optString("name", "")?.takeIf { it.isNotBlank() }
                            ?: item?.optString("id", "Item ${i + 1}")
                            ?: "Item ${i + 1}"
                        "• $label"
                    }
                }
                val result = "CENÁRIOS (${scenarios.length()})\n${describe(scenarios)}" +
                    "\n\nOVERLAYS (${overlays.length()})\n${describe(overlays)}"
                runOnUiThread { showConnectionMessage(result) }
            } catch (e: Exception) {
                runOnUiThread { showConnectionMessage(e.message ?: "Erro ao consultar LiveNexus.") }
            }
        }.start()
    }

    private fun showPlayer() {
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        view.addView(text("♫  Nenhuma música conectada", 17f))
        view.addView(text("◀       ▶       ▶▶       🔊", 22f, cyan).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20)) })
        view.addView(text("Prévia do mini player. Os comandos serão habilitados após a integração autenticada com o LiveNexus.", 13f))
        AlertDialog.Builder(this).setTitle("LiveNexus • Player").setView(view).setPositiveButton("Fechar", null).show()
    }
}
