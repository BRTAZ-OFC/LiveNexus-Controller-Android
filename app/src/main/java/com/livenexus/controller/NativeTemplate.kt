package com.livenexus.controller

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

/**
 * LiveNEXUS Controller: Native user interface replicating the exact
 * visual fidelity, cyberpunk neon palette, and component layouts of the design specifications.
 */
internal class NativeTemplate(private val activity: Activity) {
    companion object {
        const val REQUEST_MEDIA_PROJECTION = 2001

        @Volatile
        private var activeTemplate: NativeTemplate? = null

        fun onMediaProjectionResult(granted: Boolean) {
            activeTemplate?.handleMediaProjectionResult(granted)
        }
    }

    private val density = activity.resources.displayMetrics.density
    private val root = FrameLayout(activity)

    // Navigation and screen state
    private var page = "transmission"
    private var wide = false
    private var availableWidth = 0
    private var availableHeight = 0
    private var popup: PopupWindow? = null
    private var menuAnchor: View? = null

    // Encoder settings state (synced with StreamingConfig SharedPreferences)
    private var resolution = StreamingConfig.loadResolutionLabel(activity)
    private var streamOrientation = StreamingConfig.loadOrientationLabel(activity)
    private var fps = StreamingConfig.loadFpsLabel(activity)
    private var bitrate = StreamingConfig.loadBitrateKbps(activity)
    private var codec = "H.264"
    private var micEnabled = StreamingConfig.loadMicEnabled(activity)
    private var internalAudioEnabled = StreamingConfig.loadInternalAudioEnabled(activity)
    private var noiseSuppressionEnabled = StreamingConfig.loadNoiseSuppressionEnabled(activity)
    private var micVolume = StreamingConfig.loadMicVolume(activity)
    private var internalVolume = StreamingConfig.loadInternalVolume(activity)

    // Platform settings state
    private var twitchEnabled = StreamingConfig.isPlatformEnabled(activity, "Twitch")
    private var youtubeEnabled = StreamingConfig.isPlatformEnabled(activity, "YouTube")
    private var kickEnabled = StreamingConfig.isPlatformEnabled(activity, "Kick")
    private var twitchKeyVisible = false
    private var youtubeKeyVisible = false
    private var kickKeyVisible = false
    private var streamConnected = ScreenCaptureService.isLiveStreaming
    private var streamConnecting = ScreenCaptureService.isConnecting
    private var streamStartTimeMs = ScreenCaptureService.streamStartTimeMs
    private var timerLabel: TextView? = null
    private var overlayTimerLabel: TextView? = null
    private var overlayPanelTimerLabel: TextView? = null

    // Permissions & Smartphone System Overlay (WindowManager) state
    private var micPermGranted = false
    private var galleryPermGranted = false
    private var overlayPermGranted = false
    private var pendingConnectAfterPermissions = false
    private var showPermissionsModal = false
    private var overlayExpanded = false
    private var overlayMenuTab = "controls" // "controls" or "audio"
    private var sysOverlayX = -1
    private var sysOverlayY = -1
    private var countdownSeconds = 0
    private var countdownLabel: TextView? = null
    private var lifecycleRegistered = false
    private var activityInBackground = false
    private var systemOverlayView: View? = null

