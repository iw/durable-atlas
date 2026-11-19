plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":loyalty"))
    implementation(libs.temporal.sdk)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.temporal.testing)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

// Histories captured by the tests: always written under build/, and copied to the
// checked-in directory only when run with -Ddurable-atlas.updateHistories=true.
val capturedHistories = layout.buildDirectory.dir("histories")
val checkedInHistories = rootProject.layout.projectDirectory.dir("histories")
val updateHistories = providers.systemProperty("durable-atlas.updateHistories").orElse("false")

tasks.test {
    useJUnitPlatform()
    inputs.files(fileTree(checkedInHistories)).withPropertyName("checkedInHistories")
    outputs.dir(capturedHistories).withPropertyName("capturedHistories")
    outputs.upToDateWhen { updateHistories.get() != "true" }
    // A workflow that cannot make progress should fail its test, not hang the build.
    systemProperty("junit.jupiter.execution.timeout.default", "60 s")
    systemProperty("durable-atlas.historiesOut", capturedHistories.get().asFile.path)
    systemProperty("durable-atlas.historiesCheckedIn", checkedInHistories.asFile.path)
    systemProperty("durable-atlas.updateHistories", updateHistories.get())
}
