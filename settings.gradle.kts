pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
