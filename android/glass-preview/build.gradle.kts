plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    // Dev-time tool only: renders the production shader with Skia so the look can be checked without a device.
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    implementation(if (windows) libs.skiko.windows else libs.skiko.linux)
}

application {
    mainClass.set("app.heat.glass.preview.MainKt")
}

tasks.named<JavaExec>("run") {
    args(
        rootProject.file("glass/src/main/res/raw/liquid_glass.agsl").absolutePath,
        layout.buildDirectory.dir("out").get().asFile.absolutePath
    )
}
