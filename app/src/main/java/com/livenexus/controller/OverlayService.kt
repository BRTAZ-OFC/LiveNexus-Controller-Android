package com.livenexus.controller

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*

class OverlayService : Service() {
    private lateinit var manager: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private val channel = "controller_overlay"

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
        val button = TextView(this).apply {
            text = "LN"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(28, 116, 158))
            setOnClickListener { showPanel() }
        }
        try {
            manager.addView(
                button,
                params(dp(56), dp(56), Gravity.END or Gravity.CENTER_VERTICAL).apply { x = dp(12) }
            )
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
        val grid = GridLayout(this).apply { columnCount = 2 }
        val prefs = getSharedPreferences("buttons", MODE_PRIVATE)
        for (i in 0 until 8) {
            grid.addView(Button(this).apply {
                text = prefs.getString("button_$i", "Botão ${i + 1}")
                isAllCaps = false
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(66)
                columnSpec = GridLayout.spec(i % 2, 1f)
                rowSpec = GridLayout.spec(i / 2)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        root.addView(grid)
        val width = minOf(dp(340), resources.displayMetrics.widthPixels - dp(24))
        try {
            manager.addView(root, params(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            panel = root
            bubble?.visibility = View.GONE
        } catch (_: RuntimeException) {
            panel = null
        }
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