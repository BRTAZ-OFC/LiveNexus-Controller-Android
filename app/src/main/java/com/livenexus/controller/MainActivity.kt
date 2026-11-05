package com.livenexus.controller

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ExecutorService
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * O APK só libera o template após uma sessão validada pelo servidor.
 * A autenticação Google/Steam ocorre no navegador externo com retorno via deep link.
 * O código de autorização é trocado com prova PKCE e nunca é tratado como sessão.
 */
class MainActivity : Activity() {
    private val apiOrigin = "https://livenexus.acq.lat"
    private val worker = Executors.newSingleThreadExecutor()
    private val random = SecureRandom()
    private val authPrefs by lazy { getSharedPreferences("mobile_auth_pending", MODE_PRIVATE) }
    private var browser: WebView? = null
    @Volatile private var authorized = false
    @Volatile private var pendingLogin = false
    @Volatile private var sessionToken: String? = null
    @Volatile private var sessionUserId: String? = null

    private fun randomUrlSafe(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun sha256Challenge(verifier: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

    private fun clearPendingExternal() {
        authPrefs.edit().clear().apply()
    }

    private inner class LoginBridge {
        @JavascriptInterface
        fun login(username: String, password: String) {
            if (authorized || pendingLogin) return
            pendingLogin = true
            worker.execute {
                try {
                    if (username.isBlank() || password.isBlank()) {
                        showError("Preencha usuário e senha.")
                        return@execute
                    }
                    val response = apiRequest(
                        "/api/auth/login", "POST",
                        JSONObject().put("user", username).put("password", password).toString(), null
                    )
                    val token = response.optString("token")
                    if (!response.optBoolean("ok") || token.isBlank()) {
                        showError("Usuário ou senha incorretos.")
                        return@execute
                    }
                    verifyAndOpen(token)
                } catch (_: Exception) {
                    showError("Falha ao conectar ao LiveNEXUS. Verifique a internet.")
                } finally {
                    pendingLogin = false
                }
            }
        }

        @JavascriptInterface
        fun external(provider: String) {
            if (provider != "google" && provider != "steam") return
            if (authorized || pendingLogin) return
            pendingLogin = true
            worker.execute {
                try {
                    val verifier = randomUrlSafe()
                    val state = randomUrlSafe()
                    val response = apiRequest(
                        "/api/auth/mobile/start", "POST",
                        JSONObject().put("provider", provider).put("state", state)
                            .put("challenge", sha256Challenge(verifier)).toString(), null
                    )
                    val url = response.optString("url")
                    if (!response.optBoolean("ok") ||
                        !url.startsWith("/api/auth/$provider?mobile_request=")) {
                        showError("Não foi possível iniciar o login externo.")
                        return@execute
                    }
                    authPrefs.edit()
                        .putString("state", state)
                        .putString("verifier", verifier)
                        .putLong("created", System.currentTimeMillis())
                        .apply()
                    runOnUiThread {
                        if (!authorized && !isFinishing && !isDestroyed) {
                            try {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(apiOrigin + url))
                                        .addCategory(Intent.CATEGORY_BROWSABLE)
                                )
                                showError("Conclua o login externo para retornar ao LiveNEXUS.")
                            } catch (_: Exception) {
                                clearPendingExternal()
                                showError("Não foi possível abrir a autenticação externa.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LiveNexusAuth", "Falha ao iniciar autenticacao externa ($provider)", e)
                    val message = when (e) {
                        is HttpStatusException -> "Servidor LiveNEXUS respondeu HTTP ${e.status} no login externo."
                        is UnknownHostException -> "Nao foi possivel localizar o servidor LiveNEXUS (DNS)."
                        is SocketTimeoutException -> "O servidor LiveNEXUS nao respondeu no prazo (timeout)."
                        is SSLException -> "Falha na conexao HTTPS com o servidor LiveNEXUS."
                        is ConnectException -> "Nao foi possivel conectar ao servidor LiveNEXUS."
                        else -> "Erro de autenticacao: ${e.javaClass.simpleName}. Consulte o diagnostico."
                    }
                    showError(message)
                } finally {
                    pendingLogin = false
                }
            }
        }
    }

    private fun processExternalReturn(uri: Uri?) {
        if (uri?.scheme != "com.livenexus.controller" ||
            uri.host != "auth" || uri.path != "/callback") return
        val code = uri.getQueryParameter("code") ?: return
        val state = uri.getQueryParameter("state") ?: return
        val storedState = authPrefs.getString("state", null)
        val verifier = authPrefs.getString("verifier", null)
        val created = authPrefs.getLong("created", 0)
        if (authorized || pendingLogin || storedState == null || verifier == null ||
            state != storedState || System.currentTimeMillis() - created > 10 * 60 * 1000) {
            showError("Solicitação externa inválida ou expirada. Tente novamente.")
            return
        }
        pendingLogin = true
        clearPendingExternal()
        worker.execute {
            try {
                val response = apiRequest(
                    "/api/auth/mobile/exchange", "POST",
                    JSONObject().put("code", code).put("state", state)
                        .put("verifier", verifier).toString(), null
                )
                val token = response.optString("token")
                if (!response.optBoolean("ok") || token.isBlank()) {
                    showError("O servidor não confirmou o login externo.")
                    return@execute
                }
                verifyAndOpen(token)
            } catch (_: Exception) {
                showError("Falha ao validar o login externo. Tente novamente.")
            } finally {
                pendingLogin = false
            }
        }
    }

    private fun verifyAndOpen(token: String) {
        val response = apiRequest("/api/auth/me", "GET", null, token)
        if (!response.optBoolean("ok") ||
            response.optJSONObject("user")?.optString("user_id").isNullOrBlank()) {
            showError("O servidor não confirmou sua sessão.")
            return
        }
        val userId = response.optJSONObject("user")?.optString("user_id").orEmpty()
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                sessionToken = token
                sessionUserId = userId
                authorized = true
                showMainTemplate()
            }
        }
    }

    internal class HttpStatusException(val status: Int) : IOException("HTTP $status")

    private fun apiRequest(
        path: String,
        method: String,
        body: String?,
        token: String?
    ): JSONObject {
        val connection = URL(apiOrigin + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w("LiveNexusAuth", "Falha HTTP $status em $path")
                throw HttpStatusException(status)
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && !authorized) {
                browser?.evaluateJavascript("window.loginResult(${JSONObject.quote(message)});", null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(2, 3, 19)
        window.navigationBarColor = Color.rgb(2, 3, 19)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        showLogin()
        processExternalReturn(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processExternalReturn(intent.data)
    }

    private fun localBrowser(): WebView =
        WebView(this).apply {
            setBackgroundColor(Color.rgb(2, 3, 19))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = true
            }
        }

    private fun showLogin() {
        authorized = false
        sessionToken = null
        sessionUserId = null
        browser?.apply { stopLoading(); destroy() }
        val loginView = localBrowser()
        loginView.addJavascriptInterface(LoginBridge(), "AndroidLogin")
        browser = loginView
        setContentView(loginView)
        loginView.loadUrl("file:///android_asset/livenexus/login.html")
    }

    private fun showMainTemplate() {
        if (!authorized) return
        browser?.apply {
            removeJavascriptInterface("AndroidLogin")
            stopLoading()
            destroy()
        }
        val mainView = localBrowser()
        mainView.addJavascriptInterface(
            ControllerBridge(
                context = this,
                executor = worker,
                isAuthorized = { authorized },
                token = { sessionToken },
                userId = { sessionUserId },
                request = ::apiRequest,
                deliver = { name, payload ->
                    runOnUiThread {
                        if (authorized && browser === mainView && !isFinishing && !isDestroyed) {
                            mainView.evaluateJavascript(
                                "window.LiveNexusControls && window.LiveNexusControls." + name +
                                    "(" + JSONObject.quote(payload.toString()) + ");",
                                null
                            )
                        }
                    }
                }
            ), "AndroidController"
        )
        browser = mainView
        setContentView(mainView)
        mainView.loadUrl("file:///android_asset/livenexus/index.html")
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (authorized) {
            clearPendingExternal()
            showLogin()
        } else {
            moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        browser?.apply { stopLoading(); destroy() }
        browser = null
        super.onDestroy()
    }
}

/**
 * Ponte restrita à interface local do Controller; não duplica a lógica do dashboard.
 * Utiliza SOMENTE GET /api/cenarios e POST /api/controller/commands.
 * Nenhuma informação de sessão é enviada à página HTML.
 */
private class ControllerBridge(
    private val context: Activity,
    private val executor: ExecutorService,
    private val isAuthorized: () -> Boolean,
    private val token: () -> String?,
    private val userId: () -> String?,
    private val request: (String, String, String?, String?) -> JSONObject,
    private val deliver: (String, JSONObject) -> Unit
) {
    private val preferences = context.getSharedPreferences(
        "livenexus_controller_scene_buttons", Activity.MODE_PRIVATE
    )
    private val supportedActions = setOf(
        "rtc-reload", "scenario-select", "music-toggle", "music-next",
        "music-previous", "music-volume-up", "music-volume-down"
    )
    // Chaves dos subcenários recebidos do próprio servidor para este usuário.
    private var validSubscenes = emptySet<String>()

    private fun fault(action: String, message: String) =
        JSONObject().put("ok", false).put("action", action).put("message", message)

    private fun message(error: Exception): String = when (error) {
        is MainActivity.HttpStatusException -> "Servidor respondeu HTTP " + error.status + "."
        is SocketTimeoutException -> "Tempo esgotado ao consultar o LiveNEXUS."
        is UnknownHostException -> "Servidor LiveNEXUS não encontrado."
        else -> "Falha ao comunicar com o LiveNEXUS."
    }

    @JavascriptInterface
    fun loadScenes() {
        executor.execute {
            val bearer = token()
            val uid = userId()
            if (!isAuthorized() || bearer.isNullOrEmpty() || uid.isNullOrEmpty()) return@execute
            try {
                val answer = request("/api/cenarios", "GET", null, bearer)
                if (!answer.optBoolean("ok")) {
                    deliver("onCatalog", fault("catalog", "Servidor não retornou seus cenários."))
                    return@execute
                }
                val raw = answer.optJSONArray("items") ?: JSONArray()
                val scenes = JSONArray()
                val pairs = mutableSetOf<String>()
                for (i in 0 until raw.length()) {
                    val row = raw.optJSONObject(i) ?: continue
                    val sceneId = row.optString("id").trim()
                    if (sceneId.isBlank()) continue
                    val sources = listOfNotNull(
                        row.optJSONObject("settings"),
                        row.optJSONObject("config"),
                        row.optJSONObject("data"),
                        row
                    )
                    val src = sources.firstOrNull {
                        it.optJSONArray("subScenarios") != null
                    } ?: row
                    val sceneName = src.optString("name").ifBlank {
                        row.optString("name").ifBlank { "Cenário sem nome" }
                    }
                    val actualSubs = src.optJSONArray("subScenarios") ?: JSONArray()
                    val subs = JSONArray()
                    for (j in 0 until actualSubs.length()) {
                        val sub = actualSubs.optJSONObject(j) ?: continue
                        val subId = sub.optString("id").trim()
                        if (subId.isBlank()) continue
                        val subName = sub.optString("name").ifBlank { "Subcenário sem nome" }
                        subs.put(JSONObject().put("id", subId).put("name", subName))
                        pairs.add(sceneId + ":" + subId)
                    }
                    scenes.put(
                        JSONObject().put("id", sceneId)
                            .put("name", sceneName)
                            .put("subScenarios", subs)
                    )
                }
                validSubscenes = pairs
                val saved = try {
                    JSONObject(preferences.getString(uid, "{}") ?: "{}")
                } catch (_: Exception) { JSONObject() }
                deliver(
                    "onCatalog",
                    JSONObject().put("ok", true).put("scenarios", scenes)
                        .put("bindings", saved)
                )
            } catch (e: Exception) {
                Log.e("LiveNexusController", "Erro ao buscar cenários", e)
                deliver("onCatalog", fault("catalog", message(e)))
            }
        }
    }

    @JavascriptInterface
    fun saveScene(slot: Int, sceneId: String, subId: String) {
        executor.execute {
            val uid = userId()
            if (!isAuthorized() || uid.isNullOrEmpty()) return@execute
            if (slot < 1 || slot > 1000 || !validSubscenes.contains(sceneId + ":" + subId)) {
                deliver("onSaved", fault("save", "Selecione um subcenário real."))
                return@execute
            }
            try {
                val saved = try {
                    JSONObject(preferences.getString(uid, "{}") ?: "{}")
                } catch (_: Exception) { JSONObject() }
                saved.put(slot.toString(), JSONObject()
                    .put("scenarioId", sceneId).put("subcenarioId", subId))
                if (!preferences.edit().putString(uid, saved.toString()).commit()) {
                    deliver("onSaved", fault("save", "Falha ao gravar o botão."))
                    return@execute
                }
                deliver(
                    "onSaved",
                    JSONObject().put("ok", true).put("slot", slot)
                        .put("scenarioId", sceneId).put("subcenarioId", subId)
                )
            } catch (e: Exception) {
                deliver("onSaved", fault("save", "Falha ao salvar o botão no Android."))
            }
        }
    }

    @JavascriptInterface
    fun sendCommand(action: String, sceneId: String, subId: String) {
        executor.execute {
            val bearer = token()
            if (!isAuthorized() || bearer.isNullOrEmpty()) return@execute
            if (action !in supportedActions) {
                deliver("onCommand", fault(action, "Comando não disponível na API existente."))
                return@execute
            }
            if (action == "scenario-select" &&
                !validSubscenes.contains(sceneId + ":" + subId)) {
                deliver("onCommand", fault(action, "Subcenário não encontrado. Atualize a lista."))
                return@execute
            }
            try {
                val body = JSONObject().put("action", action)
                if (action == "scenario-select") {
                    body.put("scenarioId", sceneId).put("subcenarioId", subId)
                }
                val result = request("/api/controller/commands", "POST", body.toString(), bearer)
                deliver(
                    "onCommand",
                    if (result.optBoolean("ok"))
                        JSONObject().put("ok", true).put("action", action)
                    else fault(action, "O servidor não aceitou o comando.")
                )
            } catch (e: Exception) {
                Log.e("LiveNexusController", "Comando recusado: " + action, e)
                deliver("onCommand", fault(action, message(e)))
            }
        }
    }
}
