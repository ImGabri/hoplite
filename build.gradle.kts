plugins {
    id("fabric-loom") version "1.14.10"
    kotlin("jvm") version "2.3.0"
}

version = "1.1.0"
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
