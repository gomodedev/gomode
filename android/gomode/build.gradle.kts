// Gradle configuration for the Go Mode Android shell app.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.fghbuild.gomode"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.fghbuild.gomode"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Share the note score without bundling web source.
    sourceSets
        .getByName("main")
        .assets.directories
        .add(rootProject.file("../web/assets").path)
    sourceSets
        .getByName("test")
        .resources.directories
        .add(rootProject.file("../web/assets").path)
    sourceSets
        .getByName("test")
        .resources.directories
        .add(rootProject.file("../web/testdata/speech-text").path)
    sourceSets
        .getByName("test")
        .resources.directories
        .add(rootProject.file("../web/testdata/voice-choices").path)
    sourceSets
        .getByName("androidTest")
        .assets.directories
        .add(rootProject.file("../e2e/voice-preview/scene").path)

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // MemoryPreferences for this module's unit and instrumented tests.
    testFixtures {
        enable = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Go Mode hosts LAN/private backends during the WebView spike.
        disable +=
            setOf(
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
                "InsecureBaseConfiguration",
                "OldTargetApi",
            )
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
    // HaloEmulatorRenderingTest runs this bridge script, so a change to it or its lock reruns the tests.
    inputs
        .files(
            rootProject.file("../scripts/halo_emulator_bridge.py"),
            rootProject.file("../scripts/halo_emulator_bridge.py.lock"),
        ).withPropertyName("haloEmulatorBridge")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":halo-sdk"))
    implementation(project(":even-g2-sdk"))
    implementation(project(":gomode-sdk"))
    implementation(project(":mcp-sdk"))
    implementation(project(":voicegateway-sdk"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.json.schema.validator) {
        // MCP tool schemas and arguments are JSON, never YAML.
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    implementation(libs.okhttp)
    implementation(libs.stream.webrtc)
    // LiteRT's asynchronous JNI callbacks require its declared Kotlin/coroutine runtime ABI.
    implementation(libs.litertlm.android)

    testFixturesApi(libs.androidx.datastore.preferences)
    // The Compose compiler plugin compiles every Kotlin source set of this module, fixtures included,
    // and requires the Compose runtime on their classpath.
    testFixturesImplementation(platform(libs.androidx.compose.bom))
    testFixturesImplementation(libs.androidx.compose.runtime)
    testImplementation(testFixtures(project(":even-g2-sdk")))
    testImplementation(testFixtures(project(":halo-sdk")))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    add(robolectricSdkJar.name, libs.robolectric.android.all)
    androidTestImplementation(testFixtures(project(":even-g2-sdk")))
    androidTestImplementation(testFixtures(project(":halo-sdk")))
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ktlint formatting and checks for the hand-written Kotlin in this module.
ktlint {
    version.set("1.8.0")
}
