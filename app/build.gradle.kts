plugins {
    id("com.android.application")
}

android {
    namespace = "com.bajiao.notificationisland"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bajiao.notificationisland"
        minSdk = 29
        targetSdk = 37
        versionCode = 14
        versionName = "1.8.0"
    }

    lint {
        targetSdk = 37
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    // Supplied by the LSPosed/libxposed runtime; never package it in the APK.
    compileOnly("io.github.libxposed:api:102.0.0")
}

