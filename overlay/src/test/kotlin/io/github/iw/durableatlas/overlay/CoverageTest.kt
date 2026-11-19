package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.overlay.coverage.CloseCoverage
import io.github.iw.durableatlas.overlay.coverage.CoverageBuilder
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.coverage.EventCounts
import io.github.iw.durableatlas.overlay.coverage.WorkflowCoverage
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Profiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageTest {
    private val schema = JsonSchemaChecker.forSchema(CoverageJson.schema())

    /** The coverage of a workflow type of the sample repository by all of its histories. */
    private fun sample(workflowType: String): WorkflowCoverage {
        val mapper = ExecutionMapper(Fixtures.sample(workflowType))
        val histories = Fixtures.sampleHistoryNames().filter { it.substringBefore('.') == workflowType }
        return CoverageBuilder.build(mapper, histories.map { mapper.map(Fixtures.sampleHistory(it)) })
    }

    private fun demo(vararg paths: String): WorkflowCoverage {
        val mapper = ExecutionMapper(Fixtures.demo)
        return CoverageBuilder.build(mapper, paths.map { mapper.map(Fixtures.demoHistory(it)) })
    }

    private val workflowTypes: List<String> get() = Fixtures.sampleHistoryNames().map { it.substringBefore('.') }.distinct()

    @Test
    fun `hits add up over the executions of a workflow type`() {
        val coverage = sample("OrderWorkflow")
        val names = Profiles(ExecutionMapper(Fixtures.sample("OrderWorkflow")))
        val hits = coverage.nodes.associate { names.name(it.id) to it.hits }
        val executions = coverage.nodes.associate { names.name(it.id) to it.executions }

        assertEquals("OrderWorkflow", coverage.workflowType)
        assertEquals(3, coverage.executions)
        // 26 + 13 + 17 events, as the three histories were pinned one by one.
        assertEquals(EventCounts(mapped = 56, ambiguous = 0, unmapped = 0), coverage.events)
        assertEquals(3, hits["start"])
        // Two lines, one line, two lines.
        assertEquals(5, hits["activity ReserveStock"])
        assertEquals(3, executions["activity ReserveStock"])
        assertEquals(5, hits["localActivity CheckAvailability"])
        assertEquals(3, hits["loop for each line"])
        // The charge ran for the paid order and for the declined one.
        assertEquals(2, hits["activity PaymentCharge"])
        assertEquals(1, hits["activity CapturePayment"])
        // The order that timed out released both of its lines; the declined one, in the other compensation, its one.
        assertEquals(2, hits["activity ReleaseStock@44 in compensation@54"])
        assertEquals(1, executions["activity ReleaseStock@44 in compensation@54"])
        assertEquals(1, hits["activity ReleaseStock@44 in compensation@94"])
        assertEquals(2, hits["end"])
        assertEquals(1, hits["fail TemporalFailure"])

        assertEquals(
            listOf("activity PaymentRefund", "end of signal-cancelOrder", "start of signal-cancelOrder"),
            coverage.neverHit.map(names::name).sorted(),
        )
        assertEquals(31, coverage.recordedNodes)
        assertEquals(28, coverage.hitNodes)
        assertEquals(
            listOf(CloseCoverage("completed", listOf(coverage.nodes.single { names.name(it.id) == "end" }.id), 2), "failed"),
            coverage.closes.mapIndexed { index, close -> if (index == 0) close else close.status },
        )
        assertEquals(
            listOf("OrderWorkflow.json", "OrderWorkflow.payment-declined.json", "OrderWorkflow.payment-timeout.json"),
            coverage.histories.map { it.file },
        )
        assertEquals(listOf("completed", "failed", "completed"), coverage.histories.map { it.status })
    }

    @Test
    fun `handlers count their calls, and what a history cannot show is marked`() {
        val coverage = sample("OrderWorkflow")
        val handlers = coverage.entryPoints.associateBy { it.id }

        assertEquals(3, handlers.getValue("main").hits)
        // Two of the three orders got the payment signal, one of them also the update.
        assertEquals(2, handlers.getValue("signal-payment-received").hits)
        assertEquals(2, handlers.getValue("signal-payment-received").executions)
        assertEquals(1, handlers.getValue("update-change-address").hits)
        assertEquals(1, handlers.getValue("validator-change-address").hits)
        assertEquals(0, handlers.getValue("signal-cancelOrder").hits)

        // Queries leave no event: the handler and its nodes count as neither hit nor missed.
        val query = handlers.getValue("query-order-status")
        assertEquals(EntryPointKind.QUERY, query.kind)
        assertFalse(query.recorded)
        assertEquals(0, query.hits)
        val unrecorded = coverage.nodes.filter { !it.recorded }
        assertEquals(2, unrecorded.size)
        assertTrue(unrecorded.none { it.id in coverage.neverHit })
        assertTrue(coverage.edges.single { it.from == unrecorded.first { node -> node.kind.name == "START" }.id }.let { !it.recorded })

        val wakes = coverage.edges.filter { it.kind == EdgeKind.WAKES }.associate { it.from to it.hits }
        assertEquals(mapOf("signal-cancelOrder" to 0, "signal-payment-received" to 2), wakes)
    }

    @Test
    fun `edges the graph implies are listed after its own`() {
        val coverage = sample("LoopWorkflow")
        val written = Fixtures.sample("LoopWorkflow").edges.size

        assertEquals(coverage.edges.take(written).map { it.implied }.toSet(), setOf(false))
        val implied = coverage.edges.drop(written)
        // Five loops with one child each: into the child and back.
        assertEquals(10, implied.size)
        assertTrue(implied.all { it.implied })
        assertEquals(listOf(1, 1, 2, 2, 2, 2, 2, 2, 2, 2), implied.map { it.hits }.sorted())
    }

    @Test
    fun `what is not certain is counted and listed`() {
        val recursion = sample("RecursionWorkflow")

        assertEquals(listOf("paths"), recursion.ambiguous.map { it.doubt })
        assertEquals("RecursionWorkflow.json", recursion.ambiguous.single().history)
        assertEquals(3, recursion.ambiguous.single().edges.size)
        assertEquals(listOf(1), recursion.nodes.filter { it.uncertain > 0 }.map { it.uncertain })
        assertEquals(EventCounts(mapped = 19, ambiguous = 0, unmapped = 0), recursion.events)

        val chain = sample("ContinueAsNewWorkflow")
        assertEquals(listOf("candidates"), chain.ambiguous.map { it.doubt })
        assertEquals(EventCounts(mapped = 2, ambiguous = 1, unmapped = 0), chain.events)
        assertEquals(listOf(CloseCoverage("continuedAsNew", chain.ambiguous.single().nodes, 1)), chain.closes)
    }

    @Test
    fun `unmapped events are listed with their history and their likely source`() {
        val coverage = demo("paid", "cancelled", "timeout", "promotion", "continue-as-new")

        assertEquals(5, coverage.executions)
        // Of the 26 events that say an activity or a child started, 25 belong to a step with a node.
        assertEquals(EventCounts(mapped = 95, ambiguous = 0, unmapped = 1), coverage.events)
        val event = coverage.unmapped.single()
        assertEquals("OrderWorkflow.promotion.json", event.history)
        assertEquals(68L, event.eventId)
        assertEquals("ActivityTaskScheduled", event.eventType)
        assertEquals("LookUpPromotion", event.name)
        assertEquals(listOf("n17-discount-policy"), event.likelySources)

        // Between them the five orders reach every node; only version 1 of the gate was never taken.
        assertEquals(emptyList(), coverage.neverHit)
        assertEquals(listOf("n02-fraud-check-version" to "n04-parallel"), coverage.edges.filter { it.hits == 0 && it.recorded }.map { it.from to it.to })
        val hits = coverage.nodes.associate { it.id to it.hits }
        assertEquals(5, hits["n07-await-payment"])
        assertEquals(2, hits["n08-compensation"])
        assertEquals(3, hits["n12-shipments"])
        assertEquals(6, hits["n13-shipment-workflow"])
        assertEquals(1, hits["n20-continue-as-new"])
        assertEquals(2, hits["n21-end"])
        assertEquals(
            listOf("completed" to 2, "continuedAsNew" to 1, "failed" to 2),
            coverage.closes.map { it.status to it.executions },
        )
    }

    @Test
    fun `the order of the executions does not matter`() {
        val paths = listOf("paid", "cancelled", "timeout", "promotion", "continue-as-new")

        val expected = CoverageJson.encode(demo(*paths.toTypedArray()))
        assertEquals(expected, CoverageJson.encode(demo(*paths.reversed().toTypedArray())))
        assertEquals(expected, CoverageJson.encode(demo(*paths.sorted().toTypedArray())))
    }

    @Test
    fun `coverage is written as JSON that matches its schema and reads back`() {
        for (workflowType in workflowTypes) {
            val coverage = sample(workflowType)
            val json = CoverageJson.encode(coverage)

            schema.assertValid(json)
            assertEquals(coverage, CoverageJson.decode(json), workflowType)
            assertTrue(json.endsWith("}\n") && "\r" !in json, workflowType)
            GoldenFiles.assertMatches("coverage/$workflowType.coverage.json", json)
        }
        assertEquals(30, workflowTypes.size)

        val demo = CoverageJson.encode(demo("paid", "cancelled", "timeout", "promotion", "continue-as-new"))
        schema.assertValid(demo)
        GoldenFiles.assertMatches("coverage/demo/OrderWorkflow.coverage.json", demo)
    }

    @Test
    fun `coverage without an execution is valid too`() {
        val coverage = CoverageBuilder.build(ExecutionMapper(Fixtures.demo), emptyList())

        schema.assertValid(CoverageJson.encode(coverage))
        assertEquals(0, coverage.executions)
        assertEquals(30, coverage.neverHit.size)
        assertEquals(emptyList(), coverage.closes)
    }

    @Test
    fun `the schema refuses what the format does not allow`() {
        val json = CoverageJson.encode(sample("TimerWorkflow"))

        assertTrue(schema.violations(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")).isNotEmpty())
        assertTrue(schema.violations(json.replace("\"executions\": 1,\n  \"events\"", "\"executions\": -1,\n  \"events\"")).isNotEmpty())
        assertTrue(schema.violations(json.replace("\"status\": \"completed\"", "\"status\": \"done\"")).isNotEmpty())
        assertTrue(schema.violations(json.replace("\"neverHit\"", "\"missed\"")).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { CoverageJson.decode(json.replace("\"neverHit\"", "\"missed\"")) }
    }
}
