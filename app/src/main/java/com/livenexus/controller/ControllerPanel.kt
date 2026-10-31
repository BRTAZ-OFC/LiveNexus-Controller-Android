package com.livenexus.controller

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Estado de visibilidade compartilhado pelo Activity e pelo serviço no mesmo processo. */
object ControllerRuntime {
    @Volatile var foreground = false
    @Volatile var signedIn = false
    val commands = Executors.newSingleThreadExecutor()
}

/** A mesma árvore de controles e as mesmas ações nos dois modos de apresentação. */
class ControllerPanel(context: Context, private val overlay: Boolean) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("buttons", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = mutableListOf<AlertDialog>()
    private val cyan = NeonTheme.cyan
    private var player = false
    private var disposed = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun kind(index: Int) = prefs.getString("control_type_$index", "Cenário").orEmpty()
    private fun count() = prefs.getInt("scenario_count", 0).coerceIn(0, 57)
    private fun error(message: String) { if (!disposed) Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    private fun backgroundTile() = NeonTheme.card()
    init { orientation = VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(4)); render() }

    private fun task(work: () -> Unit) {
        ControllerRuntime.commands.execute {
            try { work() } catch (e: Exception) { main.post { error(e.message ?: "Falha ao executar comando.") } }
        }
    }
    private fun command(action: String) = task { LiveNexusApi.sendCommand(action) }
    private fun button(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; NeonTheme.styleLabel(this)
        setPadding(dp(8), dp(10), dp(8), dp(10)); background = backgroundTile()
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
        setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { target.alpha = 0.65f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { target.alpha = 1f }
            }; false
        }
    }
    private fun show(builder: AlertDialog.Builder) {
        if (disposed) return
        val dialog = builder.create()
        if (overlay) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
        dialog.window?.setBackgroundDrawable(NeonTheme.card(true))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(NeonTheme.cyan)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(NeonTheme.cyan)
    }
    private fun render() {
        if (disposed) return
        removeAllViews()
        if (player) { renderPlayer(); return }
        val grid = GridLayout(context).apply { columnCount = 3 }
        fun tile(label: String, configure: (() -> Unit)? = null, action: () -> Unit) {
            val position = grid.childCount
            val violet = position % 2 == 1
            grid.addView(button(label.substringAfter('\n'), action).apply {
                background = NeonTheme.card(violet, label.endsWith("Adicionar"))
                textSize = 14f
                maxLines = 3
                setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setPadding(dp(6), dp(18), dp(6), dp(22))
                val icon = NeonIcon(label.substringBefore('\n'), violet).apply { setBounds(0, 0, dp(44), dp(44)) }
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(10)
                if (configure != null) setOnLongClickListener { configure(); true }
            }, GridLayout.LayoutParams().apply {
                width = 0; height = dp(138); columnSpec = GridLayout.spec(position % 3, 1f)
                rowSpec = GridLayout.spec(position / 3); setMargins(dp(3), dp(4), dp(3), dp(4))
            })
        }
        tile("♫\nMúsica") { player = true; render() }
        tile("▣\nCâmera") { error("Câmera ainda não integrada.") }
        tile("↻\nReload RTC") { command("rtc-reload") }
        for (index in 0 until count()) {
            val type = kind(index)
            val icon = when (type) { "Contador +1" -> "+"; "Contador -1" -> "−"; "Zerar contador" -> "⟲"; "Chat" -> "💬"; else -> "◆" }
            val label = prefs.getString("scenario_$index", type).orEmpty()
            tile("$icon\n$label", { configure(index) }) { execute(index) }
        }
        tile("＋\nAdicionar") { addControl() }
        addView(grid, LayoutParams(-1, -2))
    }
    private fun renderPlayer() {
        val controls = listOf("▶ / ❚❚  Play / Pause" to "music-toggle", "◀  Música anterior" to "music-previous",
            "▶▶  Próxima música" to "music-next", "＋  Aumentar volume" to "music-volume-up", "−  Diminuir volume" to "music-volume-down")
        addView(TextView(context).apply {
            text = "MÚSICA"; textSize = 17f; setTextColor(NeonTheme.cyan)
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(16))
        })
        controls.forEachIndexed { index, (label, action) ->
            val control = button(label) { command(action) }.apply {
                background = NeonTheme.card(index % 2 == 1)
                setPadding(dp(12), dp(8), dp(12), dp(16))
            }
            if (action.startsWith("music-volume-")) {
                var pressed = false
                var pending = false
                fun send() {
                    if (pending || disposed) return
                    pending = true
                    ControllerRuntime.commands.execute {
                        try { LiveNexusApi.sendCommand(action) }
                        catch (e: Exception) { main.post { error(e.message ?: "Falha no volume.") } }
                        finally { main.post { pending = false } }
                    }
                }
                val repeat = object : Runnable {
                    override fun run() { if (pressed && !disposed) { send(); main.postDelayed(this, 220) } }
                }
                control.setOnTouchListener { target, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { pressed = true; target.alpha = 0.65f; target.parent.requestDisallowInterceptTouchEvent(true); send(); main.postDelayed(repeat, 350); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressed = false; target.alpha = 1f; main.removeCallbacks(repeat); target.parent.requestDisallowInterceptTouchEvent(false); true }
                        else -> true
                    }
                }
            }
            addView(control, LayoutParams(-1, dp(70)).apply { bottomMargin = dp(8) })
        }
        addView(button("Voltar ao painel") { player = false; render() }, LayoutParams(-1, dp(52)))
    }
    private fun execute(index: Int) {
        when (kind(index)) {
            "Cenário" -> {
                val parent = prefs.getString("control_record_$index", "").orEmpty()
                val sub = prefs.getString("control_subscenario_$index", "").orEmpty()
                if (parent.isBlank() || sub.isBlank()) configure(index)
                else task { LiveNexusApi.selectSubscenario(parent, sub) }
            }
            "Contador +1", "Teste contador +1" -> command("death-increment")
            "Contador -1" -> command("death-decrement")
            "Zerar contador" -> command("death-reset")
            "Overlay" -> {
                val id = prefs.getString("control_record_$index", "").orEmpty()
                val user = prefs.getString("control_user_$index", "").orEmpty()
                if (id.isBlank() || user.isBlank()) configure(index)
                else try {
                    val uri = Uri.parse(LiveNexusApi.baseUrl + "/overlay/receiver/" + Uri.encode(id)).buildUpon().appendQueryParameter("user_id", user).build()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { error(e.message ?: "Não foi possível abrir o Receiver.") }
            }
            else -> error("Ação ainda não integrada: ${kind(index)}")
        }
    }
    private fun addControl() {
        val options = arrayOf("Cenário", "Overlay", "LivePix", "Contador +1", "Contador -1", "Zerar contador", "Chat")
        show(AlertDialog.Builder(context).setTitle("Adicionar controle").setItems(options) { _, selected ->
            val index = count()
            if (index >= 57) error("Limite de 60 controles.")
            else {
                prefs.edit().putString("control_type_$index", options[selected]).putString("scenario_$index", options[selected])
                    .remove("control_record_$index").remove("control_subscenario_$index").remove("control_user_$index")
                    .putInt("scenario_count", index + 1).apply()
                render()
                if (selected < 2) configure(index)
            }
        }.setNegativeButton("Cancelar", null))
    }
    private fun configure(index: Int) {
        val type = kind(index)
        if (type != "Cenário" && type != "Overlay") {
            val field = EditText(context).apply { setSingleLine(); setText(prefs.getString("scenario_$index", type)) }
            show(AlertDialog.Builder(context).setTitle("Nome do botão").setView(field).setPositiveButton("Salvar") { _, _ ->
                val label = field.text.toString().trim()
                if (label.length !in 1..30) error("Use de 1 a 30 caracteres.")
                else { prefs.edit().putString("scenario_$index", label).apply(); render() }
            }.setNegativeButton("Cancelar", null)); return
        }
        task {
            val data = if (type == "Cenário") LiveNexusApi.scenarios() else LiveNexusApi.overlays()
            val records = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            main.post {
                if (!disposed) {
                    if (records.isEmpty()) error("Nenhum registro encontrado para esta conta.")
                    else show(AlertDialog.Builder(context).setTitle("Selecionar $type")
                        .setItems(records.map { label(it) }.toTypedArray()) { _, position ->
                            val record = records[position]
                            if (record.optString("id").isBlank()) error("Registro sem identificador.")
                            else if (type == "Cenário") selectSubscenario(index, record)
                            else { save(index, record, null); render() }
                        }.setNegativeButton("Cancelar", null))
                }
            }
        }
    }
    private fun label(record: JSONObject) = record.optString("name").ifBlank { record.optString("nome").ifBlank { record.optString("id") } }
    private fun selectSubscenario(index: Int, parent: JSONObject) {
        val data = parent.optJSONArray("subScenarios")
        val subs = (0 until (data?.length() ?: 0)).mapNotNull { data?.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
        if (subs.isEmpty()) { error("Este cenário não possui subcenários salvos."); return }
        show(AlertDialog.Builder(context).setTitle("Subcenário de ${label(parent)}")
            .setItems(subs.map { label(it) }.toTypedArray()) { _, position -> save(index, parent, subs[position]); render() }
            .setNegativeButton("Cancelar", null))
    }
    private fun save(index: Int, parent: JSONObject, sub: JSONObject?) {
        prefs.edit().putString("control_record_$index", parent.optString("id"))
            .putString("control_subscenario_$index", sub?.optString("id").orEmpty())
            .putString("control_user_$index", parent.optString("user_id"))
            .putString("scenario_$index", label(sub ?: parent)).apply()
    }
    fun dispose() {
        disposed = true
        main.removeCallbacksAndMessages(null)
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
    }
}
