package com.livenexus.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cliente das rotas existentes de comandos e cenários do LiveNEXUS.
 * Sem nova API ou serviço externo. Uma requisição para cada ação, sem repetição.
 */
internal object LiveNexusControllerApi {
    private const val TAG = "LiveNEXUSControllerApi"
    private const val ORIGIN = "https://livenexus.acq.lat"
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "LiveNEXUSControllerIO").apply { isDaemon = true }
    }
    private val allowedCommands = setOf(
        "death-increment", "death-decrement", "death-reset", "rtc-reload",
        "music-toggle", "music-next", "music-previous",
        "music-volume-up", "music-volume-down", "scenario-select"
    )

    data class Scenario(val id: String, val name: String, val subScenarioId: String)

    fun sendCommand(
        context: Context,
        action: String,
        scenario: Scenario? = null,
        onResult: (Boolean) -> Unit
    ) {
        if (action !in allowedCommands ||
            (action == "scenario-select" &&
                (scenario == null || scenario.id.isBlank() || scenario.subScenarioId.isBlank()))) {
            main.post { onResult(false) }
            return
        }
        val app = context.applicationContext
        worker.execute {
            val accepted = try {
                val body = JSONObject().put("action", action)
                if (action == "scenario-select") {
                    body.put("scenarioId", scenario!!.id)
                    body.put("subcenarioId", scenario.subScenarioId)
                }
                request(sessionToken(app), "/api/controller/commands", "POST", body).optBoolean("ok")
            } catch (error: Exception) {
                logError("comando " + action, error)
                false
            }
            main.post { onResult(accepted) }
        }
    }

    fun fetchScenarios(context: Context, onResult: (List<Scenario>?, String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = try {
                val json = request(verifiedToken(app), "/api/cenarios", "GET")
                val items = json.optJSONArray("items") ?: throw IOException("Lista de cenários ausente")
                val scenes = ArrayList<Scenario>()
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let { record -> parseScenario(record)?.let(scenes::add) }
                }
                Pair(scenes.toList(), null)
            } catch (error: Exception) {
                logError("consultar cenários", error)
                Pair(null, "Não foi possível carregar os cenários do LiveNEXUS.")
            }
            main.post { onResult(result.first, result.second) }
        }
    }

    fun createScenario(context: Context, name: String, onResult: (Scenario?, String?) -> Unit) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 100) {
            main.post { onResult(null, "Nome de cenário inválido.") }
            return
        }
        val app = context.applicationContext
        worker.execute {
            val result = try {
                val token = verifiedToken(app)
                // Campos idênticos ao POST utilizado no ScenariosPage.tsx do LiveNEXUS.
                val timestamp = System.currentTimeMillis()
                val id = "scenario-" + timestamp
                val subId = "sub-" + (timestamp + 1)
                val record = JSONObject().apply {
                    put("id", id)
                    put("name", clean)
                    put("visualizerSize", JSONObject().put("width", 1920).put("height", 1080))
                    put("instances", JSONArray())
                    put("background", JSONObject().put("type", "transparent").put("opacity", 100))
                    put("selectedSubScenarioId", subId)
                    put("subScenarios", JSONArray().put(JSONObject().apply {
                        put("id", subId)
                        put("name", "Sub Cenário 1")
                        put("instances", JSONArray())
                    }))
                }
                val response = request(token, "/api/cenarios", "POST", JSONObject().put("record", record))
                val created = response.optJSONObject("item")?.let(::parseScenario)
                    ?: throw IOException("Cenário criado sem confirmação válida")
                Pair(created, null)
            } catch (error: Exception) {
                logError("criar cenário", error)
                Pair(null, "Não foi possível criar o cenário no LiveNEXUS.")
            }
            main.post { onResult(result.first, result.second) }
        }
    }

    private fun parseScenario(record: JSONObject): Scenario? {
        val settings = record.optJSONObject("settings")
        val config = record.optJSONObject("config")
        val data = record.optJSONObject("data")
        val source = when {
            settings != null && settings.length() > 0 -> settings
            config?.has("subScenarios") == true -> config
            data != null && data.length() > 0 -> data
            else -> record
        }
        val id = record.optString("id", source.optString("id", "")).trim()
        if (id.isBlank()) return null
        val subs = source.optJSONArray("subScenarios") ?: return null
        val current = source.optString("selectedSubScenarioId", "").trim()
        var first = ""
        var chosen = ""
        for (i in 0 until subs.length()) {
            val candidate = subs.optJSONObject(i)?.optString("id", "")?.trim().orEmpty()
            if (candidate.isBlank()) continue
            if (first.isBlank()) first = candidate
            if (candidate == current) chosen = candidate
        }
        val subId = if (chosen.isNotBlank()) chosen else first
        if (subId.isBlank()) return null
        return Scenario(id, source.optString("name", record.optString("name", "Cenário")), subId)
    }

    private fun verifiedToken(context: Context): String {
        val token = sessionToken(context)
        // JSON-compat tem fallback de user_id; nunca acessá-lo sem validar a sessão.
        if (!LiveNexusAuth().validateSession(token)) throw IOException("Sessão inválida")
        return token
    }

    private fun sessionToken(context: Context): String =
        LocalSession(context).read()?.takeIf { it.isNotBlank() }
            ?: throw IOException("Sessão não encontrada")

    private fun request(
        token: String, path: String, method: String, body: JSONObject? = null
    ): JSONObject {
        val connection = URL(ORIGIN + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + token)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP " + status)
            val raw = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > MAX_RESPONSE_BYTES) throw IOException("Resposta grande")
                    output.write(buffer, 0, n)
                }
                output.toString(Charsets.UTF_8.name())
            }
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) throw IOException("Operação rejeitada")
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun logError(operation: String, error: Exception) {
        // Sem registrar credenciais, URLs de streaming ou conteúdo de usuário.
        Log.w(TAG, "Falha ao " + operation + ": " + error.javaClass.simpleName)
    }
}
