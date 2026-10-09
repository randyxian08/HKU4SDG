import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.hku4sdg.handrail"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.hku4sdg.handrail"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        val revision = providers.exec { commandLine("git", "describe", "--always", "--dirty") }.standardOutput.asText.get().trim()
        val stamp = Instant.now().toString()
        buildConfigField("String", "BUILD_ID", "\"$revision/$stamp\"")
        testInstrumentationRunner = "org.hku4sdg.handrail.AccessibilityCheck"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
}
