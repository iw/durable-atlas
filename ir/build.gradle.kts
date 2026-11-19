plugins {
    id("durable-atlas.kotlin-jvm")
    id("durable-atlas.kotlin-serialization")
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testFixturesImplementation(kotlin("test"))
    testFixturesImplementation(libs.json.schema.validator)
}

tasks.test {
    inputs.files(fileTree(layout.settingsDirectory.dir("demo")) { include("expected/**", "**/src/main/kotlin/**") })
        .withPropertyName("demoFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
