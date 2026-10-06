plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.osmaicoach.collector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.osmaicoach.collector"
        minSdk = 30
        targetSdk = 35
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 2000 + runNumber
        versionName = "2.0.$runNumber"
        buildConfigField("String", "BACKEND_URL", "\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\"")
        buildConfigField("String", "BACKEND_FALLBACK_URL", "\"https://osm-ai-coach-pro-cloud-v4.vercel.app\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val password = System.getenv("ANDROID_SIGNING_PASSWORD")
            val path = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!password.isNullOrBlank() && !path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = password
                keyAlias = "osmcoach"
                keyPassword = password
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
