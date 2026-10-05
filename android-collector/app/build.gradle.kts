plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.osmaicoach.collector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.osmaicoach.collector"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Troque apenas esta URL quando soubermos o domínio de produção definitivo.
        buildConfigField("String", "COACH_URL", "\"https://example.invalid\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
