plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.nexus.sdk)
    // The Nexus SDK marks nullable types with these annotations but does not bring them along.
    compileOnly(libs.jspecify)
}
