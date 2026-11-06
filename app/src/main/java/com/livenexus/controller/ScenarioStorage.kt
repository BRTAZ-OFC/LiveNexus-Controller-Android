package com.livenexus.controller

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Persistência local do cenário que o usuário salvou explicitamente no editor.
 * Arquivo privado do aplicativo, com gravação atômica para não perder o último
 * cenário válido se o Android encerrar o processo durante a escrita.
 */
internal object ScenarioStorage {
    private const val TAG = "LiveNEXUSScenario"
    private const val FILE_NAME = "livenexus_editor_scenario.json"
    private const val VERSION = 1
    private const val MAX_SIZE_BYTES = 2 * 1024 * 1024

    data class SavedScene(
        val orientation: String,
        val elements: List<NativeTemplate.CanvasElement>
    )

    fun save(
        context: Context,
        orientation: String,
        elements: List<NativeTemplate.CanvasElement>
    ): Boolean {
        return try {
            val items = JSONArray()
            for (element in elements) {
                checkGeometry(element)
                if (element.id.isBlank() || element.type.isBlank() || element.title.isBlank()) {
                    throw IllegalArgumentException("Elemento do cenário inválido")
                }
                if (element.url.length > 2048) {
                    throw IllegalArgumentException("URL do elemento excede o limite de tamanho")
                }
                items.put(JSONObject().apply {
                    put("id", element.id)
                    put("type", element.type)
                    put("title", element.title)
                    put("x", element.x.toDouble())
                    put("y", element.y.toDouble())
                    put("width", element.width.toDouble())
                    put("height", element.height.toDouble())
                    put("isSelected", element.isSelected)
                    put("aspectRatio", element.aspectRatio)
                    put("url", element.url)
                })
            }
            val json = JSONObject().apply {
                put("version", VERSION)
                put("orientation", orientation)
                put("elements", items)
            }.toString().toByteArray(Charsets.UTF_8)
            if (json.size > MAX_SIZE_BYTES) {
                throw IllegalArgumentException("Cenário excede o tamanho permitido para gravação local")
            }

            val file = AtomicFile(File(context.filesDir, FILE_NAME))
            val output = file.startWrite()
            try {
                output.write(json)
                file.finishWrite(output)
                true
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao salvar cenário no armazenamento interno", error)
            false
        }
    }

    fun load(context: Context): SavedScene? {
        val file = AtomicFile(File(context.filesDir, FILE_NAME))
        val raw = try {
            file.openRead().use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_SIZE_BYTES) {
                    throw IllegalStateException("Arquivo de cenário inválido ou grande demais")
                }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (_: FileNotFoundException) {
            return null
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao ler o cenário salvo", error)
            return null
        }

        return try {
            val document = JSONObject(raw)
            if (document.getInt("version") != VERSION) {
                throw IllegalArgumentException("Versão do cenário não suportada")
            }
            val orientation = document.getString("orientation")
            if (orientation != "Horizontal (16:9)" && orientation != "Vertical (9:16)") {
                throw IllegalArgumentException("Orientação do cenário inválida")
            }
            val items = document.getJSONArray("elements")
            val elements = ArrayList<NativeTemplate.CanvasElement>(items.length())
            val ids = HashSet<String>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val id = item.getString("id")
                val type = item.getString("type")
                val title = item.getString("title")
                val url = item.optString("url", "")
                if (id.isBlank() || !ids.add(id) || type.isBlank() || title.isBlank() || url.length > 2048) {
                    throw IllegalArgumentException("Elemento de cenário inválido")
                }
                val element = NativeTemplate.CanvasElement(
                    id = id,
                    type = type,
                    title = title,
                    x = item.getDouble("x").toFloat(),
                    y = item.getDouble("y").toFloat(),
                    width = item.getDouble("width").toFloat(),
                    height = item.getDouble("height").toFloat(),
                    isSelected = item.optBoolean("isSelected", false),
                    aspectRatio = item.optString("aspectRatio", "16:9"),
                    url = url
                )
                checkGeometry(element)
                elements.add(element)
            }
            // Garante uma única seleção visual quando o editor for reaberto.
            val selected = elements.firstOrNull { it.isSelected }?.id
            elements.forEach { it.isSelected = it.id == selected }
            SavedScene(orientation, elements)
        } catch (error: Exception) {
            Log.e(TAG, "Cenário salvo está inválido; mantendo o arquivo para recuperação", error)
            null
        }
    }

    private fun checkGeometry(element: NativeTemplate.CanvasElement) {
        val values = floatArrayOf(element.x, element.y, element.width, element.height)
        if (values.any { !it.isFinite() } ||
            element.x !in 0f..1f || element.y !in 0f..1f ||
            element.width <= 0f || element.height <= 0f ||
            element.width > 1f || element.height > 1f ||
            element.x + element.width > 1.001f ||
            element.y + element.height > 1.001f
        ) {
            throw IllegalArgumentException("Geometria do elemento inválida")
        }
    }
}
