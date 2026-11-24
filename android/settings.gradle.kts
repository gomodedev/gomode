pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

rootProject.name = "gomode"
include(":gomode")
include(":gomode-testing")
project(":gomode-testing").projectDir = file("gomode/testing")
include(":car-dial-fixture")
include(":voicegateway-sdk")
project(":voicegateway-sdk").projectDir = file("../sdk/voicegateway/kotlin")
include(":gomode-sdk")
project(":gomode-sdk").projectDir = file("../sdk/gomode/kotlin")
include(":mcp-sdk")
project(":mcp-sdk").projectDir = file("../sdk/mcp/kotlin")
include(":oauth-sdk")
project(":oauth-sdk").projectDir = file("../sdk/oauth/kotlin")
include(":halo-sdk")
project(":halo-sdk").projectDir = file("halo")
include(":halo-testing")
project(":halo-testing").projectDir = file("halo/testing")
include(":even-g2-sdk")
project(":even-g2-sdk").projectDir = file("evenG2")
include(":even-g2-testing")
project(":even-g2-testing").projectDir = file("evenG2/testing")
