plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livenexus.controller"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.livenexus.controller"
        minSdk = 29
        targetSdk = 33
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 200000 + runNumber
        versionName = "1.0.$runNumber"
    }
    signingConfigs {
        create("existingRelease") {
            val path = System.getenv("LIVENEXUS_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) storeFile = file(path)
            storePassword = System.getenv("LIVENEXUS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LIVENEXUS_KEY_ALIAS")
            keyPassword = System.getenv("LIVENEXUS_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("existingRelease")
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
}
