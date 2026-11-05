plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.shtev.quizacrowd.quizapi.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.zxing.core)
    implementation(libs.lettuce)
    implementation(libs.logback)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}
