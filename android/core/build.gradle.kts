plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Fault-injection tests are timing-sensitive; fail loudly instead of hanging CI.
    systemProperty("heat.test.timeoutMs", "20000")
    maxHeapSize = "512m"
}
