package com.livenexus.controller

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cliente HTTP exclusivo das rotas de autenticação já existentes no LiveNEXUS.
 * Usuário e senha são validados pelo backend, que consulta usuario.json.
 * Este arquivo não contém código do servidor nem acessa o JSON diretamente.
 */
internal class LiveNexusAuth {
    private val origin = "https://livenexus.acq.lat"

    class InvalidCredentials : IOException("Usuário ou senha incorretos.")
    class InvalidSession : IOException("Sessão inválida.")
    class HttpFailure(val code: Int) : IOException("HTTP $code")

    fun userPassword(user: String, password: String): String {
        val data = request(
            "/api/auth/login",
            "POST",
            JSONObject().put("user", user).put("password", password)
        )
        if (!data.optBoolean("ok")) throw InvalidCredentials()
        return tokenFrom(data)
    }

    fun startExternal(provider: String, state: String, challenge: String): Uri {
        require(provider == "google" || provider == "steam")
        val data = request(
            "/api/auth/mobile/start", "POST",
            JSONObject()
                .put("provider", provider)
                .put("state", state)
                .put("challenge", challenge)
        )
        if (!data.optBoolean("ok")) throw IOException("Login externo indisponível.")
        val relative = data.optString("url")
        val uri = Uri.parse(relative)
        val requestId = uri.getQueryParameter("mobile_request")
        if (uri.scheme != null || uri.host != null ||
            uri.path != "/api/auth/$provider" ||
            uri.queryParameterNames != setOf("mobile_request") ||
            requestId == null || !Regex("[A-Za-z0-9_-]{20,128}").matches(requestId)
        ) {
            throw IOException("O servidor retornou uma URL de login inválida.")
        }
        return Uri.parse(origin + relative)
    }

    fun exchange(code: String, state: String, verifier: String): String {
        val data = request(
            "/api/auth/mobile/exchange", "POST",
            JSONObject()
                .put("code", code)
                .put("state", state)
                .put("verifier", verifier)
        )
        if (!data.optBoolean("ok")) throw InvalidSession()
        return tokenFrom(data)
    }

    fun validateSession(token: String): Boolean {
        val data = request("/api/auth/me", "GET", null, token)
        return data.optBoolean("ok") &&
            !data.optJSONObject("user")?.optString("user_id").isNullOrBlank()
    }

    private fun tokenFrom(data: JSONObject): String {
        val token = data.optString("token", "")
        if (token.isBlank() || token == "null") throw InvalidSession()
        return token
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
        token: String? = null
    ): JSONObject {
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(body.toString())
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw HttpFailure(code)
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            return JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }
}

/** Estado de uma autorização externa pendente, independente da Activity. */
internal class PendingMobileAuth(context: Context) {
    private val prefs = context.getSharedPreferences("livenexus_pending_auth", Context.MODE_PRIVATE)

    fun save(state: String, verifier: String): Boolean =
        prefs.edit()
            .putString("state", state)
            .putString("verifier", verifier)
            .putLong("started", System.currentTimeMillis())
            .commit()

    fun takeVerifier(state: String): String? {
        val expected = prefs.getString("state", null) ?: return null
        val created = prefs.getLong("started", 0)
        val age = System.currentTimeMillis() - created
        if (!MessageDigest.isEqual(
                expected.toByteArray(Charsets.US_ASCII),
                state.toByteArray(Charsets.US_ASCII)
            ) || age < 0 || age > 10 * 60 * 1000L
        ) {
            return null
        }
        val verifier = prefs.getString("verifier", null)
        prefs.edit().clear().commit()
        return verifier
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}

/**
 * Sessão no armazenamento PRIVADO do app, cifrada com AndroidKeyStore.
 * Não é armazenamento temporário do navegador nem requer API externa adicional.
 */
internal class LocalSession(context: Context) {
    private val prefs = context.getSharedPreferences("livenexus_session", Context.MODE_PRIVATE)
    private val alias = "livenexus_controller_aes_gcm"

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keystore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(token: String): Boolean {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return prefs.edit().putString(
            "token", Base64.encodeToString(payload, Base64.NO_WRAP)
        ).commit()
    }

    fun read(): String? {
        val encoded = prefs.getString("token", null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= 28) throw IOException("Sessão armazenada inválida.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, payload.copyOfRange(0, 12))
            )
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove("token").commit()
    }
}

/** Prova PKCE exigida pelo fluxo móvel existente. */
internal object MobilePkce {
    private val random = SecureRandom()

    fun newSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
