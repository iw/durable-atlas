package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.ClassKind
import io.github.iw.durableatlas.analyser.code.SourceAnalysisException
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.testing.SampleModels
import io.github.iw.durableatlas.analyser.testing.SampleSources
import io.github.iw.durableatlas.modelextractor.ExtractedModel
import io.github.iw.durableatlas.modelextractor.ModelJson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening sources: module structure, the JDK, things that are missing, and isolation of the backend. */
class SourceBackendsTest {
    @TempDir
    lateinit var dir: Path

    private val log = mutableListOf<String>()

    private fun warnings(): List<String> = log.filter { it.startsWith("warning") }

    @Test
    fun `a module resolves against the modules it depends on and not against the others`() {
        val sources = SyntheticSources(dir)
            .module(":lib")
            .module(":app", ":lib")
            .module(":island")
            .file(
                ":lib", "lib/Greeter.kt",
                """
                package lib

                interface Greeter {
                    fun greet(name: String): String
                }

                fun shout(text: String): String = text.uppercase()
                """,
            )
            .file(
                ":app", "app/Polite.kt",
                """
                package app

                import lib.Greeter
                import lib.shout

                class Polite : Greeter {
                    override fun greet(name: String): String = shout("Hello, ${'$'}name")
                }
                """,
            )
            .file(
                ":island", "island/Alone.kt",
                """
                package island

                import lib.shout

                class Alone {
                    fun call(): String = shout("nobody hears this")
                }
                """,
            )

        sources.open(log::add).use { backend ->
            assertEquals(
                listOf("app.Polite" to ":app", "island.Alone" to ":island", "lib.Greeter" to ":lib"),
                backend.sourceClasses().map { it.name to it.module },
            )
            assertEquals(listOf("lib.Greeter"), backend.findClass("app.Polite")?.allSuperTypes)

            val greet = backend.functions("app.Polite").single()
            assertEquals(listOf("lib.Greeter.greet(kotlin.String)"), greet.overrides.map { it.toString() })
            val shout = assertNotNull(backend.body(greet.id)).walk().filterIsInstance<Call>().single().callee
            assertEquals("lib.shout", shout?.function?.name)
            assertEquals(":lib", backend.function(assertNotNull(shout).function)?.module)

            // The same call from a module that does not depend on :lib does not resolve.
            val unheard = backend.functions("island.Alone").single()
            val unresolved = assertNotNull(backend.body(unheard.id)).walk().filterIsInstance<Call>().single()
            assertEquals("shout", unresolved.name)
            assertNull(unresolved.callee)
        }
        assertEquals(emptyList(), warnings())
        assertContains(log.joinToString("\n"), "analysing 3 Kotlin file(s) in 3 module(s)")
    }

    @Test
    fun `dependency cycles and dependencies the model does not describe are reported and survived`() {
        val sources = SyntheticSources(dir)
            .module(":a", ":b", ":gone")
            .module(":b", ":a")
            .file(":a", "a/A.kt", "package a\n\nclass A")
            .file(":b", "b/B.kt", "package b\n\nclass B : a.A()")

        sources.open(log::add).use { backend ->
            assertEquals(listOf("a.A", "b.B"), backend.sourceClasses().map { it.name })
        }
        assertEquals(2, warnings().size, log.toString())
        assertContains(warnings().joinToString("\n"), "module :a depends on :gone, which the model does not describe")
        assertContains(warnings().joinToString("\n"), "form a cycle")
    }

    @Test
    fun `jars that have gone missing since the model was extracted are reported, and analysis goes on`() {
        val model = SyntheticSources(dir)
            .file(
                ":app", "app/Flow.kt",
                """
                package app

                import io.temporal.workflow.WorkflowInterface
                import io.temporal.workflow.WorkflowMethod

                @WorkflowInterface
                interface Flow {
                    @WorkflowMethod
                    fun run()
                }
                """,
            )
            .model()
        // The model now names a jar that is not there.
        val manifestFile = dir.resolve(model.index.modules.single().manifest)
        val manifest = model.manifests.single()
        Files.writeString(
            manifestFile,
            ModelJson.encode(manifest.copy(jars = manifest.jars + dir.resolve("missing.jar").toString())),
        )

        SourceBackends.open(ExtractedModel.read(dir), log = log::add).use { backend ->
            assertEquals(listOf("Flow"), TemporalDiscovery.discover(backend).workflows.map { it.name })
        }
        assertEquals(1, warnings().size, log.toString())
        assertContains(warnings().single(), "1 jar(s) named by the model no longer exist")
        assertContains(warnings().single(), "missing.jar")
    }

