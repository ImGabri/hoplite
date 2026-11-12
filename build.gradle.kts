plugins {
    id("fabric-loom") version "1.6.11"
    kotlin("jvm") version "1.9.24"
}

version = "1.0.0"
group = "com.hoplite"

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.2:v2")
    modImplementation("net.fabricmc:fabric-loader:0.15.11")
    // Fabric API for Minecraft 1.21.1 (0.93.0+1.21 does not exist).
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.102.1+1.21.1")
    
    // Allow Kotlin Standard Library usage
    implementation(kotlin("stdlib"))
}

tasks.jar {
    archiveFileName.set("hopliter.jar")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

// Keep Java compilation on the same target as Kotlin (the Gradle daemon runs
// on Java 21, which would otherwise default compileJava to 21).
tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
