plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.heat.platform"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    api(libs.play.services.wearable)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.core.ktx)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
