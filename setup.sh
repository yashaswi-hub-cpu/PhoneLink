#!/data/data/com.termux/files/usr/bin/bash
set -e

ROOT="$PWD"
PKG="app/src/main/java/com/phonelink/app"
AIDL="app/src/main/aidl/com/phonelink/app"
RES_D="app/src/main/res/drawable"
RES_M="app/src/main/res/mipmap-anydpi-v26"
RES_V="app/src/main/res/values"

mkdir -p "$PKG" "$AIDL" "$RES_D" "$RES_M" "$RES_V"
mkdir -p app/src/main/res/mipmap-mdpi
mkdir -p app/src/main/res/mipmap-hdpi
mkdir -p app/src/main/res/mipmap-xhdpi
mkdir -p app/src/main/res/mipmap-xxhdpi
mkdir -p app/src/main/res/mipmap-xxxhdpi
mkdir -p gradle/wrapper

# ---------- .gitignore ----------
cat > .gitignore <<'EOF'
*.iml
.gradle
/local.properties
/.idea
.DS_Store
/build
/app/build
/captures
.externalNativeBuild
.cxx
local.properties
*.keystore
*.jks
*.p12
EOF

# ---------- root build.gradle.kts ----------
cat > build.gradle.kts <<'EOF'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
EOF

# ---------- settings.gradle.kts ----------
cat > settings.gradle.kts <<'EOF'
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PhoneLink"
include(":app")
EOF

# ---------- gradle.properties ----------
cat > gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
EOF

# ---------- app/build.gradle.kts ----------
cat > app/build.gradle.kts <<'EOF'
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
EOF

# ---------- AndroidManifest.xml ----------
cat > app/src/main/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />

    <application
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="PhoneLink"
        android:usesCleartextTraffic="true"
        android:requestLegacyExternalStorage="true"
        android:theme="@android:style/Theme.Material.Light.DarkActionBar">

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".BrowserActivity" />
        <activity
            android:name=".PlayerActivity"
            android:configChanges="orientation|screenSize|keyboardHidden" />

        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:enabled="true"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

        <service
            android:name=".FileServerService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <service
            android:name=".TransferService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
EOF

# ---------- AIDL ----------
cat > "$AIDL/IUserService.aidl" <<'EOF'
package com.phonelink.app;

import android.os.ParcelFileDescriptor;

interface IUserService {
    void destroy();
    void exit();
    String list(String path);
    String stat(String path);
    ParcelFileDescriptor openRead(String path);
    ParcelFileDescriptor openWrite(String path);
}
EOF

echo "Created folder tree and base files."
echo "Now paste the Kotlin files (see next steps in chat)."
