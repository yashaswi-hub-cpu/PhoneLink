plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonelink.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonelink.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }
    buildFeatures { aidl = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
