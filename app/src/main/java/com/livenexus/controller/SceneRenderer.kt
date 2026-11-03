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
    private val paintLocal = object : ThreadLocal<Paint>() {
        override fun initialValue() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
    private val textPaintLocal = object : ThreadLocal<Paint>() {
        override fun initialValue() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }
    }
    private val borderPaintLocal = object : ThreadLocal<Paint>() {
        override fun initialValue() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
    }

    /**
     * Renderiza o cenário completo nas dimensões especificadas de forma determinística e estável.
     */
    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        layers: List<CaptureLayer>,
        screenFrame: Bitmap?,
        isLiveStream: Boolean = true,
        selectedLayerId: String? = null,
        handleSize: Float = 0f,
        browserFrameDrawer: ((String, Canvas, RectF, Paint) -> Boolean)? = null
    ) {
        val paint = paintLocal.get()!!
        val textPaint = textPaintLocal.get()!!
        val borderPaint = borderPaintLocal.get()!!
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
                        val srcW = screenFrame.width.toFloat().coerceAtLeast(1f)
                        val srcH = screenFrame.height.toFloat().coerceAtLeast(1f)
                        val boxW = box.width().coerceAtLeast(1f)
                        val boxH = box.height().coerceAtLeast(1f)
                        val scale = minOf(boxW / srcW, boxH / srcH)
                        val drawW = srcW * scale
                        val drawH = srcH * scale
                        val dstLeft = box.left + (boxW - drawW) / 2f
                        val dstTop = box.top + (boxH - drawH) / 2f
                        canvas.drawBitmap(
                            screenFrame,
                            null,
                            RectF(dstLeft, dstTop, dstLeft + drawW, dstTop + drawH),
                            paint
                        )
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
                    paint.reset()
                    paint.isAntiAlias = true
                    paint.isFilterBitmap = true
                    val hasFrame = browserFrameDrawer?.invoke(layer.id, canvas, box, paint) == true
                    // A WebView real já está sobre a prévia. Não desenhar um bloco opaco
                    // atrás dela, nem enviar o placeholder para o vídeo ao vivo.
                    if (!hasFrame && !isLiveStream && layer.url.isBlank()) {
                        paint.style = Paint.Style.FILL
                        paint.color = Color.argb(215, 10, 26, 52)
                        canvas.drawRoundRect(box, 10f, 10f, paint)
                        borderPaint.color = NeonTheme.cyan
                        borderPaint.strokeWidth = 2f
                        canvas.drawRoundRect(box, 10f, 10f, borderPaint)

                        textPaint.color = NeonTheme.cyan
                        textPaint.textSize = (box.height() * 0.28f).coerceIn(12f, 26f)
                        val title = "🌐 ${layer.name.take(30)}"
                        val hasSubtitle = box.height() >= 36f
                        val titleY = if (hasSubtitle) box.centerY() - 6f else box.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
                        canvas.drawText(title, box.left + 14f, titleY, textPaint)

                        if (hasSubtitle) {
                            val subPaint = Paint(textPaint).apply {
                                color = if (layer.url.isNotBlank()) Color.rgb(180, 225, 255) else Color.rgb(255, 185, 90)
                                textSize = (textPaint.textSize * 0.75f).coerceIn(10f, 18f)
                            }
                            val subText = if (layer.url.isNotBlank()) layer.url else "Duplo clique para adicionar URL"
                            canvas.drawText(subText.take(50), box.left + 14f, titleY + subPaint.textSize + 8f, subPaint)
                        }
                    }
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
