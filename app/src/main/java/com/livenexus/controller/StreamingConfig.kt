package com.livenexus.controller

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

data class CaptureLayer(
    val id: String,
    val type: String, // "screen_capture", "text", "image", "browser", "widget"
    var label: String,
    var normX: Float = 0.0f,
    var normY: Float = 0.0f,
    var normW: Float = 1.0f,
    var normH: Float = 1.0f,
    var visible: Boolean = true,
    var aspectRatio: String = "16:9",
    var url: String = ""
)

object ProjectionState {
    @Volatile
    var resultCode: Int = 0
    @Volatile
    var resultData: Intent? = null

    fun hasConsent(): Boolean = resultCode != 0 && resultData != null

    fun saveConsent(code: Int, data: Intent?) {
        resultCode = code
        resultData = data?.let { Intent(it) }
    }

    fun cloneConsentIntent(): Intent? = resultData?.let { Intent(it) }

    fun clear() {
        resultCode = 0
        resultData = null
    }
}

object StreamingConfig {
    const val CAPTURE_PREFS = "livenexus_capture_settings"
    const val AUDIO_PREFS = "livenexus_audio_settings"

    const val RTMP_YOUTUBE = "rtmp://a.rtmp.youtube.com/live2"
    const val RTMPS_YOUTUBE = "rtmps://a.rtmps.youtube.com:443/live2"
    const val RTMPS_TWITCH = "rtmps://live.twitch.tv:443/app"

    const val DEFAULT_TWITCH_URL = "rtmp://live.twitch.tv/app"
    const val DEFAULT_YOUTUBE_URL = "rtmps://a.rtmps.youtube.com:443/live2"
    const val DEFAULT_KICK_URL = "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app"

    val activeSceneLayers = CopyOnWriteArrayList<CaptureLayer>()

    fun buildRtmpTarget(baseUrl: String, streamKey: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanKey = streamKey.trim().trimStart('/')
        return "$cleanBase/$cleanKey"
    }

    fun getCapturePrefs(context: Context) =
        context.getSharedPreferences(CAPTURE_PREFS, Context.MODE_PRIVATE)

    fun getAudioPrefs(context: Context) =
        context.getSharedPreferences(AUDIO_PREFS, Context.MODE_PRIVATE)

    fun loadResolutionLabel(context: Context): String {
        val res = getCapturePrefs(context).getInt("resolution", 1920)
        return if (res <= 1280) "720p" else "1080p"
    }

    fun saveResolutionLabel(context: Context, label: String) {
        val value = if (label.startsWith("720")) 1280 else 1920
        getCapturePrefs(context).edit().putInt("resolution", value).apply()
    }

    fun loadFpsLabel(context: Context): String {
        val fps = getCapturePrefs(context).getInt("fps", 60)
        return if (fps <= 30) "30 FPS" else "60 FPS"
    }

    fun saveFpsLabel(context: Context, label: String) {
        val fps = if (label.startsWith("30")) 30 else 60
        getCapturePrefs(context).edit().putInt("fps", fps).apply()
    }

    fun loadBitrateKbps(context: Context): Int {
        return getCapturePrefs(context).getInt("bitrate", 6000).coerceIn(2000, 10000)
    }

    fun saveBitrateKbps(context: Context, kbps: Int) {
        val clamped = kbps.coerceIn(2000, 10000)
        getCapturePrefs(context).edit().putInt("bitrate", clamped).apply()
    }

    fun loadOrientationLabel(context: Context): String {
        val ratio = getCapturePrefs(context).getString("aspect_ratio", "16:9") ?: "16:9"
        return if (ratio == "9:16") "Vertical (9:16)" else "Horizontal (16:9)"
    }

    fun saveOrientationLabel(context: Context, label: String) {
        val ratio = if (label.contains("9:16") || label.contains("Vertical", ignoreCase = true)) "9:16" else "16:9"
        getCapturePrefs(context).edit().putString("aspect_ratio", ratio).apply()
    }

    fun loadMicEnabled(context: Context): Boolean =
        getAudioPrefs(context).getBoolean("mic_enabled", true)

    fun saveMicEnabled(context: Context, enabled: Boolean) {
        getAudioPrefs(context).edit().putBoolean("mic_enabled", enabled).apply()
    }

    fun loadInternalAudioEnabled(context: Context): Boolean =
        getAudioPrefs(context).getBoolean("internal_enabled", true)

    fun saveInternalAudioEnabled(context: Context, enabled: Boolean) {
        getAudioPrefs(context).edit().putBoolean("internal_enabled", enabled).apply()
    }

    fun loadNoiseSuppressionEnabled(context: Context): Boolean =
        getAudioPrefs(context).getBoolean("noise_suppression", true)

