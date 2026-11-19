package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.overlay.fetch.ConnectionFlags
import io.github.iw.durableatlas.overlay.fetch.ConnectionSettings
import io.github.iw.durableatlas.overlay.fetch.FetchException
import io.github.iw.durableatlas.overlay.fetch.FetchRequest
import io.github.iw.durableatlas.overlay.fetch.FetchSummary
import io.github.iw.durableatlas.overlay.fetch.HistoryFetcher
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import io.github.iw.durableatlas.overlay.testing.FakeTemporalServer
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Histories
import io.grpc.Status
import io.temporal.api.history.v1.History
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fetching from a stand-in for a server that is reached without a socket, except where a socket is the point. */
class HistoryFetcherTest {
    @TempDir
    lateinit var dir: Path

    private val server = FakeTemporalServer()
    private val out: Path get() = dir.resolve("histories")
    private val log = ArrayList<String>()
    private val noon: Instant = Instant.parse("2026-11-17T12:00:00Z")

    @AfterEach
    fun stop() = server.close()

    /** Settings that read nothing from the machine the tests run on. */
    private fun settings(flags: ConnectionFlags = ConnectionFlags()): ConnectionSettings =
        ConnectionSettings.resolve(flags, mapOf("TEMPORAL_CONFIG_FILE" to dir.resolve("temporal.toml").toString()))

    private fun fetch(
        workflowType: String = "W",
        limit: Int = 10,
        flags: ConnectionFlags = ConnectionFlags(),
        from: Instant? = null,
        before: Instant? = null,
    ): FetchSummary =
        HistoryFetcher(log::add).fetch(FetchRequest(workflowType, limit, out, from, before), settings(flags), server.channel())

    /** A short history of a workflow type; [steps] tells one from another. */
    private fun history(workflowType: String = "W", steps: Int = 1): History =
        Histories(workflowType).apply {
            repeat(steps) {
                workflowTask()
                ran("A")
            }
            workflowTask()
            completed()
        }.build()

    /** Adds [count] executions of a workflow type, each started a minute after the one before. */
    private fun executions(count: Int, workflowType: String = "W"): List<FakeTemporalServer.Execution> =
        (1..count).map { number ->
            server.add(history(workflowType), noon.plusSeconds(60L * number), workflowId = "w-$number", runId = "run-%04d".format(number))
        }

    private fun files(): List<String> = if (out.exists()) out.listDirectoryEntries().map { it.name }.sorted() else emptyList()

    @Test
    fun `every execution of the workflow type is written to a file of its own, in the form the overlay reads`() {
        val order = server.add(Fixtures.sampleHistories.resolve("OrderWorkflow.json"), noon, workflowId = "order-1001", runId = "run-a")
        server.add(Fixtures.sampleHistories.resolve("OrderWorkflow.payment-declined.json"), noon.plusSeconds(1), workflowId = "order-1003", runId = "run-b")
        server.add(Fixtures.sampleHistories.resolve("TimerWorkflow.json"), noon, workflowId = "timer", runId = "run-c")

        val summary = fetch("OrderWorkflow")

        assertEquals(listOf("OrderWorkflow.order-1001.run-a.json", "OrderWorkflow.order-1003.run-b.json"), files())
        assertEquals(files(), summary.histories.map { it.file })
        assertEquals(out.toAbsolutePath(), summary.outDir)
        assertEquals("127.0.0.1:7233", summary.address)
        assertEquals("default", summary.namespace)
        assertEquals("WorkflowType = 'OrderWorkflow'", summary.query)
        assertEquals(emptyList(), summary.missing.map { it.workflowId })
        assertFalse(summary.more)

        val first = summary.histories[0]
        assertEquals("order-1001", first.workflowId)
        assertEquals("run-a", first.runId)
        assertEquals(noon, first.started)
        assertEquals(CloseStatus.COMPLETED, first.status)
        assertEquals(79, first.events)
        assertEquals(CloseStatus.FAILED, summary.histories[1].status)

        // The file holds what the server has, and reads as the history it is a copy of.
        val written = out.resolve(first.file)
        assertEquals(HistoryJson.encode(order.history), Files.readString(written))
        assertTrue(Files.readString(written).endsWith("}\n"))
        val read = HistoryJson.read(written)
        assertEquals("OrderWorkflow", read.workflowType)
        assertEquals(79, read.events)
        assertEquals(Fixtures.sampleHistory("OrderWorkflow").occurrences, read.occurrences)

        assertEquals(
            listOf("found 2 execution(s) of workflow type OrderWorkflow in namespace default on 127.0.0.1:7233"),
            log,
        )
    }

