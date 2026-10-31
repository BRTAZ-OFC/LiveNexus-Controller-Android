package com.livenexus.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.hypot

/** Coordenadas proporcionais (0..1), independentes do tamanho do aparelho. */
data class CaptureLayer(
    val id: String,
    val type: String,
    var name: String,
    var x: Float = 0.1f,
    var y: Float = 0.1f,
    var width: Float = 0.8f,
    var height: Float = 0.65f,
    var visible: Boolean = true,
    var locked: Boolean = false
)

/** Canvas visual de cenas: arrastar para mover e usar o canto inferior direito para redimensionar. */
class CaptureSceneCanvas(context: Context) : View(context) {
    var layers: List<CaptureLayer> = emptyList()
        set(value) { field = value; invalidate() }
    var selectedLayerId: String? = null
        set(value) { field = value; invalidate() }
    var frame: Bitmap? = null
        set(value) { field = value; invalidate() }
    var onSelection: ((String?) -> Unit)? = null
    var onChange: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val area = RectF()
    private var moving: CaptureLayer? = null
    private var resizing = false
    private var downX = 0f
    private var downY = 0f
    private var originX = 0f
    private var originY = 0f
    private var originW = 0f
    private var originH = 0f
    private var multiTouch = false
    private var initialSpanX = 0f
    private var initialSpanY = 0f
    private var initialCenterX = 0f
    private var initialCenterY = 0f
    private var originCenterX = 0f
    private var originCenterY = 0f
    private var touched = false
    private val handleSize get() = resources.displayMetrics.density * 20f

    private fun bounds(layer: CaptureLayer): RectF = RectF(
        area.left + layer.x * area.width(),
        area.top + layer.y * area.height(),
        area.left + (layer.x + layer.width) * area.width(),
        area.top + (layer.y + layer.height) * area.height()
    )

