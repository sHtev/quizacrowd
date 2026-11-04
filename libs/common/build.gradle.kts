plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.serialization.json)
    implementation(libs.java.jwt)
    implementation(libs.commons.text)
}
