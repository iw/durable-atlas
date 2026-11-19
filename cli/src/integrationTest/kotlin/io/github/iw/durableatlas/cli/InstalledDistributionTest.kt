package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.modelextractor.ExtractedModel
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import io.github.iw.durableatlas.overlay.testing.FakeTemporalServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the tool from its installed distribution, in a process of its own. Nothing tells that
 * process where the degraded-mode classpath is, so it has to find the copy the distribution ships.
 */
class InstalledDistributionTest {
    @TempDir
    lateinit var dir: Path

    private val installation: Path = Path.of(System.getProperty("durable-atlas.installDir"))

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    /** @param temporal the `TEMPORAL_*` variables of the process, instead of those this one has */
    private fun durableAtlas(vararg arguments: String, temporal: Map<String, String>? = null): Run {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val script = installation.resolve(if (windows) "bin/durable-atlas.bat" else "bin/durable-atlas")
        val stdout = dir.resolve("stdout.txt")
        val stderr = dir.resolve("stderr.txt")
        val builder = ProcessBuilder(listOf(script.toString()) + arguments)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
        if (temporal != null) {
            builder.environment().keys.removeIf { it.startsWith("TEMPORAL_") }
            builder.environment().putAll(temporal)
        }
        val process = builder.start()
        process.outputStream.close()
        return Run(process.waitFor(), Files.readString(stdout), Files.readString(stderr))
    }

    @Test
    fun `degraded mode works with the jars shipped in the distribution`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val out = dir.resolve("out")

        val run = durableAtlas(
            "model", "--repo", repository.project.toString(), "--commit", repository.head,
            "--out", out.toString(), "--mode", "degraded", "--json",
        )