    @Test
    fun `without the SDK on the classpath its annotations do not resolve and nothing is discovered`() {
        val model = SyntheticSources(dir)
            .file(
                ":app", "app/Flow.kt",
                """
                package app

                import io.temporal.workflow.WorkflowInterface
                import io.temporal.workflow.WorkflowMethod

                @WorkflowInterface
                interface Flow {
                    @WorkflowMethod
                    fun run()
                }
                """,
            )
            .model()
        val manifest = model.manifests.single()
        Files.writeString(
            dir.resolve(model.index.modules.single().manifest),
            ModelJson.encode(manifest.copy(jars = manifest.jars.filter { "kotlin-stdlib" in it })),
        )

        SourceBackends.open(ExtractedModel.read(dir), log = log::add).use { backend ->
            val discovery = TemporalDiscovery.discover(backend)
            assertEquals(emptyList(), discovery.workflows)
            // The interface is there; its annotation has no type to be recognised by.
            assertEquals(emptyList(), backend.findClass("app.Flow")?.annotations)
        }
        assertEquals(1, warnings().size, log.toString())
        assertContains(warnings().single(), "1 annotation(s) in the sources did not resolve")
        assertContains(warnings().single(), "@WorkflowInterface at app/src/main/kotlin/app/Flow.kt:6")
    }

    @Test
    fun `the JDK to resolve against can be named, and must be one`() {
        val jdkHome = Path.of(System.getProperty("java.home"))

        SourceBackends.open(SampleModels.degraded, jdkHome, log::add).use { backend ->
            assertNotNull(backend.findClass("java.time.Duration"))
            assertEquals("java.lang.Object", backend.findClass("java.lang.Object")?.name)
        }
        assertContains(log.joinToString("\n"), "the JDK at $jdkHome")

        val failure = assertFailsWith<SourceAnalysisException> { SourceBackends.open(SampleModels.degraded, dir) }
        assertContains(failure.message.orEmpty(), "is not the home directory of a JDK")
    }

    @Test
    fun `classes from libraries are described, with their members`() {
        val backend = SampleSources.degraded

        val promise = assertNotNull(backend.findClass("io.temporal.workflow.Promise"))
        assertEquals(null, promise.module)
        assertEquals(null, promise.location)
        assertTrue(backend.functions("io.temporal.workflow.Promise").any { it.name == "get" && it.parameters.isEmpty() })

        val signalMethod = assertNotNull(backend.findClass("io.temporal.workflow.SignalMethod"))
        assertEquals(ClassKind.ANNOTATION, signalMethod.kind)
        assertEquals(listOf("description", "name", "unfinishedPolicy"), backend.properties("io.temporal.workflow.SignalMethod").map { it.name })

        // Annotations of compiled classes come with their arguments, on classes and on functions.
        val arrayDeque = assertNotNull(backend.findClass("kotlin.collections.ArrayDeque"))
        assertEquals("1.4", arrayDeque.annotations.find("kotlin.SinceKotlin")?.text("version"))
        assertContains(arrayDeque.allSuperTypes, "kotlin.collections.MutableList")
        val deprecated = backend.functions("io.temporal.activity.ActivityOptions.Builder")
            .single { it.name == "setVersioningIntent" }
        assertEquals("Deprecated in Java", deprecated.annotations.find("kotlin.Deprecated")?.text("message"))
        // A nested class is found although its name does not say where the package ends.
        assertEquals("Builder", backend.findClass("io.temporal.activity.ActivityOptions.Builder")?.simpleName)
        assertNull(backend.findClass("io.temporal.workflow.NoSuchClass"))
        assertNull(backend.findClass("not a class name"))
        assertEquals(emptyList(), backend.functions("io.temporal.workflow.NoSuchClass"))
    }

    @Test
    fun `the analysis backend and its libraries are not on the classpath of the tool`() {
        val names = listOf(
            "io.github.iw.durableatlas.analyser.backend.KotlinBackendFactory",
            "org.jetbrains.kotlin.analysis.api.KaSession",
            "com.intellij.openapi.Disposable",
            "com.google.common.collect.ImmutableList",
            "kotlinx.coroutines.Job",
            "io.opentelemetry.api.OpenTelemetry",
        )
        for (name in names) {
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }
}
