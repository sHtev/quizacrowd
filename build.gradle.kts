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
        }
    }
}