    @Test
    fun `no more executions are asked for than the limit allows, a page at a time`() {
        executions(250)

        val summary = fetch(limit = 230)

        assertEquals(230, summary.histories.size)
        assertEquals(230, files().size)
        assertTrue(summary.more)
        assertEquals(listOf(100, 100, 30), server.listed.map { it.pageSize })
        assertEquals(listOf("", "100", "200"), server.listed.map { it.nextPageToken.toStringUtf8() })
        assertEquals(230, server.read.size)
        // The server lists the most recent first, so the oldest twenty are the ones left out.
        assertEquals((21..250).map { "w-$it" }.toSet(), summary.histories.map { it.workflowId }.toSet())
        assertContains(log.single(), "found 230 execution(s) of workflow type W in namespace default on 127.0.0.1:7233, and there are more")
    }

    @Test
    fun `a server that sends smaller pages is asked until the limit is reached or nothing is left`() {
        executions(25)
        server.maxPageSize = 10

        val limited = fetch(limit = 12)
        assertEquals(12, limited.histories.size)
        assertTrue(limited.more)
        assertEquals(listOf(12, 2), server.listed.map { it.pageSize })

        server.listed.clear()
        val all = fetch(limit = 100)
        assertEquals(25, all.histories.size)
        assertFalse(all.more)
        assertEquals(listOf(100, 90, 80), server.listed.map { it.pageSize })
        assertEquals(listOf("", "10", "20"), server.listed.map { it.nextPageToken.toStringUtf8() })
    }

    @Test
    fun `exactly as many executions as the limit leaves nothing more`() {
        executions(3)

        val summary = fetch(limit = 3)

        assertEquals(3, summary.histories.size)
        assertFalse(summary.more)
    }

    @Test
    fun `the query names the workflow type and the bounds on the start time, and nothing else`() {
        executions(5)
        val from = noon.plusSeconds(120)
        val before = noon.plusSeconds(240)

        val between = fetch(from = from, before = before)
        assertEquals("WorkflowType = 'W' AND StartTime >= '2026-11-17T12:02:00Z' AND StartTime < '2026-11-17T12:04:00Z'", between.query)
        assertEquals(between.query, server.listed.last().query)
        // The lower bound is part of the range, the upper one is not.
        assertEquals(listOf("w-2", "w-3"), between.histories.map { it.workflowId })

        assertEquals("WorkflowType = 'W' AND StartTime >= '2026-11-17T12:02:00Z'", fetch(from = from).query)
        assertEquals(listOf("w-1", "w-2", "w-3"), fetch(before = before).histories.map { it.workflowId })
        assertEquals("WorkflowType = 'W' AND StartTime < '2026-11-17T12:04:00Z'", server.listed.last().query)
        assertEquals("default", server.listed.last().namespace)
    }

    @Test
    fun `a quote in the workflow type is written twice`() {
        server.add(history("it's"), noon, workflowId = "w", runId = "r")

        val summary = fetch("it's")

        assertEquals("WorkflowType = 'it''s'", summary.query)
        assertEquals(listOf("it_s.w.r.json"), files())
    }

