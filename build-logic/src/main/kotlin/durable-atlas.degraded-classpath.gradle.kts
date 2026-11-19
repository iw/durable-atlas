import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion

// Resolves the fixed compile classpath (the Temporal SDK, its Kotlin extensions and the Kotlin
// standard library, with their compile-time dependencies) that stands in for a repository's own
// classpath when its build cannot be queried. The distribution ships these jars; tests receive
// them as a system property. Everything here comes from Maven Central.

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

val degradedClasspath = configurations.create("degradedClasspath") {
    description = "Jars used as the classpath of a model built without the analysed repository's Gradle build."
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
}

dependencies {
    degradedClasspath(libs.findLibrary("temporal-sdk").get())
    degradedClasspath(libs.findLibrary("temporal-kotlin").get())
    degradedClasspath(libs.findLibrary("kotlin-stdlib").get())
}
