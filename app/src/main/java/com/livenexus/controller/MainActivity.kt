package com.livenexus.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
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
    private fun count() = prefs.getInt("scenario_count", 0).coerceIn(0, 57)
    private fun controlType(i: Int) = prefs.getString("control_type_$i", "Cenário") ?: "Cenário"
    private fun name(i: Int) = if (i < 3) listOf("Música", "Câmera", "Reload RTC")[i]
        else prefs.getString("scenario_${i - 3}", "${controlType(i - 3)} ${i - 2}") ?: "${controlType(i - 3)} ${i - 2}"
    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }
    private lateinit var status: TextView
    private fun updateStatus() { status.text = if (Settings.canDrawOverlays(this)) "● ON" else "○ OFF" }
    private val purple = Color.rgb(162, 95, 255)
    private val red = Color.rgb(245, 65, 105)
    private var page = 0
    private fun accent(i: Int) = if (i >= 2) purple else cyan
    private fun tileStyle(accent: Int): android.graphics.drawable.Drawable {
        val outer = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(45, 65, 89), Color.rgb(9, 16, 27))).apply {
            cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(58, 83, 112))
        }
        val inner = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(22, 36, 54), Color.rgb(9, 16, 27))).apply {
            cornerRadius = dp(15).toFloat(); setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        return LayerDrawable(arrayOf(outer, InsetDrawable(inner, dp(5), dp(5), dp(5), dp(7))))
    }
    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(17), dp(17), dp(17), dp(17)) }
        header.addView(text("☰", 29f, Color.LTGRAY).apply {
            setOnClickListener { showOptions() }
        }, LinearLayout.LayoutParams(dp(46), dp(55)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text("LiveNexus Controller", 21f).apply { setTypeface(null, Typeface.BOLD) })
        heading.addView(text("Painel ativo", 13f, Color.rgb(44, 210, 185)))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        status = text("●", 13f, cyan).apply { gravity = Gravity.CENTER; background = shape(Color.rgb(20, 31, 48)) }
        header.addView(status, LinearLayout.LayoutParams(dp(52), dp(42)))
        root.addView(header)
        val content = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(10), dp(13), dp(16)) }
        when (page) {
            0 -> {
                val grid = GridLayout(this).apply { columnCount = 3 }
                val total = 3 + count()
                val w = ((resources.displayMetrics.widthPixels - dp(32)) / 3) - dp(6)
                for (i in 0 until total) {
                    val color = accent(i)
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = tileStyle(color)
                        elevation = dp(5).toFloat(); isClickable = true
                        setOnClickListener {
                            if (i == 0) showPlayer()
                            else Toast.makeText(this@MainActivity, "Ação ainda não conectada: ${name(i)}", Toast.LENGTH_SHORT).show()
                        }
                        setOnLongClickListener { if (i >= 3) editLabel(i); true }
                    }
                    val icon = listOf("♫", "▣", "↻").getOrElse(i) { "▱" }
                    tile.addView(text(icon, 41f, color).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(65)))
                    tile.addView(text(name(i), 13f).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); maxLines = 2 })
                    grid.addView(tile, GridLayout.LayoutParams().apply {
                        width = w; height = w + dp(12); columnSpec = GridLayout.spec(i % 3)
                        rowSpec = GridLayout.spec(i / 3); setMargins(dp(3), dp(5), dp(3), dp(5))
                    })
                }
                val add = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = tileStyle(cyan)
                    setOnClickListener {
                        showAddMenu()
                    }
                }
                add.addView(text("+", 49f, cyan).apply { gravity = Gravity.CENTER })
                add.addView(text("Adicionar botão", 12f, cyan).apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) })
                grid.addView(add, GridLayout.LayoutParams().apply {
                    width = w; height = w + dp(12); columnSpec = GridLayout.spec(total % 3)
                    rowSpec = GridLayout.spec(total / 3); setMargins(dp(3), dp(5), dp(3), dp(5))
                })
                body.addView(grid)
            }
            1 -> {
                body.addView(text("PERFIS", 19f, cyan).apply { setPadding(dp(8), dp(15), 0, dp(20)) })
                body.addView(text("Os perfis serão vinculados à conta LiveNexus quando a autenticação estiver integrada.", 15f))
            }
            else -> {
                body.addView(text("CONFIGURAÇÕES", 19f, cyan).apply { setPadding(dp(8), dp(15), 0, dp(20)) })
                fun setting(label: String, onTap: () -> Unit) {
                    body.addView(text(label, 16f).apply {
                        gravity = Gravity.CENTER; background = tileStyle(cyan); setOnClickListener { onTap() }
                    }, LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(12) })
                }
                setting("Autorizar sobreposição") {
                    if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    else Toast.makeText(this, "Sobreposição já autorizada", Toast.LENGTH_SHORT).show()
                }
                setting("Ativar painel flutuante") {
                    if (Settings.canDrawOverlays(this)) startForegroundService(Intent(this, OverlayService::class.java))
                    else Toast.makeText(this, "Autorize a sobreposição primeiro", Toast.LENGTH_SHORT).show()
                }
                setting("Desativar controlador") { stopService(Intent(this, OverlayService::class.java)) }
            }
        }
        content.addView(body)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(6), dp(10), dp(6), dp(12))
            setBackgroundColor(Color.rgb(12, 21, 32))
        }
        listOf("◇\nSobreposição", "◎\nPerfis", "⚙\nConfigurações").forEachIndexed { index, label ->
            nav.addView(text(label, 13f, if (page == index) cyan else Color.GRAY).apply {
                gravity = Gravity.CENTER; setTypeface(null, if (page == index) Typeface.BOLD else Typeface.NORMAL)
                setOnClickListener { page = index; render() }
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(nav)
        setContentView(root)
        updateStatus()
    }
    private fun showOptions() {
        AlertDialog.Builder(this).setTitle("LiveNexus Controller")
            .setItems(arrayOf("Sobreposição", "Perfis", "Configurações")) { _, which -> page = which; render() }.show()
    }
    private fun showAddMenu() {
        AlertDialog.Builder(this).setTitle("Adicionar controle")
            .setItems(arrayOf("Cenário", "Overlay", "LivePix", "Contador +1")) { _, selected ->
                if (count() >= 57) Toast.makeText(this, "Limite de 60 controles", Toast.LENGTH_SHORT).show()
                else {
                    prefs.edit().putString("control_type_${count()}", arrayOf("Cenário", "Overlay", "LivePix", "Contador +1")[selected])
                        .putInt("scenario_count", count() + 1).apply()
                    render()
                }
            }.setNegativeButton("Cancelar", null).show()
    }
    private fun editLabel(i: Int) {
        val field = EditText(this).apply { setSingleLine(true); setText(name(i)); selectAll() }
        val holder = FrameLayout(this).apply { setPadding(dp(16), 0, dp(16), 0); addView(field) }
        AlertDialog.Builder(this).setTitle("Editar botão").setView(holder)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val value = field.text.toString().trim()
                if (value.length in 1..30) { prefs.edit().putString("scenario_${i - 3}", value).apply(); render() }
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
