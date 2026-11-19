plugins {
    id("durable-atlas.kotlin-jvm")
    id("durable-atlas.kotlin-serialization")
    id("durable-atlas.integration-test")
    id("durable-atlas.analysis-classpath")
    `java-test-fixtures`
}

// Everything that touches the Kotlin Analysis API lives in the `backend` source set. It is compiled
// against that API, packaged in a jar of its own and loaded at run time by a separate class loader,
// so the API's dependencies stay off the classpath of this module and of everything that uses it.
val backend: SourceSet = sourceSets.create("backend")

kotlin.target.compilations.named("backend") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

/** The Analysis API and the libraries it needs at run time. */
val analysisApi = configurations.dependencyScope("analysisApi")

configurations.named(backend.compileOnlyConfigurationName) {
    extendsFrom(analysisApi.get())
}
configurations.named("analysisClasspath") {
    extendsFrom(analysisApi.get())
}

dependencies {
    api(project(":ir"))
    api(project(":model-extractor"))

    // These jars embed their dependencies; the dependencies their metadata declares do not exist.
    analysisApi(libs.bundles.kotlin.analysis.api) { isTransitive = false }
    analysisApi(libs.bundles.intellij.platform)
    analysisApi(libs.caffeine)
    analysisApi(libs.kotlinx.collections.immutable)

    analysisClasspath(backend.output)

    testImplementation(testFixtures(project(":ir")))
    // Tests of a Gradle-extracted model check the same things as tests of a degraded one.
    "integrationTestImplementation"(sourceSets.test.get().output)
    testFixturesApi(testFixtures(project(":model-extractor")))
}

// The demo project is analysed by tests that compare the result with the IR written out for it by
// hand, so its files are inputs of those tests.
val demoProject = fileTree(layout.settingsDirectory.dir("demo")) {
    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
}

tasks.withType<Test>().configureEach {
    inputs.files(demoProject).withPropertyName("demoProject").withPathSensitivity(PathSensitivity.RELATIVE)
}

val backendJar = tasks.register<Jar>("backendJar") {
    description = "Assembles the analysis backend, which is loaded separately from the rest of the module."
    group = LifecycleBasePlugin.BUILD_GROUP
    archiveClassifier = "backend"
    from(backend.output)
}

// What a distribution needs in order to run the analysis: the backend and its run-time classpath.
configurations.consumable("analysisBackendElements") {
    extendsFrom(analysisApi.get())
    outgoing.artifact(backendJar)
}
