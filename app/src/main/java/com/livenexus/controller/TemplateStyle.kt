package com.livenexus.controller

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.min

/** Visual primitives for the cyberpunk neon LiveNEXUS theme matching reference design images. */
internal object TemplateStyle {
    val ink = Color.rgb(2, 4, 18)
    val cardDark = Color.rgb(6, 10, 30)
    val cardLight = Color.rgb(14, 20, 52)
    val white = Color.rgb(248, 250, 255)
    val pink = Color.rgb(225, 26, 255)
    val magenta = Color.rgb(246, 0, 255)
    val cyan = Color.rgb(0, 234, 255)
    val purple = Color.rgb(125, 38, 224)
    val green = Color.rgb(0, 230, 138)
    val muted = Color.rgb(180, 195, 230)
    val subtext = Color.rgb(130, 145, 180)
    val twitch = Color.rgb(169, 70, 255)
    val youtube = Color.rgb(255, 42, 60)
    val kick = Color.rgb(83, 252, 24)

    /** Neon-bordered card/panel drawable with configurable glow, tint and radius. */
    class Panel(
        private val density: Float,
        private val selected: Boolean = false,
        private val radius: Float = 14f,
        private val glow: Boolean = true,
        private val borderTint: Int? = null,
        private val fillGradient: IntArray? = null
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(c: Canvas) {
            val pad = 3f * density
            val r = RectF(bounds).apply { inset(pad, pad) }
            val corner = radius * density

            // 1. Background fill
            val fillColors = fillGradient ?: if (selected) {
                intArrayOf(0xffdd13ff.toInt(), 0xff9000df.toInt(), 0xff5a00bd.toInt())
            } else {
                intArrayOf(0xff0d1435.toInt(), 0xff060a22.toInt(), 0xff030515.toInt())
            }
            paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, fillColors, null, Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            if (corner > 0f) c.drawRoundRect(r, corner, corner, paint) else c.drawRect(r, paint)

            // 2. Outer neon glow layers
            val borderColors = if (borderTint != null) {
                intArrayOf(borderTint, borderTint, borderTint)
            } else if (selected) {
                intArrayOf(magenta, pink, cyan)
            } else {
                intArrayOf(pink, purple, cyan)
            }
            paint.style = Paint.Style.STROKE
            paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, borderColors, null, Shader.TileMode.CLAMP)

            if (glow) {
                for (i in 3 downTo 1) {
                    paint.strokeWidth = density * (i * 1.5f + 1f)
                    paint.alpha = if (selected) 28 + (3 - i) * 16 else 14 + (3 - i) * 8
                    if (corner > 0f) c.drawRoundRect(r, corner, corner, paint) else c.drawRect(r, paint)
                }
            }

            // 3. Crisp inner border
            paint.alpha = 255
            paint.strokeWidth = density * if (selected) 1.8f else 1.2f
            if (corner > 0f) c.drawRoundRect(r, corner, corner, paint) else c.drawRect(r, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Deep obsidian backdrop with glowing fluid neon aurora ribbons in corners. */
    class Backdrop : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            // Deep background gradient
            p.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.rgb(2, 4, 15), Color.rgb(5, 8, 25), Color.rgb(2, 3, 14)),
                null, Shader.TileMode.CLAMP
            )
            p.style = Paint.Style.FILL
            c.drawRect(0f, 0f, w, h, p)

            // Neon organic auroras / ribbons in all four corners matching reference images
            fun drawRibbon(flipX: Boolean, flipY: Boolean, color: Int) {
                c.save()
                c.translate(if (flipX) w else 0f, if (flipY) h else 0f)
                c.scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f)

