plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.livenexus.controller"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.livenexus.controller"
        minSdk = 29
        targetSdk = 32
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}