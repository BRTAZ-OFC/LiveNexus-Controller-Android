package com.livenexus.controller

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Configuração de servidores RTMP/RTMPS para transmissão direta, sem OAuth. */
object StreamingConfig {

    // Endereço base da sua hospedagem própria do LiveNexus
    const val DEFAULT_SERVER_URL = "https://livenexus.acq.lat"

    // =========================================================================
    // SERVIDORES RTMP / RTMPS PADRÃO
    // =========================================================================
    const val RTMP_YOUTUBE = "rtmps://a.rtmps.youtube.com:443/live2"
    const val RTMPS_YOUTUBE = "rtmps://a.rtmps.youtube.com:443/live2"
    const val RTMP_TWITCH = "rtmp://live.twitch.tv/app/"
    const val RTMPS_TWITCH = "rtmps://live.twitch.tv:443/app/"
    const val RTMP_KICK = "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app/"

    fun isValidStreamKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val trimmed = key.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.equals("null", ignoreCase = true) &&
            !trimmed.equals("undefined", ignoreCase = true) &&
            !trimmed.equals("none", ignoreCase = true)
    }

    fun buildActiveTargetsJson(context: Context): String {
        val prefs = context.getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
        val array = JSONArray()
        if (prefs.getBoolean("stream_youtube", false)) {
            val key = prefs.getString("key_youtube", "")?.trim() ?: ""
            if (isValidStreamKey(key)) {
                array.put(JSONObject().apply {
                    put("platform", "YouTube")
                    put("url", RTMPS_YOUTUBE)
                    put("key", key)
                })
            }
        }
        if (prefs.getBoolean("stream_twitch", false)) {
            val key = prefs.getString("key_twitch", "")?.trim() ?: ""
            if (isValidStreamKey(key)) {
                array.put(JSONObject().apply {
                    put("platform", "Twitch")
                    put("url", RTMP_TWITCH)
                    put("key", key)
                })
            }
        }
        if (prefs.getBoolean("stream_kick", false)) {
            val key = prefs.getString("key_kick", "")?.trim() ?: ""
            if (isValidStreamKey(key)) {
                array.put(JSONObject().apply {
                    put("platform", "Kick")
                    put("url", RTMP_KICK)
                    put("key", key)
                })
            }
        }
        return array.toString()
    }

    fun getSelectedPlatformsSummary(context: Context): List<String> {
        val prefs = context.getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
        val list = mutableListOf<String>()
        if (prefs.getBoolean("stream_youtube", false) && isValidStreamKey(prefs.getString("key_youtube", ""))) {
            val handle = prefs.getString("youtube_username", "")?.trim() ?: ""
            val formatted = if (handle.isNotBlank()) (if (handle.startsWith("@")) handle else "@$handle") else ""
            list.add(if (formatted.isNotBlank()) "YouTube ($formatted)" else "YouTube")
        }
        if (prefs.getBoolean("stream_twitch", false) && isValidStreamKey(prefs.getString("key_twitch", ""))) {
            val handle = prefs.getString("twitch_username", "")?.trim() ?: ""
            list.add(if (handle.isNotBlank()) "Twitch ($handle)" else "Twitch")
        }
        if (prefs.getBoolean("stream_kick", false) && isValidStreamKey(prefs.getString("key_kick", ""))) {
            val handle = prefs.getString("kick_username", "")?.trim() ?: ""
            list.add(if (handle.isNotBlank()) "Kick ($handle)" else "Kick")
        }
        return list
    }

    fun startLiveStream(context: Context): Boolean {
        val targetsJson = buildActiveTargetsJson(context)
        if (targetsJson == "[]") return false
        val prefs = context.getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
        val size = prefs.getInt("max_dimension", 1280)
        val fps = prefs.getInt("capture_fps", 30)
        val bitrate = prefs.getInt("capture_bitrate_kbps", 4500)
        val isVertical = prefs.getString("capture_orientation", "horizontal") == "vertical"

        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_STREAM
            putExtra(ScreenCaptureService.EXTRA_MAX_DIMENSION, size)
            putExtra(ScreenCaptureService.EXTRA_FPS, fps)
            putExtra(ScreenCaptureService.EXTRA_BITRATE_KBPS, bitrate)
            putExtra(ScreenCaptureService.EXTRA_TARGETS_JSON, targetsJson)
            putExtra(ScreenCaptureService.EXTRA_VERTICAL_MODE, isVertical)
        }
        context.startForegroundService(intent)
        ControllerRuntime.isTransmitting = true
        if (ControllerRuntime.transmissionStartTime <= 0L) {
            ControllerRuntime.transmissionStartTime = System.currentTimeMillis()
        }
        OverlayService.refreshVisibility()
        return true
    }

    fun stopLiveStream(context: Context) {
        ControllerRuntime.isTransmitting = false
        ControllerRuntime.transmissionStartTime = 0L
        OverlayService.refreshVisibility()
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_STREAM
        }
        context.startService(intent)
    }
}
