plugins {
    id("durable-atlas.kotlin-jvm")
    id("durable-atlas.kotlin-serialization")
    id("durable-atlas.integration-test")
    `java-test-fixtures`
}

dependencies {
    api(project(":ir"))

    testImplementation(testFixtures(project(":ir")))
    testFixturesImplementation(kotlin("test"))
}
