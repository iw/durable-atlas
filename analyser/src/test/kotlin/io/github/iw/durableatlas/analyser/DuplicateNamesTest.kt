package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Names that the sources declare more than once. Two modules of a build that do not see each
 * other may each declare a class, or a function, under the same fully qualified name, and a
 * degraded model puts the sources of all modules into one. Only one declaration is read under
 * a name, so code that may mean another must not be read with it: it is drawn as unresolved,
 * and what a constant of such a class holds is not known. Nothing is drawn that may be wrong.
 *
 * The same two sets of files are read twice: put into one module, as a degraded model has them,
 * and as two modules beside each other, as a model made from the build has them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateNamesTest {
    private lateinit var merged: SourceBackend
    private lateinit var apart: SourceBackend
    private lateinit var mergedDiscovery: Discovery
    private lateinit var apartDiscovery: Discovery
    private lateinit var one: List<IrDocument>
    private lateinit var two: List<IrDocument>
    private val mergedLog = ArrayList<String>()
    private val apartLog = ArrayList<String>()

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val single = SyntheticSources(directory.resolve("merged"))
            .file(":", "base/Plain.kt", PLAIN)
            .file(":", "a/Shared.kt", FIRST)
            .file(":", "b/Shared.kt", SECOND)
            .javaFile(":", "a/JavaLimits.java", javaLimits("a-java-queue"))
            .javaFile(":", "b/JavaLimits.java", javaLimits("b-java-queue"))
        val several = SyntheticSources(directory.resolve("apart"))
            .module(":a", ":base")
            .module(":b", ":base")
            .file(":base", "base/Plain.kt", PLAIN)
            .file(":a", "shared/Shared.kt", FIRST)
            .file(":b", "shared/Shared.kt", SECOND)
            .javaFile(":a", "shared/JavaLimits.java", javaLimits("a-java-queue"))
            .javaFile(":b", "shared/JavaLimits.java", javaLimits("b-java-queue"))
        merged = single.open(mergedLog::add)
        apart = several.open(apartLog::add)
        mergedDiscovery = TemporalDiscovery.discover(merged)
        apartDiscovery = TemporalDiscovery.discover(apart)
        one = WorkflowGraphs(merged, FIXED_COMMIT, ModelMode.DEGRADED).extract(mergedDiscovery)
        two = WorkflowGraphs(apart, FIXED_COMMIT, ModelMode.GRADLE).extract(apartDiscovery)
    }

    @AfterAll
    fun close() {
        merged.close()
        apart.close()
    }

    private fun List<IrDocument>.steps(workflowType: String): List<String> =
        single { it.workflowType == workflowType }.nodeOutline().filterNot { it.startsWith("start@") || it.startsWith("end(") }

    @Test
    fun `the names that are declared more than once are known, with the places of their declarations`() {
        val names = listOf("shared.Helper", "shared.JavaLimits", "shared.Jobs", "shared.Limits", "shared.Twice", "shared.TwiceImpl")
        assertEquals(names, merged.duplicateClasses().keys.toList())
        assertEquals(names, apart.duplicateClasses().keys.toList())
        assertEquals(
            listOf("root/src/main/kotlin/a/Shared.kt:13", "root/src/main/kotlin/b/Shared.kt:13"),
            merged.duplicateClasses().getValue("shared.Limits").map { "${it.file}:${it.line}" },
        )
        // A Java class is listed like a Kotlin one.
        assertEquals(
            listOf("a/src/main/java/shared/JavaLimits.java:3", "b/src/main/java/shared/JavaLimits.java:3"),
            apart.duplicateClasses().getValue("shared.JavaLimits").map { "${it.file}:${it.line}" },
        )
    }

    @Test
    fun `a warning says so, and names what is listed from one declaration alone`() {
        for (log in listOf(mergedLog, apartLog)) {
            val warning = log.single { "declared more than once" in it }
            assertContains(warning, "warning: 6 class name(s) are declared more than once in the sources, for example shared.Helper (")
            assertContains(warning, "only one declaration of each is read, and a call or a read that may mean another one is left unresolved")
        }
        val about = mergedDiscovery.warnings.filter { "declared more than once" in it }
        assertEquals(3, about.size, about.toString())
        assertContains(about[0], "shared.Twice is declared more than once (root/src/main/kotlin/a/Shared.kt:")
        assertContains(about[0], "workflow type Twice is listed and drawn from one of these alone")
        assertContains(about[1], "shared.TwiceImpl is declared more than once")
        assertContains(about[2], "shared.Jobs is declared more than once")
        assertContains(about[2], "its activity types are listed from one of these alone")
        assertEquals(3, apartDiscovery.warnings.count { "declared more than once" in it })
    }

    @Test
    fun `every document is well formed, a workflow that is declared twice is drawn once, and nothing is taken for a call into a library`() {
        val types = listOf("OnlyA", "OnlyB", "Twice")
        for (documents in listOf(one, two)) {
            assertEquals(types, documents.map { it.workflowType })
            documents.forEach { assertWellFormed(it) }
            // A call that may mean either declaration is a call of code of the sources: it is never left out.
            assertEquals(emptyList(), documents.flatMap { it.skippedCalls })
        }
        for (document in one) GoldenFiles.assertMatches("ir/twice/${document.workflowType}.ir.json", IrJson.encode(document))
    }

    @Test
    fun `in one module nothing tells which declaration is meant, so every use from outside the first is unresolved`() {
        // The stub's options name constants of classes that are declared twice: nothing is known of them.
        // The helper is declared twice, and so is the function of no class: one of the two holds a step.
        val expected = listOf(
            "activity@57: name One",
            // The options of this stub are a private property of the file, which is no name of two declarations.
            "activity@58: name Own, timeout PT5S",
            "unknown@57#1: label Helper.wrap, reason unresolved",
            "unknown@57#2: label stamp, reason unresolved",
            "unknown@59: label JavaLimits.QUEUE, reason computed-name",
        )
        assertEquals(expected, one.steps("OnlyA"))
        assertEquals(expected.map { it.replace("PT5S", "PT30S") }, one.steps("OnlyB").map { it.replace(LINE) { match -> "@${match.groupValues[1].toInt() + OFFSET}${match.groupValues[2]}" } })
    }

    @Test
    fun `the declaration that is read keeps what it does itself`() {
        // The workflow is that of the first file. Its own function is followed; the stub of the
        // interface that is declared twice is not known to be of the one that is read.
        assertEquals(
            listOf(
                // The options of the stub are a private property of the first file, which the second file has under the same name.
                "activity@42: name One, timeout PT5S",
                "unknown@40: label Jobs.pack, reason unresolved",
            ),
            one.steps("Twice"),
        )
    }

    @Test
    fun `in modules of their own a use is trusted where it resolves to the declaration that is read`() {
        // The first module's declarations are the ones that are read: its workflow reads as it is written.
        assertEquals(
            listOf(
                // The step of the helper and that of the function of no class, each followed to where it is written.
                "activity@24: name One, taskQueue a-queue, timeout PT5S",
                "activity@27: name One, taskQueue a-queue, timeout PT5S",
                "activity@57: name One, taskQueue a-queue, timeout PT5S",
                "activity@58: name Own, timeout PT5S",
                "activity@59: name a-java-queue, timeout PT5S",
            ),
            two.steps("OnlyA"),
        )
        // The second module's are not, though the compiler resolves each of its uses to its own.
        assertEquals(
            listOf(
                "activity@53: name One",
                "activity@54: name Own, timeout PT30S",
                "unknown@53#1: label Helper.wrap, reason unresolved",
                "unknown@53#2: label stamp, reason unresolved",
                "unknown@55: label JavaLimits.QUEUE, reason computed-name",
            ),
            two.steps("OnlyB"),
        )
        assertEquals(listOf("activity@40: name Pack, taskQueue a-queue, timeout PT5S", "activity@42: name One, timeout PT5S"), two.steps("Twice"))
    }

    @Test
    fun `private properties of two files that share a name are told apart`() {
        // Each file has a private `short`: five seconds in the first, thirty in the second.
        for (documents in listOf(one, two)) {
            assertEquals(listOf("PT5S"), documents.single { it.workflowType == "OnlyA" }.nodes.filter { it.name == "Own" }.map { it.timeout })
            assertEquals(listOf("PT30S"), documents.single { it.workflowType == "OnlyB" }.nodes.filter { it.name == "Own" }.map { it.timeout })
        }
    }

    @Test
    fun `a call that may run a function of a class that is declared twice is not followed into the one that is read`(@TempDir directory: Path) {
        // The interface is declared once; two modules each have a class `Van` that implements it, and only one is read.
        val carrier = """
            package base

            import io.temporal.activity.ActivityInterface

            @ActivityInterface
            interface Plain {
                fun one(input: String): String
            }

            interface Carrier {
                fun carry(jobs: Plain, input: String): String
            }
        """
        fun van(body: String) = """
            package shared

            import base.Carrier
            import base.Plain

            class Van : Carrier {
                override fun carry(jobs: Plain, input: String): String = $body
            }
        """
        val workflow = """
            package app

            import base.Carrier
            import base.Plain
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod

            @WorkflowInterface
            interface Carried {
                @WorkflowMethod fun run(input: String): String
            }

            class CarriedImpl(private val carrier: Carrier) : Carried {
                private val plain = Workflow.newActivityStub(Plain::class.java)
                override fun run(input: String): String = carrier.carry(plain, input)
            }
        """
        val sources = SyntheticSources(directory)
            .module(":a", ":base")
            .module(":b", ":base")
            .module(":app", ":base")
            .file(":base", "base/Carrier.kt", carrier)
            .file(":a", "shared/Van.kt", van("input"))
            .file(":b", "shared/Van.kt", van("jobs.one(input)"))
            .file(":app", "app/Carried.kt", workflow)
        sources.open().use { backend ->
            val document = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.GRADLE).extract(TemporalDiscovery.discover(backend)).single()
            // The `Van` that is read holds nothing, the other one a step: which of them runs is not known.
            assertEquals(listOf("unknown@16: reason dynamic-dispatch"), document.nodeOutline().filter { it.startsWith("unknown") })
        }
    }

    @Test
    fun `a declaration that is not the one read is never taken for it, whichever of the two is met first`(@TempDir directory: Path) {
        fun limits(seconds: Int) = """
            package shared

            import io.temporal.activity.ActivityOptions
            import java.time.Duration

            object Limits {
                val OPTIONS: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds($seconds)).build()
            }
        """
        fun workflow(name: String) = """
            package flows

            import base.Plain
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import shared.Limits

            @WorkflowInterface
            interface $name {
                @WorkflowMethod fun run(input: String): String
            }

            class ${name}Impl : $name {
                private val plain = Workflow.newActivityStub(Plain::class.java, Limits.OPTIONS)
                override fun run(input: String): String = plain.one(input)
            }
        """
        // The workflow of the second module is read first, and with it the options that are not the ones read under their name.
        val sources = SyntheticSources(directory)
            .module(":a", ":base")
            .module(":b", ":base")
            .file(":base", "base/Plain.kt", PLAIN)
            .file(":a", "shared/Limits.kt", limits(5))
            .file(":a", "flows/Late.kt", workflow("Late"))
            .file(":b", "shared/Limits.kt", limits(50))
            .file(":b", "flows/Early.kt", workflow("Early"))
        sources.open().use { backend ->
            val documents = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.GRADLE).extract(TemporalDiscovery.discover(backend))
            assertEquals(listOf("Early", "Late"), documents.map { it.workflowType })
            assertEquals(listOf("activity One", "end"), documents[0].steps())
            assertEquals(listOf("activity One timeout PT5S", "end"), documents[1].steps())
        }
    }

    private companion object {
        /** A node's line in its handle, and how many lines further down the first file has what the second has. */
        val LINE = Regex("@(\\d+)(#\\d+)?")
        const val OFFSET = 4

        fun javaLimits(queue: String): String =
            """
            package shared;

            public final class JavaLimits {
                public static final String QUEUE = "$queue";
            }
            """

        const val IMPORTS = """
import base.Plain
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
"""

        /** An activity interface that is declared once, in a module both others see. */
        const val PLAIN = """
package base

import io.temporal.activity.ActivityInterface

@ActivityInterface
interface Plain {
    fun one(input: String): String
}
"""

        /** The declarations that are read: those of the file, or of the module, that comes first. */
        const val FIRST = "package shared\n$IMPORTS" + """
private val short = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()

object Limits {
    const val QUEUE = "a-queue"
    const val SECONDS = 5L
}

@ActivityInterface
interface Jobs {
    fun pack(input: String): String
}

object Helper {
    fun wrap(jobs: Plain, input: String): String = jobs.one(input)
}

fun stamp(jobs: Plain, input: String): String = jobs.one(input)

@WorkflowInterface
interface Twice {
    @WorkflowMethod fun run(input: String): String
}

class TwiceImpl : Twice {
    private val jobs = Workflow.newActivityStub(
        Jobs::class.java,
        ActivityOptions.newBuilder().setTaskQueue(Limits.QUEUE).setStartToCloseTimeout(Duration.ofSeconds(Limits.SECONDS)).build(),
    )
    private val plain = Workflow.newActivityStub(Plain::class.java, short)
    override fun run(input: String): String = jobs.pack(own(input))

    private fun own(input: String): String = plain.one(input)
}

@WorkflowInterface
interface OnlyA {
    @WorkflowMethod fun run(input: String): String
}

class OnlyAImpl : OnlyA {
    private val plain = Workflow.newActivityStub(
        Plain::class.java,
        ActivityOptions.newBuilder().setTaskQueue(Limits.QUEUE).setStartToCloseTimeout(Duration.ofSeconds(Limits.SECONDS)).build(),
    )
    private val untyped = Workflow.newUntypedActivityStub(short)
    override fun run(input: String): String {
        val first = stamp(plain, Helper.wrap(plain, plain.one(input)))
        untyped.execute("Own", String::class.java, first)
        return untyped.execute(JavaLimits.QUEUE, String::class.java, first)
    }
}
"""

        /** The same names with other content, and a workflow of its own. */
        const val SECOND = "package shared\n$IMPORTS" + """
private val short = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build()

object Limits {
    const val QUEUE = "b-queue"
    const val SECONDS = 50L
}

@ActivityInterface(namePrefix = "B")
interface Jobs {
    fun pack(input: String): String

    fun label(input: String): String
}

object Helper {
    fun wrap(jobs: Plain, input: String): String = input
}

fun stamp(jobs: Plain, input: String): String = input

@WorkflowInterface
interface Twice {
    @WorkflowMethod fun run(input: String): String
}

class TwiceImpl : Twice {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, short)
    override fun run(input: String): String = jobs.label(jobs.pack(input))
}

@WorkflowInterface
interface OnlyB {
    @WorkflowMethod fun run(input: String): String
}

class OnlyBImpl : OnlyB {
    private val plain = Workflow.newActivityStub(
        Plain::class.java,
        ActivityOptions.newBuilder().setTaskQueue(Limits.QUEUE).setStartToCloseTimeout(Duration.ofSeconds(Limits.SECONDS)).build(),
    )
    private val untyped = Workflow.newUntypedActivityStub(short)
    override fun run(input: String): String {
        val first = stamp(plain, Helper.wrap(plain, plain.one(input)))
        untyped.execute("Own", String::class.java, first)
        return untyped.execute(JavaLimits.QUEUE, String::class.java, first)
    }
}
"""
    }
}
