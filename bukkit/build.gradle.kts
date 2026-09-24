import net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission
import versioning.BuildConfig

plugins {
    `maven-publish`
    aero.`base-conventions`
    aero.`shadow-conventions`
    id("de.eldoria.plugin-yml.bukkit") version "0.8.0"
    id("xyz.jpenilla.run-paper") version "3.0.0-beta.1"
}

repositories {
    val localOverride = if (BuildConfig.mavenLocalOverride) mavenLocal() else null

    // Exclusive Repositories (One HTTP request per dep)
    exclusive("https://repo.papermc.io/repository/maven-public/", { name = "papermc" }) {
        includeGroup("io.papermc.paper")
        includeGroup("net.md-5")
    }

    exclusive("https://libraries.minecraft.net", { mavenContent { releasesOnly() } }) {
        includeModule("com.mojang", "brigadier")
    }

    exclusive("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        includeGroup("me.clip")
    }

    val grimPublicReleases = maven("https://maven.grim.ac/public/releases") {
        mavenContent { releasesOnly() }
    }
    val grimPublicSnapshots = maven("https://maven.grim.ac/public/snapshots") {
        mavenContent { snapshotsOnly() }
    }
    val grimLegacySnapshots = maven("https://repo.grim.ac/snapshots")
    exclusiveContent {
        forRepositories(*listOfNotNull(localOverride, grimPublicReleases, grimPublicSnapshots, grimLegacySnapshots).toTypedArray())
        filter {
            includeGroup("ac.grim.grimac")
            includeGroup("com.github.retrooper")
        }
    }

    exclusive("https://repo.viaversion.com", { mavenContent { releasesOnly() } }) {
        includeGroup("com.viaversion")
    }

    exclusive("https://nexus.scarsz.me/content/repositories/releases", { mavenContent { releasesOnly() } }) {
        includeGroup("github.scarsz")
    }

    mavenCentral()
}


dependencies {
    testImplementation(libs.paper.api)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
    // AeroPlayer has an optional ViaVersion field. Mockito inspects that field when it makes a
    // test double, so the optional API must also exist in the test JVM.
    testRuntimeOnly(libs.viaversion)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    compileOnly(libs.paper.api)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.luckperms)

    if (BuildConfig.shadePE) {
        implementation(libs.packetevents.spigot)
    } else {
        compileOnly(libs.packetevents.spigot)
    }
    implementation(libs.cloud.paper)
    implementation(libs.adventure.platform.bukkit)
    implementation(libs.grim.bukkit.internal)

    implementation(project(":common"))
    shadow(project(":common"))
}

