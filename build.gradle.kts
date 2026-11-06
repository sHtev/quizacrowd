plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.jib) apply false
}

// Shared settings for every Kotlin module: JDK 25 toolchain and JUnit 5.
subprojects {
    group = "dev.shtev.quizacrowd"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
            "testImplementation"(libs.findLibrary("junit-jupiter").get())
            "testImplementation"(kotlin("test"))
            "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Netty loads a native library; JDK 25 warns unless native access is allowed.
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
}

// Container images for every service (anything with the application plugin), built with Jib: no Dockerfile.
// `./gradlew jibDockerBuild` builds into the local Docker; CI pushes with `./gradlew jib -PimageTag=<sha>`.
subprojects {
    plugins.withId("application") {
        apply(plugin = "com.google.cloud.tools.jib")
        extensions.configure<com.google.cloud.tools.jib.gradle.JibExtension> {
            from {
                image = "eclipse-temurin:25-jre"
                platforms {
                    platform { architecture = "amd64"; os = "linux" }
                    platform { architecture = "arm64"; os = "linux" }
                }
            }
            to {
                image = "ghcr.io/shtev/quizacrowd-${project.name}"
                tags = setOf(providers.gradleProperty("imageTag").getOrElse("local"))
            }
            container {
                // Heap sized from the pod's memory limit; Netty needs native access on JDK 25.
                jvmFlags = listOf("-XX:MaxRAMPercentage=75", "--enable-native-access=ALL-UNNAMED")
                ports = listOf("8080")
                user = "65532" // non-root
            }
        }
        // Jib reads the project while its tasks run, which the configuration cache forbids; those runs skip the cache.
        tasks.matching { it.name.startsWith("jib") }.configureEach {
            notCompatibleWithConfigurationCache("Jib reads the Gradle project at execution time")
        }
    }
}
