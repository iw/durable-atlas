import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared configuration for every Kotlin/JVM module of the tool.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "io.github.iw.durableatlas"

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The tool is built by whichever JDK runs Gradle (17 or later) and runs on Java 17 or later: class
// files are made for Java 17, against the Java 17 API.
val javaRelease = 17

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        jvmTarget = JvmTarget.fromTarget(javaRelease.toString())
        freeCompilerArgs.add("-Xjdk-release=$javaRelease")
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(javaRelease)
    targetCompatibility = JavaVersion.toVersion(javaRelease)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
}

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Golden files are compared byte for byte unless this flag asks for them to be rewritten.
    systemProperty(
        "durable-atlas.updateGolden",
        providers.systemProperty("durable-atlas.updateGolden").getOrElse("false"),
    )
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
