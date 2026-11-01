package com.livenexus.controller

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Editor local de captura integrado diretamente na aba Captura da MainActivity.
 * Redireciona para MainActivity (aba Captura) para unificar no layout principal.
 */
class ScreenCaptureEditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("target_page", 1)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }
}
