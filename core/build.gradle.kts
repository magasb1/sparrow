import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // FfmpegInteropTest reads these; declare them so a change re-runs the tests.
    inputs.property("ffmpegHome", providers.environmentVariable("FFMPEG_HOME").orElse(""))
    inputs.property("ffmpegMajor", providers.environmentVariable("FFMPEG_MAJOR").orElse(""))
}
