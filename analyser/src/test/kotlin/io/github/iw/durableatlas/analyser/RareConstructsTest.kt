package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.Block
import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.CodePrinter
import io.github.iw.durableatlas.analyser.code.Destructuring
import io.github.iw.durableatlas.analyser.code.Jump
import io.github.iw.durableatlas.analyser.code.JumpKind
import io.github.iw.durableatlas.analyser.code.Lambda
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.PropertyWrite
import io.github.iw.durableatlas.analyser.code.Return
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.When
import io.github.iw.durableatlas.analyser.code.walk
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lowering of Kotlin constructs the sample project does not use, and of code that does not resolve. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RareConstructsTest {
    private lateinit var backend: SourceBackend
    private lateinit var body: Block
    private val log = mutableListOf<String>()

    @BeforeAll
    fun open(@TempDir directory: Path) {
        backend = SyntheticSources(directory)
            .file(
                ":app", "rare/Holder.kt",
                """
                package rare

                import io.temporal.workflow.Workflow
                import java.time.Duration

                data class Point(val x: Int, val y: Int) {
                    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
                }

                infix fun Int.scaledBy(other: Int): Int = this * other

                class Holder(private val callback: (String) -> Int, val values: MutableList<Int>) {
                    var count = 0
                    private val lazyValue by lazy { compute(1) }

                    fun compute(input: Int?): Int {
                        val value = input ?: return -1
                        val (a, b) = Point(value, value) + Point(1, 2)
                        val scaled = a scaledBy b
                        when (val size = values.size) {
                            0 -> return 0
                            in 1..3 -> count += size
                            else -> if (size > 100) throw IllegalStateException("too many: ${'$'}size")
                        }
                        values.forEach { item ->
                            if (item < 0) return@forEach
                            values[0] = item
                            count++
                        }
                        loop@ for (i in 0 until scaled) {
                            if (i == 3) continue@loop
                            if (i > 5) break
                        }
                        fun local(n: Int) = n + 1
                        val listener = object : Runnable {
                            override fun run() {
                                Workflow.sleep(Duration.ofSeconds(1))
                            }
                        }
                        listener.run()
                        val text = callback("x").toString()
                        val length = (text as? CharSequence)?.length ?: 0
                        return local(length) + lazyValue + missingFunction(length) + this.missing.size
                    }
                }
                """,
            )
            .open(log::add)
        val compute = backend.functions("rare.Holder").single { it.name == "compute" }
        body = assertNotNull(backend.body(compute.id))
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    private fun calls(): List<Call> = body.walk().filterIsInstance<Call>().toList()

    @Test
    fun `the whole function lowers to the golden tree`() {
        GoldenFiles.assertMatches("code/rare/Holder.compute.txt", CodePrinter.print(body))
    }

    @Test
    fun `a call that does not resolve stays in the tree without a callee`() {
        val missing = calls().single { it.name == "missingFunction" }
        assertNull(missing.callee)
        assertNull(missing.receiverType)
        assertEquals(listOf("length"), missing.arguments.map { it.text })
        assertIs<LocalRead>(missing.arguments.single().value)

        // So does a property that does not exist, with what it was read from.
        val size = body.walk().filterIsInstance<PropertyRead>().single { it.name == "size" && it.property == null }
        val unknown = assertIs<PropertyRead>(size.receiver)
        assertEquals("missing", unknown.name)
        assertNull(unknown.property)
        // Code that does not resolve is nothing to warn about.
        assertTrue(log.none { it.startsWith("warning") }, log.toString())
    }

    @Test
    fun `operators and infix functions declared in the sources are calls`() {
        val plus = calls().single { it.name == "+" && it.callee?.inSource == true }
        assertEquals("rare.Point.plus(rare.Point)", plus.callee?.function.toString())
        assertEquals("rare.Point", plus.receiverType?.name)
        val infix = calls().single { it.name == "scaledBy" }
        assertEquals("rare.scaledBy(kotlin.Int) on kotlin.Int", infix.callee?.function.toString())
        assertIs<LocalRead>(infix.receiver)
        // An operator of the standard library is not worth a call of its own.
        assertTrue(calls().none { it.callee?.function?.name == "kotlin.Int.plus" })
        assertTrue(body.walk().filterIsInstance<Other>().any { it.operator == "+" })
    }

    @Test
    fun `calling a value of function type is a call of invoke on that value`() {
        val invoke = calls().single { it.name == "callback" }
        assertEquals("kotlin.Function1.invoke", invoke.callee?.function?.name)
        val receiver = assertIs<PropertyRead>(invoke.receiver)
        assertEquals("rare.Holder.callback", receiver.property.toString())
        assertTrue(receiver.onThis)
    }

    @Test
    fun `early exits, labels and destructuring are kept`() {
        val elvis = body.walk().filterIsInstance<Other>().first { it.operator == "?:" }
        assertIs<Return>(elvis.operands.last())
        assertEquals(listOf("a", "b"), body.walk().filterIsInstance<Destructuring>().single().names)
        assertEquals(listOf("forEach"), body.walk().filterIsInstance<Return>().mapNotNull { it.label }.toList())
        assertEquals(
            listOf(JumpKind.CONTINUE to "loop", JumpKind.BREAK to null),
            body.walk().filterIsInstance<Jump>().map { it.kind to it.label }.toList(),
        )
        val branches = body.walk().filterIsInstance<When>().single().branches
        assertEquals(listOf("0", "in 1..3", "else"), branches.map { it.conditionText })
        assertEquals(listOf(false, false, true), branches.map { it.isElse })
    }

    @Test
    fun `writes through operators name the property and the operator`() {
        assertEquals(
            listOf("rare.Holder.count" to "+=", "rare.Holder.count" to "++"),
            body.walk().filterIsInstance<PropertyWrite>().map { it.property.toString() to it.operator }.toList(),
        )
        assertTrue(body.walk().filterIsInstance<PropertyWrite>().all { it.onThis })
        assertTrue(body.walk().filterIsInstance<Other>().any { it.operator == "[]=" })
    }

    @Test
    fun `what an object literal or a local function contains is not part of the enclosing flow`() {
        assertTrue(calls().none { it.callee?.function?.name == "io.temporal.workflow.Workflow.sleep" })
        assertEquals(1, body.walk().filterIsInstance<Other>().count { it.operator == "object" })
        assertEquals(1, body.walk().filterIsInstance<Other>().count { it.operator == "declaration" })

        // A call of the local function still names it, by the place of its declaration.
        val local = assertNotNull(calls().single { it.name == "local" }.callee)
        assertEquals("<local>.local", local.function.name)
        assertEquals("app/src/main/kotlin/rare/Holder.kt:34", local.function.file)
        assertTrue(local.inSource)
        assertEquals(1, assertNotNull(backend.body(local.function)).statements.size)
    }

    @Test
    fun `a delegate expression stands in for the initialiser of a delegated property`() {
        val lazyValue = backend.properties("rare.Holder").single { it.name == "lazyValue" }
        assertTrue(lazyValue.hasInitializer)
        val delegate = assertIs<Call>(backend.initializer(lazyValue.id))
        assertEquals("kotlin.lazy", delegate.callee?.function?.name)
        val compute = assertIs<Lambda>(delegate.arguments.single().value).walk().filterIsInstance<Call>().single()
        assertEquals("rare.Holder.compute", compute.callee?.function?.name)

        // Constructor properties are properties too, without an initialiser.
        assertEquals(
            listOf("callback" to true, "values" to true, "count" to false, "lazyValue" to false),
            backend.properties("rare.Holder").map { it.name to it.isConstructorParameter },
        )
    }
}
