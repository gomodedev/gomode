plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
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

    // Robolectric reads the merged manifest, so the GATT test runs on the module's compileSdk.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // FakeG2Firmware for this module's tests and its hosts' unit and instrumented tests.
    testFixtures {
        enable = true
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
        allWarningsAsErrors = true
        freeCompilerArgs.add("-Wextra")
    }
}

// Robolectric resolves the Android runtime jar for its tests by downloading it from Maven Central
// while the unit tests run, so a repository hiccup aborts a test class mid-build. Resolve the same
// artifact through Gradle and point Robolectric's offline resolver at it instead: the jar is
// checksum-verified and cached with the rest of the build, and the tests need no network access.
val robolectricSdkJar =
    configurations.create("robolectricSdkJar") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

val robolectricSdkJarDir = layout.buildDirectory.dir("robolectric-sdk-jars")

val stageRobolectricSdkJar =
    tasks.register<Sync>("stageRobolectricSdkJar") {
        from(robolectricSdkJar)
        into(robolectricSdkJarDir)
    }

tasks.withType<Test>().configureEach {
    dependsOn(stageRobolectricSdkJar)
    systemProperty("robolectric.dependency.dir", robolectricSdkJarDir.get().asFile.absolutePath)
}

dependencies {
    // Coroutines: the session exposes StateFlow and suspends on BLE operations.
    api(libs.kotlinx.coroutines.core)
    // Reads the firmware font table that G2TextLayout measures text with.
    implementation(libs.kotlinx.serialization.json)

    // The protocol and session tests are plain JVM tests; the GATT transport test runs on Robolectric's
    // Bluetooth shadows.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    add(robolectricSdkJar.name, libs.robolectric.android.all)
}

// ktlint formatting and checks for the hand-written Kotlin in this module.
ktlint {
    version.set("1.8.0")
}
