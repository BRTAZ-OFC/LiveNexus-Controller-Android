package com.livenexus.controller

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView

object NeonTheme {
    val cyan = Color.rgb(0, 223, 255)
    val purple = Color.rgb(231, 36, 255)
    val ink = Color.rgb(2, 3, 22)
    fun card(violet: Boolean = false, dashed: Boolean = false): Drawable = NeonSurface(false, violet, dashed)
    fun backdrop(): Drawable = NeonSurface(true, false, false)
    fun styleLabel(view: TextView) {
        view.setTextColor(Color.WHITE)
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

private class NeonSurface(val scene: Boolean, val violet: Boolean, val dashed: Boolean) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) { canvas.restoreToCount(save); return }
        val accent = if (violet) NeonTheme.purple else NeonTheme.cyan
        val inset = if (scene) 0f else w * 0.035f
        val r = RectF(inset, inset, w-inset, h-inset)
        val radius = if (scene) 0f else minOf(w,h)*0.15f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f,0f,w,h, intArrayOf(if(violet) 0xFF20043A.toInt() else 0xFF03182F.toInt(), NeonTheme.ink, 0xFF070523.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r,radius,radius,p); p.shader = null
        val clip = Path().apply { addRoundRect(r,radius,radius,Path.Direction.CW) }
        canvas.save(); canvas.clipPath(clip)
        // Faixas diagonais e facetas estáticas, como no template de referência.
        for (i in 0..7) {
            p.color = if(i % 2 == 0) 0x221D16D9 else 0x222E07A4
            val x = w * (0.57f + i * 0.065f)
            val path = Path().apply {
                moveTo(x,0f); lineTo(x+w*.07f,0f); lineTo(w,h*.32f+i*h*.045f); lineTo(w,h*.24f+i*h*.045f); close()
            }
            canvas.drawPath(path,p)
        }
        if (scene) {
            for (side in 0..1) {
                canvas.save()
                if(side == 1) { canvas.translate(w,h); canvas.rotate(180f) }
                for(i in 0..6) {
                    val y = h*.59f+i*h*.035f
                    p.color = if(i%2 == 0) 0x441B0DB2 else 0x33083168
                    val path = Path().apply { moveTo(0f,y); lineTo(w*.46f,y+w*.46f); lineTo(w*.46f,y+w*.40f); lineTo(0f,y-w*.06f); close() }
                    canvas.drawPath(path,p)
                }
                p.style = Paint.Style.STROKE
                for(i in 5 downTo 1) {
                    p.strokeWidth = i*w*.003f; p.color = Color.argb(if(i==1) 220 else 20, if(side==0) 20 else 220, if(side==0) 200 else 30,255)
                    canvas.drawLine(0f,h*.65f,w*.48f,h*.65f+w*.48f,p)
                }
                p.style = Paint.Style.FILL
                for(x in 0..8) for(y in 0..10) { p.color = Color.argb(35,0,180,255); canvas.drawCircle(w*.025f+x*w*.017f,h*.83f+y*w*.017f,w*.0017f,p) }
                canvas.restore()
            }
        }
        canvas.restore()
        if (!scene) {
            p.style = Paint.Style.STROKE
            p.shader = LinearGradient(0f,0f,w,h,intArrayOf(accent,0xFF2516AB.toInt(),if(violet) NeonTheme.cyan else NeonTheme.purple,accent),null,Shader.TileMode.CLAMP)
            for(i in 5 downTo 1) { p.strokeWidth = w*.007f*i; p.alpha = if(i==1) 255 else 17; canvas.drawRoundRect(r,radius,radius,p) }
            p.shader = null; p.alpha = 255
            if(dashed) {
                p.color = 0xFF575A9E.toInt(); p.strokeWidth=w*.005f; p.pathEffect=DashPathEffect(floatArrayOf(w*.04f,w*.035f),0f)
                val inner=RectF(r).apply { inset(w*.05f,w*.05f) }; canvas.drawRoundRect(inner,radius*.8f,radius*.8f,p); p.pathEffect=null
            }
            p.color=accent; p.strokeCap=Paint.Cap.ROUND; p.strokeWidth=w*.018f
            canvas.drawLine(w*.43f,h*.89f,w*.57f,h*.89f,p)
            p.strokeCap=Paint.Cap.BUTT
        }
        p.style=Paint.Style.FILL; canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class NeonIcon(private val symbol: String, private val violet: Boolean = false) : Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val save=canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        val w=bounds.width().toFloat(); val h=bounds.height().toFloat()
        p.style=Paint.Style.FILL; p.color=0xFF050B30.toInt(); canvas.drawRoundRect(RectF(2f,2f,w-2,h-2),w*.22f,w*.22f,p)
        val accent=if(violet) NeonTheme.purple else NeonTheme.cyan
        p.style=Paint.Style.STROKE; p.color=accent
        for(i in 4 downTo 1) { p.strokeWidth=i*w*.025f; p.alpha=if(i==1) 220 else 20; canvas.drawRoundRect(RectF(3f,3f,w-3,h-3),w*.22f,w*.22f,p) }
        p.alpha=255; p.style=Paint.Style.FILL; p.textAlign=Paint.Align.CENTER; p.typeface=Typeface.create("sans-serif",Typeface.BOLD); p.textSize=h*.63f
        when (symbol) {
            "▣" -> {
                canvas.drawRoundRect(RectF(w*.22f,h*.32f,w*.78f,h*.72f),w*.06f,w*.06f,p)
                canvas.drawRoundRect(RectF(w*.36f,h*.24f,w*.62f,h*.38f),w*.04f,w*.04f,p)
                p.color=0xFF080A2F.toInt(); canvas.drawCircle(w*.5f,h*.51f,w*.13f,p)
            }
            "♙" -> {
                p.style=Paint.Style.STROKE; p.strokeWidth=w*.055f
                canvas.drawCircle(w*.5f,h*.34f,w*.13f,p)
                canvas.drawRoundRect(RectF(w*.25f,h*.54f,w*.75f,h*.81f),w*.12f,w*.12f,p)
                p.style=Paint.Style.FILL
            }
            "▱" -> {
                p.style=Paint.Style.STROKE; p.strokeWidth=w*.04f
                for(i in 0..2) {
                    val y=h*(.3f+i*.16f)
                    val layer=Path().apply { moveTo(w*.23f,y); lineTo(w*.5f,y-h*.12f); lineTo(w*.77f,y); lineTo(w*.5f,y+h*.12f); close() }
                    canvas.drawPath(layer,p)
                }
                p.style=Paint.Style.FILL
            }
            "💬" -> {
                p.style=Paint.Style.STROKE; p.strokeWidth=w*.04f
                canvas.drawRoundRect(RectF(w*.2f,h*.25f,w*.8f,h*.65f),w*.08f,w*.08f,p)
                canvas.drawLine(w*.32f,h*.65f,w*.28f,h*.78f,p)
                canvas.drawLine(w*.28f,h*.78f,w*.48f,h*.65f,p)
                p.style=Paint.Style.FILL
                for(i in 0..2) canvas.drawCircle(w*(.35f+i*.15f),h*.45f,w*.025f,p)
            }
            else -> canvas.drawText(symbol,w/2,h/2-(p.ascent()+p.descent())/2,p)
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int) {}
    override fun setColorFilter(filter:ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

class NeonHeader(context: Context) : View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h=height.toFloat(); val w=width.toFloat(); val size=minOf(h*.68f,w*.15f); val x=size*.15f; val y=(h-size)/2
        p.style=Paint.Style.STROKE; p.strokeWidth=size*.035f
        p.shader=LinearGradient(x,y,x+size,y+size,intArrayOf(NeonTheme.cyan,0xFF4020F4.toInt(),NeonTheme.purple),null,Shader.TileMode.CLAMP)
        val hex=Path().apply { moveTo(x+size*.5f,y); lineTo(x+size,y+size*.25f); lineTo(x+size,y+size*.75f); lineTo(x+size*.5f,y+size); lineTo(x,y+size*.75f); lineTo(x,y+size*.25f); close() }
        canvas.drawPath(hex,p); p.strokeWidth=size*.13f
        val n=Path().apply { moveTo(x+size*.24f,y+size*.75f); lineTo(x+size*.24f,y+size*.25f); lineTo(x+size*.76f,y+size*.75f); lineTo(x+size*.76f,y+size*.25f) }; canvas.drawPath(n,p)
        p.shader=null; p.style=Paint.Style.FILL; p.typeface=Typeface.create("sans-serif",Typeface.BOLD); p.textSize=minOf(h*.34f,w*.085f)
        val start=x+size*1.25f; p.color=Color.WHITE; canvas.drawText("Live",start,h*.53f,p)
        val next=start+p.measureText("Live")
        p.shader=LinearGradient(next,0f,next+p.measureText("Nexus"),0f,intArrayOf(NeonTheme.cyan,0xFF6030FE.toInt(),NeonTheme.purple),null,Shader.TileMode.CLAMP)
        canvas.drawText("Nexus",next,h*.53f,p); p.shader=null
        p.color=0xFF9391AC.toInt(); p.typeface=Typeface.create("sans-serif",Typeface.NORMAL); p.textSize=h*.12f
        canvas.drawText("C O N T R O L L E R",start,h*.77f,p)
    }
}
