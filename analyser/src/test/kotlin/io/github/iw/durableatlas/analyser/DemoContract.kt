package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.analyser.testing.DemoModels
import io.github.iw.durableatlas.analyser.testing.DemoSources
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Graph extraction on the demo project, compared with the IR that was written out for its order
 * workflow by hand (`demo/expected/OrderWorkflow.ir.json`), whichever way the model was extracted.
 *
 * The comparison is by structure, not by ids: the hand-written document names its nodes in
 * reading order, the analyser derives ids from where the nodes come from. Everything else must
 * be the same: the entry points with what they read and write, every node with its properties
 * and its region, and every edge, the ones from handlers to the await they wake included.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DemoContract(protected val mode: ModelMode) {
    protected val documents: List<IrDocument> by lazy {
        val backend = DemoSources.of(mode)
        WorkflowGraphs(backend, FIXED_COMMIT, mode).extract(TemporalDiscovery.discover(backend))
    }

    protected val expected: IrDocument by lazy {
        IrJson.decode(DemoModels.project.resolve("expected").resolve("OrderWorkflow.ir.json").readText())
    }

    protected val actual: IrDocument get() = documents.single { it.implementation == expected.implementation }

    @Test
    fun `the documents of the demo are well formed`() {
        assertEquals(listOf("OrderWorkflow", "ShipmentWorkflow"), documents.map { it.workflowType })
        documents.forEach { assertWellFormed(it) }
        assertEquals(expected.workflowType, actual.workflowType)
        assertEquals(expected.interfaceName, actual.interfaceName)
    }

    @Test
    fun `the order workflow is the one written out by hand`() {
        val differences = IrStructure.differences(expected, actual)
        report(differences)

        assertEquals(emptyList(), differences.map { it.toString() }, "differences from demo/expected/OrderWorkflow.ir.json")
        // The same structure, line for line, and so the same numbers.
        assertEquals(expected.outline(), actual.outline())
        assertEquals(expected.stats, actual.stats)
        assertEquals(expected.edges.size, actual.edges.size)
        assertEquals(expected.entryPoints.map { it.id }, actual.entryPoints.map { it.id })
    }

    @Test
    fun `the one call that cannot be followed lists the classes it may reach, and the calls that hold no step leave nothing`() {
        val unknown = actual.nodesOf(NodeKind.UNKNOWN).single()

        // A discount policy is one of two classes; which one is decided while the workflow runs.
        assertEquals(UnknownReasons.DYNAMIC_DISPATCH, unknown.reason)
        assertEquals(118, unknown.loc?.line)
        assertEquals(
            listOf("com.example.orders.pricing.PromotionDiscount", "com.example.orders.pricing.TierDiscount"),
            unknown.candidates,
        )
        // `Order.totalCents` and `warehouseWorkflowId` are followed and hold nothing: every node is a line of the workflow class.
        assertEquals(setOf("orders/src/main/kotlin/com/example/orders/OrderWorkflowImpl.kt"), actual.nodes.mapNotNull { it.loc?.file }.toSet())
    }

    @Test
    fun `sparing the functions that hold nothing changes no graph`() {
        val backend = DemoSources.of(mode)

        assertEquals(documents.sumOf { it.mainFlow().nodes.size }, assertSparingChangesNothing(backend, TemporalDiscovery.discover(backend)))
    }

    @Test
    fun `the child workflow of the demo is two activities`() {
        val shipment = documents.single { it.workflowType == "ShipmentWorkflow" }

        assertEquals(listOf("activity PackShipment timeout PT5M", "activity BookCarrier timeout PT5M", "end"), shipment.steps())
        assertEquals(1, shipment.entryPoints.size)
    }

    /** Writes what was compared and what differs to the build directory, and to the output of the test. */
    private fun report(differences: List<IrStructure.Difference>) {
        val text = buildString {
            appendLine("Demo order workflow, ${mode.name.lowercase()} model: extracted IR against demo/expected/OrderWorkflow.ir.json")
            appendLine("expected: ${expected.stats.nodes} nodes, ${expected.edges.size} edges, ${expected.entryPoints.size} entry points")
            appendLine("extracted: ${actual.stats.nodes} nodes (${actual.stats.unknown} unknown), ${actual.edges.size} edges, ${actual.entryPoints.size} entry points")
            appendLine()
            appendLine("Differences (must be none): ${differences.size}")
            differences.forEach { appendLine("  $it") }
        }
        println(text)
        val file = Path.of("build", "reports", "demo-comparison", "${mode.name.lowercase()}.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
