plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.shtev.quizacrowd.scorer.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.kafka.clients)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.logback)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgres)
    // quiz-api owns the schema; its db/migration scripts come with it.
    testImplementation(project(":services:quiz-api"))
}
