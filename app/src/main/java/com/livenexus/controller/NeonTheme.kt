package com.livenexus.controller

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView

object NeonTheme {
    val cyan = Color.rgb(0, 214, 255)
    val blue = Color.rgb(41, 182, 246)
    val purple = Color.rgb(218, 42, 255)
    val violet = Color.rgb(156, 44, 255)
    val green = Color.rgb(0, 230, 118)
    val red = Color.rgb(255, 64, 92)
    val ink = Color.rgb(4, 6, 20)

    fun card(violet: Boolean = false, dashed: Boolean = false): Drawable =
        NeonSurface(
            scene = false,
            accentColor = if (violet) this.purple else cyan,
            fillTop = if (violet) 0xFF230A44.toInt() else 0xFF06203D.toInt(),
            fillBottom = if (violet) 0xFF0C0520.toInt() else 0xFF050B1E.toInt(),
            dashed = dashed
        )

    fun tileCard(kind: String): Drawable {
        return when (kind) {
            "add_primary" -> NeonSurface(
                scene = false,
                accentColor = Color.rgb(235, 64, 255),
                fillTop = 0xFF6818A8.toInt(),
                fillBottom = 0xFF2D0954.toInt(),
                dashed = false
            )
            "camera", "livepix" -> NeonSurface(
                scene = false,
                accentColor = purple,
                fillTop = 0xFF24093D.toInt(),
                fillBottom = 0xFF0D051F.toInt(),
                dashed = false
            )
            "zerar" -> NeonSurface(
                scene = false,
                accentColor = violet,
                fillTop = 0xFF1F0C3D.toInt(),
                fillBottom = 0xFF0B061E.toInt(),
                dashed = false
            )
            "plus" -> NeonSurface(
                scene = false,
                accentColor = green,
                fillTop = 0xFF062B27.toInt(),
                fillBottom = 0xFF05131B.toInt(),
                dashed = false
            )
            "minus" -> NeonSurface(
                scene = false,
                accentColor = red,
                fillTop = 0xFF2E0B22.toInt(),
                fillBottom = 0xFF130616.toInt(),
                dashed = false
            )
            "overlay" -> NeonSurface(
                scene = false,
                accentColor = blue,
                fillTop = 0xFF081E3E.toInt(),
                fillBottom = 0xFF050C22.toInt(),
                dashed = false
            )
            else -> NeonSurface(
                scene = false,
                accentColor = cyan,
                fillTop = 0xFF062342.toInt(),
                fillBottom = 0xFF040D22.toInt(),
                dashed = false
            )
        }
    }

    fun actionPanelBackground(): Drawable = ActionPanelDrawable()

    fun previewCardBackground(): Drawable = PreviewCardDrawable()

    fun platformCardBackground(selected: Boolean): Drawable = PlatformCardDrawable(selected)

    fun transmitButtonBackground(): Drawable = TransmitButtonDrawable()

    fun stopTransmitButtonBackground(): Drawable = StopTransmitButtonDrawable()

    fun floatingLogo(context: Context): Drawable = FloatingBubbleDrawable(context)

    fun backdrop(): Drawable = NeonSurface(
        scene = true,
        accentColor = cyan,
        fillTop = 0xFF050718.toInt(),
        fillBottom = 0xFF08041C.toInt(),
        dashed = false
    )

    fun styleLabel(view: TextView) {
        view.setTextColor(Color.WHITE)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

private class ActionPanelDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val radius = minOf(w, h) * 0.065f
        val rect = RectF(2f, 2f, w - 2f, h - 2f)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF1E0836.toInt(), 0xFF0A071F.toInt(), 0xFF08061A.toInt()),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF7B2CBF.toInt(), 0xFF3B1E6D.toInt(), 0xFF5A189A.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        p.strokeWidth = 2.2f
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class PreviewCardDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val radius = minOf(w, h) * 0.08f
        val rect = RectF(2f, 2f, w - 2f, h - 2f)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF180628.toInt(), 0xFF070418.toInt(), 0xFF040616.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null