bukkit {
    name = "AeroAC"
    author = "AeroAC"
    main = "dev.aeroac.platform.bukkit.AeroACBukkitLoaderPlugin"
    prefix = "Aero AC"
    apiVersion = "1.13"
    foliaSupported = true

    if (!BuildConfig.shadePE) {
        depend = listOf("packetevents")
    }

    softDepend = listOf(
        "ProtocolLib",
        "ProtocolSupport",
        "Essentials",
        "ViaVersion",
        "ViaBackwards",
        "ViaRewind",
        "Geyser-Spigot",
        "floodgate",
        "FastLogin",
        "PlaceholderAPI",
        "LuckPerms",
        // Driver holder mods — softdepend so each backend's driver class
        // resolves through the linked classloader.
        "sqlite-jdbc",
        "mysql-jdbc",
        "postgresql-jdbc",
        "mongodb-driver",
        "jedis",
    )

    permissions {
        // Aero administrator interface. Split so a server can grant looking without granting
        // recording: opening a dataset session writes labelled data a model will be trained on,
        // and a review verdict is treated as ground truth by the offline tooling.
        register("aero.admin") {
            description = "Every Aero administrator permission"
            default = Permission.Default.OP
            children = listOf(
                "aero.gui", "aero.players", "aero.suspicious", "aero.profile", "aero.monitor",
                "aero.view", "aero.status", "aero.mitigation", "aero.alerts", "aero.training",
                "aero.training.record", "aero.training.stop", "aero.training.overview",
                "aero.training.review", "aero.training.model",
            )
        }
        register("aero.gui") {
            description = "Open the /aero interface"
            default = Permission.Default.OP
        }
        register("aero.players") {
            description = "See the online player list with its model output"
            default = Permission.Default.OP
        }
        register("aero.suspicious") {
            description = "See the flagged player list"
            default = Permission.Default.OP
        }
        register("aero.profile") {
            description = "Open a player's Aero profile and history"
            default = Permission.Default.OP
        }
        register("aero.monitor") {
            description = "Stream a player's live telemetry"
            default = Permission.Default.OP
        }
        register("aero.view") {
            description = "See the risk indicator above players. Nobody else can see it."
            default = Permission.Default.OP
        }
        register("aero.status") {
            description = "See service health, checks and statistics"
            default = Permission.Default.OP
        }
        register("aero.mitigation") {
            description = "See what was mitigated and why"
            default = Permission.Default.OP
        }
        register("aero.alerts") {
            description = "Receive Aero risk transition alerts"
            default = Permission.Default.OP
        }
        register("aero.training") {
            description = "Open the training centre"
            default = Permission.Default.OP
        }
        register("aero.training.record") {
            description = "Start a dataset recording"
            default = Permission.Default.OP
        }
        register("aero.training.stop") {
            description = "Stop a dataset recording"
            default = Permission.Default.OP
        }
        register("aero.training.overview") {
            description = "See dataset totals, breakdowns and coverage"
            default = Permission.Default.OP
        }
        register("aero.training.review") {
            description = "See the review queue. Review verdicts themselves are recorded offline."
            default = Permission.Default.OP
        }
        register("aero.training.model") {
            description = "See training jobs and evaluation results"
            default = Permission.Default.OP
        }
        register("grim.neural") {
            description = "View Aero AC telemetry profiles"
            default = Permission.Default.OP
        }
        register("grim.neural.dataset") {
            description = "Record manually labeled Aero AC datasets"
            default = Permission.Default.OP
        }
        register("grim.neural.monitor") {
            description = "Stream Aero AC neural telemetry for a player"
            default = Permission.Default.OP
        }
        register("grim.alerts") {
            description = "Receive alerts for violations"
            default = Permission.Default.OP
        }

        register("grim.alerts.enable-on-join") {
            description = "Enable alerts on join"
            default = Permission.Default.OP
        }

        register("grim.performance") {
            description = "Check performance metrics"
            default = Permission.Default.OP
        }

        register("grim.profile") {
            description = "Check user profile"
            default = Permission.Default.OP
        }

        register("grim.brand") {
            description = "Show client brands on join"
            default = Permission.Default.OP
        }

        register("grim.brand.enable-on-join") {
            description = "Enable showing client brands on join"
            default = Permission.Default.OP
        }

        register("grim.sendalert") {
            description = "Send cheater alert"
            default = Permission.Default.OP
        }

        register("grim.nosetback") {
            description = "Disable setback"
            default = Permission.Default.FALSE
        }

        register("grim.nomodifypacket") {
            description = "Disable modifying packets"
            default = Permission.Default.FALSE
        }

        register("grim.disabled") {
            description = "Disable Aero AC checks while keeping player state tracked"
            default = Permission.Default.FALSE
        }

        register("grim.exempt") {
            description = "Exempt from all checks"
            default = Permission.Default.FALSE
        }

        register("grim.verbose") {
            description = "Receive verbose alerts for violations"
            default = Permission.Default.OP
        }

        register("grim.verbose.enable-on-join") {
            description =
                "Enable verbose alerts on join"
            default = Permission.Default.FALSE
        }

        register("grim.list") {
            description =
                "Shows lists of specific data"
            default = Permission.Default.FALSE
        }

    }
}

publishing.publications.create<MavenPublication>("maven") {
    artifact(tasks["shadowJar"])
}

tasks {
    // 1.8.8 - 1.16.5   = Java 8
    // 1.17             = Java 16
    // 1.18 - 1.20.4    = Java 17
    // 1.20.5 - 1.21.11 = Java 21
    // 26.1+            = Java 25
    val version = "26.2"
    val javaVersion = JavaLanguageVersion.of(25)

    val jvmArgsExternal = listOf(
        "-Dcom.mojang.eula.agree=true",
        "-Dpaper.explicit-flush=true",
        "-DPaper.IgnoreJavaVersion=true"
    )

    runServer {
        minecraftVersion(version)
        runDirectory = projectDir.resolve("run/$version")

        val javaToolchains = project.extensions.getByType<JavaToolchainService>()
        javaLauncher = javaToolchains.launcherFor {
            vendor = JvmVendorSpec.JETBRAINS
            languageVersion = javaVersion
        }

        jvmArgs = jvmArgsExternal
    }

    shadowJar {
        exclude("META-INF/services/javax.annotation.processing.Processor")

        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
    }
}

 tasks.test { useJUnitPlatform() }
