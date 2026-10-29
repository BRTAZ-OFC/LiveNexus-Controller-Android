package com.livenexus.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("buttons", MODE_PRIVATE) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showUi()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = if (Settings.canDrawOverlays(this)) "Sobreposição autorizada" else "Sobreposição pendente"
        }
    }

    private fun showUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(16))
            setBackgroundColor(Color.rgb(16, 21, 31))
        }
        root.addView(TextView(this).apply {
            text = "LiveNexus Controller"
            textSize = 23f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(16))
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Autorizar sobreposição"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Ativar botão flutuante"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                } else {
                    Toast.makeText(this@MainActivity, "Autorize a sobreposição primeiro", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Desativar controlador"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        root.addView(TextView(this).apply {
            text = "Toque e segure um botão para editar seu nome."
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(10))
        })
        val grid = GridLayout(this).apply { columnCount = 2 }
        for (i in 0 until 8) {
            val button = Button(this).apply {
                text = prefs.getString("button_$i", "Botão ${i + 1}")
                isAllCaps = false
                setOnLongClickListener { editLabel(i, this); true }
            }
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(74)
                columnSpec = GridLayout.spec(i % 2, 1f)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        root.addView(grid)
        setContentView(ScrollView(this).apply { addView(root) })
        onResume()
    }

    private fun editLabel(index: Int, button: Button) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(button.text)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(18), 0, dp(18), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Nome do botão ${index + 1}")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotEmpty() && label.length <= 30) {
                    prefs.edit().putString("button_$index", label).apply()
                    button.text = label
                } else {
                    Toast.makeText(this, "Use de 1 a 30 caracteres", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }
}