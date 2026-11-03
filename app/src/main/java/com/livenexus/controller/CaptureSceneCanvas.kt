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
    var locked: Boolean = false,
    var url: String = ""
)

/** Canvas visual de cenas: arrastar para mover e usar o canto inferior direito para redimensionar. */
class CaptureSceneCanvas(context: Context) : View(context) {
    var layers: List<CaptureLayer> = emptyList()
        set(value) { field = value; invalidate() }
    var selectedLayerId: String? = null
        set(value) { field = value; invalidate() }
    var frame: Bitmap? = null
        set(value) { field = value; invalidate() }
    var isVertical: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }
    var onSelection: ((String?) -> Unit)? = null
    var onChange: (() -> Unit)? = null
    var onScreenSourceTap: (() -> Unit)? = null
    var onLayerDoubleClick: ((CaptureLayer) -> Unit)? = null

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTappedLayerId: String? = null
    private var lastTriggeredDoubleClickTime = 0L

    private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val x = e.x
            val y = e.y
            val target = layers.asReversed().firstOrNull {
                it.visible && bounds(it).contains(x, y)
            }
            if (target != null) {
                selectedLayerId = target.id
                onSelection?.invoke(target.id)
                triggerLayerDoubleClick(target)
                return true
            }
            return false
        }
    })

    fun triggerLayerDoubleClick(layer: CaptureLayer) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTriggeredDoubleClickTime > 300L) {
            lastTriggeredDoubleClickTime = now
            onLayerDoubleClick?.invoke(layer)
        }
    }

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

    fun computeStageRect(totalW: Int, totalH: Int): RectF {
        val availW = (totalW - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val availH = (totalH - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val targetRatio = if (isVertical) 9f / 16f else 16f / 9f
        val stageW: Float
        val stageH: Float
        if (availW / availH > targetRatio) {
            stageH = availH
            stageW = stageH * targetRatio
        } else {
            stageW = availW
            stageH = stageW / targetRatio
        }
        val stageLeft = paddingLeft + (availW - stageW) / 2f
        val stageTop = paddingTop + (availH - stageH) / 2f
        return RectF(stageLeft, stageTop, stageLeft + stageW, stageTop + stageH)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val measuredHeight = if (heightMode == View.MeasureSpec.EXACTLY) {
            View.MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (measuredWidth * 9f / 16f).toInt().coerceAtLeast(1)
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stage = computeStageRect(width, height)
        area.set(stage)
        canvas.save()
        canvas.translate(area.left, area.top)
        SceneRenderer.render(
            canvas = canvas,
            width = area.width().toInt().coerceAtLeast(1),
            height = area.height().toInt().coerceAtLeast(1),
            layers = layers,
            screenFrame = frame,
            isLiveStream = false,
            selectedLayerId = selectedLayerId,
            handleSize = handleSize
        )
        canvas.restore()
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
        gestureDetector.onTouchEvent(event)
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
                val movedDist = hypot(event.x - downX, event.y - downY)
                if (multiTouch || movedDist > resources.displayMetrics.density * 5f) {
                    touched = true
                }
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
                val tappedLayer = moving
                if (tappedLayer != null && touched) {
                    onChange?.invoke()
                } else if (tappedLayer != null && !touched) {
                    val now = android.os.SystemClock.uptimeMillis()
                    val dist = hypot(event.x - lastTapX, event.y - lastTapY)
                    if (tappedLayer.id == lastTappedLayerId && (now - lastTapTime) < 450L && dist < handleSize * 2f) {
                        lastTapTime = 0L
                        lastTappedLayerId = null
                        triggerLayerDoubleClick(tappedLayer)
                    } else {
                        lastTapTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                        lastTappedLayerId = tappedLayer.id
                    }
                }
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
