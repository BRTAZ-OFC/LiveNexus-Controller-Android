package com.livenexus.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min
import kotlin.math.max

/**
 * Renders the captured screen frame and active scenario layers onto the video encoder canvas.
 */
object SceneRenderer {
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#04060E")
        style = Paint.Style.FILL
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 8, 12, 28)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val srcRect = Rect()
    private val dstRect = RectF()

    fun renderScene(
        canvas: Canvas,
        width: Int,
        height: Int,
        layers: List<CaptureLayer>,
        screenBitmap: Bitmap?
    ) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Clear frame background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val visibleLayers = layers.filter { it.visible }

        // 2. If no layers were explicitly placed on the Scenario Editor, render full-screen capture
        if (visibleLayers.isEmpty()) {
            if (screenBitmap != null && !screenBitmap.isRecycled) {
                drawCapture(canvas, screenBitmap, RectF(0f, 0f, w, h), bitmapPaint)
            }
            return
        }

        // 3. Render configured scenario layers in order
        for (layer in visibleLayers) {
            val left = (layer.normX * w).coerceIn(0f, w)
            val top = (layer.normY * h).coerceIn(0f, h)
            val right = ((layer.normX + layer.normW) * w).coerceIn(left + 1f, w)
            val bottom = ((layer.normY + layer.normH) * h).coerceIn(top + 1f, h)
            val targetRect = RectF(left, top, right, bottom)

            when (layer.type) {
                "screen_capture" -> {
                    if (screenBitmap != null && !screenBitmap.isRecycled) {
                        drawCapture(canvas, screenBitmap, targetRect, bitmapPaint)
                    } else {
                        canvas.drawRoundRect(targetRect, 8f, 8f, labelBgPaint)
                    }
                }
                "browser" -> {
                    if (!BrowserSourceManager.draw(layer.id, canvas, targetRect)) {
                        canvas.drawRoundRect(targetRect, 10f, 10f, labelBgPaint)
                        canvas.drawRoundRect(targetRect, 10f, 10f, borderPaint)
                        textPaint.textSize = 24f
                        canvas.drawText(layer.label, targetRect.left + 16f, targetRect.centerY() + 8f, textPaint)
                    }
                }
                "text" -> {
                    canvas.drawRoundRect(targetRect, 10f, 10f, labelBgPaint)
                    canvas.drawRoundRect(targetRect, 10f, 10f, borderPaint)
                    val display = layer.label.removePrefix("Texto:").trim().ifEmpty { layer.label }
                    textPaint.textSize = (targetRect.height() * 0.38f).coerceIn(18f, 56f)
                    val textW = textPaint.measureText(display)
                    val tx = (targetRect.centerX() - textW / 2f).coerceAtLeast(targetRect.left + 12f)
                    val ty = targetRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(display, tx, ty, textPaint)
                }
                else -> {
                    canvas.drawRoundRect(targetRect, 10f, 10f, labelBgPaint)
                    canvas.drawRoundRect(targetRect, 10f, 10f, borderPaint)
                    textPaint.textSize = 24f
                    val tx = targetRect.left + 16f
                    val ty = targetRect.centerY() + 8f
                    canvas.drawText(layer.label, tx, ty, textPaint)
                }
            }
        }
    }

    fun drawCapture(canvas: Canvas, bitmap: Bitmap, target: RectF, paint: Paint) {
        if (bitmap.isRecycled || target.width() <= 0f || target.height() <= 0f) return
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        // Somente captura horizontal em destino horizontal preenche via recorte.
        // Captura vertical permanece inteira (sem zoom ou recorte forçado).
        if (bw > bh && target.width() > target.height()) {
            val scale = max(target.width() / bw, target.height() / bh)
            val visibleW = target.width() / scale
            val visibleH = target.height() / scale
            val left = ((bw - visibleW) / 2f).toInt().coerceAtLeast(0)
            val top = ((bh - visibleH) / 2f).toInt().coerceAtLeast(0)
            srcRect.set(left, top,
                (left + visibleW).toInt().coerceIn(left + 1, bitmap.width),
                (top + visibleH).toInt().coerceIn(top + 1, bitmap.height))
            canvas.drawBitmap(bitmap, srcRect, target, paint)
        } else {
            drawCenterInside(canvas, bitmap, target, paint)
        }
    }

    private fun drawCenterInside(canvas: Canvas, bitmap: Bitmap, target: RectF, paint: Paint) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        val scale = min(target.width() / bw, target.height() / bh)
        val drawW = bw * scale
        val drawH = bh * scale
        val dx = target.left + (target.width() - drawW) / 2f
        val dy = target.top + (target.height() - drawH) / 2f

        srcRect.set(0, 0, bitmap.width, bitmap.height)
        dstRect.set(dx, dy, dx + drawW, dy + drawH)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }
}
