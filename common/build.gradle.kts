plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.serialization.json)
    api(libs.lettuce)
    implementation(libs.coroutines.core)
    implementation(libs.java.jwt)
    implementation(libs.commons.text)
}
