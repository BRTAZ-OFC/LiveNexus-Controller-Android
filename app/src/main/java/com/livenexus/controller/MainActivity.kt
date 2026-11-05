package com.livenexus.controller

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Aplicativo visual do zero: preserva integralmente a marcação e os estilos
 * do protótipo Canva, sem recriar menus e componentes em Kotlin.
 * Não integra serviços nem injeta APIs no JavaScript.
 */
class MainActivity : Activity() {
    private var preview: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(2, 3, 19)
        window.navigationBarColor = Color.rgb(2, 3, 19)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val browser = WebView(this).apply {
            setBackgroundColor(Color.rgb(2, 3, 19))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = true
            }
            loadUrl("file:///android_asset/livenexus/index.html")
        }
        preview = browser
        setContentView(browser)
    }

    override fun onDestroy() {
        preview?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        preview = null
        super.onDestroy()
    }
}
