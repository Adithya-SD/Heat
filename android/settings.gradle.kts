pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HeatAndroid"

// :core          pure-JVM brain: crypto, link/session protocol, config model, motion engine (all unit-tested on the JVM)
// :platform      Android glue shared by both apps: Wear Data Layer carrier, multicast beacon, key vault
// :rooms         Heat Link: rooms found by a code over the internet (PeerJS signaling + a WebRTC data channel). Phone only: libwebrtc is large
// :glass         Liquid Glass: one AGSL shader + Compose components (phone and watch)
// :glass-preview JVM tool that renders the real AGSL shader to PNG so refraction can be checked without a device
// :phone         camera + hub app
// :wear          watch viewer/remote app
include(":core")
include(":platform")
include(":rooms")
include(":glass")
include(":glass-preview")
include(":phone")
include(":wear")
