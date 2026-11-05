package com.livenexus.controller

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A tela principal só é carregada depois de autenticação confirmada pelo backend.
 * O WebView da sessão não recebe ponte JavaScript nem token.
 * Google/Steam abrem exclusivamente fora do APK.
 */
class MainActivity : Activity() {
    private val apiOrigin = "https://livenexus.acq.lat"
    private val worker = Executors.newSingleThreadExecutor()
    private var browser: WebView? = null
    @Volatile private var authorized = false
    @Volatile private var pendingLogin = false

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
                        "/api/auth/login",
                        "POST",
                        JSONObject().put("user", username).put("password", password).toString(),
                        null
                    )
                    val session = response.optString("token")
                    if (!response.optBoolean("ok") || session.isBlank()) {
                        showError("Usuário ou senha incorretos.")
                        return@execute
                    }
                    val verification = apiRequest("/api/auth/me", "GET", null, session)
                    val user = verification.optJSONObject("user")
                    if (!verification.optBoolean("ok") || user?.optString("user_id").isNullOrBlank()) {
                        showError("Não foi possível validar a sessão.")
                        return@execute
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            authorized = true
                            showMainTemplate()
                        }
                    }
                } catch (_: Exception) {
                    showError("Falha ao conectar ao LiveNEXUS. Verifique a internet e tente novamente.")
                } finally {
                    pendingLogin = false
                }
            }
        }

        @JavascriptInterface
        fun external(provider: String) {
            val path = when (provider) {
                "google" -> "/api/auth/google"
                "steam" -> "/api/auth/steam"
                else -> return
            }
            runOnUiThread {
                if (!authorized && !isFinishing && !isDestroyed) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apiOrigin + path)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        })
                        browser?.evaluateJavascript(
                            "window.loginResult('Faça a autenticação no navegador externo. A integração de retorno ao APK ainda não está disponível.');",
                            null
                        )
                    } catch (_: Exception) {
                        browser?.evaluateJavascript(
                            "window.loginResult('Nenhum aplicativo externo disponível para autenticação.');",
                            null
                        )
                    }
                }
            }
        }
    }

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
            if (connection.responseCode !in 200..299) throw IOException("Autenticação recusada")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && !authorized) {
                val safe = JSONObject.quote(message)
                browser?.evaluateJavascript("window.loginResult($safe);", null)
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
    }

    private fun localBrowser(): WebView {
        return WebView(this).apply {
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
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            }
        }
    }

    private fun showLogin() {
        authorized = false
        browser?.apply {
            stopLoading()
            destroy()
        }
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
        browser = mainView
        setContentView(mainView)
        mainView.loadUrl("file:///android_asset/livenexus/index.html")
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (authorized) showLogin() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        browser?.apply {
            stopLoading()
            destroy()
        }
        browser = null
        super.onDestroy()
    }
}