    private val serviceStateListener: () -> Unit = {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val wasConnected = streamConnected
            streamConnected = ScreenCaptureService.isLiveStreaming
            streamConnecting = ScreenCaptureService.isConnecting
            streamStartTimeMs = ScreenCaptureService.streamStartTimeMs

            val err = ScreenCaptureService.lastError
            if (!err.isNullOrBlank()) {
                toast(err)
                ScreenCaptureService.clearError()
            }

            if (streamConnected && !wasConnected) {
                root.removeCallbacks(timerRunnable)
                root.postDelayed(timerRunnable, 1000L)
                if (activityInBackground && canDrawSystemOverlay()) {
                    showSystemOverlayWindow()
                }
            } else if (!streamConnected && wasConnected) {
                root.removeCallbacks(timerRunnable)
                removeSystemOverlayWindow()
            }
            render()
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (streamConnected) {
                val formatted = formatElapsedTime()
                timerLabel?.text = formatted
                overlayTimerLabel?.text = formatted
                overlayPanelTimerLabel?.text = formatted
                root.postDelayed(this, 1000L)
            }
        }
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdownSeconds > 1) {
                countdownSeconds--
                countdownLabel?.text = countdownSeconds.toString()
                root.postDelayed(this, 1000L)
            } else if (countdownSeconds == 1) {
                countdownSeconds = 0
                syncSceneLayersToEncoder()
                val startErr = StreamingConfig.startLiveStream(activity)
                if (startErr != null) {
                    streamConnected = false
                    streamConnecting = false
                    toast(startErr)
                    render()
                    return
                }
                streamConnecting = true
                streamConnected = true
                streamStartTimeMs = System.currentTimeMillis()
                overlayExpanded = false
                root.removeCallbacks(timerRunnable)
                root.postDelayed(timerRunnable, 1000L)
                render()
                // Minimiza o APK para a tela do smartphone e exibe somente a Sobreposição de Tela no sistema
                minimizeApkToOverlay()
            }
        }
    }

    private fun requestMediaProjectionConsent() {
        try {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
            if (mgr != null) {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            } else {
                toast("Serviço MediaProjection indisponível neste dispositivo.")
            }
        } catch (e: Throwable) {
            toast("Falha ao solicitar captura de tela: ${e.message}")
        }
    }

    private fun handleMediaProjectionResult(granted: Boolean) {
        if (granted && ProjectionState.hasConsent()) {
            startConnectionCountdown()
        } else {
            toast("A captura de tela precisa ser autorizada para iniciar a transmissão.")
            render()
        }
    }

    private fun verifyAndStartLiveFlow() {
        val activePlatforms = mutableListOf<String>()
        if (twitchEnabled) activePlatforms.add("Twitch")
        if (youtubeEnabled) activePlatforms.add("YouTube")
        if (kickEnabled) activePlatforms.add("Kick")
        if (activePlatforms.isEmpty()) {
            toast("Não há plataforma conectada.")
            return
        }

        val configuredTargets = StreamingConfig.getConfiguredPlatformTargets(activity)
        if (configuredTargets.isEmpty()) {
            val firstActive = activePlatforms.first()
            toast("Insira a Stream Key da plataforma $firstActive para transmitir.")
            promptStreamKeyDialog(firstActive) {
                if (StreamingConfig.getConfiguredPlatformTargets(activity).isNotEmpty()) {
                    verifyAndStartLiveFlow()
                }
            }
            return
        }

        syncSystemPermissions()
        if (!micPermGranted || !galleryPermGranted || !overlayPermGranted) {
            showPermissionsModal = true
            requestRuntimeMicAndGalleryPermissions()
            render()
            return
        }

        if (!ProjectionState.hasConsent()) {
            requestMediaProjectionConsent()
            return
        }

        startConnectionCountdown()
    }

    private fun startConnectionCountdown() {
        val configuredTargets = StreamingConfig.getConfiguredPlatformTargets(activity)
        if (configuredTargets.isEmpty()) {
            toast("Não há plataforma conectada com Stream Key configurada.")
            return
        }
        countdownSeconds = 3
        root.removeCallbacks(countdownRunnable)
        root.postDelayed(countdownRunnable, 1000L)
        render()
    }

    private fun stopLiveStreamAndReset(showMessage: Boolean = true) {
        StreamingConfig.stopLiveStream(activity)
        streamConnected = false
        streamConnecting = false
        overlayExpanded = false
        countdownSeconds = 0
        root.removeCallbacks(timerRunnable)
        root.removeCallbacks(countdownRunnable)
        removeSystemOverlayWindow()
        if (showMessage) {
            toast("Transmissão encerrada e recursos liberados.")
        }
        render()
    }

    private fun minimizeApkToOverlay() {
        if (!streamConnected && !streamConnecting) return
        overlayExpanded = false
        activityInBackground = true
        showSystemOverlayWindow()
        try {
            activity.moveTaskToBack(true)
        } catch (_: Throwable) {}
    }

    private fun returnToApkFromOverlay() {
        overlayExpanded = false
        removeSystemOverlayWindow()
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            if (intent != null) {
                intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
                activity.startActivity(intent)
            }
        } catch (_: Throwable) {}
    }

    private fun hasMicPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 23) {
            activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasGalleryPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            activity.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun canDrawSystemOverlay(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                android.provider.Settings.canDrawOverlays(activity)
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
    }

    private fun syncSystemPermissions() {
        if (hasMicPermission()) micPermGranted = true
        if (hasGalleryPermission()) galleryPermGranted = true
        overlayPermGranted = canDrawSystemOverlay()
    }

    private fun requestSystemOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && !canDrawSystemOverlay()) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    private fun requestRuntimeMicAndGalleryPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            val perms = mutableListOf<String>()
            if (!hasMicPermission()) {
                perms.add(android.Manifest.permission.RECORD_AUDIO)
            }
            if (!hasGalleryPermission()) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            if (perms.isNotEmpty()) {
                try {
                    activity.requestPermissions(perms.toTypedArray(), 1001)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun formatElapsedTime(): String {
        val baseStart = if (ScreenCaptureService.streamStartTimeMs > 0L) {
            ScreenCaptureService.streamStartTimeMs
        } else {
            streamStartTimeMs
        }
        if ((!streamConnected && !ScreenCaptureService.isLiveStreaming) || baseStart <= 0L) return "00:00:00"
        val totalSec = ((System.currentTimeMillis() - baseStart) / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    // Controls state: cada botão representa um subcenário real selecionado no LiveNEXUS.
    private var playerPlaying = true
    private var playerVolume = 75
    private val serverScenarios = mutableListOf<LiveNexusControllerApi.Scenario>()
    private val sceneButtonSlots = mutableListOf<SceneShortcutStore.Assignment?>()
    private var shortcutStoreLoaded = false
    private var shortcutLayoutSaved = false
    private var scenariosLoaded = false
    private var scenarioRequestVersion = 0

    private fun restoreSceneShortcutSlots() {
        if (shortcutStoreLoaded) return
        shortcutStoreLoaded = true
        val saved = SceneShortcutStore.load(activity)
        if (saved != null) {
            sceneButtonSlots.clear()
            sceneButtonSlots.addAll(saved)
            shortcutLayoutSaved = true
        }
    }

    private fun refreshSceneShortcutButtons() {
        if (page == "controls") render()
        if (overlayExpanded && systemOverlayView != null) refreshSystemOverlayWindow()
    }

    private fun applyServerScenarios(scenes: List<LiveNexusControllerApi.Scenario>) {
        restoreSceneShortcutSlots()
        serverScenarios.clear()
        serverScenarios.addAll(scenes)
        scenariosLoaded = true
        // Primeira utilização: os próprios subcenários cadastrados são os atalhos iniciais.
        // Depois disso, a organização escolhida pelo usuário não é substituída por uma busca.
        if (!shortcutLayoutSaved && sceneButtonSlots.isEmpty()) {
            for (scene in scenes) {
                for (sub in scene.subScenarios) {
                    sceneButtonSlots.add(SceneShortcutStore.Assignment(scene.id, sub.id))
                }
            }
            if (sceneButtonSlots.isNotEmpty()) {
                shortcutLayoutSaved = SceneShortcutStore.save(activity, sceneButtonSlots)
                if (!shortcutLayoutSaved) toast("Não foi possível armazenar os atalhos de subcenários.")
            }
        }
        refreshSceneShortcutButtons()
    }

    private fun loadServerScenarios() {
        val requestVersion = ++scenarioRequestVersion
        LiveNexusControllerApi.fetchScenarios(activity) { scenes, error ->
            if (activity.isFinishing || activity.isDestroyed ||
                requestVersion != scenarioRequestVersion) return@fetchScenarios
            if (scenes == null) {
                if (page == "controls" || overlayExpanded) {
                    toast(error ?: "Não foi possível carregar os subcenários.")
                }
            } else {
                applyServerScenarios(scenes)
            }
        }
    }

    private fun sceneShortcutTitle(index: Int): String {
        val assigned = sceneButtonSlots.getOrNull(index) ?: return "Selecionar"
        val sub = serverScenarios.firstOrNull { it.id == assigned.scenarioId }
            ?.subScenarios?.firstOrNull { it.id == assigned.subcenarioId }
        return sub?.name ?: if (scenariosLoaded) "Indisponível" else "Carregando"
    }

    private fun addSceneButtonSlot() {
        restoreSceneShortcutSlots()
        sceneButtonSlots.add(null)
        if (!SceneShortcutStore.save(activity, sceneButtonSlots)) {
            sceneButtonSlots.removeAt(sceneButtonSlots.lastIndex)
            toast("Não foi possível salvar o novo botão.")
            return
        }
        shortcutLayoutSaved = true
        val index = sceneButtonSlots.lastIndex
        refreshSceneShortcutButtons()
        chooseSceneForButton(index)
    }

    private fun removeSceneButtonSlot(index: Int) {
        if (index !in sceneButtonSlots.indices) return
        val removed = sceneButtonSlots.removeAt(index)
        if (!SceneShortcutStore.save(activity, sceneButtonSlots)) {
            sceneButtonSlots.add(index, removed)
            toast("Não foi possível apagar o botão.")
            return
        }
        shortcutLayoutSaved = true
        refreshSceneShortcutButtons()
        toast("Botão de cenário apagado.")
    }

    private fun showSceneButtonOptions(index: Int) {
        if (index !in sceneButtonSlots.indices) return
        showSceneSelectionDialog(
            "Configurar botão",
            listOf("Selecionar subcenário", "Apagar botão")
        ) { choice ->
            when (choice) {
                0 -> chooseSceneForButton(index)
                1 -> removeSceneButtonSlot(index)
            }
        }
    }

    private fun assignSubScenario(
        slotIndex: Int,
        scene: LiveNexusControllerApi.Scenario,
        sub: LiveNexusControllerApi.SubScenario
    ) {
        if (slotIndex !in sceneButtonSlots.indices) return
        val previous = sceneButtonSlots[slotIndex]
        sceneButtonSlots[slotIndex] = SceneShortcutStore.Assignment(scene.id, sub.id)
        if (!SceneShortcutStore.save(activity, sceneButtonSlots)) {
            sceneButtonSlots[slotIndex] = previous
            toast("Não foi possível salvar a configuração do botão.")
            return
        }
        shortcutLayoutSaved = true
        refreshSceneShortcutButtons()
        toast("Subcenário atribuído: " + sub.name)
    }

    private fun showSceneSelectionDialog(
        title: String,
        choices: List<String>,
        onSelect: (Int) -> Unit
    ) {
        if (choices.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(choices.toTypedArray()) { _, index -> onSelect(index) }
            .setNegativeButton("Cancelar", null)
            .create()
        // Ao controlar uma live minimizada, o diálogo precisa ficar acima de outros apps.
        // A permissão de sobreposição já é utilizada pelo painel flutuante do Controller.
        if (activityInBackground && systemOverlayView != null && canDrawSystemOverlay()) {
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        try {
            dialog.show()
        } catch (error: Exception) {
            Log.w("NativeTemplate", "Não foi possível abrir a seleção de subcenário", error)
            toast("Abra o APK para configurar este botão.")
        }
    }

    private fun chooseSceneForButton(slotIndex: Int) {
        if (slotIndex !in sceneButtonSlots.indices) return
        val requestVersion = ++scenarioRequestVersion
        LiveNexusControllerApi.fetchScenarios(activity) { scenes, error ->
            if (activity.isFinishing || activity.isDestroyed ||
                requestVersion != scenarioRequestVersion) return@fetchScenarios
            if (scenes == null) {
                toast(error ?: "Não foi possível consultar os cenários.")
                return@fetchScenarios
            }
            applyServerScenarios(scenes)
            val available = scenes.filter { it.subScenarios.isNotEmpty() }
            if (available.isEmpty()) {
                toast("Não há cenários com subcenários cadastrados.")
                return@fetchScenarios
            }
            showSceneSelectionDialog("Selecionar cenário", available.map { it.name }) { sceneIndex ->
                if (sceneIndex in available.indices) {
                    val scene = available[sceneIndex]
                    showSceneSelectionDialog(
                        "Subcenários - " + scene.name,
                        scene.subScenarios.map { it.name }
                    ) { subIndex ->
                        if (subIndex in scene.subScenarios.indices) {
                            assignSubScenario(slotIndex, scene, scene.subScenarios[subIndex])
                        }
                    }
                }
            }
        }
    }

    private fun selectServerScenario(index: Int) {
        val assigned = sceneButtonSlots.getOrNull(index)
        if (assigned == null) {
            chooseSceneForButton(index)
            return
        }
        val scene = serverScenarios.firstOrNull { it.id == assigned.scenarioId }
        val sub = scene?.subScenarios?.firstOrNull { it.id == assigned.subcenarioId }
        if (scene == null || sub == null) {
            toast("Subcenário não encontrado. Segure o botão para escolher outro.")
            return
        }
        LiveNexusControllerApi.sendCommand(
            activity, "scenario-select", scene.copy(subScenarioId = sub.id)
        ) { accepted ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                toast(
                    if (accepted) "Subcenário enviado ao LiveNEXUS: " + sub.name
                    else "Falha ao solicitar a troca de subcenário."
                )
            }
        }
    }

    private fun sendPlayerCommand(action: String, onAccepted: () -> Unit = {}) {
        LiveNexusControllerApi.sendCommand(activity, action) { accepted ->
            if (activity.isFinishing || activity.isDestroyed) return@sendCommand
            if (accepted) onAccepted() else toast("Não foi possível enviar o comando ao player.")
        }
    }

    private fun reloadControllerScene() {
        syncSceneLayersToEncoder()
        ScreenCaptureService.reloadCurrentScene()
        LiveNexusControllerApi.sendCommand(activity, "rtc-reload") { accepted ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                toast(if (accepted) "Reload local e RTC solicitado." else "Reload local executado; falha ao solicitar RTC.")
            }
        }
    }

    // Canvas Element Model (Adjustable elements on the scenario canvas)
    data class CanvasElement(
        val id: String,
        val type: String, // "screen_capture", "text", "image"
        var title: String,
        var x: Float = 0.10f, // 0.0 to 1.0 (relative to canvas width)
        var y: Float = 0.10f, // 0.0 to 1.0 (relative to canvas height)
        var width: Float = 0.80f, // 0.0 to 1.0
        var height: Float = 0.80f, // 0.0 to 1.0
        var isSelected: Boolean = true,
        var aspectRatio: String = "16:9", // "16:9" or "9:16"
        var url: String = ""
    )

    // Editor state (Canvas starts completely blank)
    private var editorTab = "Todos"
    private var editorMode = "cursor"
    private var zoom = 68
    private val canvasElements = mutableListOf<CanvasElement>()
    private var selectedElementId: String? = null
    private var expandedPreview: View? = null

    private fun syncSceneLayersToEncoder() {
        val layers = canvasElements.map { el ->
            CaptureLayer(
                id = el.id,
                type = el.type,
                label = el.title,
                normX = el.x,
                normY = el.y,
                normW = el.width,
                normH = el.height,
                visible = true,
                aspectRatio = el.aspectRatio,
                url = el.url
            )
        }
        StreamingConfig.activeSceneLayers.clear()
        StreamingConfig.activeSceneLayers.addAll(layers)
        BrowserSourceManager.sync(activity.applicationContext, layers)
    }

    private fun getSelectedElement(): CanvasElement? =
        canvasElements.find { it.id == selectedElementId } ?: canvasElements.find { it.isSelected }

    private fun addScreenCapture() {
        val existing = canvasElements.find { it.type == "screen_capture" }
        if (existing != null) {
            canvasElements.forEach { it.isSelected = (it.id == existing.id) }
            selectedElementId = existing.id
            toast("Captura de Tela selecionada! Arraste ou redimensione pelos pontos.")
        } else {
            canvasElements.forEach { it.isSelected = false }
            val newElement = CanvasElement(
                id = "screen_${System.currentTimeMillis()}",
                type = "screen_capture",
                title = "Captura de Tela",
                x = 0f,
                y = 0f,
                width = 1f,
                height = 1f,
                isSelected = true
            )
            canvasElements.add(newElement)
            selectedElementId = newElement.id
            toast("Elemento 'Captura de Tela' adicionado ao cenário!")
        }
        syncSceneLayersToEncoder()
        root.handler?.post { render() } ?: render()
    }

    private fun addTextElement() {
        canvasElements.forEach { it.isSelected = false }
        val newElement = CanvasElement(
            id = "text_${System.currentTimeMillis()}",
            type = "text",
            title = "Texto: LiveNEXUS Stream",
            x = 0.20f,
            y = 0.05f,
            width = 0.60f,
            height = 0.15f,
            isSelected = true
        )
        canvasElements.add(newElement)
        selectedElementId = newElement.id
        syncSceneLayersToEncoder()
        toast("Elemento 'Texto' adicionado ao cenário!")
        root.handler?.post { render() } ?: render()
    }

    private fun removeSelectedElement() {
        val selected = getSelectedElement()
        if (selected != null) {
            canvasElements.remove(selected)
            selectedElementId = canvasElements.lastOrNull()?.id
            canvasElements.lastOrNull()?.isSelected = true
            syncSceneLayersToEncoder()
            toast("Elemento '${selected.title}' removido do cenário.")
            root.handler?.post { render() } ?: render()
        } else {
            toast("Nenhum elemento selecionado para remover.")
        }
    }

    private fun adjustSelected(action: String) {
        val elem = getSelectedElement() ?: run {
            toast("Selecione um elemento para ajustar.")
            return
        }
        when (action) {
            "fullscreen" -> {
                elem.x = 0f
                elem.y = 0f
                elem.width = 1f
                elem.height = 1f
                toast("Captura de Tela ajustada para Tela Cheia!")
            }
            "center" -> {
                elem.x = ((1f - elem.width) / 2f).coerceIn(0f, 1f)
                elem.y = ((1f - elem.height) / 2f).coerceIn(0f, 1f)
                toast("Elemento centralizado.")
            }
            "grow" -> {
                val nw = (elem.width + 0.06f).coerceAtMost(1f)
                val nh = (elem.height + 0.06f).coerceAtMost(1f)
                elem.x = (elem.x - 0.03f).coerceIn(0f, 1f - nw)
                elem.y = (elem.y - 0.03f).coerceIn(0f, 1f - nh)
                elem.width = nw
                elem.height = nh
            }
            "shrink" -> {
                val nw = (elem.width - 0.06f).coerceAtLeast(0.15f)
                val nh = (elem.height - 0.06f).coerceAtLeast(0.15f)
                elem.x = (elem.x + 0.03f).coerceIn(0f, 1f - nw)
                elem.y = (elem.y + 0.03f).coerceIn(0f, 1f - nh)
                elem.width = nw
                elem.height = nh
            }
        }
        syncSceneLayersToEncoder()
        render()
    }

    private val scrollPositions = mutableMapOf<String, Int>()
    private var currentScroll: ScrollView? = null

    private fun dp(value: Number): Int = (value.toFloat() * density + 0.5f).toInt()
    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    fun show() {
        restoreSceneShortcutSlots()
        // Restaura apenas o último cenário salvo explicitamente pelo usuário.
        ScenarioStorage.load(activity)?.let { saved ->
            canvasElements.clear()
            canvasElements.addAll(saved.elements)
            // Migra somente a geometria padrao antiga (80% centralizado).
            // Nao altera posicionamentos ou tamanhos personalizados pelo usuario.
            canvasElements.filter { it.type == "screen_capture" &&
                it.x == 0.10f && it.y == 0.10f &&
                it.width == 0.80f && it.height == 0.80f &&
                it.aspectRatio == "16:9"
            }.forEach {
                it.x = 0f
                it.y = 0f
                it.width = 1f
                it.height = 1f
            }
            selectedElementId = saved.elements.firstOrNull { it.isSelected }?.id
            streamOrientation = saved.orientation
            StreamingConfig.saveOrientationLabel(activity, streamOrientation)
            syncSceneLayersToEncoder()
        }
        activeTemplate = this
        ScreenCaptureService.addStateListener(serviceStateListener)
        popup?.dismiss()
        popup = null
        menuAnchor = null
        page = "transmission"
        root.background = TemplateStyle.Backdrop()
        registerLifecycleObserver()
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val width = r - l
            val height = b - t
            if (width > 0 && height > 0 && (width != availableWidth || height != availableHeight)) {
                rememberScroll()
                availableWidth = width
                availableHeight = height
                wide = width > height
                root.post { render() }
            }
        }
        activity.setContentView(root)
    }

    private fun registerLifecycleObserver() {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        syncSystemPermissions()
        activity.application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {
                if (a === activity) {
                    activityInBackground = false
                    // Quando voltar para o APK, a sobreposição de tela do smartphone some
                    removeSystemOverlayWindow()
                }
            }
            override fun onActivityResumed(a: Activity) {
                if (a === activity) {
                    activityInBackground = false
                    syncSystemPermissions()
                    streamConnected = ScreenCaptureService.isLiveStreaming
                    streamConnecting = ScreenCaptureService.isConnecting
                    // Quando está dentro do APK, a sobreposição de tela some
                    removeSystemOverlayWindow()
                    if (pendingConnectAfterPermissions && canDrawSystemOverlay()) {
                        pendingConnectAfterPermissions = false
                        showPermissionsModal = false
                        micPermGranted = true
                        galleryPermGranted = true
                        overlayPermGranted = true
                        root.post { verifyAndStartLiveFlow() }
                    } else {
                        root.post { render() }
                    }
                }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {
                if (a === activity) {
                    activityInBackground = true
                    // Quando você minimiza o APK para o smartphone, a sobreposição de tela volta a aparecer
                    if ((streamConnected || ScreenCaptureService.isLiveStreaming) && canDrawSystemOverlay()) {
                        showSystemOverlayWindow()
                    }
                }
            }
            override fun onActivitySaveInstanceState(a: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                if (a === activity) {
                    ScreenCaptureService.removeStateListener(serviceStateListener)
                    if (activeTemplate === this@NativeTemplate) {
                        activeTemplate = null
                    }
                    removeSystemOverlayWindow()
                }
            }
        })
    }

    private fun rememberScroll() {
        currentScroll?.let { scrollPositions[page] = it.scrollY }
    }

    private fun navigate(destination: String) {
        rememberScroll()
        popup?.dismiss()
        popup = null
        page = destination
        if (destination == "controls") loadServerScenarios()
        activity.requestedOrientation = if (destination == "editor") {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        render()
    }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private fun label(value: String, size: Float = 10.5f, bold: Boolean = false, color: Int = TemplateStyle.white) =
        TextView(activity).apply {
            text = value
            setTextColor(color)
            // Preserva o tamanho individual de cada componente e reduz mais 3 pixels físicos.
            val sizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, size, activity.resources.displayMetrics
            )
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (sizePx - 5f).coerceAtLeast(6f))
            typeface = TemplateStyle.font(activity, bold)
            includeFontPadding = false
        }

    private fun ViewGroup?.safeAdd(child: View?, params: ViewGroup.LayoutParams? = null) {
        if (this == null || child == null) return
        if (params != null) addView(child, params) else addView(child)
    }

    private fun add(parent: LinearLayout?, child: View?, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) {
        parent.safeAdd(
            child,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (height >= 0) dp(height) else height
            ).apply { topMargin = dp(top) }
        )
    }

    private fun weighted(
        parent: LinearLayout?,
        child: View?,
        weight: Float = 1f,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        gap: Int = 3
    ) {
        parent.safeAdd(
            child,
            LinearLayout.LayoutParams(
                0,
                if (height >= 0) dp(height) else height,
                weight
            ).apply {
                marginStart = dp(gap)
                marginEnd = dp(gap)
            }
        )
    }

    private fun icon(name: String, tint: Int = TemplateStyle.cyan) = TemplateStyle.Icon(activity, name, tint)

    private fun card(radius: Float = 16f, prominent: Boolean = true, borderTint: Int? = null) = column().apply {
        background = TemplateStyle.Panel(density, false, radius, prominent, borderTint)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun action(
        title: String,
        symbol: String? = null,
        selected: Boolean = false,
        stacked: Boolean = false,
        radius: Float = 10f,
        tint: Int? = null,
        click: () -> Unit = { toast("Comando executado.") }
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(38)
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = TemplateStyle.Panel(density, selected, radius, selected, tint)
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { click() }

            if (symbol != null) {
                addView(
                    icon(symbol, if (selected) TemplateStyle.white else (tint ?: TemplateStyle.cyan)),
                    LinearLayout.LayoutParams(dp(if (stacked) 21 else 17), dp(if (stacked) 21 else 17)).apply {
                        if (stacked) bottomMargin = dp(3) else marginEnd = dp(6)
                    }
                )
            }
            if (title.isNotEmpty()) {
                addView(
                    label(title, if (stacked) 7.5f else 9.5f, true).apply {
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(
                        if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
    }

    private fun iconAction(
        symbol: String,
        description: String,
        radius: Float = 10f,
        click: () -> Unit = { toast("Ação: $description") }
    ): View = action("", symbol, radius = radius, click = click).apply {
        contentDescription = description
        minimumWidth = dp(40)
    }

    // =========================================================================
    // RENDER DISPATCHER
    // =========================================================================
    private var renderScheduled = false

    private fun render() {
        if (availableWidth <= 0) return
        val currentRoot = root ?: return
        if (renderScheduled) return
        renderScheduled = true
        currentRoot.post {
            renderScheduled = false
            if (availableWidth <= 0) return@post
            try {
                popup?.dismiss()
                popup = null
                menuAnchor = null
                currentScroll = null
                expandedPreview = null
                countdownLabel = null
                currentRoot.removeAllViews()

                val shell = column().apply {
                    setPadding(dp(if (wide) 12 else 8), dp(4), dp(if (wide) 12 else 8), dp(6))
                }
                currentRoot.safeAdd(shell, FrameLayout.LayoutParams(-1, -1))

                // Screen Header
                if (page == "editor") {
                    add(shell, editorHeader(), if (wide) 52 else 64)
                    shell.safeAdd(editor(), LinearLayout.LayoutParams(-1, 0, 1f))
                    if (showPermissionsModal) {
                        attachPermissionsModal()
                    }
                    if (countdownSeconds > 0) {
                        attachCountdownOverlay()
                    }
                    return@post
                }

                add(shell, header(), if (wide) 52 else 66)

                // Body Content
                when (page) {
                    "transmission" -> {
                        shell.safeAdd(home(), LinearLayout.LayoutParams(-1, 0, 1f))
                        add(shell, navigation(), if (wide) 50 else 66, 4)
                    }
                    "controls" -> {
                        shell.safeAdd(controls(), LinearLayout.LayoutParams(-1, 0, 1f))
                        if (!wide) add(shell, navigation(), 66, 4)
                    }
                    "encoder" -> {
                        val scroll = ScrollView(activity).apply {
                            isFillViewport = true
                            isVerticalScrollBarEnabled = false
                            clipToPadding = false
                        }
                        currentScroll = scroll
                        val body = encoder()
                        scroll.safeAdd(body, FrameLayout.LayoutParams(-1, -2))
                        shell.safeAdd(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
                        scroll.post { scroll.scrollTo(0, scrollPositions[page] ?: 0) }
                        if (!wide) add(shell, navigation(), 66, 4)
                    }
                    "platforms" -> {
                        val scroll = ScrollView(activity).apply {
                            isFillViewport = true
                            isVerticalScrollBarEnabled = false
                            clipToPadding = false
                        }
                        currentScroll = scroll
                        val body = platforms()
                        scroll.safeAdd(body, FrameLayout.LayoutParams(-1, -2))
                        shell.safeAdd(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
                        scroll.post { scroll.scrollTo(0, scrollPositions[page] ?: 0) }
                        add(shell, navigation(), if (wide) 50 else 66, 4)
                    }
                }

                if (showPermissionsModal) {
                    attachPermissionsModal()
                }
                if (countdownSeconds > 0) {
                    attachCountdownOverlay()
                }
            } catch (e: Throwable) {
                Log.e("NativeTemplate", "Erro ao renderizar: ${e.message}", e)
            }
        }
    }

    // =========================================================================
    // COMMON HEADER (Images 1, 2, 3, 5, 6, 7, 8, 9)
    // =========================================================================
    private fun header(): View = row().apply {
        val isSubPage = page == "encoder" || page == "platforms" || page == "controls"

        // Left Action: Back or Menu
        val leftButton = action(
            "",
            if (isSubPage) "back" else "menu",
            radius = 12f
        ) {
            if (isSubPage) navigate("transmission") else showMenu()
        }
        addView(leftButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Center Brand Logo
        val brand = TemplateStyle.Brand(activity)
        addView(brand, LinearLayout.LayoutParams(0, dp(if (wide) 50 else 60), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })

        // Right Action: Real Status Badge and optional Gear icon
        val isLive = ScreenCaptureService.isLiveStreaming || streamConnected
        val isConn = ScreenCaptureService.isConnecting || streamConnecting || countdownSeconds > 0
        val badgeText = when {
            isLive -> "Conectado"
            isConn -> "Conectando"
            else -> "Offline"
        }
        val badgeColor = when {
            isLive -> TemplateStyle.green
            isConn -> TemplateStyle.cyan
            else -> TemplateStyle.muted
        }
        val badge = TemplateStyle.ConnectionBadge(activity, badgeText, badgeColor)
        addView(badge, LinearLayout.LayoutParams(dp(112), dp(32)))

        if (wide) {
            val gear = iconAction("gear", "Configurações", radius = 10f) { navigate("encoder") }
            addView(gear, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
        }
    }

    // =========================================================================
    // BOTTOM NAVIGATION DOCK (Images 1, 5, 7)
    // =========================================================================
    private fun navigation(): View = row().apply {
        val tabs = listOf(
            Triple("MENU", "menu", "menu"),
            Triple("TRANSMISSÃO", "broadcast", "transmission"),
            Triple("CONFIGURAÇÃO", "gear", "encoder"),
            Triple("PLATAFORMAS", "layers", "platforms")
        )
        tabs.forEach { (title, symbol, dest) ->
            val isSelected = when (dest) {
                "transmission" -> page == "transmission"
                "encoder" -> page == "encoder"
                "platforms" -> page == "platforms"
                else -> false
            }
            val button = action(title, symbol, isSelected, stacked = true, radius = 12f) {
                if (dest == "menu") showMenu() else navigate(dest)
            }
            weighted(this, button, 1f, if (wide) 48 else 62, gap = 2)
            if (dest == "menu") menuAnchor = button
        }
    }

    // =========================================================================
    // QUICK MENU POPUP (Image 1)
    // =========================================================================
    private fun showMenu() {
        if (popup?.isShowing == true) {
            popup?.dismiss()
            popup = null
            return
        }
        val anchor = menuAnchor ?: root
        val panel = card(radius = 18f).apply {
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }

        // Header with Logo and Close 'X'
        val heading = row()
        heading.addView(TemplateStyle.Brand(activity, "MENU RÁPIDO"), LinearLayout.LayoutParams(0, dp(44), 1f))
        heading.addView(iconAction("close", "Fechar menu", radius = 10f) { popup?.dismiss() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        add(panel, heading)

        // Menu Items: Cenários & Controles
        add(panel, action("Cenários", "layers", selected = true, radius = 12f) {
            navigate("editor")
        }, 46, top = 8)

        add(panel, action("Controles", "game", selected = false, radius = 12f) {
            navigate("controls")
        }, 46, top = 6)

        popup = PopupWindow(
            panel,
            min(dp(290), (availableWidth - dp(24)).coerceAtLeast(dp(1))),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(16).toFloat()
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST)
            )
            val pos = IntArray(2)
            anchor.getLocationOnScreen(pos)
            showAtLocation(
                root,
                Gravity.TOP or Gravity.START,
                dp(12),
                maxOf(dp(64), pos[1] - panel.measuredHeight - dp(8))
            )
        }
    }

    // =========================================================================
    // 16:9 OR 9:16 ASPECT RATIO CONTAINER
    // =========================================================================
    private class SixteenByNine(
        context: Context,
        private val vertical: Boolean = false
    ) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
            val availableHeight = if (heightMode == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else View.MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            val ratioW = if (vertical) 9L else 16L
            val ratioH = if (vertical) 16L else 9L
            val fittedWidth = if (availableHeight == Int.MAX_VALUE) availableWidth
            else min(availableWidth, (availableHeight.toLong() * ratioW / ratioH).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val fittedHeight = (fittedWidth.toLong() * ratioH / ratioW).toInt()
            super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(fittedWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fittedHeight, View.MeasureSpec.EXACTLY)
            )
        }
    }

    // =========================================================================
    // 1. TRANSMISSION HOME SCREEN (Images 1 & 5)
    // =========================================================================
    private fun home(): View = column().apply {
        gravity = Gravity.CENTER

        val isVertical = streamOrientation == "Vertical (9:16)"

        val preview = SixteenByNine(activity, vertical = isVertical).apply {
            background = TemplateStyle.Panel(density, false, radius = 0f, glow = true)
            setPadding(dp(6), dp(6), dp(6), dp(6))

            val inner = FrameLayout(activity).apply {
                background = TemplateStyle.Backdrop()
            }
            addView(inner, FrameLayout.LayoutParams(-1, -1))

            val centerContent = column().apply {
                gravity = Gravity.CENTER
            }
            inner.addView(centerContent, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

            // Large glowing play triangle
            centerContent.addView(icon("play"), LinearLayout.LayoutParams(dp(if (isVertical) 60 else 76), dp(if (isVertical) 60 else 76)))

            if (wide) {
                centerContent.addView(
                    label("LiveNEXUS", if (isVertical) 18f else 26f, bold = true).apply {
                        gravity = Gravity.CENTER
                        typeface = TemplateStyle.font(activity, bold = true)
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) }
                )
                centerContent.addView(
                    label("— CENTRAL DE TRANSMISSÃO —", if (isVertical) 7.5f else 8.5f, bold = false, color = TemplateStyle.muted).apply {
                        gravity = Gravity.CENTER
                        letterSpacing = 0.22f
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) }
                )
            } else {
                centerContent.addView(
                    label("MINIATURA", if (isVertical) 20f else 26f, bold = true).apply {
                        gravity = Gravity.CENTER
                        letterSpacing = 0.08f
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) }
                )
            }
        }

        val maxWidth = if (wide) (availableWidth * 0.78f).toInt() else -1
        val maxHeight = if (wide) {
            (availableHeight * 0.54f).toInt()
        } else {
            (availableHeight * (if (isVertical) 0.44f else 0.36f)).toInt()
        }
        addView(preview, LinearLayout.LayoutParams(maxWidth, maxHeight).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Botão para alternar o cenário entre Horizontal (16:9) e Vertical (9:16)
        val aspectButtonText = if (isVertical) {
            "Cenário: Vertical (9:16) ⇄ Mudar p/ 16:9"
        } else {
            "Cenário: Horizontal (16:9) ⇄ Mudar p/ 9:16"
        }
        val aspectToggleBtn = action(
            aspectButtonText,
            "screen",
            selected = isVertical,
            radius = 12f
        ) {
            streamOrientation = if (isVertical) "Horizontal (16:9)" else "Vertical (9:16)"
            StreamingConfig.saveOrientationLabel(activity, streamOrientation)
            toast("Cenário alterado para $streamOrientation")
            render()
        }
        val aspectBtnWidth = min(dp(300), (availableWidth * 0.84f).toInt())
        addView(aspectToggleBtn, LinearLayout.LayoutParams(aspectBtnWidth, dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(if (wide) 10 else 14)
        })

        // Botão Conectar / Conectado (Vermelho quando desconectado, Verde quando conectado)
        val hasActivePlatform = twitchEnabled || youtubeEnabled || kickEnabled
        if (!hasActivePlatform && streamConnected) {
            stopLiveStreamAndReset(false)
        }

        val btnPrimary = if (streamConnected) Color.rgb(0, 215, 115) else Color.rgb(255, 34, 60)
        val btnDark = if (streamConnected) Color.rgb(0, 140, 72) else Color.rgb(160, 0, 28)
        val btnDeep = if (streamConnected) Color.rgb(0, 80, 40) else Color.rgb(90, 0, 16)
        val btnBorder = if (streamConnected) TemplateStyle.green else btnPrimary
        val btnText = when {
            streamConnected -> "Conectado"
            streamConnecting -> "Conectando..."
            else -> "Conectar"
        }

        val connectionButton = row().apply {
            gravity = Gravity.CENTER
            background = TemplateStyle.Panel(
                density,
                selected = true,
                radius = 16f,
                glow = true,
                borderTint = btnBorder,
                fillGradient = intArrayOf(btnPrimary, btnDark, btnDeep)
            )
            isClickable = true
            isFocusable = true
            contentDescription = btnText
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener {
                val activePlatforms = mutableListOf<String>()
                if (twitchEnabled) activePlatforms.add("Twitch")
                if (youtubeEnabled) activePlatforms.add("YouTube")
                if (kickEnabled) activePlatforms.add("Kick")

                if (activePlatforms.isEmpty()) {
                    stopLiveStreamAndReset(false)
                    toast("Não há plataforma conectada.")
                } else if (!streamConnected && !streamConnecting && countdownSeconds == 0) {
                    verifyAndStartLiveFlow()
                } else {
                    stopLiveStreamAndReset(true)
                }
            }

            addView(
                icon(if (streamConnected) "check" else "link", TemplateStyle.white),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
            )
            addView(
                label(btnText, 12f, bold = true, color = TemplateStyle.white).apply {
                    letterSpacing = 0.05f
                }
            )
        }

        val btnWidth = min(dp(260), (availableWidth * 0.72f).toInt())
        val btnParams = LinearLayout.LayoutParams(
            btnWidth,
            dp(52)
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(if (wide) 12 else 20)
        }
        addView(connectionButton, btnParams)

        // Cronômetro logo abaixo do botão quando estiver Conectado
        if (streamConnected) {
            val timerBox = row().apply {
                gravity = Gravity.CENTER
                background = TemplateStyle.Panel(density, false, radius = 12f, glow = true, borderTint = TemplateStyle.green)
                setPadding(dp(16), dp(6), dp(16), dp(6))
            }
            val recDot = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(TemplateStyle.green)
                }
            }
            timerBox.addView(recDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            val chronoText = label(formatElapsedTime(), 12f, bold = true, color = TemplateStyle.green).apply {
                letterSpacing = 0.10f
            }
            timerLabel = chronoText
            timerBox.addView(chronoText)

            addView(timerBox, LinearLayout.LayoutParams(-2, dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            })
            root.removeCallbacks(timerRunnable)
            root.postDelayed(timerRunnable, 1000L)
        } else {
            timerLabel = null
        }
    }

    // =========================================================================
    // 2. CONFIGURAÇÃO SCREEN (Images 2 & 3)
    // =========================================================================
    private fun encoder(): LinearLayout = column().apply {
        // Subheader banner: Configuração
        val banner = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        banner.addView(icon("gear", TemplateStyle.white), LinearLayout.LayoutParams(dp(26), dp(26)))
        banner.addView(label("Configuração", 16f, true).apply { marginStart(dp(8)) })
        banner.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))

        // Badge: Configuração de Vídeo e Áudio da Transmissão
        val subBadge = row().apply {
            background = TemplateStyle.Panel(density, false, radius = 16f, glow = false)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        subBadge.addView(icon("video", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(16), dp(16)))
        subBadge.addView(
            label("Configuração de Vídeo e Áudio", 8f, false, TemplateStyle.muted).apply {
                marginStart(dp(6))
            }
        )
        if (wide) banner.addView(subBadge)
        add(this, banner)
        if (!wide) add(this, subBadge, top = 2)

        // Resolução Card (1280x720 / 1920x1080)
        val resCard = card(radius = 14f)
        val resHead = row()
        resHead.addView(icon("screen"), LinearLayout.LayoutParams(dp(18), dp(18)))
        resHead.addView(label("Resolução", 10f, true).apply { marginStart(dp(6)) })
        add(resCard, resHead)
        val resChoices = row()
        listOf("720p", "1080p").forEach { res ->
            weighted(resChoices, action(res, selected = res == resolution, radius = 12f) {
                resolution = res
                StreamingConfig.saveResolutionLabel(activity, res)
                val dims = if (res == "720p") "1280×720" else "1920×1080"
                toast("Resolução do encoder definida: $res ($dims)")
                render()
            }, 1f, 38, 2)
        }
        add(resCard, resChoices, top = 6)

        // FPS Card (30 / 60)
        val fpsCard = card(radius = 14f)
        val fpsHead = row()
        fpsHead.addView(icon("layers"), LinearLayout.LayoutParams(dp(18), dp(18)))
        fpsHead.addView(label("FPS (Taxa de Quadros)", 10f, true).apply { marginStart(dp(6)) })
        add(fpsCard, fpsHead)
        val fpsChoices = row()
        listOf("30 FPS", "60 FPS").forEach { opt ->
            weighted(fpsChoices, action(opt, selected = opt == fps, radius = 12f) {
                fps = opt
                StreamingConfig.saveFpsLabel(activity, opt)
                toast("Taxa de quadros do encoder definida: $opt")
                render()
            }, 1f, 38, 2)
        }
        add(fpsCard, fpsChoices, top = 6)

        if (wide) {
            val topRow = row()
            weighted(topRow, resCard, 1f, gap = 2)
            weighted(topRow, fpsCard, 1f, gap = 2)
            add(this, topRow, top = 4)
        } else {
            add(this, resCard, top = 4)
            add(this, fpsCard, top = 4)
        }

        // Bitrate Card (2000–10000 kbps)
        val bitrateCard = card(radius = 14f)
        val bitHead = row()
        bitHead.addView(icon("broadcast"), LinearLayout.LayoutParams(dp(18), dp(18)))
        bitHead.addView(label("Bitrate de Vídeo", 10f, true).apply { marginStart(dp(6)) })
        bitHead.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
        val bitBadge = label("$bitrate Kbps", 9f, true).apply {
            background = TemplateStyle.Panel(density, false, radius = 8f, glow = false)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bitHead.addView(bitBadge)
        add(bitrateCard, bitHead)

        val bitSeek = customSeekBar(((bitrate - 2000) / 500).coerceIn(0, 16), 16) {
            bitrate = (it * 500 + 2000).coerceIn(2000, 10000)
            bitBadge.text = "$bitrate Kbps"
            StreamingConfig.saveBitrateKbps(activity, bitrate)
            ScreenCaptureService.updateDynamicBitrate(bitrate)
        }
        add(bitrateCard, bitSeek, 32, top = 6)
        val bitLimits = row()
        bitLimits.addView(label("2000 Kbps", 8f, color = TemplateStyle.muted))
        bitLimits.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
        bitLimits.addView(label("10000 Kbps", 8f, color = TemplateStyle.muted))
        add(bitrateCard, bitLimits)

        // Codec Card (H.264 / AAC native MediaCodec)
        val codecCard = card(radius = 14f)
        val codecHead = row()
        codecHead.addView(icon("gear"), LinearLayout.LayoutParams(dp(18), dp(18)))
        codecHead.addView(label("Codec de Vídeo", 10f, true).apply { marginStart(dp(6)) })
        add(codecCard, codecHead)
        val codecSelector = action("H.264     ⌄", radius = 10f) {
            codec = "H.264"
            toast("Codec nativo ativo: Vídeo H.264 (AVC) + Áudio AAC")
        }
        add(codecCard, codecSelector, 38, top = 6)

        if (wide) {
            val midRow = row()
            weighted(midRow, bitrateCard, 1.8f, gap = 2)
            weighted(midRow, codecCard, 1f, gap = 2)
            add(this, midRow, top = 4)
        } else {
            add(this, bitrateCard, top = 4)
            add(this, codecCard, top = 4)
        }

        // Audio & Volume Cards
        val audioCard = card(radius = 14f)
        val audioHead = row()
        audioHead.addView(icon("mic"), LinearLayout.LayoutParams(dp(18), dp(18)))
        audioHead.addView(label("Áudio", 10f, true).apply { marginStart(dp(6)) })
        add(audioCard, audioHead)

        val micRow = row()
        weighted(micRow, label("Microfone (Áudio Externo)", 9f), 1f)
        micRow.addView(neonToggle(micEnabled) {
            micEnabled = it
            StreamingConfig.saveMicEnabled(activity, it)
        })
        add(audioCard, micRow, 34, top = 4)

        val internalRow = row()
        weighted(internalRow, label("Áudio Interno (Som do Dispositivo)", 9f), 1f)
        internalRow.addView(neonToggle(internalAudioEnabled) {
            internalAudioEnabled = it
            StreamingConfig.saveInternalAudioEnabled(activity, it)
        })
        add(audioCard, internalRow, 34, top = 2)

        // Apenas na página Configuração > Áudio. Não replica no menu flutuante.
        val suppressionRow = row()
        weighted(suppressionRow, label("Supressão de ruído", 9f), 1f)
        suppressionRow.addView(neonToggle(noiseSuppressionEnabled) { enabled ->
            noiseSuppressionEnabled = enabled
            StreamingConfig.saveNoiseSuppressionEnabled(activity, enabled)
        })
        add(audioCard, suppressionRow, 34, top = 2)

        val volumeCard = card(radius = 14f)
        val volumeHead = row()
        volumeHead.addView(icon("volume"), LinearLayout.LayoutParams(dp(18), dp(18)))
        volumeHead.addView(label("Volume", 10f, true).apply { marginStart(dp(6)) })
        add(volumeCard, volumeHead)

        val micVolVal = label("$micVolume%", 8.5f, true)
        val micVolRow = row()
        micVolRow.addView(label("Microfone:", 8.5f, color = TemplateStyle.muted), LinearLayout.LayoutParams(dp(86), -2))
        weighted(micVolRow, customSeekBar(micVolume, 100) {
            micVolume = it
            micVolVal.text = "$it%"
            StreamingConfig.saveMicVolume(activity, it)
        }, 1f, 30)
        micVolRow.addView(micVolVal, LinearLayout.LayoutParams(dp(40), -2))
        add(volumeCard, micVolRow, top = 3)

        val intVolVal = label("$internalVolume%", 8.5f, true)
        val intVolRow = row()
        intVolRow.addView(label("Áudio Interno:", 8.5f, color = TemplateStyle.muted), LinearLayout.LayoutParams(dp(86), -2))
        weighted(intVolRow, customSeekBar(internalVolume, 100) {
            internalVolume = it
            intVolVal.text = "$it%"
            StreamingConfig.saveInternalVolume(activity, it)
        }, 1f, 30)
        intVolRow.addView(intVolVal, LinearLayout.LayoutParams(dp(40), -2))
        add(volumeCard, intVolRow, top = 2)

        if (wide) {
            val lowerRow = row()
            weighted(lowerRow, audioCard, 1.2f, gap = 2)
            weighted(lowerRow, volumeCard, 1f, gap = 2)
            add(this, lowerRow, top = 4)
        } else {
            add(this, audioCard, top = 4)
            add(this, volumeCard, top = 4)
        }

        // Action Buttons: Restaurar Padrão, Salvar Configurações, Aplicar
        val footer = row()
        weighted(footer, action("Restaurar\nPadrão", "refresh", radius = 12f) {
            resolution = "1080p"
            streamOrientation = "Horizontal (16:9)"
            fps = "60 FPS"
            bitrate = 6000
            micEnabled = true
            internalAudioEnabled = true
            micVolume = 100
            internalVolume = 100
            StreamingConfig.saveResolutionLabel(activity, resolution)
            StreamingConfig.saveOrientationLabel(activity, streamOrientation)
            StreamingConfig.saveFpsLabel(activity, fps)
            StreamingConfig.saveBitrateKbps(activity, bitrate)
            StreamingConfig.saveMicEnabled(activity, micEnabled)
            StreamingConfig.saveInternalAudioEnabled(activity, internalAudioEnabled)
            StreamingConfig.saveMicVolume(activity, micVolume)
            StreamingConfig.saveInternalVolume(activity, internalVolume)
            ScreenCaptureService.updateDynamicBitrate(bitrate)
            toast("Configurações padrão restauradas no encoder.")
            render()
        }, 1f, 48, 2)

        weighted(footer, action("Salvar Configurações", "save", selected = true, radius = 14f) {
            StreamingConfig.saveResolutionLabel(activity, resolution)
            StreamingConfig.saveOrientationLabel(activity, streamOrientation)
            StreamingConfig.saveFpsLabel(activity, fps)
            StreamingConfig.saveBitrateKbps(activity, bitrate)
            StreamingConfig.saveMicEnabled(activity, micEnabled)
            StreamingConfig.saveInternalAudioEnabled(activity, internalAudioEnabled)
            StreamingConfig.saveMicVolume(activity, micVolume)
            StreamingConfig.saveInternalVolume(activity, internalVolume)
            ScreenCaptureService.updateDynamicBitrate(bitrate)
            toast("Configurações salvas no encoder ($resolution • $fps • $bitrate Kbps)!")
        }, 1.6f, 48, 2)

        weighted(footer, action("Aplicar", "check", radius = 12f) {
            StreamingConfig.saveResolutionLabel(activity, resolution)
            StreamingConfig.saveOrientationLabel(activity, streamOrientation)
            StreamingConfig.saveFpsLabel(activity, fps)
            StreamingConfig.saveBitrateKbps(activity, bitrate)
            StreamingConfig.saveMicEnabled(activity, micEnabled)
            StreamingConfig.saveInternalAudioEnabled(activity, internalAudioEnabled)
            StreamingConfig.saveMicVolume(activity, micVolume)
            StreamingConfig.saveInternalVolume(activity, internalVolume)
            ScreenCaptureService.updateDynamicBitrate(bitrate)
            toast("Configurações aplicadas ao encoder real.")
        }, 1f, 48, 2)

        add(this, footer, 48, top = 8)
    }

    // =========================================================================
    // 3. PLATAFORMAS SCREEN (Images 6 & 7)
    // =========================================================================
    private fun platforms(): LinearLayout = column().apply {
        // Subheader banner: Plataformas de Transmissão
        val banner = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        banner.addView(icon("layers", TemplateStyle.white), LinearLayout.LayoutParams(dp(26), dp(26)))
        banner.addView(label("Plataformas de Transmissão", 14.5f, true).apply { marginStart(dp(8)) })
        banner.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))

        val linkBadge = row().apply {
            background = TemplateStyle.Panel(density, false, radius = 16f, glow = false)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        linkBadge.addView(icon("link", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(16), dp(16)))
        linkBadge.addView(
            label("Conexão via RTMP / RTMPS", 8f, false, TemplateStyle.muted).apply {
                marginStart(dp(6))
            }
        )
        if (wide) banner.addView(linkBadge)
        add(this, banner)
        if (!wide) add(this, linkBadge, top = 2)

        // 3 Cards: Twitch, YouTube, Kick
        val twitchCard = platformCard(
            name = "Twitch",
            symbol = "twitch",
            themeColor = TemplateStyle.twitch,
            enabled = twitchEnabled,
            url = StreamingConfig.getPlatformUrl(activity, "Twitch"),
            keyRevealed = twitchKeyVisible,
            onToggle = {
                twitchEnabled = it
                StreamingConfig.setPlatformEnabled(activity, "Twitch", it)
            },
            onPeek = { twitchKeyVisible = !twitchKeyVisible; render() }
        )

        val youtubeCard = platformCard(
            name = "YouTube",
            symbol = "youtube",
            themeColor = TemplateStyle.youtube,
            enabled = youtubeEnabled,
            url = StreamingConfig.getPlatformUrl(activity, "YouTube"),
            keyRevealed = youtubeKeyVisible,
            onToggle = {
                youtubeEnabled = it
                StreamingConfig.setPlatformEnabled(activity, "YouTube", it)
            },
            onPeek = { youtubeKeyVisible = !youtubeKeyVisible; render() }
        )

        val kickCard = platformCard(
            name = "Kick",
            symbol = "kick",
            themeColor = TemplateStyle.kick,
            enabled = kickEnabled,
            url = StreamingConfig.getPlatformUrl(activity, "Kick"),
            keyRevealed = kickKeyVisible,
            onToggle = {
                kickEnabled = it
                StreamingConfig.setPlatformEnabled(activity, "Kick", it)
            },
            onPeek = { kickKeyVisible = !kickKeyVisible; render() }
        )

        if (wide) {
            val list = row()
            weighted(list, twitchCard, 1f, gap = 2)
            weighted(list, youtubeCard, 1f, gap = 2)
            weighted(list, kickCard, 1f, gap = 2)
            add(this, list, top = 4)
        } else {
            add(this, twitchCard, top = 4)
            add(this, youtubeCard, top = 4)
            add(this, kickCard, top = 4)
        }
    }

    private fun promptStreamKeyDialog(platformName: String, onSaved: (() -> Unit)? = null) {
        val currentKey = StreamingConfig.getStreamKey(activity, platformName)
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "Cole a Stream Key da $platformName"
            setText(currentKey)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Stream Key • $platformName")
            .setMessage("Insira sua chave de transmissão RTMP/RTMPS (armazenada localmente no dispositivo):")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newKey = input.text?.toString()?.trim().orEmpty()
                StreamingConfig.setStreamKey(activity, platformName, newKey)
                if (newKey.isNotBlank()) {
                    StreamingConfig.setPlatformEnabled(activity, platformName, true)
                    when (platformName) {
                        "Twitch" -> twitchEnabled = true
                        "YouTube" -> youtubeEnabled = true
                        "Kick" -> kickEnabled = true
                    }
                    toast("Stream Key da $platformName salva!")
                } else {
                    toast("Stream Key da $platformName removida.")
                }
                render()
                onSaved?.invoke()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptServerUrlDialog(platformName: String) {
        val currentUrl = StreamingConfig.getPlatformUrl(activity, platformName)
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "rtmp:// ou rtmps://"
            setText(currentUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("Servidor RTMP/RTMPS • $platformName")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newUrl = input.text?.toString()?.trim().orEmpty()
                if (newUrl.startsWith("rtmp://", ignoreCase = true) || newUrl.startsWith("rtmps://", ignoreCase = true)) {
                    StreamingConfig.setPlatformUrl(activity, platformName, newUrl)
                    toast("URL do servidor $platformName atualizada.")
                    render()
                } else {
                    toast("Informe uma URL iniciando com rtmp:// ou rtmps://")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun testPlatformRtmpConnection(platformName: String) {
        val key = StreamingConfig.getStreamKey(activity, platformName)
        if (key.isBlank()) {
            toast("Toque em Stream Key para configurar a chave da $platformName.")
            promptStreamKeyDialog(platformName)
            return
        }
        val baseUrl = StreamingConfig.getPlatformUrl(activity, platformName)
        toast("Testando handshake RTMP/RTMPS em $platformName...")
        Thread({
            val width = if (resolution == "720p") 1280 else 1920
            val height = if (resolution == "720p") 720 else 1080
            val fpsVal = if (fps.startsWith("30")) 30 else 60
            val client = RtmpClient(
                rtmpUrl = baseUrl,
                streamKey = key,
                width = width,
                height = height,
                fps = fpsVal,
                videoBitrateKbps = bitrate
            )
            var errorDetail: String? = null
            val ok = try {
                client.connect()
                true
            } catch (e: Exception) {
                errorDetail = e.message
                false
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {}
            }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (ok) {
                        toast("$platformName: Conexão RTMP/RTMPS verificada com sucesso!")
                    } else {
                        val suffix = errorDetail?.let { " ($it)" } ?: ""
                        toast("$platformName: Falha no handshake RTMP/RTMPS$suffix")
                    }
                    render()
                }
            }
        }, "RtmpTest-$platformName").start()
    }

    private fun platformCard(
        name: String,
        symbol: String,
        themeColor: Int,
        enabled: Boolean,
        url: String,
        keyRevealed: Boolean,
        onToggle: (Boolean) -> Unit,
        onPeek: () -> Unit
    ): View = card(radius = 16f, borderTint = themeColor).apply {
        val storedKey = StreamingConfig.getStreamKey(activity, name)
        val isStreamingThis = ScreenCaptureService.isLiveStreaming && ScreenCaptureService.activePlatforms.contains(name)
        val isReady = enabled && storedKey.isNotBlank()

        // Platform header: Logo + Name + Status + Switch
        val head = row()
        head.addView(icon(symbol, themeColor), LinearLayout.LayoutParams(dp(26), dp(26)))
        head.addView(label(name, 12.5f, true).apply { marginStart(dp(8)) })

        val statusPill = row().apply {
            background = TemplateStyle.Panel(density, false, radius = 10f, glow = false)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            marginStart(dp(8))
        }
        val statusColor = when {
            isStreamingThis -> TemplateStyle.green
            isReady -> TemplateStyle.cyan
            enabled -> TemplateStyle.magenta
            else -> TemplateStyle.muted
        }
        val statusLabel = when {
            isStreamingThis -> " Transmitindo"
            isReady -> " Pronto"
            enabled -> " Sem Chave"
            else -> " Desconectado"
        }
        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(statusColor)
            }
        }
        statusPill.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))
        statusPill.addView(label(statusLabel, 7.5f, true, statusColor))
        head.addView(statusPill)

        head.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(neonToggle(enabled) {
            onToggle(it)
            render()
        })
        add(this, head)

        // URL Field (tapping edits URL, copy button copies to clipboard)
        add(this, label("URL do Servidor (${if (url.startsWith("rtmps", ignoreCase = true)) "RTMPS" else "RTMP"})", 7.5f, true, TemplateStyle.muted), top = 6)
        val urlBox = row().apply {
            background = TemplateStyle.Panel(density, false, radius = 8f, glow = false)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { promptServerUrlDialog(name) }
        }
        urlBox.addView(
            label(url, 8.5f, color = TemplateStyle.white).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        urlBox.addView(iconAction("copy", "Copiar URL", radius = 6f) {
            try {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("RTMP URL", url))
            } catch (_: Throwable) {}
            toast("URL copiada: $url")
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        add(this, urlBox, 36, top = 2)

        // Stream Key Field (tapping opens dialog to set/edit key; never hardcoded)
        add(this, label("Stream Key (toque para configurar)", 7.5f, true, TemplateStyle.muted), top = 4)
        val keyBox = row().apply {
            background = TemplateStyle.Panel(density, false, radius = 8f, glow = false)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            isClickable = true
            setOnClickListener { promptStreamKeyDialog(name) }
        }
        val keyDisplay = when {
            storedKey.isBlank() -> "Toque para inserir Stream Key..."
            keyRevealed -> storedKey
            else -> "••••••••••••••••"
        }
        keyBox.addView(
            label(keyDisplay, 9.5f, true, if (storedKey.isBlank()) TemplateStyle.muted else TemplateStyle.cyan).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        keyBox.addView(iconAction("eye", "Revelar chave", radius = 6f) {
            if (storedKey.isBlank()) {
                promptStreamKeyDialog(name)
            } else {
                onPeek()
            }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        add(this, keyBox, 36, top = 2)

        // Card Action Buttons: Testar & Desconectar
        val btns = row()
        weighted(btns, action("Testar", "broadcast", selected = true, radius = 10f) {
            onToggle(true)
            testPlatformRtmpConnection(name)
        }, 1f, 36, 2)
        weighted(btns, action("Desconectar", "link", selected = false, radius = 10f) {
            onToggle(false)
            if (!twitchEnabled && !youtubeEnabled && !kickEnabled && (streamConnected || ScreenCaptureService.isLiveStreaming)) {
                stopLiveStreamAndReset(false)
            }
            toast("$name desconectado.")
            render()
        }, 1f, 36, 2)
        add(this, btns, 36, top = 6)
    }

    // =========================================================================
    // 4. CONTROLES SCREEN (Images 8 & 9)
    // =========================================================================
    private fun controls(): LinearLayout = column().apply {
        // Title Banner: Controles
        val banner = row().apply {
            gravity = Gravity.CENTER
            background = TemplateStyle.Panel(density, false, radius = 18f, glow = true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        banner.addView(icon("game", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(28), dp(28)))
        banner.addView(label("Controles", 16f, true).apply { marginStart(dp(10)) })
        add(this, banner, if (wide) 48 else 52)

        // Hotkeys Card
        val hotkeys = card(radius = 16f)
        val hotHead = row()
        hotHead.addView(icon("keyboard", TemplateStyle.white), LinearLayout.LayoutParams(dp(24), dp(24)))
        val hotTitles = column()
        hotTitles.addView(label("Hotkeys", 12.5f, true))
        hotTitles.addView(label("Atalhos de cenas, subcenas e ações rápidas", 8f, color = TemplateStyle.muted))
        weighted(hotHead, hotTitles, 1f, gap = 6)
        hotHead.addView(iconAction("info", "Informações dos atalhos", radius = 8f) {
            toast("Hotkeys configuráveis pelo teclado ou toque.")
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        add(hotkeys, hotHead)

        // Hotkey Scene Buttons Grid
        val buttonList = column()
        val buttons = mutableListOf<View>()
        buttons.add(sceneButton("refresh", "Reload", selected = true) {
            reloadControllerScene()
        })
        buttons.add(sceneButton("plus", "Mortes +", selected = false) {
            DeathCounterCommands.send(activity, increase = true) { delivered ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    toast(if (delivered) "Comando + enviado ao contador de mortes." else "Falha ao enviar comando + ao LiveNEXUS.")
                }
            }
        })
        buttons.add(sceneButton("minus", "Mortes −", selected = false) {
            DeathCounterCommands.send(activity, increase = false) { delivered ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    toast(if (delivered) "Comando − enviado ao contador de mortes." else "Falha ao enviar comando − ao LiveNEXUS.")
                }
            }
        })
        buttons.add(sceneButton("plus", "Adicionar\ncenário", selected = false) {
            addSceneButtonSlot()
        })
        for (i in sceneButtonSlots.indices) {
            buttons.add(sceneButton(
                "video", sceneShortcutTitle(i), selected = false,
                longClick = { showSceneButtonOptions(i) }
            ) {
                selectServerScenario(i)
            })
        }

        val columns = if (wide) ((availableWidth / density - 60f) / 95f).toInt().coerceAtLeast(3)
        else if (availableWidth / density < 340f) 2 else 3

        buttons.chunked(columns).forEach { group ->
            val gridLine = row().apply { gravity = Gravity.START }
            group.forEach { b ->
                gridLine.addView(b, LinearLayout.LayoutParams(dp(82), dp(78)).apply {
                    marginEnd = dp(8)
                })
            }
            add(buttonList, gridLine, 78, top = 8)
        }

        val scroller = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(buttonList)
        }
        hotkeys.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(hotkeys, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        // Player (Mídia) Card at Bottom
        val player = card(radius = 16f)
        val mediaBar = row()

        // Equalizer Cover art
        val cover = FrameLayout(activity).apply {
            background = TemplateStyle.Panel(density, true, radius = 10f, glow = false)
            addView(icon("music", TemplateStyle.white), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        mediaBar.addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Empty space (sem escrita nenhuma)
        mediaBar.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))

        // Volume slider
        val volLabel = label("$playerVolume%", 8f, true)
        val volSeek = customSeekBar(playerVolume, 100) {
            val difference = it - playerVolume
            if (difference != 0) {
                playerVolume = it
                volLabel.text = "$it%"
                val command = if (difference > 0) "music-volume-up" else "music-volume-down"
                repeat(kotlin.math.abs(difference)) { sendPlayerCommand(command) }
            }
        }
        mediaBar.addView(icon("volume", TemplateStyle.white), LinearLayout.LayoutParams(dp(18), dp(18)))
        mediaBar.addView(volSeek, LinearLayout.LayoutParams(dp(if (wide) 140 else 90), dp(28)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        mediaBar.addView(volLabel, LinearLayout.LayoutParams(dp(36), -2))

        // Transport Controls: Prev, Pause/Play, Next
        val controlsGroup = row()
        controlsGroup.addView(iconAction("prev_track", "Anterior", radius = 8f) {
            sendPlayerCommand("music-previous")
        }, LinearLayout.LayoutParams(dp(36), dp(36)))

        val playPause = action("", if (playerPlaying) "pause" else "play", selected = true, radius = 10f) {
            sendPlayerCommand("music-toggle") {
                playerPlaying = !playerPlaying
                render()
            }
        }
        controlsGroup.addView(playPause, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })

        controlsGroup.addView(iconAction("next_track", "Próxima", radius = 8f) {
            sendPlayerCommand("music-next")
        }, LinearLayout.LayoutParams(dp(36), dp(36)))

        mediaBar.addView(controlsGroup)
        add(player, mediaBar)
        add(this, player, top = 8)
    }

    private fun sceneButton(
        symbol: String,
        title: String,
        selected: Boolean,
        longClick: (() -> Unit)? = null,
        click: () -> Unit
    ): View =
        column().apply {
            gravity = Gravity.CENTER
            background = TemplateStyle.Panel(density, selected, radius = 12f, glow = selected)
            isClickable = true
            isFocusable = true
            contentDescription = title.replace("\n", " ")
            setOnClickListener { click() }
            if (longClick != null) setOnLongClickListener {
                longClick.invoke()
                true
            }

            if (symbol == "plus" || symbol == "minus") {
                val sign = if (symbol == "plus") "+" else "−"
                safeAdd(label(sign, 23f, true, TemplateStyle.cyan).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(34)))
            } else {
                safeAdd(icon(symbol, if (selected) TemplateStyle.white else TemplateStyle.magenta), LinearLayout.LayoutParams(dp(24), dp(24)))
            }
            safeAdd(label(title, 8f, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2), 0, dp(2), 0)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }

    // =========================================================================
    // 5. EDITOR DE CENÁRIO / OVERLAY (Image 4)
    // =========================================================================
    private fun editorHeader(): View = row().apply {
        addView(TemplateStyle.Brand(activity, ""), LinearLayout.LayoutParams(dp(140), dp(46)))
        addView(label("│  Editor de Cenário / Overlay", 10f, true).apply {
            marginStart(dp(6))
        }, LinearLayout.LayoutParams(0, -2, 1f))

        addView(iconAction("back", "Desfazer", radius = 8f) { toast("Desfazer") }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(iconAction("refresh", "Refazer", radius = 8f) { toast("Refazer") }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) })
        addView(action("Visualizar", "eye", radius = 10f) { showExpandedPreview() }, LinearLayout.LayoutParams(dp(100), dp(38)).apply { marginStart = dp(6) })
        addView(action("Salvar", "save", selected = true, radius = 10f) {
            if (ScenarioStorage.save(activity, streamOrientation, canvasElements.toList())) {
                syncSceneLayersToEncoder()
                StreamingConfig.saveOrientationLabel(activity, streamOrientation)
                toast("Cenário salvo no dispositivo e aplicado ao encoder!")
            } else {
                toast("Não foi possível salvar o cenário. Tente novamente.")
            }
        }, LinearLayout.LayoutParams(dp(86), dp(38)).apply { marginStart = dp(6) })
    }

    private fun editor(): LinearLayout = column().apply {
        val workspace = row().apply {
            gravity = Gravity.TOP
            clipChildren = true
            clipToPadding = true
        }

        // Left Vertical Tool Panel: Somente o botão de cima (cursor), Captura de Tela (screen), Browser Source (browser) e Widgets (grid)
        val toolbar = column().apply {
            background = TemplateStyle.Panel(density, false, radius = 0f, glow = false)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val tools = listOf(
            "cursor" to "Seleção",
            "screen" to "Captura de Tela",
            "browser" to "Browser Source",
            "grid" to "Widgets"
        )
        tools.forEach { (tool, desc) ->
            val isCurrent = editorMode == tool
            val btn = action("", tool, selected = isCurrent, radius = 8f) {
                editorMode = tool
                editorTab = "Todos"
                render()
            }
            btn.contentDescription = desc
            add(toolbar, btn, 38, top = 2)
        }
        workspace.addView(toolbar, LinearLayout.LayoutParams(dp(52), -1))

        // Center Area: Overlay Canvas (Blank by default, receives adjustable elements)
        val center = column().apply {
            setPadding(dp(6), dp(4), dp(6), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }

        val previewBox = FrameLayout(activity)
        val preview = editorCanvasMockup()
        previewBox.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        center.addView(previewBox, LinearLayout.LayoutParams(-1, 0, 1f))

        // Quick Element Adjustment Bar (appears when an element is selected)
        val selElem = getSelectedElement()
        if (selElem != null) {
            val adjustBar = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = TemplateStyle.Panel(density, false, radius = 6f, glow = false)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }
            adjustBar.addView(
                label("Ajustar '${selElem.title}':", 7.5f, true, TemplateStyle.cyan),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
            )
            adjustBar.addView(action("Tela Cheia", "expand", radius = 6f) {
                adjustSelected("fullscreen")
            }, LinearLayout.LayoutParams(-2, dp(28)).apply { marginEnd = dp(4) })

            adjustBar.addView(action("Centralizar", "move", radius = 6f) {
                adjustSelected("center")
            }, LinearLayout.LayoutParams(-2, dp(28)).apply { marginEnd = dp(4) })

            adjustBar.addView(action("＋", "", radius = 6f) {
                adjustSelected("grow")
            }, LinearLayout.LayoutParams(dp(30), dp(28)).apply { marginEnd = dp(4) })

            adjustBar.addView(action("－", "", radius = 6f) {
                adjustSelected("shrink")
            }, LinearLayout.LayoutParams(dp(30), dp(28)).apply { marginEnd = dp(4) })

            adjustBar.addView(action("Remover", "trash", radius = 6f) {
                removeSelectedElement()
            }, LinearLayout.LayoutParams(-2, dp(28)))

            add(center, adjustBar, 32, top = 3)
        }

        // Bottom Canvas Adjustment Bar
        val footer = row().apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        footer.addView(label("Ajuste do canvas", 8f, color = TemplateStyle.muted), LinearLayout.LayoutParams(-2, -2))
        val isVerticalCanvas = streamOrientation == "Vertical (9:16)"
        val canvasRatioText = if (isVerticalCanvas) "1080 x 1920 (9:16) ⌄" else "1920 x 1080 (16:9) ⌄"
        footer.addView(
            label(canvasRatioText, 8f, true).apply {
                background = TemplateStyle.Panel(density, false, radius = 6f, glow = false)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                marginStart(dp(10))
                isClickable = true
                setOnClickListener {
                    streamOrientation = if (isVerticalCanvas) "Horizontal (16:9)" else "Vertical (9:16)"
                    StreamingConfig.saveOrientationLabel(activity, streamOrientation)
                    render()
                }
            }
        )

        // Botão ao lado do Ajuste do canvas (visível quando a Captura de Tela está acionada e selecionada)
        // Muda somente a Captura de Tela entre 16:9 e 9:16
        if (selElem != null && selElem.type == "screen_capture") {
            val isVertCap = selElem.aspectRatio == "9:16"
            val capBtnLabel = if (isVertCap) "Captura: 9:16 ⇄ 16:9" else "Captura: 16:9 ⇄ 9:16"
            val capRatioBtn = action(capBtnLabel, "screen", selected = isVertCap, radius = 6f) {
                if (selElem.aspectRatio == "16:9") {
                    selElem.aspectRatio = "9:16"
                    selElem.width = 0.34f
                    selElem.height = 0.84f
                    selElem.x = 0.33f
                    selElem.y = 0.08f
                    toast("Captura de Tela alterada para 9:16 (Vertical)")
                } else {
                    selElem.aspectRatio = "16:9"
                    selElem.width = 1f
                    selElem.height = 1f
                    selElem.x = 0f
                    selElem.y = 0f
                    toast("Captura de Tela alterada para 16:9 (Horizontal)")
                }
                syncSceneLayersToEncoder()
                render()
            }
            capRatioBtn.setPadding(dp(8), dp(2), dp(8), dp(2))
            footer.addView(capRatioBtn, LinearLayout.LayoutParams(-2, dp(30)).apply {
                marginStart = dp(8)
            })
        }

        footer.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(iconAction("back", "Diminuir zoom", radius = 6f) {
            zoom = maxOf(20, zoom - 10)
            render()
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        footer.addView(label("$zoom%", 8f, true).apply {
            gravity = Gravity.CENTER
            width = dp(46)
        })
        footer.addView(iconAction("next", "Aumentar zoom", radius = 6f) {
            zoom = minOf(200, zoom + 10)
            render()
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        footer.addView(iconAction("expand", "Tela cheia", radius = 6f) {
            showExpandedPreview()
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(6) })

        add(center, footer, 38, top = 2)
        workspace.addView(center, LinearLayout.LayoutParams(0, -1, 1f))

        // Right Sidebar: Elementos
        val elements = column().apply {
            background = TemplateStyle.Panel(density, false, radius = 0f, glow = false)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val elHead = row()
        elHead.addView(icon("layers", TemplateStyle.white), LinearLayout.LayoutParams(dp(20), dp(20)))
        elHead.addView(label("Elementos", 11f, true).apply { marginStart(dp(6)) })
        add(elements, elHead)

        // Tabs: Todos, Cenas, Camadas
        val tabsRow = row()
        listOf("Todos", "Cenas", "Camadas").forEach { tab ->
            weighted(tabsRow, action(tab, selected = editorTab == tab, radius = 8f) {
                editorTab = tab
                render()
            }, 1f, 30, 1)
        }
        add(elements, tabsRow, 32, top = 4)

        // Container FIXO na lateral direita entre os botões Todos | Cenas | Camadas e o botão Voltar
        // O container permanece fixo e os elementos rolam somente dentro dele
        val fixedContainer = FrameLayout(activity).apply {
            background = TemplateStyle.Panel(density, false, radius = 0f, glow = false)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipChildren = true
            clipToPadding = true
        }

        val dynamicContent = column()
        populateDynamicContainer(dynamicContent)

        val elScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipChildren = true
            clipToPadding = true
            addView(dynamicContent, FrameLayout.LayoutParams(-1, -2))
        }
        fixedContainer.addView(elScroll, FrameLayout.LayoutParams(-1, -1))

        elements.addView(fixedContainer, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        // Bottom Voltar button
        add(elements, action("Voltar", "back", selected = true, radius = 10f) {
            navigate("transmission")
        }, 40, top = 4)

        val rightWidth = (availableWidth * 0.28f).coerceIn(dp(150).toFloat(), dp(210).toFloat()).toInt()
        workspace.addView(elements, LinearLayout.LayoutParams(rightWidth, -1))

        addView(workspace, LinearLayout.LayoutParams(-1, -1))
    }

    /**
     * Populates the scrollable list inside the fixed right sidebar container
     * based on which tool/menu is currently selected on the left vertical toolbar.
     */
    private fun populateDynamicContainer(box: LinearLayout) {
        if (editorTab == "Cenas") {
            val head = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(6))
            }
            head.addView(icon("video", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(18), dp(18)))
            head.addView(label("Cenas do Projeto", 9f, true, TemplateStyle.cyan).apply { marginStart(dp(6)) })
            add(box, head)

            val sceneItem = row().apply {
                background = TemplateStyle.Panel(density, true, radius = 6f, glow = true)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(icon("video", TemplateStyle.white), LinearLayout.LayoutParams(dp(18), dp(18)))
                val col = column().apply { marginStart(dp(6)) }
                col.addView(label("Cena 1 (Principal)", 8f, true))
                col.addView(label("1920x1080 • Ao Vivo", 7f, false, TemplateStyle.muted))
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label("● ATIVA", 7f, true, TemplateStyle.green))
            }
            add(box, sceneItem, top = 4)
            return
        }

        if (editorTab == "Camadas") {
            val head = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(6))
            }
            head.addView(icon("layers", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(18), dp(18)))
            head.addView(label("Camadas (${canvasElements.size})", 9f, true, TemplateStyle.cyan).apply { marginStart(dp(6)) })
            add(box, head)

            if (canvasElements.isEmpty()) {
                val emptyBox = column().apply {
                    background = TemplateStyle.Panel(density, false, radius = 6f, glow = false)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    addView(label("Nenhuma camada no cenário", 7.5f, true, TemplateStyle.subtext))
                    addView(label("Selecione Captura de Tela, Browser Source ou Widgets à esquerda.", 7f, false, TemplateStyle.muted).apply {
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                add(box, emptyBox, top = 4)
            } else {
                canvasElements.forEach { elem ->
                    val layerRow = row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = TemplateStyle.Panel(density, elem.isSelected, radius = 6f, glow = elem.isSelected)
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                        isClickable = true
                        setOnClickListener {
                            canvasElements.forEach { it.isSelected = (it.id == elem.id) }
                            selectedElementId = elem.id
                            render()
                        }
                    }
                    layerRow.addView(
                        icon(
                            when (elem.type) {
                                "screen_capture" -> "screen"
                                "browser" -> "browser"
                                "widget" -> "grid"
                                else -> "layers"
                            },
                            if (elem.isSelected) TemplateStyle.white else TemplateStyle.cyan
                        ),
                        LinearLayout.LayoutParams(dp(16), dp(16))
                    )
                    layerRow.addView(
                        label(elem.title, 7.5f, elem.isSelected).apply {
                            marginStart(dp(4))
                            maxLines = 1
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                    layerRow.addView(iconAction("trash", "Remover", radius = 4f) {
                        canvasElements.remove(elem)
                        if (selectedElementId == elem.id) selectedElementId = canvasElements.lastOrNull()?.id
                        canvasElements.lastOrNull()?.isSelected = true
                        syncSceneLayersToEncoder()
                        toast("Elemento removido.")
                        render()
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                    add(box, layerRow, 30, top = 2)
                }
            }
            return
        }

        // Helper to build a sleek Image-4 style element row inside the fixed container
        fun elementRow(title: String, subtitle: String, iconName: String, isActive: Boolean = false, onAddToEditor: () -> Unit) {
            val item = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = TemplateStyle.Panel(density, isActive, radius = 8f, glow = isActive)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                setOnClickListener { onAddToEditor() }
            }
            item.addView(
                icon(iconName, if (isActive) TemplateStyle.white else TemplateStyle.cyan),
                LinearLayout.LayoutParams(dp(18), dp(18))
            )
            val textCol = column().apply {
                marginStart(dp(6))
            }
            textCol.addView(label(title, 8f, true).apply { maxLines = 1 })
            textCol.addView(label(subtitle, 7f, false, TemplateStyle.muted).apply { maxLines = 1 })
            item.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(
                icon("next", if (isActive) TemplateStyle.white else TemplateStyle.muted),
                LinearLayout.LayoutParams(dp(14), dp(14))
            )
            add(box, item, top = 4)
        }

        val head = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }

        when (editorMode) {
            "screen" -> {
                head.addView(icon("screen", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(18), dp(18)))
                head.addView(label("Captura de Tela", 9f, true, TemplateStyle.cyan).apply { marginStart(dp(6)) })
                add(box, head)

                val isScreenAdded = canvasElements.any { it.title == "Captura de Tela" }
                val isGameAdded = canvasElements.any { it.title == "Captura de Jogos" }
                elementRow(
                    title = "Captura de Tela",
                    subtitle = if (isScreenAdded) "Ativo no editor (toque para focar)" else "Captura da tela do smartphone",
                    iconName = "screen",
                    isActive = isScreenAdded
                ) {
                    addScreenCapture()
                }
                elementRow(
                    title = "Captura de Jogos",
                    subtitle = if (isGameAdded) "Ativo no editor (toque para focar)" else "Captura de jogos no smartphone",
                    iconName = "game",
                    isActive = isGameAdded
                ) {
                    val existingGame = canvasElements.find { it.title == "Captura de Jogos" }
                    if (existingGame != null) {
                        canvasElements.forEach { it.isSelected = (it.id == existingGame.id) }
                        selectedElementId = existingGame.id
                        toast("Captura de Jogos selecionada!")
                        render()
                    } else {
                        addGenericElement("Captura de Jogos", "screen_capture")
                    }
                }
            }
            "browser" -> {
                head.addView(icon("browser", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(18), dp(18)))
                head.addView(label("Browser Source", 9f, true, TemplateStyle.cyan).apply { marginStart(dp(6)) })
                add(box, head)

                val isBrowserAdded = canvasElements.any { it.type == "browser" }
                elementRow(
                    title = "Browser Source (URL)",
                    subtitle = if (isBrowserAdded) "Ativo no editor (toque para adicionar)" else "Toque para adicionar ao editor",
                    iconName = "browser",
                    isActive = isBrowserAdded
                ) {
                    addGenericElement("Browser Source (URL)", "browser")
                }
            }
            "grid" -> {
                head.addView(icon("grid", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(18), dp(18)))
                head.addView(label("Widgets", 9f, true, TemplateStyle.cyan).apply { marginStart(dp(6)) })
                add(box, head)

                val noticeBox = column().apply {
                    background = TemplateStyle.Panel(density, false, radius = 8f, glow = false)
                    setPadding(dp(10), dp(14), dp(10), dp(14))
                    addView(label("Widgets serão adicionadas mais à frente.", 7.5f, true, TemplateStyle.muted).apply {
                        gravity = Gravity.CENTER
                    })
                }
                add(box, noticeBox, top = 6)
            }
            else -> {
                val sel = getSelectedElement()
                if (sel != null) {
                    head.addView(icon("cursor", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(18), dp(18)))
                    head.addView(label("Elemento Selecionado", 9f, true, TemplateStyle.cyan).apply { marginStart(dp(6)) })
                    add(box, head)

                    val infoCard = column().apply {
                        background = TemplateStyle.Panel(density, true, radius = 6f, glow = true)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        addView(label(sel.title, 8f, true, TemplateStyle.white).apply { setPadding(0, 0, 0, dp(4)) })
                        addView(label("Posição: X ${(sel.x * 100).toInt()}% • Y ${(sel.y * 100).toInt()}%", 7f, false, TemplateStyle.muted))
                        addView(label("Dimensão: L ${(sel.width * 100).toInt()}% • A ${(sel.height * 100).toInt()}%", 7f, false, TemplateStyle.muted))
                    }
                    add(box, infoCard, top = 4)

                    val btnRow = row()
                    weighted(btnRow, action("Centralizar", "move", radius = 6f) {
                        adjustSelected("center")
                    }, 1f, 32, 2)
                    weighted(btnRow, action("Remover", "trash", radius = 6f) {
                        removeSelectedElement()
                    }, 1f, 32, 2)
                    add(box, btnRow, 32, top = 6)
                }
            }
        }
    }

    private fun configureBrowserSource(element: CanvasElement) {
        if (element.type != "browser" || activity.isFinishing || activity.isDestroyed) return
        val field = EditText(activity).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://..."
            setText(element.url)
            setSelection(text.length)
        }
        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("Browser Source - URL")
            .setView(field)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = BrowserSourceManager.validUrl(field.text.toString())
                if (url == null) {
                    field.error = "Informe uma URL HTTPS válida."
                    return@setOnClickListener
                }
                element.url = url
                syncSceneLayersToEncoder()
                render()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addGenericElement(title: String, type: String) {
        canvasElements.forEach { it.isSelected = false }
        val newElem = CanvasElement(
            id = "${type}_${System.currentTimeMillis()}",
            type = type,
            title = title,
            x = if (type == "screen_capture") 0f else 0.20f,
            y = if (type == "screen_capture") 0f else 0.20f,
            width = if (type == "screen_capture") 1f else 0.60f,
            height = if (type == "screen_capture") 1f else 0.50f,
            isSelected = true
        )
        canvasElements.add(newElem)
        selectedElementId = newElem.id
        syncSceneLayersToEncoder()
        toast("Elemento '$title' adicionado ao cenário!")
        render()
    }

    /**
     * Builds the interactive Scenario Canvas matching Image 4:
     * Completely blank by default, displaying added elements (like Captura de Tela)
     * as fully interactive, movable, and resizable objects with 8 adjustment handles.
     */
    private fun editorCanvasMockup(): View {
        val isVertical = streamOrientation == "Vertical (9:16)"
        val frame = SixteenByNine(activity, vertical = isVertical)
        frame.background = TemplateStyle.Panel(density, false, radius = 0f, glow = true)
        val container = FrameLayout(activity).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        frame.addView(container, FrameLayout.LayoutParams(-1, -1))

        val hud = CyberneticOverlayView(
            activity,
            canvasElements,
            interactive = true,
            onSelectionChanged = {
                selectedElementId = canvasElements.firstOrNull { it.isSelected }?.id
                syncSceneLayersToEncoder()
                render()
            },
            onGeometryChanged = {
                syncSceneLayersToEncoder()
            },
            onBrowserDoubleTap = { element ->
                configureBrowserSource(element)
            }
        )
        container.addView(hud, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun showExpandedPreview() {
        if (expandedPreview != null || availableWidth <= 0 || availableHeight <= 0) return
        popup?.dismiss()
        val isVertical = streamOrientation == "Vertical (9:16)"
        val cover = FrameLayout(activity).apply {
            setBackgroundColor(0xfa020410.toInt())
            isClickable = true
        }
        val maxWidth = min(
            availableWidth - dp(24),
            ((availableHeight - dp(90)).coerceAtLeast(dp(90)) * (if (isVertical) 9f / 16f else 16f / 9f)).toInt()
        )
        val panel = column().apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val preview = SixteenByNine(activity, vertical = isVertical).apply {
            addView(CyberneticOverlayView(activity, canvasElements, interactive = false), FrameLayout.LayoutParams(-1, -1))
        }
        add(panel, preview)
        add(panel, action("Fechar Visualização", "close", selected = true, radius = 12f) {
            root.removeView(cover)
            expandedPreview = null
        }, 44, top = 8)

        cover.addView(panel, FrameLayout.LayoutParams(maxWidth, -2, Gravity.CENTER))
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        expandedPreview = cover
    }

    // =========================================================================
    // INTERACTIVE CANVAS VIEW (Blank workspace + Adjustable Elements)
    // =========================================================================
    private class CyberneticOverlayView(
        context: Context,
        private val elements: MutableList<CanvasElement>,
        private val interactive: Boolean = true,
        private val onSelectionChanged: (() -> Unit)? = null,
        private val onGeometryChanged: (() -> Unit)? = null,
        private val onBrowserDoubleTap: ((CanvasElement) -> Unit)? = null
    ) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val density = context.resources.displayMetrics.density

        private var activeHandle = -1
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var selectionChangedDuringTouch = false
        private var geometryChangedDuringTouch = false
        private var previewFrame: Bitmap? = null
        private var touchElementId: String? = null
        private var lastTapElementId: String? = null
        private var lastTapTime = 0L
        private var touchDownX = 0f
        private var touchDownY = 0f

        // Reaproveita a captura do serviço ativo; jamais inicia uma segunda MediaProjection.
        private val previewRefresh = object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                val hasCapture = elements.any { it.type == "screen_capture" }
                val hasBrowser = elements.any { it.type == "browser" && it.url.isNotBlank() }
                val next = if (hasCapture && ScreenCaptureService.isLiveStreaming) {
                    ScreenCaptureService.getPreviewSnapshot(480)
                } else {
                    null
                }
                val previous = previewFrame
                previewFrame = next
                if (previous !== next && previous != null && !previous.isRecycled) previous.recycle()
                if (hasCapture || hasBrowser) invalidate()
                postDelayed(this, if (hasBrowser) 100L else if (hasCapture) 1000L else 1500L)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post(previewRefresh)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(previewRefresh)
            previewFrame?.let { if (!it.isRecycled) it.recycle() }
            previewFrame = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            // 1. Clean Obsidian Canvas Background
            p.style = Paint.Style.FILL
            p.color = Color.rgb(8, 12, 22)
            c.drawRect(0f, 0f, w, h, p)

            val margin = 8f * density

            // Subtle 16:9 safe boundary frame (containers without rounding)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f * density
            p.color = Color.argb(40, 0, 234, 255)
            c.drawRect(margin, margin, w - margin, h - margin, p)

            // 2. BLANK STATE: Keep completely clean when no elements are added yet
            if (elements.isEmpty()) {
                return
            }

            // 3. RENDER ADDED ELEMENTS (Captura de Tela, Textos, etc.)
            for (el in elements) {
                val ex = el.x * w
                val ey = el.y * h
                val ew = el.width * w
                val eh = el.height * h
                val er = ex + ew
                val eb = ey + eh

                if (el.type == "screen_capture") {
                    // Preview acompanha a mesma correcao de margem esquerda do encoder.
                    val captureLeft = if (
                        ex > 0f && ex <= w * 0.06f && er >= w * 0.96f
                    ) 0f else ex
                    p.style = Paint.Style.FILL
                    p.color = Color.rgb(10, 16, 32)
                    c.drawRect(captureLeft, ey, er, eb, p)

                    val capturedFrame = previewFrame
                    if (capturedFrame != null && !capturedFrame.isRecycled) {
                        val saved = c.save()
                        c.clipRect(captureLeft, ey, er, eb)
                        SceneRenderer.drawCapture(c, capturedFrame, RectF(captureLeft, ey, er, eb), previewPaint)
                        c.restoreToCount(saved)
                    } else {
                        val title = if (el.title == "Captura de Jogos") "Captura de Jogos" else "Captura de Tela"
                        val detail = if (ScreenCaptureService.isLiveStreaming) {
                            "Aguardando quadro da tela..."
                        } else {
                            "Prévia real durante a transmissão"
                        }
                        p.style = Paint.Style.FILL
                        p.typeface = TemplateStyle.font(context, bold = true)
                        p.color = TemplateStyle.cyan
                        p.textSize = (min(13f * density, eh * 0.20f).coerceAtLeast(6f * density) - 12f).coerceAtLeast(6f)
                        c.drawText(title, ex + 8f * density, ey + eh * 0.46f, p)
                        p.typeface = TemplateStyle.font(context)
                        p.color = TemplateStyle.white
                        p.textSize = (min(10f * density, eh * 0.15f).coerceAtLeast(5f * density) - 12f).coerceAtLeast(6f)
                        c.drawText(detail, ex + 8f * density, ey + eh * 0.62f, p)
                    }
                } else if (el.type == "browser" && BrowserSourceManager.draw(el.id, c, RectF(ex, ey, er, eb))) {
                    // Exibe os quadros reais da página, sem alterar os containers do template.
                } else {
                    p.style = Paint.Style.FILL
                    p.color = Color.argb(210, 12, 18, 38)
                    c.drawRect(ex, ey, er, eb, p)

                    val headerH = min(18f * density, eh * 0.25f)
                    p.color = Color.rgb(20, 28, 56)
                    c.drawRect(ex, ey, er, ey + headerH, p)

                    p.color = if (el.type == "browser") TemplateStyle.cyan else TemplateStyle.magenta
                    p.textSize = (min(8.5f * density, headerH * 0.65f) - 12f).coerceAtLeast(6f)
                    p.typeface = TemplateStyle.font(context, bold = true)
                    val tag = if (el.type == "browser") "BROWSER SOURCE" else "WIDGET"
                    c.drawText("$tag | ${el.title}", ex + 8f * density, ey + headerH * 0.72f, p)

                    p.color = Color.WHITE
                    p.textSize = (min(10.5f * density, eh * 0.25f) - 12f).coerceAtLeast(6f)
                    c.drawText(el.title, ex + 10f * density, ey + eh * 0.62f, p)
                }

                // 4. SELECTION FRAME & 8 ADJUSTMENT HANDLES (Matching Image 4)
                if (el.isSelected && interactive) {
                    // Cyan bounding border
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2f * density
                    p.color = TemplateStyle.cyan
                    c.drawRect(ex, ey, er, eb, p)

                    // 8 Square Adjustment Handles
                    val hs = 7.5f * density
                    val handles = listOf(
                        PointF(ex, ey),                     // 0: top-left
                        PointF(ex + ew / 2f, ey),           // 1: top-mid
                        PointF(er, ey),                     // 2: top-right
                        PointF(er, ey + eh / 2f),           // 3: mid-right
                        PointF(er, eb),                     // 4: bot-right
                        PointF(ex + ew / 2f, eb),           // 5: bot-mid
                        PointF(ex, eb),                     // 6: bot-left
                        PointF(ex, ey + eh / 2f)            // 7: mid-left
                    )
                    for (pt in handles) {
                        p.style = Paint.Style.FILL
                        p.color = Color.WHITE
                        c.drawRect(pt.x - hs / 2f, pt.y - hs / 2f, pt.x + hs / 2f, pt.y + hs / 2f, p)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 1.5f * density
                        p.color = TemplateStyle.cyan
                        c.drawRect(pt.x - hs / 2f, pt.y - hs / 2f, pt.x + hs / 2f, pt.y + hs / 2f, p)
                    }

                    // Dimension / Status pill badge
                    val badgeW = 210f * density
                    val badgeH = 16f * density
                    val badgeY = max(2f * density, ey - badgeH - 3f * density)
                    p.style = Paint.Style.FILL
                    p.color = Color.rgb(10, 16, 32)
                    c.drawRect(ex, badgeY, ex + badgeW, badgeY + badgeH, p)

                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 1f * density
                    p.color = TemplateStyle.cyan
                    c.drawRect(ex, badgeY, ex + badgeW, badgeY + badgeH, p)

                    p.style = Paint.Style.FILL
                    p.color = TemplateStyle.cyan
                    p.textSize = (7f * density - 12f).coerceAtLeast(6f)
                    p.typeface = TemplateStyle.font(context, bold = true)
                    val dimStr = "${(el.width * 100).toInt()}% × ${(el.height * 100).toInt()}%"
                    c.drawText("${el.title} [$dimStr] • Ajustável", ex + 6f * density, badgeY + badgeH * 0.7f, p)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!interactive) return super.onTouchEvent(event)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return false

            val sel = elements.find { it.isSelected }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    touchDownX = event.x
                    touchDownY = event.y
                    touchElementId = null
                    activeHandle = -1
                    selectionChangedDuringTouch = false
                    geometryChangedDuringTouch = false

                    if (sel != null) {
                        val ex = sel.x * w
                        val ey = sel.y * h
                        val ew = sel.width * w
                        val eh = sel.height * h
                        val er = ex + ew
                        val eb = ey + eh
                        val hitRadius = 24f * density

                        // Check 8 handles
                        val pts = listOf(
                            PointF(ex, ey),                     // 0: top-left
                            PointF(ex + ew / 2f, ey),           // 1: top-mid
                            PointF(er, ey),                     // 2: top-right
                            PointF(er, ey + eh / 2f),           // 3: mid-right
                            PointF(er, eb),                     // 4: bot-right
                            PointF(ex + ew / 2f, eb),           // 5: bot-mid
                            PointF(ex, eb),                     // 6: bot-left
                            PointF(ex, ey + eh / 2f)            // 7: mid-left
                        )
                        for (idx in pts.indices) {
                            if (hypot(event.x - pts[idx].x, event.y - pts[idx].y) <= hitRadius) {
                                activeHandle = idx
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }
                        }

                        // Check inside selected element for moving
                        if (event.x in ex..er && event.y in ey..eb) {
                            touchElementId = sel.id
                            activeHandle = 8 // move
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }

                    // Check if tapped on another element
                    val clicked = elements.findLast { el ->
                        val ex = el.x * w
                        val ey = el.y * h
                        val er = ex + el.width * w
                        val eb = ey + el.height * h
                        event.x in ex..er && event.y in ey..eb
                    }
                    if (clicked != null) {
                        selectionChangedDuringTouch = sel?.id != clicked.id
                        elements.forEach { it.isSelected = (it.id == clicked.id) }
                        touchElementId = clicked.id
                        activeHandle = 8
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - lastTouchX) / w
                    val dy = (event.y - lastTouchY) / h
                    val current = elements.find { it.isSelected }
                    if (current != null && activeHandle != -1) {
                        when (activeHandle) {
                            8 -> { // Move
                                current.x = (current.x + dx).coerceIn(0f, 1f - current.width)
                                current.y = (current.y + dy).coerceIn(0f, 1f - current.height)
                            }
                            4 -> { // Bot-Right
                                current.width = (current.width + dx).coerceIn(0.15f, 1f - current.x)
                                current.height = (current.height + dy).coerceIn(0.15f, 1f - current.y)
                            }
                            0 -> { // Top-Left
                                val nw = (current.width - dx).coerceIn(0.15f, current.x + current.width)
                                val nh = (current.height - dy).coerceIn(0.15f, current.y + current.height)
                                current.x += current.width - nw
                                current.y += current.height - nh
                                current.width = nw
                                current.height = nh
                            }
                            2 -> { // Top-Right
                                val nw = (current.width + dx).coerceIn(0.15f, 1f - current.x)
                                val nh = (current.height - dy).coerceIn(0.15f, current.y + current.height)
                                current.y += current.height - nh
                                current.width = nw
                                current.height = nh
                            }
                            6 -> { // Bot-Left
                                val nw = (current.width - dx).coerceIn(0.15f, current.x + current.width)
                                val nh = (current.height + dy).coerceIn(0.15f, 1f - current.y)
                                current.x += current.width - nw
                                current.width = nw
                                current.height = nh
                            }
                            1 -> { // Top-Mid
                                val nh = (current.height - dy).coerceIn(0.15f, current.y + current.height)
                                current.y += current.height - nh
                                current.height = nh
                            }
                            3 -> { // Mid-Right
                                current.width = (current.width + dx).coerceIn(0.15f, 1f - current.x)
                            }
                            5 -> { // Bot-Mid
                                current.height = (current.height + dy).coerceIn(0.15f, 1f - current.y)
                            }
                            7 -> { // Mid-Left
                                val nw = (current.width - dx).coerceIn(0.15f, current.x + current.width)
                                current.x += current.width - nw
                                current.width = nw
                            }
                        }
                        if (dx != 0f || dy != 0f) geometryChangedDuringTouch = true
                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeHandle = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    if (geometryChangedDuringTouch) onGeometryChanged?.invoke()
                    if (selectionChangedDuringTouch) onSelectionChanged?.invoke()
                    val touched = touchElementId
                    val moved = geometryChangedDuringTouch ||
                        hypot(event.x - touchDownX, event.y - touchDownY) > 12f * density
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved &&
                        touched != null && touched == lastTapElementId &&
                        event.eventTime - lastTapTime in 40L..android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
                    ) {
                        val element = elements.find { it.id == touched && it.type == "browser" }
                        if (element != null) onBrowserDoubleTap?.invoke(element)
                        lastTapElementId = null
                        lastTapTime = 0L
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                        lastTapElementId = touched
                        lastTapTime = event.eventTime
                    } else {
                        lastTapElementId = null
                        lastTapTime = 0L
                    }
                    touchElementId = null
                    geometryChangedDuringTouch = false
                    selectionChangedDuringTouch = false
                    return true
                }
                else -> return super.onTouchEvent(event)
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    // =========================================================================
    // PERMISSIONS PROMPT MODAL (Microfone, Galeria, Sobreposição de Tela)
    // =========================================================================
    private fun attachPermissionsModal() {
        val backdrop = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(215, 4, 7, 18))
            isClickable = true
            isFocusable = true
        }

        val modalWidth = min(dp(380), (availableWidth - dp(24)).coerceAtLeast(dp(240)))
        val modalCard = card(radius = 18f, prominent = true, borderTint = TemplateStyle.cyan).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // Modal Header
        val head = row()
        head.addView(icon("check", TemplateStyle.cyan), LinearLayout.LayoutParams(dp(24), dp(24)))
        val headTextCol = column().apply { marginStart(dp(8)) }
        headTextCol.addView(label("Permissões Necessárias", 12.5f, true, TemplateStyle.white))
        headTextCol.addView(label("Autorize as permissões para iniciar a transmissão", 8f, false, TemplateStyle.muted))
        head.addView(headTextCol, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconAction("close", "Fechar", radius = 8f) {
            showPermissionsModal = false
            render()
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        add(modalCard, head)

        fun permissionItem(
            title: String,
            subtitle: String,
            symbol: String,
            granted: Boolean,
            onToggle: (Boolean) -> Unit
        ): View = row().apply {
            background = TemplateStyle.Panel(
                density,
                selected = granted,
                radius = 12f,
                glow = granted,
                borderTint = if (granted) TemplateStyle.green else TemplateStyle.cyan
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener {
                onToggle(!granted)
                render()
            }
            addView(
                icon(symbol, if (granted) TemplateStyle.green else TemplateStyle.cyan),
                LinearLayout.LayoutParams(dp(22), dp(22))
            )
            val info = column().apply { marginStart(dp(10)) }
            info.addView(label(title, 10f, true, TemplateStyle.white))
            info.addView(label(subtitle, 7.5f, false, TemplateStyle.muted))
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            addView(neonToggle(granted) {
                onToggle(it)
                render()
            })
        }

        // 1. Permissão de Microfone
        add(
            modalCard,
            permissionItem(
                title = "Microfone",
                subtitle = "Capturar áudio do microfone na transmissão",
                symbol = "mic",
                granted = micPermGranted
            ) { isChecked ->
                micPermGranted = isChecked
                if (isChecked) requestRuntimeMicAndGalleryPermissions()
            },
            top = 10
        )

        // 2. Permissão de Galeria
        add(
            modalCard,
            permissionItem(
                title = "Galeria",
                subtitle = "Acessar imagens e mídias da galeria para o cenário",
                symbol = "image",
                granted = galleryPermGranted
            ) { isChecked ->
                galleryPermGranted = isChecked
                if (isChecked) requestRuntimeMicAndGalleryPermissions()
            },
            top = 8
        )

        // 3. Permissão de Sobreposição de Tela (Sistema Android do Smartphone)
        add(
            modalCard,
            permissionItem(
                title = "Sobreposição de Tela",
                subtitle = if (canDrawSystemOverlay()) "Autorizada no smartphone" else "Toque para autorizar sobreposição no smartphone",
                symbol = "screen",
                granted = canDrawSystemOverlay()
            ) { _ ->
                if (!canDrawSystemOverlay()) {
                    requestSystemOverlayPermission()
                } else {
                    overlayPermGranted = true
                }
            },
            top = 8
        )

        // Botões de ação do modal
        val btnRow = row()
        weighted(
            btnRow,
            action("Cancelar", "close", selected = false, radius = 12f) {
                pendingConnectAfterPermissions = false
                showPermissionsModal = false
                render()
            },
            0.9f,
            44,
            3
        )

        val allGranted = micPermGranted && galleryPermGranted && canDrawSystemOverlay()
        weighted(
            btnRow,
            action(
                if (allGranted) "Conectar Agora" else "Permitir Tudo e Conectar",
                "check",
                selected = true,
                radius = 12f,
                tint = TemplateStyle.green
            ) {
                micPermGranted = true
                galleryPermGranted = true
                requestRuntimeMicAndGalleryPermissions()
                if (!canDrawSystemOverlay()) {
                    pendingConnectAfterPermissions = true
                    toast("Ative a permissão 'Sobrepor a outros apps' no smartphone para continuar.")
                    requestSystemOverlayPermission()
                } else {
                    overlayPermGranted = true
                    pendingConnectAfterPermissions = false
                    showPermissionsModal = false
                    verifyAndStartLiveFlow()
                }
            },
            1.5f,
            44,
            3
        )
        add(modalCard, btnRow, 44, top = 12)

        backdrop.addView(modalCard, FrameLayout.LayoutParams(modalWidth, -2, Gravity.CENTER))
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
    }

    // =========================================================================
    // 3-SECOND COUNTDOWN OVERLAY BEFORE MINIMIZING TO SMARTPHONE SCREEN OVERLAY
    // =========================================================================
    private fun attachCountdownOverlay() {
        val backdrop = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(225, 3, 6, 16))
            isClickable = true
            isFocusable = true
        }

        val box = card(radius = 22f, prominent = true, borderTint = TemplateStyle.cyan).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        box.addView(
            createApkIconBadge(46),
            LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            }
        )

        box.addView(
            label("Iniciando Transmissão...", 12f, true, TemplateStyle.cyan).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        val circleBadge = FrameLayout(activity).apply {
            background = TemplateStyle.Panel(
                density,
                selected = true,
                radius = 55f,
                glow = true,
                borderTint = TemplateStyle.green
            )
        }
        val numText = label(countdownSeconds.toString(), 42f, true, TemplateStyle.white).apply {
            gravity = Gravity.CENTER
        }
        countdownLabel = numText
        circleBadge.addView(numText, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        box.addView(
            circleBadge,
            LinearLayout.LayoutParams(dp(110), dp(110)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        )

        box.addView(
            label("Minimizando APK para a Sobreposição do Smartphone...", 8.5f, false, TemplateStyle.muted).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        backdrop.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
    }

    // =========================================================================
    // SMARTPHONE SYSTEM OVERLAY (WindowManager TYPE_APPLICATION_OVERLAY)
    // Exibe fora do APK (na tela do smartphone):
    // - Ícone do APK + Temporizador (arrastável)
    // - Ao acionar: abre painel com 2 menus: 1) Controles 2) Áudio e Volume
    // =========================================================================
    private fun createApkIconBadge(sizeDp: Int = 34): FrameLayout = FrameLayout(activity).apply {
        background = TemplateStyle.Panel(
            density,
            selected = true,
            radius = (sizeDp * 0.28f),
            glow = true,
            borderTint = TemplateStyle.cyan
        )
        val iconSize = (dp((sizeDp * 0.62f).toInt()) - 3).coerceAtLeast(1)
        addView(
            icon("play", TemplateStyle.white),
            FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        )
    }

    private fun refreshSystemOverlayWindow() {
        removeSystemOverlayWindow()
        showSystemOverlayWindow()
    }

    private fun showSystemOverlayWindow() {
        if (!canDrawSystemOverlay() || (!streamConnected && !ScreenCaptureService.isLiveStreaming)) return
        if (systemOverlayView != null) return
        try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return
            val dm = activity.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val windowType = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            }

            if (sysOverlayX < 0 || sysOverlayY < 0) {
                sysOverlayX = (screenW - dp(165)).coerceAtLeast(dp(12))
                sysOverlayY = dp(84)
            }

            if (!overlayExpanded) {
                // Estado recolhido na tela do smartphone: Ícone do APK + Temporizador
                overlayPanelTimerLabel = null
                val sysPill = row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = TemplateStyle.Panel(
                        density,
                        selected = false,
                        radius = 24f,
                        glow = true,
                        borderTint = TemplateStyle.green
                    )
                    setPadding(dp(8), dp(6), dp(12), dp(6))
                    elevation = dp(20).toFloat()
                    addView(
                        createApkIconBadge(32),
                        LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) }
                    )
                    val dot = View(activity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(TemplateStyle.green)
                        }
                    }
                    addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
                    val tLabel = label(formatElapsedTime(), 10.5f, true, TemplateStyle.green).apply {
                        letterSpacing = 0.08f
                    }
                    overlayTimerLabel = tLabel
                    addView(tLabel)
                }

                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = sysOverlayX
                    y = sysOverlayY
                }

                var downRawX = 0f
                var downRawY = 0f
                var startX = 0
                var startY = 0
                var moved = false

                sysPill.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = ev.rawX
                            downRawY = ev.rawY
                            startX = params.x
                            startY = params.y
                            moved = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (ev.rawX - downRawX).toInt()
                            val dy = (ev.rawY - downRawY).toInt()
                            if (hypot(dx.toFloat(), dy.toFloat()) > dp(6)) {
                                moved = true
                                sysOverlayX = (startX + dx).coerceIn(0, (screenW - dp(140)).coerceAtLeast(0))
                                sysOverlayY = (startY + dy).coerceIn(0, (screenH - dp(60)).coerceAtLeast(0))
                                params.x = sysOverlayX
                                params.y = sysOverlayY
                                try {
                                    wm.updateViewLayout(v, params)
                                } catch (_: Throwable) {}
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                v.performClick()
                                overlayExpanded = true
                                loadServerScenarios()
                                refreshSystemOverlayWindow()
                            }
                            true
                        }
                        else -> false
                    }
                }

                wm.addView(sysPill, params)
                systemOverlayView = sysPill
            } else {
                // Estado expandido na tela do smartphone: Painel com 2 Menus (1: Controles | 2: Áudio e Volume)
                overlayTimerLabel = null
                val panelWidth = min(dp(400), (screenW - dp(20)).coerceAtLeast(dp(260)))

                val overlayRoot = FrameLayout(activity).apply {
                    setBackgroundColor(Color.argb(145, 3, 6, 16))
                    isClickable = true
                    setOnClickListener {
                        overlayExpanded = false
                        refreshSystemOverlayWindow()
                    }
                }

                val overlayPanel = card(radius = 18f, prominent = true, borderTint = TemplateStyle.cyan).apply {
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    isClickable = true
                }

                // Cabeçalho com Ícone do APK + Temporizador + Botão Voltar ao APK + Fechar Painel
                val topBar = row()
                topBar.addView(
                    createApkIconBadge(30).apply {
                        isClickable = true
                        setOnClickListener { returnToApkFromOverlay() }
                    },
                    LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) }
                )
                val titleCol = column()
                titleCol.addView(label("LiveNEXUS • Sobreposição", 10.5f, true, TemplateStyle.white))
                val timerRow = row()
                val dot = View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(TemplateStyle.green)
                    }
                }
                timerRow.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(5) })
                val panelTimer = label(formatElapsedTime(), 9f, true, TemplateStyle.green)
                overlayPanelTimerLabel = panelTimer
                timerRow.addView(panelTimer)
                titleCol.addView(timerRow)
                topBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

                topBar.addView(
                    action("Abrir APK", "expand", selected = false, radius = 8f) {
                        returnToApkFromOverlay()
                    },
                    LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
                )

                topBar.addView(
                    iconAction("close", "Minimizar painel", radius = 8f) {
                        overlayExpanded = false
                        refreshSystemOverlayWindow()
                    },
                    LinearLayout.LayoutParams(dp(34), dp(34))
                )
                add(overlayPanel, topBar)

                // Abas dos 2 Menus: 1) Controles | 2) Áudio e Volume
                val menuTabs = row()
                weighted(
                    menuTabs,
                    action(
                        "Controles",
                        "game",
                        selected = overlayMenuTab == "controls",
                        radius = 10f
                    ) {
                        overlayMenuTab = "controls"
                        refreshSystemOverlayWindow()
                    },
                    1f,
                    40,
                    3
                )
                weighted(
                    menuTabs,
                    action(
                        "Áudio e Volume",
                        "volume",
                        selected = overlayMenuTab == "audio",
                        radius = 10f
                    ) {
                        overlayMenuTab = "audio"
                        refreshSystemOverlayWindow()
                    },
                    1f,
                    40,
                    3
                )
                add(overlayPanel, menuTabs, 40, top = 10)

                val contentContainer = column()
                if (overlayMenuTab == "controls") {
                    buildOverlayControlsMenu(contentContainer)
                } else {
                    buildOverlayAudioVolumeMenu(contentContainer)
                }

                val contentScroll = ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(contentContainer, FrameLayout.LayoutParams(-1, -2))
                }
                val maxContentHeight = (screenH * 0.60f).toInt().coerceAtLeast(dp(200))
                overlayPanel.addView(
                    contentScroll,
                    LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(8)
                    }
                )
                contentScroll.maxHeight(maxContentHeight)

                overlayRoot.addView(
                    overlayPanel,
                    FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                )

                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                wm.addView(overlayRoot, params)
                systemOverlayView = overlayRoot
            }
        } catch (_: Throwable) {}
    }

    private fun removeSystemOverlayWindow() {
        val view = systemOverlayView ?: return
        systemOverlayView = null
        overlayTimerLabel = null
        overlayPanelTimerLabel = null
        try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            wm?.removeView(view)
        } catch (_: Throwable) {}
    }

    private fun ScrollView.maxHeight(maxPx: Int) {
        addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            if (bottom - top > maxPx) {
                val lp = v.layoutParams
                if (lp != null && lp.height != maxPx) {
                    lp.height = maxPx
                    v.layoutParams = lp
                }
            }
        }
    }

    /**
     * Menu 1 da Sobreposição de Tela (no Smartphone): Controles (Hotkeys + Player sem escrita)
     */
    private fun buildOverlayControlsMenu(parent: LinearLayout) {
        val hotkeys = card(radius = 14f)
        val hotHead = row()
        hotHead.addView(icon("keyboard", TemplateStyle.white), LinearLayout.LayoutParams(dp(20), dp(20)))
        val hotTitles = column().apply { marginStart(dp(6)) }
        hotTitles.addView(label("Hotkeys", 11f, true))
        hotTitles.addView(label("Atalhos de cenas, subcenas e ações rápidas", 7.5f, color = TemplateStyle.muted))
        hotHead.addView(hotTitles, LinearLayout.LayoutParams(0, -2, 1f))
        add(hotkeys, hotHead)

        val buttons = mutableListOf<View>()
        buttons.add(sceneButton("refresh", "Reload", selected = true) {
            reloadControllerScene()
        })
        buttons.add(sceneButton("plus", "Mortes +", selected = false) {
            DeathCounterCommands.send(activity, increase = true) { delivered ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    toast(if (delivered) "Comando + enviado ao contador de mortes." else "Falha ao enviar comando + ao LiveNEXUS.")
                }
            }
        })
        buttons.add(sceneButton("minus", "Mortes −", selected = false) {
            DeathCounterCommands.send(activity, increase = false) { delivered ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    toast(if (delivered) "Comando − enviado ao contador de mortes." else "Falha ao enviar comando − ao LiveNEXUS.")
                }
            }
        })
        buttons.add(sceneButton("plus", "Adicionar\ncenário", selected = false) {
            addSceneButtonSlot()
        })
        for (i in sceneButtonSlots.indices) {
            buttons.add(sceneButton(
                "video", sceneShortcutTitle(i), selected = false,
                longClick = { showSceneButtonOptions(i) }
            ) {
                selectServerScenario(i)
            })
        }

        val gridCols = 3
        buttons.chunked(gridCols).forEach { group ->
            val gridLine = row().apply { gravity = Gravity.START }
            group.forEach { b ->
                gridLine.addView(b, LinearLayout.LayoutParams(dp(78), dp(74)).apply {
                    marginEnd = dp(8)
                })
            }
            add(hotkeys, gridLine, 74, top = 8)
        }
        add(parent, hotkeys, top = 2)

        // Container do Player (sem escrita nenhuma)
        val player = card(radius = 14f)
        val mediaBar = row()

        val cover = FrameLayout(activity).apply {
            background = TemplateStyle.Panel(density, true, radius = 10f, glow = false)
            addView(icon("music", TemplateStyle.white), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        mediaBar.addView(cover, LinearLayout.LayoutParams(dp(40), dp(40)))
        mediaBar.addView(Space(activity), LinearLayout.LayoutParams(0, 1, 1f))

        val volLabel = label("$playerVolume%", 8f, true)
        val volSeek = customSeekBar(playerVolume, 100) {
            val difference = it - playerVolume
            if (difference != 0) {
                playerVolume = it
                volLabel.text = "$it%"
                val command = if (difference > 0) "music-volume-up" else "music-volume-down"
                repeat(kotlin.math.abs(difference)) { sendPlayerCommand(command) }
            }
        }
        mediaBar.addView(icon("volume", TemplateStyle.white), LinearLayout.LayoutParams(dp(16), dp(16)))
        mediaBar.addView(volSeek, LinearLayout.LayoutParams(dp(84), dp(26)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        mediaBar.addView(volLabel, LinearLayout.LayoutParams(dp(34), -2))

        val controlsGroup = row()
        controlsGroup.addView(iconAction("prev_track", "Anterior", radius = 8f) {
            sendPlayerCommand("music-previous")
        }, LinearLayout.LayoutParams(dp(34), dp(34)))

        val playPause = action("", if (playerPlaying) "pause" else "play", selected = true, radius = 10f) {
            sendPlayerCommand("music-toggle") {
                playerPlaying = !playerPlaying
                refreshSystemOverlayWindow()
            }
        }
        controlsGroup.addView(playPause, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })

        controlsGroup.addView(iconAction("next_track", "Próxima", radius = 8f) {
            sendPlayerCommand("music-next")
        }, LinearLayout.LayoutParams(dp(34), dp(34)))

        mediaBar.addView(controlsGroup)
        add(player, mediaBar)
        add(parent, player, top = 8)
    }

    /**
     * Menu 2 da Sobreposição de Tela (no Smartphone): Container Áudio e Volume do menu Configurações
     */
    private fun buildOverlayAudioVolumeMenu(parent: LinearLayout) {
        // Container Áudio
        val audioCard = card(radius = 14f)
        val audioHead = row()
        audioHead.addView(icon("mic"), LinearLayout.LayoutParams(dp(18), dp(18)))
        audioHead.addView(label("Áudio", 10f, true).apply { marginStart(dp(6)) })
        add(audioCard, audioHead)

        val micRow = row()
        weighted(micRow, label("Microfone (Áudio Externo)", 9f), 1f)
        micRow.addView(neonToggle(micEnabled) {
            micEnabled = it
            StreamingConfig.saveMicEnabled(activity, it)
        })
        add(audioCard, micRow, 34, top = 4)

        val internalRow = row()
        weighted(internalRow, label("Áudio Interno (Som do Dispositivo)", 9f), 1f)
        internalRow.addView(neonToggle(internalAudioEnabled) {
            internalAudioEnabled = it
            StreamingConfig.saveInternalAudioEnabled(activity, it)
        })
        add(audioCard, internalRow, 34, top = 2)
        add(parent, audioCard, top = 2)

        // Container Volume
        val volumeCard = card(radius = 14f)
        val volumeHead = row()
        volumeHead.addView(icon("volume"), LinearLayout.LayoutParams(dp(18), dp(18)))
        volumeHead.addView(label("Volume", 10f, true).apply { marginStart(dp(6)) })
        add(volumeCard, volumeHead)

        val micVolVal = label("$micVolume%", 8.5f, true)
        val micVolRow = row()
        micVolRow.addView(label("Microfone:", 8.5f, color = TemplateStyle.muted), LinearLayout.LayoutParams(dp(86), -2))
        weighted(micVolRow, customSeekBar(micVolume, 100) {
            micVolume = it
            micVolVal.text = "$it%"
            StreamingConfig.saveMicVolume(activity, it)
        }, 1f, 30)
        micVolRow.addView(micVolVal, LinearLayout.LayoutParams(dp(40), -2))
        add(volumeCard, micVolRow, top = 3)

        val intVolVal = label("$internalVolume%", 8.5f, true)
        val intVolRow = row()
        intVolRow.addView(label("Áudio Interno:", 8.5f, color = TemplateStyle.muted), LinearLayout.LayoutParams(dp(86), -2))
        weighted(intVolRow, customSeekBar(internalVolume, 100) {
            internalVolume = it
            intVolVal.text = "$it%"
            StreamingConfig.saveInternalVolume(activity, it)
        }, 1f, 30)
        intVolRow.addView(intVolVal, LinearLayout.LayoutParams(dp(40), -2))
        add(volumeCard, intVolRow, top = 2)
        add(parent, volumeCard, top = 8)
    }

    // =========================================================================
    // UI HELPERS (Toggles, Sliders, Layout Spacers)
    // =========================================================================
    private fun neonToggle(checked: Boolean, onChange: ((Boolean) -> Unit)? = null): Switch =
        Switch(activity).apply {
            isChecked = checked
            showText = false
            thumbTintList = ColorStateList.valueOf(TemplateStyle.white)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(TemplateStyle.magenta, 0xff252945.toInt())
            )
            setOnCheckedChangeListener { _, v -> onChange?.invoke(v) }
        }

    private fun customSeekBar(value: Int, maximum: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(activity).apply {
            max = maximum
            progress = value
            progressTintList = ColorStateList.valueOf(TemplateStyle.magenta)
            thumbTintList = ColorStateList.valueOf(TemplateStyle.white)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

    private fun View.marginStart(pixels: Int) {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.marginStart = pixels
            layoutParams = lp
        }
    }
}
