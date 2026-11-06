package com.livenexus.controller

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Atalhos locais do Controller, separados por sessão autenticada.
 * Guarda somente IDs de cenários/subcenários; nomes são lidos da API real.
 * Não altera o JSON dos cenários do editor nem o banco do LiveNEXUS.
 */
internal object SceneShortcutStore {
    private const val TAG = "LiveNEXUSSceneShortcuts"
    private const val PREFS_NAME = "livenexus_scene_shortcuts"
    private const val FORMAT_VERSION = 1

    data class Assignment(val scenarioId: String, val subcenarioId: String)

    private fun sessionKey(context: Context): String? {
        val token = LocalSession(context.applicationContext).read()
            ?.takeIf { it.isNotBlank() } ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val encoded = buildString(hash.size * 2) {
            for (part in hash) {
                val byteValue = part.toInt() and 0xff
                append(hex[byteValue ushr 4])
                append(hex[byteValue and 0x0f])
            }
        }
        return "shortcuts_" + encoded
    }

    /** null = ainda não existem atalhos configurados nesta sessão. */
    fun load(context: Context): List<Assignment?>? {
        return try {
            val key = sessionKey(context) ?: return null
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null) ?: return null
            val data = JSONObject(raw)
            if (data.getInt("version") != FORMAT_VERSION) return null
            val items = data.getJSONArray("slots")
            val result = ArrayList<Assignment?>(items.length())
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i)
                if (item == null) {
                    result.add(null)
                    continue
                }
                val scenarioId = item.optString("scenarioId", "").trim()
                val subcenarioId = item.optString("subcenarioId", "").trim()
                if (scenarioId.isBlank() || subcenarioId.isBlank()) {
                    throw IllegalArgumentException("Atalho com ID inválido")
                }
                result.add(Assignment(scenarioId, subcenarioId))
            }
            result
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível ler os atalhos: " + error.javaClass.simpleName)
            null
        }
    }

    fun save(context: Context, slots: List<Assignment?>): Boolean {
        return try {
            val key = sessionKey(context) ?: return false
            val array = JSONArray()
            for (slot in slots) {
                if (slot == null) {
                    array.put(JSONObject.NULL)
                } else {
                    require(slot.scenarioId.isNotBlank() && slot.subcenarioId.isNotBlank())
                    array.put(JSONObject()
                        .put("scenarioId", slot.scenarioId)
                        .put("subcenarioId", slot.subcenarioId))
                }
            }
            val raw = JSONObject()
                .put("version", FORMAT_VERSION)
                .put("slots", array)
                .toString()
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(key, raw).commit()
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao salvar atalhos locais", error)
            false
        }
    }
}
