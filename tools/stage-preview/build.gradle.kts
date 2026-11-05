plugins {
    kotlin("jvm") version "2.1.20"
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.orbital.synth.preview.PreviewMainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.register<JavaExec>("render") {
    group = "preview"
    description = "Render scripted Aurora stage scenarios to build/frames/*.png"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.orbital.synth.preview.PreviewMainKt")
    jvmArgs("-Djava.awt.headless=true")
    args(layout.buildDirectory.dir("frames").get().asFile.absolutePath)
}
