package com.livenexus.controller

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.browser.auth.AuthTabIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/**
 * LiveNEXUS Controller: Activity 100% nativa.
 * Login por usuário/senha ou navegador externo (Google/Steam).
 * Após autenticação, exibe uma tela vazia para a próxima etapa.
 */
class MainActivity : ComponentActivity() {
    private val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        if (result.resultCode == AuthTabIntent.RESULT_OK) {
            handleExternalReturn(result.resultUri)
        } else if (result.resultCode == AuthTabIntent.RESULT_CANCELED && !authenticated) {
            status("Autenticação cancelada ou navegador fechado.")
        }
    }
    private val backend = LiveNexusAuth()
    private val executor = Executors.newSingleThreadExecutor()
    private val working = AtomicBoolean(false)
    private val session by lazy { LocalSession(applicationContext) }
    private val pendingAuth by lazy { PendingMobileAuth(applicationContext) }

    private var authenticated = false
    private var userInput: EditText? = null
    private var passwordInput: EditText? = null
    private var statusText: TextView? = null
    private var loginButtons: List<Button> = emptyList()

    private val backgroundColor = Color.rgb(2, 4, 20)
    private fun color(value: String): Int = Color.parseColor(value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val callback = intent?.data
        if (isAuthReturn(callback)) {
            showLogin()
            handleExternalReturn(callback)
        } else {
            restoreExistingSession()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAuthReturn(intent.data)) handleExternalReturn(intent.data)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Mantém a mesma Activity, os campos digitados e a sessão na rotação.
        window.decorView.requestLayout()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        // Voltar ou minimizar não encerra a sessão.
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == NativeTemplate.REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ProjectionState.saveConsent(resultCode, data)
                NativeTemplate.onMediaProjectionResult(true)
            } else {
                NativeTemplate.onMediaProjectionResult(false)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun isAuthReturn(uri: Uri?): Boolean =
        uri?.scheme == "com.livenexus.controller" &&
            uri.host == "auth" && uri.path == "/callback"

    private fun restoreExistingSession() {
        showLoading()
        executor.execute {
            val stored = try {
                session.read()
            } catch (_: Exception) {
                null
            }
            if (stored.isNullOrBlank()) {
                ui { showLogin() }
                return@execute
            }
            try {
                if (backend.validateSession(stored)) {
                    ui { showBlankScreen() }
                } else {
                    session.clear()
                    ui { showLogin() }
                }
            } catch (error: LiveNexusAuth.HttpFailure) {
                if (error.code == 401 || error.code == 403) {
                    session.clear()
                    ui { showLogin() }
                } else {
                    // Sem confirmação por problema no serviço: não apaga o login salvo.
                    ui { showBlankScreen() }
                }
            } catch (_: Exception) {
                // Falha de internet não é uma ordem de logout.
                ui { showBlankScreen() }
            }
        }
    }

    private fun submitCredentials() {
        val username = userInput?.text?.toString()?.trim().orEmpty()
        val password = passwordInput?.text?.toString().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            status("Preencha usuário e senha.")
            return
        }
        if (!working.compareAndSet(false, true)) return
        passwordInput?.text?.clear()
        setBusy(true)
        status("Validando sua conta…")
        executor.execute {
            try {
                val token = backend.userPassword(username, password)
                finishAuthentication(token)
            } catch (error: Exception) {
                ui { status(errorMessage(error)) }
            } finally {
                working.set(false)
                ui { setBusy(false) }
            }
        }
    }

    private fun startExternalLogin(provider: String) {
        if (provider != "google" && provider != "steam") return
        if (!working.compareAndSet(false, true)) return
        setBusy(true)
        status("Abrindo autenticação externa…")
        executor.execute {
            try {
                val verifier = MobilePkce.newSecret()
                val state = MobilePkce.newSecret()
                val authorizationUrl = backend.startExternal(
                    provider, state, MobilePkce.challenge(verifier)
                )
                if (!pendingAuth.save(state, verifier)) {
                    throw IOException("Não foi possível preparar a autenticação externa.")
                }
                ui {
                    try {
                        AuthTabIntent.Builder().build().launch(
                            authTabLauncher, authorizationUrl, "com.livenexus.controller"
                        )
                        status("Conclua a autenticação para retornar ao aplicativo.")
                    } catch (_: Exception) {
                        pendingAuth.clear()
                        status("Não foi possível abrir o navegador.")
                    }
                }
            } catch (error: Exception) {
                ui { status(errorMessage(error)) }
            } finally {
                working.set(false)
                ui { setBusy(false) }
            }
        }
    }

    private fun handleExternalReturn(uri: Uri?) {
        if (!isAuthReturn(uri) || authenticated) return
        val code = uri?.getQueryParameter("code").orEmpty()
        val state = uri?.getQueryParameter("state").orEmpty()
        if (!Regex("[A-Za-z0-9_-]{20,128}").matches(code) ||
            !Regex("[A-Za-z0-9_-]{24,128}").matches(state)
        ) {
            status("Retorno de autenticação inválido.")
            return
        }
        if (!working.compareAndSet(false, true)) return
        setBusy(true)
        status("Confirmando autenticação…")
        executor.execute {
            try {
                val verifier = pendingAuth.takeVerifier(state)
                    ?: throw IOException("Autenticação externa inválida ou expirada.")
                val token = backend.exchange(code, state, verifier)
                finishAuthentication(token)
            } catch (error: Exception) {
                ui { status(errorMessage(error)) }
            } finally {
                working.set(false)
                ui { setBusy(false) }
            }
        }
    }

    private fun finishAuthentication(token: String) {
        if (!backend.validateSession(token)) throw LiveNexusAuth.InvalidSession()
        if (!session.save(token)) {
            throw IOException("Não foi possível guardar a sessão no Android.")
        }
        ui { showBlankScreen() }
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is LiveNexusAuth.InvalidCredentials -> "Usuário ou senha incorretos."
        is LiveNexusAuth.InvalidSession -> "O servidor não confirmou a sessão."
        is UnknownHostException -> "Não foi possível localizar o servidor LiveNEXUS."
        is SocketTimeoutException -> "O servidor demorou a responder. Tente novamente."
        is SSLException -> "Falha na conexão segura com o LiveNEXUS."
        is LiveNexusAuth.HttpFailure ->
            if (error.code == 401 || error.code == 403) {
                "Autenticação recusada pelo servidor."
            } else {
                "Servidor LiveNEXUS indisponível (HTTP " + error.code + ")."
            }
        else -> error.message?.takeIf {
            it == "Autenticação externa inválida ou expirada." ||
                it == "Não foi possível guardar a sessão no Android." ||
                it == "Não foi possível preparar a autenticação externa."
        } ?: "Não foi possível autenticar. Confira a conexão e tente novamente."
    }

    private fun status(value: String) {
        statusText?.text = value
    }

    private fun setBusy(isBusy: Boolean) {
        loginButtons.forEach { it.isEnabled = !isBusy }
    }

    private fun showLoading() {
        val screen = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        val label = TextView(this).apply {
            text = "Verificando sessão…"
            textSize = 13f
            setTextColor(color("#B8C6EA"))
            gravity = Gravity.CENTER
        }
        screen.addView(
            label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(screen)
    }

    private fun showBlankScreen() {
        authenticated = true
        userInput = null
        passwordInput = null
        statusText = null
        loginButtons = emptyList()
        NativeTemplate(this).show()
    }

    private fun showLogin() {
        authenticated = false
        val root = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        root.addView(
            NativeGradientBackground(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        root.addView(
            scroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(20), dp(14), dp(20))
        }
        scroll.addView(
            center, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val panel = NativeLoginPanel().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(23), dp(19), dp(23))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(color("#0E1234"), color("#05081E"), color("#030516"))
            ).apply {
                cornerRadius = 0f
                setStroke(dp(1), color("#8B33FF"))
            }
            elevation = dp(12).toFloat()
        }
        center.addView(
            panel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        panel.addView(brand)
        brand.addView(brandPart("▷", 30f, "#E13AFF"))
        brand.addView(brandPart("Live", 31f, "#F7F9FF"))
        val nexus = brandPart("NEXUS", 31f, "#F600FF")
        nexus.paint.shader = LinearGradient(
            0f, 0f, dp(140).toFloat(), 0f,
            intArrayOf(color("#F600FF"), color("#8C42FF"), color("#00EAFF")),
            null, Shader.TileMode.CLAMP
        )
        brand.addView(nexus)

        val tagline = centeredText(
            "CONTROLE DE TRANSMISSÃO", 8f, "#E3D9FB"
        ).apply {
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.24f
        }
        panel.addView(tagline, row(top = 6))

        val heading = centeredText("ENTRAR NA CONTA", 18f, "#F7F9FF").apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        panel.addView(heading, row(top = 26, bottom = 12))

        userInput = addInput(panel, "Usuário", false)
        passwordInput = addInput(panel, "Senha", true)

        val enter = actionButton("ENTRAR", "#E82CFF", true)
        panel.addView(enter, row(top = 13, height = 43))
        enter.setOnClickListener { submitCredentials() }

        val divider = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        panel.addView(divider, row(top = 18, bottom = 4))
        divider.addView(line(), LinearLayout.LayoutParams(0, dp(1), 1f))
        divider.addView(
            centeredText("OU CONTINUAR COM", 10f, "#B8C6EA"),
            LinearLayout.LayoutParams(dp(146), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        divider.addView(line(), LinearLayout.LayoutParams(0, dp(1), 1f))

        val google = actionButton("Conectar com Google", "#00D9FF", false)
        panel.addView(google, row(top = 11, height = 43))
        google.setOnClickListener { startExternalLogin("google") }

        val steam = actionButton("Conectar com Steam", "#8B33FF", false)
        panel.addView(steam, row(top = 11, height = 43))
        steam.setOnClickListener { startExternalLogin("steam") }
        loginButtons = listOf(enter, google, steam)

        statusText = centeredText("", 11f, "#FFADCA")
        panel.addView(statusText, row(top = 12, height = 20))

        val note = centeredText(
            "Acesso ao aplicativo somente após validação da conta LiveNEXUS.",
            10f, "#B8C6EA"
        )
        panel.addView(note, row(top = 14))
        setContentView(root)
    }

    private fun brandPart(value: String, size: Float, hex: String): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color(hex))
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            includeFontPadding = false
            setSingleLine(true)
        }

    private fun centeredText(value: String, size: Float, hex: String): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color(hex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            gravity = Gravity.CENTER
        }

    private fun row(
        top: Int = 0,
        bottom: Int = 0,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (height > 0) dp(height) else height
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun line(): View = View(this).apply { setBackgroundColor(color("#405280")) }

    private fun fieldBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(color("#09132C"))
            cornerRadius = 0f
            setStroke(dp(1), color(if (focused) "#00EAFF" else "#42518B"))
        }

    private fun addInput(
        panel: LinearLayout, caption: String, secret: Boolean
    ): EditText {
        val label = TextView(this).apply {
            text = caption
            setTextColor(color("#C4D7FF"))
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        panel.addView(label, row(top = 12, bottom = 6))
        val field = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(color("#F6FAFF"))
            setHintTextColor(color("#B8C6EA"))
            background = fieldBackground(false)
            setPadding(dp(12), 0, dp(12), 0)
            if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
                imeOptions = EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        submitCredentials()
                        true
                    } else false
                }
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.LengthFilter(128))
                setAutofillHints(View.AUTOFILL_HINT_USERNAME)
                imeOptions = EditorInfo.IME_ACTION_NEXT
            }
            setOnFocusChangeListener { _, focused ->
                background = fieldBackground(focused)
            }
        }
        panel.addView(field, row(height = 45))
        return field
    }

    private fun actionButton(title: String, border: String, primary: Boolean): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = 0
            minimumHeight = 0
            backgroundTintList = null
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (primary) {
                    intArrayOf(color("#4B009B"), color("#B500DC"), color("#7200C8"))
                } else {
                    intArrayOf(color("#101634"), color("#030716"))
                }
            ).apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), color(border))
            }
        }

    private inner class NativeLoginPanel : LinearLayout(this@MainActivity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = View.MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(
                    minOf(available, dp(390)), View.MeasureSpec.EXACTLY
                ),
                heightMeasureSpec
            )
        }
    }

    /** Fundo de luzes desenhado pelo Canvas nativo, sem imagem nem CSS. */
    private inner class NativeGradientBackground : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(backgroundColor)
            glow(canvas, -0.08f * w, 0.03f * h, 0.55f * w, "#66004A76")
            glow(canvas, 1.08f * w, 0.23f * h, 0.65f * w, "#70004EA0")
            glow(canvas, 0.65f * w, 0.98f * h, 0.55f * w, "#663E006F")
            paint.shader = null
        }

        private fun glow(canvas: Canvas, cx: Float, cy: Float, radius: Float, hex: String) {
            if (radius <= 0f) return
            paint.shader = RadialGradient(
                cx, cy, radius,
                color(hex), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
