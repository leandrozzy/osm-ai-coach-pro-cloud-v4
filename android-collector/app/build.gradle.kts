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
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 1000 + runNumber
        versionName = "1.0.$runNumber"
        buildConfigField("String", "COACH_URL", "\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\"")
    }

    buildFeatures { buildConfig = true }

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
