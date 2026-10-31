package com.livenexus.controller

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var scenesList: LinearLayout
    private lateinit var sceneStatus: TextView
    private data class Scene(val id: String, var name: String, val sources: MutableList<String>)
    private val scenes = mutableListOf<Scene>()
    private var selectedSceneId = ""
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
        content.addView(label("CENAS E FONTES", 17f).apply {
            setTypeface(null, Typeface.BOLD)
        })
        sceneStatus = label("", 13f)
        content.addView(sceneStatus)
        scenesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(scenesList)
        content.addView(control("Adicionar cena") { editSceneName(null) })
        content.addView(control("Renomear cena") {
            scenes.find { it.id == selectedSceneId }?.let { editSceneName(it) }
        })
        content.addView(control("Adicionar fonte: captura de tela") {
            scenes.find { it.id == selectedSceneId }?.let {
                if (!it.sources.contains("screen")) {
                    it.sources.add("screen")
                    persistScenes()
                    showScenes()
                } else Toast.makeText(this, "Fonte já adicionada.", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(control("Remover cena") { removeScene() })
        loadScenes()
        showScenes()
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

    private fun loadScenes() {
        scenes.clear()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val saved = JSONArray(prefs.getString("capture_scenes", "[]"))
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank() || scenes.any { it.id == id }) continue
                val sources = mutableListOf<String>()
                val list = item.optJSONArray("sources")
                for (j in 0 until (list?.length() ?: 0)) {
                    if (list?.optString(j) == "screen" && !sources.contains("screen")) sources.add("screen")
                }
                scenes.add(Scene(id, name, sources))
            }
        } catch (_: org.json.JSONException) {
            Toast.makeText(this, "Não foi possível carregar cenas.", Toast.LENGTH_LONG).show()
        }
        if (scenes.isEmpty()) scenes.add(Scene("default", "Cena 1", mutableListOf("screen")))
        val savedId = prefs.getString("active_capture_scene", "")
        selectedSceneId = scenes.find { it.id == savedId }?.id ?: scenes.first().id
    }

    private fun persistScenes() {
        val array = JSONArray()
        scenes.forEach { scene ->
            array.put(JSONObject().put("id", scene.id).put("name", scene.name)
                .put("sources", JSONArray(scene.sources)))
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("capture_scenes", array.toString())
            .putString("active_capture_scene", selectedSceneId)
            .apply()
    }

    private fun showScenes() {
        scenesList.removeAllViews()
        scenes.forEach { scene ->
            scenesList.addView(control(
                (if (scene.id == selectedSceneId) "● " else "○ ") + scene.name +
                    (if ("screen" in scene.sources) "  |  Tela" else "  |  Sem fontes")
            ) {
                selectedSceneId = scene.id
                persistScenes()
                showScenes()
            })
        }
        sceneStatus.text = "Cena selecionada: " +
            (scenes.find { it.id == selectedSceneId }?.name ?: "Nenhuma") +
            " (configuração local, sem composição de vídeo)"
    }

    private fun editSceneName(scene: Scene?) {
        val field = EditText(this).apply {
            setSingleLine(true)
            setText(scene?.name ?: "Nova cena")
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(if (scene == null) "Criar cena" else "Renomear cena")
            .setView(field)
            .setPositiveButton("Salvar") { _, _ ->
                val name = field.text.toString().trim()
                if (name.length !in 1..50) {
                    Toast.makeText(this, "Nome entre 1 e 50 caracteres.", Toast.LENGTH_LONG).show()
                } else {
                    if (scene == null) {
                        val created = Scene(java.util.UUID.randomUUID().toString(), name, mutableListOf())
                        scenes.add(created)
                        selectedSceneId = created.id
                    } else scene.name = name
                    persistScenes()
                    showScenes()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun removeScene() {
        if (scenes.size <= 1) {
            Toast.makeText(this, "Mantenha ao menos uma cena.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remover cena selecionada?")
            .setPositiveButton("Remover") { _, _ ->
                scenes.removeAll { it.id == selectedSceneId }
                selectedSceneId = scenes.first().id
                persistScenes()
                showScenes()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