    @Test
    fun `file names are the same every time, safe on every file system, and in a fixed order`() {
        server.add(history(), noon, workflowId = "tenant/42: order #7", runId = "0195f3c2-aaaa")
        server.add(history(), noon.plusSeconds(1), workflowId = "tenant/42: order #7", runId = "0195f3c2-bbbb")
        server.add(history(), noon.plusSeconds(2), workflowId = "..", runId = "0195f3c2-cccc")
        server.add(history(), noon.plusSeconds(3), workflowId = "x".repeat(300), runId = "0195f3c2-dddd")
        server.add(history(), noon.plusSeconds(4), workflowId = "Bestellung-äöü", runId = "0195f3c2-eeee")

        val names = fetch().histories.map { it.file }

        assertEquals(
            listOf(
                "W.Bestellung-___.0195f3c2-eeee.json",
                "W._.0195f3c2-cccc.json",
                "W.tenant_42__order__7.0195f3c2-aaaa.json",
                "W.tenant_42__order__7.0195f3c2-bbbb.json",
                "W.${"x".repeat(80)}.0195f3c2-dddd.json",
            ),
            names,
        )
        assertEquals(names, files())
        assertTrue(names.all { Regex("[A-Za-z0-9._-]+").matches(it) })

        // A second fetch writes the same files with the same bytes.
        val before = files().associateWith { Files.readString(out.resolve(it)) }
        assertEquals(names, fetch().histories.map { it.file })
        assertEquals(before, files().associateWith { Files.readString(out.resolve(it)) })
    }

    @Test
    fun `a long history is read page by page`() {
        val long = server.add(history(steps = 20), noon, workflowId = "w", runId = "r")
        server.historyPageSize = 7

        val summary = fetch()

        assertEquals(long.history.eventsCount, summary.histories.single().events)
        assertEquals(HistoryJson.encode(long.history), Files.readString(out.resolve("W.w.r.json")))
        assertEquals((0 until long.history.eventsCount step 7).map { if (it == 0) "" else it.toString() }, server.read.map { it.nextPageToken.toStringUtf8() })
        assertTrue(server.read.all { it.execution.workflowId == "w" && it.execution.runId == "r" && it.namespace == "default" })
    }

    @Test
    fun `the worker versions of every history are part of the summary`() {
        val a = WorkerVersion("orders", "build-a")
        val b = WorkerVersion("orders", "build-b")
        server.add(FakeTemporalServer.ranBy(history(), "orders", "build-a"), noon, workflowId = "one", runId = "r1")
        server.add(FakeTemporalServer.ranBy(FakeTemporalServer.ranBy(history(steps = 3), "orders", "build-a"), "orders", "build-b", from = 2), noon, workflowId = "two", runId = "r2")
        server.add(history(), noon, workflowId = "zero", runId = "r0")

        val histories = fetch().histories.associateBy { it.workflowId }

        assertEquals(listOf(a), histories.getValue("one").workerVersions)
        assertEquals(a, histories.getValue("one").workerVersion)
        assertEquals(listOf(a, b), histories.getValue("two").workerVersions)
        assertEquals(b, histories.getValue("two").workerVersion)
        assertEquals(emptyList(), histories.getValue("zero").workerVersions)
        assertNull(histories.getValue("zero").workerVersion)
    }

    @Test
    fun `an execution of an unfinished workflow is fetched as far as it has come`() {
        val running = Histories("W").apply { workflowTask(); activity("A") }.build()
        server.add(running, noon, workflowId = "w", runId = "r")

        assertEquals(CloseStatus.RUNNING, fetch().histories.single().status)
    }

    @Test
    fun `an execution whose history is gone by the time it is asked for is reported and left out`() {
        val executions = executions(3)
        server.forgetHistory(executions[1])

        val summary = fetch()

        assertEquals(listOf("w-1", "w-3"), summary.histories.map { it.workflowId })
        assertEquals(listOf("w-2" to "run-0002"), summary.missing.map { it.workflowId to it.runId })
        assertEquals(2, files().size)
        assertContains(log, "warning: the server has no history for w-2 (run run-0002) any more; left out")
    }

    @Test
    fun `finding nothing writes nothing and is no failure`() {
        executions(2)

        val summary = fetch("Other")

        assertEquals(emptyList(), summary.histories)
        assertFalse(out.exists())
        assertEquals(listOf("found 0 execution(s) of workflow type Other in namespace default on 127.0.0.1:7233"), log)

        // Names the server's query language cannot be trusted with get a word of warning.
        fetch("a\\b")
        assertContains(log.last(), "does not match names that hold \\ or \" reliably")
    }

