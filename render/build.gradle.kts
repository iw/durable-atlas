plugins {
    id("durable-atlas.kotlin-jvm")
    id("durable-atlas.kotlin-serialization")
}

dependencies {
    // Workflow IR types are part of this module's public API.
    api(project(":ir"))

    // Layout of the SVG diagrams. Guava is declared here with a fixed version, because the layout
    // engine names it as an open range.
    implementation(libs.elk.layered) {
        exclude(group = "com.google.guava", module = "guava")
    }
    runtimeOnly(libs.xbase.lib) {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation(libs.guava)

    // The font SVG text is measured with, and which a diagram can embed. Only the font files and
    // their licence are used, so the look-and-feel library the artifact is made for stays out.
    implementation(libs.inter.font) {
        isTransitive = false
    }

    testImplementation(testFixtures(project(":ir")))
    testImplementation(libs.batik.transcoder)
    // The JSON Schema of OpenAPI 3.1, from the files of a library that validates against it.
    testImplementation(libs.vertx.openapi) {
        isTransitive = false
    }
}

tasks.test {
    // The SVG tests also draw the documents of the demo project and those the analyser's tests
    // expect, and leave pictures of the diagrams for a person to review.
    inputs.files(
        fileTree(layout.settingsDirectory.dir("demo/expected")) { include("*.ir.json") },
        fileTree(layout.settingsDirectory.dir("analyser/src/test/resources/golden/ir")) { include("**/*.ir.json") },
        fileTree(layout.settingsDirectory.dir("analyser/src/test/resources/golden/overview")) { include("**/*.ir.json") },
        fileTree(layout.settingsDirectory.dir("analyser/src/test/resources/golden/inventory")) { include("**/*.json") },
    )
        .withPropertyName("documentsOfOtherModules")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.buildDirectory.dir("svg-review")).withPropertyName("reviewPictures")
}
