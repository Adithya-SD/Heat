plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Lens Lab: a plain camera for trying out a phone's lenses. Not Heat and not shipped to anyone; it shares Heat's zoom
// smoothing and icons, nothing else.
//
// The package name decides which cameras some phones let an app open (Samsung hides its tele and its all-lens logical
// camera from most packages). It defaults to a neutral id; pass -PlabId=<package> to build it under another one for
// your own phone.
android {
    namespace = "app.lenslab"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = (findProperty("labId") as String?) ?: "app.lenslab"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":glass"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