    fun saveNoiseSuppressionEnabled(context: Context, enabled: Boolean) {
        getAudioPrefs(context).edit().putBoolean("noise_suppression", enabled).apply()
    }

    fun loadMicVolume(context: Context): Int =
        getAudioPrefs(context).getInt("mic_volume", 100).coerceIn(0, 100)

    fun saveMicVolume(context: Context, volume: Int) {
        getAudioPrefs(context).edit().putInt("mic_volume", volume.coerceIn(0, 100)).apply()
    }

    fun loadInternalVolume(context: Context): Int =
        getAudioPrefs(context).getInt("internal_volume", 100).coerceIn(0, 100)

    fun saveInternalVolume(context: Context, volume: Int) {
        getAudioPrefs(context).edit().putInt("internal_volume", volume.coerceIn(0, 100)).apply()
    }

    fun isPlatformEnabled(context: Context, platform: String): Boolean {
        val key = "enabled_${platform.lowercase()}"
        return getCapturePrefs(context).getBoolean(key, false)
    }

    fun setPlatformEnabled(context: Context, platform: String, enabled: Boolean) {
        val key = "enabled_${platform.lowercase()}"
        getCapturePrefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun getPlatformUrl(context: Context, platform: String): String {
        val p = platform.lowercase()
        val defaultUrl = when (p) {
            "twitch" -> DEFAULT_TWITCH_URL
            "youtube" -> DEFAULT_YOUTUBE_URL
            "kick" -> DEFAULT_KICK_URL
            else -> DEFAULT_TWITCH_URL
        }
        return getCapturePrefs(context).getString("url_$p", defaultUrl)?.takeIf { it.isNotBlank() } ?: defaultUrl
    }

    fun setPlatformUrl(context: Context, platform: String, url: String) {
        val p = platform.lowercase()
        getCapturePrefs(context).edit().putString("url_$p", url.trim()).apply()
    }

    fun getStreamKey(context: Context, platform: String): String {
        val p = platform.lowercase()
        return getCapturePrefs(context).getString("key_$p", "")?.trim().orEmpty()
    }

    fun setStreamKey(context: Context, platform: String, streamKey: String) {
        val p = platform.lowercase()
        getCapturePrefs(context).edit().putString("key_$p", streamKey.trim()).apply()
    }

    fun getConfiguredPlatformTargets(context: Context): Map<String, String> {
        val targets = linkedMapOf<String, String>()
        val platforms = listOf("Twitch", "YouTube", "Kick")
        for (name in platforms) {
            if (isPlatformEnabled(context, name)) {
                val key = getStreamKey(context, name)
                val url = getPlatformUrl(context, name)
                if (key.isNotBlank() && url.isNotBlank()) {
                    targets[name] = buildRtmpTarget(url, key)
                }
            }
        }
        return targets
    }

    fun getConfiguredStreamTargets(context: Context): List<StreamPublisher.StreamTarget> {
        val targets = mutableListOf<StreamPublisher.StreamTarget>()
        val platforms = listOf("Twitch", "YouTube", "Kick")
        for (name in platforms) {
            if (isPlatformEnabled(context, name)) {
                val key = getStreamKey(context, name)
                val url = getPlatformUrl(context, name)
                if (key.isNotBlank() && url.isNotBlank()) {
                    targets.add(
                        StreamPublisher.StreamTarget(
                            platformName = name,
                            rtmpUrl = url,
                            streamKey = key
                        )
                    )
                }
            }
        }
        return targets
    }

    fun startLiveStream(context: Context): String? {
        if (!ProjectionState.hasConsent()) {
            return "Autorize a captura de tela do Android para iniciar a transmissão."
        }
        val targets = getConfiguredStreamTargets(context)
        if (targets.isEmpty()) {
            return "Ative ao menos uma plataforma e configure a Stream Key em Plataformas."
        }
        val consentIntent = ProjectionState.cloneConsentIntent()
            ?: return "Permissão de captura de tela inválida. Autorize novamente."

        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_STREAM
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, ProjectionState.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, consentIntent)
            putExtra(ScreenCaptureService.EXTRA_PLATFORM_NAMES, targets.map { it.platformName }.toTypedArray())
            putExtra(ScreenCaptureService.EXTRA_PLATFORM_URLS, targets.map { it.rtmpUrl }.toTypedArray())
            putExtra(ScreenCaptureService.EXTRA_PLATFORM_KEYS, targets.map { it.streamKey }.toTypedArray())
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            null
        } catch (e: Exception) {
            "Falha ao iniciar o serviço de transmissão: ${e.message}"
        }
    }

    fun stopLiveStream(context: Context) {
        // Não inicia outro serviço para solicitar parada: encerra o serviço já existente.
        // onDestroy() do ScreenCaptureService libera MediaProjection, encoders e RTMP.
        context.stopService(Intent(context, ScreenCaptureService::class.java))
    }
}
