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
        versionCode = 4
        versionName = "2.1"
    }
    buildFeatures { aidl = true }
    // Same key on every build, so a new APK installs over the old app and keeps its saved phones.
    signingConfigs {
        create("phonelink") {
            storeFile = file("phonelink.jks")
            storePassword = "phonelink"
            keyAlias = "phonelink"
            keyPassword = "phonelink"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("phonelink")
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
    implementation("androidx.core:core:1.12.0")
}
