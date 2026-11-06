package com.livenexus.controller

import android.app.Activity
import android.content.pm.ActivityInfo
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
    private var addedSceneCount = 0
    private var playerVolume = 75
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
        rememberScroll(); popup?.dismiss(); page=destination; activity.requestedOrientation=if(destination=="editor") ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; render()
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
        add(shell,if(page=="editor") editorHeader() else header(),if(wide)54 else 72)
        if(page=="editor") {
            shell.addView(editor(),LinearLayout.LayoutParams(-1,0,1f))
        } else if(page=="controls") {
            shell.addView(controls(),LinearLayout.LayoutParams(-1,0,1f))
        } else if(page=="transmission") {
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
        if(page!="encoder" && page!="editor" && page!="controls" && !(page=="platforms" && wide))add(shell,navigation(),if(wide)48 else 66,6)
        if(page=="transmission" && !wide)add(shell,Space(activity),(availableHeight/density*.14f).toInt())
    }

    private fun editorHeader():View = row().apply {
        addView(TemplateStyle.Brand(activity,""),LinearLayout.LayoutParams(dp(135),dp(46)))
        addView(label("│  Editor de Cenário / Overlay",12f,true),LinearLayout.LayoutParams(0,-2,1f))
        addView(iconAction("back","Desfazer"),LinearLayout.LayoutParams(dp(38),dp(38)))
        addView(iconAction("refresh","Refazer"),LinearLayout.LayoutParams(dp(38),dp(38)))
        addView(action("Visualizar","eye") { showExpandedPreview() },LinearLayout.LayoutParams(dp(102),dp(40)))
        addView(action("Salvar","save",true),LinearLayout.LayoutParams(dp(88),dp(40)))
    }

    private fun header():View = row().apply {
        if(wide && page=="transmission") {
            addView(TemplateStyle.Brand(activity,""),LinearLayout.LayoutParams(dp(140),dp(48)))
            addView(label("│  ▦  Painel de Controle (APK)",12f,true))
            addView(Space(activity),LinearLayout.LayoutParams(0,1,1f))
            addView(icon("gear",TemplateStyle.white),LinearLayout.LayoutParams(dp(23),dp(23)))
        } else {
            val back=page=="encoder" || page=="platforms" || page=="editor" || page=="controls"
            addView(iconAction(if(back)"back" else "menu",if(back)"Voltar à transmissão" else "Abrir menu") {
                if(back)navigate("transmission") else showMenu()
            },LinearLayout.LayoutParams(dp(48),dp(48)))
            addView(TemplateStyle.Brand(activity),LinearLayout.LayoutParams(0,dp(if(wide)52 else 62),1f))
            if(wide && page!="transmission" && page!="controls") addView(label("Sessão ativa",11f).apply {
                setTextColor(0xff00e8b4.toInt())
                background=TemplateStyle.Panel(density,radius=20f,glow=false)
            }) else addView(Space(activity),LinearLayout.LayoutParams(dp(12),1))
        }
    }

    private fun navigation():View = row().apply {
        val items=listOf(Triple("MENU","menu","menu"),Triple("TRANSMISSÃO","broadcast","transmission"),
            Triple("CONFIGURAÇÃO","gear","encoder"),Triple("PLATAFORMAS","layers","platforms"))
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
        val content=if(wide) controlsWide() else controlsPortrait()
        addView(content,LinearLayout.LayoutParams(-1,-1))
    }

    private fun controlsWide():View = column().apply {
        val heading=row().apply {
            gravity=Gravity.CENTER
            background=TemplateStyle.Panel(density,radius=6f)
        }
        heading.addView(icon("game"),LinearLayout.LayoutParams(dp(30),dp(30)))
        heading.addView(label("Controles",21f,true),
            LinearLayout.LayoutParams(-2,-2).apply {marginStart=dp(9)})
        add(this,heading,48)

        val hotkeys=card(radius=6f).apply {
            setPadding(dp(9),dp(6),dp(9),dp(7))
        }
        sectionHeader(hotkeys,"Hotkeys","keyboard","Atalhos de cenas, subcenas e ações rápidas")
        val buttons=mutableListOf<View>()
        buttons.add(portraitSceneButton("refresh","Reload",true) {unavailable()})
        buttons.add(portraitSceneButton("plus","Adicionar\ncenário",false) {
            addedSceneCount++
            render()
        })
        for(index in 1..addedSceneCount) {
            buttons.add(portraitSceneButton("video","Cena $index",false) {unavailable()})
        }
        val buttonList=column()
        val columns=((availableWidth/density-42f)/90f).toInt().coerceAtLeast(2)
        buttons.chunked(columns).forEach { group ->
            val line=row().apply {gravity=Gravity.START}
            group.forEach { button ->
                line.addView(button,LinearLayout.LayoutParams(dp(82),dp(72)).apply {
                    marginEnd=dp(7)
                })
            }
            add(buttonList,line,72,top=5)
        }
        val scroller=ScrollView(activity).apply {
            isVerticalScrollBarEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            addView(buttonList)
        }
        hotkeys.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        addView(hotkeys,LinearLayout.LayoutParams(-1,0,1f).apply {
            topMargin=dp(6)
        })

        val player=card(radius=6f).apply {
            setPadding(dp(8),dp(5),dp(8),dp(6))
        }
        val bar=row()
        bar.addView(icon("music",TemplateStyle.white),
            LinearLayout.LayoutParams(dp(27),dp(27)))
        bar.addView(label("Player (Mídia)",14f,true),
            LinearLayout.LayoutParams(0,-2,.8f).apply {marginStart=dp(6)})
        listOf("back" to "Anterior","pause" to "Pausar","next" to "Próxima")
            .forEach { (symbol,name) ->
                bar.addView(iconAction(symbol,name),
                    LinearLayout.LayoutParams(dp(42),dp(42)).apply {marginStart=dp(3)})
            }
        bar.addView(icon("volume",TemplateStyle.white),
            LinearLayout.LayoutParams(dp(21),dp(21)).apply {marginStart=dp(12)})
        val valueLabel=label("$playerVolume%",12f,true).apply {gravity=Gravity.CENTER}
        weighted(bar,seek(playerVolume,100) {
            playerVolume=it
            valueLabel.text="$it%"
        },1f,38,2)
        bar.addView(valueLabel,LinearLayout.LayoutParams(dp(44),-2))
        add(player,bar,45)
        add(this,player,68,top=7)
    }

    private fun controlsPortrait():View = column().apply {
        val heading=row().apply {
            gravity=Gravity.CENTER
            background=TemplateStyle.Panel(density,radius=6f)
        }
        heading.addView(icon("game"),LinearLayout.LayoutParams(dp(31),dp(31)))
        heading.addView(label("Controles",21f,true),
            LinearLayout.LayoutParams(-2,-2).apply {marginStart=dp(9)})
        add(this,heading,53)

        val keys=card(radius=6f)
        sectionHeader(keys,"Hotkeys","keyboard","Atalhos de cenas, subcenas e ações rápidas")
        val buttonList=column()
        val buttons=mutableListOf<View>()
        buttons.add(portraitSceneButton("refresh","Reload",true) {unavailable()})
        buttons.add(portraitSceneButton("plus","Adicionar\ncenário",false) {
            addedSceneCount++
            render()
        })
        for(index in 1..addedSceneCount) {
            buttons.add(portraitSceneButton("video","Cena $index",false) {unavailable()})
        }
        val columns=if(availableWidth/density<305f) 2 else 3
        buttons.chunked(columns).forEach { group ->
            val line=row().apply {gravity=Gravity.START}
            group.forEach { button ->
                line.addView(button,LinearLayout.LayoutParams(dp(80),dp(76)).apply {
                    marginEnd=dp(7)
                })
            }
            add(buttonList,line,76,top=6)
        }
        val scroll=ScrollView(activity).apply {
            isVerticalScrollBarEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            addView(buttonList)
        }
        keys.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        addView(keys,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=dp(7)})

        val player=card(radius=6f).apply {
            setPadding(dp(7),dp(5),dp(7),dp(6))
        }
        val playerTitle=row()
        playerTitle.addView(icon("music",TemplateStyle.white),
            LinearLayout.LayoutParams(dp(24),dp(24)))
        playerTitle.addView(label("Player (Mídia)",15f,true),
            LinearLayout.LayoutParams(-2,-2).apply {marginStart=dp(7)})
        add(player,playerTitle,26)
        val playback=row()
        playback.addView(icon("volume",TemplateStyle.white),
            LinearLayout.LayoutParams(dp(19),dp(19)))
        val valueLabel=label("$playerVolume%",11f,true).apply {gravity=Gravity.CENTER}
        val volume=seek(playerVolume,100) {
            playerVolume=it
            valueLabel.text="$it%"
        }
        weighted(playback,volume,1f,36,0)
        playback.addView(valueLabel,LinearLayout.LayoutParams(dp(39),-2))
        listOf("back" to "Anterior","pause" to "Pausar","next" to "Próxima")
            .forEach { (symbol,name) ->
                playback.addView(iconAction(symbol,name),
                    LinearLayout.LayoutParams(dp(37),dp(38)).apply {marginStart=dp(2)})
            }
        add(player,playback,41,top=2)
        add(this,player,83,top=8)
    }

    private fun portraitSceneButton(symbol:String,title:String,selected:Boolean,click:()->Unit):View =
        column().apply {
            gravity=Gravity.CENTER
            background=TemplateStyle.Panel(density,selected,radius=6f)
            isClickable=true
            isFocusable=true
            contentDescription=title.replace("\n"," ")
            setOnClickListener {click()}
            if(symbol=="plus") {
                addView(label("+",29f,true).apply {gravity=Gravity.CENTER},
                    LinearLayout.LayoutParams(-1,dp(35)))
            } else {
                addView(icon(symbol),LinearLayout.LayoutParams(dp(29),dp(29)))
            }
            addView(label(title,11f,true).apply {
                gravity=Gravity.CENTER
                maxLines=2
            },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(3)})
        }

    private fun hotkeys():View = card().apply {
        sectionHeader(this,"Hotkeys","keyboard","Atalhos de cenas, subcenas e ações rápidas")
        val keys=row();listOf("Cena 1  F1","Cena 2  F2","Subcena  F3").forEachIndexed { i,s -> weighted(keys,action(s,if(i==2)"layers" else "video"),height=48,gap=1) }
        add(this,keys,top=5)
        val commands=row()
        listOf("Iniciar Transmissão" to "play","Parar Transmissão" to "stop","Reiniciar" to "refresh").forEach { (s,i)->weighted(commands,action(s,i),height=48,gap=1) }
        add(this,commands,top=3)
    }
    private fun player():View = card(radius=5f).apply {
        sectionHeader(this,"Player (Mídia)","music","Controle de músicas e mídias")
        val media=row()
        val cover=FrameLayout(activity).apply {
            background=TemplateStyle.Panel(density,true,5f)
            addView(icon("music"),FrameLayout.LayoutParams(dp(31),dp(31),Gravity.CENTER))
        }
        media.addView(cover,LinearLayout.LayoutParams(dp(55),dp(55)))
        val information=column()
        add(information,label("Nenhuma mídia carregada",12f,true))
        add(information,label("--:-- / --:--",10f),top=4)
        weighted(media,information,1f,gap=6)
        val transport=row().apply { gravity=Gravity.CENTER }
        listOf("back" to "Anterior","pause" to "Pausar","next" to "Próxima").forEach { (symbol,name) ->
            transport.addView(iconAction(symbol,name),
                LinearLayout.LayoutParams(dp(48),dp(48)).apply {
                    marginStart=dp(3);marginEnd=dp(3)
                })
        }
        // Mantém o tamanho dos controles, sem comprimir a capa ou o texto.
        val usableWidth=availableWidth/density-40f
        if(usableWidth>=445f) {
            media.addView(transport,LinearLayout.LayoutParams(dp(162),dp(48)))
            add(this,media,top=6)
        } else {
            add(this,media,top=6)
            add(this,transport,48,top=5)
        }
        add(this,volumeRow("",75){},top=5)
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
        // Configurações: tela própria, sem navegação inferior. Voltar fica no cabeçalho.
        val content=row().apply { gravity=Gravity.TOP }
        if(wide) {
            val side=card(radius=5f)
            listOf("Configurações" to "gear","Vídeo" to "screen","Áudio" to "mic",
                "Avançado" to "gear","Pré-visualização" to "eye").forEachIndexed { index,item ->
                add(side,action(item.first,item.second,index==0),45,3)
            }
            content.addView(side,LinearLayout.LayoutParams(dp(165),-2))
        }
        val main=card(radius=5f)
        val heading=row()
        heading.addView(icon("gear",TemplateStyle.white),LinearLayout.LayoutParams(dp(30),dp(30)))
        weighted(heading,label("Configurações",20f,true),gap=6)
        heading.addView(action("Configuração de\nVídeo e Áudio","video"),
            LinearLayout.LayoutParams(dp(145),dp(48)))
        add(main,heading,52)

        val res=choices("Resolução","screen",listOf("720p","1080p"),resolution){resolution=it}
        if(!wide) add(main,res,top=5)
        val orient=choices("Orientação","copy",listOf("Horizontal (16:9)","Vertical (9:16)"),streamOrientation){streamOrientation=it}
        if(!wide) add(main,orient,top=4)
        val frames=choices("FPS (Taxa de Quadros)","layers",listOf("30 FPS","60 FPS"),fps){fps=it}
        if(wide) {
            val top=row().apply {gravity=Gravity.TOP}
            weighted(top,res,1f,gap=1)
            weighted(top,orient,1.2f,gap=1)
            weighted(top,frames,1f,gap=1)
            add(main,top,top=4)
        } else add(main,frames,top=4)

        val rate=card(radius=5f,prominent=false)
        sectionHeader(rate,"Bitrate de Vídeo","broadcast")
        val rateRow=row()
        val bitrateLabel=label("$bitrate Kbps",12f,true)
        weighted(rateRow,seek(bitrate-2000,8000){
            bitrate=it+2000
            bitrateLabel.text="$bitrate Kbps"
        },height=33)
        rateRow.addView(bitrateLabel,LinearLayout.LayoutParams(dp(85),-2))
        add(rate,rateRow,top=3)
        val limits=row()
        weighted(limits,label("2000 Kbps",10f))
        limits.addView(label("10000 Kbps",10f))
        add(rate,limits)
        if(!wide) add(main,rate,top=4)

        val codec=card(radius=5f,prominent=false)
        sectionHeader(codec,"Codec de Vídeo","grid")
        add(codec,action("H.264     ⌄"),44,3)
        if(wide) {
            val middle=row().apply {gravity=Gravity.TOP}
            weighted(middle,rate,1.8f,gap=1)
            weighted(middle,codec,1f,gap=1)
            add(main,middle,top=4)
        } else add(main,codec,top=4)

        val audio=card(radius=5f,prominent=false)
        sectionHeader(audio,"Áudio","mic")
        val external=row()
        weighted(external,label("Microfone (Áudio Externo)",11f))
        external.addView(toggle(mic){mic=it})
        add(audio,external,38,3)
        val internal=row()
        weighted(internal,label("Áudio Interno (Som do Dispositivo)",11f))
        internal.addView(toggle(internalAudio){internalAudio=it})
        add(audio,internal,38,2)
        if(!wide) add(main,audio,top=4)

        val volume=card(radius=5f,prominent=false)
        sectionHeader(volume,"Volume","volume")
        add(volume,volumeRow("Microfone:",micVolume){micVolume=it},top=5)
        add(volume,volumeRow("Áudio Interno:",internalVolume){internalVolume=it},top=5)
        if(wide) {
            val lower=row().apply {gravity=Gravity.TOP}
            weighted(lower,audio,1.25f,gap=1)
            weighted(lower,volume,1f,gap=1)
            add(main,lower,top=4)
        } else add(main,volume,top=4)
        weighted(content,main,1f,gap=2)
        add(this,content)

        // Estes três botões pertencem à página, não à barra de menu do aplicativo.
        val footer=row()
        weighted(footer,action("Restaurar\nPadrão","refresh") {
            resolution="1080p"
            streamOrientation="Horizontal (16:9)"
            fps="60 FPS"
            bitrate=6000
            mic=true
            internalAudio=true
            micVolume=100
            internalVolume=100
            rememberScroll()
            render()
        },height=62,gap=1)
        weighted(footer,action("Salvar\nConfigurações","save",true),1.4f,62,1)
        weighted(footer,action("Aplicar","check"),height=62,gap=1)
        add(this,footer,62,top=12)
    }

    private fun platforms():LinearLayout=column().apply {
        val content=row().apply {gravity=Gravity.TOP}
        if(wide) {
            val side=card(radius=5f)
            listOf(Triple("Configuração","gear","encoder"),Triple("Plataformas","layers","platforms"),
                Triple("Transmissão","broadcast","transmission"),Triple("Visualização","screen","transmission"),
                Triple("Cenário / Overlay","layers","editor")).forEach { (name,symbol,dest) ->
                add(side,action(name,symbol,dest==page){navigate(dest)},45,3)
            }
            content.addView(side,LinearLayout.LayoutParams(dp(185),-2))
        }
        val main=column()
        val heading=row()
        heading.addView(icon("layers",TemplateStyle.white),LinearLayout.LayoutParams(dp(24),dp(24)))
        weighted(heading,label("Plataformas de Transmissão",if(wide)17f else 16f,true),gap=4)
        if(wide) {
            val titleRow=row()
            weighted(titleRow,heading,1f,gap=0)
            titleRow.addView(action("Conexão via RTMP / RTMPS","link"),
                LinearLayout.LayoutParams(dp(220),dp(38)))
            add(main,titleRow,43)
        } else {
            add(main,heading,34)
            add(main,action("Conexão via RTMP / RTMPS","link"),36,2)
        }
        val items=if(wide)row().apply {gravity=Gravity.TOP} else column()
        listOf("Twitch","YouTube","Kick").forEach { name ->
            if(wide) weighted(items,platform(name),gap=1)
            else add(items,platform(name),top=3)
        }
        add(main,items,top=3)
        weighted(content,main)
        add(this,content)
        val startButton=action("Iniciar Transmissão em Todas","broadcast",true)
        val updateButton=action("Atualizar Conexões","refresh")
        val stopButton=action("Encerrar Todas","stop")
        if(wide) {
            val footer=row()
            weighted(footer,updateButton,1f,44,1)
            weighted(footer,startButton,1.7f,44,1)
            weighted(footer,stopButton,1f,44,1)
            add(this,footer,44,top=5)
        } else {
            add(this,startButton,44,top=5)
            val footer=row()
            weighted(footer,updateButton,1f,42,1)
            weighted(footer,stopButton,1f,42,1)
            add(this,footer,42,top=3)
        }
    }

    private fun platform(name:String):View=card(radius=5f).apply {
        setPadding(dp(8),dp(6),dp(8),dp(7))
        val heading=row()
        heading.addView(icon(name.lowercase()),LinearLayout.LayoutParams(dp(26),dp(26)))
        weighted(heading,label(name,16f,true),gap=3)
        val status=label("Não conectado",9f,true).apply {
            setTextColor(TemplateStyle.muted)
        }
        heading.addView(status)
        heading.addView(toggle(false){unavailable()})
        add(this,heading,31)
        add(this,label("URL do Servidor (RTMP / RTMPS)",9f,true),top=3)
        add(this,action("Configuração não disponível","copy"),34,1)
        add(this,label("Stream Key",9f,true),top=3)
        add(this,action("Não configurada","eye"),34,1)
        val buttons=row()
        weighted(buttons,action("Testar","broadcast",true),1f,38,1)
        weighted(buttons,action("Conectar","link"),1f,38,1)
        add(this,buttons,38,top=4)
    }

    private fun editor():LinearLayout=column().apply {
        val workspace=row().apply {
            gravity=Gravity.TOP
            clipChildren=true
            clipToPadding=true
        }
        val sidebar=column().apply {
            background=TemplateStyle.Panel(density,radius=3f,glow=false)
            setPadding(dp(3),dp(4),dp(3),dp(4))
        }
        listOf("cursor","move","text","image","video","layers","trash").forEach { key ->
            val item=action("",key,editorMode==key) {
                when(key) {
                    "cursor","move" -> {editorMode=key;render()}
                    else -> unavailable()
                }
            }
            item.contentDescription=key
            add(sidebar,item,40,2)
        }
        // A barra esquerda rola apenas dentro do espaço do editor.
        // Não deixa os ícones invadirem o cabeçalho ao arrastar.
        val sidebarScroll=ScrollView(activity).apply {
            isVerticalScrollBarEnabled=false
            isFillViewport=false
            overScrollMode=View.OVER_SCROLL_NEVER
            clipChildren=true
            clipToPadding=true
            addView(sidebar,FrameLayout.LayoutParams(-1,-2))
        }
        workspace.addView(sidebarScroll,LinearLayout.LayoutParams(dp(53),-1))
        val center=column().apply {
            setPadding(dp(5),dp(4),dp(5),dp(4))
            gravity=Gravity.CENTER_VERTICAL
        }
        val previewBox=FrameLayout(activity)
        val preview=editorPreview()
        previewBox.addView(preview,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        center.addView(previewBox,LinearLayout.LayoutParams(-1,0,1f))
        val footer=row()
        footer.addView(label("Ajuste do canvas",10f),LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(label("1920 x 1080 (16:9)",10f,true),LinearLayout.LayoutParams(dp(145),-2))
        footer.addView(iconAction("back","Diminuir zoom") {zoom=maxOf(10,zoom-10);render()},LinearLayout.LayoutParams(dp(36),dp(36)))
        footer.addView(label("$zoom%",10f).apply {gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(44),-2))
        footer.addView(iconAction("next","Aumentar zoom") {zoom=minOf(200,zoom+10);render()},LinearLayout.LayoutParams(dp(36),dp(36)))
        footer.addView(iconAction("expand","Tela cheia") {showExpandedPreview()},LinearLayout.LayoutParams(dp(36),dp(36)))
        add(center,footer,40,2)
        workspace.addView(center,LinearLayout.LayoutParams(0,-1,1f))
        val elements=column().apply {
            background=TemplateStyle.Panel(density,radius=3f,glow=false)
            setPadding(dp(5),dp(5),dp(5),dp(5))
            clipChildren=true
            clipToPadding=true
        }
        sectionHeader(elements,"Elementos","layers")
        val filters=row()
        listOf("Todos","Cenas","Overlays").forEach { value ->
            weighted(filters,action(value,selected=filter==value) {filter=value;render()},height=31,gap=0)
        }
        add(elements,filters,34,3)
        val search=EditText(activity).apply {
            hint="Buscar elemento..."
            setSingleLine(true)
            textSize=10f
            setTextColor(TemplateStyle.white)
            setHintTextColor(TemplateStyle.muted)
            background=TemplateStyle.Panel(density,radius=4f,glow=false)
        }
        add(elements,search,37,4)
        val elementItems=column()
        listOf("Texto" to "text","Imagem" to "image","Vídeo / Fonte" to "video",
            "Alertas" to "bell","Widgets" to "grid","Mídia Social" to "share",
            "Formas" to "shape","Cenas Salvas" to "folder").forEach { (name,symbol) ->
            add(elementItems,action("$name    ›",symbol),37,2)
        }
        val scroll=ScrollView(activity).apply {
            isVerticalScrollBarEnabled=false
            overScrollMode=View.OVER_SCROLL_NEVER
            clipChildren=true
            clipToPadding=true
            addView(elementItems)
        }
        elements.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        add(elements,action("Voltar","back",true) {navigate("transmission")},42,4)
        workspace.addView(elements,LinearLayout.LayoutParams(dp(184),-1))
        addView(workspace,LinearLayout.LayoutParams(-1,-1))
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
        frame.background=TemplateStyle.Backdrop()
        val artwork=TemplateStyle.Brand(activity,"")
        frame.addView(artwork,FrameLayout.LayoutParams(-1,-1).apply {
            leftMargin=dp(25);rightMargin=dp(25);topMargin=dp(18);bottomMargin=dp(18)
        })
        editorArtwork=artwork
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
