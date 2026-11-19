package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Profiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every history of the sample repository laid over the document of its workflow type. The
 * counts are what each history shows, event by event; they were read against the histories by
 * hand.
 */
class FixtureOverlayTest {
    private class Overlaid(val trace: ExecutionTrace, val profiles: Profiles) {
        val nodes: String get() = profiles.nodes(trace.nodes)
        val edges: String get() = profiles.edges(trace.edges)
        val handlers: String get() = trace.entryPoints.entries.joinToString("\n") { "${it.key} ×${it.value.count}" }
    }

    /** @param history file name of the history without `.json`; what precedes the first dot is the workflow type */
    private fun overlay(history: String): Overlaid {
        val mapper = ExecutionMapper(Fixtures.sample(history.substringBefore('.')))
        return Overlaid(mapper.map(Fixtures.sampleHistory(history)), Profiles(mapper))
    }

    /** The workflows whose history holds a step that the analysis could not see; see their test below. */
    private val hardCases = mapOf(
        "ComputedActivityNameWorkflow" to "ShipExpress",
        "DeepHelperChainWorkflow" to "ReserveStock",
        "DynamicDispatchWorkflow" to "NotifySms",
    )

    @Test
    fun `every history maps without an unmapped event, apart from the steps behind unknown calls`() {
        val names = Fixtures.sampleHistoryNames()
        assertEquals(32, names.size)
        for (name in names) {
            val trace = overlay(name).trace
            if (name in hardCases) continue
            assertEquals(emptyList(), trace.unmapped, name)
            assertTrue(trace.ambiguous.none { it.doubt == Doubt.OUT_OF_ORDER }, name)
            assertTrue(trace.closedAt.isNotEmpty(), "$name ends at no node")
        }
        // Nothing but these two is left open by any history.
        val open = names.associateWith { overlay(it).trace.ambiguous.map { match -> match.doubt } }.filterValues { it.isNotEmpty() }
        assertEquals(mapOf("ContinueAsNewWorkflow" to listOf(Doubt.CANDIDATES), "RecursionWorkflow" to listOf(Doubt.PATHS)), open)
    }

    @Test
    fun `a history laid over the wrong document fails nowhere, and its hits are those of nodes that document has`() {
        val documents = Fixtures.sampleHistoryNames().map { it.substringBefore('.') }.distinct().map(Fixtures::sample) + Fixtures.demo
        val histories = Fixtures.sampleHistoryNames().map(Fixtures::sampleHistory) +
            listOf("paid", "cancelled", "timeout", "promotion", "continue-as-new").map(Fixtures::demoHistory)
        var unmapped = 0
        val statuses = HashMap<String, MutableSet<CloseStatus>>()
        for (document in documents) {
            val mapper = ExecutionMapper(document)
            val nodes = document.nodes.map { it.id }.toSet()
            val handlers = document.entryPoints.map { it.id }.toSet()
            for (history in histories) {
                val trace = mapper.map(history)
                val name = "${history.source} over ${document.workflowType}"
                assertTrue(nodes.containsAll(trace.nodes.keys), name)
                assertTrue(handlers.containsAll(trace.entryPoints.keys), name)
                assertTrue(trace.edges.keys.all { it.to in nodes && (it.from in nodes || it.from in handlers) }, name)
                assertTrue(trace.nodes.values.all { it.count > 0 && it.uncertain <= it.count }, name)
                // The start of the workflow always has a place.
                assertTrue(trace.mapped >= 1, name)
                statuses.getOrPut(history.source) { HashSet() } += trace.status
                unmapped += trace.unmapped.size
            }
        }
        // How an execution ended is in its history, whatever it is laid over.
        assertTrue(statuses.values.all { it.size == 1 }, statuses.toString())
        assertEquals(setOf(CloseStatus.COMPLETED, CloseStatus.FAILED, CloseStatus.CONTINUED_AS_NEW), statuses.values.flatten().toSet())
        // Most steps of a history have no node in the graph of another workflow.
        assertTrue(unmapped > 1000, unmapped.toString())
    }

