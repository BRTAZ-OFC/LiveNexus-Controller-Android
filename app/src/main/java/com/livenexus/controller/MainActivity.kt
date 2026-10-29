package com.livenexus.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("buttons", MODE_PRIVATE) }
    private val cyan = Color.rgb(43, 205, 239)
    private val background = Color.rgb(10, 15, 25)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, stroke: Int = Color.rgb(47, 67, 89)) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(17).toFloat(); setStroke(dp(1), stroke)
    }
    private fun count() = prefs.getInt("button_count", 8).coerceIn(1, 60)
    private val icons = listOf("🎙", "▣", "▤", "▤", "☏", "♫", "⏺", "◉", "↩")
    private val names = listOf("Microfone", "Câmera", "Cena 1", "Cena 2", "Chat", "Música", "Gravar", "Mutar", "Voltar")
    private fun name(i: Int) = prefs.getString("button_$i", names.getOrElse(i) { "Botão ${i + 1}" }) ?: "Botão ${i + 1}"
    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }
    private lateinit var status: TextView
    private fun updateStatus() { status.text = if (Settings.canDrawOverlays(this)) "● Sobreposição autorizada" else "○ Autorize a sobreposição" }
    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(22), dp(16), dp(24)); setBackgroundColor(background)
        }
        root.addView(text("☰   LiveNexus Controller", 23f).apply { setTypeface(null, Typeface.BOLD) })
        status = text("", 13f, cyan).apply { setPadding(0, dp(7), 0, dp(18)) }
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun action(label: String, click: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = shape(Color.rgb(24, 39, 59), cyan); setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-1, dp(47)).apply { bottomMargin = dp(9) })
        }
        action("Autorizar sobreposição") {
            if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        action("Ativar painel flutuante") {
            if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, OverlayService::class.java))
            else Toast.makeText(this, "Autorize a sobreposição primeiro", Toast.LENGTH_SHORT).show()
        }
        action("Desativar controlador") { stopService(Intent(this, OverlayService::class.java)) }
        root.addView(actions)
        root.addView(text("MEUS CONTROLES", 13f, cyan).apply { setPadding(0, dp(15), 0, dp(12)) })
        val grid = GridLayout(this).apply { columnCount = 3 }
        val total = count()
        for (i in 0 until total) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = shape(Color.rgb(19, 29, 45))
                setOnClickListener {
                    if (name(i).equals("Música", true)) showPlayer()
                    else Toast.makeText(this@MainActivity, "Configure a ação: ${name(i)}", Toast.LENGTH_SHORT).show()
                }
                setOnLongClickListener { editLabel(i); true }
            }
            tile.addView(text(icons.getOrElse(i) { "◆" }, 29f, cyan).apply { gravity = Gravity.CENTER })
            tile.addView(text(name(i), 12f).apply { gravity = Gravity.CENTER; maxLines = 2 })
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0; height = dp(108); columnSpec = GridLayout.spec(i % 3, 1f)
                rowSpec = GridLayout.spec(i / 3); setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        val add = TextView(this).apply {
            text = "＋\nAdicionar"; gravity = Gravity.CENTER; textSize = 15f; setTextColor(cyan)
            background = shape(Color.rgb(16, 30, 48), cyan)
            setOnClickListener {
                if (count() >= 60) Toast.makeText(this@MainActivity, "Limite de 60 botões", Toast.LENGTH_SHORT).show()
                else { prefs.edit().putInt("button_count", count() + 1).apply(); render() }
            }
        }
        grid.addView(add, GridLayout.LayoutParams().apply {
            width = 0; height = dp(108); columnSpec = GridLayout.spec(total % 3, 1f)
            rowSpec = GridLayout.spec(total / 3); setMargins(dp(3), dp(3), dp(3), dp(3))
        })
        root.addView(grid)
        root.addView(text("Segure uma tecla para renomear. As ações serão conectadas ao LiveNexus após a autenticação.", 12f, Color.LTGRAY).apply { setPadding(0, dp(18), 0, 0) })
        setContentView(ScrollView(this).apply { addView(root) })
        updateStatus()
    }
    private fun editLabel(i: Int) {
        val field = EditText(this).apply { setSingleLine(true); setText(name(i)); selectAll() }
        val holder = FrameLayout(this).apply { setPadding(dp(16), 0, dp(16), 0); addView(field) }
        AlertDialog.Builder(this).setTitle("Editar botão").setView(holder)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val value = field.text.toString().trim()
                if (value.length in 1..30) { prefs.edit().putString("button_$i", value).apply(); render() }
                else Toast.makeText(this, "Use de 1 a 30 caracteres", Toast.LENGTH_SHORT).show()
            }.show()
    }
    private fun showPlayer() {
        val view = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        view.addView(text("♫  Nenhuma música conectada", 17f))
        view.addView(text("◀       ▶       ▶▶       🔊", 22f, cyan).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20)) })
        view.addView(text("Prévia do mini player. Os comandos serão habilitados após a integração autenticada com o LiveNexus.", 13f))
        AlertDialog.Builder(this).setTitle("LiveNexus • Player").setView(view).setPositiveButton("Fechar", null).show()
    }
}
