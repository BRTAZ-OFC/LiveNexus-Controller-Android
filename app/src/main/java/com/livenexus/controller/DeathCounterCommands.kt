package com.livenexus.controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Envia somente os comandos existentes do contador de mortes ao LiveNEXUS.
 * Cada toque gera um único POST; não há repetição automática nem consulta periódica.
 * O painel web autenticado consome e aplica a alteração ao contador global.
 */
internal object DeathCounterCommands {
    private const val TAG = "LiveNEXUSDeathControl"
    private const val ENDPOINT = "https://livenexus.acq.lat/api/controller/commands"
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiveNEXUSDeathCommands").apply { isDaemon = true }
    }

    fun send(context: Context, increase: Boolean, onResult: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val delivered = try {
                val token = LocalSession(appContext).read()
                if (token.isNullOrBlank()) {
                    false
                } else {
                    val action = if (increase) "death-increment" else "death-decrement"
                    val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 6000
                        connection.readTimeout = 6000
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        connection.doOutput = true
                        connection.setRequestProperty("Authorization", "Bearer $token")
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.setRequestProperty("Accept", "application/json")
                        val payload = JSONObject().put("action", action).toString()
                        connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                            it.write(payload)
                        }
                        val status = connection.responseCode
                        status in 200..299 && connection.inputStream.use { input ->
                            val response = input.bufferedReader(Charsets.UTF_8).readText()
                            JSONObject(response).optBoolean("ok", false)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            } catch (error: Exception) {
                // Nunca registrar token de sessão nem o corpo da resposta.
                Log.w(TAG, "Falha ao enviar comando do contador de mortes: ${error.javaClass.simpleName}")
                false
            }
            main.post { onResult(delivered) }
        }
    }
}
