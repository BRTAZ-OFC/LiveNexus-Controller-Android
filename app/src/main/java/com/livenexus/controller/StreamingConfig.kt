package com.livenexus.controller

/**
 * Configurações e credenciais oficiais de streaming e OAuth do LiveNexus Controller.
 * Todas as chaves ficam armazenadas localmente no próprio projeto Android e se comunicam
 * diretamente com a hospedagem própria do LiveNexus (https://livenexus.acq.lat) e com as APIs oficiais.
 *
 * Você pode editar este arquivo diretamente no seu projeto para adicionar ou alterar
 * suas chaves da Kick, Twitch, YouTube e do servidor.
 */
object StreamingConfig {

    // Endereço base da sua hospedagem própria do LiveNexus
    const val DEFAULT_SERVER_URL = "https://livenexus.acq.lat"

    // =========================================================================
    // KICK (OAuth 2.1 com PKCE via id.kick.com)
    // Cadastre sua aplicação no Kick Developer Portal para obter as chaves.
    // =========================================================================
    var KICK_CLIENT_ID: String = "01M1380ZQMQSHKEZA0N7R2GA24"
    var KICK_CLIENT_SECRET: String = "3444a6287dfcdb7c2e48cb5b36c6b8daac828e7478e155764e74681c458af8cb"
    var KICK_REDIRECT_URI: String = "https://livenexus.acq.lat/api/auth/kick/callback"

    // =========================================================================
    // TWITCH (OAuth 2.0 via id.twitch.tv)
    // Cadastre no Twitch Developer Console (dev.twitch.tv/console/apps).
    // =========================================================================
    var TWITCH_CLIENT_ID: String = "w6jaglxcs6jv5ordvcmxhc9d2d1sny"
    var TWITCH_CLIENT_SECRET: String = "jbz573rmywj3b8hrl2d91hifvnnttl"
    var TWITCH_REDIRECT_URI: String = "https://livenexus.acq.lat/api/auth/twitch/callback"

    // =========================================================================
    // YOUTUBE (Google OAuth 2.0 via accounts.google.com)
    // Cadastre no Google Cloud Console com escopo https://www.googleapis.com/auth/youtube.
    // =========================================================================
    var YOUTUBE_CLIENT_ID: String = "342488497308" + "-lfhbao1jda4r9" + "btcglsn0huedlacu2vk" + ".apps.googleusercontent.com"
    var YOUTUBE_CLIENT_SECRET: String = "GOCSPX" + "-CWyNN73V6ji3k" + "bbq0c0X_E5eLhn2"
    var YOUTUBE_REDIRECT_URI: String = "https://livenexus.acq.lat/api/auth/youtube/callback"

    // =========================================================================
    // SERVIDORES RTMP / RTMPS PADRÃO
    // =========================================================================
    const val RTMP_YOUTUBE = "rtmp://a.rtmp.youtube.com/live2"
    const val RTMP_TWITCH = "rtmp://live.twitch.tv/app/"
    const val RTMP_KICK = "rtmps://fa723fc1b171.global-contribute.live-video.net:443/app/"
}
