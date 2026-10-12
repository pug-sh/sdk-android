plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.spotless)
}

// Mirrors .editorconfig, which ktlint under Spotless does not pick up on its own.
val ktlintStyle =
    mapOf(
        "ktlint_code_style" to "android_studio",
        "max_line_length" to "120",
        "ij_kotlin_allow_trailing_comma" to "true",
        "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
    )

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintStyle)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintStyle)
    }
}

val detektCli = configurations.create("detektCli")

dependencies {
    detektCli(libs.detekt.cli)
}

val detektSources = listOf("pug/src", "sample/src", "tools/abi-dump/src", "tools/gen-track/src")
val detektConfig = layout.projectDirectory.file("config/detekt.yml")
val detektReport = layout.buildDirectory.file("reports/detekt.txt")

tasks.register<JavaExec>("detekt") {
    description = "Fails on unused code that detekt can see within a file: imports, parameters, private declarations."
    classpath = detektCli
    mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
    inputs.files(detektSources.map { fileTree(it) { include("**/*.kt", "**/*.kts") } })
    inputs.file(detektConfig)
    outputs.file(detektReport)
    args(
        "--input",
        detektSources.joinToString(","),
        "--config",
        detektConfig.asFile.path,
        "--report",
        "txt:${detektReport.get().asFile.path}",
    )
}
