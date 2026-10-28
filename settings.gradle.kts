pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Provisions the JDK that gradle/gradle-daemon-jvm.properties asks for.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sdk-android"

include(":pug")
include(":sample")
include(":tools:abi-dump")
include(":tools:gen-track")
include(":tools:dead-code")
