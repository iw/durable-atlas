package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.analyser.inventory.StandaloneActivities
import io.github.iw.durableatlas.ir.Detail
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An overview shows what a workflow does, not where the analysis ran out. It has no node for a
 * call behind which a step is only possible: the code handed to a call that is not modelled
 * stands where the call is made, once, and a call that leads to a function no class of the
 * sources implements is left out. A branch, a loop or a lane that held nothing else is gone
 * with it. The calls behind which a step is certain keep their nodes, and each names its call.
 * Everything else is the node it is in the full document, with the same id.
 *
 * The sources of [OverviewSources] are read as a degraded model has them: the library whose
 * functions are handed the blocks is not there. Each test gives the graph of one workflow
 * without ids, as [IrStructure] writes it: a node is its kind and its line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var full: List<IrDocument>
    private lateinit var overviews: List<IrDocument>
    private val warnings = ArrayList<String>()

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        OverviewSources.FILES.forEach { (name, text) -> sources.file(":app", "shop/$name.kt", text) }
        backend = sources.open()
        discovery = TemporalDiscovery.discover(backend)
        full = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED).extract(discovery)
        val graphs = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED, detail = Detail.OVERVIEW)
        overviews = graphs.extract(discovery)
        warnings += graphs.warnings
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    private fun List<IrDocument>.of(workflowType: String): IrDocument = single { it.workflowType == workflowType }

    private fun IrDocument.graph(): List<String> = outline().filterNot { it.startsWith("entry point ") }

    /** The nodes and edges of a workflow as the full document has them, without ids. */
    private fun full(workflowType: String): List<String> = full.of(workflowType).graph()

    /** The nodes and edges of a workflow as its overview has them, without ids. */
    private fun overview(workflowType: String): List<String> = overviews.of(workflowType).graph()

    /** The calls the overview of a workflow lists as left out: reason, label and line of each. */
    private fun leftOut(workflowType: String): List<String> =
        overviews.of(workflowType).leftOutCalls.map { "${it.reason} ${it.label}@${it.loc.line}" }

    @Test
    fun `every document is well formed, and the full ones are the golden ones`() {
        assertEquals((OverviewSources.RULES + OverviewSources.SERVICES).sorted(), full.map { it.workflowType })
        assertEquals(OverviewSources.FILES.keys, OverviewSources.SHARED + OverviewSources.RULES + OverviewSources.SERVICES)
        for (document in full + overviews) assertWellFormed(document)
        for (document in full) {
            assertEquals(null, document.detail)
            GoldenFiles.assertMatches("ir/shop/${document.workflowType}.ir.json", IrJson.encode(document))
        }
        for (document in overviews) assertEquals(Detail.OVERVIEW, document.detail)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `an overview keeps every step of the full document, and its golden file is there where it differs`() {
        // Two workflows hold nothing that an overview leaves out or names anew: one without bodies, one that never ends.
        val same = listOf("Delegated", "Forever")
        assertEquals(full.map { it.workflowType } - same, assertOverviewsKeepTheSteps(backend, discovery, "overview/shop"))
    }

    @Test
    fun `a model made from a build that lacks the library keeps the steps as well`() {
        // Every call into the library is then a call that did not resolve and may be a step: those nodes stay.
        val built = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.GRADLE).extract(discovery)
        val overviews = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.GRADLE, detail = Detail.OVERVIEW).extract(discovery)

        assertOverviewsKeepTheSteps(built, overviews) { _, _ -> }
        val wrapped = overviews.of("Wrapped")
        assertEquals(listOf("unresolved"), wrapped.nodesOf(NodeKind.UNKNOWN).map { it.reason }.distinct())
        assertEquals(emptyList(), wrapped.leftOutCalls)
    }

    @Test
    fun `the activities that no workflow calls are the same list in both details, with the same workflows named as unsure`() {
        val whole = StandaloneActivities.of(discovery, full, emptyList(), FIXED_COMMIT, ModelMode.DEGRADED)
        val brief = StandaloneActivities.of(discovery, overviews, emptyList(), FIXED_COMMIT, ModelMode.DEGRADED)

        assertEquals(whole, brief)
        // A workflow whose overview has no unknown node left may still call an activity where nothing can be seen.
        assertEquals(0, overviews.of("Helped").stats.unknown)
        assertTrue("Helped" in brief.unresolvedWorkflows)
        assertEquals(full.map { it.workflowType }, brief.unresolvedWorkflows)
    }

    @Test
    fun `reading the sources again gives the same overviews`() {
        assertEquals(overviews, WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED, detail = Detail.OVERVIEW).extract(discovery))
    }

    @Test
    fun `an overview has no node of the three kinds it leaves out, save in a loop that never ends`() {
        fun IrDocument.unsure(): List<String> =
            nodes.filter {
                it.reason == UnknownReasons.UNSEEN_CODE || (it.reason == UnknownReasons.DYNAMIC_DISPATCH && it.candidates.isEmpty()) ||
                    (it.reason == UnknownReasons.UNSUPPORTED && it.label?.endsWith("without a Kotlin body") != true)
            }.map { "$workflowType: ${it.inWords()}" }

        assertEquals(listOf("Forever: unknown unseen-code (Helper.poll)"), overviews.flatMap { it.unsure() })
        assertEquals(125, full.flatMap { it.unsure() }.size)
        // Every node that could not be read says what was called.
        assertEquals(emptyList(), overviews.flatMap { it.nodes }.filter { it.kind == NodeKind.UNKNOWN && it.label == null })
    }

    @Test
    fun `the workflows of a service lose most of their unknown nodes and many of their branches`() {
        fun counts(documents: List<IrDocument>): Map<String, String> =
            documents.filter { it.workflowType in OverviewSources.SERVICES }.associate { document ->
                document.workflowType to "${document.stats.nodes} nodes, ${document.stats.unknown} unknown, ${document.nodesOf(NodeKind.BRANCH).size} branches"
            }

        assertEquals(
            mapOf(
                "Fulfilment" to "56 nodes, 33 unknown, 7 branches",
                "Quotes" to "48 nodes, 25 unknown, 16 branches",
                "Refunds" to "34 nodes, 19 unknown, 5 branches",
                "Renewal" to "27 nodes, 15 unknown, 2 branches",
            ),
            counts(full),
        )
        assertEquals(
            mapOf(
                "Fulfilment" to "23 nodes, 2 unknown, 5 branches",
                "Quotes" to "9 nodes, 0 unknown, 3 branches",
                "Refunds" to "15 nodes, 1 unknown, 4 branches",
                "Renewal" to "15 nodes, 3 unknown, 2 branches",
            ),
            counts(overviews),
        )
        // What is gone is listed, by reason.
        assertEquals(
            mapOf("dynamic-dispatch" to 12, "unseen-code" to 46, "unsupported" to 28),
            overviews.filter { it.workflowType in OverviewSources.SERVICES }.flatMap { it.leftOutCalls }.groupingBy { it.reason }.eachCount().toSortedMap(),
        )
    }

    @Test
    fun `a block handed to a call that is not modelled stands where the call is made, once`() {
        // In full the call is a node, and a way leads past the block.
        assertEquals(
            listOf(
                "node activity@37: name Reserve, timeout PT5S",
                "node activity@39: name Ship, timeout PT5S",
                "node end(main)",
                "node start@35",
                "node unknown@36: label withRequestContext, reason unsupported",
                "edge activity@37 -> activity@39",
                "edge activity@39 -> end(main)",
                "edge start@35 -> unknown@36",
                "edge unknown@36 -> activity@37",
                "edge unknown@36 -> activity@39",
            ),
            full("Wrapped"),
        )
        // The overview has neither: the block follows what came before, and what comes after follows the block.
        assertEquals(
            listOf(
                "node activity@37: name Reserve, timeout PT5S",
                "node activity@39: name Ship, timeout PT5S",
                "node end(main)",
                "node start@35",
                "edge activity@37 -> activity@39",
                "edge activity@39 -> end(main)",
                "edge start@35 -> activity@37",
            ),
            overview("Wrapped"),
        )
        assertEquals(listOf("unsupported withRequestContext@36"), leftOut("Wrapped"))
    }

    @Test
    fun `several blocks handed to one call stand one after the other, in the order of the code`() {
        assertEquals(
            listOf(
                "node activity@35#1: name Charge, timeout PT5S",
                "node activity@35#2: name Refund, timeout PT5S",
                "node activity@36: name Notify, timeout PT5S",
                "node end(main)",
                "node start@34",
                "edge activity@35#1 -> activity@35#2",
                "edge activity@35#2 -> activity@36",
                "edge activity@36 -> end(main)",
                "edge start@34 -> activity@35#1",
            ),
            overview("Twice"),
        )
        assertEquals(listOf("unsupported either@35"), leftOut("Twice"))
    }

    @Test
    fun `blocks inside blocks come down to their steps`() {
        assertEquals(
            listOf(
                "node activity@39: name Reserve, timeout PT5S",
                "node activity@41: name Charge, timeout PT5S",
                "node activity@44: name Ship, timeout PT5S",
                "node end(main)",
                "node start@36",
                "edge activity@39 -> activity@41",
                "edge activity@41 -> activity@44",
                "edge activity@44 -> end(main)",
                "edge start@36 -> activity@39",
            ),
            overview("Nested"),
        )
        assertEquals(listOf("unsupported span@37", "unsupported withRequestContext@38", "unsupported retrying@40"), leftOut("Nested"))
    }

    @Test
    fun `a block inside a loop stays in the loop, and a loop that holds nothing else is gone`() {
        // The second loop holds a call that is left out, the third that and a `break`: neither is drawn.
        assertEquals(
            listOf(
                "node activity@37: name Reserve, timeout PT5S, parent loop@35",
                "node activity@48: name Ship, timeout PT5S",
                "node end(main)",
                "node loop@35: label for each line",
                "node start@34",
                "edge activity@48 -> end(main)",
                "edge loop@35 -> activity@48",
                "edge start@34 -> loop@35",
            ),
            overview("Looped"),
        )
        assertEquals(listOf("unsupported traced@36", "unseen-code Helper.remember@42", "unseen-code Helper.known@46"), leftOut("Looped"))
    }

    @Test
    fun `a branch whose ways hold only what is left out is gone, and one that keeps a step stays`() {
        // The first decision keeps both ways: one holds the block of a call that is not modelled, the other a step.
        // The second, on `order.gift`, held one call and is gone. The third keeps its step, and its other way leads on.
        // Of the three arms of the `when` one holds a step: the others lead on, each by its name.
        assertEquals(
            listOf(
                "node activity@38: name Ship, timeout PT5S",
                "node activity@41: name Notify, timeout PT5S",
                "node activity@49: name Audit, timeout PT5S",
                "node activity@55: name Notify, timeout PT5S",
                "node branch@36: label order.express",
                "node branch@48: label order.note != null",
                "node branch@53: label order.channel",
                "node end(main)",
                "node start@35",
                "edge activity@38 -> branch@48",
                "edge activity@41 -> branch@48",
                "edge activity@49 -> branch@53",
                "edge activity@55 -> end(main)",
                "edge branch@36 -> activity@38 [yes]",
                "edge branch@36 -> activity@41 [no]",
                "edge branch@48 -> activity@49 [yes]",
                "edge branch@48 -> branch@53 [no]",
                "edge branch@53 -> activity@55 [\"sms\"]",
                "edge branch@53 -> end(main) [\"email\"]",
                "edge branch@53 -> end(main) [else]",
                "edge start@35 -> branch@36",
            ),
            overview("Blocked"),
        )
        assertEquals(listOf("unsupported withRequestContext@37", "unseen-code Helper.remember@45", "unseen-code Helper.remember@51", "unseen-code Helper.describe@54", "unseen-code Helper.remember@56", "unseen-code Helper.describe@58"), leftOut("Blocked"))
    }

    @Test
    fun `a helper that calls a function no class implements leaves nothing`() {
        assertEquals(
            listOf(
                "node activity@36: name Reserve, timeout PT5S",
                "node activity@39: name Charge, timeout PT5S",
                "node end(main)",
                "node start@34",
                "edge activity@36 -> activity@39",
                "edge activity@39 -> end(main)",
                "edge start@34 -> activity@36",
            ),
            overview("Helped"),
        )
        assertEquals(listOf("unseen-code Helper.describe@35", "unseen-code Helper.remember@37", "unseen-code Helper.price@38"), leftOut("Helped"))
    }

    @Test
    fun `a call of a function that no class implements is left out, and one that runs one of several classes stays and is named`() {
        // The mapper is called by the code that is drawn, the transaction runner as well, and it is handed code: that stands in place.
        assertEquals(
            listOf(
                "node activity@37: name Reserve, timeout PT5S",
                "node activity@40: name Charge, timeout PT5S",
                "node end(main)",
                "node start@34",
                "node unknown@43: label Notifier.send, reason dynamic-dispatch, candidates shop.EmailNotifier, shop.SmsNotifier",
                "edge activity@37 -> activity@40",
                "edge activity@40 -> unknown@43",
                "edge start@34 -> activity@37",
                "edge unknown@43 -> end(main)",
            ),
            overview("Direct"),
        )
        assertEquals(listOf("dynamic-dispatch OrderMapper.toText@36", "dynamic-dispatch Transactions.inTransaction@39"), leftOut("Direct"))
    }

    @Test
    fun `a lane that holds nothing is gone with its region, and a compensation that holds nothing stays as it was`() {
        // Both lanes held one call. The saga still compensates: the region is there, and what it would run is listed.
        assertEquals(
            listOf(
                "node activity@42: name Charge, timeout PT5S",
                "node compensation@44",
                "node end(main)",
                "node start@34",
                "edge activity@42 -(exception)-> compensation@44 [ActivityFailure]",
                "edge activity@42 -> end(main)",
                "edge compensation@44 -> end(main)",
                "edge start@34 -> activity@42",
            ),
            overview("Emptied"),
        )
        assertEquals(listOf("unseen-code Helper.remember@36", "unseen-code Helper.remember@38", "unseen-code Helper.price@39"), leftOut("Emptied"))
    }

    @Test
    fun `a race keeps what it waits for when one of its lanes is gone, and a lane that nothing waits for stays a region`() {
        assertEquals(
            listOf(
                "node activity@37: name Charge, timeout PT5S, parent parallel@36",
                "node activity@42: name Audit, timeout PT5S, parent parallel@40",
                "node activity@44: name Ship, timeout PT5S",
                "node end(main)",
                "node parallel@36: join first",
                "node parallel@40: waitsFor nothing",
                "node start@34",
                "edge activity@44 -> end(main)",
                "edge parallel@36 -> parallel@40",
                "edge parallel@40 -> activity@44",
                "edge start@34 -> parallel@36",
            ),
            overview("Lanes"),
        )
        assertEquals(listOf("unseen-code Helper.price@36", "unseen-code Helper.remember@41"), leftOut("Lanes"))
    }

    @Test
    fun `every call behind which a step is certain keeps its node, and each node names its call`() {
        // Too deep, recursive, a name that is computed, one of two classes, a stub of an interface that is missing.
        assertEquals(
            listOf(
                "node activity@Deep.kt:50: name Charge, timeout PT5S",
                "node branch@Deep.kt:51: label left > 0",
                "node end(main)",
                "node start@Kept.kt:37",
                "node unknown@Deep.kt:41: label Deep.nine, reason depth-limit",
                "node unknown@Deep.kt:51: label Deep.again, reason recursion",
                "node unknown@Kept.kt:40: label \"Check\" + kind, reason computed-name",
                "node unknown@Kept.kt:41: reason dynamic-dispatch, candidates shop.EmailNotifier, shop.SmsNotifier",
                "node unknown@Kept.kt:42: label RemoteSteps.push, reason unresolved",
                "edge activity@Deep.kt:50 -> branch@Deep.kt:51",
                "edge branch@Deep.kt:51 -> unknown@Deep.kt:51 [yes]",
                "edge branch@Deep.kt:51 -> unknown@Kept.kt:40 [no]",
                "edge start@Kept.kt:37 -> unknown@Deep.kt:41",
                "edge unknown@Deep.kt:41 -> activity@Deep.kt:50",
                "edge unknown@Deep.kt:51 -> unknown@Kept.kt:40",
                "edge unknown@Kept.kt:40 -> unknown@Kept.kt:41",
                "edge unknown@Kept.kt:41 -> unknown@Kept.kt:42",
                "edge unknown@Kept.kt:42 -> end(main)",
            ),
            full("Kept"),
        )
        // The same nodes: the one that only said why now says what was called.
        assertEquals(
            listOf(
                "node activity@Deep.kt:50: name Charge, timeout PT5S",
                "node branch@Deep.kt:51: label left > 0",
                "node end(main)",
                "node start@Kept.kt:37",
                "node unknown@Deep.kt:41: label Deep.nine, reason depth-limit",
                "node unknown@Deep.kt:51: label Deep.again, reason recursion",
                "node unknown@Kept.kt:40: label \"Check\" + kind, reason computed-name",
                "node unknown@Kept.kt:41: label Notifier.send, reason dynamic-dispatch, candidates shop.EmailNotifier, shop.SmsNotifier",
                "node unknown@Kept.kt:42: label RemoteSteps.push, reason unresolved",
                "edge activity@Deep.kt:50 -> branch@Deep.kt:51",
                "edge branch@Deep.kt:51 -> unknown@Deep.kt:51 [yes]",
                "edge branch@Deep.kt:51 -> unknown@Kept.kt:40 [no]",
                "edge start@Kept.kt:37 -> unknown@Deep.kt:41",
                "edge unknown@Deep.kt:41 -> activity@Deep.kt:50",
                "edge unknown@Deep.kt:51 -> unknown@Kept.kt:40",
                "edge unknown@Kept.kt:40 -> unknown@Kept.kt:41",
                "edge unknown@Kept.kt:41 -> unknown@Kept.kt:42",
                "edge unknown@Kept.kt:42 -> end(main)",
            ),
            overview("Kept"),
        )
        assertEquals(listOf(), leftOut("Kept"))
    }

    @Test
    fun `a workflow method and a handler without a body keep their nodes`() {
        assertEquals(
            listOf(
                "node end(main)",
                "node end(signal-stop)",
                "node start@35#1",
                "node start@35#2",
                "node unknown@35#1: label handler without a Kotlin body, reason unsupported",
                "node unknown@35#2: label workflow method without a Kotlin body, reason unsupported",
                "edge start@35#1 -> unknown@35#2",
                "edge start@35#2 -> unknown@35#1",
                "edge unknown@35#1 -> end(signal-stop)",
                "edge unknown@35#2 -> end(main)",
            ),
            overview("Delegated"),
        )
        assertEquals(listOf(), leftOut("Delegated"))
    }

    @Test
    fun `a clause that only a call which is left out may reach is entered from where the flow stood`() {
        // In the first `try` a step fails the same way: nothing is said of the call. The second holds nothing else,
        // and its clause is drawn from what the flow came from, as a clause is that no step is known to reach.
        assertEquals(
            listOf(
                "node activity@35: name Reserve, timeout PT5S",
                "node activity@39: name Charge, timeout PT5S",
                "node activity@41: name Release, timeout PT5S",
                "node activity@47: name Refund, timeout PT5S",
                "node end(main)",
                "node start@34",
                "edge activity@35 -> activity@39",
                "edge activity@39 -(exception)-> activity@41 [ActivityFailure]",
                "edge activity@39 -(exception)-> activity@47 [ActivityFailure]",
                "edge activity@39 -> end(main)",
                "edge activity@41 -(exception)-> activity@47 [ActivityFailure]",
                "edge activity@41 -> end(main)",
                "edge activity@47 -> end(main)",
                "edge start@34 -> activity@35",
            ),
            overview("Caught"),
        )
        assertEquals(listOf("unseen-code Helper.remember@38", "unseen-code Helper.remember@45"), leftOut("Caught"))
    }

    @Test
    fun `a copy of a finally block that only a call which is left out reaches is drawn all the same`() {
        // The block is drawn twice: once for the way on, once for the way to the clause.
        assertEquals(
            listOf(
                "node activity@35: name Reserve, timeout PT5S",
                "node activity@40#1: name Release, timeout PT5S",
                "node activity@40#2: name Release, timeout PT5S",
                "node activity@43: name Notify, timeout PT5S",
                "node end(main)",
                "node start@34",
                "node unknown@38: label Helper.remember, reason unseen-code, candidates shop.OrderRepository.save(shop.Order)",
                "edge activity@35 -> unknown@38",
                "edge activity@40#1 -(exception)-> activity@43 [ActivityFailure]",
                "edge activity@40#1 -> activity@43",
                "edge activity@40#2 -(exception)-> activity@43 [ActivityFailure]",
                "edge activity@40#2 -> end(main)",
                "edge activity@43 -> end(main)",
                "edge start@34 -> activity@35",
                "edge unknown@38 -(exception)-> activity@40#1 [ActivityFailure]",
                "edge unknown@38 -> activity@40#2",
            ),
            full("Cleaned"),
        )
        // Both copies stay, each the node it was.
        assertEquals(
            listOf(
                "node activity@35: name Reserve, timeout PT5S",
                "node activity@40#1: name Release, timeout PT5S",
                "node activity@40#2: name Release, timeout PT5S",
                "node activity@43: name Notify, timeout PT5S",
                "node end(main)",
                "node start@34",
                "edge activity@35 -(exception)-> activity@40#1 [ActivityFailure]",
                "edge activity@35 -> activity@40#2",
                "edge activity@40#1 -(exception)-> activity@43 [ActivityFailure]",
                "edge activity@40#1 -> activity@43",
                "edge activity@40#2 -(exception)-> activity@43 [ActivityFailure]",
                "edge activity@40#2 -> end(main)",
                "edge activity@43 -> end(main)",
                "edge start@34 -> activity@35",
            ),
            overview("Cleaned"),
        )
        assertEquals(listOf("unseen-code Helper.remember@38"), leftOut("Cleaned"))
    }

    @Test
    fun `a loop that the flow never gets past stays as it is, with the call in it`() {
        assertEquals(
            listOf(
                "node activity@35: name Audit, timeout PT5S",
                "node loop@37: label while true",
                "node start@34",
                "node unknown@38: label Helper.poll, reason unseen-code, candidates shop.OrderRepository.next(), parent loop@37",
                "edge activity@35 -> loop@37",
                "edge start@34 -> activity@35",
            ),
            full("Forever"),
        )
        assertEquals(
            listOf(
                "node activity@35: name Audit, timeout PT5S",
                "node loop@37: label while true",
                "node start@34",
                "node unknown@38: label Helper.poll, reason unseen-code, candidates shop.OrderRepository.next(), parent loop@37",
                "edge activity@35 -> loop@37",
                "edge start@34 -> activity@35",
            ),
            overview("Forever"),
        )
        assertEquals(listOf(), leftOut("Forever"))
    }

    @Test
    fun `what is chained to a promise nothing is known of, a lambda that is kept, and a block that a function of the sources hands on stand in place`() {
        assertEquals(
            listOf(
                "node activity@36: name Audit, timeout PT5S",
                "node activity@37: name Ship, timeout PT5S",
                "node end(main)",
                "node start@34",
                "edge activity@36 -> activity@37",
                "edge activity@37 -> end(main)",
                "edge start@34 -> activity@36",
            ),
            overview("Chained"),
        )
        assertEquals(listOf("unsupported Promise.thenApply@36"), leftOut("Chained"))
        assertEquals(
            listOf(
                "node activity@36: name Audit, timeout PT5S",
                "node activity@37: name Ship, timeout PT5S",
                "node end(main)",
                "node start@34",
                "edge activity@36 -> activity@37",
                "edge activity@37 -> end(main)",
                "edge start@34 -> activity@36",
            ),
            overview("Stored"),
        )
        assertEquals(listOf("unsupported lambda@36"), leftOut("Stored"))
        assertEquals(
            listOf(
                "node activity@37: name Charge, timeout PT5S",
                "node activity@39: name Ship, timeout PT5S",
                "node end(main)",
                "node start@34",
                "edge activity@37 -> activity@39",
                "edge activity@39 -> end(main)",
                "edge start@34 -> activity@37",
            ),
            overview("Handed"),
        )
        assertEquals(listOf("unsupported traced@40"), leftOut("Handed"))
    }

    @Test
    fun `what follows a block that never completes is reached past it, as in full`() {
        assertEquals(
            listOf(
                "node activity@37: name Audit, timeout PT5S",
                "node activity@40: name Ship, timeout PT5S",
                "node end(main)",
                "node fail@38: name Rejected",
                "node start@34",
                "node unknown@36: label traced, reason unsupported",
                "edge activity@37 -> fail@38",
                "edge activity@40 -> end(main)",
                "edge start@34 -> unknown@36",
                "edge unknown@36 -> activity@37",
                "edge unknown@36 -> activity@40",
            ),
            full("Thrown"),
        )
        assertEquals(
            listOf(
                "node activity@37: name Audit, timeout PT5S",
                "node activity@40: name Ship, timeout PT5S",
                "node end(main)",
                "node fail@38: name Rejected",
                "node start@34",
                "edge activity@37 -> fail@38",
                "edge activity@40 -> end(main)",
                "edge start@34 -> activity@37",
                "edge start@34 -> activity@40",
            ),
            overview("Thrown"),
        )
        assertEquals(listOf("unsupported traced@36"), leftOut("Thrown"))
    }

    @Test
    fun `a decision that only asks how a wait ended and holds nothing else leaves the wait with one way on`() {
        assertEquals(
            listOf(
                "node activity@41: name Ship, timeout PT5S",
                "node await@36: timeout PT1H, reads paid, label paid",
                "node end(main)",
                "node start@35",
                "node unknown@39: label Helper.remember, reason unseen-code, candidates shop.OrderRepository.save(shop.Order)",
                "edge activity@41 -> end(main)",
                "edge await@36 -(timeout)-> unknown@39",
                "edge await@36 -> activity@41 [paid]",
                "edge start@35 -> await@36",
                "edge unknown@39 -> activity@41",
            ),
            full("Parted"),
        )
        assertEquals(
            listOf(
                "node activity@41: name Ship, timeout PT5S",
                "node await@36: timeout PT1H, reads paid, label paid",
                "node end(main)",
                "node start@35",
                "edge activity@41 -> end(main)",
                "edge await@36 -> activity@41",
                "edge start@35 -> await@36",
            ),
            overview("Parted"),
        )
        assertEquals(listOf("unseen-code Helper.remember@39"), leftOut("Parted"))
    }

    @Test
    fun `a call that decides a branch is left out, and the branch stays for what it holds`() {
        assertEquals(
            listOf(
                "node activity@37: name Notify, timeout PT5S",
                "node activity@40: name Ship, timeout PT5S",
                "node branch@36: label helper.known(order.id)",
                "node branch@39: label helper.price(order) > 100",
                "node end(main)",
                "node start@34",
                "edge activity@37 -> branch@39",
                "edge activity@40 -> end(main)",
                "edge branch@36 -> activity@37 [yes]",
                "edge branch@36 -> branch@39 [no]",
                "edge branch@39 -> activity@40 [no]",
                "edge branch@39 -> end(main) [yes]",
                "edge start@34 -> branch@36",
            ),
            overview("Decided"),
        )
        assertEquals(listOf("unseen-code Helper.known@36", "unseen-code Helper.price@39"), leftOut("Decided"))
    }

    @Test
    fun `a node keeps its id when one before it on the same line is left out`() {
        assertEquals(
            listOf(
                "node activity@37: name Ship, timeout PT5S",
                "node branch@37: label order.express",
                "node end(main)",
                "node start@34",
                "node unknown@36: label Notifier.send, reason dynamic-dispatch, candidates shop.EmailNotifier, shop.SmsNotifier",
                "edge activity@37 -> end(main)",
                "edge branch@37 -> activity@37 [yes]",
                "edge branch@37 -> end(main) [no]",
                "edge start@34 -> unknown@36",
                "edge unknown@36 -> branch@37",
            ),
            overview("OneLine"),
        )
        assertEquals(listOf("unsupported traced@36", "unseen-code Helper.remember@37"), leftOut("OneLine"))
    }

    @Test
    fun `a handler is read the same way`() {
        assertEquals(
            listOf(
                "node activity@40: name Ship, timeout PT5S",
                "node await@39: reads paid, label paid",
                "node end(main)",
                "node end(signal-paid)",
                "node start@38",
                "node start@43",
                "edge activity@40 -> end(main)",
                "edge await@39 -> activity@40",
                "edge entry point signal-paid -(wakes)-> await@39",
                "edge start@38 -> await@39",
                "edge start@43 -> end(signal-paid)",
            ),
            overview("Signalled"),
        )
        assertEquals(listOf("unseen-code Helper.known@44", "unseen-code Helper.remember@45"), leftOut("Signalled"))
    }
}
