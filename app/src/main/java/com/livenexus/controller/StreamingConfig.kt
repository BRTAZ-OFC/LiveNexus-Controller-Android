package com.livenexus.controller

/** Configuração de servidores RTMP/RTMPS para transmissão direta, sem OAuth. */
object StreamingConfig {

    // Endereço base da sua hospedagem própria do LiveNexus
    const val DEFAULT_SERVER_URL = "https://livenexus.acq.lat"

    // =========================================================================
    // SERVIDORES RTMP / RTMPS PADRÃO
    // =========================================================================
    const val RTMP_YOUTUBE = "rtmp://a.rtmp.youtube.com/live2"
    const val RTMPS_YOUTUBE = "rtmps://a.rtmps.youtube.com:443/live2"
    const val RTMP_TWITCH = "rtmp://live.twitch.tv/app/"
    const val RTMPS_TWITCH = "rtmps://live.twitch.tv:443/app/"
    const val RTMP_KICK = "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app/"
}
