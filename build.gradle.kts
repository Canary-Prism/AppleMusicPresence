plugins {
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.github.canary-prism"
version = "1.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

application {
    mainClass = "canaryprism.presence.apple.music.Main"
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.scijava.org/content/repositories/public/")
    }
    maven { url = uri("https://jitpack.io") }
}

dependencies {

    implementation("com.github.JnCrMx:discord-game-sdk4j:v1.0.0")

    implementation(libs.obstmusic)

    implementation(libs.slf4j)

    runtimeOnly(libs.bundles.log4j)

    implementation(libs.directories)

    implementation(libs.picocli)

    implementation(libs.json)

    implementation(libs.caffeine)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    mergeServiceFiles()
}