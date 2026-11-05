plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.shtev.quizacrowd.ingest.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.lettuce)
    implementation(libs.kafka.clients)
    implementation(libs.logback)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.redpanda)
}
