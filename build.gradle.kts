plugins {
    id("fabric-loom") version "1.5-SNAPSHOT"
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
    // Fixed version for 1.21.1
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.93.0+1.21")
    
    // Allow Kotlin Standard Library usage
    implementation(kotlin("stdlib"))
}

tasks.jar {
    archiveFileName.set("hopliter.jar")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}
