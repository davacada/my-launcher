plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.davacada.gasto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.davacada.gasto"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Same fixed key as the other apps, so updates install over the previous build
    // (and so the Google sign-in for Gmail keeps matching this app).
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
    // Google sign-in, to read receipts in Gmail (read-only).
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // A daily check: renewals coming up, Gmail sync.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Pull down to refresh.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
