import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import versioning.BuildConfig

plugins {
    id("com.gradleup.shadow")
}

tasks.named<ShadowJar>("shadowJar") {
    minimize {
        // adventure's DataComponentValueConverter gson provider is only referenced via
        // ServiceLoader, so minimize() strips it and adventure's static init then throws
        // (ServiceConfigurationError) on enable. Keep the gson serializer's classes.
        exclude(dependency("net.kyori:adventure-text-serializer-gson:.*"))
    }
    archiveFileName = "${rootProject.name}-${project.name}-${rootProject.version}.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    if (BuildConfig.relocate) {
        if (BuildConfig.shadePE) {
            relocate("io.github.retrooper.packetevents", "dev.aeroac.shaded.io.github.retrooper.packetevents")
            relocate("com.github.retrooper.packetevents", "dev.aeroac.shaded.com.github.retrooper.packetevents")
            relocate("net.kyori", "dev.aeroac.shaded.kyori") // use PE's built-in adventure instead when not shading PE
        }
        relocate("club.minnced", "dev.aeroac.shaded.discord-webhooks")
        relocate("org.slf4j", "dev.aeroac.shaded.slf4j") // Required by discord-webhooks
        relocate("github.scarsz.configuralize", "dev.aeroac.shaded.configuralize")
        relocate("com.github.puregero", "dev.aeroac.shaded.com.github.puregero")
        relocate("com.google.code.gson", "dev.aeroac.shaded.gson")
        relocate("alexh", "dev.aeroac.shaded.maps")
        relocate("it.unimi.dsi.fastutil", "dev.aeroac.shaded.fastutil")
        relocate("okhttp3", "dev.aeroac.shaded.okhttp3")
        relocate("okio", "dev.aeroac.shaded.okio")
        relocate("org.yaml.snakeyaml", "dev.aeroac.shaded.snakeyaml")
        relocate("org.json", "dev.aeroac.shaded.json")
        relocate("org.intellij", "dev.aeroac.shaded.intellij")
        relocate("org.jetbrains", "dev.aeroac.shaded.jetbrains")
        relocate("org.incendo", "dev.aeroac.shaded.incendo")
        relocate("io.leangen.geantyref", "dev.aeroac.shaded.geantyref") // Required by cloud
        relocate("com.zaxxer", "dev.aeroac.shaded.zaxxer") // Database history
    }
    mergeServiceFiles()
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}
