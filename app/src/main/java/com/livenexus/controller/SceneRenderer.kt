package com.livenexus.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
                drawCenterCrop(canvas, screenBitmap, RectF(0f, 0f, w, h))
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
                        drawCenterCrop(canvas, screenBitmap, targetRect)
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

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f || target.width() <= 0f || target.height() <= 0f) return

        // Preenche a area sem distorcer a proporcao do jogo.
        // Apenas as extremidades excedentes sao cortadas.
        val scale = max(target.width() / bw, target.height() / bh)
        val cropWidth = (target.width() / scale).coerceAtMost(bw)
        val cropHeight = (target.height() / scale).coerceAtMost(bh)
        val left = ((bw - cropWidth) / 2f).toInt().coerceAtLeast(0)
        val top = ((bh - cropHeight) / 2f).toInt().coerceAtLeast(0)
        val right = (left + cropWidth).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (top + cropHeight).toInt().coerceIn(top + 1, bitmap.height)
        srcRect.set(left, top, right, bottom)
        dstRect.set(target)
        canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
    }
}
