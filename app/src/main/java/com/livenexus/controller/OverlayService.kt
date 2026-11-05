package com.livenexus.controller

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*

class OverlayService : Service() {
    private lateinit var manager: WindowManager
    private var bubble: View? = null
    private var bubbleLayout: WindowManager.LayoutParams? = null
    private var timerBadge: TextView? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayTimerRunnable = object : Runnable {
        override fun run() {
            updateTimerBadge()
            overlayHandler.postDelayed(this, 1000L)
        }
    }
    private var previousWidth = 0
    private var previousHeight = 0
    private var trash: View? = null
    private var panel: View? = null
    private val channel = "controller_overlay"
    private var controls: ControllerPanel? = null

    companion object {
        @Volatile private var active: OverlayService? = null
        fun refreshVisibility() {
            val service = active ?: return
            // O pipeline RTMP chama este método de uma thread de trabalho.
            // WindowManager/Views do overlay devem ser atualizados na UI.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (active === service) {
                    service.syncVisibility()
                    service.updateTimerBadge()
                }
            } else {
                service.overlayHandler.post {
                    if (active === service) {
                        service.syncVisibility()
                        service.updateTimerBadge()
                    }
                }
            }
        }
    }

    private val prefs by lazy { getSharedPreferences("buttons", MODE_PRIVATE) }
    private fun tileBackground(accent: Int = Color.rgb(55, 88, 120)) = GradientDrawable().apply {
        setColor(Color.rgb(19, 29, 45))
        cornerRadius = dp(15).toFloat()
        setStroke(dp(1), accent)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun params(width: Int, height: Int, gravity: Int) =
        WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    override fun onCreate() {
        super.onCreate()
        active = this
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(channel, "Controlador ativo", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channel)
            .setContentTitle("LiveNexus Controller")
            .setContentText("Botão flutuante ativo")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        syncVisibility()
        return START_NOT_STICKY
    }

    private fun screenSize(): android.graphics.Point =
        android.graphics.Point().also { manager.defaultDisplay.getRealSize(it) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        repositionBubble()
        if (panel != null) {
            hidePanel()
            showPanel()
        }
    }

    private fun repositionBubble() {
        val view = bubble ?: return
        val layout = bubbleLayout ?: return
        val screen = screenSize()
        val newWidth = (screen.x - layout.width).coerceAtLeast(0)
        val newHeight = (screen.y - layout.height).coerceAtLeast(0)
        if (newWidth == previousWidth && newHeight == previousHeight) return
        val ratioX = if (previousWidth > 0) layout.x.toFloat() / previousWidth else 0f
        val ratioY = if (previousHeight > 0) layout.y.toFloat() / previousHeight else 0f
        layout.x = (ratioX * newWidth).toInt().coerceIn(0, newWidth)
        layout.y = (ratioY * newHeight).toInt().coerceIn(0, newHeight)
        previousWidth = newWidth
        previousHeight = newHeight
        try { manager.updateViewLayout(view, layout) } catch (_: RuntimeException) {}
    }

    private fun showTrash(active: Boolean) {
        if (trash != null) return
        val target = TextView(this).apply {
            text = "✕"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = tileBackground(Color.rgb(239, 70, 85))
        }
        try {
            manager.addView(target, params(dp(76), dp(76),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                y = dp(32)
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            })
            trash = target
            target.alpha = if (active) 1f else 0.85f
        } catch (_: RuntimeException) { trash = null }
    }

    private fun hideTrash() {
        trash?.let { try { manager.removeView(it) } catch (_: RuntimeException) {} }
        trash = null
    }

    private fun isOverTrash(rawX: Float, rawY: Float): Boolean {
        val target = trash ?: return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val tolerance = dp(28)
        return rawX >= location[0] - tolerance &&
            rawX <= location[0] + target.width + tolerance &&
            rawY >= location[1] - tolerance &&
            rawY <= location[1] + target.height + tolerance
    }

    private fun formatElapsedTime(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun updateTimerBadge() {
        val badge = timerBadge ?: return
        val transmitting = ControllerRuntime.isTransmitting || ScreenCaptureService.isCapturing
        if (transmitting) {
            val start = ControllerRuntime.transmissionStartTime
            val elapsed = if (start > 0) System.currentTimeMillis() - start else 0L
            badge.text = "● ${formatElapsedTime(elapsed)}"
            if (badge.visibility != View.VISIBLE) {
                badge.visibility = View.VISIBLE
                bubble?.let { b ->
                    bubbleLayout?.let { bl ->
                        try { manager.updateViewLayout(b, bl) } catch (_: Exception) {}
                    }
                }
            }
        } else {
            if (badge.visibility != View.GONE) {
                badge.visibility = View.GONE
                bubble?.let { b ->
                    bubbleLayout?.let { bl ->
                        try { manager.updateViewLayout(b, bl) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun showBubble() {
        val logoSize = dp(56)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
        }

        val logoView = TextView(this).apply {
            text = ""
            contentDescription = "LiveNexus Controller"
            background = NeonTheme.floatingLogo(this@OverlayService)
        }
        container.addView(logoView, LinearLayout.LayoutParams(logoSize, logoSize))

        val badge = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 20, 6, 36))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.rgb(255, 64, 92))
            }
            visibility = if (ControllerRuntime.isTransmitting || ScreenCaptureService.isCapturing) View.VISIBLE else View.GONE
        }
        timerBadge = badge
        container.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
        })

        updateTimerBadge()

        val screen = screenSize()
        val maxX = (screen.x - dp(64)).coerceAtLeast(0)
        val maxY = (screen.y - dp(80)).coerceAtLeast(0)
        previousWidth = maxX
        previousHeight = maxY
        val layout = params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.LEFT
        ).apply {
            x = (prefs.getFloat("bubble_ratio_x", 1f).coerceIn(0f, 1f) * maxX).toInt()
            y = (prefs.getFloat("bubble_ratio_y", 0.5f).coerceIn(0f, 1f) * maxY).toInt()
        }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    repositionBubble()
                    startX = layout.x; startY = layout.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                        showTrash(false)
                    }
                    if (dragging) {
                        val currentScreen = screenSize()
                        val currentMaxX = (currentScreen.x - dp(64)).coerceAtLeast(0)
                        val currentMaxY = (currentScreen.y - dp(80)).coerceAtLeast(0)
                        layout.x = (startX + dx.toInt()).coerceIn(0, currentMaxX)
                        layout.y = (startY + dy.toInt()).coerceIn(0, currentMaxY)
                        manager.updateViewLayout(view, layout)
                        trash?.alpha = if (isOverTrash(event.rawX, event.rawY)) 1f else 0.75f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val remove = dragging && isOverTrash(event.rawX, event.rawY)
                    hideTrash()
                    if (remove) {
                        stopSelf()
                    } else if (dragging) {
                        val bounds = screenSize()
                        val rangeX = (bounds.x - dp(64)).coerceAtLeast(0)
                        val rangeY = (bounds.y - dp(80)).coerceAtLeast(0)
                        prefs.edit()
                            .putFloat("bubble_ratio_x", if (rangeX > 0) layout.x.toFloat() / rangeX else 0f)
                            .putFloat("bubble_ratio_y", if (rangeY > 0) layout.y.toFloat() / rangeY else 0f)
                            .apply()
                    } else {
                        view.performClick()
                        showPanel()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { hideTrash(); true }
                else -> false
            }
        }
        try {
            manager.addView(container, layout)
            bubble = container
            bubbleLayout = layout
            overlayHandler.removeCallbacks(overlayTimerRunnable)
            overlayHandler.post(overlayTimerRunnable)
        } catch (_: RuntimeException) { stopSelf() }
    }

    private fun syncVisibility() {
        if (ControllerRuntime.foreground || !ControllerRuntime.signedIn) {
            hidePanel()
            hideTrash()
            overlayHandler.removeCallbacks(overlayTimerRunnable)
            timerBadge = null
            bubble?.let { try { manager.removeView(it) } catch (_: RuntimeException) {} }
            bubble = null
            bubbleLayout = null
        } else if (Settings.canDrawOverlays(this)) {
            if (bubble == null) {
                showBubble()
            } else {
                updateTimerBadge()
            }
        }
    }

    private fun showPanel() {
        if (panel != null || ControllerRuntime.foreground || !ControllerRuntime.signedIn) return
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = NeonTheme.backdrop()
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(NeonHeader(this), LinearLayout.LayoutParams(0, dp(if (isLandscape) 52 else 64), 1f))
        header.addView(TextView(this).apply {
            text = "−"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Minimizar painel"
            setOnClickListener { hidePanel() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        val view = ControllerPanel(this, true)
        controls = view
        val screen = screenSize()
        val height = if (isLandscape) {
            minOf(dp(340), (screen.y - dp(36)).coerceAtLeast(dp(140)))
        } else {
            minOf(dp(520), (screen.y - dp(70)).coerceAtLeast(dp(160)))
        }
        root.addView(ScrollView(this).apply { addView(view) }, LinearLayout.LayoutParams(-1, height))
        val width = if (isLandscape) {
            minOf(dp(540), screen.x - dp(24))
        } else {
            minOf(dp(390), screen.x - dp(20))
        }
        try {
            manager.addView(root, params(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            panel = root
            bubble?.visibility = View.GONE
        } catch (e: RuntimeException) {
            view.dispose()
            controls = null
            Toast.makeText(this, "Falha ao abrir painel: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hidePanel() {
        controls?.dispose()
        controls = null
        panel?.let { try { manager.removeView(it) } catch (_: RuntimeException) {} }
        panel = null
        bubble?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        overlayHandler.removeCallbacks(overlayTimerRunnable)
        timerBadge = null
        if (active === this) active = null
        hidePanel()
        hideTrash()
        bubble?.let { try { manager.removeView(it) } catch (_: RuntimeException) {} }
        bubble = null
        bubbleLayout = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
