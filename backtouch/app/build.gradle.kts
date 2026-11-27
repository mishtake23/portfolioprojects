plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.backtouch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.backtouch"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// No third-party dependencies on purpose (Step 1+2 use only the Android framework).