        assertEquals(ExitCode.SUCCESS, run.exitCode, run.stderr)
        val summary = Json.parseToJsonElement(run.stdout).jsonObject
        assertEquals("degraded", summary.getValue("mode").jsonPrimitive.content)
        val model = ExtractedModel.read(Path.of(summary.getValue("directory").jsonPrimitive.content))
        val shipped = installation.resolve("lib/degraded").toRealPath()
        assertTrue(shipped.isDirectory())
        val jars = model.jars(model.manifests.single())
        assertTrue(jars.any { it.fileName.toString().startsWith("temporal-sdk-") }, jars.toString())
        assertTrue(jars.any { it.fileName.toString().startsWith("kotlin-stdlib-") }, jars.toString())
        for (jar in jars) {
            assertEquals(shipped, jar.toRealPath().parent, jar.toString())
        }
    }

    @Test
    fun `the sources of a model are analysed with the backend shipped in the distribution`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val models = dir.resolve("models")
        val extraction = durableAtlas(
            "model", "--repo", repository.project.toString(), "--commit", repository.head,
            "--out", models.toString(), "--mode", "degraded", "--json",
        )
        assertEquals(ExitCode.SUCCESS, extraction.exitCode, extraction.stderr)
        val model = Json.parseToJsonElement(extraction.stdout).jsonObject.getValue("directory").jsonPrimitive.content
        val out = dir.resolve("ir")

        // Nothing tells the process where the analysis backend is, and the process has to end by
        // itself although the analysis leaves threads behind.
        val run = durableAtlas("analyse", "--model", model, "--out", out.toString(), "--json")

        assertEquals(ExitCode.SUCCESS, run.exitCode, run.stderr)
        val summary = Json.parseToJsonElement(run.stdout).jsonObject
        assertEquals(30, summary.getValue("workflows").jsonArray.size)
        val inventory = InventoryJson.decode(Files.readString(out.resolve("inventory.json")))
        assertEquals(repository.head, inventory.commit)
        assertEquals(4, inventory.activityInterfaces.size)
        assertEquals(30, out.listDirectoryEntries("*.ir.json").size)
        val typed = IrJson.decode(Files.readString(out.resolve("TypedActivityWorkflow.ir.json")))
        assertEquals(repository.head, typed.commit)
        assertEquals(6, typed.stats.nodes)

        // The backend and what it runs on are kept apart from the tool's own jars.
        fun jars(directory: String) = installation.resolve(directory).listDirectoryEntries("*.jar").map { it.name }
        assertTrue(jars("lib/analysis").any { it.startsWith("analysis-api-standalone-for-ide-") })
        assertTrue(jars("lib/analysis").any { it.startsWith("analyser-") && it.endsWith("-backend.jar") }, jars("lib/analysis").toString())
        for (foreign in listOf("analysis-api", "kotlin-compiler", "core-", "util-", "kotlinx-coroutines", "opentelemetry")) {
            assertTrue(jars("lib").none { it.startsWith(foreign) }, "$foreign in ${jars("lib")}")
        }
        assertTrue(jars("lib/analysis").none { it.startsWith("kotlin-stdlib") }, jars("lib/analysis").toString())
    }

    @Test
    fun `the whole pipeline runs in one process, and its status is that of the run`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val out = dir.resolve("out")
        val arguments = arrayOf(
            "run", "--repo", repository.project.toString(), "--commit", repository.head,
            "--out", out.toString(), "--mode", "degraded",
        )

        val run = durableAtlas(*arguments)

        assertEquals(ExitCode.SUCCESS, run.exitCode, run.stderr)
        val lines = run.stdout.lines()
        assertTrue(lines.first().startsWith("Workflow type"), run.stdout)
        assertEquals("30 workflow(s) drawn from the degraded model of ${repository.head}", lines[31])
        assertTrue(Files.isRegularFile(Path.of(lines[32])), lines[32])
        assertEquals(30, out.resolve("ir").resolve(repository.head).listDirectoryEntries("*.ir.json").size)
        assertTrue(Files.isRegularFile(out.resolve("diagrams").resolve(repository.head).resolve("index.md")))

        // The limit on unknown nodes reaches the process's exit status.
        val strict = durableAtlas(*arguments, "--max-unresolved-pct", "0", "--json")
        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, strict.exitCode, strict.stderr)
        assertEquals(2, Json.parseToJsonElement(strict.stdout).jsonObject.getValue("exitCode").jsonPrimitive.content.toInt())
    }

    @Test
    fun `histories are laid over the IR with the Temporal SDK shipped in the distribution`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val out = dir.resolve("out")
        val analysed = durableAtlas(
            "run", "--repo", repository.project.toString(), "--commit", repository.head,
            "--out", out.toString(), "--mode", "degraded", "--format", "svg",
        )
        assertEquals(ExitCode.SUCCESS, analysed.exitCode, analysed.stderr)
        val overlay = dir.resolve("overlay")

        val run = durableAtlas(
            "overlay", "--ir", out.resolve("ir").resolve(repository.head).toString(), "--history", repository.project.resolve("histories").toString(),
            "--out", overlay.toString(), "--json",
        )

        assertEquals(ExitCode.SUCCESS, run.exitCode, run.stderr)
        val summary = Json.parseToJsonElement(run.stdout).jsonObject
        assertEquals(30, summary.getValue("workflows").jsonArray.size)
        assertEquals(0, summary.getValue("unmatched").jsonArray.size)
        assertTrue(Files.isRegularFile(overlay.resolve("OrderWorkflow.coverage.svg")))
        assertTrue(Files.isRegularFile(overlay.resolve("executions/OrderWorkflow.payment-declined.svg")))
        // The plain diagram the overlays are drawn on is the one `run` drew for the same IR, byte for byte.
        assertEquals(
            Files.readString(out.resolve("diagrams").resolve(repository.head).resolve("OrderWorkflow.svg")),
            Files.readString(overlay.resolve("OrderWorkflow.svg")),
        )
        // The SDK logs through SLF4J, which is given nowhere to log to and does not complain about it.
        assertTrue(run.stderr.lines().filter { it.isNotEmpty() }.all { it.startsWith("overlay: ") }, run.stderr)

        // Histories are read with the Temporal SDK's own types, so the SDK, the protocol classes it
        // comes with and the library it parses JSON with are now among the tool's own jars. That
        // is a second copy of the SDK: the one under lib/degraded is the class path of the code
        // that is analysed in degraded mode, and is never loaded by the tool.
        fun jars(directory: String) = installation.resolve(directory).listDirectoryEntries("*.jar").map { it.name }
        for (needed in listOf("temporal-sdk-", "temporal-serviceclient-", "protobuf-java-", "json-path-", "slf4j-nop-")) {
            assertTrue(jars("lib").any { it.startsWith(needed) }, "$needed in ${jars("lib")}")
        }
        // None of it reaches the analysis backend, whose class loader sees the JDK, Kotlin and the tool's own types only.
        assertTrue(jars("lib/analysis").none { it.startsWith("temporal-") || it.startsWith("grpc-") || it.startsWith("protobuf-") }, jars("lib/analysis").toString())
    }

    @Test
    fun `histories are fetched from a server with the Temporal client shipped in the distribution`() {
        val histories = Path.of(System.getProperty("durable-atlas.fixtureDir")).resolve("histories")
        val out = dir.resolve("fetched")
        // The settings come from a profile of a configuration file, which the distribution has to be able to read.
        val configuration = dir.resolve("temporal.toml")

        FakeTemporalServer().use { server ->
            val noon = Instant.parse("2026-11-17T12:00:00Z")
            server.namespaces += "orders"
            server.apiKey = "secret"
            val paid = server.add(histories.resolve("OrderWorkflow.json"), noon, workflowId = "order-1001", runId = "run-a", namespace = "orders")
            server.add(FakeTemporalServer.ranBy(paid.history, "orders", "build-a"), noon.plusSeconds(60), workflowId = "order-2001", runId = "run-b", namespace = "orders")
            server.add(histories.resolve("TimerWorkflow.json"), noon, workflowId = "timer", runId = "run-t", namespace = "orders")
            val address = server.listen()
            Files.writeString(
                configuration,
                """
                [profile.sample]
                address = "$address"
                namespace = "orders"
                api_key = "secret"

                [profile.sample.tls]
                disabled = true
                """.trimIndent(),
            )
            val temporal = mapOf("TEMPORAL_CONFIG_FILE" to configuration.toString())

            val run = durableAtlas("fetch", "--workflow-type", "OrderWorkflow", "--limit", "10", "--out", out.toString(), "--profile", "sample", "--json", temporal = temporal)

            assertEquals(ExitCode.SUCCESS, run.exitCode, run.stderr)
            val summary = Json.parseToJsonElement(run.stdout).jsonObject
            assertEquals(address, summary.getValue("address").jsonPrimitive.content)
            assertEquals("orders", summary.getValue("namespace").jsonPrimitive.content)
            val fetched = summary.getValue("histories").jsonArray.map { it.jsonObject }
            assertEquals(listOf("order-1001", "order-2001"), fetched.map { it.getValue("workflowId").jsonPrimitive.content })
            assertEquals("build-a", fetched[1].getValue("workerVersion").jsonObject.getValue("buildId").jsonPrimitive.content)
            assertEquals(
                listOf("OrderWorkflow.order-1001.run-a.json", "OrderWorkflow.order-2001.run-b.json"),
                out.listDirectoryEntries().map { it.name }.sorted(),
            )
            assertTrue(server.authorizations.isNotEmpty() && server.authorizations.all { it == "Bearer secret" })
            // Connecting, the transport and the reader of the configuration file say nothing of their own: not on Java 17, and not on later ones.
            assertEquals(
                listOf(
                    "fetch: found 2 execution(s) of workflow type OrderWorkflow in namespace orders on $address",
                    "fetch: wrote 2 history file(s) to ${out.toRealPath()}",
                ),
                run.stderr.lines().filter { it.isNotEmpty() }.map { it.replace(out.toString(), out.toRealPath().toString()) },
            )

            // A server that refuses the key ends the process with status 1 and one line that says why.
            val refused = durableAtlas(
                "fetch", "--workflow-type", "OrderWorkflow", "--limit", "10", "--out", dir.resolve("refused").toString(),
                "--profile", "sample", "--api-key", "guess",
                temporal = temporal,
            )
            assertEquals(ExitCode.FAILURE, refused.exitCode)
            assertEquals("", refused.stdout)
            assertEquals(
                "fetch: failed: the Temporal server at $address refused the credentials for namespace orders: Request unauthorized.\n",
                refused.stderr,
            )
        }

        // What was fetched is laid over the IR like any other history.
        val repository = SampleRepository.create(dir.resolve("repo"))
        val analysed = durableAtlas(
            "run", "--repo", repository.project.toString(), "--commit", repository.head,
            "--out", dir.resolve("out").toString(), "--mode", "degraded", "--format", "mermaid",
        )
        assertEquals(ExitCode.SUCCESS, analysed.exitCode, analysed.stderr)
        val overlaid = durableAtlas(
            "overlay", "--ir", dir.resolve("out/ir").resolve(repository.head).toString(), "--history", out.toString(),
            "--out", dir.resolve("overlay").toString(), "--format", "mermaid", "--json",
        )
        assertEquals(ExitCode.SUCCESS, overlaid.exitCode, overlaid.stderr)
        val workflow = Json.parseToJsonElement(overlaid.stdout).jsonObject.getValue("workflows").jsonArray.single().jsonObject
        assertEquals(2, workflow.getValue("executions").jsonPrimitive.content.toInt())
        assertEquals(0, workflow.getValue("events").jsonObject.getValue("unmapped").jsonPrimitive.content.toInt())

        // The SDK's reader of Temporal's environment and configuration file, and the library it reads the file with.
        fun jars(directory: String) = installation.resolve(directory).listDirectoryEntries("*.jar").map { it.name }
        for (needed in listOf("temporal-envconfig-", "jackson-dataformat-toml-", "grpc-netty-shaded-")) {
            assertTrue(jars("lib").any { it.startsWith(needed) }, "$needed in ${jars("lib")}")
        }
        assertTrue(jars("lib/analysis").none { it.startsWith("temporal-") || it.startsWith("jackson-dataformat-") }, jars("lib/analysis").toString())
    }

    @Test
    fun `an analysis that fails ends the process with status 1`() {
        val run = durableAtlas("analyse", "--model", dir.toString(), "--out", dir.resolve("ir").toString())

        assertEquals(ExitCode.FAILURE, run.exitCode, run.stderr)
        assertEquals("", run.stdout)
        assertTrue(run.stderr.startsWith("analyse: failed: No model index at "), run.stderr)
    }

    @Test
    fun `the process exits with status 3 when it falls back to degraded mode`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val commit = repository.commit("Remove the wrapper") { project -> Files.delete(project.resolve("gradlew")) }
        val out = dir.resolve("out")

        val run = durableAtlas("model", "--repo", repository.project.toString(), "--commit", commit, "--out", out.toString())

        assertEquals(ExitCode.DEGRADED, run.exitCode, run.stderr)
        assertTrue(run.stdout.startsWith("degraded model of $commit"), run.stdout)
    }
}