    @Test
    fun `the order that is paid`() {
        val order = overlay("OrderWorkflow")

        assertEquals(CloseStatus.COMPLETED, order.trace.status)
        // 17 steps, handler calls and ends, and the 8 activities and the child that started.
        assertEquals(26, order.trace.mapped)
        assertEquals(
            """
            activity PaymentCharge ×1
            activity CapturePayment ×1
            start ×1
            loop for each line ×1
            branch !localInventory.checkAvailability(line.sku) ×2
            localActivity CheckAvailability ×2
            activity ReserveStock ×2
            activity NotifyEmail@49 ×1
            await paymentReference != null or cancelReason != null ×1
            versionGate order-audit ×1
            activity NotifyAudit ×1
            parallel ×1
            activity NotifyEmail@73 ×1
            activity NotifySms ×1
            childWorkflow Shipment ×1
            start of signal-payment-received ×1
            start of update-change-address ×1
            start of validator-change-address ×1
            end ×1
            end of signal-payment-received ×1
            end of update-change-address ×1
            end of validator-change-address ×1
            """.trimIndent(),
            order.nodes,
        )
        assertEquals(
            """
            activity CapturePayment -> versionGate order-audit ×1
            activity NotifyAudit -> parallel ×1
            activity NotifyEmail@49 -> await paymentReference != null or cancelReason != null ×1
            activity NotifyEmail@73 -> parallel ×1
            activity NotifySms -> parallel ×1
            activity PaymentCharge -> activity CapturePayment ×1
            activity ReserveStock -> loop for each line ×2
            await paymentReference != null or cancelReason != null -> activity PaymentCharge 'paymentReference != null' ×1
            branch !localInventory.checkAvailability(line.sku) -> activity ReserveStock 'no' ×2
            childWorkflow Shipment -> end ×1
            localActivity CheckAvailability -> branch !localInventory.checkAvailability(line.sku) ×2
            loop for each line -> activity NotifyEmail@49 ×1
            loop for each line -> localActivity CheckAvailability ×2
            parallel -> activity NotifyEmail@73 ×1
            parallel -> activity NotifySms ×1
            parallel -> childWorkflow Shipment ×1
            signal-payment-received -> await paymentReference != null or cancelReason != null wakes ×1
            start -> loop for each line ×1
            start of signal-payment-received -> end of signal-payment-received ×1
            start of update-change-address -> end of update-change-address ×1
            start of validator-change-address -> end of validator-change-address ×1
            versionGate order-audit -> activity NotifyAudit 'v1' ×1
            """.trimIndent(),
            order.edges,
        )
        assertEquals(
            """
            main ×1
            validator-change-address ×1
            update-change-address ×1
            signal-payment-received ×1
            """.trimIndent(),
            order.handlers,
        )
    }

    @Test
    fun `the order whose payment is declined`() {
        val order = overlay("OrderWorkflow.payment-declined")

        assertEquals(CloseStatus.FAILED, order.trace.status)
        assertEquals(listOf("fail TemporalFailure"), order.trace.closedAt.map(order.profiles::name))
        // The refund was not registered yet when the charge failed, so the compensation begins at its second step.
        assertEquals(
            """
            activity PaymentCharge ×1
            start ×1
            loop for each line ×1
            branch !localInventory.checkAvailability(line.sku) ×1
            localActivity CheckAvailability ×1
            activity ReserveStock ×1
            activity ReleaseStock@44 in compensation@94 ×1
            activity NotifyEmail@49 ×1
            await paymentReference != null or cancelReason != null ×1
            compensation@94 ×1
            fail TemporalFailure ×1
            start of signal-payment-received ×1
            end of signal-payment-received ×1
            """.trimIndent(),
            order.nodes,
        )
        assertEquals(
            """
            activity NotifyEmail@49 -> await paymentReference != null or cancelReason != null ×1
            activity PaymentCharge -> compensation@94 exception 'TemporalFailure' ×1
            activity ReserveStock -> loop for each line ×1
            await paymentReference != null or cancelReason != null -> activity PaymentCharge 'paymentReference != null' ×1
            branch !localInventory.checkAvailability(line.sku) -> activity ReserveStock 'no' ×1
            compensation@94 -> fail TemporalFailure ×1
            localActivity CheckAvailability -> branch !localInventory.checkAvailability(line.sku) ×1
            loop for each line -> activity NotifyEmail@49 ×1
            loop for each line -> localActivity CheckAvailability ×1
            signal-payment-received -> await paymentReference != null or cancelReason != null wakes ×1
            start -> loop for each line ×1
            start of signal-payment-received -> end of signal-payment-received ×1
            """.trimIndent(),
            order.edges,
        )
    }

