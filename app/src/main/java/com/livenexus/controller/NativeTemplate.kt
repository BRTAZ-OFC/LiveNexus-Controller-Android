package com.livenexus.controller

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/** Template gráfico nativo. Sem HTML, CSS, JavaScript ou WebView.
 * Desenhos são referência visual e não substituem as rotinas reais do aplicativo.
 */
internal class NativeTemplate(private val activity: Activity) {
    private val view = NativeScreen(activity)
    fun show() = activity.setContentView(view)

    private data class Target(val box: RectF, val fixed: Boolean, val onClick: () -> Unit)

    private inner class NativeScreen(activity: Activity) : View(activity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val touch = mutableListOf<Target>()
        private val bg = Color.rgb(2, 4, 20)
        private val white = Color.rgb(247, 249, 255)
        private val muted = Color.rgb(186, 198, 230)
        private val pink = Color.rgb(238, 0, 255)
        private val cyan = Color.rgb(0, 234, 255)
        private val violet = Color.rgb(144, 35, 255)
        private var page = "menu"
        private var wide = false
        private var w = 928f
        private var h = 1647f
        private var factor = 1f
        private var scroll = 0f
        private var maxScroll = 0f
        private var downY = 0f
        private var startScroll = 0f
        private var moved = false
        private var resolution = "1080p"
        private var fps = "60 FPS"
        private var orientation = "Horizontal (16:9)"
        private var editorTab = "Cenário / Overlay"
        private var filter = "Todos"
        private var zoom = 66

        private fun unavailable() {
            Toast.makeText(activity, "Comando não conectado à função real nesta versão.", Toast.LENGTH_SHORT).show()
        }
        private fun navigate(to: String) { page = to; scroll = 0f; invalidate() }
        private fun rgb(s: String): Int = Color.parseColor(s)

        private fun rr(c: Canvas, x: Float, y: Float, ww: Float, hh: Float, radius: Float,
                       first: Int, last: Int, border: Boolean = false, thickness: Float = 2f) {
            p.reset(); p.isAntiAlias = true
            p.style = if (border) Paint.Style.STROKE else Paint.Style.FILL
            p.strokeWidth = thickness
            p.shader = LinearGradient(x, y, x + ww, y + hh, first, last, Shader.TileMode.CLAMP)
            c.drawRoundRect(x, y, x + ww, y + hh, radius, radius, p)
            p.shader = null
            p.style = Paint.Style.FILL
        }
        private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float,
                        col: Int = white, bold: Boolean = false, center: Boolean = false) {
            p.reset(); p.isAntiAlias = true; p.color = col; p.textSize = size
            p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            p.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
            c.drawText(s, x, y, p)
        }
        private fun box(c: Canvas, x: Float, y: Float, ww: Float, hh: Float,
                        a: Int = pink, b: Int = cyan, radius: Float = 27f) {
            for (i in 3 downTo 1) {
                p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE
                p.strokeWidth = 5f * i; p.alpha = 20
                p.shader = LinearGradient(x, y, x + ww, y + hh, a, b, Shader.TileMode.CLAMP)
                c.drawRoundRect(x, y, x + ww, y + hh, radius, radius, p)
                p.shader = null
            }
            rr(c, x, y, ww, hh, radius, rgb("#11102D"), rgb("#001627"))
            rr(c, x, y, ww, hh, radius, a, b, true, 2.9f)
        }
        private fun button(c: Canvas, name: String, x: Float, y: Float, ww: Float, hh: Float,
                           active: Boolean = false, accent: Int = cyan, size: Float = 21f,
                           fixed: Boolean = false, click: (() -> Unit)? = null) {
            rr(c, x, y, ww, hh, 15f,
               if(active) rgb("#C700F3") else rgb("#1B174A"),
               if(active) rgb("#540096") else rgb("#001728"))
            rr(c, x, y, ww, hh, 15f, if(active) pink else accent, violet, true, 2.2f)
            txt(c, name, x + ww / 2f, y + hh / 2f + size * .34f, size, white, true, true)
            touch.add(Target(RectF(x, y, x + ww, y + hh), fixed, click ?: { unavailable() }))
        }
        private fun emblem(c: Canvas, x: Float, y: Float, size: Float) {
            val t = Path().apply {
                moveTo(x-size*.30f,y-size*.46f)
                lineTo(x+size*.44f,y-size*.06f)
                quadTo(x+size*.54f,y,x+size*.43f,y+size*.09f)
                lineTo(x-size*.28f,y+size*.47f)
                close()
            }
            p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeJoin=Paint.Join.ROUND
            p.strokeWidth=size*.13f
            p.shader=LinearGradient(x-size*.4f,y-size*.4f,x+size*.5f,y+size*.5f,pink,cyan,Shader.TileMode.CLAMP)
            c.drawPath(t,p);p.shader=null;p.style=Paint.Style.FILL
        }
        private fun logo(c: Canvas, cx: Float, y: Float, size: Float = 62f) {
            val left = cx - size*3.15f
            emblem(c, left, y+size*.45f, size*.79f)
            txt(c, "Live", left+size*.75f, y+size, size, white, true)
            p.reset();p.isAntiAlias=true;p.textSize=size;p.typeface=Typeface.create("sans-serif", Typeface.BOLD)
            val tx=left+size*.75f+p.measureText("Live")
            p.shader=LinearGradient(tx,y,tx+size*2.9f,y+size,pink,cyan,Shader.TileMode.CLAMP)
            c.drawText("NEXUS",tx,y+size,p);p.shader=null
            txt(c,"C O N T R O L E   D E   T R A N S M I S S Ã O",
                cx,y+size+34f,if(wide)14f else 17f,white,false,true)
        }
        private fun swoosh(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, bright: Int) {
            val path=Path().apply {
                moveTo(x,y)
                cubicTo(x+dx*.15f,y+dy*.15f,x+dx*.7f,y+dy*.95f,x+dx,y+dy)
            }
            p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE
            p.color=bright;p.alpha=65;p.strokeWidth=26f;c.drawPath(path,p)
            p.alpha=255;p.strokeWidth=4f;c.drawPath(path,p)
            p.style=Paint.Style.FILL
        }
        private fun background(c: Canvas) {
            rr(c,0f,0f,w,h,0f,rgb("#090016"),rgb("#00152B"))
            swoosh(c,-40f,180f,410f,-280f,pink)
            swoosh(c,w+55f,40f,-460f,300f,cyan)
            swoosh(c,-40f,h-230f,530f,270f,cyan)
            swoosh(c,w+15f,h-220f,-450f,260f,pink)
        }
        private fun header(c: Canvas) {
            logo(c,w*.50f,if(wide)8f else 28f,if(wide)57f else 67f)
            button(c,if(page=="menu")"☰" else "‹",34f,if(wide)33f else 48f,
                if(wide)81f else 99f,if(wide)70f else 97f,
                size=56f,accent=violet,fixed=true) {
                if(page=="menu") unavailable() else navigate("menu")
            }
            if(page!="menu" && page!="dashboard") {
                rr(c,w-230f,55f,210f,51f,26f,rgb("#061729"),rgb("#03162A"))
                rr(c,w-230f,55f,210f,51f,26f,rgb("#00F8A8"),cyan,true,2f)
                txt(c,"● Sessão ativa",w-125f,89f,18f,rgb("#00F6B9"),true,true)
            }
        }
        private fun navigation(c: Canvas) {
            val x=if(wide) 110f else 26f
            val yy=h-if(wide) 112f else 161f
            val ww=(w-2*x)/4f
            val labels=listOf("MENU","TRANSMISSÃO","AJUSTE ENCODER","PLATAFORMAS")
            val icons=listOf("☰","◉","⚙","◇")
            val names=listOf("menu","controls","encoder","platforms")
            for(i in 0..3) {
                val active=page==names[i] || (page=="editor" && i==1)
                val bx=x+i*ww+2f
                box(c,bx,yy,ww-4f,if(wide)85f else 131f,
                    if(active)pink else cyan,if(active)pink else violet,17f)
                if(active)rr(c,bx+3f,yy+3f,ww-10f,if(wide)79f else 125f,15f,rgb("#AF00E5"),rgb("#480079"))
                txt(c,icons[i],bx+(ww-4f)/2f,yy+(if(wide)34f else 64f),
                    if(wide)34f else 47f,if(active)white else cyan,true,true)
                txt(c,labels[i],bx+(ww-4f)/2f,yy+(if(wide)71f else 107f),
                    if(wide)20f else if(i==2)19f else 24f,white,true,true)
                touch.add(Target(RectF(bx,yy,bx+ww-4f,yy+(if(wide)85f else 131f)),true){navigate(names[i])})
            }
        }
        private fun art(c: Canvas,x:Float,y:Float,ww:Float,hh:Float,title:String) {
            box(c,x,y,ww,hh,pink,cyan,42f)
            c.save();c.clipRect(x+3f,y+3f,x+ww-3f,y+hh-3f)
            swoosh(c,x-50f,y+hh*.38f,ww*.48f,-hh*.38f,pink)
            swoosh(c,x+ww*.28f,y+hh*1.04f,ww*.75f,-hh*.39f,cyan)
            emblem(c,x+ww*.5f,y+hh*.42f,min(ww*.18f,hh*.25f))
            txt(c,title,x+ww*.5f,y+hh*.68f,min(70f,ww*.095f),white,true,true)
            c.restore()
        }
        private fun titlebar(c:Canvas,name:String,yy:Float):Float {
            box(c,30f,yy,w-60f,if(wide)74f else 84f)
            txt(c,name,w/2f,yy+(if(wide)51f else 60f),if(wide)39f else 42f,white,true,true)
            return yy+(if(wide)91f else 107f)
        }
        private fun menu(c:Canvas):Float {
            val x=if(wide)110f else 43f
            val y=if(wide)210f else 238f
            val ww=w-2*x
            val hh=if(wide) 410f else 805f
            art(c,x,y,ww,hh,"MINIATURA")
            val qx=if(wide) x+40f else 28f
            val qy=y+hh-(if(wide)190f else 203f)
            val qw=if(wide) 430f else 525f
            val qh=if(wide) 235f else 359f
            box(c,qx,qy,qw,qh)
            logo(c,qx+qw*.52f,qy+14f,if(wide) 28f else 36f)
            txt(c,"M E N U   R Á P I D O",qx+qw/2f,qy+(if(wide)94f else 113f),
                if(wide)14f else 21f,white,false,true)
            val by=qy+(if(wide)106f else 133f)
            button(c,"◇   Cenários    ›",qx+28f,by,qw-56f,if(wide)50f else 88f,
                true,size=if(wide)20f else 32f){navigate("editor")}
            button(c,"♧   Controles    ›",qx+28f,by+(if(wide)62f else 108f),
                qw-56f,if(wide)50f else 88f,accent=cyan,size=if(wide)20f else 32f){navigate("controls")}
            return qy+qh+30f
        }
        private fun previewControls(c:Canvas,x:Float,y:Float,ww:Float) {
            button(c,"Canvas: 1080 × 1920 (9:16)",x,y,ww*.55f,51f,size=20f)
            button(c,"−",x+ww*.57f,y,ww*.1f,51f,size=33f){
                zoom=max(40,zoom-10);invalidate()
            }
            button(c,"$zoom%",x+ww*.69f,y,ww*.13f,51f,size=23f)
            button(c,"+",x+ww*.83f,y,ww*.08f,51f,size=33f){
                zoom=min(150,zoom+10);invalidate()
            }
            button(c,"⛶",x+ww*.92f,y,ww*.08f,51f,size=29f)
        }
        private fun editorTools(c:Canvas,x:Float,y:Float,ww:Float,land:Boolean) {
            val labels=listOf("Texto","Imagem","Vídeo / Fonte","Alertas","Widgets",
                "Mídia Social","Formas","Cenas Salvas","Browser Source","WebCam")
            val f=listOf("Todos","Cenários","Overlays")
            f.forEachIndexed{i,s->button(c,s,x+i*ww/3f,y,ww/3f-5f,if(land)40f else 55f,
                s==filter,size=if(land)17f else 22f){filter=s;invalidate()}}
            val by=y+(if(land)51f else 73f)
            val ch=if(land)67f else 107f
            labels.forEachIndexed{i,s->
                val cx=x+(i%5)*ww/5f
                val cy=by+(i/5)*(ch+10f)
                button(c,s,cx,cy,ww/5f-7f,ch,size=if(land)13f else 18f)
            }
            val ty=by+2*(ch+10f)+11f
            listOf("↖","✥","⛶","↻","▣","▱","♙","▤").forEachIndexed{i,s->
                button(c,s,x+i*ww/8f,ty,ww/8f-6f,if(land)42f else 60f,i==0,
                    size=if(land)23f else 31f)
            }
        }
        private fun editor(c:Canvas):Float {
            val y=if(wide)180f else 183f
            val tabs=listOf("Cenário / Overlay","Editor","Visualizar","Salvar")
            val total=w-48f
            val widths=if(wide)listOf(.27f,.24f,.24f,.25f) else listOf(.34f,.20f,.22f,.24f)
            var tx=24f
            tabs.forEachIndexed{i,s->
                val ww=total*widths[i]-5f
                button(c,s,tx,y,ww,if(wide)59f else 76f,
                    s==editorTab,size=if(wide)24f else 22f){
                    editorTab=s;invalidate()
                    if(s=="Salvar")unavailable()
                }
                tx+=total*widths[i]
            }
            val cy=y+(if(wide)77f else 99f)
            if(wide) {
                box(c,26f,cy,1018f,500f)
                art(c,57f,cy+37f,952f,380f,"LiveNEXUS")
                rr(c,82f,cy+64f,902f,328f,0f,cyan,cyan,true,3f)
                previewControls(c,59f,cy+433f,947f)
                box(c,1060f,cy,510f,500f)
                txt(c,"◇  Elementos",1082f,cy+47f,31f,white,true)
                editorTools(c,1076f,cy+66f,480f,true)
                return cy+529f
            }
            box(c,23f,cy,882f,604f)
            art(c,55f,cy+42f,817f,466f,"LiveNEXUS")
            rr(c,82f,cy+69f,763f,413f,0f,cyan,cyan,true,3f)
            previewControls(c,55f,cy+526f,817f)
            val ey=cy+628f
            box(c,24f,ey,880f,449f)
            txt(c,"◇  Elementos",52f,ey+51f,37f,white,true)
            editorTools(c,50f,ey+70f,832f,false)
            return ey+475f
        }
        private fun group(c:Canvas,x:Float,y:Float,ww:Float,hh:Float,title:String,
                          opts:List<String> = emptyList(),active:String="",
                          pick:((String)->Unit)?=null) {
            rr(c,x,y,ww,hh,19f,rgb("#121C3A"),rgb("#060A25"))
            rr(c,x,y,ww,hh,19f,violet,cyan,true,1.7f)
            txt(c,title,x+24f,y+39f,if(wide)21f else 25f,white,true)
            if(opts.isNotEmpty()){
                val iw=(ww-52f-(opts.size-1)*7f)/opts.size
                opts.forEachIndexed{i,s->
                    button(c,s,x+26f+i*(iw+7f),y+53f,iw,hh-64f,
                        active==s,size=if(wide)17f else 21f){
                        if(pick==null) unavailable() else { pick(s);invalidate() }
                    }
                }
            }
        }
        private fun slider(c:Canvas,x:Float,y:Float,ww:Float,label:String) {
            rr(c,x,y,ww*.75f,11f,5f,pink,cyan)
            rr(c,x+ww*.75f,y,ww*.25f,11f,5f,rgb("#35466A"),rgb("#35466A"))
            rr(c,x+ww*.75f-12f,y-11f,27f,27f,13f,white,white)
            txt(c,label,x+ww-5f,y-14f,19f,white,true)
        }
        private fun encoder(c:Canvas):Float {
            val y=180f
            if(wide) {
                box(c,26f,y,324f,554f)
                listOf("Ajuste Encoder","Vídeo","Áudio","Avançado","Pré-visualização").forEachIndexed{i,s->
                    button(c,s,44f,y+63f+i*91f,288f,73f,i==0,size=23f)
                }
                box(c,366f,y,1206f,554f)
                txt(c,"⚙  Ajuste Encoder",396f,y+54f,35f,white,true)
                val sy=y+83f
                group(c,389f,sy,370f,126f,"Resolução",listOf("720p","1080p"),resolution){resolution=it}
                group(c,774f,sy,370f,126f,"Orientação",
                    listOf("Horizontal (16:9)","Vertical (9:16)"),orientation){orientation=it}
                group(c,1159f,sy,390f,126f,"FPS (Taxa de Quadros)",listOf("30 FPS","60 FPS"),fps){fps=it}
                group(c,389f,sy+140f,750f,135f,"Bitrate de Vídeo")
                slider(c,420f,sy+216f,680f,"6000 Kbps")
                group(c,1152f,sy+140f,397f,135f,"Codec de Vídeo")
                button(c,"H.264  ⌄",1177f,sy+196f,348f,57f)
                group(c,389f,sy+288f,550f,150f,"Áudio")
                txt(c,"Microfone (Áudio Externo)      ●",415f,sy+359f,22f)
                txt(c,"Áudio Interno                          ●",415f,sy+406f,22f)
                group(c,955f,sy+288f,594f,150f,"Volume")
                slider(c,980f,sy+356f,538f,"100%")
                slider(c,980f,sy+410f,538f,"100%")
                button(c,"RESTAURAR PADRÃO",136f,y+582f,355f,67f)
                button(c,"SALVAR CONFIGURAÇÕES",545f,y+582f,510f,67f,true)
                button(c,"APLICAR",1110f,y+582f,330f,67f)
                return y+680f
            }
            var yy=titlebar(c,"⚙  Ajuste Encoder",y)
            group(c,35f,yy,858f,130f,"Resolução",listOf("720p","1080p"),resolution){resolution=it};yy+=144f
            group(c,35f,yy,858f,130f,"Orientação",
                listOf("Horizontal (16:9)","Vertical (9:16)"),orientation){orientation=it};yy+=144f
            group(c,35f,yy,858f,130f,"FPS (Taxa de Quadros)",
                listOf("30 FPS","60 FPS"),fps){fps=it};yy+=144f
            group(c,35f,yy,858f,139f,"Bitrate de Vídeo");slider(c,68f,yy+94f,783f,"6000 Kbps");yy+=153f
            group(c,35f,yy,858f,126f,"Codec de Vídeo")
            button(c,"H.264   ⌄",68f,yy+59f,788f,52f,size=22f);yy+=141f
            group(c,35f,yy,858f,162f,"Áudio")
            txt(c,"Microfone (Áudio Externo)                          ●",65f,yy+99f,23f)
            txt(c,"Áudio Interno (Som do dispositivo)           ●",65f,yy+140f,23f);yy+=178f
            group(c,35f,yy,858f,172f,"Volume")
            slider(c,66f,yy+102f,786f,"100%")
            slider(c,66f,yy+143f,786f,"100%");yy+=191f
            button(c,"RESTAURAR",35f,yy,264f,68f)
            button(c,"SALVAR CONFIGURAÇÕES",310f,yy,346f,68f,true)
            button(c,"APLICAR",667f,yy,224f,68f)
            return yy+94f
        }
        private fun hotkeys(c:Canvas,x:Float,y:Float,ww:Float,hh:Float) {
            box(c,x,y,ww,hh)
            txt(c,"⌨  Hotkeys",x+25f,y+50f,34f,white,true)
            txt(c,"Atalhos de cenas, subcenas e ações rápidas",x+25f,y+80f,20f,muted)
            val names=listOf("Cena 1   F1","Cena 2   F2","Subcena   F3")
            names.forEachIndexed{i,s->
                button(c,s,x+25f+i*(ww-37f)/3f,y+98f,(ww-65f)/3f,55f,accent=pink,
                    size=if(wide)18f else 20f)
            }
            listOf("▷ Iniciar Transmissão","■ Parar Transmissão","↻ Reiniciar").forEachIndexed{i,s->
                button(c,s,x+25f+i*(ww-37f)/3f,y+166f,(ww-65f)/3f,53f,
                    accent=if(i==1)pink else cyan,size=if(wide)17f else 18f)
            }
        }
        private fun player(c:Canvas,x:Float,y:Float,ww:Float,hh:Float) {
            box(c,x,y,ww,hh)
            txt(c,"♫  Player (Mídia)",x+24f,y+46f,34f,white,true)
            txt(c,"Controle de músicas e mídias",x+24f,y+76f,20f,muted)
            rr(c,x+27f,y+103f,114f,104f,15f,violet,rgb("#031531"))
            txt(c,"♫",x+84f,y+181f,60f,pink,true,true)
            txt(c,"Nenhuma mídia carregada",x+164f,y+137f,21f,white,true)
            txt(c,"--:-- / --:--",x+164f,y+166f,20f,muted)
            button(c,"◀",x+ww-247f,y+107f,64f,65f,size=25f)
            button(c,"Ⅱ",x+ww-172f,y+107f,64f,65f,true,size=26f)
            button(c,"▶",x+ww-97f,y+107f,64f,65f,size=26f)
            slider(c,x+164f,y+195f,ww-211f,"75%")
        }
        private fun tts(c:Canvas,x:Float,y:Float,ww:Float,hh:Float) {
            box(c,x,y,ww,hh)
            txt(c,"♩  TTS (Voz)",x+24f,y+46f,34f,white,true)
            txt(c,"Leitura de mensagens por voz",x+24f,y+77f,20f,muted)
            txt(c,"Ativar TTS",x+ww-165f,y+53f,18f)
            button(c,"○",x+ww-65f,y+23f,44f,47f,accent=cyan,size=28f)
            button(c,"Digite uma mensagem para o TTS...",x+24f,y+101f,ww-225f,58f,size=17f)
            button(c,"▷ Ler",x+ww-185f,y+101f,158f,58f,true,size=22f)
            txt(c,"Ducking (reduzir áudio da mídia)",x+26f,y+201f,20f,muted)
            button(c,"-12 dB",x+ww-154f,y+171f,126f,50f,accent=cyan)
        }
        private fun bots(c:Canvas,x:Float,y:Float,ww:Float,hh:Float) {
            box(c,x,y,ww,hh)
            txt(c,"♙  Bots (Chat)",x+24f,y+48f,34f,white,true)
            txt(c,"Controle dos bots do chat nas plataformas",x+24f,y+80f,20f,muted)
            listOf("Twitch","Kick","YouTube").forEachIndexed{i,s->
                button(c,s+"   ○",x+23f+i*(ww-40f)/3f,y+111f,(ww-70f)/3f,68f,
                    accent=if(i==1)cyan else pink,size=20f)
            }
        }
        private fun integrations(c:Canvas,x:Float,y:Float,ww:Float,hh:Float) {
            box(c,x,y,ww,hh)
            txt(c,"♧  Integrações",x+24f,y+47f,34f,white,true)
            txt(c,"Serviços e integrações conectados",x+24f,y+82f,20f,muted)
            listOf("OBS (WebSocket)","StreamElements","Streamlabs").forEachIndexed{i,s->
                val left=if(wide)x+460f else x+25f
                val top=if(wide)y+12f+i*46f else y+102f+i*50f
                button(c,s+"   ·   Não verificado",left,top,
                    if(wide)ww-495f else ww-50f,if(wide)41f else 43f,
                    accent=cyan,size=if(wide)18f else 21f)
            }
        }
        private fun controls(c:Canvas):Float {
            val yy=titlebar(c,"♧   Controles",180f)
            if(wide){
                hotkeys(c,30f,yy,760f,232f)
                tts(c,810f,yy,760f,232f)
                player(c,30f,yy+249f,760f,230f)
                bots(c,810f,yy+249f,760f,230f)
                integrations(c,30f,yy+497f,1540f,168f)
                return yy+690f
            }
            hotkeys(c,35f,yy,859f,236f)
            player(c,35f,yy+255f,859f,234f)
            tts(c,35f,yy+509f,859f,230f)
            bots(c,35f,yy+758f,859f,205f)
            integrations(c,35f,yy+982f,859f,271f)
            return yy+1280f
        }
        private fun platform(c:Canvas,name:String,x:Float,y:Float,ww:Float,hh:Float,index:Int) {
            box(c,x,y,ww,hh,if(index==2)cyan else pink,if(index==2)cyan else violet)
            txt(c,name,x+24f,y+48f,32f,white,true)
            rr(c,x+ww-198f,y+17f,174f,42f,22f,rgb("#06E5A7"),cyan,true)
            txt(c,"Não verificado",x+ww-112f,y+45f,16f,rgb("#00F5AB"),true,true)
            txt(c,"URL do Servidor (RTMP/RTMPS)",x+23f,y+96f,19f,white,true)
            rr(c,x+24f,y+105f,ww-48f,56f,10f,rgb("#192142"),rgb("#051127"))
            txt(c,"Configuração não exibida",x+41f,y+143f,19f,muted)
            txt(c,"Stream Key",x+23f,y+196f,19f,white,true)
            rr(c,x+24f,y+208f,ww-48f,55f,10f,rgb("#192142"),rgb("#051127"))
            txt(c,"••••••••••••••••",x+41f,y+247f,23f,white)
            button(c,"TESTAR",x+22f,y+hh-79f,(ww-60f)/2f,58f,true,size=20f)
            button(c,"CONECTAR",x+ww/2f+8f,y+hh-79f,(ww-60f)/2f,58f,size=20f)
        }
        private fun platforms(c:Canvas):Float {
            val yy=titlebar(c,"◇  Plataformas de Transmissão",180f)
            if(wide){
                listOf("Twitch","YouTube","Kick").forEachIndexed{i,s->
                    platform(c,s,32f+i*520f,yy,502f,401f,i)
                }
                button(c,"ATUALIZAR CONEXÕES",74f,yy+432f,363f,71f)
                button(c,"INICIAR TRANSMISSÃO EM TODAS",471f,yy+432f,660f,71f,true)
                button(c,"ENCERRAR TODAS",1162f,yy+432f,360f,71f)
                return yy+530f
            }
            listOf("Twitch","YouTube","Kick").forEachIndexed{i,s->
                platform(c,s,40f,yy+i*338f,849f,322f,i)
            }
            val by=yy+1015f
            button(c,"INICIAR TRANSMISSÃO EM TODAS",50f,by,828f,67f,true)
            button(c,"ATUALIZAR CONEXÕES",50f,by+85f,402f,63f)
            button(c,"ENCERRAR TODAS",465f,by+85f,413f,63f)
            return by+175f
        }
        private fun dashboard(c:Canvas):Float {
            val yy=198f
            if(wide) {
                box(c,28f,yy,748f,507f)
                box(c,824f,yy,748f,507f)
                art(c,54f,yy+70f,696f,347f,"LIVE OVERLAY")
                art(c,850f,yy+70f,696f,347f,"CENTRAL DE TRANSMISSÃO")
                button(c,"EDITOR DE CENÁRIO / OVERLAY",95f,yy+433f,590f,59f,true){
                    navigate("editor")
                }
                button(c,"TELA DE CONTROLE (APK)",910f,yy+433f,590f,59f,accent=cyan){
                    navigate("controls")
                }
                return yy+531f
            }
            box(c,24f,yy,880f,428f)
            art(c,47f,yy+29f,833f,326f,"LIVE OVERLAY")
            button(c,"ABRIR EDITOR",65f,yy+368f,795f,50f,true){navigate("editor")}
            val y2=yy+453f
            box(c,24f,y2,880f,428f)
            art(c,47f,y2+29f,833f,326f,"CENTRAL DE TRANSMISSÃO")
            button(c,"ABRIR CONTROLES",65f,y2+368f,795f,50f,accent=cyan){
                navigate("controls")
            }
            return y2+456f
        }
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            wide=width>height
            w=if(wide)1600f else 928f
            factor=width/w
            h=height/factor
            touch.clear()
            c.save();c.scale(factor,factor)
            background(c)
            header(c)
            val start=if(wide)173f else 174f
            val end=h-(if(wide)127f else 175f)
            c.save()
            c.clipRect(0f,start,w,end)
            c.translate(0f,-scroll)
            val contentEnd=when(page) {
                "menu"->menu(c)
                "editor"->editor(c)
                "encoder"->encoder(c)
                "controls"->controls(c)
                "platforms"->platforms(c)
                else->dashboard(c)
            }
            maxScroll=max(0f,contentEnd-end+25f)
            c.restore()
            navigation(c)
            c.restore()
        }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            val x=e.x/factor
            val y=e.y/factor
            val start=if(wide)173f else 174f
            val end=h-(if(wide)127f else 175f)
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN -> {
                    downY=y;startScroll=scroll;moved=false;return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if(downY in start..end && kotlin.math.abs(y-downY)>10f) {
                        moved=true
                        scroll=(startScroll+downY-y).coerceIn(0f,maxScroll)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if(!moved){
                        val isBody=y in start..end
                        val match=touch.asReversed().firstOrNull { t ->
                            t.fixed != isBody && t.box.contains(x,if(t.fixed)y else y+scroll)
                        }
                        match?.onClick?.invoke()
                        performClick()
                    }
                    return true
                }
            }
            return true
        }
        override fun performClick():Boolean {super.performClick();return true}
    }
}
