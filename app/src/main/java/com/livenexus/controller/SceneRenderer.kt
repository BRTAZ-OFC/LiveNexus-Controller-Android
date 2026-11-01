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
     * Remove barras claras uniformes de letterboxing sem cortar conteúdo legítimo.
     */
    fun previewContentRect(bitmap: Bitmap): Rect {
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        if (bitmap.width < 100 || bitmap.height < 100) return full
        fun blankAt(x: Int): Boolean {
            for (fraction in floatArrayOf(.25f, .5f, .75f)) {
                val y = (bitmap.height * fraction).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                if (minOf(r, g, b) < 170 || maxOf(r, g, b) - minOf(r, g, b) > 24) return false
            }
            return true
        }
        if (!blankAt(1) || !blankAt(bitmap.width - 2) || blankAt(bitmap.width / 2)) return full
        var left = 1
        while (left < bitmap.width / 3 && blankAt(left)) left += 3
        var right = bitmap.width - 2
        while (right > bitmap.width * 2 / 3 && blankAt(right)) right -= 3
        if (left < bitmap.width / 12 || bitmap.width - 1 - right < bitmap.width / 12) return full
        return Rect(left, 0, right + 1, bitmap.height)
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
                        val sameOrientation = (box.width() >= box.height()) == (srcW >= srcH)
                        if (sameOrientation) {
                            val ratio = max(box.width() / srcW, box.height() / srcH)
                            val sourceWidth = (box.width() / ratio).toInt().coerceIn(1, region.width())
                            val sourceHeight = (box.height() / ratio).toInt().coerceIn(1, region.height())
                            val sourceLeft = region.left + (region.width() - sourceWidth) / 2
                            val sourceTop = region.top + (region.height() - sourceHeight) / 2
                            canvas.drawBitmap(
                                screenFrame,
                                Rect(sourceLeft, sourceTop, sourceLeft + sourceWidth, sourceTop + sourceHeight),
                                box, paint
                            )
                        } else {
                            val scale = minOf(box.width() / srcW, box.height() / srcH)
                            val drawW = srcW * scale
                            val drawH = srcH * scale
                            val dstLeft = box.left + (box.width() - drawW) / 2f
                            val dstTop = box.top + (box.height() - drawH) / 2f
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
                        val label = if (box.width() < box.height() * 0.85f) "CAPTURA DE TELA" else "CAPTURA DE TELA - TOQUE PARA INICIAR"
                        canvas.drawText(label, box.centerX(), box.centerY(), paint)
                        if (box.width() < box.height() * 0.85f) {
                            paint.textSize = (minOf(box.width(), box.height()) * 0.07f).coerceIn(10f, 18f)
                            canvas.drawText("TOQUE PARA INICIAR", box.centerX(), box.centerY() + paint.textSize * 1.4f, paint)
                        }
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