    @Test
    fun `the order that is not paid in time`() {
        val order = overlay("OrderWorkflow.payment-timeout")

        assertEquals(CloseStatus.COMPLETED, order.trace.status)
        // Two lines were reserved, so the one step of the compensation runs twice.
        assertEquals(
            """
            start ×1
            loop for each line ×1
            branch !localInventory.checkAvailability(line.sku) ×2
            localActivity CheckAvailability ×2
            activity ReserveStock ×2
            activity ReleaseStock@44 in compensation@54 ×2
            activity NotifyEmail@49 ×1
            await paymentReference != null or cancelReason != null ×1
            compensation@54 ×1
            activity NotifyEmail@55 ×1
            end ×1
            """.trimIndent(),
            order.nodes,
        )
        // The timer of the await fired: it is left on its timeout edge, not on the one for a cancelled order.
        assertEquals(
            """
            activity NotifyEmail@49 -> await paymentReference != null or cancelReason != null ×1
            activity NotifyEmail@55 -> end ×1
            activity ReserveStock -> loop for each line ×2
            await paymentReference != null or cancelReason != null -> compensation@54 timeout ×1
            branch !localInventory.checkAvailability(line.sku) -> activity ReserveStock 'no' ×2
            compensation@54 -> activity NotifyEmail@55 ×1
            compensation@54 -> activity ReleaseStock@44 in compensation@54 ×1
            localActivity CheckAvailability -> branch !localInventory.checkAvailability(line.sku) ×2
            loop for each line -> activity NotifyEmail@49 ×1
            loop for each line -> localActivity CheckAvailability ×2
            start -> loop for each line ×1
            """.trimIndent(),
            order.edges,
        )
        assertEquals("main ×1", order.handlers)
    }

    @Test
    fun `an activity of the same name in a handler and in the workflow method goes to the one that runs`() {
        val approval = overlay("ApprovalWorkflow")

        assertEquals(
            """
            start ×1
            activity NotifyEmail@18 ×1
            await approvals >= requiredApprovals or rejectionReason != null ×1
            activity NotifyAudit@30 ×1
            start of signal-approve ×2
            start of update-set-required-approvals ×1
            start of validator-set-required-approvals ×1
            start of update-addNote ×1
            activity NotifyAudit@62 ×1
            end ×1
            end of signal-approve ×2
            end of update-addNote ×1
            end of update-set-required-approvals ×1
            end of validator-set-required-approvals ×1
            """.trimIndent(),
            approval.nodes,
        )
        // The first NotifyAudit follows the update `addNote` while the await still waits: it is the handler's.
        assertEquals(listOf("activity NotifyAudit@62"), approval.trace.matches.single { it.eventId == 21L }.nodes.map(approval.profiles::name))
        assertEquals(listOf("activity NotifyAudit@30"), approval.trace.matches.single { it.eventId == 37L }.nodes.map(approval.profiles::name))
        // Both approvals arrive while the await waits. The update of the required approvals came before it began.
        assertEquals(
            """
            activity NotifyAudit@30 -> end ×1
            activity NotifyAudit@62 -> end of update-addNote ×1
            activity NotifyEmail@18 -> await approvals >= requiredApprovals or rejectionReason != null ×1
            await approvals >= requiredApprovals or rejectionReason != null -> activity NotifyAudit@30 'approvals >= requiredApprovals' ×1
            signal-approve -> await approvals >= requiredApprovals or rejectionReason != null wakes ×2
            start -> activity NotifyEmail@18 ×1
            start of signal-approve -> end of signal-approve ×2
            start of update-addNote -> activity NotifyAudit@62 ×1
            start of update-set-required-approvals -> end of update-set-required-approvals ×1
            start of validator-set-required-approvals -> end of validator-set-required-approvals ×1
            """.trimIndent(),
            approval.edges,
        )
        assertEquals(
            """
            main ×1
            validator-set-required-approvals ×1
            update-set-required-approvals ×1
            update-addNote ×1
            signal-approve ×2
            """.trimIndent(),
            approval.handlers,
        )
    }

    @Test
    fun `a version marker takes the edge of the version it recorded`() {
        val gate = overlay("VersionGateWorkflow")

        assertEquals(
            """
            start ×1
            versionGate express-shipping ×1
            activity ShipExpress ×1
            end ×1
            """.trimIndent(),
            gate.nodes,
        )
        assertEquals(
            """
            activity ShipExpress -> end ×1
            start -> versionGate express-shipping ×1
            versionGate express-shipping -> activity ShipExpress 'v1' ×1
            """.trimIndent(),
            gate.edges,
        )
    }

