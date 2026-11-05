# LiveNEXUS Controller — Base visual Canva

Reconstrução do APK do zero a partir do template web do Canva.

## Escopo atual
- Interface única: HTML/CSS/JS visual do Canva em `app/src/main/assets/livenexus/index.html`.
- Seis telas: Menu, Editor, Encoder, Controles, Plataformas e Visão Geral.
- WebView local Android (Kotlin), sem acesso à rede.
- Fontes DM Sans e Space Mono e ícones Lucide incorporados como recursos locais.
- Botões e seletores são somente demonstrativos; **nenhuma integração funcional com transmissão, encoder, RTC, TTS, bots ou APIs foi implementada**.
- Identificador de aplicativo: `com.livenexus.controller`.
- Mantida somente a configuração de assinatura release para permitir atualização assinada.

## Compilar e verificar
O GitHub Actions executa `python3 verificar_template.py` e `gradle --no-daemon :app:assembleRelease` com a assinatura configurada nos secrets existentes. O APK é publicado no workflow **COMPILAR APK ANDROID**.

**Fidelidade:** HTML/CSS extraído do template utilizado no laboratório e levado sem redesenho das seis telas. A aprovação da equivalência visual depende de comparação direta no dispositivo com o Canva.
