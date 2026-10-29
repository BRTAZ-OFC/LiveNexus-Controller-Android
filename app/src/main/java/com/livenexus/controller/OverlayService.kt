package com.livenexus.controller

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*

class OverlayService : Service() {
    private lateinit var manager: WindowManager
    private var bubble: View? = null
    private var bubbleLayout: WindowManager.LayoutParams? = null
    private var previousWidth = 0
    private var previousHeight = 0
    private var trash: View? = null
    private var panel: View? = null
    private val channel = "controller_overlay"
    private val prefs by lazy { getSharedPreferences("buttons", MODE_PRIVATE) }
    private fun tileBackground(accent: Int = Color.rgb(55, 88, 120)) = GradientDrawable().apply {
        setColor(Color.rgb(19, 29, 45)); cornerRadius = dp(15).toFloat(); setStroke(dp(1), accent)
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
        if (bubble == null) showBubble()
        return START_NOT_STICKY
    }

    private fun screenSize(): android.graphics.Point =
        android.graphics.Point().also { manager.defaultDisplay.getRealSize(it) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        repositionBubble()
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
        val screen = screenSize()
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

    private fun showBubble() {
        val size = dp(56)
        val screen = screenSize()
        val maxX = (screen.x - size).coerceAtLeast(0)
        val maxY = (screen.y - size).coerceAtLeast(0)
        previousWidth = maxX
        previousHeight = maxY
        val layout = params(size, size, Gravity.TOP or Gravity.LEFT).apply {
            x = (prefs.getFloat("bubble_ratio_x", 1f).coerceIn(0f, 1f) * maxX).toInt()
            y = (prefs.getFloat("bubble_ratio_y", 0.5f).coerceIn(0f, 1f) * maxY).toInt()
        }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val button = TextView(this).apply {
            text = "LN"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = tileBackground(Color.rgb(43, 205, 239))
            setOnTouchListener { view, event ->
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
                            val currentMaxX = (currentScreen.x - size).coerceAtLeast(0)
                            val currentMaxY = (currentScreen.y - size).coerceAtLeast(0)
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
                            val rangeX = (bounds.x - size).coerceAtLeast(0)
                            val rangeY = (bounds.y - size).coerceAtLeast(0)
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
        }
        try {
            manager.addView(button, layout)
            bubble = button
            bubbleLayout = layout
        } catch (_: RuntimeException) { stopSelf() }
    }

    private fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(19, 28, 43))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "LiveNexus Controller"
            textSize = 16f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(Button(this).apply {
            text = "−"
            contentDescription = "Minimizar painel"
            setOnClickListener { hidePanel() }
        })
        root.addView(header)
        val grid = GridLayout(this).apply { columnCount = 3 }
        val count = 3 + prefs.getInt("scenario_count", 0).coerceIn(0, 57)
        val defaults = listOf("Música", "Câmera", "Reload RTC")
        fun controlType(index: Int) = prefs.getString("control_type_$index", "Cenário") ?: "Cenário"
        for (i in 0 until count) {
            val label = if (i < 3) defaults[i] else {
                val kind = prefs.getString("control_type_${i - 3}", "Cenário") ?: "Cenário"
                prefs.getString("scenario_${i - 3}", "$kind ${i - 2}") ?: "$kind ${i - 2}"
            }
            val tile = TextView(this).apply {
                val icon = if (i < 3) listOf("♫", "▣", "↻")[i] else when (controlType(i - 3)) {
                    "Contador +1" -> "+"
                    "Contador -1" -> "−"
                    "Zerar contador" -> "⟲"
                    "Chat" -> "💬"
                    else -> "◆"
                }
                text = "$icon\n$label"
                textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = tileBackground()
                setOnClickListener {
                    if (label.equals("Música", true)) showMiniPlayer()
                    else Toast.makeText(this@OverlayService, "Ação ainda não conectada: $label", Toast.LENGTH_SHORT).show()
                }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0; height = dp(88); columnSpec = GridLayout.spec(i % 3, 1f)
                rowSpec = GridLayout.spec(i / 3); setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        grid.addView(TextView(this).apply {
            text = "＋\\nAdicionar".replace("\\n", "\n"); gravity = Gravity.CENTER; textSize = 12f
            setTextColor(Color.rgb(43, 205, 239)); background = tileBackground(Color.rgb(43, 205, 239))
            setOnClickListener {
                showAddControlMenu()
            }
        }, GridLayout.LayoutParams().apply {
            width = 0; height = dp(88); columnSpec = GridLayout.spec(count % 3, 1f)
            rowSpec = GridLayout.spec(count / 3); setMargins(dp(3), dp(3), dp(3), dp(3))
        })
        root.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(-1, dp(350)))
        val width = minOf(dp(340), resources.displayMetrics.widthPixels - dp(24))
        try {
            manager.addView(root, params(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            panel = root
            bubble?.visibility = View.GONE
        } catch (_: RuntimeException) {
            panel = null
        }
    }

    private fun showAddControlMenu() {
        AlertDialog.Builder(this).setTitle("Adicionar controle")
            .setItems(arrayOf("Cenário", "Overlay", "LivePix", "Contador +1", "Contador -1", "Zerar contador", "Chat")) { _, selected ->
                val current = prefs.getInt("scenario_count", 0).coerceIn(0, 57)
                if (current >= 57) Toast.makeText(this, "Limite de 60 controles", Toast.LENGTH_SHORT).show()
                else {
                    prefs.edit().putString("control_type_$current", arrayOf("Cenário", "Overlay", "LivePix", "Contador +1", "Contador -1", "Zerar contador", "Chat")[selected])
                        .putInt("scenario_count", current + 1).apply()
                    hidePanel()
                    showPanel()
                }
            }.setNegativeButton("Cancelar", null)
            .create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                show()
            }
    }

    private fun showMiniPlayer() {
        hidePanel()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = tileBackground(Color.rgb(43, 205, 239))
        }
        root.addView(TextView(this).apply {
            text = "♫  LiveNexus • Player"; textSize = 18f; setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Nenhuma música conectada\\n\\n◀      ▶      ▶▶      🔊\\n\\nAguardando integração com o LiveNexus"
                .replace("\\n", "\n")
            textSize = 14f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = "Voltar ao painel"; setOnClickListener { hidePanel(); showPanel() }
        })
        val width = minOf(dp(340), resources.displayMetrics.widthPixels - dp(24))
        try {
            manager.addView(root, params(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            panel = root; bubble?.visibility = View.GONE
        } catch (_: RuntimeException) { panel = null; bubble?.visibility = View.VISIBLE }
    }

    private fun hidePanel() {
        panel?.let { manager.removeView(it) }
        panel = null
        bubble?.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        hideTrash()
        panel?.let { manager.removeView(it) }
        panel = null
        bubble?.let { manager.removeView(it) }
        bubble = null
        bubbleLayout = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}