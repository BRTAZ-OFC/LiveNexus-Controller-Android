plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.livenexus.controller"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.livenexus.controller"
        minSdk = 29
        targetSdk = 33
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100000 + buildNumber
        versionName = "1.0.$buildNumber"
    }
    signingConfigs {
        create("release") {
            val envKeystore = System.getenv("LIVENEXUS_KEYSTORE_PATH")
            val releaseKeystore = if (!envKeystore.isNullOrBlank()) file(envKeystore) else file("livenexus-release.jks")
            val fallbackKeystore = file("${rootDir}/debug.keystore")
            storeFile = if (releaseKeystore.exists()) releaseKeystore else fallbackKeystore
            storePassword = System.getenv("LIVENEXUS_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("LIVENEXUS_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("LIVENEXUS_KEY_PASSWORD") ?: "android"
        }
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
