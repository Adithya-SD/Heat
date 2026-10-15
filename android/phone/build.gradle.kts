import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Phone and watch MUST be signed by the same key: the Wearable Data Layer only connects two apps that share
// an application id and a signing identity. Both use the local debug keystore until a release key exists.
val sharedKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")

// Builds for other people (-PheatDist) are signed with a private key kept outside the repository, in
// ~/.heat/release.properties (storeFile, storePassword, keyAlias, keyPassword). The phone and the watch must use the
// same one. Everything else, including plain release builds, uses the shared debug key so a development device keeps
// updating in place.
val distProps = Properties().apply {
    file(System.getProperty("user.home") + "/.heat/release.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.heat.phone"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        // Not the earlier app's id, so old and new can be installed side by side and compared on one phone.
        applicationId = "app.heat.camera"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        // libwebrtc is big and per-architecture. Every phone that can run this camera app is 64-bit ARM or older 32-bit ARM.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("heat") {
            storeFile = sharedKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (distProps.isNotEmpty()) create("dist") {
            storeFile = file(distProps.getProperty("storeFile"))
            storePassword = distProps.getProperty("storePassword")
            keyAlias = distProps.getProperty("keyAlias")
            keyPassword = distProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix, ever: a suffixed debug build is invisible to the watch.
            signingConfig = signingConfigs.getByName("heat")
        }
        release {
            signingConfig = if (project.hasProperty("heatDist") && distProps.isNotEmpty()) signingConfigs.getByName("dist") else signingConfigs.getByName("heat")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":platform"))
    implementation(project(":rooms"))
    implementation(libs.zxing.core)
    implementation(project(":glass"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
