#!/usr/bin/env python3
"""Verifica o APK visual sem alegar equivalência pixel a pixel."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
asset = root / "app/src/main/assets/livenexus"
html = (asset / "index.html").read_text(encoding="utf-8")
main = (root / "app/src/main/java/com/livenexus/controller/MainActivity.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
icons = "\n".join((asset / "icons" / f"icons-{n}.js").read_text(encoding="utf-8") for n in range(1, 6))
checks = {
    "identidade original do APK": 'applicationId = "com.livenexus.controller"' in gradle,
    "somente INTERNET e nenhum serviço": len(manifest.findall("uses-permission")) == 1 and manifest.findall("uses-permission")[0].attrib.get("{http://schemas.android.com/apk/res/android}name") == "android.permission.INTERNET" and not manifest.find("application").findall("service"),
    "somente uma Activity": len(manifest.find("application").findall("activity")) == 1,
    "interface HTML/CSS local": 'file:///android_asset/livenexus/index.html' in main,
    "WebView sem rede": "settings.blockNetworkLoads = true" in main,
    "ponte de login isolada": 'addJavascriptInterface(LoginBridge(), "AndroidLogin")' in main and 'removeJavascriptInterface("AndroidLogin")' in main,
    "Controller usa APIs existentes": '"/api/cenarios"' in main and '"/api/controller/commands"' in main and '"AndroidController"' in main,
    "template liga comandos reais": 'window.LiveNexusControls' in html and "controllerCommand('rtc-reload')" in html and "controllerCommand('music-next')" in html,
    "selecao pelo nome do subcenario": 'hotkey-scene-child' in html and 'sub.name' in html,
    "seis telas Canva": all(f'id="{name}-view"' in html for name in ("menu", "editor", "encoder", "controls", "platforms", "dashboard")),
    "CSS Canva preservado": all(word in html for word in (".platform-grid", ".mini-frame", "function navigate(name)")),
    "sem dependências Canva CDN": all(word not in html for word in ("__codeletBootstrap__", "/_sdk/", "cdn.tailwindcss.com", "cdn.jsdelivr.net", "fonts.googleapis.com")),
    "TTS ainda não conectado": "speechSynthesis" not in html,
    "login obrigatório": 'login.html' in main and 'if (!authorized) return' in main,
    "sessão validada pelo servidor": '"/api/auth/me"' in main,
    "sessão criptografada no AndroidKeyStore": all(
        fragment in main for fragment in (
            'SecureSessionCache', 'AndroidKeyStore',
            'AES/GCM/NoPadding', 'GCMParameterSpec',
            'sessionCache.save(token)', 'sessionCache.load()'
        )
    ),
    "restauração valida API antes do menu": 'verifyAndOpen(cached, restoring = true)' in main,
    "rotação preserva Activity e WebView": (
        'android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"' in
        (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        and 'onConfigurationChanged(newConfig: Configuration)' in main
        and 'browser?.invalidate()' in main
    ),
    "botão voltar não apaga sessão": 'override fun onBackPressed()' in main and 'moveTaskToBack(true)' in main,
    "provedores externos": 'Intent.ACTION_VIEW' in main and '"/api/auth/mobile/start"' in main,
    "callback do aplicativo": 'com.livenexus.controller' in main and 'android:scheme="com.livenexus.controller"' in (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"),
    "PKCE": 'sha256Challenge(verifier)' in main and '"/api/auth/mobile/exchange"' in main,
    "ícones incorporados": all(word in icons for word in ('"menu"', '"settings"', '"layers"', '"radio"')),
    "fontes incorporadas": all((asset / "fonts" / f).is_file() for f in ("dm-sans.ttf", "space-mono-regular.ttf", "space-mono-bold.ttf")),
    "sem arquivo Kotlin legado": sorted(p.name for p in (root / "app/src/main/java/com/livenexus/controller").glob("*.kt")) == ["MainActivity.kt"]
}
scripts = [
    script for script in re.findall(r"<script[^>]*>(.*?)</script>", html, flags=re.DOTALL)
    if script.strip()
]
if not scripts:
    checks["JS do template presente"] = False
else:
    validation = subprocess.run(
        ["node", "--check"],
        input="\n".join(scripts),
        text=True,
        capture_output=True,
        check=False,
    )
    checks["JS do template sem erro de sintaxe"] = validation.returncode == 0
    if validation.returncode:
        print(validation.stderr)
for label,valid in checks.items():
    print(("PASS " if valid else "FAIL ") + label)
if not all(checks.values()):
    raise SystemExit(1)
