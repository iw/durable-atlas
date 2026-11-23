package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.analyser.requests.ClientSpec
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelKind
import io.github.iw.durableatlas.ir.ModelType
import io.github.iw.durableatlas.ir.Payload
import io.github.iw.durableatlas.ir.PayloadKind
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the requests of [PayloadSources] send, what their code reads of the answers, their
 * parameters, the arguments of a client's calls and the models of both, read two ways:
 * - `built`: with kotlinx.serialization, the Kotlin module of Jackson and the client on the
 *   classpath, as a model made from the build has them;
 * - `alone`: as a degraded model has the sources, where none of them resolves and annotations
 *   and calls are known by the names they are written with.
 *
 * Both give the same, which each test checks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PayloadsExtractionTest {
    private lateinit var directory: Path
    private lateinit var built: AnalysisResult
    private lateinit var alone: AnalysisResult

    @BeforeAll
    fun analyse(@TempDir directory: Path) {
        this.directory = directory
        built = analyse("built", libraries = true)
        alone = analyse("alone", libraries = false)
    }

    private fun analyse(name: String, libraries: Boolean, configure: (SyntheticSources) -> Unit = {}, maxDepth: Int? = null): AnalysisResult {
        val sources = SyntheticSources(directory.resolve(name))
        if (libraries) {
            sources.module(":client")
            sources.module(":app", ":client")
            RequestSources.CLIENT.forEach { (path, text) -> sources.file(":client", path, text) }
            PayloadSources.LIBRARY.forEach { (path, text) -> sources.file(":client", path, text) }
            RequestSources.LIBRARY.forEach { (path, text) -> sources.javaFile(":app", path, text) }
            sources.libraries = KOTLINX
        }
        PayloadSources.FILES.forEach { (file, text) -> sources.file(":app", file, text) }
        PayloadSources.RESOURCES.forEach { (file, text) -> sources.resource(":app", file, text) }
        configure(sources)
        val request = AnalysisRequest(sources.model().directory, directory.resolve("$name-out"), clients = listOf(ClientSpec.parse("ServiceClient.invoke:functionName")))
        return Analyser {}.analyse(if (maxDepth == null) request else request.copy(maxDepth = maxDepth))
    }

    private fun payload(payload: Payload?): String {
        payload ?: return "-"
        val facts = listOfNotNull(payload.format?.name?.lowercase(), payload.mediaType, payload.naming).joinToString(",").let { if (it.isEmpty()) "" else " [$it]" }
        return when (payload.kind) {
            PayloadKind.TYPE -> "${payload.type}$facts"
            else -> "${payload.kind.name.lowercase()}$facts"
        }
    }

    private fun type(type: ModelType): String =
        buildString {
            append(type.kind.name.lowercase())
            if (type.typeParameters.isNotEmpty()) append(type.typeParameters.joinToString(",", "<", ">"))
            type.jsonNaming?.let { append(" naming=").append(it) }
            type.jsonDiscriminator?.let { append(" json-disc=").append(it) }
            type.serialDiscriminator?.let { append(" serial-disc=").append(it) }
            type.jsonName?.let { append(" json=").append(it) }
            type.serialName?.let { append(" serial=").append(it) }
            if (type.loc == null && type.kind != ModelKind.NOT_READ) append(" (library)")
            for (property in type.properties) {
                append(" | ").append(property.name).append(':').append(property.type)
                if (property.optional) append(" opt")
                property.jsonName?.let { append(" json=").append(it) }
                property.serialName?.let { append(" serial=").append(it) }
            }
            for (value in type.values) append(" | ").append(value.name).append(value.jsonName?.let { " json=$it" }.orEmpty()).append(value.serialName?.let { " serial=$it" }.orEmpty())
            for (subtype in type.subtypes) append(" | ").append(subtype.type).append(subtype.jsonName?.let { " json=$it" }.orEmpty()).append(subtype.serialName?.let { " serial=$it" }.orEmpty())
        }

    private companion object {
        /** The jars of kotlinx.serialization, which the tool itself runs with. */
        val KOTLINX: List<Path> = listOf(kotlinx.serialization.json.Json::class.java, kotlinx.serialization.KSerializer::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()) }
            .distinct()
    }

    /** Each activity type and what [part] says of its one request, in both models. */
    private fun each(part: (Request) -> String): Map<String, String> {
        val read = listOf(built, alone).map { result ->
            result.inventory.activityInterfaces.flatMap { it.activities }.associate { activity -> activity.name to activity.requests.joinToString(" ;; ", transform = part) }
        }
        assertEquals(read[0], read[1], "built and alone differ")
        return read[0]
    }

    @Test
    fun `what a request sends is the type a library writes, text with its media type, nothing, or unknown`() {
        assertEquals(
            mapOf(
                "Bytes" to "kotlin.collections.List<payloads.Line> [jackson,application/json]",
                "Client" to "-",
                "Deep" to "none",
                "Empty" to "none",
                "Events" to "none",
                "Expected" to "-",
                // A body that comes from a file is not read.
                "File" to "unknown",
                // Written by Jackson in the activity and sent by a helper that decides on no body only for an empty text.
                "Helper" to "payloads.Charge [jackson]",
                "Jackson" to "payloads.Charge [jackson]",
                "Lost" to "none",
                "Pair" to "none",
                "Scans" to "none",
                "Serial" to "payloads.Booking [kotlinx,application/json]",
                "Stream" to "none",
                "Text" to "text [text/plain]",
                "Tree" to "none",
            ),
            each { payload(it.sends) },
        )
    }

    @Test
    fun `the answer is followed forward to the type it is read as, through helpers and back into their callers`() {
        assertEquals(
            mapOf(
                // TypeReference, also of a generic type and of a type from a jar.
                "Bytes" to "kotlin.collections.List<payloads.Line> [jackson]",
                // A client's answer is its type argument, or the type its value is expected to have.
                "Client" to "payloads.Booking",
                "Expected" to "kotlin.Int",
                // Handed down three functions to the one that reads it.
                "Deep" to "payloads.Receipt [jackson]",
                // Kept as text and handed back.
                "Empty" to "text",
                // The reified readValue of the Kotlin module.
                "Events" to "kotlin.collections.List<payloads.Event> [jackson]",
                // Discarded.
                "File" to "not_read",
                "Text" to "not_read",
                // Handed back by the helper that sends it, and read by its caller.
                "Helper" to "payloads.Receipt [jackson]",
                "Jackson" to "payloads.Receipt [jackson]",
                // Handed to a function that does not read it as anything.
                "Lost" to "unknown",
                "Pair" to "kotlin.Pair<kotlin.String, kotlin.Int> [jackson]",
                // kotlinx.serialization, with a serializer and with a type argument.
                "Scans" to "payloads.Scan [kotlinx]",
                "Serial" to "payloads.Booking [kotlinx]",
                // Read from an InputStream.
                "Stream" to "payloads.Page<payloads.Line> [jackson]",
                "Tree" to "payloads.Node [jackson]",
            ),
            each { payload(it.receives) },
        )
    }

    @Test
    fun `the parameters of a target and the arguments of a client have the types of what gives them`() {
        val parts = each { request ->
            (request.parameters.map { "${it.name}:${it.location.name.lowercase()}:${it.type}" } +
                request.arguments.map { "${it.name}@${it.position}:${it.type}" } +
                listOfNotNull(request.constant?.let { "constant $it" })).joinToString(" ")
        }.filterValues { it.isNotEmpty() }
        assertEquals(
            mapOf(
                "Bytes" to "id:path:kotlin.String",
                "Client" to "payload@2:payloads.Charge constant payloads.PayloadsImpl.BOOK",
                "Deep" to "id:path:kotlin.String",
                // The name is a literal: no constant gives it.
                "Expected" to "payload@2:kotlin.String",
                "Stream" to "id:path:kotlin.String page:query:kotlin.Int",
            ),
            parts,
        )
    }

    @Test
    fun `the hosts of a request are the values of its key in each profile, the one without a profile first`() {
        val hosts = each { request -> request.hosts.joinToString(",") { "${it.value}@${it.profile ?: "-"} ${it.file}" } }
        assertEquals(
            "http://billing.example.com@- app/src/main/resources/application.yaml,https://billing.prod.example.com@prod app/src/main/resources/application.yaml",
            hosts.getValue("Jackson"),
        )
        assertEquals("", hosts.getValue("Client"))
        // The host is the value without a profile, as before.
        assertEquals("http://billing.example.com", built.inventory.activityInterfaces.flatMap { it.activities }.single { it.name == "Jackson" }.requests.single().host)
    }

    @Test
    fun `every type a request sends or reads, and each type those reach, is in the table of the inventory`() {
        val tables = listOf(built, alone).map { result -> result.inventory.types.mapValues { (_, type) -> type(type) } }
        assertEquals(tables[0], tables[1])
        assertEquals(
            mapOf(
                // From a jar: its properties, in the order of its constructor, and its type parameters.
                "kotlin.Pair" to "object<A,B> (library) | first:A | second:B",
                // @SerialName on a property, a default value, and @Transient left out.
                "payloads.Booking" to "object | id:kotlin.String serial=booking_id | level:payloads.Level opt",
                // @JsonProperty on the getter, a nullable with a default, @JsonIgnore, kotlin.jvm.Transient and a getter without a field left out,
                // a public property with a field kept.
                "payloads.Charge" to "object | orderId:kotlin.String | amount:payloads.Money json=total | method:payloads.Method | key:java.util.UUID? opt | note:kotlin.String? opt",
                // A sealed class with @JsonTypeInfo and @JsonSubTypes.
                "payloads.Event" to "alternatives json-disc=kind | payloads.Event.Placed json=placed | payloads.Event.Dropped json=dropped",
                "payloads.Event.Dropped" to "object | reason:kotlin.String?",
                "payloads.Event.Placed" to "object | at:java.time.Instant",
                "payloads.Level" to "enum | STANDARD serial=standard | EXPRESS serial=express",
                "payloads.Line" to "object | sku:kotlin.String | quantity:kotlin.Int",
                "payloads.Method" to "enum | CARD | BANK_TRANSFER json=bank-transfer",
                "payloads.Money" to "object | cents:kotlin.Long | currency:kotlin.String opt | rate:org.example.rates.Rate? opt",
                // A type of a library the model does not have is named, and not read.
                "org.example.rates.Rate" to "not_read",
                // A type that holds itself is in the table once.
                "payloads.Node" to "object | name:kotlin.String | parent:payloads.Node? | children:kotlin.collections.List<payloads.Node>",
                "payloads.Page" to "object<T> | items:kotlin.collections.List<T> | next:kotlin.String?",
                // @JsonNaming, and the standard types and collections of its properties, which have no entry.
                "payloads.Receipt" to "object naming=KebabCaseStrategy | chargeId:kotlin.String | takenAt:java.time.Instant | " +
                    "lines:kotlin.collections.List<payloads.Line> | totals:kotlin.collections.Map<kotlin.String, payloads.Money>",
                "payloads.Scan" to "alternatives | payloads.Scan.Seen serial=seen | payloads.Scan.Gone serial=gone",
                "payloads.Scan.Gone" to "object serial=gone | at:kotlin.String",
                "payloads.Scan.Seen" to "object serial=seen | at:kotlin.String",
            ),
            tables[0],
        )
    }

    @Test
    fun `the naming strategy of Quarkus for Jackson in the requesting module is on what Jackson writes and reads, not on the rest`() {
        val result = analyse("quarkus", libraries = false, configure = { it.resource(":app", "application.properties", "quarkus.jackson.property-naming-strategy=SNAKE_CASE") })
        val requests = result.inventory.activityInterfaces.flatMap { it.activities }.associate { it.name to it.requests.single() }
        assertEquals("payloads.Charge [jackson,SNAKE_CASE]", payload(requests.getValue("Jackson").sends))
        assertEquals("payloads.Receipt [jackson,SNAKE_CASE]", payload(requests.getValue("Jackson").receives))
        assertEquals("payloads.Booking [kotlinx]", payload(requests.getValue("Serial").receives))
        assertEquals("payloads.Booking", payload(requests.getValue("Client").receives))
        assertEquals("text [text/plain]", payload(requests.getValue("Text").sends))
        assertEquals(alone.inventory.types, result.inventory.types)
    }

    @Test
    fun `an answer is followed down only as deep as the calls of a workflow are, and is unknown past that`() {
        val result = analyse("depth", libraries = false, maxDepth = 2)
        val deep = result.inventory.activityInterfaces.flatMap { it.activities }.single { it.name == "Deep" }.requests.single()
        assertEquals("unknown", payload(deep.receives))
        // What it sends is read where it is made, which is within the depth.
        assertEquals("none", payload(deep.sends))
    }

    @Test
    fun `the document of the workflow and the inventory are the golden ones, which the endpoints page is drawn from`() {
        assertEquals(IrJson.encode(built.workflows.single().document), IrJson.encode(alone.workflows.single().document))
        assertEquals(InventoryJson.encode(built.inventory), InventoryJson.encode(alone.inventory))
        GoldenFiles.assertMatches("ir/payloads/Paying.ir.json", IrJson.encode(alone.workflows.single().document))
        GoldenFiles.assertMatches("inventory/payloads/inventory.json", InventoryJson.encode(alone.inventory))
    }
}
