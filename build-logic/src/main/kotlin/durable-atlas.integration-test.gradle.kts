import org.gradle.process.CommandLineArgumentProvider

// For modules whose tests extract models from the sample repository under `fixtures/`.
//
// Adds an `integrationTest` source set and task for the tests that run that repository's own Gradle
// build, so the quick tests in `test` stay quick; `check` runs both. Every test task is told where
// the sample repository and the degraded-mode classpath are.

plugins {
    id("durable-atlas.kotlin-jvm")
    id("durable-atlas.degraded-classpath")
}

/** Passes file locations to the test JVM without making their absolute paths part of the cache key. */
abstract class SampleRepositoryArguments : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sampleRepositoryFiles: ConfigurableFileCollection

    @get:Internal
    abstract val sampleRepository: DirectoryProperty

    @get:Classpath
    abstract val degradedClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf(
        "-Ddurable-atlas.fixtureDir=${sampleRepository.get().asFile.path}",
        "-Ddurable-atlas.degradedClasspath=${degradedClasspath.asPath}",
    )
}

val sampleRepositoryDir = layout.settingsDirectory.dir("fixtures/sample-monorepo")

val integrationTestSourceSet = sourceSets.create("integrationTest")

configurations.named(integrationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named(integrationTestSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the tests that drive the sample repository's Gradle build."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.withType<Test>().configureEach {
    // The sample repository's build is started from the tests; it runs on the JDK the tests run on.
    val launcher = javaLauncher
    doFirst {
        environment("JAVA_HOME", launcher.get().metadata.installationPath.asFile.path)
    }
    jvmArgumentProviders.add(
        objects.newInstance<SampleRepositoryArguments>().apply {
            sampleRepository.set(sampleRepositoryDir)
            sampleRepositoryFiles.from(
                fileTree(sampleRepositoryDir) {
                    exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**")
                },
            )
            degradedClasspath.from(configurations.named("degradedClasspath"))
        },
    )
}
