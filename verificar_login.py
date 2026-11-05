from pathlib import Path

root = Path(__file__).resolve().parent
source = root / "app/src/main/java/com/livenexus/controller"
activity = (source / "MainActivity.kt").read_text(encoding="utf-8")
auth = (source / "LiveNexusAuth.kt").read_text(encoding="utf-8")
manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")

checks = {
    "interface sem HTML, CSS ou JavaScript": not any(
        p.suffix.lower() in {".html", ".htm", ".js", ".css"}
        for p in root.rglob("*") if p.is_file()
    ),
    "sem WebView": "WebView" not in activity + auth and "android.webkit" not in activity + auth,
    "UI realmente nativa em Kotlin": all(
        part in activity for part in ("EditText(this)", "Button(this)", "TextView(this)", "ScrollView(this)")
    ),
    "login apenas usuário e senha": all(
        part in activity for part in ('"Usuário"', '"Senha"', '"ENTRAR"')
    ) and "e-mail" not in activity.lower() and "email" not in activity.lower(),
    "Google e Steam externos": all(
        part in activity for part in ('"google"', '"steam"', "AuthTabIntent.Builder().build().launch")
    ),
    "rotas originais do servidor": all(
        part in auth for part in (
            '"/api/auth/login"',
            '"/api/auth/mobile/start"',
            '"/api/auth/mobile/exchange"',
            '"/api/auth/me"',
        )
    ),
    "callback de autenticação existente": all(
        part in manifest for part in (
            'android:scheme="com.livenexus.controller"',
            'android:host="auth"', 'android:path="/callback"'
        )
    ),
    "sem recreação por rotação": 'android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"' in manifest,
    "sessão AES-GCM no AndroidKeyStore": all(
        part in auth for part in ("AndroidKeyStore", "AES/GCM/NoPadding", "GCMParameterSpec")
    ),
    "tela pós-login nativa": "private fun showBlankScreen()" in activity and "NativeTemplate(this).show()" in activity and (source / "NativeTemplate.kt").exists(),
    "pacote e assinatura original": 'applicationId = "com.livenexus.controller"' in gradle
        and "LIVENEXUS_KEYSTORE_PATH" in gradle,
}
for description, ok in checks.items():
    print(("OK" if ok else "FALHOU") + " - " + description)
if not all(checks.values()):
    raise SystemExit(1)
