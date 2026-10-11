// The SDK's public API dump, pug/api/pug.api: `make abi-dump` writes it, `make check` holds the AAR
// to it. Not part of the SDK.
plugins {
    // AGP's built-in Kotlin puts this plugin on the build classpath, at the version AGP was built with.
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass = "sh.pug.abidump.MainKt"
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.abi.tools)
}

// The Kotlin Gradle plugin's own ABI validation and BCV both look for the kotlin-android plugin, which
// AGP's built-in Kotlin does not apply, so this runs the same dump over the published AAR instead.
val aar = rootProject.layout.projectDirectory.file("pug/build/outputs/aar/pug-release.aar").asFile
val dump = rootProject.layout.projectDirectory.file("pug/api/pug.api").asFile

tasks.register<JavaExec>("abiDump") {
    description = "Writes the public API of the release AAR to pug/api/pug.api."
    dependsOn(":pug:bundleReleaseAar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    args(aar.path, dump.path)
}

tasks.register<JavaExec>("abiCheck") {
    description = "Fails when the public API of the release AAR differs from pug/api/pug.api."
    dependsOn(":pug:bundleReleaseAar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    args("--check", aar.path, dump.path)
}
