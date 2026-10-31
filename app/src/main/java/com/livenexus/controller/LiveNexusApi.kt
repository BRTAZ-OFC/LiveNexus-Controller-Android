package com.livenexus.controller

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.CookieManager

/**
 * Cliente de leitura da API existente do LiveNexus.
 * Nenhuma rota nova é exigida no servidor.
 * O token fica somente na memória do processo durante este teste.
 */
object LiveNexusApi {
    @Volatile var baseUrl: String = "https://livenexus-teste.acq.lat"
        private set
    @Volatile private var token: String? = null

    fun configure(url: String) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "Informe uma URL HTTPS do LiveNexus." }
        val parsed = URL(normalized)
        require(parsed.host.isNotBlank() && parsed.userInfo == null && parsed.query == null && parsed.ref == null) {
            "Informe somente o endereço base do LiveNexus."
        }
        baseUrl = normalized
        token = null
    }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        require(baseUrl.isNotBlank()) { "Configure primeiro o endereço do LiveNexus." }
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            CookieManager.getInstance().getCookie(baseUrl)?.let { connection.setRequestProperty("Cookie", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val response = try { JSONObject(raw) } catch (_: Exception) {
                throw IllegalStateException("A resposta não é JSON (HTTP $status). Verifique o endereço da API.")
            }
            if (status !in 200..299 || !response.optBoolean("ok", false)) {
                throw IllegalStateException(response.optString("message",
                    response.optString("error", "Falha na API (HTTP $status).")))
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun login(user: String, password: String): JSONObject {
        token = null
        val result = request("/api/auth/login", "POST",
            JSONObject().put("user", user).put("password", password))
        val newToken = result.optString("token", "")
        require(newToken.isNotBlank()) { "O servidor não retornou token de sessão." }
        token = newToken
        return request("/api/auth/me").getJSONObject("user")
    }

    fun acceptWebToken(candidate: String): JSONObject {
        require(candidate.isNotBlank()) { "Token de login vazio." }
        token = candidate
        return try { checkSession() } catch (error: Exception) { token = null; throw error }
    }

    fun isConnected() = !token.isNullOrBlank()
    fun checkSession(): JSONObject = request("/api/auth/me").getJSONObject("user")
    fun logout() { token = null; CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
    fun scenarios() = request("/api/cenarios").getJSONArray("items")
    fun overlays() = request("/api/overlays").getJSONArray("items")
    fun selectSubscenario(scenarioId: String, subcenarioId: String): JSONObject {
        require(scenarioId.isNotBlank() && subcenarioId.isNotBlank()) { "Cenário e subcenário obrigatórios." }
        return request("/api/controller/commands", "POST", JSONObject()
            .put("action", "scenario-select")
            .put("scenarioId", scenarioId)
            .put("subcenarioId", subcenarioId))
    }

    fun sendCommand(action: String): JSONObject {
        require(action in setOf("death-increment", "death-decrement", "death-reset", "rtc-reload", "music-toggle", "music-next", "music-previous", "music-volume-up", "music-volume-down")) {
            "Comando não permitido."
        }
        return request("/api/controller/commands", "POST", JSONObject().put("action", action))
    }
}
