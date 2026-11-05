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
    @Volatile var lastProjectionResultCode: Int = 0
    @Volatile var lastProjectionData: Intent? = null
    val commands = Executors.newSingleThreadExecutor()
}

/** Painel de controle com Abas de Transmissão & Áudio e Ações, usado tanto no app quanto na sobreposição flutuante. */
class ControllerPanel(context: Context, private val isOverlay: Boolean) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("buttons", Context.MODE_PRIVATE)
    private val capturePrefs = context.getSharedPreferences("livenexus_capture_settings", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val dialogs = mutableListOf<AlertDialog>()
    private var activeTab = if (isOverlay && (ControllerRuntime.isTransmitting || ScreenCaptureService.isLiveStreaming)) 0 else prefs.getInt("active_tab", 0)
    private var player = false
    private var adding = false
    private var disposed = false

    // Timer para atualizar o cronômetro da transmissão ao vivo em tempo real
    private val liveTimerRunnable = object : Runnable {
        override fun run() {
            if (!disposed && activeTab == 0 && (ControllerRuntime.isTransmitting || ScreenCaptureService.isLiveStreaming)) {
                updateLiveTimerText()
                main.postDelayed(this, 1000L)
            }
        }
    }
    private var liveTimerView: TextView? = null

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
        if (isOverlay) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
        dialog.window?.setBackgroundDrawable(NeonTheme.card(true))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(NeonTheme.cyan)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(NeonTheme.cyan)
    }

    private fun findMainActivity(ctx: Context?): MainActivity? {
        var c = ctx
        while (c is android.content.ContextWrapper) {
            if (c is MainActivity) return c
            c = c.baseContext
        }
        return null
    }

    private fun styleForType(type: String): Triple<String, String, Int> = when (type) {
        "Iniciar Live" -> Triple("((•))", "camera", NeonTheme.green)
        "Encerrar Live" -> Triple("⬛", "minus", NeonTheme.red)
        "Overlay" -> Triple("🖼", "overlay", NeonTheme.blue)
        "LivePix" -> Triple("💎", "livepix", NeonTheme.purple)
        "Contador +1", "Teste contador +1" -> Triple("+", "plus", NeonTheme.green)
        "Contador -1" -> Triple("−", "minus", NeonTheme.red)
        "Zerar contador" -> Triple("⟲", "zerar", NeonTheme.violet)
        "Chat" -> Triple("💬", "livepix", NeonTheme.purple)
        else -> Triple("▱", "cenario", NeonTheme.cyan)
    }

    private fun formatElapsedTime(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun updateLiveTimerText() {
        val view = liveTimerView ?: return
        val isLive = ControllerRuntime.isTransmitting || ScreenCaptureService.isLiveStreaming
        if (isLive) {
            val start = if (ControllerRuntime.transmissionStartTime > 0) ControllerRuntime.transmissionStartTime else System.currentTimeMillis()
            val elapsed = System.currentTimeMillis() - start
            view.text = "● AO VIVO • ${formatElapsedTime(elapsed)}"
        } else {
            view.text = "○ Parado"
        }
    }

    private fun render() {
        if (disposed) return
        main.removeCallbacks(liveTimerRunnable)
        liveTimerView = null
        removeAllViews()

        if (player) {
            renderPlayer()
            return
        }

        val isLandscape = !isOverlay &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // 1. BARRA DE NAVEGAÇÃO DE ABAS NO TOPO (Transmissão & Áudio vs Ações & Botões)
        renderTabBar(isLandscape)

        // 2. CONTEÚDO DA ABA SELECIONADA
        if (activeTab == 0) {
            renderLiveAndAudioTab(isLandscape)
            if (ControllerRuntime.isTransmitting || ScreenCaptureService.isLiveStreaming) {
                main.post(liveTimerRunnable)
            }
        } else {
            renderActionsTab(isLandscape)
        }
    }

    /** Barra de abas elegante e destacada para alternar entre Live/Áudio e o painel de Ações. */
    private fun renderTabBar(isLandscape: Boolean) {
        val tabRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }

        val isLive = ControllerRuntime.isTransmitting || ScreenCaptureService.isLiveStreaming
        val liveBadgeLabel = if (isLive) "🔴 Live & Áudio ●" else "🔴 Live & Áudio"

        val tab0 = TextView(context).apply {
            text = liveBadgeLabel
            textSize = if (isOverlay) 12f else 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (activeTab == 0) Color.WHITE else Color.rgb(180, 190, 225))
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = if (activeTab == 0) {
                NeonTheme.tileCard(if (isLive) "minus" else "camera")
            } else {
                NeonTheme.card(violet = true)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (activeTab != 0) {
                    activeTab = 0
                    prefs.edit().putInt("active_tab", 0).apply()
                    render()
                }
            }
        }

        val tab1 = TextView(context).apply {
            text = "🎮 Ações & Botões"
            textSize = if (isOverlay) 12f else 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (activeTab == 1) Color.WHITE else Color.rgb(180, 190, 225))
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = if (activeTab == 1) {
                NeonTheme.tileCard("cenario")
            } else {
                NeonTheme.card(violet = false)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (activeTab != 1) {
                    activeTab = 1
                    prefs.edit().putInt("active_tab", 1).apply()
                    render()
                }
            }
        }

        val tabHeight = dp(if (isLandscape) 38 else 42)
        tabRow.addView(tab0, LayoutParams(0, tabHeight, 1f).apply { rightMargin = dp(4) })
        tabRow.addView(tab1, LayoutParams(0, tabHeight, 1f).apply { leftMargin = dp(4) })

        addView(tabRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
    }

    /** ABA 0: CONTROLE COMPLETO DE TRANSMISSÃO AO VIVO E ÁUDIO (MICROFONE + INTERNO + SUPRESSÃO). */
    private fun renderLiveAndAudioTab(isLandscape: Boolean) {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(2), 0, dp(2), dp(4))
        }

        val isLive = ControllerRuntime.isTransmitting || ScreenCaptureService.isLiveStreaming

        // === 1. CARD PRINCIPAL DA TRANSMISSÃO AO VIVO ===
        val liveCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = NeonTheme.actionPanelBackground()
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusView = TextView(context).apply {
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            if (isLive) {
                val start = if (ControllerRuntime.transmissionStartTime > 0) ControllerRuntime.transmissionStartTime else System.currentTimeMillis()
                val elapsed = System.currentTimeMillis() - start
                text = "● AO VIVO • ${formatElapsedTime(elapsed)}"
                setTextColor(Color.WHITE)
                background = NeonTheme.tileCard("minus")
            } else {
                text = "○ Transmissão Parada"
                setTextColor(Color.rgb(180, 195, 230))
                background = NeonTheme.card()
            }
        }
        liveTimerView = statusView
        headerRow.addView(statusView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        liveCard.addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        // Resumo das plataformas conectadas / configuradas
        val platforms = if (isLive) ScreenCaptureService.activePlatforms else StreamingConfig.getSelectedPlatformsSummary(context)
        val targetsDesc = if (platforms.isNotEmpty()) {
            "Canais: " + platforms.joinToString(", ")
        } else {
            "Nenhuma chave RTMP salva (configure no app)"
        }
        val targetsLabel = TextView(context).apply {
            text = targetsDesc
            textSize = 11f
            setTextColor(if (platforms.isNotEmpty()) NeonTheme.cyan else Color.rgb(235, 120, 140))
            setPadding(0, 0, 0, dp(8))
        }
        liveCard.addView(targetsLabel)

        // BOTÃO DE AÇÃO: INICIAR OU ENCERRAR TRANSMISSÃO
        if (isLive) {
            val stopBtn = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                background = NeonTheme.stopTransmitButtonBackground()
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val act = findMainActivity(context)
                    if (act != null) {
                        act.stopLiveTransmission()
                    } else {
                        StreamingConfig.stopLiveStream(context)
                        Toast.makeText(context, "Transmissão encerrada.", Toast.LENGTH_SHORT).show()
                    }
                    render()
                }
            }
            val stopIcon = TextView(context).apply {
                text = "⬛"
                textSize = 14f
                setTextColor(Color.rgb(255, 64, 92))
                setPadding(0, 0, dp(8), 0)
            }
            val stopLabel = TextView(context).apply {
                text = "ENCERRAR TRANSMISSÃO"
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            stopBtn.addView(stopIcon)
            stopBtn.addView(stopLabel)
            liveCard.addView(stopBtn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            val startBtn = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                background = NeonTheme.transmitButtonBackground()
                setPadding(dp(14), dp(11), dp(14), dp(11))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val act = findMainActivity(context)
                    if (act != null) {
                        act.handleStartTransmissionClick()
                        render()
                    } else {
                        // Na sobreposição, voltar à Activity somente para permissões
                        // e contagem; ela se minimizará após iniciar o serviço.
                        try {
                            context.startActivity(Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra("auto_start_stream", true)
                            })
                        } catch (error: Exception) {
                            Toast.makeText(context, "Não foi possível abrir o início da live: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            val startIcon = TextView(context).apply {
                text = "🔴"
                textSize = 14f
                setPadding(0, 0, dp(8), 0)
            }
            val startLabel = TextView(context).apply {
                text = "INICIAR TRANSMISSÃO AO VIVO"
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            startBtn.addView(startIcon)
            startBtn.addView(startLabel)
            liveCard.addView(startBtn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        container.addView(liveCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // === 2. CONTROLE DE ÁUDIO DO MICROFONE ===
        renderAudioControlCard(
            container = container,
            title = "Microfone (Voz)",
            iconSymbol = "🎙️",
            key = "mic",
            accentColor = NeonTheme.cyan,
            defaultVol = 75
        )

        // === 3. CONTROLE DE ÁUDIO INTERNO (JOGO / SISTEMA) ===
        renderAudioControlCard(
            container = container,
            title = "Áudio Interno (Jogo/Sistema)",
            iconSymbol = "♫",
            key = "internal",
            accentColor = NeonTheme.purple,
            defaultVol = 85
        )

        // === 4. SUPRESSÃO DE RUÍDOS ===
        val nsCard = LinearLayout(context).apply {
            orientation = VERTICAL
            background = NeonTheme.actionPanelBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val nsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nsTextContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        nsTextContainer.addView(TextView(context).apply {
            text = "🔇 Supressão de Ruídos"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        nsTextContainer.addView(TextView(context).apply {
            text = "Filtro passa-alta e noise gate contra chiados no microfone"
            textSize = 10.5f
            setTextColor(Color.rgb(170, 185, 220))
            setPadding(0, dp(2), 0, 0)
        })
        nsRow.addView(nsTextContainer, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val nsEnabled = capturePrefs.getBoolean("noise_suppression_enabled", true)
        val nsSwitch = Switch(context).apply {
            isChecked = nsEnabled
            setOnCheckedChangeListener { _, isChecked ->
                capturePrefs.edit().putBoolean("noise_suppression_enabled", isChecked).apply()
            }
        }
        nsRow.addView(nsSwitch)
        nsCard.addView(nsRow)

        container.addView(nsCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // === 5. BOTÕES DE ATALHO RÁPIDO INFERIORES ===
        val quickActionsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val openAppBtn = TextView(context).apply {
            text = "⚙️ Abrir App LiveNexus"
            textSize = 11.5f
            setTextColor(NeonTheme.cyan)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = NeonTheme.card()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    error(e.message ?: "Não foi possível abrir o app.")
                }
            }
        }
        quickActionsRow.addView(openAppBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        })

        val refreshBtn = TextView(context).apply {
            text = "↻ Atualizar Status"
            textSize = 11.5f
            setTextColor(Color.rgb(200, 210, 245))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = NeonTheme.card(violet = true)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                render()
            }
        }
        quickActionsRow.addView(refreshBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
        })

        container.addView(quickActionsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Constrói um card de controle com toggle Mudo/Ativo, indicador de volume e SeekBar interativo. */
    private fun renderAudioControlCard(
        container: LinearLayout,
        title: String,
        iconSymbol: String,
        key: String,
        accentColor: Int,
        defaultVol: Int
    ) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = NeonTheme.actionPanelBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val isEnabled = capturePrefs.getBoolean("${key}_enabled", true)
        val currentVol = capturePrefs.getInt("${key}_volume", defaultVol).coerceIn(0, 100)

        // Linha 1: Título com ícone, status (Ativo/Mudo) e porcentagem
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = "$iconSymbol  $title"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        topRow.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val volPercentLabel = TextView(context).apply {
            text = if (isEnabled) "$currentVol%" else "MUDO"
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (isEnabled) accentColor else Color.rgb(255, 64, 92))
            setPadding(dp(4), 0, dp(8), 0)
        }
        topRow.addView(volPercentLabel)

        // Botão de alternância Mudo / Ativo
        val muteToggleBtn = TextView(context).apply {
            text = if (isEnabled) "Ativo" else "Mutado"
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (isEnabled) NeonTheme.green else Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = if (isEnabled) NeonTheme.tileCard("plus") else NeonTheme.tileCard("minus")
            isClickable = true
            isFocusable = true
        }
        topRow.addView(muteToggleBtn)
        card.addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        // Linha 2: Controles rápidos (-10%, SeekBar, +10%)
        val sliderRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val minusBtn = TextView(context).apply {
            text = "−10"
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = NeonTheme.card()
            isClickable = true
            isFocusable = true
        }
        sliderRow.addView(minusBtn, LayoutParams(dp(36), dp(30)))

        val seekBar = SeekBar(context).apply {
            max = 100
            progress = currentVol
            setEnabled(isEnabled)
        }
        sliderRow.addView(seekBar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })

        val plusBtn = TextView(context).apply {
            text = "+10"
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = NeonTheme.card()
            isClickable = true
            isFocusable = true
        }
        sliderRow.addView(plusBtn, LayoutParams(dp(36), dp(30)))

        card.addView(sliderRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Listeners
        fun updateUi(enabled: Boolean, vol: Int) {
            capturePrefs.edit()
                .putBoolean("${key}_enabled", enabled)
                .putInt("${key}_volume", vol)
                .apply()
            volPercentLabel.text = if (enabled) "$vol%" else "MUDO"
            volPercentLabel.setTextColor(if (enabled) accentColor else Color.rgb(255, 64, 92))
            muteToggleBtn.text = if (enabled) "Ativo" else "Mutado"
            muteToggleBtn.setTextColor(if (enabled) NeonTheme.green else Color.WHITE)
            muteToggleBtn.background = if (enabled) NeonTheme.tileCard("plus") else NeonTheme.tileCard("minus")
            seekBar.isEnabled = enabled
            if (seekBar.progress != vol) seekBar.progress = vol
        }

        muteToggleBtn.setOnClickListener {
            val nextEnabled = !capturePrefs.getBoolean("${key}_enabled", true)
            val vol = capturePrefs.getInt("${key}_volume", defaultVol)
            updateUi(nextEnabled, vol)
        }

        minusBtn.setOnClickListener {
            val vol = (capturePrefs.getInt("${key}_volume", defaultVol) - 10).coerceIn(0, 100)
            updateUi(true, vol)
        }

        plusBtn.setOnClickListener {
            val vol = (capturePrefs.getInt("${key}_volume", defaultVol) + 10).coerceIn(0, 100)
            updateUi(true, vol)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val enabled = progress > 0
                    updateUi(enabled, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        container.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    /** ABA 1: PAINEL DE BOTÕES E AÇÕES (MÚSICA, CÂMERA, RELOAD RTC, ADICIONAR E CENÁRIOS). */
    private fun renderActionsTab(isLandscape: Boolean) {
        val mainHeight = dp(if (isLandscape) 74 else if (isOverlay) 78 else 84)

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
                createTileView("Música", "♫", "cenario", NeonTheme.cyan, isLandscape || isOverlay) {
                    adding = false
                    player = true
                    render()
                }
            },
            {
                createTileView("Câmera", "▣", "camera", NeonTheme.purple, isLandscape || isOverlay) {
                    if (adding) {
                        adding = false
                        render()
                    }
                    error("Câmera ainda não integrada.")
                }
            },
            {
                createTileView("Reload\nRTC", "↻", "cenario", NeonTheme.cyan, isLandscape || isOverlay) {
                    if (adding) {
                        adding = false
                        render()
                    }
                    command("rtc-reload")
                }
            },
            {
                createTileView("Adicionar", "＋", "add_primary", Color.WHITE, isLandscape || isOverlay) {
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

        // 2. Menu visual "Adicionar Ação" — exibido quando o usuário toca em Adicionar
        val addActionPanel = buildAddActionPanel(isLandscape).apply {
            visibility = if (adding) View.VISIBLE else View.GONE
        }
        if (adding) {
            addView(addActionPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            })
        }

        // 3. Grade de controles adicionados pelo usuário
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
                    compact = isLandscape || isOverlay,
                    configure = {
                        if (adding) {
                            adding = false
                            render()
                        }
                        configure(index)
                    },
                    action = {
                        if (adding) {
                            adding = false
                            render()
                        }
                        execute(index)
                    }
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
            "Iniciar Live",
            "Encerrar Live",
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
                compact = isLandscape || isOverlay
            ) {
                selectAddOption(optionIndex, optionName)
            }
        }

        if (isLandscape) {
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
            val tileHeight = dp(if (isOverlay) 72 else 78)
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
            val chatRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            chatRow.addView(View(context), LayoutParams(0, 1, 0.7f))
            chatRow.addView(
                makeOptionTile(6, options[6]),
                LayoutParams(0, dp(if (isOverlay) 64 else 70), 1.6f)
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
            "Iniciar Live" -> {
                val act = findMainActivity(context)
                if (act != null) {
                    act.handleStartTransmissionClick()
                } else {
                    try {
                        context.startActivity(Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("auto_start_stream", true)
                        })
                    } catch (error: Exception) {
                        Toast.makeText(context, "Não foi possível abrir o início da live: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
                render()
            }
            "Encerrar Live" -> {
                val act = findMainActivity(context)
                if (act != null) {
                    act.stopLiveTransmission()
                } else {
                    StreamingConfig.stopLiveStream(context)
                    Toast.makeText(context, "Transmissão encerrada.", Toast.LENGTH_SHORT).show()
                }
                render()
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
