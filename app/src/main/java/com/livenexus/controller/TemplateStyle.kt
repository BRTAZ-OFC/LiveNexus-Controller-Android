package com.livenexus.controller

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.min

/** Visual primitives only. Layout and touch handling belong to native Android views. */
internal object TemplateStyle {
    val ink = Color.rgb(2, 5, 19)
    val white = Color.rgb(248, 249, 255)
    val pink = Color.rgb(221, 19, 255)
    val cyan = Color.rgb(0, 229, 255)
    val purple = Color.rgb(111, 58, 218)
    val muted = Color.rgb(189, 198, 222)

    class Panel(private val density: Float, private val selected: Boolean = false,
                private val radius: Float = 12f, private val glow: Boolean = true) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val r = RectF(bounds).apply { inset(3f * density, 3f * density) }
            val corner = radius * density
            paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                if (selected) intArrayOf(0xffdb09ff.toInt(), 0xff6200a6.toInt(), 0xffa000ef.toInt())
                else intArrayOf(0xff111029.toInt(), 0xff020617.toInt(), 0xff001527.toInt()),
                null, Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            c.drawRoundRect(r, corner, corner, paint)
            paint.style = Paint.Style.STROKE
            paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(pink, purple, cyan), null, Shader.TileMode.CLAMP)
            if (glow) for (i in 4 downTo 1) {
                paint.strokeWidth = density * (i + 1)
                paint.alpha = 15 + (4-i)*5
                c.drawRoundRect(r, corner, corner, paint)
            }
            paint.alpha = 255
            paint.strokeWidth = density * if (glow) 1.3f else .65f
            c.drawRoundRect(r, corner, corner, paint)
            paint.shader = null
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    class Backdrop : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            c.drawColor(ink)
            fun ribbon(flip: Boolean, bottom: Boolean, color: Int) {
                c.save()
                c.translate(if(flip) w else 0f, if(bottom) h else 0f)
                c.scale(if(flip) -1f else 1f, if(bottom) -1f else 1f)
                val path = Path().apply {
                    moveTo(0f, h*.13f)
                    cubicTo(w*.13f,h*.07f,w*.17f,h*.025f,w*.30f,0f)
                    lineTo(w*.14f,0f); lineTo(0f,h*.04f); close()
                }
                p.shader = LinearGradient(0f,0f,w*.28f,h*.10f,
                    intArrayOf(color and 0x55ffffff,color,color and 0x00ffffff), null,Shader.TileMode.CLAMP)
                p.style=Paint.Style.FILL;c.drawPath(path,p)
                p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=1.3f;p.color=color
                val edge=Path().apply { moveTo(0f,h*.13f);cubicTo(w*.13f,h*.07f,w*.17f,h*.025f,w*.30f,0f) }
                c.drawPath(edge,p)
                p.shader=RadialGradient(0f,h*.08f,w*.38f,color and 0x55ffffff,Color.TRANSPARENT,Shader.TileMode.CLAMP)
                p.style=Paint.Style.FILL;c.drawRect(0f,0f,w*.5f,h*.25f,p);p.shader=null
                c.restore()
            }
            ribbon(false,false,pink);ribbon(true,false,cyan)
            ribbon(false,true,cyan);ribbon(true,true,pink)
        }
        override fun setAlpha(alpha: Int) { p.alpha=alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter=colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
    }

    class Brand(context: Context, private val subtitle: String = "CONTROLE DE TRANSMISSÃO") : View(context) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val unit=min(width/325f,height/73f)
            val left=(width-325f*unit)/2f; val top=(height-73f*unit)/2f
            c.save();c.translate(left,top);c.scale(unit,unit)
            drawIcon(c,p,"play",RectF(7f,2f,49f,44f),pink)
            p.style=Paint.Style.FILL;p.shader=null;p.color=white;p.textSize=41f
            p.typeface=Typeface.create("sans-serif",Typeface.BOLD_ITALIC)
            c.drawText("Live",61f,40f,p)
            val start=61f+p.measureText("Live")
            p.shader=LinearGradient(start,0f,319f,40f,pink,cyan,Shader.TileMode.CLAMP)
            c.drawText("NEXUS",start,40f,p);p.shader=null
            p.typeface=Typeface.create("sans-serif",Typeface.NORMAL);p.textSize=10f;p.color=white
            p.textAlign=Paint.Align.CENTER
            c.drawText(subtitle.toCharArray().joinToString(" "),162f,66f,p)
            p.textAlign=Paint.Align.LEFT;c.restore()
        }
    }

    class Icon(context: Context, val name: String, private val tint: Int = cyan) : View(context) {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(c:Canvas) { drawIcon(c,p,name,RectF(2f,2f,width-2f,height-2f),tint) }
    }

    private fun drawIcon(c:Canvas,p:Paint,name:String,r:RectF,color:Int) {
        c.save();c.translate(r.left,r.top);c.scale(r.width()/32f,r.height()/32f)
        p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeWidth=2f
        p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.color=color
        fun line(a:Float,b:Float,d:Float,e:Float)=c.drawLine(a,b,d,e,p)
        fun path(vararg xy:Float) { val v=Path();v.moveTo(xy[0],xy[1]);for(i in 2 until xy.size step 2)v.lineTo(xy[i],xy[i+1]);c.drawPath(v,p) }
        when(name) {
            "youtube" -> {p.color=Color.RED;p.style=Paint.Style.FILL;c.drawRoundRect(1f,6f,31f,27f,6f,6f,p);p.color=white;path(13f,11f,23f,16f,13f,22f,13f,11f)}
            "kick" -> {p.color=0xff53fc18.toInt();p.style=Paint.Style.FILL;path(3f,2f,13f,2f,13f,11f,20f,2f,29f,2f,20f,16f,29f,30f,19f,30f,13f,21f,13f,30f,3f,30f,3f,2f)}
            "twitch" -> {p.color=0xffbf66ff.toInt();path(5f,2f,30f,2f,30f,21f,22f,28f,16f,28f,10f,32f,10f,28f,3f,28f,3f,5f,5f,2f);line(14f,8f,14f,17f);line(23f,8f,23f,17f)}
            "menu" -> { line(5f,7f,27f,7f);line(5f,16f,27f,16f);line(5f,25f,27f,25f) }
            "back" -> path(20f,5f,9f,16f,20f,27f)
            "next" -> path(11f,5f,22f,16f,11f,27f)
            "close" -> {line(7f,7f,25f,25f);line(7f,25f,25f,7f)}
            "layers" -> {path(3f,11f,16f,3f,29f,11f,16f,19f,3f,11f);path(3f,17f,16f,25f,29f,17f);path(3f,23f,16f,31f,29f,23f)}
            "play" -> {p.strokeWidth=4.5f;p.shader=LinearGradient(0f,0f,32f,32f,pink,cyan,Shader.TileMode.CLAMP);path(7f,3f,28f,16f,7f,29f,7f,3f)}
            "broadcast" -> {c.drawCircle(16f,16f,2f,p);for(i in listOf(8f,14f)){c.drawArc(16f-i,16f-i,16f+i,16f+i,-55f,110f,false,p);c.drawArc(16f-i,16f-i,16f+i,16f+i,125f,110f,false,p)}}
            "gear" -> {c.drawCircle(16f,16f,5f,p);c.drawCircle(16f,16f,11f,p);for(i in 0..7){c.save();c.rotate(i*45f,16f,16f);c.drawRect(13f,1f,19f,6f,p);c.restore()}}
            "game" -> {val v=Path();v.moveTo(9f,8f);v.cubicTo(2f,7f,0f,25f,5f,27f);v.lineTo(12f,22f);v.lineTo(20f,22f);v.cubicTo(33f,35f,33f,7f,23f,8f);v.close();c.drawPath(v,p);line(6f,16f,14f,16f);line(10f,12f,10f,20f);c.drawCircle(23f,14f,1f,p);c.drawCircle(26f,18f,1f,p)}
            "mic" -> {c.drawRoundRect(11f,2f,21f,22f,5f,5f,p);c.drawArc(6f,10f,26f,27f,0f,180f,false,p);line(16f,27f,16f,31f);line(10f,31f,22f,31f)}
            "music" -> {path(12f,25f,12f,7f,27f,3f,27f,21f);c.drawOval(3f,23f,12f,30f,p);c.drawOval(18f,19f,27f,26f,p)}
            "keyboard" -> {c.drawRoundRect(2f,5f,30f,27f,3f,3f,p);for(y in listOf(11f,16f))for(x in listOf(7f,13f,19f,25f))c.drawPoint(x,y,p);line(10f,22f,23f,22f)}
            "bot" -> {c.drawRoundRect(4f,9f,28f,28f,6f,6f,p);line(16f,3f,16f,9f);c.drawCircle(11f,17f,1f,p);c.drawCircle(22f,17f,1f,p);line(12f,23f,20f,23f)}
            "link" -> {c.save();c.rotate(40f,16f,16f);c.drawRoundRect(10f,0f,22f,19f,6f,6f,p);c.drawRoundRect(10f,13f,22f,32f,6f,6f,p);c.restore()}
            "save" -> {path(3f,3f,24f,3f,29f,8f,29f,29f,3f,29f,3f,3f);c.drawRect(9f,3f,22f,12f,p);c.drawRect(9f,20f,23f,29f,p)}
            "eye" -> {val v=Path();v.moveTo(1f,16f);v.quadTo(16f,-3f,31f,16f);v.quadTo(16f,35f,1f,16f);c.drawPath(v,p);c.drawCircle(16f,16f,5f,p)}
            "grid" -> for(x in listOf(3f,19f))for(y in listOf(3f,19f))c.drawRoundRect(x,y,x+10f,y+10f,2f,2f,p)
            "screen" -> {c.drawRoundRect(2f,3f,30f,24f,2f,2f,p);line(16f,24f,16f,30f);line(10f,30f,22f,30f)}
            "image" -> {c.drawRoundRect(3f,3f,29f,29f,3f,3f,p);c.drawCircle(10f,10f,2f,p);path(3f,25f,13f,15f,19f,21f,24f,15f,29f,21f)}
            "text" -> {line(4f,4f,28f,4f);line(16f,4f,16f,29f);line(11f,29f,21f,29f)}
            "video" -> {c.drawRoundRect(2f,6f,22f,27f,3f,3f,p);path(22f,12f,30f,7f,30f,26f,22f,21f)}
            "folder" -> path(2f,29f,2f,6f,12f,6f,16f,11f,30f,11f,30f,29f,2f,29f)
            "globe" -> {c.drawCircle(16f,16f,14f,p);c.drawOval(10f,2f,22f,30f,p);line(2f,16f,30f,16f);line(6f,8f,26f,8f);line(6f,24f,26f,24f)}
            "refresh" -> {c.drawArc(4f,4f,28f,28f,35f,300f,false,p);path(28f,2f,28f,12f,19f,12f)}
            "check" -> path(3f,17f,12f,26f,29f,6f)
            "pause" -> {c.drawRoundRect(7f,4f,11f,28f,1f,1f,p);c.drawRoundRect(21f,4f,25f,28f,1f,1f,p)}
            "stop", "shape" -> c.drawRoundRect(5f,5f,27f,27f,2f,2f,p)
            "lock" -> {c.drawRoundRect(5f,14f,27f,30f,3f,3f,p);c.drawArc(10f,1f,22f,24f,180f,180f,false,p);c.drawPoint(16f,22f,p)}
            "trash" -> {path(7f,9f,9f,29f,24f,29f,26f,9f);line(4f,8f,29f,8f);line(12f,3f,21f,3f);line(14f,13f,14f,25f);line(20f,13f,20f,25f)}
            "copy" -> {c.drawRoundRect(3f,3f,23f,23f,2f,2f,p);path(26f,9f,29f,9f,29f,29f,9f,29f,9f,26f)}
            "expand" -> {path(3f,11f,3f,3f,11f,3f);path(21f,3f,29f,3f,29f,11f);path(29f,21f,29f,29f,21f,29f);path(11f,29f,3f,29f,3f,21f)}
            "cursor" -> path(6f,2f,26f,20f,17f,19f,22f,29f,17f,31f,12f,21f,6f,28f,6f,2f)
            "move" -> {line(16f,2f,16f,30f);line(2f,16f,30f,16f);path(10f,8f,16f,2f,22f,8f);path(10f,24f,16f,30f,22f,24f);path(8f,10f,2f,16f,8f,22f);path(24f,10f,30f,16f,24f,22f)}
            "bell" -> {path(4f,24f,8f,19f,8f,12f);c.drawArc(8f,4f,24f,20f,180f,180f,false,p);path(24f,12f,24f,19f,28f,24f,4f,24f);c.drawArc(12f,23f,20f,31f,0f,180f,false,p)}
            "share" -> {line(8f,16f,24f,6f);line(8f,16f,24f,26f);for(v in listOf(8f to 16f,24f to 6f,24f to 26f))c.drawCircle(v.first,v.second,4f,p)}
            "volume" -> {path(3f,12f,9f,12f,17f,5f,17f,27f,9f,20f,3f,20f,3f,12f);c.drawArc(15f,7f,29f,25f,-65f,130f,false,p)}
            else -> {c.drawRoundRect(3f,4f,29f,28f,3f,3f,p);line(9f,10f,23f,10f);line(9f,17f,23f,17f)}
        }
        p.shader=null;c.restore()
    }
}
