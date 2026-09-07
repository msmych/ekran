plugins {
    java
    application
}

group = "uk.matvey"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.javalin)
    implementation(libs.javalin.rendering)
    implementation(libs.thymeleaf)
    implementation(libs.jackson.databind)
    implementation(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.javalin.testtools)
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