                // Soft background radial glow
                val glowColor = (color and 0x00ffffff) or (0x44 shl 24)
                p.shader = RadialGradient(0f, 0f, w * 0.45f, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                p.style = Paint.Style.FILL
                c.drawRect(0f, 0f, w * 0.5f, h * 0.35f, p)

                // Sharp fluid laser ribbon
                val path = Path().apply {
                    moveTo(0f, h * 0.16f)
                    cubicTo(w * 0.12f, h * 0.11f, w * 0.22f, h * 0.04f, w * 0.38f, 0f)
                    lineTo(w * 0.22f, 0f)
                    cubicTo(w * 0.14f, h * 0.04f, w * 0.06f, h * 0.08f, 0f, h * 0.09f)
                    close()
                }
                val startAlpha = (color and 0x00ffffff) or (0x22 shl 24)
                val midAlpha = (color and 0x00ffffff) or (0xbb.toInt() shl 24)
                p.shader = LinearGradient(0f, 0f, w * 0.35f, h * 0.15f,
                    intArrayOf(startAlpha, midAlpha, Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                p.style = Paint.Style.FILL
                c.drawPath(path, p)

                // Glowing laser edge
                val edge = Path().apply {
                    moveTo(0f, h * 0.16f)
                    cubicTo(w * 0.12f, h * 0.11f, w * 0.22f, h * 0.04f, w * 0.38f, 0f)
                }
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.4f
                p.color = color
                c.drawPath(edge, p)

                p.strokeWidth = 5.0f
                p.alpha = 60
                c.drawPath(edge, p)

                c.restore()
            }

            drawRibbon(false, false, magenta)
            drawRibbon(true, false, cyan)
            drawRibbon(false, true, cyan)
            drawRibbon(true, true, magenta)
            p.shader = null
        }

        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** LiveNEXUS Logo brand with play triangle, glowing gradient typography and wing dividers. */
    class Brand(context: Context, private val subtitle: String = "CONTROLE DE TRANSMISSÃO") : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val unit = min(width / 340f, height / 64f)
            val left = (width - 340f * unit) / 2f
            val top = (height - 64f * unit) / 2f
            c.save()
            c.translate(left, top)
            c.scale(unit, unit)

            // Play icon
            drawIcon(c, p, "play", RectF(10f, 4f, 48f, 42f), magenta)

            // "Live" in bold white
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = white
            p.textSize = 36f
            p.typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
            c.drawText("Live", 60f, 36f, p)

            // "NEXUS" in vibrant cyan-magenta gradient
            val liveWidth = p.measureText("Live")
            val nexusStart = 60f + liveWidth
            p.shader = LinearGradient(nexusStart, 0f, 320f, 36f,
                intArrayOf(pink, purple, cyan), null, Shader.TileMode.CLAMP)
            c.drawText("NEXUS", nexusStart, 36f, p)
            p.shader = null

            // Subtitle with stylish horizontal divider lines on both sides: —— SUBTITLE ——
            p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            p.textSize = 8.5f
            p.color = subtext
            p.letterSpacing = 0.22f
            val cleanSub = subtitle.uppercase()
            val textWidth = p.measureText(cleanSub)
            val center = 170f
            c.drawText(cleanSub, center - textWidth / 2f, 56f, p)

            // Horizontal lines
            p.strokeWidth = 1f
            p.color = Color.argb(120, 180, 195, 230)
            val lineLen = 32f
            c.drawLine(center - textWidth / 2f - lineLen - 6f, 53f, center - textWidth / 2f - 6f, 53f, p)
            c.drawLine(center + textWidth / 2f + 6f, 53f, center + textWidth / 2f + lineLen + 6f, 53f, p)

            c.restore()
        }
    }

    /** Status badge "Conectado 🟢" with Wi-Fi icon, matching top-right of all images. */
    class ConnectionBadge(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = context.resources.displayMetrics.density

        override fun onDraw(c: Canvas) {
            val r = RectF(1f * density, 1f * density, width - 1f * density, height - 1f * density)
            val corner = r.height() / 2f

            // Pill background
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(0xee081a28.toInt(), 0xee020a14.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, corner, corner, p)

            // Pill neon border
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.2f * density
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(cyan, green), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, corner, corner, p)
            p.shader = null

            // Wi-Fi icon on the left
            drawIcon(c, p, "wifi", RectF(8f * density, 6f * density, 24f * density, 22f * density), cyan)

            // Green status dot
            val dotX = 30f * density
            val dotY = r.centerY()
            p.style = Paint.Style.FILL
            p.color = green
            c.drawCircle(dotX, dotY, 3.5f * density, p)

            // Text "Conectado"
            p.color = green
            p.textSize = 10.5f * density
            p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            c.drawText("Conectado", dotX + 7f * density, dotY + 3.8f * density, p)
        }
    }

    /** Vector icon generator reproducing the exact icons in the 9 reference images. */
    class Icon(context: Context, val name: String, private val tint: Int = cyan) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c: Canvas) {
            drawIcon(c, p, name, RectF(2f, 2f, width - 2f, height - 2f), tint)
        }
    }

    internal fun drawIcon(c: Canvas, p: Paint, name: String, r: RectF, color: Int) {
        c.save()
        c.translate(r.left, r.top)
        c.scale(r.width() / 32f, r.height() / 32f)
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.2f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = color

        fun line(a: Float, b: Float, d: Float, e: Float) = c.drawLine(a, b, d, e, p)
        fun path(vararg xy: Float) {
            val v = Path()
            v.moveTo(xy[0], xy[1])
            for (i in 2 until xy.size step 2) v.lineTo(xy[i], xy[i + 1])
            c.drawPath(v, p)
        }

        when (name) {
            "youtube" -> {
                p.color = youtube
                p.style = Paint.Style.FILL
                c.drawRoundRect(2f, 7f, 30f, 25f, 6f, 6f, p)
                p.color = white
                path(13f, 11f, 22f, 16f, 13f, 21f, 13f, 11f)
            }
            "kick" -> {
                p.color = kick
                p.style = Paint.Style.FILL
                path(4f, 4f, 12f, 4f, 12f, 12f, 18f, 4f, 27f, 4f, 19f, 15f, 28f, 28f, 18f, 28f, 12f, 19f, 12f, 28f, 4f, 28f, 4f, 4f)
            }
            "twitch" -> {
                p.color = twitch
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.4f
                path(5f, 3f, 28f, 3f, 28f, 20f, 21f, 27f, 15f, 27f, 11f, 30f, 11f, 27f, 5f, 27f, 5f, 3f)
                line(13f, 9f, 13f, 17f)
                line(20f, 9f, 20f, 17f)
            }
            "wifi" -> {
                c.drawCircle(16f, 25f, 2f, p)
                c.drawArc(11f, 17f, 21f, 27f, -145f, 110f, false, p)
                c.drawArc(6f, 11f, 26f, 31f, -145f, 110f, false, p)
                c.drawArc(1f, 5f, 31f, 35f, -145f, 110f, false, p)
            }
            "menu" -> {
                line(5f, 8f, 27f, 8f)
                line(5f, 16f, 27f, 16f)
                line(5f, 24f, 27f, 24f)
            }
            "back" -> path(20f, 6f, 10f, 16f, 20f, 26f)
            "next" -> path(12f, 6f, 22f, 16f, 12f, 26f)
            "prev_track" -> {
                line(9f, 6f, 9f, 26f)
                path(23f, 7f, 12f, 16f, 23f, 25f, 23f, 7f)
            }
            "next_track" -> {
                path(9f, 7f, 20f, 16f, 9f, 25f, 9f, 7f)
                line(23f, 6f, 23f, 26f)
            }
            "close" -> {
                line(8f, 8f, 24f, 24f)
                line(8f, 24f, 24f, 8f)
            }
            "layers" -> {
                path(3f, 10f, 16f, 3f, 29f, 10f, 16f, 17f, 3f, 10f)
                path(3f, 16f, 16f, 23f, 29f, 16f)
                path(3f, 22f, 16f, 29f, 29f, 22f)
            }
            "play" -> {
                p.strokeWidth = 3.5f
                p.shader = LinearGradient(0f, 0f, 32f, 32f, pink, cyan, Shader.TileMode.CLAMP)
                path(8f, 4f, 26f, 16f, 8f, 28f, 8f, 4f)
            }
            "broadcast" -> {
                c.drawCircle(16f, 16f, 2.8f, p)
                c.drawArc(8f, 8f, 24f, 24f, -55f, 110f, false, p)
                c.drawArc(8f, 8f, 24f, 24f, 125f, 110f, false, p)
                c.drawArc(2f, 2f, 30f, 30f, -55f, 110f, false, p)
                c.drawArc(2f, 2f, 30f, 30f, 125f, 110f, false, p)
            }
            "gear" -> {
                c.drawCircle(16f, 16f, 4.5f, p)
                c.drawCircle(16f, 16f, 10f, p)
                for (i in 0..7) {
                    c.save()
                    c.rotate(i * 45f, 16f, 16f)
                    c.drawRect(14f, 1.5f, 18f, 6f, p)
                    c.restore()
                }
            }
            "game" -> {
                val v = Path()
                v.moveTo(9f, 8f)
                v.cubicTo(2f, 7f, 0f, 25f, 5f, 27f)
                v.lineTo(12f, 22f)
                v.lineTo(20f, 22f)
                v.cubicTo(33f, 35f, 33f, 7f, 23f, 8f)
                v.close()
                c.drawPath(v, p)
                line(6f, 16f, 14f, 16f)
                line(10f, 12f, 10f, 20f)
                c.drawCircle(22f, 14f, 1.2f, p)
                c.drawCircle(25f, 18f, 1.2f, p)
            }
            "mic" -> {
                c.drawRoundRect(11f, 3f, 21f, 20f, 5f, 5f, p)
                c.drawArc(6f, 10f, 26f, 25f, 0f, 180f, false, p)
                line(16f, 25f, 16f, 29f)
                line(10f, 29f, 22f, 29f)
            }
            "music" -> {
                path(12f, 24f, 12f, 6f, 26f, 3f, 26f, 20f)
                c.drawOval(4f, 22f, 12f, 28f, p)
                c.drawOval(18f, 18f, 26f, 24f, p)
                // equalizer mini waves
                line(8f, 30f, 8f, 27f)
                line(16f, 30f, 16f, 25f)
                line(22f, 30f, 22f, 26f)
            }
            "keyboard" -> {
                c.drawRoundRect(2f, 6f, 30f, 26f, 4f, 4f, p)
                for (y in listOf(11f, 16f)) {
                    for (x in listOf(7f, 13f, 19f, 25f)) c.drawPoint(x, y, p)
                }
                line(9f, 21f, 23f, 21f)
            }
            "info" -> {
                c.drawCircle(16f, 16f, 12f, p)
                c.drawCircle(16f, 10.5f, 1.2f, p)
                line(16f, 14f, 16f, 22f)
            }
            "link" -> {
                c.save()
                c.rotate(40f, 16f, 16f)
                c.drawRoundRect(9f, 1f, 23f, 18f, 6f, 6f, p)
                c.drawRoundRect(9f, 14f, 23f, 31f, 6f, 6f, p)
                c.restore()
            }
            "save" -> {
                path(4f, 4f, 24f, 4f, 28f, 8f, 28f, 28f, 4f, 28f, 4f, 4f)
                c.drawRect(9f, 4f, 23f, 12f, p)
                c.drawRect(8f, 18f, 24f, 28f, p)
            }
            "eye" -> {
                val v = Path()
                v.moveTo(2f, 16f)
                v.quadTo(16f, 2f, 30f, 16f)
                v.quadTo(16f, 30f, 2f, 16f)
                c.drawPath(v, p)
                c.drawCircle(16f, 16f, 4.5f, p)
            }
            "grid" -> {
                for (x in listOf(4f, 18f)) {
                    for (y in listOf(4f, 18f)) c.drawRoundRect(x, y, x + 10f, y + 10f, 2f, 2f, p)
                }
            }
            "browser" -> {
                c.drawCircle(16f, 16f, 12f, p)
                c.drawOval(10f, 4f, 22f, 28f, p)
                line(4f, 16f, 28f, 16f)
                line(6f, 10f, 26f, 10f)
                line(6f, 22f, 26f, 22f)
            }
            "screen" -> {
                c.drawRoundRect(3f, 4f, 29f, 23f, 3f, 3f, p)
                line(16f, 23f, 16f, 28f)
                line(10f, 28f, 22f, 28f)
            }
            "image" -> {
                c.drawRoundRect(3f, 3f, 29f, 29f, 4f, 4f, p)
                c.drawCircle(10f, 10f, 2.5f, p)
                path(3f, 24f, 12f, 14f, 19f, 20f, 24f, 14f, 29f, 20f)
            }
            "text" -> {
                line(4f, 6f, 28f, 6f)
                line(16f, 6f, 16f, 27f)
                line(11f, 27f, 21f, 27f)
            }
            "video" -> {
                c.drawRoundRect(3f, 6f, 22f, 26f, 3f, 3f, p)
                path(22f, 12f, 29f, 7f, 29f, 25f, 22f, 20f)
            }
            "folder" -> path(3f, 27f, 3f, 6f, 13f, 6f, 16f, 10f, 29f, 10f, 29f, 27f, 3f, 27f)
            "refresh" -> {
                c.drawArc(4f, 4f, 28f, 28f, 35f, 300f, false, p)
                path(27f, 3f, 27f, 12f, 18f, 12f)
            }
            "check" -> path(5f, 16f, 13f, 24f, 27f, 8f)
            "pause" -> {
                p.style = Paint.Style.FILL
                c.drawRoundRect(8f, 6f, 13f, 26f, 2f, 2f, p)
                c.drawRoundRect(19f, 6f, 24f, 26f, 2f, 2f, p)
            }
            "stop", "shape" -> c.drawRoundRect(6f, 6f, 26f, 26f, 3f, 3f, p)
            "lock" -> {
                c.drawRoundRect(6f, 13f, 26f, 28f, 3f, 3f, p)
                c.drawArc(10f, 3f, 22f, 22f, 180f, 180f, false, p)
                c.drawCircle(16f, 20f, 1.8f, p)
            }
            "trash" -> {
                path(7f, 9f, 9f, 28f, 23f, 28f, 25f, 9f)
                line(4f, 9f, 28f, 9f)
                line(12f, 4f, 20f, 4f)
                line(13f, 13f, 13f, 24f)
                line(19f, 13f, 19f, 24f)
            }
            "copy" -> {
                c.drawRoundRect(4f, 4f, 22f, 22f, 3f, 3f, p)
                path(25f, 9f, 28f, 9f, 28f, 28f, 9f, 28f, 9f, 25f)
            }
            "expand" -> {
                path(4f, 11f, 4f, 4f, 11f, 4f)
                path(21f, 4f, 28f, 4f, 28f, 11f)
                path(28f, 21f, 28f, 28f, 21f, 28f)
                path(11f, 28f, 4f, 28f, 4f, 21f)
            }
            "cursor" -> path(6f, 3f, 26f, 19f, 17f, 19f, 22f, 29f, 17f, 31f, 12f, 21f, 6f, 27f, 6f, 3f)
            "move" -> {
                line(16f, 3f, 16f, 29f)
                line(3f, 16f, 29f, 16f)
                path(11f, 8f, 16f, 3f, 21f, 8f)
                path(11f, 24f, 16f, 29f, 21f, 24f)
                path(8f, 11f, 3f, 16f, 8f, 21f)
                path(24f, 11f, 29f, 16f, 24f, 21f)
            }
            "bell" -> {
                path(5f, 23f, 8f, 19f, 8f, 12f)
                c.drawArc(8f, 4f, 24f, 20f, 180f, 180f, false, p)
                path(24f, 12f, 24f, 19f, 27f, 23f, 5f, 23f)
                c.drawArc(12f, 22f, 20f, 30f, 0f, 180f, false, p)
            }
            "share" -> {
                line(9f, 16f, 23f, 7f)
                line(9f, 16f, 23f, 25f)
                for (v in listOf(9f to 16f, 23f to 7f, 23f to 25f)) c.drawCircle(v.first, v.second, 3.8f, p)
            }
            "volume" -> {
                path(4f, 11f, 10f, 11f, 18f, 4f, 18f, 28f, 10f, 21f, 4f, 21f, 4f, 11f)
                c.drawArc(16f, 7f, 28f, 25f, -65f, 130f, false, p)
            }
            else -> {
                c.drawRoundRect(4f, 4f, 28f, 28f, 3f, 3f, p)
                line(9f, 11f, 23f, 11f)
                line(9f, 18f, 23f, 18f)
            }
        }
        p.shader = null
        c.restore()
    }
}
