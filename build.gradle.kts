plugins {
    id("fabric-loom") version "1.14.10"
    kotlin("jvm") version "2.3.0"
}

version = "1.2.1"
group = "com.example.slotcycler"

base {
    archivesName.set("slotcycler")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.6:v2")
    modImplementation("net.fabricmc:fabric-loader:0.18.2")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")
    // Provides the Kotlin standard library at runtime (install the
    // fabric-language-kotlin mod alongside this one).
    modImplementation("net.fabricmc:fabric-language-kotlin:1.14.2+kotlin.2.4.21")
}

// Fill in ${version} in fabric.mod.json.
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// `./gradlew installMod` builds the jar and copies it into your Minecraft mods
// folder. Pass -PmodsDir=<path> if yours is not in the default location.
tasks.register<Copy>("installMod") {
    group = "mod"
    description = "Builds the mod jar and copies it into the Minecraft mods folder."

    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val defaultModsDir = when {
        "win" in os -> "${System.getenv("APPDATA") ?: "$home/AppData/Roaming"}/.minecraft/mods"
        "mac" in os -> "$home/Library/Application Support/minecraft/mods"
        else -> "$home/.minecraft/mods"
    }
    val modsDir = (findProperty("modsDir") as String?) ?: defaultModsDir

    // Remove older versions of this mod so Fabric doesn't see duplicates.
    doFirst {
        fileTree(modsDir) { include("${project.name}-*.jar") }.forEach { it.delete() }
    }
    from(tasks.named("remapJar"))
    into(modsDir)
    doLast { println("Installed ${project.name}-${project.version}.jar to $modsDir") }
}
