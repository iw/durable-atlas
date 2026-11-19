package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.discovery.WorkflowType
import io.github.iw.durableatlas.analyser.inventory.Inventory
import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.analyser.inventory.InventorySchema
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discovery on declarations the sample project does not have: interface hierarchies, names that
 * come from constants, annotations that are imported under another name, and annotations that
 * only look like the SDK's. The expectations follow the SDK's own rules for these cases.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NamingRulesTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery

    @BeforeAll
    fun open(@TempDir directory: Path) {
        backend = SyntheticSources(directory)
            .file(
                ":app", "hierarchy/Flows.kt",
                """
                package hierarchy

                import io.temporal.workflow.QueryMethod
                import io.temporal.workflow.SignalMethod
                import io.temporal.workflow.WorkflowInterface
                import io.temporal.workflow.WorkflowMethod

                // Not a workflow interface itself: its methods belong to the interfaces that extend it.
                interface BaseFlow {
                    @WorkflowMethod
                    fun run(input: String): String

                    @SignalMethod
                    fun stop()
                }

                @WorkflowInterface
                interface FirstFlow : BaseFlow

                @WorkflowInterface
                interface SecondFlow : BaseFlow {
                    @QueryMethod(name = "state")
                    fun currentState(): String
                }

                // Registers handlers, but no workflow type.
                @WorkflowInterface
                interface Pausable {
                    @SignalMethod
                    fun pause()
                }

                class FirstFlowImpl : FirstFlow, Pausable {
                    override fun run(input: String): String = input
                    override fun stop() = Unit
                    override fun pause() = Unit
                }

                abstract class SecondFlowBase : SecondFlow {
                    override fun stop() = Unit
                    override fun currentState(): String = "running"
                }

                class SecondFlowImpl : SecondFlowBase() {
                    override fun run(input: String): String = input
                }

                class OtherSecondFlowImpl : SecondFlowBase() {
                    override fun run(input: String): String = input.reversed()
                    override fun currentState(): String = "reversing"
                }
                """,
            )
            .file(
                ":app", "hierarchy/Nested.kt",
                """
                package hierarchy

                import io.temporal.workflow.SignalMethod
                import io.temporal.workflow.WorkflowInterface
                import io.temporal.workflow.WorkflowMethod

                // A workflow interface above another: the upper one names the workflow type.
                @WorkflowInterface
                interface Parent {
                    @WorkflowMethod
                    fun go()
                }

                @WorkflowInterface
                interface Child : Parent {
                    @SignalMethod
                    fun poke()
                }

                class ChildImpl : Child {
                    override fun go() = Unit
                    override fun poke() = Unit
                }

                class Outer {
                    @WorkflowInterface
                    interface Inner {
                        @WorkflowMethod
                        fun go()
                    }

                    class InnerImpl : Inner {
                        override fun go() = Unit
                    }
                }

                // Declared, but implemented nowhere.
                @WorkflowInterface
                interface Unimplemented {
                    @WorkflowMethod
                    fun go()
                }
                """,
            )
            .file(
                ":app", "names/Names.kt",
                """
                package names

                import io.temporal.workflow.SignalMethod
                import io.temporal.workflow.UpdateMethod
                import io.temporal.workflow.UpdateValidatorMethod
                import io.temporal.workflow.WorkflowMethod
                import io.temporal.workflow.WorkflowInterface as Flow

                const val PREFIX = "billing"
                const val TYPE_NAME = PREFIX + "-run"

                object Signals {
                    const val CANCEL = "cancel-requested"
                }

                // The annotation is imported under another name, and written out in full below.
                @Flow
                interface Renamed {
                    @WorkflowMethod(name = TYPE_NAME)
                    fun run()

                    @SignalMethod(name = Signals.CANCEL)
                    fun cancel()

                    @SignalMethod(name = "")
                    fun emptyName()

                    @UpdateMethod
                    fun change(value: Int)

                    @UpdateValidatorMethod(updateName = "change")
                    fun checkChange(value: Int)
                }

                @io.temporal.workflow.WorkflowInterface
                interface Qualified {
                    @io.temporal.workflow.WorkflowMethod
                    fun run()
                }
                """,
            )
            .file(
                ":app", "lookalike/LookAlike.kt",
                """
                package lookalike

                annotation class WorkflowInterface
                annotation class WorkflowMethod(val name: String = "")
                annotation class ActivityInterface(val namePrefix: String = "")

                @WorkflowInterface
                interface NotAWorkflow {
                    @WorkflowMethod(name = "LooksLikeOne")
                    fun run()
                }

                @ActivityInterface(namePrefix = "No")
                interface NotActivities {
                    fun act()
                }

                class NotAWorkflowImpl : NotAWorkflow, NotActivities {
                    override fun run() = Unit
                    override fun act() = Unit
                }
                """,
            )
            .file(
                ":app", "activities/Activities.kt",
                """
                package activities

                import io.temporal.activity.ActivityInterface
                import io.temporal.activity.ActivityMethod

                // Not an activity interface itself: each interface that extends it names its methods.
                interface Common {
                    fun ping()

                    @ActivityMethod(name = "Fixed")
                    fun fixed()
                }

                @ActivityInterface(namePrefix = "A_")
                interface AActivities : Common {
                    fun own()
                }

                @ActivityInterface
                interface BActivities : Common {
                    @ActivityMethod(name = "")
                    fun eTag()
                }

                class Both : AActivities, BActivities {
                    override fun ping() = Unit
                    override fun fixed() = Unit
                    override fun own() = Unit
                    override fun eTag() = Unit
                }
                """,
            )
            .open()
        discovery = TemporalDiscovery.discover(backend)
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    private fun workflow(type: String): WorkflowType = discovery.workflows.single { it.name == type }

    private fun WorkflowType.entryPointNames(): List<Triple<EntryPointKind, String, String>> =
        entryPoints.map { Triple(it.kind, it.name, it.method.name) }

    @Test
    fun `only interfaces that register a workflow method define a workflow type`() {
        assertEquals(
            listOf(
                "FirstFlow" to "hierarchy.FirstFlow",
                "Inner" to "hierarchy.Outer.Inner",
                "Parent" to "hierarchy.Parent",
                "Qualified" to "names.Qualified",
                "SecondFlow" to "hierarchy.SecondFlow",
                "Unimplemented" to "hierarchy.Unimplemented",
                "billing-run" to "names.Renamed",
            ),
            discovery.workflows.map { it.name to it.declaration.name },
        )
    }

    @Test
    fun `a method inherited from an interface without the annotation is named after the interface that has it`() {
        // The default workflow type is the simple name of the annotated interface, not of the
        // interface that declares the method.
        assertEquals(
            listOf(
                Triple(EntryPointKind.WORKFLOW_METHOD, "FirstFlow", "run"),
                Triple(EntryPointKind.SIGNAL, "stop", "stop"),
            ),
            workflow("FirstFlow").entryPointNames(),
        )
        assertEquals(
            listOf(
                Triple(EntryPointKind.WORKFLOW_METHOD, "SecondFlow", "run"),
                Triple(EntryPointKind.SIGNAL, "stop", "stop"),
                Triple(EntryPointKind.QUERY, "state", "currentState"),
            ),
            workflow("SecondFlow").entryPointNames(),
        )
        assertEquals("hierarchy.BaseFlow", workflow("FirstFlow").entryPoints.first().method.id.owner)
    }

    @Test
    fun `implementations are the concrete classes, and inherited functions count`() {
        val second = workflow("SecondFlow")
        assertEquals(
            listOf("hierarchy.OtherSecondFlowImpl", "hierarchy.SecondFlowImpl"),
            second.implementations.map { it.declaration.name },
        )
        // stop() and currentState() come from the abstract superclass unless the class has its own.
        assertEquals(
            listOf(
                "hierarchy.SecondFlowImpl.run",
                "hierarchy.SecondFlowBase.stop",
                "hierarchy.SecondFlowBase.currentState",
            ),
            second.implementations.single { it.declaration.simpleName == "SecondFlowImpl" }
                .entryPoints.map { it.function?.id?.name },
        )
        assertEquals(
            listOf(
                "hierarchy.OtherSecondFlowImpl.run",
                "hierarchy.SecondFlowBase.stop",
                "hierarchy.OtherSecondFlowImpl.currentState",
            ),
            second.implementations.single { it.declaration.simpleName == "OtherSecondFlowImpl" }
                .entryPoints.map { it.function?.id?.name },
        )
        assertEquals(emptyList(), workflow("Unimplemented").implementations)
    }

    @Test
    fun `handlers of further workflow interfaces are additional entry points of the class`() {
        val first = workflow("FirstFlow").implementations.single()
        assertEquals("hierarchy.FirstFlowImpl", first.declaration.name)
        assertEquals(
            listOf(Triple(EntryPointKind.SIGNAL, "pause", "hierarchy.FirstFlowImpl.pause")),
            first.additionalEntryPoints.map { Triple(it.entryPoint.kind, it.entryPoint.name, it.function?.id?.name) },
        )
        assertFalse(discovery.workflows.any { it.declaration.name == "hierarchy.Pausable" })
    }

    @Test
    fun `a workflow interface below another adds handlers but no workflow type`() {
        val parent = workflow("Parent")
        assertEquals(listOf(Triple(EntryPointKind.WORKFLOW_METHOD, "Parent", "go")), parent.entryPointNames())
        val implementation = parent.implementations.single()
        assertEquals("hierarchy.ChildImpl", implementation.declaration.name)
        assertEquals(listOf("poke"), implementation.additionalEntryPoints.map { it.entryPoint.name })
        assertFalse(discovery.workflows.any { it.name == "Child" })
    }

    @Test
    fun `a nested interface is named by its own simple name`() {
        val inner = workflow("Inner")
        assertEquals("hierarchy.Outer.Inner", inner.declaration.name)
        assertEquals(listOf("hierarchy.Outer.InnerImpl"), inner.implementations.map { it.declaration.name })
    }

    @Test
    fun `names may come from constants, and an empty name counts as none`() {
        assertEquals(
            listOf(
                Triple(EntryPointKind.WORKFLOW_METHOD, "billing-run", "run"),
                Triple(EntryPointKind.SIGNAL, "cancel-requested", "cancel"),
                Triple(EntryPointKind.SIGNAL, "emptyName", "emptyName"),
                Triple(EntryPointKind.UPDATE, "change", "change"),
                Triple(EntryPointKind.UPDATE_VALIDATOR, "change", "checkChange"),
            ),
            workflow("billing-run").entryPointNames(),
        )
    }

    @Test
    fun `annotations are recognised by what they resolve to, not by how they are written`() {
        // Imported under another name, and written with its full name.
        assertEquals("names.Renamed", workflow("billing-run").declaration.name)
        assertEquals("names.Qualified", workflow("Qualified").declaration.name)
        // Same simple names as the SDK's annotations, declared elsewhere.
        assertFalse(discovery.workflows.any { it.declaration.name.startsWith("lookalike.") })
        assertFalse(discovery.activityInterfaces.any { it.declaration.name.startsWith("lookalike.") })
        assertEquals(
            listOf("lookalike.WorkflowInterface"),
            backend.findClass("lookalike.NotAWorkflow")?.annotations?.map { it.type },
        )
    }

    @Test
    fun `activity names take the prefix of the interface that registers the method`() {
        fun activities(name: String) =
            discovery.activityInterfaces.single { it.declaration.name == name }.activities.map { it.method.name to it.name }

        assertEquals(listOf("activities.AActivities", "activities.BActivities"), discovery.activityInterfaces.map { it.declaration.name })
        // A name on the method is used as it is; without one the method name is capitalised.
        assertEquals(listOf("own" to "A_Own", "ping" to "A_Ping", "fixed" to "Fixed"), activities("activities.AActivities"))
        assertEquals(listOf("eTag" to "ETag", "fixed" to "Fixed", "ping" to "Ping"), activities("activities.BActivities"))
        for (activityInterface in discovery.activityInterfaces) {
            assertEquals(listOf("activities.Both"), activityInterface.implementations.map { it.name })
        }
        assertFalse(discovery.activityInterfaces.any { it.declaration.name == "activities.Common" })
    }

    @Test
    fun `the inventory of these declarations matches its schema`() {
        val inventory = Inventory.of(discovery, FIXED_COMMIT, ModelMode.DEGRADED)
        val json = InventoryJson.encode(inventory)

        JsonSchemaChecker.forSchema(InventorySchema.text()).assertValid(json)
        assertEquals(inventory, InventoryJson.decode(json))
        val first = inventory.workflows.single { it.workflowType == "FirstFlow" }
        assertEquals(":app", first.module)
        assertEquals("app/src/main/kotlin/hierarchy/Flows.kt", first.loc?.file)
        assertEquals(listOf("pause"), first.implementations.single().additionalEntryPoints.map { it.name })
        assertNull(inventory.workflows.single { it.workflowType == "Unimplemented" }.implementations.firstOrNull())
        assertTrue(discovery.warnings.isEmpty(), discovery.warnings.toString())
    }
}
