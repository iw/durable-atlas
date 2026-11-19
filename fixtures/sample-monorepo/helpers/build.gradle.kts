plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":activities"))
    implementation(libs.temporal.sdk)
}