    @Test
    fun `the lanes of parallel regions are entered and left, whatever order their events come in`() {
        val parallel = overlay("ParallelWorkflow")

        assertEquals(
            """
            start ×1
            activity ReserveStock ×1
            parallel@32 ×1
            activity QuoteShipping ×1
            activity NotifyEmail ×1
            parallel@37 ×1
            activity NotifySms ×1
            activity NotifyAudit ×1
            parallel@42 ×1
            end ×1
            """.trimIndent(),
            parallel.nodes,
        )
        assertEquals(
            """
            activity NotifyAudit -> parallel@42 ×1
            activity NotifyEmail -> parallel@37 ×1
            activity NotifySms -> parallel@37 ×1
            activity QuoteShipping -> parallel@32 ×1
            activity ReserveStock -> parallel@32 ×1
            parallel@32 -> activity QuoteShipping ×1
            parallel@32 -> activity ReserveStock ×1
            parallel@32 -> parallel@37 ×1
            parallel@37 -> activity NotifyEmail ×1
            parallel@37 -> activity NotifySms ×1
            parallel@37 -> parallel@42 ×1
            parallel@42 -> activity NotifyAudit ×1
            parallel@42 -> end ×1
            start -> parallel@32 ×1
            """.trimIndent(),
            parallel.edges,
        )
    }

    @Test
    fun `a loop is entered once and its body counts the rounds`() {
        val loops = overlay("LoopWorkflow")

        assertEquals(
            """
            start ×1
            loop for each sku@23 ×1
            activity ReserveStock ×2
            loop while pauses < 2 ×1
            timer ×2
            loop do while !available ×1
            activity CheckAvailability ×1
            loop for each sku@41 ×1
            activity ShipItem ×2
            activity QuoteShipping ×2
            loop for each sku@46 ×1
            end ×1
            """.trimIndent(),
            loops.nodes,
        )
        assertEquals(
            """
            activity CheckAvailability -> loop do while !available ×1
            activity QuoteShipping -> loop for each sku@46 ×2
            activity ReserveStock -> loop for each sku@23 ×2
            activity ShipItem -> loop for each sku@41 ×2
            loop do while !available -> activity CheckAvailability ×1
            loop do while !available -> loop for each sku@41 ×1
            loop for each sku@23 -> activity ReserveStock ×2
            loop for each sku@23 -> loop while pauses < 2 ×1
            loop for each sku@41 -> activity ShipItem ×2
            loop for each sku@41 -> loop for each sku@46 ×1
            loop for each sku@46 -> activity QuoteShipping ×2
            loop for each sku@46 -> end ×1
            loop while pauses < 2 -> loop do while !available ×1
            loop while pauses < 2 -> timer ×2
            start -> loop for each sku@23 ×1
            timer -> loop while pauses < 2 ×2
            """.trimIndent(),
            loops.edges,
        )
    }

    @Test
    fun `a compensation runs what was registered by then`() {
        val saga = overlay("SagaWorkflow")

        assertEquals(CloseStatus.FAILED, saga.trace.status)
        assertEquals(listOf("fail ActivityFailure"), saga.trace.closedAt.map(saga.profiles::name))
        // The charge fails before its refund is registered: only the stock is released, and nothing is shipped.
        assertEquals(
            """
            start ×1
            activity ReserveStock ×1
            activity ReleaseStock ×1
            activity PaymentCharge ×1
            compensation ×1
            fail ActivityFailure ×1
            """.trimIndent(),
            saga.nodes,
        )
        assertEquals(
            """
            activity PaymentCharge -> compensation exception 'ActivityFailure' ×1
            activity ReserveStock -> activity PaymentCharge ×1
            compensation -> fail ActivityFailure ×1
            start -> activity ReserveStock ×1
            """.trimIndent(),
            saga.edges,
        )
    }

    @Test
    fun `a failure after a parallel region leads to the compensation`() {
        val references = overlay("MethodReferenceWorkflow")

        assertEquals(CloseStatus.FAILED, references.trace.status)
        assertEquals(
            """
            start ×1
            activity ReserveStock ×1
            parallel ×1
            activity NotifyEmail ×1
            activity ReleaseStock ×1
            activity PaymentCharge ×1
            compensation ×1
            fail ActivityFailure ×1
            """.trimIndent(),
            references.nodes,
        )
        assertEquals(
            """
            activity NotifyEmail -> parallel ×1
            activity PaymentCharge -> compensation exception 'ActivityFailure' ×1
            activity ReserveStock -> parallel ×1
            compensation -> activity ReleaseStock ×1
            compensation -> fail ActivityFailure ×1
            parallel -> activity NotifyEmail ×1
            parallel -> activity PaymentCharge ×1
            parallel -> activity ReserveStock ×1
            start -> parallel ×1
            """.trimIndent(),
            references.edges,
        )
    }

