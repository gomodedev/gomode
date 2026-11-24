// Fake Even G2 glasses for the SDK's tests and its hosts' unit and instrumented tests.
//
// A library rather than Gradle test fixtures: fixtures in Kotlin need
// android.experimental.enableTestFixturesKotlinSupport, which makes AGP apply KotlinBaseApiPlugin
// beside org.jetbrains.kotlin.android in every module, and ktlint-gradle 14.2.0 then registers its
// Android tasks once per plugin and fails on the duplicate task names.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.fghbuild.eveng2.testing"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Dependency and SDK freshness belong to `make upgrade`, as in the other modules.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt.yml"))
    parallel = true
}

dependencies {
    api(project(":even-g2-sdk"))
}

// ktlint formatting and checks for the hand-written Kotlin in this module.
ktlint {
    version.set("1.8.0")
}
