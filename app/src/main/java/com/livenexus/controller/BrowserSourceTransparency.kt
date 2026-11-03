package com.livenexus.controller

import android.graphics.Color
import android.util.Log
import android.webkit.WebView

/**
 * A transparência de uma WebView não remove o background HTML definido pela página.
 * Aplica transparência somente ao fundo estrutural de HTML e BODY;
 * elementos internos (cards, texto, alertas, imagens) permanecem intactos.
 */
internal object BrowserSourceTransparency {
    private const val TAG = "BrowserSourceTransparency"

    private val transparentPageScript = """
        (function() {
            var doc = document;
            var root = doc.documentElement;
            if (!root) return;
            var id = 'livenexus-browser-source-alpha';
            var style = doc.getElementById(id);
            if (!style) {
                style = doc.createElement('style');
                style.id = id;
                // Apenas o fundo estrutural; não apagar imagens ou fundos de widgets em #root.
                style.textContent = 'html, body { background-color: transparent !important; background-image: none !important; }';
                (doc.head || root).appendChild(style);
            }
            [root, doc.body].forEach(function(element) {
                if (element) {
                    element.style.setProperty('background-color', 'transparent', 'important');
                    element.style.setProperty('background-image', 'none', 'important');
                }
            });
        })();
    """.trimIndent()

    /** Deve ser chamada na thread principal, após o carregamento da página. */
    fun apply(webView: WebView, onApplied: () -> Unit = {}) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        try {
            webView.evaluateJavascript(transparentPageScript) {
                onApplied()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível aplicar transparência ao Browser Source", error)
            onApplied()
        }
    }
}
