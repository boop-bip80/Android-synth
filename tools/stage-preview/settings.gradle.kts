// Standalone developer tool: NOT part of the root build or CI.
// Renders the Aurora stage simulation (the exact `:core` code the Android app draws
// with) headlessly through Java2D, so the visuals can be iterated on in seconds
// without an emulator:  gradle -p tools/stage-preview render
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "stage-preview"
include(":core")
project(":core").projectDir = file("../../core")
