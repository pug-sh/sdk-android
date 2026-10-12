import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.process.CommandLineArgumentProvider

// Fails on SDK code that nothing in the public API reaches: R8 shrinks the release AAR with only
// pug/api/pug.api kept and reports what it removed. Not part of the SDK.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

application {
    mainClass = "sh.pug.deadcode.MainKt"
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

val sdkRuntime = configurations.create("sdkRuntime")

dependencies {
    implementation(libs.r8)
    sdkRuntime(libs.kotlinx.coroutines.core)
}

evaluationDependsOn(":pug")
val bootClasspath =
    project(":pug").extensions.getByType<LibraryAndroidComponentsExtension>().sdkComponents.bootClasspath

val aar = rootProject.layout.projectDirectory.file("pug/build/outputs/aar/pug-release.aar").asFile
val api = rootProject.layout.projectDirectory.file("pug/api/pug.api").asFile
val allowed = layout.projectDirectory.file("allowed.txt").asFile

tasks.register<JavaExec>("deadCodeCheck") {
    description = "Fails when the release AAR holds code that the public API in pug/api/pug.api cannot reach."
    dependsOn(":pug:bundleReleaseAar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    args(aar.path, api.path, allowed.path)
    argumentProviders.add(objects.newInstance<LibraryJars>().apply { files.from(bootClasspath, sdkRuntime) })
}

abstract class LibraryJars : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val files: ConfigurableFileCollection

    override fun asArguments(): List<String> = files.map { it.path }
}
