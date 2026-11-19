import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.process.CommandLineArgumentProvider

// For modules that run the Kotlin source analysis in their tests or ship it in a distribution.
//
// The analysis backend and the Kotlin Analysis API it is built on are loaded at run time by a class
// loader of their own, so that the many libraries the API drags in (an IntelliJ platform core, a
// fork of kotlinx.coroutines, Guava, OpenTelemetry, a logging bridge) never share a classpath with
// the rest of the tool. `analysisClasspath` resolves the files that class loader is given; what
// goes into it is declared by the module that applies this plugin. Test tasks receive the
// location of those files as a system property.

val analysisClasspath = configurations.create("analysisClasspath") {
    description = "The analysis backend and everything it needs at run time, loaded in isolation from the tool."
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
    // The backend shares the Kotlin standard library with the tool: its own types cross the
    // boundary between the two class loaders, so both sides must see the same classes.
    for (module in listOf("kotlin-stdlib", "kotlin-stdlib-common", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")) {
        exclude(group = "org.jetbrains.kotlin", module = module)
    }
}

/** Passes the files to the test JVM without making their absolute paths part of the cache key. */
abstract class AnalysisClasspathArguments : CommandLineArgumentProvider {
    @get:Classpath
    abstract val analysisClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("-Ddurable-atlas.analysisClasspath=${analysisClasspath.asPath}")
}

tasks.withType<Test>().configureEach {
    // An analysis holds the indexes of the JDK and of every jar on the analysed classpath, and
    // several of them are open at a time while tests run.
    maxHeapSize = "1g"
    jvmArgumentProviders.add(
        objects.newInstance<AnalysisClasspathArguments>().apply {
            analysisClasspath.from(configurations.named("analysisClasspath"))
        },
    )
}
