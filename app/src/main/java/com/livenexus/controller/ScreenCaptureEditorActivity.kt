package com.livenexus.controller

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
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
import android.widget.HorizontalScrollView

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

    private val violet = Color.rgb(198, 22, 244)
    private val cyan = NeonTheme.cyan

    private fun panel(stroke: Int = Color.rgb(57, 65, 110)): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(18, 19, 51), Color.rgb(5, 9, 31))).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun neonButton(text: String, accent: Int = violet, action: () -> Unit): TextView =
        label(text, 13f).apply {
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = panel(accent)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun section(title: String): TextView = label("▏" + title, 16f).apply {
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun layoutParams(
        height: Int = -2, width: Int = -1, top: Int = 0, bottom: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun weightedRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { i, item ->
                addView(neonButton(item.first, if (i == 0) violet else Color.rgb(65, 78, 146), item.second),
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        if (i > 0) leftMargin = dp(6)
                    })
            }
        }

    private fun openSettings() {
        AlertDialog.Builder(this)
            .setTitle("Configurações de captura")
            .setItems(arrayOf("Sobre a prévia", "Encerrar captura")) { _, index ->
                if (index == 1) stopCapture()
                else Toast.makeText(this,
                    "A prévia é local; a transmissão não está integrada.", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        status.text = "Encerrando captura..."
        canvasEditor.frame = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NeonTheme.ink
        window.navigationBarColor = NeonTheme.ink
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NeonTheme.backdrop()
            setPadding(dp(if (landscape) 12 else 14), dp(6), dp(if (landscape) 12 else 14), dp(8))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(NeonHeader(this), LinearLayout.LayoutParams(
            if (landscape) dp(210) else 0, dp(if (landscape) 65 else 72),
            if (landscape) 0f else 1f
        ))
        if (landscape) header.addView(label("▏EDITOR DE CAPTURA", 19f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(neonButton("▣", violet) { finish() }, layoutParams(dp(40), dp(46)))
        header.addView(neonButton("⋮", cyan) { openSettings() },
            LinearLayout.LayoutParams(dp(46), dp(40)).apply { leftMargin = dp(6) })
        root.addView(header, layoutParams(dp(if (landscape) 53 else 72)))

        canvasEditor = CaptureSceneCanvas(this).apply {
            contentDescription = "Prévia editável de cenas. Arraste ou redimensione as fontes."
            onSelection = { selectedLayerId = it; showLayers() }
            onChange = { persistScenes(); showLayers() }
            background = panel(violet)
        }
        preview = ImageView(this).apply { visibility = View.GONE }
        status = label("Captura desligada.", 12f).apply {
            setTextColor(Color.rgb(164, 180, 207))
        }
        sceneStatus = label("", 12f)
        scenesList = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layersList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val scenePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(9), dp(8), dp(9), dp(9))
            addView(section("CENAS"))
            addView(sceneStatus)
            val swipe = HorizontalScrollView(this@ScreenCaptureEditorActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(scenesList)
            }
            addView(swipe, layoutParams(dp(48), -1, 6, 5))
            addView(weightedRow(
                "+ Cena" to { editSceneName(null) },
                "Renomear" to { activeScene()?.let { editSceneName(it) } },
                "Excluir" to { removeScene() }
            ))
        }

        val sourcesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(9), dp(8), dp(9), dp(9))
            val sectionHead = LinearLayout(this@ScreenCaptureEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(section("FONTES / CAMADAS").apply { textSize = if (landscape) 13f else 16f }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(neonButton("+ Adicionar fonte", cyan) { chooseSource() },
                    LinearLayout.LayoutParams(dp(if (landscape) 144 else 160), dp(39)))
            }
            addView(sectionHead)
            addView(layersList)
        }

        resolution = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ScreenCaptureEditorActivity,
                android.R.layout.simple_spinner_dropdown_item, options
            )
            val saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_RESOLUTION, 1280)
            setSelection(sizes.indexOf(saved).takeIf { it >= 0 } ?: 1)
            background = panel(violet)
        }
        val bottomControls = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val resolutionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("▣  RESOLUÇÃO", 13f).apply {
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(resolution, LinearLayout.LayoutParams(
                dp(if (landscape) 185 else 200), dp(47)))
        }
        bottomControls.addView(resolutionRow, if (landscape)
            LinearLayout.LayoutParams(0, dp(50), 1f) else layoutParams(dp(50)))
        val actions = weightedRow(
            "◉  Iniciar captura" to { askPermission() },
            "■  Parar captura" to { stopCapture() },
            "◉  Transmitir" to {
                Toast.makeText(this,
                    "Transmissão ainda não integrada. Somente prévia local.",
                    Toast.LENGTH_LONG).show()
            }
        )
        bottomControls.addView(actions, if (landscape)
            LinearLayout.LayoutParams(0, -2, 2f).apply { leftMargin = dp(10) }
            else layoutParams(dp(49), -1, 7))

        val navigation = weightedRow(
            "⌂ Controlador" to { finish() },
            "▣ Captura" to { },
            "◉ Transmissão" to {
                Toast.makeText(this, "Transmissão ainda não integrada.", Toast.LENGTH_SHORT).show()
            },
            "⚙ Configurações" to { openSettings() }
        )
        if (landscape) {
            // All important editor panels remain visible in landscape, with
            // scrolling confined to the right-hand source/scene column.
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, dp(4))
            }
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            left.addView(canvasEditor, LinearLayout.LayoutParams(-1, 0, 1f))
            left.addView(status, layoutParams(dp(23), -1, 4))
            columns.addView(left, LinearLayout.LayoutParams(0, -1, 3f).apply {
                rightMargin = dp(10)
            })
            val rightScroll = ScrollView(this).apply {
                fillViewport = true
                isVerticalScrollBarEnabled = true
            }
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            right.addView(scenePanel, layoutParams(-2, -1, 0, 7))
            right.addView(sourcesPanel)
            rightScroll.addView(right)
            columns.addView(rightScroll, LinearLayout.LayoutParams(0, -1, 2f))
            root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(bottomControls, layoutParams(dp(65), -1, 2))
            root.addView(navigation, layoutParams(dp(47), -1, 5))
        } else {
            val editorScroll = ScrollView(this)
            val work = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(7))
            }
            work.addView(section("EDITOR DE CAPTURA"))
            work.addView(label("Crie cenas, adicione fontes e capture sua tela.", 12f))
            work.addView(canvasEditor, layoutParams(dp(245), -1, 12, 5))
            work.addView(status, layoutParams(-2, -1, 0, 9))
            work.addView(scenePanel, layoutParams(-2, -1, 4, 9))
            work.addView(sourcesPanel, layoutParams(-2, -1, 0, 9))
            work.addView(bottomControls)
            editorScroll.addView(work)
            root.addView(editorScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(navigation, layoutParams(dp(52), -1, 5))
        }
        setContentView(root)
        loadScenes()
        showScenes()
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
                            raw.optDouble("x", 0.1).toFloat().coerceIn(-2f, 2f),
                            raw.optDouble("y", 0.1).toFloat().coerceIn(-2f, 2f),
                            raw.optDouble("width", .8).toFloat().coerceIn(.08f, 3f),
                            raw.optDouble("height", .65).toFloat().coerceIn(.08f, 3f),
                            raw.optBoolean("visible", true),
                            raw.optBoolean("locked", false)
                        ))
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
            val selected = scene.id == selectedSceneId
            scenesList.addView(neonButton(
                (if (selected) "● " else "○ ") + scene.name,
                if (selected) violet else Color.rgb(63, 74, 126)
            ) {
                selectedSceneId = scene.id
                selectedLayerId = scene.sources.lastOrNull()?.id
                persistScenes()
                showScenes()
            }, LinearLayout.LayoutParams(dp(145), dp(43)).apply {
                rightMargin = dp(7)
            })
        }
        sceneStatus.text = "Cena ativa: " + (activeScene()?.name ?: "Nenhuma")
        showLayers()
    }

    private fun showLayers() {
        if (!::layersList.isInitialized || !::canvasEditor.isInitialized) return
        layersList.removeAllViews()
        val sources = activeScene()?.sources ?: emptyList()
        sources.asReversed().forEach { layer ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = panel(if (layer.id == selectedLayerId) violet else Color.rgb(40, 50, 87))
                setPadding(dp(6), dp(3), dp(6), dp(3))
            }
            val prefix = when (layer.type) {
                "screen" -> "▣"
                "text" -> "T"
                else -> "▧"
            }
            row.addView(label(prefix + "  " + layer.name, 13f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener {
                    selectedLayerId = layer.id
                    showLayers()
                }
            }, LinearLayout.LayoutParams(0, -1, 1f))
            fun actionButton(text: String, color: Int = violet, action: () -> Unit) {
                row.addView(neonButton(text, color, action),
                    LinearLayout.LayoutParams(dp(35), dp(38)).apply { leftMargin = dp(3) })
            }
            actionButton(if (layer.visible) "◉" else "⊘") {
                selectedLayerId = layer.id; changeLayer("visible")
            }
            actionButton(if (layer.locked) "▣" else "♧") {
                selectedLayerId = layer.id; changeLayer("locked")
            }
            actionButton("↑") { selectedLayerId = layer.id; reorderLayer(1) }
            actionButton("↓") { selectedLayerId = layer.id; reorderLayer(-1) }
            actionButton("×", Color.rgb(199, 53, 97)) {
                selectedLayerId = layer.id; changeLayer("remove")
            }
            layersList.addView(row, layoutParams(dp(48), -1, 0, 5))
        }
        if (sources.isEmpty()) layersList.addView(label("Nenhuma fonte nesta cena.", 12f))
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
