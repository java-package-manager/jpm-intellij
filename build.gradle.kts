import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.jpm"
// The release tag must match this version (see .github/workflows/ci.yml).
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    // dev.jpm:jpm-api, jpm's client: releases, and snapshots of jpm's main branch until jpm 0.12.0
    maven("https://repo.thenextlvl.net/releases")
    maven("https://repo.thenextlvl.net/snapshots")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("dev.jpm:jpm-api:0.11.0-20261122.163616-1")
    intellijPlatform {
        intellijIdea("2026.1.5.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Use the platform's interface default methods instead of copying them into every implementing class,
        // which would look like overriding the platform's internal methods.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginVerification {
        // The oldest supported release (2026.1) and the newest one; every IDE in between shares their APIs.
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.5.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
    // Marketplace publishing and signing, used by the release job when these secrets are set.
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("PLUGIN_SIGNING_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PLUGIN_SIGNING_PRIVATE_KEY")
        password = providers.environmentVariable("PLUGIN_SIGNING_PRIVATE_KEY_PASSWORD")
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}
