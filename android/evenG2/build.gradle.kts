plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.fghbuild.eveng2"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Dependency and SDK freshness belong to `make upgrade`, as in the app and fixture modules.
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
    // Coroutines: the session exposes StateFlow and suspends on BLE operations.
    api(libs.kotlinx.coroutines.core)

    // The protocol and session tests are plain JVM tests; no Android class reaches them.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Reads the enterprise SDK's contract.json wire vectors.
    testImplementation(libs.kotlinx.serialization.json)
}

// ktlint formatting and checks for the hand-written Kotlin in this module.
ktlint {
    version.set("1.8.0")
}