        // Fundo com ondas suaves neon em violeta/azul
        val wave = Path().apply {
            moveTo(0f, h * 0.70f)
            cubicTo(w * 0.35f, h * 0.55f, w * 0.65f, h * 0.85f, w, h * 0.60f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        p.shader = LinearGradient(
            0f, h * 0.60f, w, h,
            intArrayOf(0x356A11CB, 0x252575FC, 0x40B5179E),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(wave, p)
        p.shader = null

        // Bordas e detalhes cibernéticos angulares nos cantos
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.4f
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFFBA2BFF.toInt(), 0xFF5B1ED4.toInt(), 0xFF9D4EDD.toInt(), 0xFF00D2FF.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null

        // Detalhes de cantos neon (cyber brackets)
        p.color = NeonTheme.purple
        p.strokeWidth = 2f
        val cornerLen = minOf(w, h) * 0.12f
        // Bottom Right
        canvas.drawLine(w - 12f - cornerLen, h - 12f, w - 12f, h - 12f, p)
        canvas.drawLine(w - 12f, h - 12f - cornerLen, w - 12f, h - 12f, p)
        // Top Right
        canvas.drawLine(w - 12f - cornerLen, 12f, w - 12f, 12f, p)
        canvas.drawLine(w - 12f, 12f, w - 12f, 12f + cornerLen, p)

        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class PlatformCardDrawable(val selected: Boolean) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val radius = minOf(w, h) * 0.18f
        val rect = RectF(2f, 2f, w - 2f, h - 2f)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            if (selected) intArrayOf(0xFF1E0C38.toInt(), 0xFF0A0720.toInt())
            else intArrayOf(0xFF0F1026.toInt(), 0xFF07091B.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = if (selected) 2.2f else 1.2f
        p.color = if (selected) NeonTheme.purple else 0xFF2A2E54.toInt()
        canvas.drawRoundRect(rect, radius, radius, p)

        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class TransmitButtonDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val radius = minOf(w, h) * 0.28f
        val rect = RectF(2f, 2f, w - 2f, h - 2f)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0xFFB5179E.toInt(), 0xFF7209B7.toInt(), 0xFF560BAD.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.2f
        p.color = 0xFFF72585.toInt()
        canvas.drawRoundRect(rect, radius, radius, p)

        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class StopTransmitButtonDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val radius = minOf(w, h) * 0.28f
        val rect = RectF(2f, 2f, w - 2f, h - 2f)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFF13152C.toInt(), 0xFF0A0C1E.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f
        p.color = 0xFF35395E.toInt()
        canvas.drawRoundRect(rect, radius, radius, p)

        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class FloatingBubbleDrawable(context: Context) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconBitmap: Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, R.drawable.livenexus_launcher)
    } catch (_: Throwable) {
        null
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val radius = minOf(w, h) * 0.26f
        val rect = RectF(2f, 2f, w - 2f, h - 2f)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF140933.toInt(), 0xFF060A20.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null

        val bmp = iconBitmap
        if (bmp != null) {
            val inset = w * 0.08f
            val dst = RectF(inset, inset, w - inset, h - inset)
            canvas.drawBitmap(bmp, null, dst, p)
        } else {
            drawStylizedN(canvas, w * 0.22f, h * 0.22f, w * 0.56f, p)
        }

        p.style = Paint.Style.STROKE
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(NeonTheme.cyan, NeonTheme.purple),
            null,
            Shader.TileMode.CLAMP
        )
        p.strokeWidth = w * 0.04f
        canvas.drawRoundRect(rect, radius, radius, p)
        p.shader = null
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun drawStylizedN(canvas: Canvas, x: Float, y: Float, size: Float, p: Paint) {
    p.style = Paint.Style.FILL
    p.shader = LinearGradient(
        x, y + size, x + size, y,
        intArrayOf(0xFFBA2BFF.toInt(), 0xFF7B2FFF.toInt(), 0xFF2563FF.toInt(), NeonTheme.cyan),
        null,
        Shader.TileMode.CLAMP
    )
    val leftLeg = Path().apply {
        moveTo(x + size * 0.04f, y + size * 0.92f)
        lineTo(x + size * 0.22f, y + size * 0.08f)
        lineTo(x + size * 0.44f, y + size * 0.08f)
        lineTo(x + size * 0.26f, y + size * 0.92f)
        close()
    }
    val diag = Path().apply {
        moveTo(x + size * 0.24f, y + size * 0.08f)
        lineTo(x + size * 0.45f, y + size * 0.08f)
        lineTo(x + size * 0.78f, y + size * 0.92f)
        lineTo(x + size * 0.57f, y + size * 0.92f)
        close()
    }
    val rightLeg = Path().apply {
        moveTo(x + size * 0.58f, y + size * 0.92f)
        lineTo(x + size * 0.76f, y + size * 0.08f)
        lineTo(x + size * 0.96f, y + size * 0.08f)
        lineTo(x + size * 0.78f, y + size * 0.92f)
        close()
    }
    canvas.drawPath(leftLeg, p)
    canvas.drawPath(diag, p)
    canvas.drawPath(rightLeg, p)
    p.shader = null
}

private class NeonSurface(
    val scene: Boolean,
    val accentColor: Int,
    val fillTop: Int,
    val fillBottom: Int,
    val dashed: Boolean
) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            canvas.restoreToCount(save)
            return
        }
        val inset = if (scene) 0f else 2f
        val r = RectF(inset, inset, w - inset, h - inset)
        val radius = if (scene) 0f else minOf(w, h) * 0.18f

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(fillTop, fillBottom),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(r, radius, radius, p)
        p.shader = null

        if (scene) {
            val wave1 = Path().apply {
                moveTo(0f, h * 0.75f)
                cubicTo(w * 0.35f, h * 0.62f, w * 0.65f, h * 0.88f, w, h * 0.55f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            p.shader = LinearGradient(
                0f, h * 0.55f, w, h,
                intArrayOf(0x285E17EB, 0x1800D2FF, 0x309B26FF),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(wave1, p)
            p.shader = null
        } else {
            p.style = Paint.Style.STROKE
            for (i in 3 downTo 1) {
                p.strokeWidth = if (i == 1) 2.2f else i * 2.2f
                p.color = accentColor
                p.alpha = if (i == 1) 235 else 28
                canvas.drawRoundRect(r, radius, radius, p)
            }
            p.alpha = 255
            if (dashed) {
                p.color = accentColor
                p.alpha = 110
                p.strokeWidth = 1.5f
                p.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
                val inner = RectF(r).apply { inset(w * 0.05f, h * 0.05f) }
                canvas.drawRoundRect(inner, radius * 0.8f, radius * 0.8f, p)
                p.pathEffect = null
                p.alpha = 255
            }
        }
        p.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class NeonIcon(private val symbol: String, private val accent: Int = NeonTheme.cyan) : Drawable() {
    constructor(symbol: String, violet: Boolean) : this(
        symbol,
        if (violet) NeonTheme.purple else NeonTheme.cyan
    )

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            canvas.restoreToCount(save)
            return
        }
        p.color = accent
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND

        when (symbol) {
            "gamepad", "controlador" -> {
                // Controle de videogame estilizado (Controlador)
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                val body = Path().apply {
                    moveTo(w * 0.24f, h * 0.28f)
                    lineTo(w * 0.76f, h * 0.28f)
                    cubicTo(w * 0.94f, h * 0.28f, w * 0.96f, h * 0.65f, w * 0.82f, h * 0.84f)
                    cubicTo(w * 0.72f, h * 0.96f, w * 0.62f, h * 0.80f, w * 0.54f, h * 0.68f)
                    lineTo(w * 0.46f, h * 0.68f)
                    cubicTo(w * 0.38f, h * 0.80f, w * 0.28f, h * 0.96f, w * 0.18f, h * 0.84f)
                    cubicTo(w * 0.04f, h * 0.65f, w * 0.06f, h * 0.28f, w * 0.24f, h * 0.28f)
                    close()
                }
                canvas.drawPath(body, p)
                // D-Pad à esquerda
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.07f
                canvas.drawLine(w * 0.28f, h * 0.48f, w * 0.40f, h * 0.48f, p)
                canvas.drawLine(w * 0.34f, h * 0.42f, w * 0.34f, h * 0.54f, p)
                // 4 botões à direita
                p.style = Paint.Style.FILL
                val r = w * 0.035f
                canvas.drawCircle(w * 0.66f, h * 0.48f, r, p)
                canvas.drawCircle(w * 0.74f, h * 0.42f, r, p)
                canvas.drawCircle(w * 0.74f, h * 0.54f, r, p)
                canvas.drawCircle(w * 0.82f, h * 0.48f, r, p)
            }
            "monitor", "captura" -> {
                // Monitor com base (Captura)
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                val screen = RectF(w * 0.14f, h * 0.18f, w * 0.86f, h * 0.70f)
                canvas.drawRoundRect(screen, w * 0.08f, w * 0.08f, p)
                // Suporte e base
                canvas.drawLine(w * 0.50f, h * 0.70f, w * 0.50f, h * 0.84f, p)
                canvas.drawLine(w * 0.32f, h * 0.84f, w * 0.68f, h * 0.84f, p)
            }
            "broadcast", "transmissao" -> {
                // Antena de transmissão com ondas concêntricas (( • ))
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                val inner = RectF(w * 0.32f, h * 0.28f, w * 0.68f, h * 0.72f)
                canvas.drawArc(inner, 130f, 100f, false, p)
                canvas.drawArc(inner, -50f, 100f, false, p)
                val outer = RectF(w * 0.16f, h * 0.14f, w * 0.84f, h * 0.86f)
                canvas.drawArc(outer, 135f, 90f, false, p)
                canvas.drawArc(outer, -45f, 90f, false, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(w * 0.50f, h * 0.50f, w * 0.09f, p)
            }
            "link", "conexao" -> {
                // Elos de corrente entrelaçados (Conexão)
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                val link1 = RectF(w * 0.16f, h * 0.30f, w * 0.56f, h * 0.70f)
                canvas.drawRoundRect(link1, w * 0.16f, w * 0.16f, p)
                val link2 = RectF(w * 0.44f, h * 0.30f, w * 0.84f, h * 0.70f)
                canvas.drawRoundRect(link2, w * 0.16f, w * 0.16f, p)
            }
            "video_camera", "video" -> {
                // Câmera de vídeo profissional
                p.style = Paint.Style.FILL
                val camBody = RectF(w * 0.14f, h * 0.30f, w * 0.64f, h * 0.70f)
                canvas.drawRoundRect(camBody, w * 0.08f, w * 0.08f, p)
                val lens = Path().apply {
                    moveTo(w * 0.64f, h * 0.42f)
                    lineTo(w * 0.86f, h * 0.26f)
                    lineTo(w * 0.86f, h * 0.74f)
                    lineTo(w * 0.64f, h * 0.58f)
                    close()
                }
                canvas.drawPath(lens, p)
            }
            "audio_wave", "audio" -> {
                // Onda de áudio / Equalizador vertical
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.09f
                canvas.drawLine(w * 0.22f, h * 0.42f, w * 0.22f, h * 0.58f, p)
                canvas.drawLine(w * 0.36f, h * 0.28f, w * 0.36f, h * 0.72f, p)
                canvas.drawLine(w * 0.50f, h * 0.18f, w * 0.50f, h * 0.82f, p)
                canvas.drawLine(w * 0.64f, h * 0.26f, w * 0.64f, h * 0.74f, p)
                canvas.drawLine(w * 0.78f, h * 0.40f, w * 0.78f, h * 0.60f, p)
            }
            "youtube" -> {
                // Ícone oficial YouTube vermelho
                p.style = Paint.Style.FILL
                p.color = Color.rgb(255, 0, 51)
                val rect = RectF(w * 0.10f, h * 0.22f, w * 0.90f, h * 0.78f)
                canvas.drawRoundRect(rect, w * 0.16f, w * 0.16f, p)
                p.color = Color.WHITE
                val triangle = Path().apply {
                    moveTo(w * 0.42f, h * 0.37f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.42f, h * 0.63f)
                    close()
                }
                canvas.drawPath(triangle, p)
            }
            "twitch" -> {
                // Ícone oficial Twitch roxo
                p.style = Paint.Style.FILL
                p.color = Color.rgb(169, 112, 255)
                val bubble = Path().apply {
                    moveTo(w * 0.16f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.66f)
                    lineTo(w * 0.62f, h * 0.66f)
                    lineTo(w * 0.50f, h * 0.82f)
                    lineTo(w * 0.50f, h * 0.66f)
                    lineTo(w * 0.28f, h * 0.66f)
                    lineTo(w * 0.16f, h * 0.52f)
                    close()
                }
                canvas.drawPath(bubble, p)
                p.color = 0xFF180A2A.toInt()
                canvas.drawRect(RectF(w * 0.40f, h * 0.34f, w * 0.47f, h * 0.52f), p)
                canvas.drawRect(RectF(w * 0.57f, h * 0.34f, w * 0.64f, h * 0.52f), p)
            }
            "kick" -> {
                // Ícone Kick verde neon
                p.style = Paint.Style.FILL
                p.color = Color.rgb(83, 252, 24)
                p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                p.textSize = h * 0.76f
                p.textAlign = Paint.Align.CENTER
                canvas.drawText("K", w * 0.50f, h * 0.50f - (p.ascent() + p.descent()) / 2f, p)
            }
            "fullscreen" -> {
                // Colchetes de tela cheia [ ]
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                // Top-Left
                canvas.drawLine(w * 0.18f, h * 0.36f, w * 0.18f, h * 0.18f, p)
                canvas.drawLine(w * 0.18f, h * 0.18f, w * 0.36f, h * 0.18f, p)
                // Top-Right
                canvas.drawLine(w * 0.64f, h * 0.18f, w * 0.82f, h * 0.18f, p)
                canvas.drawLine(w * 0.82f, h * 0.18f, w * 0.82f, h * 0.36f, p)
                // Bottom-Left
                canvas.drawLine(w * 0.18f, h * 0.64f, w * 0.18f, h * 0.82f, p)
                canvas.drawLine(w * 0.18f, h * 0.82f, w * 0.36f, h * 0.82f, p)
                // Bottom-Right
                canvas.drawLine(w * 0.64f, h * 0.82f, w * 0.82f, h * 0.82f, p)
                canvas.drawLine(w * 0.82f, h * 0.82f, w * 0.82f, h * 0.64f, p)
            }
            "back", "arrow_back" -> {
                // Seta para voltar ←
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.09f
                canvas.drawLine(w * 0.20f, h * 0.50f, w * 0.80f, h * 0.50f, p)
                canvas.drawLine(w * 0.20f, h * 0.50f, w * 0.44f, h * 0.26f, p)
                canvas.drawLine(w * 0.20f, h * 0.50f, w * 0.44f, h * 0.74f, p)
            }
            "dots", "more" -> {
                // 3 pontos verticais ⋮
                p.style = Paint.Style.FILL
                canvas.drawCircle(w * 0.50f, h * 0.24f, w * 0.07f, p)
                canvas.drawCircle(w * 0.50f, h * 0.50f, w * 0.07f, p)
                canvas.drawCircle(w * 0.50f, h * 0.76f, w * 0.07f, p)
            }
            "wifi" -> {
                // Ícone Wifi 📶
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                canvas.drawArc(RectF(w * 0.18f, h * 0.22f, w * 0.82f, h * 0.86f), -135f, 90f, false, p)
                canvas.drawArc(RectF(w * 0.32f, h * 0.38f, w * 0.68f, h * 0.82f), -135f, 90f, false, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(w * 0.50f, h * 0.75f, w * 0.08f, p)
            }
            "arrow_right" -> {
                // Chevron direito >
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.10f
                canvas.drawLine(w * 0.36f, h * 0.25f, w * 0.64f, h * 0.50f, p)
                canvas.drawLine(w * 0.64f, h * 0.50f, w * 0.36f, h * 0.75f, p)
            }
            "checkbox_on" -> {
                // Caixa marcada
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                canvas.drawRoundRect(RectF(w * 0.16f, h * 0.16f, w * 0.84f, h * 0.84f), w * 0.14f, w * 0.14f, p)
                p.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f), w * 0.08f, w * 0.08f, p)
            }
            "checkbox_off" -> {
                // Caixa desmarcada vazia
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                canvas.drawRoundRect(RectF(w * 0.16f, h * 0.16f, w * 0.84f, h * 0.84f), w * 0.14f, w * 0.14f, p)
            }
            "♫" -> {
                p.style = Paint.Style.FILL
                canvas.drawOval(RectF(w * 0.18f, h * 0.60f, w * 0.44f, h * 0.82f), p)
                canvas.drawOval(RectF(w * 0.54f, h * 0.52f, w * 0.80f, h * 0.74f), p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.085f
                canvas.drawLine(w * 0.40f, h * 0.68f, w * 0.40f, h * 0.22f, p)
                canvas.drawLine(w * 0.76f, h * 0.60f, w * 0.76f, h * 0.16f, p)
                val beam = Path().apply {
                    moveTo(w * 0.40f, h * 0.22f)
                    lineTo(w * 0.76f, h * 0.16f)
                    lineTo(w * 0.76f, h * 0.30f)
                    lineTo(w * 0.40f, h * 0.36f)
                    close()
                }
                p.style = Paint.Style.FILL
                canvas.drawPath(beam, p)
            }
            "▣" -> {
                p.style = Paint.Style.FILL
                val body = RectF(w * 0.14f, h * 0.28f, w * 0.86f, h * 0.78f)
                canvas.drawRoundRect(body, w * 0.12f, w * 0.12f, p)
                val top = Path().apply {
                    moveTo(w * 0.33f, h * 0.28f)
                    lineTo(w * 0.40f, h * 0.17f)
                    lineTo(w * 0.60f, h * 0.17f)
                    lineTo(w * 0.67f, h * 0.28f)
                    close()
                }
                canvas.drawPath(top, p)
                p.color = 0xFF140726.toInt()
                canvas.drawCircle(w * 0.50f, h * 0.54f, w * 0.17f, p)
                p.color = accent
                canvas.drawCircle(w * 0.50f, h * 0.54f, w * 0.08f, p)
            }
            "↻" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.085f
                val arcRect = RectF(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.80f)
                canvas.drawArc(arcRect, -155f, 130f, false, p)
                canvas.drawArc(arcRect, 25f, 130f, false, p)
                p.style = Paint.Style.FILL
                val arrowTop = Path().apply {
                    moveTo(w * 0.80f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.40f)
                    lineTo(w * 0.62f, h * 0.36f)
                    close()
                }
                val arrowBottom = Path().apply {
                    moveTo(w * 0.20f, h * 0.84f)
                    lineTo(w * 0.16f, h * 0.60f)
                    lineTo(w * 0.38f, h * 0.64f)
                    close()
                }
                canvas.drawPath(arrowTop, p)
                canvas.drawPath(arrowBottom, p)
            }
            "＋", "+" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.11f
                canvas.drawLine(w * 0.50f, h * 0.22f, w * 0.50f, h * 0.78f, p)
                canvas.drawLine(w * 0.22f, h * 0.50f, w * 0.78f, h * 0.50f, p)
            }
            "−" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.12f
                canvas.drawLine(w * 0.24f, h * 0.50f, w * 0.76f, h * 0.50f, p)
            }
            "⟲" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.09f
                val arcRect = RectF(w * 0.20f, h * 0.20f, w * 0.80f, h * 0.80f)
                canvas.drawArc(arcRect, -70f, 290f, false, p)
                p.style = Paint.Style.FILL
                val arrow = Path().apply {
                    moveTo(w * 0.52f, h * 0.10f)
                    lineTo(w * 0.30f, h * 0.23f)
                    lineTo(w * 0.52f, h * 0.35f)
                    close()
                }
                canvas.drawPath(arrow, p)
            }
            "▱", "◆" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.075f
                val topLayer = Path().apply {
                    moveTo(w * 0.50f, h * 0.20f)
                    lineTo(w * 0.82f, h * 0.38f)
                    lineTo(w * 0.50f, h * 0.56f)
                    lineTo(w * 0.18f, h * 0.38f)
                    close()
                }
                canvas.drawPath(topLayer, p)
                val bottomLayer = Path().apply {
                    moveTo(w * 0.18f, h * 0.55f)
                    lineTo(w * 0.50f, h * 0.74f)
                    lineTo(w * 0.82f, h * 0.55f)
                }
                canvas.drawPath(bottomLayer, p)
            }
            "🖼" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.075f
                val frame = RectF(w * 0.16f, h * 0.22f, w * 0.84f, h * 0.78f)
                canvas.drawRoundRect(frame, w * 0.10f, w * 0.10f, p)
                val mountain = Path().apply {
                    moveTo(w * 0.22f, h * 0.70f)
                    lineTo(w * 0.42f, h * 0.46f)
                    lineTo(w * 0.58f, h * 0.62f)
                    lineTo(w * 0.70f, h * 0.50f)
                    lineTo(w * 0.80f, h * 0.70f)
                }
                canvas.drawPath(mountain, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(w * 0.64f, h * 0.37f, w * 0.055f, p)
            }
            "💎" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.07f
                val gem = Path().apply {
                    moveTo(w * 0.30f, h * 0.22f)
                    lineTo(w * 0.70f, h * 0.22f)
                    lineTo(w * 0.86f, h * 0.42f)
                    lineTo(w * 0.50f, h * 0.82f)
                    lineTo(w * 0.14f, h * 0.42f)
                    close()
                }
                canvas.drawPath(gem, p)
                canvas.drawLine(w * 0.14f, h * 0.42f, w * 0.86f, h * 0.42f, p)
                canvas.drawLine(w * 0.30f, h * 0.22f, w * 0.38f, h * 0.42f, p)
                canvas.drawLine(w * 0.70f, h * 0.22f, w * 0.62f, h * 0.42f, p)
                canvas.drawLine(w * 0.38f, h * 0.42f, w * 0.50f, h * 0.82f, p)
                canvas.drawLine(w * 0.62f, h * 0.42f, w * 0.50f, h * 0.82f, p)
            }
            "💬" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.075f
                val bubble = Path().apply {
                    addRoundRect(
                        RectF(w * 0.16f, h * 0.20f, w * 0.84f, h * 0.66f),
                        w * 0.12f,
                        w * 0.12f,
                        Path.Direction.CW
                    )
                }
                canvas.drawPath(bubble, p)
                val tail = Path().apply {
                    moveTo(w * 0.34f, h * 0.66f)
                    lineTo(w * 0.28f, h * 0.82f)
                    lineTo(w * 0.48f, h * 0.66f)
                }
                canvas.drawPath(tail, p)
                p.style = Paint.Style.FILL
                for (i in 0..2) {
                    canvas.drawCircle(w * (0.35f + i * 0.15f), h * 0.43f, w * 0.045f, p)
                }
            }
            "⚙", "configuracao" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.08f
                canvas.drawCircle(w * 0.50f, h * 0.50f, w * 0.16f, p)
                canvas.drawCircle(w * 0.50f, h * 0.50f, w * 0.28f, p)
                for (angle in 0 until 360 step 45) {
                    val rad = Math.toRadians(angle.toDouble())
                    val x1 = (w * 0.50f + Math.cos(rad) * w * 0.26f).toFloat()
                    val y1 = (h * 0.50f + Math.sin(rad) * h * 0.26f).toFloat()
                    val x2 = (w * 0.50f + Math.cos(rad) * w * 0.36f).toFloat()
                    val y2 = (h * 0.50f + Math.sin(rad) * h * 0.36f).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, p)
                }
            }
            else -> {
                p.style = Paint.Style.FILL
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                p.textSize = h * 0.56f
                canvas.drawText(symbol, w / 2f, h / 2f - (p.ascent() + p.descent()) / 2f, p)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class NeonHeader(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(h * 0.74f, w * 0.22f)
        val x = size * 0.08f
        val y = (h - size) / 2f

        drawStylizedN(canvas, x, y, size, p)

        p.style = Paint.Style.FILL
        val start = x + size * 1.18f
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        p.textSize = minOf(h * 0.38f, w * 0.12f)
        p.color = Color.WHITE
        canvas.drawText("LiveNexus", start, h * 0.50f, p)

        p.color = 0xFFD0CEE6.toInt()
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.textSize = minOf(h * 0.22f, w * 0.07f)
        canvas.drawText("C o n t r o l l e r", start, h * 0.80f, p)
    }
}