    @Test
    fun `an await without a timer is passed on the way, and counts the signal that let it go on`() {
        val await = overlay("AwaitWorkflow")

        assertEquals(
            """
            start ×1
            await released@23 ×1
            await released@27 ×1
            timer ×1
            start of signal-release ×1
            end ×1
            end of signal-release ×1
            """.trimIndent(),
            await.nodes,
        )
        // The second await ran into its timeout: no signal came while it waited.
        assertEquals(
            """
            await released@23 -> await released@27 ×1
            await released@27 -> timer timeout ×1
            signal-release -> await released@23 wakes ×1
            start -> await released@23 ×1
            start of signal-release -> end of signal-release ×1
            timer -> end ×1
            """.trimIndent(),
            await.edges,
        )
    }

    @Test
    fun `a step behind an unknown call is unmapped, and the unknown call is named as its likely source`() {
        for ((workflow, activity) in hardCases) {
            val overlaid = overlay(workflow)
            val event = overlaid.trace.unmapped.single()

            assertEquals("ActivityTaskScheduled", event.eventType, workflow)
            assertEquals(activity, event.name, workflow)
            assertEquals(5L, event.eventId, workflow)
            val unknown = Fixtures.sample(workflow).nodes.single { it.kind == NodeKind.UNKNOWN }
            assertEquals(listOf(unknown.id), event.likelySources, workflow)
            // The unknown call itself was passed: the execution went on behind it.
            assertEquals(1, overlaid.trace.nodes.getValue(unknown.id).count, workflow)
            assertEquals(CloseStatus.COMPLETED, overlaid.trace.status, workflow)
            assertEquals(2, overlaid.trace.mapped, workflow)
        }
    }

    @Test
    fun `what a recursion repeats goes to the nodes it runs again`() {
        val recursion = overlay("RecursionWorkflow")

        assertEquals(emptyList(), recursion.trace.unmapped)
        // Six attempts, five waits between them, and each wait leads back through the call that was not followed.
        assertEquals(
            """
            activity CheckAvailability ×6
            branch inventory.checkAvailability(sku) ×6
            branch attemptsLeft <= 0 ×6 (1?)
            timer ×5
            unknown waitForStock ×5
            start ×1
            end ×1
            """.trimIndent(),
            recursion.nodes,
        )
        // How the last attempt came to the end is not in the history: either branch may have returned.
        assertEquals(
            """
            activity CheckAvailability -> branch inventory.checkAvailability(sku) ×6
            branch attemptsLeft <= 0 -> end 'yes' ×1 (1?)
            branch attemptsLeft <= 0 -> timer 'no' ×5
            branch inventory.checkAvailability(sku) -> branch attemptsLeft <= 0 'no' ×6 (1?)
            branch inventory.checkAvailability(sku) -> end 'yes' ×1 (1?)
            start -> activity CheckAvailability ×1
            timer -> unknown waitForStock ×5
            """.trimIndent(),
            recursion.edges,
        )
        val doubt = recursion.trace.ambiguous.single()
        assertEquals(Doubt.PATHS, doubt.doubt)
        assertEquals("WorkflowExecutionCompleted", doubt.eventType)
        assertEquals(listOf("branch attemptsLeft <= 0"), doubt.nodes.map(recursion.profiles::name))
    }

    @Test
    fun `two ways to continue as new that the history cannot tell apart are both counted, and reported`() {
        val chain = overlay("ContinueAsNewWorkflow")

        assertEquals(CloseStatus.CONTINUED_AS_NEW, chain.trace.status)
        assertEquals(emptyList(), chain.trace.unmapped)
        val doubt = chain.trace.ambiguous.single()
        assertEquals(Doubt.CANDIDATES, doubt.doubt)
        assertEquals(listOf("continueAsNew ContinueAsNewWorkflow@22", "continueAsNew ContinueAsNewWorkflow@26"), doubt.nodes.map(chain.profiles::name).sorted())
        assertEquals(doubt.nodes, chain.trace.closedAt)
        assertEquals(
            """
            start ×1
            timer ×1
            branch iteration >= limit ×1
            branch iteration % 2 == 0 ×1
            continueAsNew ContinueAsNewWorkflow@22 ×1 (1?)
            continueAsNew ContinueAsNewWorkflow@26 ×1 (1?)
            """.trimIndent(),
            chain.nodes,
        )
    }
}
