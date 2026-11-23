package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The commands on a commit of the sample repository with an activity that makes requests of
 * other services, and the configuration that says where they go: `--client`, `--no-requests`, and
 * what a model made before the configuration files were exported gives.
 */
class RequestCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }

    /** The commit with the workflow whose activity sends an HTTP request and calls a client. */
    private val requesting: String by lazy {
        repository.commit("Add a workflow whose activity makes requests") { project ->
            val sources = Files.createDirectories(project.resolve("$SOURCES/pricing"))
            Files.writeString(sources.resolve("PricingWorkflow.kt"), WORKFLOW.trimIndent() + "\n")
            val resources = Files.createDirectories(project.resolve("workflows/src/main/resources"))
            Files.writeString(resources.resolve("application.yaml"), CONFIGURATION.trimIndent() + "\n")
        }
    }

    private fun command(vararg arguments: String): CliktCommandTestResult = durableAtlas().test(arguments.toList())

    private fun run(vararg arguments: String): CliktCommandTestResult =
        command("run", "--repo", repository.project.toString(), "--commit", requesting, "--out", out.toString(), "--mode", "degraded", *arguments)

    private fun requests(ir: Path): List<Request> {
        val file = Files.list(ir).use { files -> files.toList().single { it.fileName.toString().startsWith("PricingWorkflow") } }
        return IrJson.decode(Files.readString(file)).nodes.single { it.name == "Quote" }.requests
    }

    @Test
    fun `run reads the requests of an activity with the clients it is told of, and draws them unless told not to`() {
        val result = run("--client", "PriceClient.invoke:functionName", "--client", "PriceClient.history:2", "--json")
        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val ir = out.resolve("ir").resolve(requesting)
        val requests = requests(ir)
        assertEquals(listOf("http", "client", "client"), requests.map { it.kind.name.lowercase() })
        val http = requests.first()
        assertEquals("GET", http.method)
        assertEquals("{pricing.url}/quotes/{sku}", http.target)
        assertEquals(listOf("pricing.url"), http.keys)
        assertEquals("http://pricing.example.com", http.host)
        assertEquals(listOf("price-quote", "price-history"), requests.drop(1).map { it.operation })
        // Each operation named by a constant says which.
        assertEquals(
            listOf("com.example.fixture.workflows.pricing.PricingActivitiesImpl.QUOTE", "com.example.fixture.workflows.pricing.PricingActivitiesImpl.HISTORY"),
            requests.drop(1).map { it.constant },
        )
        assertContains(result.stderr, "found 3 request(s) of 1 activity type(s) after ")
        assertFalse("--force makes it again" in result.stderr, result.stderr)

        val diagrams = out.resolve("diagrams").resolve(requesting)
        val page = Files.readString(diagrams.resolve("PricingWorkflow.md"))
        assertContains(page, "## Requests\n")
        assertContains(page, "| `Quote` | [`GET {pricing.url}/quotes/{sku}`](endpoints/index.md#get-pricing-quotessku) | `http://pricing.example.com` | `String` |")
        // The endpoints page and an OpenAPI document per service, which the summary counts.
        val summary = Json.parseToJsonElement(result.stdout).jsonObject.getValue("endpoints").jsonObject
        assertEquals(
            listOf(diagrams.resolve("endpoints/index.html").toString(), diagrams.resolve("endpoints/index.md").toString()),
            summary.getValue("pages").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf(diagrams.resolve("endpoints/pricing.openapi.json").toString(), diagrams.resolve("endpoints/PriceClient.openapi.json").toString()),
            summary.getValue("openapi").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(listOf(2, 3), listOf("services", "operations").map { summary.getValue(it).jsonPrimitive.int })
        assertContains(Files.readString(diagrams.resolve("endpoints/index.md")), "### FN price-quote\n")
        assertContains(Files.readString(diagrams.resolve("PricingWorkflow.svg")), "GET {pricing.url}/quotes/{sku} · host http://pricing.example.com")

        // Drawn without them, the diagrams are those of the document without requests; the IR still has them.
        val plain = command("run", "--repo", repository.project.toString(), "--commit", requesting, "--out", dir.resolve("plain").toString(), "--mode", "degraded", "--no-requests")
        assertEquals(ExitCode.SUCCESS, plain.statusCode, plain.stderr)
        val plainDiagrams = dir.resolve("plain").resolve("diagrams").resolve(requesting)
        assertFalse("## Requests" in Files.readString(plainDiagrams.resolve("PricingWorkflow.md")))
        assertFalse("pricing.url" in Files.readString(plainDiagrams.resolve("PricingWorkflow.svg")))
        assertFalse(Files.exists(plainDiagrams.resolve("endpoints")))
        assertEquals(1, requests(dir.resolve("plain").resolve("ir").resolve(requesting)).size)

        val rendered = command("render", "--ir", ir.toString(), "--out", dir.resolve("rendered").toString(), "--no-requests")
        assertEquals(ExitCode.SUCCESS, rendered.statusCode, rendered.stderr)
        assertEquals(
            Files.readString(plainDiagrams.resolve("PricingWorkflow.svg")),
            Files.readString(dir.resolve("rendered").resolve("PricingWorkflow.svg")).replace("diagrams/$requesting/", ""),
        )
    }

    @Test
    fun `without --client only the HTTP requests are read`() {
        assertEquals(ExitCode.SUCCESS, run().statusCode)
        assertEquals(listOf("GET"), requests(out.resolve("ir").resolve(requesting)).map { it.method })
    }

    @Test
    fun `a model made before the configuration files were exported gives keys without hosts, and analyse says once how to make it again`() {
        assertEquals(ExitCode.SUCCESS, run().statusCode)
        val model = out.resolve("model").resolve(requesting)
        val manifest = model.resolve("modules/_.json")
        val text = Files.readString(manifest)
        assertContains(text, "\"resources\": [")
        // As the earlier version of the tool wrote it.
        Files.writeString(manifest, text.replace(Regex(",\\n  \"resources\": \\[[^\\]]*\\]"), ""))

        val analysed = command("analyse", "--model", model.toString(), "--out", dir.resolve("ir").toString())
        assertEquals(ExitCode.SUCCESS, analysed.statusCode, analysed.stderr)
        val warnings = analysed.stderr.lines().filter { "--force makes it again" in it }
        assertEquals(
            listOf(
                "analyse: warning: the model holds no application.yaml, application.yml, application.properties, so no request has a host; " +
                    "a model made by an earlier version of the tool holds none: --force makes it again",
            ),
            warnings,
        )
        val request = requests(dir.resolve("ir")).single()
        assertEquals(listOf("pricing.url"), request.keys)
        assertEquals(null, request.host)
    }

    @Test
    fun `a client that is not written as the form says is refused, and the help names both options`() {
        for (bad in listOf("invoke()", "Client.", "invoke:0", "invoke:a:b")) {
            val result = command("analyse", "--model", dir.toString(), "--out", out.toString(), "--client", bad)
            assertEquals(ExitCode.FAILURE, result.statusCode, bad)
            assertContains(result.stderr, "does not name a client")
        }
        for (name in listOf("analyse", "run")) {
            val help = command(name, "--help").stdout.replace(Regex("\\s+"), " ")
            assertContains(help, "--client=<spec> A client of the repository's own whose calls are requests of the activities that make them, written [Type.]method[:parameter]")
            assertContains(help, "'ServiceClient.invoke:functionName'")
        }
        for (name in listOf("run", "render", "overlay")) {
            val help = command(name, "--help").stdout.replace(Regex("\\s+"), " ")
            assertContains(help, "--no-requests Draw activities without what they ask of other services")
            assertContains(help, "and no endpoints page or OpenAPI documents.")
        }
        assertContains(command("render", "--help").stdout.replace(Regex("\\s+"), " "), "an endpoints page with an OpenAPI document per service")
        assertTrue("--client" !in command("render", "--help").stdout)
    }

    private companion object {
        const val SOURCES = "workflows/src/main/kotlin/com/example/fixture/workflows"

        const val WORKFLOW = """
            package com.example.fixture.workflows.pricing

            import io.temporal.activity.ActivityInterface
            import io.temporal.activity.ActivityOptions
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.net.URI
            import java.net.http.HttpClient
            import java.net.http.HttpRequest
            import java.net.http.HttpResponse
            import java.time.Duration
            import org.eclipse.microprofile.config.inject.ConfigProperty
            import org.example.pricing.PriceClient

            @ActivityInterface
            interface PricingActivities {
                fun quote(sku: String): Long
            }

            class PricingActivitiesImpl(
                @ConfigProperty(name = "pricing.url") private val pricingUrl: String,
                private val prices: PriceClient,
            ) : PricingActivities {
                private val http = HttpClient.newHttpClient()

                override fun quote(sku: String): Long {
                    val body = http.send(HttpRequest.newBuilder(URI.create("${'$'}pricingUrl/quotes/${'$'}sku")).build(), HttpResponse.BodyHandlers.ofString()).body()
                    prices.invoke<Long>(functionName = QUOTE, payload = body)
                    return prices.history(sku, HISTORY)
                }

                companion object {
                    const val QUOTE = "price-quote"
                    const val HISTORY = "price-history"
                }
            }

            @WorkflowInterface
            interface PricingWorkflow {
                @WorkflowMethod
                fun price(sku: String): Long
            }

            class PricingWorkflowImpl : PricingWorkflow {
                private val pricing = Workflow.newActivityStub(
                    PricingActivities::class.java,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
                )

                override fun price(sku: String): Long = pricing.quote(sku)
            }
        """

        const val CONFIGURATION = """
            pricing:
              url: http://pricing.example.com
        """
    }
}
