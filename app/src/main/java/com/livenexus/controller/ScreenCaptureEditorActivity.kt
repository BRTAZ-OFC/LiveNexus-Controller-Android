package com.livenexus.controller

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast

/**
 * Editor local de captura. Não inclui transmissão, áudio nem salvamento de vídeo.
 * A prévia representa a tela inteira autorizada pelo usuário.
 */
class ScreenCaptureEditorActivity : Activity() {
    companion object {
        private const val CAPTURE_REQUEST = 7820
        private const val PREFS = "livenexus_capture_settings"
        private const val KEY_RESOLUTION = "max_dimension"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var resolution: Spinner
    private var activeUi = false
    private val options = arrayOf("480p (econômico)", "720p (padrão)", "1080p (alta resolução)")
    private val sizes = intArrayOf(854, 1280, 1920)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
    }
    private fun control(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = NeonTheme.card(true)
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NeonTheme.ink
        window.navigationBarColor = NeonTheme.ink
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NeonTheme.backdrop()
            setPadding(dp(14), dp(12), dp(14), dp(16))
        }
        root.addView(NeonHeader(this), LinearLayout.LayoutParams(-1, dp(80)))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("EDITOR DE CAPTURA", 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(8))
        })
        content.addView(label("Prévia local da tela autorizada. Nada é transmitido para a internet.", 12f))
        preview = ImageView(this).apply {
            setBackgroundColor(Color.rgb(6, 8, 24))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Prévia da captura de tela"
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(260)).apply {
            topMargin = dp(12)
            bottomMargin = dp(10)
        })
        status = label("Captura desligada.", 13f)
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(label("Resolução da captura", 15f).apply {
            setTypeface(null, Typeface.BOLD)
        })
        resolution = Spinner(this)
        resolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val settings = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = settings.getInt(KEY_RESOLUTION, 1280)
        resolution.setSelection(sizes.indexOf(saved).takeIf { it >= 0 } ?: 1)
        content.addView(resolution, LinearLayout.LayoutParams(-1, dp(54)))
        content.addView(label("A mudança de resolução vale para a próxima captura.", 12f).apply {
            setPadding(0, 0, 0, dp(15))
        })
        content.addView(control("Iniciar captura") { askPermission() },
            LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(9) })
        content.addView(control("Parar captura") {
            stopService(Intent(this, ScreenCaptureService::class.java))
            status.text = "Encerrando captura..."
            preview.setImageDrawable(null)
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(9) })
        content.addView(control("Voltar ao controlador") { finish() },
            LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(label(
            "A prévia pode repetir a própria tela do editor enquanto ele estiver visível. " +
                "Abra outro aplicativo para observar a captura de seu conteúdo.", 12f
        ).apply { setPadding(0, dp(12), 0, 0) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun askPermission() {
        if (ScreenCaptureService.isCapturing) {
            Toast.makeText(this, "Pare a captura atual antes de iniciar outra.", Toast.LENGTH_LONG).show()
            return
        }
        val size = sizes[resolution.selectedItemPosition.coerceIn(0, sizes.lastIndex)]
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RESOLUTION, size).apply()
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST)
        } catch (error: Exception) {
            status.text = "Falha ao solicitar captura: ${error.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_REQUEST) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Captura não autorizada."
            return
        }
        try {
            val size = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_RESOLUTION, 1280)
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_MAX_DIMENSION, size)
            }
            startForegroundService(intent)
            status.text = "Iniciando captura..."
        } catch (error: Exception) {
            status.text = "Falha ao iniciar: ${error.message}"
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (!activeUi) return
            val running = ScreenCaptureService.isCapturing
            status.text = when {
                running -> "Captura local ativa — sem transmissão."
                ScreenCaptureService.lastError != null -> "Erro: ${ScreenCaptureService.lastError}"
                else -> "Captura desligada."
            }
            if (running) {
                val frame = ScreenCaptureService.previewFrame
                if (frame != null) preview.setImageBitmap(frame)
            } else preview.setImageDrawable(null)
            ui.postDelayed(this, 850L)
        }
    }

    override fun onResume() {
        super.onResume()
        activeUi = true
        ui.post(refresh)
    }

    override fun onPause() {
        activeUi = false
        ui.removeCallbacks(refresh)
        super.onPause()
    }
}
