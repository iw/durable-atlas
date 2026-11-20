package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.requests.ClientSpec
import io.github.iw.durableatlas.ir.Detail
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.RequestKind
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The requests that activities make of other services, read from [RequestSources] two ways:
 * - `built`: with MicroProfile Config, SmallRye Config and the client on the classpath, as a
 *   model made from the build has them;
 * - `alone`: the Kotlin files of the service alone, as a degraded model has them, where neither
 *   the annotations nor the client resolve.
 *
 * A request is written as what it asks, its keys, its host, its response type, the class that
 * makes it where several implement its activity type, and where it is made.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestsExtractionTest {
    private lateinit var directory: Path
    private lateinit var built: AnalysisResult
    private lateinit var alone: AnalysisResult
    private val aloneLog = ArrayList<String>()

    @BeforeAll
    fun analyse(@TempDir directory: Path) {
        this.directory = directory
        built = analyse("built", libraries = true, CLIENTS)
        alone = analyse("alone", libraries = false, CLIENTS, aloneLog)
    }

    private fun sources(name: String, libraries: Boolean): SyntheticSources {
        val sources = SyntheticSources(directory.resolve(name))
        if (libraries) {
            sources.module(":client")
            sources.module(":app", ":client")
            RequestSources.CLIENT.forEach { (path, text) -> sources.file(":client", path, text) }
            RequestSources.LIBRARY.forEach { (path, text) -> sources.javaFile(":app", path, text) }
        }
        RequestSources.FILES.forEach { (file, text) -> sources.file(":app", "requests/$file.kt", text) }
        RequestSources.RESOURCES.forEach { (file, text) -> sources.resource(":app", file, text) }
        return sources
    }

    private fun analyse(
        name: String,
        libraries: Boolean,
        clients: List<String>,
        log: MutableList<String> = ArrayList(),
        configure: (SyntheticSources) -> Unit = {},
        request: (AnalysisRequest) -> AnalysisRequest = { it },
    ): AnalysisResult {
        val sources = sources(name, libraries).also(configure)
        val model = sources.model().directory
        val analysis = AnalysisRequest(model, directory.resolve("$name-out"), clients = clients.map(ClientSpec::parse))
        return Analyser(log::add).analyse(request(analysis))
    }

    /** The requests of each activity type of [interfaceName], by activity type, as the inventory has them. */
    private fun AnalysisResult.requests(interfaceName: String): Map<String, List<String>> =
        inventory.activityInterfaces.single { it.interfaceName == "requests.$interfaceName" }.activities
            .associate { activity -> activity.name to activity.requests.map(::describe) }

    private fun describe(request: Request): String {
        val what = when (request.kind) {
            RequestKind.HTTP -> "${request.method ?: "HTTP"} ${request.target}"
            RequestKind.CLIENT -> "${request.client}.${request.call} ${request.operation}"
        }
        val keys = if (request.keys.isEmpty()) "" else " keys ${request.keys.joinToString(",")}"
        val host = request.host?.let { " host $it" }.orEmpty()
        val response = request.response?.let { " -> $it" }.orEmpty()
        val className = request.className?.let { " in ${it.substringAfterLast('.')}" }.orEmpty()
        return "$what$keys$host$response$className @${request.loc.file.substringAfterLast('/')}:${request.loc.line}"
    }

    // ---- java.net.http

    @Test
    fun `a request built with HttpRequest and sent is read in each shape, alike in both models`() {
        val expected = mapOf(
            "Inline" to listOf("GET https://api.example.com/orders -> String @HttpCalls.kt:66"),
            // Through a helper that takes the method and the path.
            "Helper" to listOf("POST https://api.example.com/orders/{id} -> String @HttpCalls.kt:80"),
            "TwoHelpers" to listOf("DELETE https://api.example.com/stock/{id} -> String @HttpCalls.kt:80"),
            "Async" to listOf("POST https://api.example.com/events -> String @HttpCalls.kt:84"),
            // A body that is discarded is no response.
            "Patch" to listOf("PATCH https://api.example.com/orders/{id} @HttpCalls.kt:92"),
            "Template" to listOf("GET https://api.example.com/orders/{order.id}/lines -> String @HttpCalls.kt:96"),
            "Plus" to listOf("PUT https://api.example.com/parcels/{id} -> String @HttpCalls.kt:101"),
            "Resolved" to listOf(
                "DELETE https://api.example.com/v2/refunds/{id} -> String @HttpCalls.kt:106",
                "GET https://api.example.com/health -> String @HttpCalls.kt:107",
            ),
            "Formatted" to listOf(
                "GET https://api.example.com/invoices/{id} -> String @HttpCalls.kt:112",
                "GET https://api.example.com/credits/{id.length} -> String @HttpCalls.kt:113",
            ),
            "Constructed" to listOf("GET https://api.example.com/labels/{id} -> String @HttpCalls.kt:117"),
            "Head" to listOf("HEAD https://api.example.com @HttpCalls.kt:121"),
            // Sent, but made where the code that is read does not show: known by what is sent.
            "Elsewhere" to listOf("HTTP {request} -> String @HttpCalls.kt:125"),
            // Made and never sent: no request.
            "Unsent" to emptyList(),
            // Made in a loop, and on two ways: one request each.
            "Looped" to listOf("POST https://api.example.com/batch -> String @HttpCalls.kt:80"),
            "TwoWays" to listOf(
                "GET https://api.example.com/twice -> String @HttpCalls.kt:80",
                "GET https://api.example.com/once/{id} -> String @HttpCalls.kt:80",
            ),
            // A method that cannot be told is shown as written.
            "ComputedMethod" to listOf("{verb} https://api.example.com/any -> String @HttpCalls.kt:80"),
            "Deep" to listOf("GET https://api.example.com/deep/{id} -> String @HttpCalls.kt:80"),
        )
        assertEquals(expected, built.requests("HttpCalls"))
        assertEquals(expected, alone.requests("HttpCalls"))

        // A builder kept in a variable may be told more in statements of their own, which are not read: no method is taken
        // for granted, and a request without the URI the chain gives is known by what is sent.
        val held = mapOf(
            "Held" to listOf("HTTP {builder.build()} -> String @HttpCalls.kt:170"),
            "HeldMethod" to listOf("PUT https://api.example.com/kept/{id} -> String @HttpCalls.kt:176"),
        )
        assertEquals(held, built.requests("HeldCalls"))
        assertEquals(held, alone.requests("HeldCalls"))
    }

    @Test
    fun `calls into functions of the sources are followed as deep as the workflows are read`() {
        val shallow = analyse("shallow", libraries = false, emptyList()) { it.copy(maxDepth = 2) }.requests("HttpCalls")
        // The request lies three calls deep: below the limit it is not seen.
        assertEquals(emptyList(), shallow.getValue("Deep"))
        assertEquals(listOf("POST https://api.example.com/orders/{id} -> String @HttpCalls.kt:80"), shallow.getValue("Helper"))
        val deep = analyse("deep", libraries = false, emptyList()) { it.copy(maxDepth = 3) }.requests("HttpCalls")
        assertEquals(listOf("GET https://api.example.com/deep/{id} -> String @HttpCalls.kt:80"), deep.getValue("Deep"))
    }

    // ---- clients

    private val clientRequests = mapOf(
        "Named" to listOf("ServiceClient.invoke named-fn -> List<Item> @ClientCalls.kt:77"),
        "Positional" to listOf("ServiceClient.invoke positional-fn -> Item @ClientCalls.kt:79"),
        "OwnConstant" to listOf("ServiceClient.invoke own-fn @ClientCalls.kt:82"),
        "OtherConstant" to listOf("ServiceClient.invoke catalogue-fn @ClientCalls.kt:86"),
        "InObject" to listOf("ServiceClient.invoke object-fn @ClientCalls.kt:90"),
        "TopLevel" to listOf("ServiceClient.invoke top-level-fn @ClientCalls.kt:94"),
        "ThroughProperty" to listOf("ServiceClient.invoke own-fn @ClientCalls.kt:98"),
        "ThroughHelper" to listOf("ServiceClient.invoke helped-fn -> Item @ClientCalls.kt:103"),
        "Templated" to listOf("ServiceClient.invoke {region}-own-fn @ClientCalls.kt:106"),
        "Operator" to listOf("ServiceClient.invoke own-fn -> Item @ClientCalls.kt:109"),
        "Expected" to listOf("ServiceClient.invoke helped-fn -> Item @ClientCalls.kt:112"),
        "RequestObject" to listOf("ServiceClient.call own-fn -> Item @ClientCalls.kt:117"),
        "Builder" to listOf("ServiceClient.call catalogue-fn -> Item @ClientCalls.kt:121"),
        "FunctionValue" to emptyList(),
        "Configured" to listOf("ServiceClient.invoke {functions.lookup} keys functions.lookup @ClientCalls.kt:130"),
        "Discarded" to listOf("ServiceClient.invoke discarded-fn -> Item @ClientCalls.kt:134"),
    )

    @Test
    fun `a call of a named client is a request, its operation found in each form`() {
        assertEquals(clientRequests, built.requests("ClientCalls"))
    }

    @Test
    fun `where the client does not resolve, its calls are known by the type written at the receiver's declaration`() {
        // A positional argument cannot be bound to a parameter of a function that does not
        // resolve: the operation is not told, and nothing is guessed.
        val unbound = mapOf(
            "Positional" to listOf("ServiceClient.invoke null -> Item @ClientCalls.kt:79"),
            "Discarded" to listOf("ServiceClient.invoke null -> Item @ClientCalls.kt:134"),
        )
        assertEquals(clientRequests + unbound, alone.requests("ClientCalls"))
        // By position, it is.
        val byPosition = analyse("position", libraries = false, listOf("ServiceClient.invoke:1")).requests("ClientCalls")
        assertEquals(clientRequests.getValue("Positional"), byPosition.getValue("Positional"))
        assertEquals(clientRequests.getValue("Discarded"), byPosition.getValue("Discarded"))
    }

    @Test
    fun `a client named without a type is any receiver's, though no function value's, and another type matches nothing`() {
        for ((name, libraries) in listOf("untyped-built" to true, "untyped-alone" to false)) {
            val untyped = analyse(name, libraries, listOf("invoke:functionName", "call:functionName")).requests("ClientCalls")
            val reference = if (libraries) built else alone
            assertEquals(reference.requests("ClientCalls"), untyped, name)
            assertEquals(emptyList(), untyped.getValue("FunctionValue"))
        }
        val other = analyse("other", libraries = true, listOf("OtherClient.invoke:functionName")).requests("ClientCalls")
        assertTrue(other.values.all { it.isEmpty() }, other.toString())
        val none = analyse("none", libraries = true, emptyList())
        assertTrue(none.requests("ClientCalls").values.all { it.isEmpty() })
        // The HTTP requests are read all the same.
        assertEquals(built.requests("HttpCalls"), none.requests("HttpCalls"))
    }

    // ---- configuration

    private val configRequests = mapOf(
        "Field" to listOf(
            // An expression is kept as written.
            "GET {stock.url}/items/{id} keys stock.url host \${STOCK_URL:http://stock.example.com} -> String @ConfigCalls.kt:83",
        ),
        // Given in the profile `prod` only.
        "AnnotatedField" to listOf("GET {audit.url}/entries keys audit.url host https://audit.example.com -> String @ConfigCalls.kt:83"),
        // Both files give the key: the YAML value; it has a value for `prod` as well.
        "ConstructorParameter" to listOf("GET {orders.url}/orders/{id} keys orders.url host http://orders.example.com -> String @ConfigCalls.kt:83"),
        "Mapping" to listOf(
            "GET {services.inventory-url}/reserve keys services.inventory-url host http://inventory.example.com -> String @ConfigCalls.kt:83",
        ),
        // A Kotlin property is read through its getter.
        "MappingProperty" to listOf(
            "GET {services.get-billing-url}/bills keys services.get-billing-url host http://billing.example.com -> String @ConfigCalls.kt:83",
        ),
        "Group" to listOf(
            "GET {services.shipping.base}{services.shipping.version}/parcels keys services.shipping.base,services.shipping.version " +
                "host http://shipping.example.com -> String @ConfigCalls.kt:83",
        ),
        // From the properties file.
        "Provided" to listOf("GET {provided.url} keys provided.url host http://provided.example.com -> String @ConfigCalls.kt:83"),
        "Optional" to listOf("GET {optional.url}/ping keys optional.url host http://optional.example.com -> String @ConfigCalls.kt:83"),
        // A key that no file gives: no host.
        "Missing" to listOf("GET {nowhere.url} keys nowhere.url -> String @ConfigCalls.kt:83"),
        "Defaulted" to listOf("GET {absent.url} keys absent.url host http://fallback.example.com -> String @ConfigCalls.kt:83"),
        // A nested profile section.
        "Profiled" to listOf("GET {only.prod.url} keys only.prod.url host https://prod.example.com -> String @ConfigCalls.kt:83"),
    )

    @Test
    fun `a value from configuration is known by its key in each form, resolved or not, with the host the files give`() {
        assertEquals(configRequests, built.requests("ConfigCalls"))
        assertEquals(configRequests, alone.requests("ConfigCalls"))
        // Without the library, its annotations do not resolve, and are read as they are written: nothing to warn of.
        assertTrue(aloneLog.none { "did not resolve" in it }, aloneLog.joinToString("\n"))
    }

    @Test
    fun `a model made before the resource files were exported keeps the keys, gives no host and says so once`() {
        val log = ArrayList<String>()
        val old = analyse("old", libraries = false, CLIENTS, log, configure = { it.listResources = false })
        // A default that the code gives is a host all the same.
        val withoutHosts = configRequests.mapValues { (_, requests) -> requests.map { it.replace(Regex(" host \\S+"), "") } } + mapOf(
            "Defaulted" to configRequests.getValue("Defaulted"),
            "Optional" to listOf("GET {optional.url}/ping keys optional.url host http://none.example.com -> String @ConfigCalls.kt:83"),
        )
        assertEquals(withoutHosts, old.requests("ConfigCalls"))
        assertEquals(1, log.count { "--force makes it again" in it }, log.joinToString("\n"))
        assertEquals(0, aloneLog.count { "--force makes it again" in it }, aloneLog.joinToString("\n"))
    }

    // ---- activity types and documents

    @Test
    fun `each class that implements an activity type is read, and a type implemented nowhere in the sources has no requests`() {
        val expected = listOf(
            "POST https://mail.example.com/send -> String in MailNotices @Requested.kt:54",
            "ServiceClient.invoke text-send in TextNotices @Requested.kt:60",
        )
        assertEquals(mapOf("Announce" to expected), built.requests("Notices"))
        assertEquals(mapOf("Announce" to expected), alone.requests("Notices"))
        assertEquals(mapOf("Call" to emptyList()), built.requests("Remote"))
    }

    @Test
    fun `the nodes that run an activity type carry its requests, as the inventory lists them, and the IR is the golden one`() {
        for (result in listOf(built, alone)) {
            val document = result.workflows.single().document
            val byType = result.inventory.activityInterfaces.flatMap { it.activities }.associate { it.name to it.requests }
            val activities = document.nodes.filter { it.kind == NodeKind.ACTIVITY }
            assertEquals(listOf("Announce", "Call", "Helper", "Named"), activities.mapNotNull { it.name }.sorted())
            for (node in activities) assertEquals(byType.getValue(node.name!!), node.requests, node.name)
            // No other node has any.
            assertTrue(document.nodes.filter { it.kind != NodeKind.ACTIVITY }.all { it.requests.isEmpty() })
        }
        assertEquals(IrJson.encode(built.workflows.single().document), IrJson.encode(alone.workflows.single().document))
        GoldenFiles.assertMatches("ir/requests/Requested.ir.json", IrJson.encode(alone.workflows.single().document))
    }

    @Test
    fun `the detail of a document has no bearing on its requests`() {
        val overview = analyse("overview", libraries = false, CLIENTS) { it.copy(detail = Detail.OVERVIEW) }
        val full = alone.workflows.single().document.nodes.map { it.requests }
        assertEquals(full, overview.workflows.single().document.nodes.map { it.requests })
    }

    private companion object {
        val CLIENTS = listOf("ServiceClient.invoke:functionName", "ServiceClient.call:functionName")
    }
}
