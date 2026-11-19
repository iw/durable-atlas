package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.overlay.testing.FakeTemporalServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `fetch` command against a stand-in for a Temporal server on the loopback interface, which
 * holds the histories the sample repository keeps of its workflows. Every invocation names its
 * own environment, so the settings of whoever runs the tests are never read.
 */
class FetchCommandTest {
    @TempDir
    lateinit var dir: Path

    private val server = FakeTemporalServer()
    private val address: String by lazy { server.listen() }
    private val out: Path get() = dir.resolve("histories")
    private val saved: Path = Path.of(System.getProperty("durable-atlas.fixtureDir")).resolve("histories")
    private val noon: Instant = Instant.parse("2026-11-17T12:00:00Z")

    @AfterEach
    fun stop() = server.close()

    /** The environment of an invocation: nothing but a configuration file inside the test's directory, and [variables]. */
    private fun environment(vararg variables: Pair<String, String>): Map<String, String> =
        mapOf("TEMPORAL_CONFIG_FILE" to dir.resolve("temporal.toml").toString()) + variables

    private fun fetch(
        vararg arguments: String,
        workflowType: String = "OrderWorkflow",
        limit: Int = 10,
        environment: Map<String, String> = environment("TEMPORAL_ADDRESS" to address),
    ): CliktCommandTestResult =
        durableAtlas(environment).test(listOf("fetch", "--workflow-type", workflowType, "--limit", limit.toString(), "--out", out.toString()) + arguments)

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun files(): List<String> = if (out.exists()) out.listDirectoryEntries().map { it.name }.sorted() else emptyList()

    /** The three executions of the composite workflow, the first two by workers of a version each. */
    private fun orders() {
        val paid = server.add(saved.resolve("OrderWorkflow.json"), noon, workflowId = "order-1001", runId = "run-a")
        val declined = server.add(saved.resolve("OrderWorkflow.payment-declined.json"), noon.plusSeconds(3600), workflowId = "order-1003", runId = "run-c")
        server.add(saved.resolve("OrderWorkflow.payment-timeout.json"), noon.plusSeconds(7200), workflowId = "order-1002", runId = "run-b")
        server.add(saved.resolve("TimerWorkflow.json"), noon, workflowId = "timer", runId = "run-t")
        server.add(FakeTemporalServer.ranBy(paid.history, "orders", "build-a"), noon.plusSeconds(86_400), workflowId = "order-2001", runId = "run-d")
        server.add(
            FakeTemporalServer.ranBy(FakeTemporalServer.ranBy(declined.history, "orders", "build-a"), "orders", "build-b", from = 4),
            noon.plusSeconds(90_000),
            workflowId = "order-2003",
            runId = "run-e",
        )
    }

