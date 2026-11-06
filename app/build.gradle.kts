plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livenexus.controller"
    ndkVersion = "27.0.12077973"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.livenexus.controller"
        minSdk = 29
        targetSdk = 33
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 200000 + runNumber
        versionName = "1.0.$runNumber"
    }
    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("existingRelease") {
            val path = System.getenv("LIVENEXUS_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) storeFile = file(path)
            storePassword = System.getenv("LIVENEXUS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LIVENEXUS_KEY_ALIAS")
            keyPassword = System.getenv("LIVENEXUS_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("existingRelease")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.activity:activity:1.9.0")
}
