package com.livenexus.controller

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private val diagnosticsSending = AtomicBoolean(false)
    private val diagnosticsWorker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "LiveNEXUSDiagnosticIO").apply { isDaemon = true }
    }

    private val encoderDiagnosticsSending = AtomicBoolean(false)

    // Relatório numérico do encoder em canal autenticado; sem URLs ou credenciais.
    fun sendEncoderDiagnostics(context: Context, report: JSONObject) {
        if (!encoderDiagnosticsSending.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            diagnosticsWorker.execute {
                try {
                    request(sessionToken(app), "/api/laboratorio/encoder/reports", "POST", report)
                } catch (error: Exception) {
                    logError("enviar métricas do encoder", error)
                } finally {
                    encoderDiagnosticsSending.set(false)
                }
            }
        } catch (error: Exception) {
            encoderDiagnosticsSending.set(false)
            logError("agendar métricas do encoder", error)
        }
    }

    // Canal de observabilidade isolado da fila de comandos do Controller.
    fun sendBrowserDiagnostics(context: Context, report: JSONObject) {
        if (!diagnosticsSending.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            diagnosticsWorker.execute {
                try {
                    val response = request(sessionToken(app), "/api/laboratorio/browser-source/reports", "POST", report)
                    response.optJSONObject("captureRequest")?.let { capture ->
                        BrowserSourceManager.armVisualComparison(
                            app, capture.optString("id"), capture.optString("sourceId")
                        )
                    }
                } catch (error: Exception) {
                    logError("enviar diagnostico Browser Source", error)
                } finally {
                    diagnosticsSending.set(false)
                }
            }
        } catch (error: Exception) {
            diagnosticsSending.set(false)
            logError("agendar diagnostico Browser Source", error)
        }
    }

    fun sendVisualComparison(
        context: Context,
        requestId: String,
        sourceId: String,
        original: Bitmap,
        composed: Bitmap,
        detailTop: Bitmap,
        detailBottom: Bitmap
    ) {
        val app = context.applicationContext
        try {
            diagnosticsWorker.execute {
                try {
                    fun encode(image: Bitmap, quality: Int, maxBytes: Int): String {
                        val stream = ByteArrayOutputStream()
                        if (!image.compress(Bitmap.CompressFormat.JPEG, quality, stream) ||
                            stream.size() > maxBytes) throw IOException("Imagem diagnóstica muito grande")
                        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    }
                    val body = JSONObject().apply {
                        put("id", requestId)
                        put("sourceId", sourceId)
                        put("originalWidth", original.width)
                        put("originalHeight", original.height)
                        put("composedWidth", composed.width)
                        put("composedHeight", composed.height)
                        put("original", encode(original, 60, 230_000))
                        put("composed", encode(composed, 60, 230_000))
                        put("detailTopWidth", detailTop.width)
                        put("detailTopHeight", detailTop.height)
                        put("detailBottomWidth", detailBottom.width)
                        put("detailBottomHeight", detailBottom.height)
                        put("detailTop", encode(detailTop, 85, 155_000))
                        put("detailBottom", encode(detailBottom, 85, 155_000))
                    }
                    // Mantém o payload abaixo de express.json({ limit: "1mb" }) do servidor.
                    if (body.toString().toByteArray(Charsets.UTF_8).size > 950_000) {
                        throw IOException("Relatório visual excede o limite de envio")
                    }
                    request(sessionToken(app), "/api/laboratorio/browser-source/capture/result", "POST", body)
                } catch (error: Exception) {
                    logError("enviar comparação visual", error)
                } finally {
                    original.recycle()
                    composed.recycle()
                    detailTop.recycle()
                    detailBottom.recycle()
                }
            }
        } catch (error: Exception) {
            original.recycle()
            composed.recycle()
            detailTop.recycle()
            detailBottom.recycle()
            logError("agendar comparação visual", error)
        }
    }

    fun sendDomGeometry(context: Context, requestId: String, sourceId: String, dom: JSONObject) {
        val app = context.applicationContext
        try {
            diagnosticsWorker.execute {
                try {
                    val body = JSONObject().apply {
                        put("id", requestId)
                        put("sourceId", sourceId)
                        put("dom", dom)
                    }
                    if (body.toString().toByteArray(Charsets.UTF_8).size > 35_000) {
                        throw IOException("Geometria diagnóstica muito grande")
                    }
                    request(sessionToken(app), "/api/laboratorio/browser-source/capture/dom", "POST", body)
                } catch (error: Exception) {
                    logError("enviar geometria HTML", error)
                }
            }
        } catch (error: Exception) {
            logError("agendar geometria HTML", error)
        }
    }

    private val allowedCommands = setOf(
        "death-increment", "death-decrement", "death-reset", "rtc-reload",
        "music-toggle", "music-next", "music-previous",
        "music-volume-up", "music-volume-down", "scenario-select"
    )

    data class SubScenario(val id: String, val name: String)
    data class Scenario(
        val id: String,
        val name: String,
        val subScenarioId: String,
        val subScenarios: List<SubScenario> = emptyList()
    )

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
        // A estrutura de cenários pode estar no registro principal ou aninhada.
        // Escolha apenas um objeto que contenha a lista real de subcenários.
        val source = when {
            record.optJSONArray("subScenarios") != null -> record
            settings?.optJSONArray("subScenarios") != null -> settings
            config?.optJSONArray("subScenarios") != null -> config
            data?.optJSONArray("subScenarios") != null -> data
            else -> return null
        }
        val id = record.optString("id", source.optString("id", "")).trim()
        if (id.isBlank()) return null
        val subs = source.optJSONArray("subScenarios") ?: return null
        val current = source.optString("selectedSubScenarioId", "").trim()
        val list = ArrayList<SubScenario>()
        val seen = HashSet<String>()
        for (i in 0 until subs.length()) {
            val item = subs.optJSONObject(i) ?: continue
            val subId = item.optString("id", "").trim()
            if (subId.isBlank() || !seen.add(subId)) continue
            val realName = item.optString("name", "").trim().ifEmpty { subId }
            list.add(SubScenario(subId, realName))
        }
        if (list.isEmpty()) return null
        val selectedId = list.find { it.id == current }?.id ?: list.first().id
        val realScenarioName = source.optString("name", record.optString("name", "")).trim().ifEmpty { id }
        return Scenario(id, realScenarioName, selectedId, list)
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
