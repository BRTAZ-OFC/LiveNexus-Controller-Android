package com.livenexus.controller

import android.app.*
import android.content.Intent
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

    private fun showBubble() {
        val size = dp(56)
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - size).coerceAtLeast(0)
        val savedX = prefs.getInt("bubble_x", maxX - dp(12))
        val savedY = prefs.getInt("bubble_y", maxY / 2)
        val layout = params(size, size, Gravity.TOP or Gravity.START).apply {
            x = savedX.coerceIn(0, maxX)
            y = savedY.coerceIn(0, maxY)
        }
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
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
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = layout.x
                        startY = layout.y
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) dragging = true
                        if (dragging) {
                            layout.x = (startX + dx.toInt()).coerceIn(0, maxX)
                            layout.y = (startY + dy.toInt()).coerceIn(0, maxY)
                            manager.updateViewLayout(this, layout)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            prefs.edit().putInt("bubble_x", layout.x).putInt("bubble_y", layout.y).apply()
                        } else {
                            performClick()
                            showPanel()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        try {
            manager.addView(button, layout)
            bubble = button
        } catch (_: RuntimeException) {
            stopSelf()
        }
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
        val count = prefs.getInt("button_count", 8).coerceIn(1, 60)
        val defaults = listOf("Microfone", "Câmera", "Cena 1", "Cena 2", "Chat", "Música", "Gravar", "Mutar", "Voltar")
        for (i in 0 until count) {
            val label = prefs.getString("button_$i", defaults.getOrElse(i) { "Botão ${i + 1}" }) ?: "Botão ${i + 1}"
            val tile = TextView(this).apply {
                text = "◆\\n$label".replace("\\n", "\n")
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
                if (count < 60) { prefs.edit().putInt("button_count", count + 1).apply(); hidePanel(); showPanel() }
                else Toast.makeText(this@OverlayService, "Limite de 60 botões", Toast.LENGTH_SHORT).show()
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
        panel?.let { manager.removeView(it) }
        panel = null
        bubble?.let { manager.removeView(it) }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}