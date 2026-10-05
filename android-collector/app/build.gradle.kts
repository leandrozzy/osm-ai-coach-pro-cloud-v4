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
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "COACH_URL", "\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
