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

    private val violet = Color.rgb(191, 31, 240)
    private val cyan = NeonTheme.cyan
    private val textMuted = Color.rgb(165, 169, 193)
    private val stroke = Color.rgb(52, 61, 110)
    private var landscapeEditor = false

    private fun shape(outline: Int = stroke, active: Boolean = false): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                if (active) Color.rgb(65, 13, 95) else Color.rgb(16, 19, 48),
                Color.rgb(6, 9, 30)
            )
        ).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(if (active) 2 else 1).coerceAtLeast(1), outline)
        }

    private fun caption(text: String, size: Float = 12f): TextView = label(text, size).apply {
        setTextColor(textMuted)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun tap(
        title: String, active: Boolean = false, accent: Int = violet, action: () -> Unit
    ): TextView = label(title, if (landscapeEditor) 11f else 13f).apply {
        gravity = Gravity.CENTER
        setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        setPadding(dp(5), dp(3), dp(5), dp(3))
        background = shape(if (active) accent else stroke, active)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun section(title: String, size: Float = 15f): TextView =
        label("▏" + title, size).apply {
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun params(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun row(vararg items: Pair<String, () -> Unit>, height: Int = 43): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEachIndexed { i, item ->
                addView(tap(item.first, i == 0, if (i == 0) violet else stroke, item.second),
                    LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                        if (i > 0) leftMargin = dp(5)
                    })
            }
        }

    private fun stopCapture() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        status.text = "Encerrando captura..."
        canvasEditor.frame = null
    }

    private fun openSettings() {
        AlertDialog.Builder(this).setTitle("Configurações de captura")
            .setItems(arrayOf("Sobre a prévia", "Encerrar captura")) { _, which ->
                if (which == 1) stopCapture()
                else Toast.makeText(this, "A prévia é local, sem envio para plataformas.",
                    Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun navigation(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shape()
        setPadding(dp(5), dp(3), dp(5), dp(3))
        val destinations = listOf(
            Triple("⌂", "Controlador", false),
            Triple("▣", "Captura", true),
            Triple("◉", "Transmissão", false),
            Triple("⚙", "Configurações", false)
        )
        destinations.forEachIndexed { index, (icon, name, active) ->
            val tab = LinearLayout(this@ScreenCaptureEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (active) background = shape(violet, true)
                addView(label(icon, if (landscapeEditor) 19f else 22f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(if (active) violet else Color.WHITE)
                })
                addView(label(name, if (landscapeEditor) 10f else 11f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(if (active) violet else Color.WHITE)
                    maxLines = 1
                })
                setOnClickListener {
                    when (index) {
                        0 -> finish()
                        2 -> Toast.makeText(this@ScreenCaptureEditorActivity,
                            "Transmissão não integrada.", Toast.LENGTH_SHORT).show()
                        3 -> openSettings()
                    }
                }
            }
            addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NeonTheme.ink
        window.navigationBarColor = NeonTheme.ink
        landscapeEditor = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val compact = landscapeEditor
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NeonTheme.backdrop()
            setPadding(dp(if (compact) 9 else 12), dp(3), dp(if (compact) 9 else 12), dp(5))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(NeonHeader(this),
            LinearLayout.LayoutParams(if (compact) dp(175) else 0,
                dp(if (compact) 50 else 66), if (compact) 0f else 1f))
        if (compact) header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(section("EDITOR DE CAPTURA", 18f))
            addView(caption("Crie cenas, adicione fontes e capture sua tela.", 10f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(tap("▤", false) { finish() },
            params(dp(37), dp(36)))
        header.addView(tap("⋮", false) { openSettings() },
            LinearLayout.LayoutParams(dp(37), dp(36)).apply { leftMargin = dp(6) })
        root.addView(header, params(-1, dp(if (compact) 53 else 68)))

        canvasEditor = CaptureSceneCanvas(this).apply {
            contentDescription = "Prévia do cenário: mover com um dedo; redimensionar com dois."
            onSelection = { id -> selectedLayerId = id; showLayers() }
            onChange = { persistScenes(); showLayers() }
            background = shape(violet, true)
        }
        preview = ImageView(this).apply { visibility = View.GONE }
        status = caption("Captura desligada.", 11f)
        sceneStatus = caption("", 11f)
        scenesList = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layersList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val scenePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape()
            setPadding(dp(8), dp(5), dp(8), dp(7))
            val top = LinearLayout(this@ScreenCaptureEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(section("CENAS", 14f), LinearLayout.LayoutParams(0, -1, 1f))
                addView(caption("Gerenciar cenas ›", 10f).apply {
                    setOnClickListener { manageScene() }
                })
            }
            addView(top, params(-1, dp(27)))
            addView(HorizontalScrollView(this@ScreenCaptureEditorActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(scenesList)
            }, params(-1, dp(45), 6, 1))
        }

        val sourcesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape()
            setPadding(dp(8), dp(7), dp(8), dp(6))
            addView(LinearLayout(this@ScreenCaptureEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(section("FONTES / CAMADAS", if (compact) 12f else 15f),
                    LinearLayout.LayoutParams(0, -1, 1f))
                addView(tap("+ Adicionar fonte", false, cyan) { chooseSource() },
                    params(dp(if (compact) 117 else 155), dp(33)))
            }, params(-1, dp(37), 0, 3))
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
            background = shape(violet)
        }
        val bottom = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = shape()
        }
        val resolutionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(if (compact) "▣ RESOLUÇÃO" else "RESOLUÇÃO DA CAPTURA", 11f).apply {
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -1, if (compact) 0.8f else 1f))
            addView(resolution, LinearLayout.LayoutParams(
                0, dp(36), if (compact) 1.2f else 1f))
        }
        bottom.addView(resolutionRow,
            if (compact) LinearLayout.LayoutParams(0, dp(42), 2f) else params(-1, dp(42)))
        val actions = row(
            "◉ Iniciar captura" to { askPermission() },
            "■ Parar captura" to { stopCapture() },
            "◉ Transmitir" to {
                Toast.makeText(this, "Transmissão não integrada.", Toast.LENGTH_LONG).show()
            },
            height = 41
        )
        bottom.addView(actions,
            if (compact) LinearLayout.LayoutParams(0, -2, 3f).apply { leftMargin = dp(9) }
            else params(-1, -2, 6))

        if (compact) {
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(canvasEditor, LinearLayout.LayoutParams(-1, 0, 1f))
            left.addView(status, params(-1, dp(19), 3))
            columns.addView(left, LinearLayout.LayoutParams(0, -1, 3f).apply {
                rightMargin = dp(8)
            })
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(scenePanel, params(-1, -2, 0, 6))
            val sourceScroll = ScrollView(this).apply { isFillViewport = false }
            sourceScroll.addView(sourcesPanel)
            right.addView(sourceScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            columns.addView(right, LinearLayout.LayoutParams(0, -1, 2f))
            root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(bottom, params(-1, dp(57), 2))
        } else {
            val scroll = ScrollView(this)
            val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            stack.addView(section("EDITOR DE CAPTURA", 19f))
            stack.addView(caption("Crie cenas, adicione fontes e capture sua tela.", 12f))
            stack.addView(canvasEditor, params(-1, dp(238), 8, 5))
            stack.addView(status, params(-1, -2, 0, 8))
            stack.addView(scenePanel, params(-1, -2, 0, 9))
            stack.addView(sourcesPanel, params(-1, -2, 0, 9))
            stack.addView(bottom)
            scroll.addView(stack)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(navigation(), params(-1, dp(if (compact) 53 else 62), 5))
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
            mutableListOf(CaptureLayer("default-screen", "screen", "Captura de tela", 0f, 0f, 1f, 1f))))
        // Migra somente a camada padrao antiga, preservando ajustes personalizados.
        scenes.forEach { scene ->
            scene.sources.filter { it.id == "default-screen" && it.type == "screen" }.forEach { layer ->
                if (layer.x == 0.1f && layer.y == 0.1f &&
                    layer.width == 0.8f && layer.height == 0.65f) {
                    layer.x = 0f
                    layer.y = 0f
                    layer.width = 1f
                    layer.height = 1f
                }
            }
        }
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

    private fun manageScene() {
        AlertDialog.Builder(this).setTitle("Gerenciar cenas")
            .setItems(arrayOf("Adicionar cena", "Renomear cena", "Excluir cena")) { _, index ->
                when (index) {
                    0 -> editSceneName(null)
                    1 -> activeScene()?.let { editSceneName(it) }
                    2 -> removeScene()
                }
            }.show()
    }

    private fun showScenes() {
        scenesList.removeAllViews()
        scenes.forEach { scene ->
            val selected = scene.id == selectedSceneId
            scenesList.addView(tap(
                (if (selected) "● " else "") + scene.name,
                selected
            ) {
                selectedSceneId = scene.id
                selectedLayerId = scene.sources.lastOrNull()?.id
                persistScenes()
                showScenes()
            }, LinearLayout.LayoutParams(dp(if (landscapeEditor) 122 else 136), dp(42)).apply {
                rightMargin = dp(6)
            })
        }
        scenesList.addView(tap("+ Nova cena") { editSceneName(null) },
            LinearLayout.LayoutParams(dp(if (landscapeEditor) 120 else 136), dp(42)))
        sceneStatus.text = "Cena ativa: " + (activeScene()?.name ?: "Nenhuma")
        showLayers()
    }

    private fun showLayers() {
        if (!::layersList.isInitialized || !::canvasEditor.isInitialized) return
        layersList.removeAllViews()
        val sources = activeScene()?.sources ?: emptyList()
        sources.asReversed().forEach { layer ->
            val selected = layer.id == selectedLayerId
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(if (selected) violet else stroke)
                setPadding(dp(3), dp(2), dp(3), dp(2))
            }
            val icon = when (layer.type) {
                "screen" -> "▣"
                "text" -> "T"
                else -> "▤"
            }
            item.addView(label(icon + "  " + layer.name, if (landscapeEditor) 11f else 12f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { selectedLayerId = layer.id; showLayers() }
            }, LinearLayout.LayoutParams(0, -1, 1f))
            fun action(glyph: String, color: Int = Color.WHITE, command: () -> Unit) {
                item.addView(label(glyph, if (landscapeEditor) 17f else 19f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(color)
                    setOnClickListener { selectedLayerId = layer.id; command() }
                }, params(dp(if (landscapeEditor) 26 else 31), -1))
            }
            action(if (layer.visible) "◉" else "⊘", violet) { changeLayer("visible") }
            action(if (layer.locked) "▣" else "♧") { changeLayer("locked") }
            action("↑") { reorderLayer(1) }
            action("↓") { reorderLayer(-1) }
            action("×", Color.rgb(250, 70, 101)) { changeLayer("remove") }
            layersList.addView(item, params(-1, dp(if (landscapeEditor) 37 else 43), 0, 3))
        }
        if (sources.isEmpty()) layersList.addView(caption("Adicione uma fonte à cena.", 12f))
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
