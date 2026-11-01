package com.livenexus.controller

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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
    @Volatile var isTransmitting = false
    @Volatile var transmissionStartTime = 0L
    val commands = Executors.newSingleThreadExecutor()
}

/** A mesma árvore de controles e as mesmas ações nos dois modos de apresentação. */
class ControllerPanel(context: Context, private val overlay: Boolean) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("buttons", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = mutableListOf<AlertDialog>()
    private var player = false
    private var adding = true
    private var disposed = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun kind(index: Int) = prefs.getString("control_type_$index", "Cenário").orEmpty()
    private fun count() = prefs.getInt("scenario_count", 0).coerceIn(0, 57)
    private fun error(message: String) {
        if (!disposed) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(6))
        render()
    }

    private fun task(work: () -> Unit) {
        ControllerRuntime.commands.execute {
            try {
                work()
            } catch (e: Exception) {
                main.post { error(e.message ?: "Falha ao executar comando.") }
            }
        }
    }

    private fun command(action: String) = task { LiveNexusApi.sendCommand(action) }

    private fun button(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        NeonTheme.styleLabel(this)
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = NeonTheme.card()
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.alpha = 0.68f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.alpha = 1f
            }
            false
        }
    }

    private fun createTileView(
        title: String,
        symbol: String,
        cardStyle: String,
        iconAccent: Int,
        compact: Boolean,
        configure: (() -> Unit)? = null,
        action: () -> Unit
    ): TextView = button(title, action).apply {
        background = NeonTheme.tileCard(cardStyle)
        textSize = if (compact) 11f else 11.5f
        maxLines = 2
        setAutoSizeTextTypeUniformWithConfiguration(
            9, if (compact) 11 else 12, 1, TypedValue.COMPLEX_UNIT_SP
        )
        val verticalPad = dp(if (compact) 7 else 9)
        setPadding(dp(4), verticalPad, dp(4), verticalPad)
        val iconSize = dp(if (compact) 24 else 28)
        val icon = NeonIcon(symbol, iconAccent).apply {
            setBounds(0, 0, iconSize, iconSize)
        }
        setCompoundDrawables(null, icon, null, null)
        compoundDrawablePadding = dp(if (compact) 4 else 6)
        if (configure != null) {
            setOnLongClickListener {
                configure()
                true
            }
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

    private fun styleForType(type: String): Triple<String, String, Int> = when (type) {
        "Overlay" -> Triple("🖼", "overlay", NeonTheme.blue)
        "LivePix" -> Triple("💎", "livepix", NeonTheme.purple)
        "Contador +1", "Teste contador +1" -> Triple("+", "plus", NeonTheme.green)
        "Contador -1" -> Triple("−", "minus", NeonTheme.red)
        "Zerar contador" -> Triple("⟲", "zerar", NeonTheme.violet)
        "Chat" -> Triple("💬", "livepix", NeonTheme.purple)
        else -> Triple("▱", "cenario", NeonTheme.cyan)
    }

    private fun render() {
        if (disposed) return
        removeAllViews()
        if (player) {
            renderPlayer()
            return
        }

        val isLandscape = !overlay &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val mainHeight = dp(if (isLandscape) 74 else if (overlay) 78 else 84)

        // 1. Quatro botões principais na linha superior: Música, Câmera, Reload RTC, Adicionar
        val mainRowWrapper = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        val mainRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }

        val primaryButtons = listOf(
             {
                createTileView("Música", "♫", "cenario", NeonTheme.cyan, isLandscape || overlay) {
                    player = true
                    render()
                }
            },
            {
                createTileView("Câmera", "▣", "camera", NeonTheme.purple, isLandscape || overlay) {
                    error("Câmera ainda não integrada.")
                }
            },
            {
                createTileView("Reload\nRTC", "↻", "cenario", NeonTheme.cyan, isLandscape || overlay) {
                    command("rtc-reload")
                }
            },
            {
                createTileView("Adicionar", "＋", "add_primary", Color.WHITE, isLandscape || overlay) {
                    adding = !adding
                    render()
                }
            }
        )

        primaryButtons.forEachIndexed { idx, factory ->
            val btn = factory()
            val lp = if (isLandscape) {
                LayoutParams(dp(106), mainHeight).apply {
                    if (idx > 0) leftMargin = dp(8)
                }
            } else {
                LayoutParams(0, mainHeight, 1f).apply {
                    if (idx > 0) leftMargin = dp(6)
                }
            }
            mainRow.addView(btn, lp)
        }

        if (isLandscape) {
            mainRowWrapper.addView(mainRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(mainRowWrapper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        } else {
            addView(mainRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        // 2. Menu visual "Adicionar Ação" — exibido somente quando o usuário toca em Adicionar
        if (adding) {
            addView(buildAddActionPanel(isLandscape), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            })
        }

        // 3. Grade de controles adicionados pelo usuário (abaixo dos 4 botões principais)
        val total = count()
        if (total > 0) {
            val columns = when {
                isLandscape -> 5
                else -> 3
            }
            val tileHeight = dp(if (isLandscape) 72 else 82)
            val grid = GridLayout(context).apply {
                columnCount = columns
                alignmentMode = GridLayout.ALIGN_BOUNDS
            }
            for (index in 0 until total) {
                val type = kind(index)
                val (symbol, cardStyle, accentColor) = styleForType(type)
                val label = prefs.getString("scenario_$index", type).orEmpty()
                val tile = createTileView(
                    title = label,
                    symbol = symbol,
                    cardStyle = cardStyle,
                    iconAccent = accentColor,
                    compact = isLandscape || overlay,
                    configure = { configure(index) },
                    action = { execute(index) }
                )
                grid.addView(tile, GridLayout.LayoutParams().apply {
                    width = 0
                    height = tileHeight
                    columnSpec = GridLayout.spec(index % columns, 1f)
                    rowSpec = GridLayout.spec(index / columns)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun buildAddActionPanel(isLandscape: Boolean): View {
        val options = listOf(
            "Cenário",
            "Overlay",
            "LivePix",
            "Contador +1",
            "Contador -1",
            "Zerar contador",
            "Chat"
        )
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            background = NeonTheme.actionPanelBackground()
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(View(context), LayoutParams(dp(28), dp(28)))
        headerRow.addView(TextView(context).apply {
            text = "Adicionar Ação"
            textSize = if (isLandscape) 14f else 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.rgb(185, 175, 220))
            gravity = Gravity.CENTER
            contentDescription = "Fechar menu Adicionar Ação"
            setOnClickListener {
                adding = false
                render()
            }
        }, LayoutParams(dp(28), dp(28)))
        container.addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))

        val divider = View(context).apply {
            setBackgroundColor(0x447B2CBF)
        }
        container.addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        fun makeOptionTile(optionIndex: Int, optionName: String): TextView {
            val (symbol, cardStyle, accent) = styleForType(optionName)
            val iconColor = if (optionName == "Chat" && isLandscape) NeonTheme.cyan else accent
            val tileStyle = if (optionName == "Chat" && isLandscape) "cenario" else cardStyle
            return createTileView(
                title = optionName,
                symbol = symbol,
                cardStyle = tileStyle,
                iconAccent = iconColor,
                compact = isLandscape || overlay
            ) {
                selectAddOption(optionIndex, optionName)
            }
        }

        if (isLandscape) {
            // Modo Horizontal (Paisagem): todas as 7 opções distribuídas na largura em linha única
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tileHeight = dp(68)
            options.forEachIndexed { idx, optionName ->
                row.addView(
                    makeOptionTile(idx, optionName),
                    LayoutParams(0, tileHeight, 1f).apply {
                        if (idx > 0) leftMargin = dp(5)
                    }
                )
            }
            container.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            // Modo Vertical (Retrato): 2 linhas de 3 botões + botão Chat centralizado abaixo
            val tileHeight = dp(if (overlay) 72 else 78)
            for (rowIdx in 0..1) {
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (colIdx in 0..2) {
                    val idx = rowIdx * 3 + colIdx
                    val optionName = options[idx]
                    row.addView(
                        makeOptionTile(idx, optionName),
                        LayoutParams(0, tileHeight, 1f).apply {
                            if (colIdx > 0) leftMargin = dp(6)
                        }
                    )
                }
                container.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                })
            }
            // Linha inferior com "Chat" centralizado, como na imagem de referência
            val chatRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            chatRow.addView(View(context), LayoutParams(0, 1, 0.7f))
            chatRow.addView(
                makeOptionTile(6, options[6]),
                LayoutParams(0, dp(if (overlay) 64 else 70), 1.6f)
            )
            chatRow.addView(View(context), LayoutParams(0, 1, 0.7f))
            container.addView(chatRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        return container
    }

    private fun selectAddOption(selected: Int, optionName: String) {
        val index = count()
        if (index >= 57) {
            error("Limite de 60 controles.")
        } else {
            adding = false
            prefs.edit()
                .putString("control_type_$index", optionName)
                .putString("scenario_$index", optionName)
                .remove("control_record_$index")
                .remove("control_subscenario_$index")
                .remove("control_user_$index")
                .putInt("scenario_count", index + 1)
                .apply()
            render()
            if (selected < 2) configure(index)
        }
    }

    private fun renderPlayer() {
        val controls = listOf(
            "▶ / ❚❚  Play / Pause" to "music-toggle",
            "◀  Música anterior" to "music-previous",
            "▶▶  Próxima música" to "music-next",
            "＋  Aumentar volume" to "music-volume-up",
            "−  Diminuir volume" to "music-volume-down"
        )
        addView(TextView(context).apply {
            text = "MÚSICA"
            textSize = 16f
            setTextColor(NeonTheme.cyan)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })
        controls.forEachIndexed { index, (label, action) ->
            val control = button(label) { command(action) }.apply {
                background = NeonTheme.card(index % 2 == 1)
                setPadding(dp(12), dp(8), dp(12), dp(12))
            }
            if (action.startsWith("music-volume-")) {
                var pressed = false
                var pending = false
                fun send() {
                    if (pending || disposed) return
                    pending = true
                    ControllerRuntime.commands.execute {
                        try {
                            LiveNexusApi.sendCommand(action)
                        } catch (e: Exception) {
                            main.post { error(e.message ?: "Falha no volume.") }
                        } finally {
                            main.post { pending = false }
                        }
                    }
                }
                val repeat = object : Runnable {
                    override fun run() {
                        if (pressed && !disposed) {
                            send()
                            main.postDelayed(this, 220)
                        }
                    }
                }
                control.setOnTouchListener { target, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pressed = true
                            target.alpha = 0.65f
                            target.parent.requestDisallowInterceptTouchEvent(true)
                            send()
                            main.postDelayed(repeat, 350)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            pressed = false
                            target.alpha = 1f
                            main.removeCallbacks(repeat)
                            target.parent.requestDisallowInterceptTouchEvent(false)
                            true
                        }
                        else -> true
                    }
                }
            }
            addView(control, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(7) })
        }
        addView(button("Voltar ao painel") { player = false; render() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
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
                    val uri = Uri.parse(LiveNexusApi.baseUrl + "/overlay/receiver/" + Uri.encode(id))
                        .buildUpon()
                        .appendQueryParameter("user_id", user)
                        .build()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    error(e.message ?: "Não foi possível abrir o Receiver.")
                }
            }
            else -> error("Ação ainda não integrada: ${kind(index)}")
        }
    }

    private fun configure(index: Int) {
        val type = kind(index)
        if (type != "Cenário" && type != "Overlay") {
            val field = EditText(context).apply {
                setSingleLine()
                setText(prefs.getString("scenario_$index", type))
            }
            show(
                AlertDialog.Builder(context)
                    .setTitle("Nome do botão")
                    .setView(field)
                    .setPositiveButton("Salvar") { _, _ ->
                        val label = field.text.toString().trim()
                        if (label.length !in 1..30) error("Use de 1 a 30 caracteres.")
                        else {
                            prefs.edit().putString("scenario_$index", label).apply()
                            render()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
            )
            return
        }
        task {
            val data = if (type == "Cenário") LiveNexusApi.scenarios() else LiveNexusApi.overlays()
            val records = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            main.post {
                if (!disposed) {
                    if (records.isEmpty()) error("Nenhum registro encontrado para esta conta.")
                    else show(
                        AlertDialog.Builder(context)
                            .setTitle("Selecionar $type")
                            .setItems(records.map { label(it) }.toTypedArray()) { _, position ->
                                val record = records[position]
                                if (record.optString("id").isBlank()) error("Registro sem identificador.")
                                else if (type == "Cenário") selectSubscenario(index, record)
                                else {
                                    save(index, record, null)
                                    render()
                                }
                            }
                            .setNegativeButton("Cancelar", null)
                    )
                }
            }
        }
    }

    private fun label(record: JSONObject) =
        record.optString("name").ifBlank { record.optString("nome").ifBlank { record.optString("id") } }

    private fun selectSubscenario(index: Int, parent: JSONObject) {
        val data = parent.optJSONArray("subScenarios")
        val subs = (0 until (data?.length() ?: 0))
            .mapNotNull { data?.optJSONObject(it) }
            .filter { it.optString("id").isNotBlank() }
        if (subs.isEmpty()) {
            error("Este cenário não possui subcenários salvos.")
            return
        }
        show(
            AlertDialog.Builder(context)
                .setTitle("Subcenário de ${label(parent)}")
                .setItems(subs.map { label(it) }.toTypedArray()) { _, position ->
                    save(index, parent, subs[position])
                    render()
                }
                .setNegativeButton("Cancelar", null)
        )
    }

    private fun save(index: Int, parent: JSONObject, sub: JSONObject?) {
        prefs.edit()
            .putString("control_record_$index", parent.optString("id"))
            .putString("control_subscenario_$index", sub?.optString("id").orEmpty())
            .putString("control_user_$index", parent.optString("user_id"))
            .putString("scenario_$index", label(sub ?: parent))
            .apply()
    }

    fun dispose() {
        disposed = true
        main.removeCallbacksAndMessages(null)
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }
}
