package com.livenexus.controller

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.*
import kotlin.math.min

/** Native, independently measured components; artwork never receives control events. */
internal class NativeTemplate(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    private val root = FrameLayout(activity)
    private var page = "transmission"
    private var wide = false
    private var availableWidth = 0
    private var availableHeight = 0
    private var popup: PopupWindow? = null
    private var menuAnchor: View? = null
    private var resolution = "1080p"
    private var streamOrientation = "Horizontal (16:9)"
    private var fps = "60 FPS"
    private var bitrate = 6000
    private var mic = true
    private var internalAudio = true
    private var micVolume = 100
    private var internalVolume = 100
    private var ttsText = ""
    private var zoom = 66
    private var filter = "Todos"
    private var editorTab = "Cenário / Overlay"
    private var editorMode = "cursor"
    private var editorLocked = false
    private var editorPositionX = .5f
    private var editorPositionY = .5f
    private var editorArtwork: View? = null
    private var expandedPreview: View? = null
    private val scrollPositions = mutableMapOf<String, Int>()
    private var currentScroll: ScrollView? = null

    private fun dp(value: Number) = (value.toFloat() * density + .5f).toInt()
    private fun unavailable() = Toast.makeText(activity,
        "Comando não conectado à função real nesta versão.", Toast.LENGTH_SHORT).show()

    fun show() {
        // A tela inicial sempre abre limpa: o menu rápido só aparece após um toque.
        popup?.dismiss()
        popup = null
        menuAnchor = null
        page = "transmission"
        root.background = TemplateStyle.Backdrop()
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val width = r-l; val height = b-t
            if (width > 0 && height > 0 && (width != availableWidth || height != availableHeight)) {
                rememberScroll()
                availableWidth=width; availableHeight=height
                wide=width>height
                root.post { render() }
            }
        }
        activity.setContentView(root)
    }

    private fun rememberScroll() { currentScroll?.let { scrollPositions[page]=it.scrollY } }
    private fun navigate(destination: String) {
        rememberScroll(); popup?.dismiss(); page=destination; render()
    }
    private fun column() = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; clipChildren=false; clipToPadding=false }
    private fun row() = LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; clipChildren=false; clipToPadding=false }
    private fun label(value: String, size: Float = 13f, bold: Boolean = false) = TextView(activity).apply {
        text=value; setTextColor(TemplateStyle.white); textSize=size
        typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding=false
    }
    private fun add(parent: LinearLayout, child: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) {
        parent.addView(child,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,if(height>=0)dp(height) else height).apply { topMargin=dp(top) })
    }
    private fun weighted(parent: LinearLayout, child: View, weight: Float=1f, height: Int=ViewGroup.LayoutParams.WRAP_CONTENT, gap: Int=3) {
        parent.addView(child,LinearLayout.LayoutParams(0,if(height>=0)dp(height) else height,weight).apply { marginStart=dp(gap);marginEnd=dp(gap) })
    }
    private fun icon(name: String, tint: Int=TemplateStyle.cyan) = TemplateStyle.Icon(activity,name,tint)
    private fun card(radius: Float=12f, prominent: Boolean=true) = column().apply {
        background=TemplateStyle.Panel(density,radius=radius,glow=prominent)
        setPadding(dp(11),dp(9),dp(11),dp(10))
    }
    private fun action(title: String, symbol: String?=null, selected: Boolean=false,
                       stacked: Boolean=false, click: () -> Unit = { unavailable() }): LinearLayout {
        return LinearLayout(activity).apply {
            orientation=if(stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER;minimumHeight=dp(38)
            setPadding(dp(5),dp(5),dp(5),dp(5))
            background=TemplateStyle.Panel(density,selected,8f,false)
            isClickable=true;isFocusable=true;contentDescription=title
            setOnClickListener { click() }
            if(symbol!=null) addView(icon(symbol,if(selected) TemplateStyle.white else TemplateStyle.cyan),
                LinearLayout.LayoutParams(dp(if(stacked)26 else 20),dp(if(stacked)26 else 20)).apply {
                    if(stacked) bottomMargin=dp(4) else marginEnd=dp(5)
                })
            if(title.isNotEmpty()) addView(label(title,if(stacked)10f else 12f,true).apply {
                gravity=Gravity.CENTER;maxLines=2;ellipsize=TextUtils.TruncateAt.END
                setAutoSizeTextTypeUniformWithConfiguration(9,if(stacked)12 else 14,1,TypedValue.COMPLEX_UNIT_SP)
            },LinearLayout.LayoutParams(if(stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    private fun iconAction(symbol:String, description:String, click:()->Unit={unavailable()}): View =
        action("",symbol,click=click).apply { contentDescription=description;minimumWidth=dp(38) }

    private fun render() {
        if(availableWidth==0)return
        popup?.dismiss();popup=null;menuAnchor=null
        currentScroll=null;expandedPreview=null;root.removeAllViews()
        val shell=column().apply { setPadding(dp(if(wide)12 else 8),dp(3),dp(if(wide)12 else 8),dp(8)) }
        root.addView(shell,FrameLayout.LayoutParams(-1,-1))
        add(shell,header(),if(wide)54 else 72)
        if(page=="transmission") {
            shell.addView(home(),LinearLayout.LayoutParams(-1,0,1f))
        } else {
            val scroll=ScrollView(activity).apply {
                isFillViewport=true;isVerticalScrollBarEnabled=false;clipToPadding=false
                overScrollMode=View.OVER_SCROLL_NEVER
            }
            currentScroll=scroll
            val body=when(page) {
                "controls" -> controls()
                "encoder" -> encoder()
                "platforms" -> platforms()
                else -> editor()
            }
            body.setPadding(dp(2),dp(4),dp(2),dp(6))
            scroll.addView(body,FrameLayout.LayoutParams(-1,-2))
            shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
            scroll.post { scroll.scrollTo(0,scrollPositions[page]?:0) }
        }
        if(page!="encoder")add(shell,navigation(),if(wide)48 else 66,6)
        if(page=="transmission" && !wide)add(shell,Space(activity),(availableHeight/density*.14f).toInt())
    }

    private fun header():View = row().apply {
        if(wide && page=="transmission") {
            addView(TemplateStyle.Brand(activity,""),LinearLayout.LayoutParams(dp(140),dp(48)))
            addView(label("│  ▦  Painel de Controle (APK)",12f,true))
            addView(Space(activity),LinearLayout.LayoutParams(0,1,1f))
            addView(icon("gear",TemplateStyle.white),LinearLayout.LayoutParams(dp(23),dp(23)))
        } else {
            val back=page=="encoder" || page=="platforms" || page=="editor"
            addView(iconAction(if(back)"back" else "menu",if(back)"Voltar à transmissão" else "Abrir menu") {
                if(back)navigate("transmission") else showMenu()
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(TemplateStyle.Brand(activity),LinearLayout.LayoutParams(0,dp(if(wide)52 else 62),1f))
            if(wide && page!="transmission") addView(label("Sessão ativa",11f).apply {
                setTextColor(0xff00e8b4.toInt())
                background=TemplateStyle.Panel(density,radius=20f,glow=false)
            }) else addView(Space(activity),LinearLayout.LayoutParams(dp(12),1))
        }
    }

    private fun navigation():View = row().apply {
        val items=listOf(Triple("MENU","menu","menu"),Triple("TRANSMISSÃO","broadcast","transmission"),
            Triple(if(wide)"AJUSTE ENCODER" else "AJUSTE\nENCODER","gear","encoder"),Triple("PLATAFORMAS","layers","platforms"))
        items.forEach { (title,symbol,destination) ->
            val selected=if(destination=="transmission")page!="platforms"&&page!="encoder" else destination==page
            val button=action(title,symbol,selected,stacked=true) {
                if(destination=="menu")showMenu() else navigate(destination)
            }
            weighted(this,button,height=64,gap=0)
            if(destination=="menu")menuAnchor=button
        }
    }

    private fun showMenu() {
        if(popup?.isShowing==true) { popup?.dismiss();popup=null;return }
        val anchor=menuAnchor ?: root
        val panel=card(18f).apply { setPadding(dp(12),dp(8),dp(12),dp(14)) }
        val heading=row()
        weighted(heading,TemplateStyle.Brand(activity,"MENU RÁPIDO"),height=51,gap=0)
        heading.addView(iconAction("close","Fechar menu") { popup?.dismiss() },LinearLayout.LayoutParams(dp(48),dp(48)))
        add(panel,heading)
        add(panel,action("Cenários     ›","layers",true){navigate("editor")},48,4)
        add(panel,action("Controles     ›","game"){navigate("controls")},48,5)
        popup=PopupWindow(panel,min(dp(285),(availableWidth-dp(16)).coerceAtLeast(dp(1))),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));isOutsideTouchable=true;elevation=dp(8).toFloat()
            panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(availableHeight,View.MeasureSpec.AT_MOST))
            val position=IntArray(2);anchor.getLocationOnScreen(position)
            showAtLocation(root,Gravity.TOP or Gravity.START,dp(8),maxOf(dp(56),position[1]-panel.measuredHeight))
        }
    }

    /** Mede a prévia dentro do espaço disponível e preserva 16:9 nos dois eixos. */
    private class SixteenByNine(context: android.content.Context): FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
            val availableWidth=View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            val heightMode=View.MeasureSpec.getMode(heightMeasureSpec)
            val availableHeight=if(heightMode==View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
                                else View.MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            val fittedWidth=if(availableHeight==Int.MAX_VALUE) availableWidth else
                min(availableWidth,(availableHeight.toLong()*16L/9L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val fittedHeight=(fittedWidth.toLong()*9L/16L).toInt()
            super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(fittedWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(fittedHeight,View.MeasureSpec.EXACTLY)
            )
        }
    }

    private fun home(): View = FrameLayout(activity).apply {
        // A miniatura da transmissão jamais vira quadrada em retrato.
        val preview=SixteenByNine(activity).apply {
            addView(if(wide)TemplateStyle.Brand(activity,"CENTRAL DE TRANSMISSÃO") else art("MINIATURA"),FrameLayout.LayoutParams(-1,-1))
        }
        addView(preview,FrameLayout.LayoutParams(if(wide)(availableWidth*.76f).toInt() else -1,if(wide)(availableHeight*.7f).toInt() else -1,Gravity.CENTER))
    }
    private fun art(title:String):View = FrameLayout(activity).apply {
        background=TemplateStyle.Panel(density,radius=24f)
        setPadding(dp(5),dp(5),dp(5),dp(5))
        clipToOutline=true
        outlineProvider=object:android.view.ViewOutlineProvider(){
            override fun getOutline(view:View,outline:android.graphics.Outline){ outline.setRoundRect(0,0,view.width,view.height,dp(24).toFloat()) }
        }
        val inner=FrameLayout(activity).apply { background=TemplateStyle.Backdrop() }
        addView(inner,FrameLayout.LayoutParams(-1,-1))
        val content=column().apply { gravity=Gravity.CENTER }
        inner.addView(content,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        content.addView(icon("play"),LinearLayout.LayoutParams(dp(58),dp(58)))
        content.addView(label(title,if(title=="MINIATURA")30f else 27f,true).apply {
            gravity=Gravity.CENTER;typeface=Typeface.create("sans-serif",if(title=="MINIATURA")Typeface.BOLD else Typeface.BOLD_ITALIC)
        },LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(16) })
        if(title=="LiveNEXUS") editorArtwork=content
    }
    private fun title(title:String,symbol:String):View = row().apply {
        background=TemplateStyle.Panel(density,radius=18f)
        setPadding(dp(8),dp(4),dp(8),dp(4))
        addView(iconAction("back","Voltar à transmissão"){navigate("transmission")},LinearLayout.LayoutParams(dp(48),dp(48)))
        val center=row().apply { gravity=Gravity.CENTER }
        center.addView(icon(symbol),LinearLayout.LayoutParams(dp(28),dp(28)))
        center.addView(label(title,20f,true),LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(9) })
        weighted(this,center)
        addView(Space(activity),LinearLayout.LayoutParams(dp(48),1))
    }
    private fun sectionHeader(parent:LinearLayout,title:String,symbol:String,subtitle:String?=null) {
        val heading=row()
        heading.addView(icon(symbol,TemplateStyle.white),LinearLayout.LayoutParams(dp(27),dp(27)).apply { marginEnd=dp(8) })
        val texts=column();add(texts,label(title,16f,true))
        if(subtitle!=null)add(texts,label(subtitle,10f),top=2)
        weighted(heading,texts,gap=0);add(parent,heading,top=1)
    }

    private fun controls():LinearLayout = column().apply {
        add(this,title("Controles","game"),54,0)
        if(wide) {
            val columns=row().apply { gravity=Gravity.TOP }
            val left=column();add(left,hotkeys());add(left,player(),top=5)
            val right=column();add(right,tts(),top=0);add(right,bots(),top=5)
            weighted(columns,left);weighted(columns,right);add(this,columns,top=5)
        } else {add(this,hotkeys(),top=5);add(this,player(),top=5);add(this,tts(),top=5);add(this,bots(),top=5)}
        add(this,integrations(),top=5)
    }
    private fun hotkeys():View = card().apply {
        sectionHeader(this,"Hotkeys","keyboard","Atalhos de cenas, subcenas e ações rápidas")
        val keys=row();listOf("Cena 1  F1","Cena 2  F2","Subcena  F3").forEachIndexed { i,s -> weighted(keys,action(s,if(i==2)"layers" else "video"),height=48,gap=1) }
        add(this,keys,top=5)
        val commands=row()
        listOf("Iniciar Transmissão" to "play","Parar Transmissão" to "stop","Reiniciar" to "refresh").forEach { (s,i)->weighted(commands,action(s,i),height=48,gap=1) }
        add(this,commands,top=3)
    }
    private fun player():View = card().apply {
        sectionHeader(this,"Player (Mídia)","music","Controle de músicas e mídias")
        val media=row()
        val cover=FrameLayout(activity).apply { background=TemplateStyle.Panel(density,true,8f);addView(icon("music"),FrameLayout.LayoutParams(dp(30),dp(30),Gravity.CENTER)) }
        media.addView(cover,LinearLayout.LayoutParams(dp(50),dp(50)))
        val track=column();add(track,label("Nenhuma mídia carregada",11f,true));add(track,label("--:-- / --:--",10f),top=3)
        val transport=row().apply { gravity=Gravity.CENTER }
        listOf("back" to "Anterior","pause" to "Pausar","next" to "Próxima").forEach { (i,s)->
            transport.addView(iconAction(i,s),LinearLayout.LayoutParams(dp(48),dp(48)).apply {
                marginStart=dp(3);marginEnd=dp(3)
            })
        }
        weighted(media,track,gap=5)
        // Em telas estreitas, a barra de reprodução ocupa uma linha própria.
        // Botões não disputam espaço com o nome da faixa nem são comprimidos.
        val estimatedPanelWidth=if(wide)availableWidth/density/2f else availableWidth/density
        if(estimatedPanelWidth<390f) {
            add(this,media,top=5)
            add(this,transport,48,top=5)
        } else {
            media.addView(transport,LinearLayout.LayoutParams(dp(162),dp(48)))
            add(this,media,top=5)
        }
        add(this,volumeRow("",75){},top=1)
    }
    private fun tts():View = card().apply {
        val heading=row()
        val title=column();sectionHeader(title,"TTS (Voz)","mic","Leitura de mensagens por voz")
        weighted(heading,title,gap=0)
        heading.addView(label("Ativar TTS",9f))
        heading.addView(toggle(false){ unavailable() })
        add(this,heading)
        val input=row()
        val field=EditText(activity).apply {
            setText(ttsText);hint="Digite uma mensagem para o TTS...";textSize=11f;setTextColor(TemplateStyle.white)
            setHintTextColor(TemplateStyle.muted);setSingleLine(true);inputType=InputType.TYPE_CLASS_TEXT
            background=TemplateStyle.Panel(density,radius=7f,glow=false);setPadding(dp(8),0,dp(8),0)
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { ttsText=s?.toString().orEmpty() }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
        }
        weighted(input,field,3f,48,0);weighted(input,action("Ler","play",true),1f,48,0);add(this,input,top=3)
        val duck=row();weighted(duck,label("Ducking (reduzir áudio da mídia)",10f));duck.addView(label("-12 dB",11f,true))
        add(this,duck,27,3)
    }
    private fun toggle(checked:Boolean,changed:(Boolean)->Unit):Switch = Switch(activity).apply {
        isChecked=checked;showText=false;minimumHeight=dp(28)
        thumbTintList=ColorStateList.valueOf(TemplateStyle.white)
        trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(TemplateStyle.cyan,0xff35304b.toInt()))
        setOnCheckedChangeListener { _,value -> changed(value) }
    }
    private fun bots():View=card().apply {
        sectionHeader(this,"Bots (Chat)","bot","Controle dos bots do chat nas plataformas")
        val list=row()
        listOf("Twitch","Kick","YouTube").forEach { name ->
            val item=row().apply { background=TemplateStyle.Panel(density,radius=8f,glow=false);setPadding(dp(5),dp(8),dp(5),dp(8)) }
            item.addView(icon(name.lowercase()),LinearLayout.LayoutParams(dp(19),dp(24)))
            val words=column();add(words,label(name,10f,true));add(words,label("Bot do chat",8f),top=2)
            weighted(item,words,gap=2)
            item.addView(toggle(false){unavailable()}.apply{scaleX=.8f;scaleY=.8f},LinearLayout.LayoutParams(dp(33),dp(28)))
            weighted(list,item,gap=2)
        };add(this,list,top=6)
    }
    private fun integrations():View=card().apply {
        val content=if(wide)row() else column()
        val heading=column();sectionHeader(heading,"Integrações","link","Serviços e integrações conectados")
        val list=column()
        listOf("OBS (WebSocket)","StreamElements","Streamlabs").forEach { name ->
            val item=action("$name     ›","link")
            add(list,item,48,2)
        }
        if(wide){weighted(content,heading,1f);weighted(content,list,2f)}else{add(content,heading);add(content,list,top=5)}
        add(this,content)
    }

    private fun choices(title:String,symbol:String,options:List<String>,value:String,changed:(String)->Unit):View=card(prominent=false).apply {
        val head=row();head.addView(icon(symbol),LinearLayout.LayoutParams(dp(19),dp(19)));weighted(head,label(title,12f,true));add(this,head)
        val optionsRow=row()
        options.forEach { option -> weighted(optionsRow,action(option,selected=option==value) { rememberScroll();changed(option);render() },height=48,gap=1) }
        add(this,optionsRow,top=3)
    }
    private fun volumeRow(name:String,value:Int,changed:(Int)->Unit):View=row().apply {
        if(name.isNotEmpty())addView(label(name,10f),LinearLayout.LayoutParams(dp(68),-2))
        else addView(icon("volume"),LinearLayout.LayoutParams(dp(20),dp(20)))
        val number=label("$value%",10f,true)
        val seek=seek(value,100) { number.text="$it%";changed(it) }
        weighted(this,seek,height=29,gap=0);addView(number,LinearLayout.LayoutParams(dp(36),-2))
    }
    private fun seek(value:Int,maximum:Int,changed:(Int)->Unit):SeekBar=SeekBar(activity).apply {
        max=maximum;progress=value;progressTintList=ColorStateList.valueOf(TemplateStyle.pink)
        thumbTintList=ColorStateList.valueOf(TemplateStyle.white)
        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?,p:Int,fromUser:Boolean) {if(fromUser)changed(p)}
            override fun onStartTrackingTouch(s:SeekBar?) {}
            override fun onStopTrackingTouch(s:SeekBar?) {}
        })
    }
    private fun encoder():LinearLayout=column().apply {
        val content=row().apply { gravity=Gravity.TOP }
        if(wide) {
            val side=card()
            listOf("Ajuste Encoder" to "gear","Vídeo" to "screen","Áudio" to "mic","Avançado" to "gear","Pré-visualização" to "eye").forEachIndexed { i,(s,k)->add(side,action(s,k,i==0),48,3) }
            content.addView(side,LinearLayout.LayoutParams(dp(135),-2))
        }
        val main=card()
        sectionHeader(main,"Ajuste Encoder","gear")
        val res=choices("Resolução","screen",listOf("720p","1080p"),resolution){resolution=it}
        val orient=choices("Orientação","copy",listOf("Horizontal (16:9)","Vertical (9:16)"),streamOrientation){streamOrientation=it}
        val frames=choices("FPS (Taxa de Quadros)","layers",listOf("30 FPS","60 FPS"),fps){fps=it}
        if(wide) {val line=row();weighted(line,res);weighted(line,orient,1.3f);weighted(line,frames);add(main,line,top=5)}
        else {add(main,res,top=5);add(main,orient,top=3);add(main,frames,top=3)}
        val rate=card(prominent=false)
        sectionHeader(rate,"Bitrate de Vídeo","broadcast")
        val rateRow=row();val number=label("$bitrate Kbps",11f,true)
        weighted(rateRow,seek(bitrate-2000,8000){bitrate=it+2000;number.text="$bitrate Kbps"},height=29);rateRow.addView(number)
        add(rate,rateRow);val limits=row();weighted(limits,label("2000 Kbps",9f));limits.addView(label("10000 Kbps",9f));add(rate,limits)
        val codec=card(prominent=false);sectionHeader(codec,"Codec de Vídeo","grid");add(codec,action("H.264     ⌄"),48,3)
        if(wide){val line=row();weighted(line,rate,1.8f);weighted(line,codec);add(main,line,top=4)}else{add(main,rate,top=3);add(main,codec,top=3)}
        val audio=card(prominent=false);sectionHeader(audio,"Áudio","mic")
        val external=row();weighted(external,label("Microfone (Áudio Externo)",10f));external.addView(toggle(mic){mic=it});add(audio,external,28)
        val device=row();weighted(device,label("Áudio Interno (Som do Dispositivo)",10f));device.addView(toggle(internalAudio){internalAudio=it});add(audio,device,28)
        val volume=card(prominent=false);sectionHeader(volume,"Volume","volume")
        add(volume,volumeRow("Microfone:",micVolume){micVolume=it});add(volume,volumeRow("Áudio Interno:",internalVolume){internalVolume=it})
        if(wide){val line=row();weighted(line,audio);weighted(line,volume);add(main,line,top=4)}else{add(main,audio,top=3);add(main,volume,top=3)}
        weighted(content,main,gap=3);add(this,content)
        val footer=row()
        weighted(footer,action("Restaurar Padrão","refresh"){
            resolution="1080p";streamOrientation="Horizontal (16:9)";fps="60 FPS";bitrate=6000;mic=true;internalAudio=true;micVolume=100;internalVolume=100;rememberScroll();render()
        },height=45)
        weighted(footer,action("Salvar Configurações","save",true),1.3f,45)
        weighted(footer,action("Aplicar","check"),height=45)
        add(this,footer,top=7)
    }

    private fun platforms():LinearLayout=column().apply {
        val content=row().apply {gravity=Gravity.TOP}
        if(wide){
            val side=card()
            listOf(Triple("Ajuste Encoder","gear","encoder"),Triple("Plataformas","layers","platforms"),Triple("Transmissão","broadcast","transmission"),Triple("Visualização","screen","transmission"),Triple("Cenário / Overlay","layers","editor")).forEach { (s,i,d)->add(side,action(s,i,d==page){navigate(d)},48,4) }
            content.addView(side,LinearLayout.LayoutParams(dp(135),-2))
        }
        val main=column()
        sectionHeader(main,"Plataformas de Transmissão","layers")
        add(main,label("Conexão via RTMP / RTMPS",10f).apply { gravity=Gravity.CENTER },top=5)
        val list=if(wide)row().apply {gravity=Gravity.TOP} else column()
        listOf("Twitch","YouTube","Kick").forEach { name ->
            if(wide)weighted(list,platform(name)) else add(list,platform(name),top=4)
        }
        add(main,list,top=5);weighted(content,main);add(this,content)
        val start=action("Iniciar Transmissão em Todas","broadcast",true)
        val update=action("Atualizar Conexões","refresh")
        val stop=action("Encerrar Todas","stop")
        val footer=row()
        if(wide){weighted(footer,update);weighted(footer,start,1.6f);weighted(footer,stop)}
        else {add(this,start,40,5);weighted(footer,update);weighted(footer,stop)}
        add(this,footer,43,5)
    }
    private fun platform(name:String):View=card().apply {
        val heading=row();heading.addView(icon(name.lowercase()),LinearLayout.LayoutParams(dp(27),dp(27)));weighted(heading,label(name,17f,true));heading.addView(toggle(false){unavailable()});add(this,heading,30)
        // No stream backend exists in this repository; do not present fabricated connected states or keys.
        add(this,label("Não conectado",9f).apply{setTextColor(TemplateStyle.muted)},top=1)
        add(this,label("URL do Servidor (RTMP / RTMPS)",10f,true),top=6)
        add(this,action("Configuração não disponível","copy"),48,2)
        add(this,label("Stream Key",10f,true),top=5)
        add(this,action("Não configurada","eye"),48,2)
        val buttons=row();weighted(buttons,action("Testar","broadcast",true),height=48,gap=0);weighted(buttons,action("Conectar","link"),height=48,gap=0)
        add(this,buttons,top=5)
    }

    private fun editor():LinearLayout=column().apply {
        val tabs=row()
        listOf("Cenário / Overlay" to "layers","Editor" to "grid","Visualizar" to "eye","Salvar" to "save").forEach { (s,i)->
            weighted(tabs,action(s,i,s==editorTab){if(s=="Salvar")unavailable() else {editorTab=s;rememberScroll();render()}},if(s=="Cenário / Overlay")1.5f else 1f,48,0)
        };add(this,tabs)
        val content=if(wide)row().apply {gravity=Gravity.TOP} else column()
        val canvas=card()
        val frame=editorPreview()
        add(canvas,frame,top=3)
        val tools=row()
        val canvasLabel=if(wide)"Canvas: 1920 × 1080 (16:9)" else "16:9 · 1920×1080"
        tools.addView(action(canvasLabel),LinearLayout.LayoutParams(0,dp(48),1f))
        tools.addView(action("−"){zoom=maxOf(10,zoom-10);rememberScroll();render()},
            LinearLayout.LayoutParams(dp(48),dp(48)))
        tools.addView(label("$zoom%",11f).apply{gravity=Gravity.CENTER},
            LinearLayout.LayoutParams(dp(48),dp(48)))
        tools.addView(action("+"){zoom=minOf(200,zoom+10);rememberScroll();render()},
            LinearLayout.LayoutParams(dp(48),dp(48)))
        tools.addView(iconAction("expand","Tela cheia"){showExpandedPreview()},
            LinearLayout.LayoutParams(dp(48),dp(48)))
        add(canvas,tools,top=6)
        val elements=card();sectionHeader(elements,"Elementos","layers")
        val filters=row();listOf("Todos","Cenários","Overlays").forEach { s-> weighted(filters,action(s,selected=filter==s){filter=s;rememberScroll();render()},height=30,gap=0) };add(elements,filters,top=4)
        val items=listOf("Texto" to "text","Imagem" to "image","Vídeo / Fonte" to "video","Alertas" to "bell","Widgets" to "grid","Mídia Social" to "share","Formas" to "shape","Cenas Salvas" to "folder","Browser Source" to "globe","WebCam" to "video")
        val count=if(wide)2 else if(availableWidth/density<360)4 else 5
        items.chunked(count).forEach { group ->
            val line=row();group.forEach { (s,i)->weighted(line,action(s,i,stacked=true),height=55,gap=0) }
            repeat(count-group.size){weighted(line,Space(activity))};add(elements,line,top=3)
        }
        // 48 dp por controle: rolagem horizontal evita esmagar os ícones.
        val toolbar=row()
        listOf("cursor","move","expand","refresh","copy","layers","lock","trash").forEach { key ->
            val selected=(key==editorMode) || (key=="lock" && editorLocked)
            val description=when(key) {
                "cursor"->"Selecionar"
                "move"->"Mover elemento"
                "expand"->"Ampliar prévia"
                "refresh"->"Restaurar posição"
                "copy"->"Duplicar"
                "layers"->"Camadas"
                "lock"->if(editorLocked)"Destravar elemento" else "Travar elemento"
                else->"Excluir"
            }
            val button=action("",key,selected) {
                when(key) {
                    "cursor","move" -> { editorMode=key;rememberScroll();render() }
                    "lock" -> { editorLocked=!editorLocked;rememberScroll();render() }
                    "refresh" -> { editorPositionX=.5f;editorPositionY=.5f;rememberScroll();render() }
                    "expand" -> showExpandedPreview()
                    else -> unavailable()
                }
            }.apply { contentDescription=description }
            toolbar.addView(button,LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginEnd=dp(4) })
        }
        val toolbarScroll=HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
            addView(toolbar)
        }
        add(elements,toolbarScroll,top=6)
        if(wide){weighted(content,canvas,2.8f);weighted(content,elements,1f)}else{add(content,canvas,top=6);add(content,elements,top=6)}
        add(this,content,top=4)
    }

    private fun positionEditorArtwork(frame:View) {
        val element=editorArtwork ?: return
        val freeX=(frame.width-element.width).coerceAtLeast(0)
        val freeY=(frame.height-element.height).coerceAtLeast(0)
        element.translationX=(editorPositionX-.5f)*freeX
        element.translationY=(editorPositionY-.5f)*freeY
        val zoomScale=zoom/66f
        element.scaleX=zoomScale
        element.scaleY=zoomScale
    }

    private fun editorPreview(): View {
        val frame=SixteenByNine(activity)
        frame.addView(art("LiveNEXUS"),FrameLayout.LayoutParams(-1,-1))
        if(editorTab!="Visualizar") frame.foreground=SelectionBorder(density,editorLocked)
        frame.isClickable=true
        var previousX=0f
        var previousY=0f
        frame.setOnTouchListener { target,event ->
            if(editorLocked || editorMode!="move" || editorTab=="Visualizar") {
                false
            } else {
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        previousX=event.x;previousY=event.y
                        target.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val element=editorArtwork
                        if(element!=null) {
                            val freeX=(frame.width-element.width).coerceAtLeast(1)
                            val freeY=(frame.height-element.height).coerceAtLeast(1)
                            editorPositionX=(editorPositionX+(event.x-previousX)/freeX).coerceIn(0f,1f)
                            editorPositionY=(editorPositionY+(event.y-previousY)/freeY).coerceIn(0f,1f)
                            positionEditorArtwork(frame)
                        }
                        previousX=event.x;previousY=event.y
                        true
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                        target.parent?.requestDisallowInterceptTouchEvent(false)
                        if(event.actionMasked==MotionEvent.ACTION_UP) target.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
        frame.post { positionEditorArtwork(frame) }
        return frame
    }

    private fun showExpandedPreview() {
        if(expandedPreview!=null || availableWidth<=0 || availableHeight<=0)return
        popup?.dismiss()
        val cover=FrameLayout(activity).apply {
            setBackgroundColor(0xee010315.toInt())
            isClickable=true
        }
        val maxWidth=min(availableWidth-dp(24),
            ((availableHeight-dp(100)).coerceAtLeast(dp(90))*16f/9f).toInt()).coerceAtLeast(dp(120))
        val panel=column().apply {
            setPadding(dp(4),dp(4),dp(4),dp(4))
        }
        val preview=SixteenByNine(activity).apply {
            addView(art("MINIATURA"),FrameLayout.LayoutParams(-1,-1))
        }
        add(panel,preview)
        add(panel,action("Voltar","back"){ root.removeView(cover);expandedPreview=null },48,8)
        cover.addView(panel,FrameLayout.LayoutParams(maxWidth,-2,Gravity.CENTER))
        root.addView(cover,FrameLayout.LayoutParams(-1,-1))
        expandedPreview=cover
    }

    private class SelectionBorder(private val density:Float,private val locked:Boolean):android.graphics.drawable.Drawable() {
        private val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(c:android.graphics.Canvas){
            val inset=10*density;val r=android.graphics.RectF(bounds).apply{inset(inset,inset)}
            p.color=if(locked) TemplateStyle.pink else TemplateStyle.cyan
            p.style=android.graphics.Paint.Style.STROKE;p.strokeWidth=density
            c.drawRect(r,p);p.style=android.graphics.Paint.Style.FILL
            for(x in listOf(r.left,r.centerX(),r.right))for(y in listOf(r.top,r.centerY(),r.bottom)){
                if(x==r.centerX()&&y==r.centerY())continue
                c.drawRoundRect(x-3*density,y-3*density,x+3*density,y+3*density,density,density,p)
            }
        }
        override fun setAlpha(alpha:Int){p.alpha=alpha}
        override fun setColorFilter(filter:android.graphics.ColorFilter?){p.colorFilter=filter}
        @Deprecated("Deprecated in Android") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }
}