    @Test
    fun `histories are written and summarised as JSON on stdout, logs go to stderr`() {
        orders()

        val result = fetch("--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(
            listOf("command", "address", "namespace", "workflowType", "query", "limit", "directory", "histories", "missing", "more"),
            summary.keys.toList(),
        )
        assertEquals("fetch", summary.text("command"))
        assertEquals(address, summary.text("address"))
        assertEquals("default", summary.text("namespace"))
        assertEquals("OrderWorkflow", summary.text("workflowType"))
        assertEquals("WorkflowType = 'OrderWorkflow'", summary.text("query"))
        assertEquals(10, summary.getValue("limit").jsonPrimitive.int)
        assertEquals(out.toRealPath(), Path.of(summary.text("directory")).toRealPath())
        assertEquals(0, summary.getValue("missing").jsonArray.size)
        assertFalse(summary.getValue("more").jsonPrimitive.boolean)

        val histories = summary.list("histories")
        val names = listOf(
            "OrderWorkflow.order-1001.run-a.json",
            "OrderWorkflow.order-1002.run-b.json",
            "OrderWorkflow.order-1003.run-c.json",
            "OrderWorkflow.order-2001.run-d.json",
            "OrderWorkflow.order-2003.run-e.json",
        )
        assertEquals(names, files())
        assertEquals(names.map { out.toRealPath().resolve(it) }, histories.map { Path.of(it.text("file")).toRealPath() })
        assertEquals(listOf("file", "workflowId", "runId", "startTime", "status", "events"), histories[0].keys.toList())
        assertEquals(listOf("order-1001", "run-a", "2026-11-17T12:00:00Z", "completed"), listOf("workflowId", "runId", "startTime", "status").map { histories[0].text(it) })
        assertEquals(79, histories[0].getValue("events").jsonPrimitive.int)
        assertEquals(listOf("completed", "completed", "failed", "completed", "failed"), histories.map { it.text("status") })

        // The worker version of each history, and all of them where an execution changed hands.
        assertEquals(
            listOf("file", "workflowId", "runId", "startTime", "status", "events", "workerVersion"),
            histories[3].keys.toList(),
        )
        assertEquals(mapOf("deployment" to "orders", "buildId" to "build-a"), histories[3].getValue("workerVersion").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals("build-b", histories[4].getValue("workerVersion").jsonObject.text("buildId"))
        assertEquals(listOf("build-a", "build-b"), histories[4].list("workerVersions").map { it.text("buildId") })

        assertEquals(
            "fetch: found 5 execution(s) of workflow type OrderWorkflow in namespace default on $address\n" +
                "fetch: wrote 5 history file(s) to ${out.toAbsolutePath()}\n",
            result.stderr,
        )

        // What was written is what the overlay reads.
        val overlaid = durableAtlas().test(
            listOf("overlay", "--ir", SampleIr.degraded.toString(), "--history", out.toString(), "--out", dir.resolve("overlay").toString(), "--format", "mermaid", "--json"),
        )
        assertEquals(ExitCode.SUCCESS, overlaid.statusCode, overlaid.stderr)
        val workflow = Json.parseToJsonElement(overlaid.stdout).jsonObject.list("workflows").single()
        assertEquals(5, workflow.getValue("executions").jsonPrimitive.int)
        assertEquals(0, workflow.getValue("events").jsonObject.getValue("unmapped").jsonPrimitive.int)
        assertEquals("build-b", workflow.list("histories")[4].getValue("workerVersion").jsonObject.text("buildId"))
    }

    @Test
    fun `without --json the summary is a table, a sentence and the directory`() {
        orders()

        val result = fetch("--limit", "2")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals(listOf("History", "Workflow", "id", "Status", "Events", "Worker", "version"), lines[0].split(Regex("\\s+")))
        // The server lists the most recent first.
        assertEquals(listOf("OrderWorkflow.order-2001.run-d.json", "order-2001", "completed", "79", "orders:build-a"), lines[1].split(Regex("\\s+")))
        assertEquals(
            listOf("OrderWorkflow.order-2003.run-e.json", "order-2003", "failed", "36", "orders:build-a,", "then", "orders:build-b"),
            lines[2].split(Regex("\\s+")),
        )
        assertEquals(
            "2 history file(s) of workflow type OrderWorkflow from namespace default on $address; more executions fit, raise --limit to fetch them",
            lines[3],
        )
        assertEquals(out.toRealPath(), Path.of(lines[4]).toRealPath())
        assertEquals(listOf(""), lines.drop(5))
        assertContains(result.stderr, "fetch: found 2 execution(s) of workflow type OrderWorkflow in namespace default on $address, and there are more\n")
    }

    @Test
    fun `the time range is part of the query`() {
        orders()

        val day = fetch("--since", "2026-11-17", "--until", "2026-11-18", "--json")
        assertEquals(ExitCode.SUCCESS, day.statusCode, day.stderr)
        assertEquals(
            "WorkflowType = 'OrderWorkflow' AND StartTime >= '2026-11-17T00:00:00Z' AND StartTime < '2026-11-18T00:00:00Z'",
            day.summary().text("query"),
        )
        assertEquals(listOf("order-1001", "order-1002", "order-1003"), day.summary().list("histories").map { it.text("workflowId") })

        // A time with an offset; the bound itself is part of the range.
        val afternoon = fetch("--since", "2026-11-17T14:00:00+01:00", "--json")
        assertEquals("WorkflowType = 'OrderWorkflow' AND StartTime >= '2026-11-17T13:00:00Z'", afternoon.summary().text("query"))
        assertEquals(
            listOf("order-1002", "order-1003", "order-2001", "order-2003"),
            afternoon.summary().list("histories").map { it.text("workflowId") },
        )

        val invalid = fetch("--since", "yesterday")
        assertEquals(ExitCode.FAILURE, invalid.statusCode)
        assertContains(invalid.stderr, "--since")
        assertContains(invalid.stderr, "neither a date like 2026-11-17 nor a time like 2026-11-17T09:30:00Z")
    }

    @Test
    fun `finding nothing is no failure`() {
        orders()

        val result = fetch("--json", workflowType = "NoSuchWorkflow")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(0, result.summary().getValue("histories").jsonArray.size)
        assertContains(result.stderr, "fetch: warning: no execution of workflow type NoSuchWorkflow fits; nothing was written\n")
        assertFalse(out.exists())
    }

    @Test
    fun `an option goes before the environment, and the environment before the profile`() {
        orders()
        server.namespaces += listOf("from-flag", "from-environment", "from-profile")
        server.add(saved.resolve("TimerWorkflow.json"), noon, workflowId = "by-flag", runId = "r1", namespace = "from-flag")
        server.add(saved.resolve("TimerWorkflow.json"), noon, workflowId = "by-environment", runId = "r2", namespace = "from-environment")
        server.add(saved.resolve("TimerWorkflow.json"), noon, workflowId = "by-profile", runId = "r3", namespace = "from-profile")
        Files.writeString(
            dir.resolve("temporal.toml"),
            """
            [profile.default]
            address = "$address"
            namespace = "from-profile"

            [profile.elsewhere]
            address = "127.0.0.1:1"
            namespace = "from-profile"
            """.trimIndent(),
        )
        fun namespaceAndId(result: CliktCommandTestResult): Pair<String, List<String>> {
            assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
            return result.summary().text("namespace") to result.summary().list("histories").map { it.text("workflowId") }
        }

        val profile = fetch("--json", workflowType = "TimerWorkflow", environment = environment())
        assertEquals("from-profile" to listOf("by-profile"), namespaceAndId(profile))

        val env = fetch("--json", workflowType = "TimerWorkflow", environment = environment("TEMPORAL_NAMESPACE" to "from-environment"))
        assertEquals("from-environment" to listOf("by-environment"), namespaceAndId(env))

        val flag = fetch(
            "--namespace", "from-flag", "--json",
            workflowType = "TimerWorkflow",
            environment = environment("TEMPORAL_NAMESPACE" to "from-environment"),
        )
        assertEquals("from-flag" to listOf("by-flag"), namespaceAndId(flag))

        // The profile the option names, with the address the option gives instead of the profile's.
        val named = fetch("--profile", "elsewhere", "--address", address, "--json", workflowType = "TimerWorkflow", environment = environment())
        assertEquals("from-profile" to listOf("by-profile"), namespaceAndId(named))
        val byEnvironment = fetch(
            "--json",
            workflowType = "TimerWorkflow",
            environment = environment("TEMPORAL_PROFILE" to "elsewhere", "TEMPORAL_ADDRESS" to address),
        )
        assertEquals("from-profile" to listOf("by-profile"), namespaceAndId(byEnvironment))

        val unknown = fetch("--profile", "nowhere", environment = environment())
        assertEquals(ExitCode.FAILURE, unknown.statusCode)
        assertContains(unknown.stderr, "fetch: failed: the Temporal configuration cannot be used: ")
        assertContains(unknown.stderr, "nowhere")
    }

    @Test
    fun `the API key comes from the option or the environment`() {
        orders()
        server.apiKey = "secret"

        val byOption = fetch("--api-key", "secret", "--no-tls", "--json")
        assertEquals(ExitCode.SUCCESS, byOption.statusCode, byOption.stderr)
        assertEquals(5, byOption.summary().getValue("histories").jsonArray.size)
        assertFalse("secret" in byOption.stdout + byOption.stderr)

        val byEnvironment = fetch("--json", environment = environment("TEMPORAL_ADDRESS" to address, "TEMPORAL_API_KEY" to "secret", "TEMPORAL_TLS" to "false"))
        assertEquals(ExitCode.SUCCESS, byEnvironment.statusCode, byEnvironment.stderr)
        assertTrue(server.authorizations.all { it == "Bearer secret" })

        val optionWins = fetch("--api-key", "guess", "--no-tls", environment = environment("TEMPORAL_ADDRESS" to address, "TEMPORAL_API_KEY" to "secret"))
        assertEquals(ExitCode.FAILURE, optionWins.statusCode)
        assertEquals("", optionWins.stdout)
        assertEquals(
            "fetch: failed: the Temporal server at $address refused the credentials for namespace default: Request unauthorized.\n",
            optionWins.stderr,
        )

        val none = fetch()
        assertEquals(ExitCode.FAILURE, none.statusCode)
        assertContains(none.stderr, "refused the credentials")
    }

    @Test
    fun `a server that cannot be reached, a namespace it does not know and settings that cannot be used are failures`() {
        orders()
        val free = ServerSocket(0).use { it.localPort }

        val unreachable = fetch("--address", "127.0.0.1:$free", "--connect-timeout", "2", "--json")
        assertEquals(ExitCode.FAILURE, unreachable.statusCode)
        assertEquals("", unreachable.stdout)
        assertContains(unreachable.stderr, "fetch: failed: the Temporal server at 127.0.0.1:$free cannot be reached: ")

        val namespace = fetch("--namespace", "nowhere")
        assertEquals(ExitCode.FAILURE, namespace.statusCode)
        assertEquals(
            "fetch: failed: the Temporal server at $address does not know the namespace nowhere: Namespace nowhere is not found.\n",
            namespace.stderr,
        )

        val certificate = fetch("--tls-cert-path", dir.resolve("cert.pem").toString(), "--tls-key-path", dir.resolve("key.pem").toString())
        assertEquals(ExitCode.FAILURE, certificate.statusCode)
        assertContains(certificate.stderr, "fetch: failed: the TLS settings cannot be used: ")

        // A server that does not speak TLS, asked over TLS.
        val tls = fetch("--tls", "--connect-timeout", "5")
        assertEquals(ExitCode.FAILURE, tls.statusCode)
        assertContains(tls.stderr, "fetch: failed: the Temporal server at $address cannot be reached: ")

        Files.writeString(dir.resolve("temporal.toml"), "[profile.default\n")
        val file = fetch()
        assertEquals(ExitCode.FAILURE, file.statusCode)
        assertContains(file.stderr, "fetch: failed: the Temporal configuration file cannot be read: ")

        assertFalse(out.exists())
    }

    @Test
    fun `bad arguments are usage errors`() {
        val missing = durableAtlas(environment()).test("fetch --limit 5 --out $out")
        assertEquals(ExitCode.FAILURE, missing.statusCode)
        assertContains(missing.stderr, "--workflow-type")

        val limit = fetch("--limit", "0")
        assertEquals(ExitCode.FAILURE, limit.statusCode)
        assertContains(limit.stderr, "--limit")

        val help = durableAtlas(environment()).test("fetch --help")
        assertEquals(ExitCode.SUCCESS, help.statusCode)
        for (option in listOf(
            "--workflow-type", "--limit", "--out", "--since", "--until", "--address", "--namespace", "--api-key", "--tls", "--no-tls",
            "--tls-cert-path", "--tls-key-path", "--tls-ca-path", "--tls-server-name", "--profile", "--connect-timeout", "--json",
        )) {
            assertContains(help.stdout, option)
        }
        assertContains(help.stdout, "TEMPORAL_ADDRESS")
    }
}
