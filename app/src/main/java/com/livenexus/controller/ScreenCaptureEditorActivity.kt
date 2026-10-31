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
    private lateinit var layersList: LinearLayout
    private lateinit var canvasEditor: CaptureSceneCanvas
    private var selectedLayerId: String? = null
    private lateinit var sceneStatus: TextView
    private data class Scene(val id: String, var name: String, val sources: MutableList<CaptureLayer>)
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
        canvasEditor = CaptureSceneCanvas(this).apply {
            contentDescription = "Canvas visual de cenas. Arraste a fonte ou redimensione pelo canto."
            onSelection = { id ->
                selectedLayerId = id
                showLayers()
            }
            onChange = { persistScenes(); showLayers() }
        }
        content.addView(canvasEditor, LinearLayout.LayoutParams(-1, dp(270)).apply {
            topMargin = dp(12)
            bottomMargin = dp(10)
        })
        preview = ImageView(this).apply { visibility = View.GONE }
        status = label("Captura desligada.", 13f)
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        content.addView(label("CENAS", 17f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(8))
        })
        sceneStatus = label("", 13f)
        content.addView(sceneStatus)
        scenesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(scenesList)
        val sceneButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun sceneAction(name: String, action: () -> Unit) {
            sceneButtons.addView(control(name, action),
                LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        sceneAction("+ Cena") { editSceneName(null) }
        sceneAction("Renomear") { activeScene()?.let { editSceneName(it) } }
        sceneAction("Excluir") { removeScene() }
        content.addView(sceneButtons)
        content.addView(label("FONTES / CAMADAS", 17f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(15), 0, dp(7))
        })
        layersList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(layersList)
        content.addView(control("+ Adicionar fonte") { chooseSource() },
            LinearLayout.LayoutParams(-1, dp(48)))
        val layerActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun layerAction(name: String, action: () -> Unit) {
            layerActions.addView(control(name, action), LinearLayout.LayoutParams(0, dp(47), 1f))
        }
        layerAction("↑") { reorderLayer(1) }
        layerAction("↓") { reorderLayer(-1) }
        layerAction("Ocultar") { changeLayer("visible") }
        layerAction("Bloquear") { changeLayer("locked") }
        layerAction("Remover") { changeLayer("remove") }
        content.addView(layerActions)
        content.addView(label("Toque na fonte para selecionar. Arraste no canvas para mover; use o canto inferior direito para redimensionar.", 12f).apply {
            setPadding(0, dp(7), 0, dp(14))
        })
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

    private fun activeScene(): Scene? = scenes.find { it.id == selectedSceneId }

    private fun loadScenes() {
        scenes.clear()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val saved = JSONArray(prefs.getString("capture_scenes", "[]"))
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                if (id.isBlank() || name.isBlank() || scenes.any { it.id == id }) continue
                val sources = mutableListOf<CaptureLayer>()
                val list = item.optJSONArray("sources")
                for (j in 0 until (list?.length() ?: 0)) {
                    val raw = list?.opt(j)
                    if (raw is String && raw == "screen") {
                        // Compatibilidade com as cenas criadas no PONTO SEGURO 32.
                        sources.add(CaptureLayer(java.util.UUID.randomUUID().toString(), "screen", "Captura de tela"))
                    } else if (raw is JSONObject) {
                        val type = raw.optString("type")
                        if (type != "screen" && type != "text") continue
                        val sourceId = raw.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                        if (sources.any { it.id == sourceId }) continue
                        sources.add(CaptureLayer(
                            sourceId, type,
                            raw.optString("name").ifBlank { if (type == "screen") "Captura de tela" else "Texto" },
                            raw.optDouble("x", 0.1).toFloat().coerceIn(0f, .92f),
                            raw.optDouble("y", 0.1).toFloat().coerceIn(0f, .92f),
                            raw.optDouble("width", .8).toFloat().coerceIn(.08f, 1f),
                            raw.optDouble("height", .65).toFloat().coerceIn(.08f, 1f),
                            raw.optBoolean("visible", true),
                            raw.optBoolean("locked", false)
                        ).also {
                            it.width = it.width.coerceAtMost(1f - it.x)
                            it.height = it.height.coerceAtMost(1f - it.y)
                        })
                    }
                }
                scenes.add(Scene(id, name, sources))
            }
        } catch (_: org.json.JSONException) {
            Toast.makeText(this, "Não foi possível carregar as cenas.", Toast.LENGTH_LONG).show()
        }
        if (scenes.isEmpty()) scenes.add(Scene("default", "Cena 1",
            mutableListOf(CaptureLayer("default-screen", "screen", "Captura de tela"))))
        val savedId = prefs.getString("active_capture_scene", "")
        selectedSceneId = scenes.find { it.id == savedId }?.id ?: scenes.first().id
        selectedLayerId = activeScene()?.sources?.lastOrNull()?.id
    }

    private fun persistScenes() {
        val array = JSONArray()
        scenes.forEach { scene ->
            val sources = JSONArray()
            scene.sources.forEach { layer ->
                sources.put(JSONObject()
                    .put("id", layer.id)
                    .put("type", layer.type)
                    .put("name", layer.name)
                    .put("x", layer.x)
                    .put("y", layer.y)
                    .put("width", layer.width)
                    .put("height", layer.height)
                    .put("visible", layer.visible)
                    .put("locked", layer.locked))
            }
            array.put(JSONObject().put("id", scene.id).put("name", scene.name)
                .put("sources", sources))
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("capture_scenes", array.toString())
            .putString("active_capture_scene", selectedSceneId)
            .apply()
    }

    private fun showScenes() {
        scenesList.removeAllViews()
        scenes.forEach { scene ->
            scenesList.addView(control((if (scene.id == selectedSceneId) "● " else "○ ") +
                scene.name + "  (${scene.sources.size} fontes)") {
                selectedSceneId = scene.id
                selectedLayerId = scene.sources.lastOrNull()?.id
                persistScenes()
                showScenes()
            }, LinearLayout.LayoutParams(-1, dp(43)))
        }
        sceneStatus.text = "Cena ativa: " + (activeScene()?.name ?: "Nenhuma")
        showLayers()
    }

    private fun showLayers() {
        if (!::layersList.isInitialized || !::canvasEditor.isInitialized) return
        layersList.removeAllViews()
        val sources = activeScene()?.sources ?: emptyList()
        sources.asReversed().forEach { layer ->
            val label = (if (selectedLayerId == layer.id) "● " else "○ ") +
                (if (layer.visible) "◉ " else "⊘ ") +
                (if (layer.locked) "▣ " else "") + layer.name
            layersList.addView(control(label) {
                selectedLayerId = layer.id
                showLayers()
            }, LinearLayout.LayoutParams(-1, dp(42)))
        }
        canvasEditor.layers = sources
        canvasEditor.selectedLayerId = selectedLayerId
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
                        selectedLayerId = null
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
                selectedLayerId = activeScene()?.sources?.lastOrNull()?.id
                persistScenes()
                showScenes()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseSource() {
        AlertDialog.Builder(this).setTitle("Adicionar fonte")
            .setItems(arrayOf("Captura de tela", "Texto")) { _, which ->
                if (which == 0) addSource("screen", "Captura de tela")
                else {
                    val field = EditText(this).apply { setSingleLine(true); setText("Texto") }
                    AlertDialog.Builder(this).setTitle("Conteúdo do texto")
                        .setView(field)
                        .setPositiveButton("Adicionar") { _, _ ->
                            val content = field.text.toString().trim()
                            if (content.isNotEmpty()) addSource("text", content.take(80))
                        }
                        .setNegativeButton("Cancelar", null).show()
                }
            }.show()
    }

    private fun addSource(type: String, name: String) {
        val scene = activeScene() ?: return
        if (type == "screen" && scene.sources.any { it.type == "screen" }) {
            Toast.makeText(this, "A cena já possui captura de tela.", Toast.LENGTH_SHORT).show()
            return
        }
        val layer = CaptureLayer(java.util.UUID.randomUUID().toString(), type, name,
            if (type == "screen") 0f else .15f,
            if (type == "screen") 0f else .15f,
            if (type == "screen") 1f else .6f,
            if (type == "screen") 1f else .25f)
        scene.sources.add(layer)
        selectedLayerId = layer.id
        persistScenes()
        showLayers()
    }

    private fun changeLayer(action: String) {
        val scene = activeScene() ?: return
        val layer = scene.sources.find { it.id == selectedLayerId } ?: return
        when (action) {
            "visible" -> layer.visible = !layer.visible
            "locked" -> layer.locked = !layer.locked
            "remove" -> {
                scene.sources.remove(layer)
                selectedLayerId = scene.sources.lastOrNull()?.id
            }
        }
        persistScenes()
        showLayers()
    }

    private fun reorderLayer(direction: Int) {
        val sources = activeScene()?.sources ?: return
        val index = sources.indexOfFirst { it.id == selectedLayerId }
        val next = index + direction
        if (index < 0 || next !in sources.indices) return
        val layer = sources.removeAt(index)
        sources.add(next, layer)
        persistScenes()
        showLayers()
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
            canvasEditor.frame = if (running) ScreenCaptureService.previewFrame else null
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
