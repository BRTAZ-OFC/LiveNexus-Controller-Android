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
            storeFile = file(System.getenv("LIVENEXUS_KEYSTORE_PATH") ?: "livenexus-release.jks")
            storePassword = System.getenv("LIVENEXUS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LIVENEXUS_KEY_ALIAS")
            keyPassword = System.getenv("LIVENEXUS_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
