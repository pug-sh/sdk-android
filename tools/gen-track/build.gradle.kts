// The typed-track generator (PLAN.md D19), run by `make typed-track`. Not part of the SDK.
plugins {
    // AGP's built-in Kotlin puts this plugin on the build classpath, at the version AGP was built with.
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass = "sh.pug.gentrack.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.org.json)
}
