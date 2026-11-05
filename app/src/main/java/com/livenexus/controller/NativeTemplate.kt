package com.livenexus.controller

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Reinterpretação NATIVA do protótipo LiveNEXUS.
 * Nenhuma WebView, HTML, JavaScript, conexão simulada ou nova integração.
 * Os componentes de transmissão são somente apresentação até serem ligados
 * aos mecanismos reais existentes na aplicação.
 */
internal class NativeTemplate(private val activity: Activity) {
    private val cyan = Color.rgb(0, 234, 255)
    private val pink = Color.rgb(233, 0, 255)
    private val white = Color.rgb(247, 249, 255)
    private val muted = Color.rgb(184, 198, 234)
    private val backdrop = Color.rgb(2, 4, 20)
    private var page = "menu"
    private lateinit var root: LinearLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var navigation: LinearLayout

    private fun dp(n: Int) = (activity.resources.displayMetrics.density * n + 0.5f).toInt()

    private fun gradient(vararg values: Int, stroke: Int = cyan, radius: Int = 12): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, values).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun label(value: String, sp: Float = 12f, strong: Boolean = false, tint: Int = white): TextView =
        TextView(activity).apply {
            text = value
            textSize = sp
            setTextColor(tint)
            gravity = Gravity.CENTER_VERTICAL
            if (strong) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun column(): LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun pad(view: View, x: Int = 10, y: Int = 10) {
        view.setPadding(dp(x), dp(y), dp(x), dp(y))
    }

    private fun add(parent: LinearLayout, view: View, top: Int = 6) {
        val originalHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        parent.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, originalHeight
        ).apply { topMargin = dp(top) })
    }

    private fun card(title: String, accent: Int = pink, setup: (LinearLayout) -> Unit): LinearLayout =
        column().apply {
            background = gradient(Color.rgb(14, 18, 52), Color.rgb(5, 8, 30), stroke = accent)
            pad(this, 12, 12)
            add(this, label(title, 14f, true), 0)
            setup(this)
        }

    private fun button(value: String, active: Boolean = false, action: (() -> Unit)? = null): Button =
        Button(activity).apply {
            text = value
            isAllCaps = false
            textSize = 11f
            minHeight = 0
            minimumHeight = 0
            setTextColor(white)
            backgroundTintList = null
            background = if (active) gradient(
                Color.rgb(75, 0, 155), Color.rgb(181, 0, 220), stroke = pink, radius = 8
            ) else gradient(Color.rgb(16, 22, 52), Color.rgb(3, 7, 22), radius = 8)
            setOnClickListener { action?.invoke() ?: unavailable() }
        }

    private fun choice(parent: LinearLayout, vararg values: String) {
        val group = row()
        values.forEachIndexed { index, value ->
            val b = button(value, index == 0)
            group.addView(b, LinearLayout.LayoutParams(0, dp(43), 1f).apply {
                if (index > 0) leftMargin = dp(5)
            })
        }
        add(parent, group)
    }

    private fun switch(parent: LinearLayout, caption: String) {
        val line = row()
        line.addView(label(caption, 11f), LinearLayout.LayoutParams(0, dp(40), 1f))
        val toggle = Switch(activity).apply {
            isChecked = false
            isEnabled = false
            contentDescription = "$caption: configuração indisponível nesta versão"
        }
        line.addView(toggle)
        add(parent, line, 3)
    }

    private fun slider(parent: LinearLayout, caption: String, progress: Int) {
        add(parent, label(caption, 11f, true, muted))
        add(parent, SeekBar(activity).apply {
            max = 100
            this.progress = progress
            isEnabled = false
        }, 0)
    }

    private fun unavailable() {
        android.widget.Toast.makeText(
            activity, "Interface visual: função não conectada nesta versão.", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun section(title: String): LinearLayout = column().apply {
        add(this, label(title, 18f, true), 3)
    }

    private fun preview(caption: String, height: Int = 185): FrameLayout =
        FrameLayout(activity).apply {
            background = gradient(Color.rgb(15, 25, 72), Color.rgb(3, 7, 25), stroke = cyan, radius = 11)
            val center = column().apply { gravity = Gravity.CENTER }
            center.addView(label("▷ LiveNEXUS", 26f, true), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            center.addView(label(caption, 10f, false, muted))
            addView(center, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
        }

    fun show() {
        root = column().apply { setBackgroundColor(backdrop) }
        val header = row().apply { pad(this, 10, 13) }
        header.addView(label("▷", 24f, true, pink), LinearLayout.LayoutParams(dp(34), dp(42)))
        val branding = column()
        add(branding, label("LiveNEXUS", 23f, true), 0)
        add(branding, label("CONTROLE DE TRANSMISSÃO", 8f, false, muted), 0)
        header.addView(branding, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("SESSÃO ATIVA", 9f, true, cyan))
        add(root, header, 0)
        contentHost = FrameLayout(activity)
        root.addView(contentHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        navigation = row().apply {
            pad(this, 4, 5)
            background = gradient(Color.rgb(4, 9, 32), Color.rgb(4, 9, 32), stroke = cyan, radius = 11)
        }
        add(root, navigation, 0)
        activity.setContentView(root)
        navigate(page)
    }

    private fun navigate(value: String) {
        page = value
        contentHost.removeAllViews()
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val body = column().apply { pad(this, 11, 8) }
        scroll.addView(body)
        contentHost.addView(scroll)
        when (page) {
            "menu" -> menu(body)
            "editor" -> editor(body)
            "encoder" -> encoder(body)
            "controls" -> controls(body)
            "platforms" -> platforms(body)
            "dashboard" -> dashboard(body)
        }
        navigation.removeAllViews()
        listOf(
            "MENU" to "menu", "TRANSMISSÃO" to "controls",
            "AJUSTE ENCODER" to "encoder", "PLATAFORMAS" to "platforms"
        ).forEachIndexed { index, item ->
            navigation.addView(button(item.first, page == item.second) { navigate(item.second) },
                LinearLayout.LayoutParams(0, dp(51), 1f).apply {
                    if (index > 0) leftMargin = dp(3)
                })
        }
    }

    private fun menu(body: LinearLayout) {
        add(body, preview("MINIATURA", 250), 12)
        add(body, card("MENU RÁPIDO") {
            add(it, button("◈ CENÁRIOS / OVERLAYS", true) { navigate("editor") })
            add(it, button("CONTROLES", false) { navigate("controls") })
        }, 12)
        add(body, button("VISÃO GERAL") { navigate("dashboard") }, 10)
    }

    private fun editor(body: LinearLayout) {
        add(body, section("EDITOR DE CENÁRIO / OVERLAY"), 2)
        add(body, card("CENÁRIO / OVERLAY") {
            choice(it, "CENÁRIO", "OVERLAY")
            add(it, preview("LIVE OVERLAY"), 10)
            choice(it, "EDITAR", "VISUALIZAR", "SALVAR")
        }, 8)
        add(body, card("◈ ELEMENTOS", cyan) {
            listOf(
                "Texto     •     Imagem", "Vídeo / Fonte     •     Alertas",
                "Widgets     •     Mídia Social", "Formas     •     Cenas Salvas",
                "Browser Source     •     Webcam"
            ).forEach { item ->
                add(it, button(item), 5)
            }
            choice(it, "Selecionar", "Mover", "Camadas")
        }, 9)
    }

    private fun encoder(body: LinearLayout) {
        add(body, section("AJUSTE ENCODER"))
        add(body, card("VÍDEO") {
            add(it, label("Resolução", 12f, true, cyan))
            choice(it, "720p", "1080p")
            add(it, label("Orientação", 12f, true, cyan))
            choice(it, "16:9", "9:16")
            add(it, label("Taxa de quadros", 12f, true, cyan))
            choice(it, "30 FPS", "60 FPS")
            slider(it, "Bitrate · 2000–10000 Kbps", 50)
            add(it, label("Codec de vídeo", 12f, true, cyan))
            choice(it, "H.264", "H.265", "AV1")
        }, 8)
        add(body, card("ÁUDIO", cyan) {
            switch(it, "Microfone")
            switch(it, "Áudio interno")
            slider(it, "Volume do microfone", 100)
            slider(it, "Volume interno", 100)
        }, 9)
        add(body, button("SALVAR CONFIGURAÇÕES", true), 9)
        add(body, label("Controles visuais. Não alteram o encoder existente.", 10f, false, muted))
    }

    private fun controls(body: LinearLayout) {
        add(body, section("CONTROLES"))
        add(body, card("⌨ HOTKEYS") {
            choice(it, "Cena 1", "Cena 2", "Subcena")
            choice(it, "Iniciar", "Parar", "Reiniciar")
        }, 8)
        add(body, card("TTS (VOZ)", cyan) {
            switch(it, "Ativar TTS")
            slider(it, "Volume da voz", 80)
            add(it, button("LER / PARAR VOZ"))
        }, 9)
        add(body, card("♫ PLAYER (MÍDIA)") {
            add(it, label("Nenhuma mídia carregada", 11f, false, muted))
            choice(it, "Anterior", "Play", "Próxima")
            slider(it, "Volume", 75)
        }, 9)
        add(body, card("BOTS (CHAT)", cyan) {
            switch(it, "Twitch")
            switch(it, "Kick")
            switch(it, "YouTube")
        }, 9)
        add(body, card("INTEGRAÇÕES") {
            add(it, label("Conexões não verificadas", 11f, false, muted))
            add(it, label("OBS · StreamElements · Streamlabs", 11f))
        }, 9)
    }

    private fun platforms(body: LinearLayout) {
        add(body, section("PLATAFORMAS DE TRANSMISSÃO"))
        listOf("TWITCH", "YOUTUBE", "KICK").forEachIndexed { index, name ->
            add(body, card(name, if (index == 2) cyan else pink) {
                add(it, label("Estado: não verificado", 11f, false, muted))
                add(it, label("Configuração e chave de transmissão", 11f))
                add(it, label("As credenciais não são exibidas nesta interface visual.", 10f, false, muted))
                choice(it, "TESTAR", "CONECTAR")
            }, 9)
        }
        add(body, button("ATUALIZAR CONEXÕES"), 10)
        add(body, button("INICIAR TRANSMISSÃO EM TODAS", true), 7)
        add(body, button("ENCERRAR TODAS"), 7)
    }

    private fun dashboard(body: LinearLayout) {
        add(body, section("VISÃO GERAL · LiveNEXUS"))
        add(body, card("EDITOR DE CENÁRIO / OVERLAY") {
            add(it, preview("LIVE OVERLAY"))
            add(it, button("ABRIR EDITOR", true) { navigate("editor") })
        }, 9)
        add(body, card("TELA DE CONTROLE (APK)", cyan) {
            add(it, preview("CENTRAL DE TRANSMISSÃO"))
            add(it, button("ABRIR CONTROLES") { navigate("controls") })
        }, 10)
        add(body, label("Protótipo de interface nativa · integrações não conectadas", 10f, false, muted), 10)
    }
}
