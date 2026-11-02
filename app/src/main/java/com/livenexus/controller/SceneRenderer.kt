package com.livenexus.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max

/**
 * Renderizador de cenários do LiveNEXUS.
 * Usado tanto na prévia interativa (CaptureSceneCanvas) quanto na transmissão ao vivo (SceneCompositor).
 */
object SceneRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    /**
     * Remove barras escuras ou claras uniformes de letterboxing/pillarboxing sem cortar conteúdo legítimo.
     */
    fun previewContentRect(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height
        val full = Rect(0, 0, w, h)
        if (w < 80 || h < 80) return full

        fun isUniformBorderPixel(color: Int, refColor: Int): Boolean {
            val dr = kotlin.math.abs(Color.red(color) - Color.red(refColor))
            val dg = kotlin.math.abs(Color.green(color) - Color.green(refColor))
            val db = kotlin.math.abs(Color.blue(color) - Color.blue(refColor))
            if (dr > 10 || dg > 10 || db > 10) return false
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val isDark = maxOf(r, g, b) <= 16
            val isLight = minOf(r, g, b) >= 180 && (maxOf(r, g, b) - minOf(r, g, b) <= 20)
            return isDark || isLight
        }

        val samples = floatArrayOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)

        fun blankCol(x: Int): Boolean {
            val cx = x.coerceIn(0, w - 1)
            val ref = bitmap.getPixel(cx, h / 2)
            for (f in samples) {
                val y = (h * f).toInt().coerceIn(0, h - 1)
                if (!isUniformBorderPixel(bitmap.getPixel(cx, y), ref)) return false
            }
            return true
        }

        fun blankRow(y: Int): Boolean {
            val cy = y.coerceIn(0, h - 1)
            val ref = bitmap.getPixel(w / 2, cy)
            for (f in samples) {
                val x = (w * f).toInt().coerceIn(0, w - 1)
                if (!isUniformBorderPixel(bitmap.getPixel(x, cy), ref)) return false
            }
            return true
        }

        var left = 0
        var right = w - 1
        if (blankCol(1) && blankCol(w - 2) && !blankCol(w / 2)) {
            val maxCropX = w * 38 / 100
            while (left < maxCropX && blankCol(left)) left += 2
            while (right > w - 1 - maxCropX && blankCol(right)) right -= 2
        }

        var top = 0
        var bottom = h - 1
        if (blankRow(1) && blankRow(h - 2) && !blankRow(h / 2)) {
            val maxCropY = h * 38 / 100
            while (top < maxCropY && blankRow(top)) top += 2
            while (bottom > h - 1 - maxCropY && blankRow(bottom)) bottom -= 2
        }

        if (right - left < w / 4 || bottom - top < h / 4) return full
        return Rect(left, top, right + 1, bottom + 1)
    }

    /**
     * Renderiza o cenário completo nas dimensões especificadas.
     */
    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        layers: List<CaptureLayer>,
        screenFrame: Bitmap?,
        isLiveStream: Boolean = true,
        selectedLayerId: String? = null,
        handleSize: Float = 0f
    ) {
        val area = RectF(0f, 0f, width.toFloat(), height.toFloat())

        // 1. Fundo do estúdio
        canvas.drawColor(Color.rgb(4, 8, 28))
        paint.color = Color.rgb(11, 17, 40)
        paint.style = Paint.Style.FILL
        canvas.drawRect(area, paint)

        // Linhas de grade sutis (apenas na prévia/editor se não for stream ao vivo)
        if (!isLiveStream) {
            paint.color = Color.rgb(35, 50, 81)
            paint.strokeWidth = 2f
            for (step in 1..3) {
                val x = area.left + area.width() * step / 4f
                val y = area.top + area.height() * step / 4f
                canvas.drawLine(x, area.top, x, area.bottom, paint)
                canvas.drawLine(area.left, y, area.right, y, paint)
            }
        }

        canvas.save()
        canvas.clipRect(area)

        layers.forEach { layer ->
            if (!layer.visible) return@forEach
            val box = RectF(
                area.left + layer.x * area.width(),
                area.top + layer.y * area.height(),
                area.left + (layer.x + layer.width) * area.width(),
                area.top + (layer.y + layer.height) * area.height()
            )

            when (layer.type) {
                "screen" -> {
                    if (screenFrame != null && !screenFrame.isRecycled) {
                        canvas.save()
                        canvas.clipRect(box)
                        paint.style = Paint.Style.FILL
                        paint.alpha = 255
                        paint.color = Color.rgb(6, 10, 24)
                        canvas.drawRect(box, paint)
                        paint.color = Color.WHITE
                        val region = previewContentRect(screenFrame)
                        val srcW = region.width().toFloat().coerceAtLeast(1f)
                        val srcH = region.height().toFloat().coerceAtLeast(1f)
                        val boxW = box.width().coerceAtLeast(1f)
                        val boxH = box.height().coerceAtLeast(1f)
                        val srcAspect = srcW / srcH
                        val boxAspect = boxW / boxH
                        if (kotlin.math.abs(srcAspect - boxAspect) / boxAspect <= 0.08f) {
                            canvas.drawBitmap(screenFrame, region, box, paint)
                        } else {
                            val scale = minOf(boxW / srcW, boxH / srcH)
                            val drawW = srcW * scale
                            val drawH = srcH * scale
                            val dstLeft = box.left + (boxW - drawW) / 2f
                            val dstTop = box.top + (boxH - drawH) / 2f
                            canvas.drawBitmap(
                                screenFrame,
                                region,
                                RectF(dstLeft, dstTop, dstLeft + drawW, dstTop + drawH),
                                paint
                            )
                        }
                        canvas.restore()
                    } else if (!isLiveStream) {
                        paint.style = Paint.Style.FILL
                        paint.color = Color.rgb(17, 24, 56)
                        canvas.drawRoundRect(box, 12f, 12f, paint)
                        paint.color = Color.rgb(140, 150, 190)
                        val prevAlign = paint.textAlign
                        paint.textAlign = Paint.Align.CENTER
                        paint.textSize = (minOf(box.width(), box.height()) * 0.085f).coerceIn(11f, 22f)
                        canvas.drawText("CAPTURA DE TELA", box.centerX(), box.centerY(), paint)
                        paint.textAlign = prevAlign
                    }
                }
                "text" -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(220, 28, 14, 56)
                    canvas.drawRoundRect(box, 12f, 12f, paint)
                    borderPaint.color = NeonTheme.purple
                    borderPaint.strokeWidth = 3f
                    canvas.drawRoundRect(box, 12f, 12f, borderPaint)

                    textPaint.color = Color.WHITE
                    textPaint.textSize = (box.height() * 0.55f).coerceIn(14f, 48f)
                    val textY = box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                    canvas.drawText(layer.name.take(60), box.left + 16f, textY, textPaint)
                }
                "browser" -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(200, 12, 36, 68)
                    canvas.drawRoundRect(box, 10f, 10f, paint)
                    borderPaint.color = NeonTheme.cyan
                    borderPaint.strokeWidth = 2f
                    canvas.drawRoundRect(box, 10f, 10f, borderPaint)

                    textPaint.color = NeonTheme.cyan
                    textPaint.textSize = (box.height() * 0.4f).coerceIn(12f, 32f)
                    val textY = box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                    canvas.drawText("🌐 ${layer.name.take(40)}", box.left + 14f, textY, textPaint)
                }
                else -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(17, 52, 75)
                    canvas.drawRoundRect(box, 7f, 7f, paint)
                    paint.color = Color.WHITE
                    paint.textSize = (area.height() * .05f).coerceIn(12f, 28f)
                    canvas.drawText(layer.name.take(40), box.left + 8f, box.centerY(), paint)
                }
            }

            // Alças de seleção e borda ativa no editor
            if (!isLiveStream && layer.id == selectedLayerId) {
                borderPaint.style = Paint.Style.STROKE
                borderPaint.strokeWidth = 3f
                borderPaint.color = NeonTheme.cyan
                canvas.drawRect(box, borderPaint)
                if (handleSize > 0f) {
                    paint.style = Paint.Style.FILL
                    paint.color = NeonTheme.purple
                    canvas.drawCircle(box.right, box.bottom, handleSize * .55f, paint)
                }
            }
        }
        canvas.restore()

        if (!isLiveStream) {
            borderPaint.style = Paint.Style.STROKE
            borderPaint.strokeWidth = 3f
            borderPaint.color = NeonTheme.purple
            canvas.drawRect(area, borderPaint)
        }
    }
}
