plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.davacada.sanka"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.davacada.sanka"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // the phone is 64-bit ARM; leaving out other chips keeps the image-recognition library small
        ndk { abiFilters += "arm64-v8a" }
    }

    // Same fixed key as the launcher, so updates install over the previous build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // On-device image labelling (model bundled in the app, nothing is sent anywhere) for "By similarity".
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