    // Remove somente barras claras uniformes que já venham embutidas no bitmap.
    // Não corta bordas de uma captura normal que tenha conteúdo até as extremidades.
    private fun previewContentRect(bitmap: Bitmap): android.graphics.Rect {
        val full = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
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
        return android.graphics.Rect(left, 0, right + 1, bitmap.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(4, 8, 28))
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        val fittedWidth = minOf(availableWidth.toFloat(), availableHeight * 16f / 9f)
        val fittedHeight = fittedWidth * 9f / 16f
        val left = (width - fittedWidth) / 2f
        val top = (height - fittedHeight) / 2f
        area.set(left, top, left + fittedWidth, top + fittedHeight)
        paint.color = Color.rgb(11, 17, 40)
        paint.style = Paint.Style.FILL
        canvas.drawRect(area, paint)

        paint.color = Color.rgb(35, 50, 81)
        paint.strokeWidth = resources.displayMetrics.density
        for (step in 1..3) {
            val x = area.left + area.width() * step / 4f
            val y = area.top + area.height() * step / 4f
            canvas.drawLine(x, area.top, x, area.bottom, paint)
            canvas.drawLine(area.left, y, area.right, y, paint)
        }

        canvas.save()
        canvas.clipRect(area)
        layers.forEach { layer ->
            if (!layer.visible) return@forEach
            val box = bounds(layer)
            if (layer.type == "screen" && frame != null) {
                paint.style = Paint.Style.FILL
                paint.alpha = 255
                paint.color = Color.WHITE
                val bitmap = frame!!
                // CENTER_CROP / COVER: removes empty borders from mismatched aspect ratios.
                val region = previewContentRect(bitmap)
                val ratio = max(box.width() / region.width(), box.height() / region.height())
                val sourceWidth = (box.width() / ratio).toInt().coerceIn(1, region.width())
                val sourceHeight = (box.height() / ratio).toInt().coerceIn(1, region.height())
                val sourceLeft = region.left + (region.width() - sourceWidth) / 2
                val sourceTop = region.top + (region.height() - sourceHeight) / 2
                canvas.drawBitmap(
                    bitmap,
                    android.graphics.Rect(sourceLeft, sourceTop, sourceLeft + sourceWidth, sourceTop + sourceHeight),
                    box, paint
                )
            } else {
                paint.style = Paint.Style.FILL
                paint.color = if (layer.type == "text") Color.rgb(32, 16, 63) else Color.rgb(17, 52, 75)
                canvas.drawRoundRect(box, 7f, 7f, paint)
                paint.color = Color.WHITE
                paint.textSize = (area.height() * .065f).coerceIn(12f, 28f)
                val label = if (layer.type == "screen") "TELA - INICIE A CAPTURA" else layer.name
                canvas.drawText(label.take(40), box.left + 8f, box.centerY(), paint)
            }
            if (layer.id == selectedLayerId) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = resources.displayMetrics.density * 2f
                paint.color = NeonTheme.cyan
                canvas.drawRect(box, paint)
                paint.style = Paint.Style.FILL
                paint.color = NeonTheme.purple
                canvas.drawCircle(box.right, box.bottom, handleSize * .55f, paint)
            }
        }
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * 2f
        paint.color = NeonTheme.purple
        canvas.drawRect(area, paint)
        paint.style = Paint.Style.FILL
    }

    private fun rememberPinch(event: MotionEvent, target: CaptureLayer) {
        multiTouch = true
        resizing = false
        initialSpanX = abs(event.getX(1) - event.getX(0)).coerceAtLeast(1f)
        initialSpanY = abs(event.getY(1) - event.getY(0)).coerceAtLeast(1f)
        initialCenterX = (event.getX(0) + event.getX(1)) / 2f
        initialCenterY = (event.getY(0) + event.getY(1)) / 2f
        originX = target.x
        originY = target.y
        originW = target.width
        originH = target.height
        originCenterX = originX + originW / 2f
        originCenterY = originY + originH / 2f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (area.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                moving = layers.asReversed().firstOrNull {
                    it.visible && bounds(it).let { box ->
                        box.contains(x, y) ||
                            (abs(x - box.right) <= handleSize && abs(y - box.bottom) <= handleSize)
                    }
                }
                val target = moving
                selectedLayerId = target?.id
                onSelection?.invoke(target?.id)
                multiTouch = false
                touched = false
                if (target == null || target.locked) {
                    moving = null
                    return true
                }
                val box = bounds(target)
                resizing = abs(x - box.right) <= handleSize && abs(y - box.bottom) <= handleSize
                downX = x
                downY = y
                originX = target.x
                originY = target.y
                originW = target.width
                originH = target.height
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val target = moving ?: selectedLayerId?.let { id ->
                    layers.find { it.id == id && it.visible && !it.locked }
                }
                if (event.pointerCount >= 2 && target != null) {
                    moving = target
                    rememberPinch(event, target)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val target = moving ?: return true
                if (multiTouch && event.pointerCount >= 2) {
                    val spanX = abs(event.getX(1) - event.getX(0))
                    val spanY = abs(event.getY(1) - event.getY(0))
                    val deltaX = spanX - initialSpanX
                    val deltaY = spanY - initialSpanY
                    val threshold = handleSize * .45f
                    val horizontal = abs(deltaX) > threshold
                    val vertical = abs(deltaY) > threshold
                    val scaleX = if (horizontal) spanX / initialSpanX else 1f
                    val scaleY = if (vertical) spanY / initialSpanY else 1f
                    // Diagonal pinch scales both axes by the same amount.
                    val proportional = horizontal && vertical &&
                        abs(deltaX / max(1f, area.width())) > .015f &&
                        abs(deltaY / max(1f, area.height())) > .015f
                    val factor = if (proportional) {
                        hypot(spanX, spanY) / hypot(initialSpanX, initialSpanY)
                    } else 1f
                    target.width = (originW * if (proportional) factor else scaleX).coerceIn(.08f, 4f)
                    target.height = (originH * if (proportional) factor else scaleY).coerceIn(.08f, 4f)
                    val centerDx = ((event.getX(0) + event.getX(1)) / 2f - initialCenterX) /
                        max(1f, area.width())
                    val centerDy = ((event.getY(0) + event.getY(1)) / 2f - initialCenterY) /
                        max(1f, area.height())
                    target.x = (originCenterX + centerDx - target.width / 2f).coerceIn(-3f, 3f)
                    target.y = (originCenterY + centerDy - target.height / 2f).coerceIn(-3f, 3f)
                } else if (!multiTouch) {
                    val dx = (event.x - downX) / max(1f, area.width())
                    val dy = (event.y - downY) / max(1f, area.height())
                    if (resizing) {
                        target.width = (originW + dx).coerceIn(.08f, 4f)
                        target.height = (originH + dy).coerceIn(.08f, 4f)
                    } else {
                        target.x = (originX + dx).coerceIn(-3f, 3f)
                        target.y = (originY + dy).coerceIn(-3f, 3f)
                    }
                }
                touched = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (multiTouch) {
                    if (touched) onChange?.invoke()
                    moving = null
                    multiTouch = false
                    touched = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moving != null && touched) onChange?.invoke()
                moving = null
                multiTouch = false
                touched = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moving = null
                multiTouch = false
                touched = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
