import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    signing
}

android {
    namespace = "sh.pug.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Robolectric's Android 16 runtime reaches into the JDK's FileDescriptor internals.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Dependency versions are deliberate floors (PLAN.md D2/D3), and these checks query the
        // network, which would make CI results depend on the day it runs.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin {
    explicitApi()
    coreLibrariesVersion = libs.versions.kotlinStdlib.get()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
        allWarningsAsErrors = true
        // A Java caller passing null for a non-null parameter must get a logged no-op, not Kotlin's
        // entry-point NullPointerException (PLAN.md D20).
        freeCompilerArgs.add("-Xno-param-assertions")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(libs.protobuf.java)
    testImplementation(libs.robolectric)
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

// The Central Portal takes a zip of a Maven repository holding the signed publication.
val centralRepository = layout.buildDirectory.dir("central/repository")

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("GROUP").get()
            artifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
            pom {
                name = "Pug Android SDK"
                description =
                    "Pug analytics for Android: event tracking, identity, sessions, consent and batched delivery."
                url = "https://github.com/pug-sh/sdk-android"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/license/mit"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "pug-sh"
                        name = "pug.sh"
                        url = "https://pug.sh"
                    }
                }
                scm {
                    url = "https://github.com/pug-sh/sdk-android"
                    connection = "scm:git:https://github.com/pug-sh/sdk-android.git"
                    developerConnection = "scm:git:ssh://git@github.com/pug-sh/sdk-android.git"
                }
                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/pug-sh/sdk-android/issues"
                }
            }
        }
    }
    repositories {
        maven {
            name = "central"
            url = uri(centralRepository)
        }
    }
}

// From ORG_GRADLE_PROJECT_signingInMemoryKey and ORG_GRADLE_PROJECT_signingInMemoryKeyPassword, so
// the key never sits in a file. Without it nothing is signed, and centralBundle refuses to run.
val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
signing {
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingInMemoryKeyPassword").orNull)
    }
    isRequired = signingKey != null
    sign(publishing.publications["release"])
}

val cleanCentralRepository =
    tasks.register<Delete>("cleanCentralRepository") {
        delete(centralRepository)
    }

tasks.matching { it.name == "publishReleasePublicationToCentralRepository" }.configureEach {
    dependsOn(cleanCentralRepository)
}

tasks.register<Zip>("centralBundle") {
    description = "Zips the signed release publication into a Maven Central Portal bundle."
    dependsOn("publishReleasePublicationToCentralRepository")
    from(centralRepository)
    exclude("**/maven-metadata*")
    destinationDirectory = layout.buildDirectory.dir("central")
    archiveFileName = "pug-android-$sdkVersion.zip"
    val repository = centralRepository
    doFirst {
        val unsigned =
            repository.get().asFileTree.files.filter { file ->
                val name = file.name
                !name.startsWith("maven-metadata") &&
                    listOf(".asc", ".md5", ".sha1", ".sha256", ".sha512").none { name.endsWith(it) } &&
                    !File(file.path + ".asc").isFile
            }
        check(unsigned.isEmpty()) {
            "Unsigned artifacts: ${unsigned.joinToString { it.name }}. Set ORG_GRADLE_PROJECT_signingInMemoryKey " +
                "and ORG_GRADLE_PROJECT_signingInMemoryKeyPassword (docs/development.md, Releasing)"
        }
    }
}
