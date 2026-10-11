import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// An app that uses the SDK as a host app does, from Kotlin and Java. Its release build is minified by
// R8 with no keep rules for the SDK, which `make check` builds.
plugins {
    alias(libs.plugins.android.application)
}

// Set in ~/.gradle/gradle.properties to send to a real project; without an API key the sample runs
// in dryRun and only logs.
val pugProjectId = providers.gradleProperty("pug.sample.projectId").getOrElse("sample-project")
val pugApiKey = providers.gradleProperty("pug.sample.apiKey").getOrElse("")
val pugEndpoint = providers.gradleProperty("pug.sample.endpoint").getOrElse("https://api.pugs.dev")

fun javaString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "sh.pug.android.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "sh.pug.android.sample"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "PUG_PROJECT_ID", javaString(pugProjectId))
        buildConfigField("String", "PUG_API_KEY", javaString(pugApiKey))
        buildConfigField("String", "PUG_ENDPOINT", javaString(pugEndpoint))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The debug key, so that the minified build can be installed and tried.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":pug"))
}