    @Test
    fun `namespace and API key are those of the settings`() {
        server.namespaces += "orders"
        server.apiKey = "secret"
        server.add(history(), noon, workflowId = "w", runId = "r", namespace = "orders")
        server.add(history(), noon, workflowId = "elsewhere", runId = "r2")

        val summary = fetch(flags = ConnectionFlags(namespace = "orders", apiKey = "secret", tls = false))

        assertEquals("orders", summary.namespace)
        assertEquals(listOf("w"), summary.histories.map { it.workflowId })
        assertEquals(listOf("orders"), server.listed.map { it.namespace })
        assertEquals(listOf("orders"), server.read.map { it.namespace })
        assertTrue(server.authorizations.isNotEmpty())
        assertTrue(server.authorizations.all { it == "Bearer secret" }, server.authorizations.toString())
    }

    @Test
    fun `a namespace the server does not know is a failure that names it`() {
        executions(1)

        val failure = assertFailsWith<FetchException> { fetch(flags = ConnectionFlags(namespace = "nowhere")) }

        assertEquals(
            "the Temporal server at 127.0.0.1:7233 does not know the namespace nowhere: Namespace nowhere is not found.",
            failure.message,
        )
        assertFalse(out.exists())
    }

    @Test
    fun `credentials the server refuses are a failure that says so`() {
        executions(1)
        server.apiKey = "secret"

        val wrong = assertFailsWith<FetchException> { fetch(flags = ConnectionFlags(apiKey = "guess", tls = false)) }
        assertEquals("the Temporal server at 127.0.0.1:7233 refused the credentials for namespace default: Request unauthorized.", wrong.message)

        val none = assertFailsWith<FetchException> { fetch() }
        assertContains(none.message!!, "refused the credentials")

        server.apiKey = null
        server.failure = Status.PERMISSION_DENIED.withDescription("Request unauthorized.")
        val denied = assertFailsWith<FetchException> { fetch() }
        assertContains(denied.message!!, "refused the credentials for namespace default: Request unauthorized.")
        assertFalse(out.exists())
    }

    @Test
    fun `a server that is not there is a failure that names its address`() {
        val free = ServerSocket(0).use { it.localPort }
        val fetcher = HistoryFetcher(log::add, connectTimeout = Duration.ofSeconds(1))

        val failure = assertFailsWith<FetchException> {
            fetcher.fetch(FetchRequest("W", 1, out), settings(ConnectionFlags(address = "127.0.0.1:$free")))
        }

        assertContains(failure.message!!, "the Temporal server at 127.0.0.1:$free cannot be reached: ")
        assertFalse(out.exists())
    }

    @Test
    fun `a server reached over a socket is fetched from like any other`() {
        executions(2)
        val address = server.listen()

        val summary = HistoryFetcher(log::add).fetch(FetchRequest("W", 5, out), settings(ConnectionFlags(address = address)))

        assertEquals(address, summary.address)
        assertEquals(2, files().size)
    }

    @Test
    fun `a busy server is asked again, and a query it refuses is a failure`() {
        executions(2)
        server.busyFor = 2

        assertEquals(2, fetch().histories.size)
        assertEquals(3, server.listed.size)

        server.failure = Status.INVALID_ARGUMENT.withDescription("invalid query")
        val refused = assertFailsWith<FetchException> { fetch() }
        assertEquals("the Temporal server at 127.0.0.1:7233 refused the query WorkflowType = 'W': invalid query", refused.message)

        server.failure = Status.INTERNAL.withDescription("out of order")
        val broken = assertFailsWith<FetchException> { fetch() }
        assertEquals("the Temporal server at 127.0.0.1:7233 failed to list the executions: INTERNAL: out of order", broken.message)
    }

    @Test
    fun `a request without a workflow type or with a limit below one is refused`() {
        assertFailsWith<IllegalArgumentException> { FetchRequest("", 1, out) }
        assertFailsWith<IllegalArgumentException> { FetchRequest("W", 0, out) }
    }
}
