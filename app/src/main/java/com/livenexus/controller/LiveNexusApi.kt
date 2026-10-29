package com.livenexus.controller

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente de leitura da API existente do LiveNexus.
 * Nenhuma rota nova é exigida no servidor.
 * O token fica somente na memória do processo durante este teste.
 */
object LiveNexusApi {
    @Volatile var baseUrl: String = ""
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

    fun isConnected() = !token.isNullOrBlank()
    fun logout() { token = null }
    fun scenarios() = request("/api/cenarios").getJSONArray("items")
    fun overlays() = request("/api/overlays").getJSONArray("items")
}
