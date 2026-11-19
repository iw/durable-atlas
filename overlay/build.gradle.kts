plugins {
    id("durable-atlas.kotlin-jvm")
    id("durable-atlas.kotlin-serialization")
    `java-test-fixtures`
}

dependencies {
    // Workflow IR types are part of this module's public API.
    api(project(":ir"))

    // Coverage and execution diagrams are drawn by the renderers, whose formats callers choose.
    api(project(":render"))

    // Event histories are read with the Temporal SDK's own types. Reading them from JSON needs a
    // library the SDK does not bring along.
    implementation(libs.temporal.sdk)
    runtimeOnly(libs.json.path)

    // Histories are fetched with the SDK's client. Where the server is and how to sign in is read
    // from the environment and from Temporal's configuration file the way the SDK reads them.
    implementation(libs.temporal.envconfig)

    // A stand-in for a Temporal server, for the tests of this module and of the command line.
    testFixturesApi(libs.temporal.sdk)

    testImplementation(testFixtures(project(":ir")))
    testImplementation(libs.batik.transcoder)
}

tasks.test {
    // The tests lay the histories of the sample repository and of the demo project over the
    // documents the analyser's tests expect, and leave pictures of the diagrams for a person to
    // review.
    inputs.files(
        fileTree(layout.settingsDirectory.dir("analyser/src/test/resources/golden/ir")) { include("*.ir.json", "ends/*.ir.json", "exits/*.ir.json", "constructs/*.ir.json", "patterns/*.ir.json", "silent/*.ir.json", "open/*.ir.json", "options/*.ir.json", "calls/*.ir.json", "stepfree/*.ir.json", "failures/*.ir.json") },
        fileTree(layout.settingsDirectory.dir("fixtures/sample-monorepo/histories")) { include("*.json") },
        fileTree(layout.settingsDirectory.dir("demo")) { include("expected/*.ir.json", "histories/*.json") },
    )
        .withPropertyName("documentsAndHistories")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("overlay-review")).withPropertyName("reviewPictures")
}
