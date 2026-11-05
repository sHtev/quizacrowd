plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.shtev.quizacrowd.ingest.ApplicationKt")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback)
    testImplementation(libs.ktor.server.test.host)
}
