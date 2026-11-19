plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":activities"))
    implementation(project(":helpers"))
    implementation(project(":nexus-api"))
    implementation(libs.temporal.sdk)
    // Kotlin support for the SDK. Without it the SDK rejects, at run time, a Kotlin reference to a
    // stub's method handed to Async or Saga (see MethodReferenceWorkflow).
    implementation(libs.temporal.kotlin)

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

// Runs the workflows on a Temporal server instead of the test environment; see worker/ServerRun.kt
// for the arguments. Not part of the build.
tasks.register<JavaExec>("runOnServer") {
    group = "application"
    description = "Runs the fixture's workflows on the Temporal server at TEMPORAL_ADDRESS."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.example.fixture.workflows.worker.ServerRunKt"
}
