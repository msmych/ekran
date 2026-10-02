plugins {
    java
    application
    id("com.diffplug.spotless") version "7.2.1"
}

group = "uk.matvey"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.caffeine)
    implementation(libs.javalin)
    implementation(libs.javalin.rendering)
    implementation(libs.thymeleaf)
    implementation(libs.jackson.databind)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.javalin.testtools)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass = "uk.matvey.ekran.Main"
}

tasks.test {
    useJUnitPlatform()
}

// hygiene only, not a code formatter — hand layout and line breaks stay as written
spotless {
    java {
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named<JavaExec>("run") {
    val dotenv = file(".env").takeIf { it.exists() }?.readLines()
        ?.filter { it.isNotBlank() && !it.startsWith("#") }
        ?.associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        } ?: emptyMap()
    environment.putAll(dotenv)
    environment.putIfAbsent("DATABASE_URL", "postgres://ekran:ekran@localhost:5433/ekran")
    environment.putIfAbsent("PUBLIC_BASE_URL", "http://localhost:7070")
}