package io.github.iw.durableatlas.analyser

/**
 * Activities written to try how what a request sends, what the code reads of its answer, its
 * parameters, the arguments of a client's call, and the models of both are read.
 *
 * [FILES] are the sources of a service, module `:app`, with [RESOURCES] its configuration. Jackson
 * itself is on the classpath of every model. A model made from the build has the jars of
 * kotlinx.serialization too, and, in a module `:client` of its own, the client of the repository's
 * own and as much of the Kotlin module of Jackson as the sources use ([LIBRARY]); without them the
 * sources read as a degraded model has them, where none of these resolves.
 */
internal object PayloadSources {
    val FILES: Map<String, String> = linkedMapOf(
        "payloads/Payloads.kt" to """
            package payloads

            import com.example.functions.ServiceClient
            import com.fasterxml.jackson.core.type.TypeReference
            import com.fasterxml.jackson.databind.ObjectMapper
            import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
            import com.fasterxml.jackson.module.kotlin.readValue
            import io.temporal.activity.ActivityInterface
            import io.temporal.activity.ActivityOptions
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.io.InputStream
            import java.net.URI
            import java.net.http.HttpClient
            import java.net.http.HttpRequest
            import java.net.http.HttpRequest.BodyPublishers
            import java.net.http.HttpResponse
            import java.net.http.HttpResponse.BodyHandlers
            import java.nio.file.Path
            import java.time.Duration
            import kotlinx.serialization.decodeFromString
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.json.Json
            import org.eclipse.microprofile.config.inject.ConfigProperty

            @WorkflowInterface
            interface Paying {
                @WorkflowMethod
                fun pay(id: String)
            }

            class PayingImpl : Paying {
                private val payloads = Workflow.newActivityStub(
                    Payloads::class.java,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build(),
                )

                override fun pay(id: String) {
                    payloads.jackson(id)
                    payloads.serial(id)
                }
            }

            @ActivityInterface
            interface Payloads {
                fun jackson(id: String): String
                fun bytes(id: String)
                fun serial(id: String): Booking
                fun text(id: String)
                fun empty(id: String): String
                fun helper(id: String): String
                fun stream(id: String, page: Int): Page<Line>
                fun lost(id: String)
                fun file(id: String)
                fun events(): List<Event>
                fun scans(): Scan
                fun tree(): Node
                fun pair(): Pair<String, Int>
                fun deep(id: String): Receipt
                fun client(id: String): Booking
                fun expected(id: String): Int
            }

            class PayloadsImpl(
                @ConfigProperty(name = "billing.url") private val billing: String,
                private val functions: ServiceClient,
            ) : Payloads {
                private val http = HttpClient.newHttpClient()
                private val mapper = ObjectMapper()

                override fun jackson(id: String): String {
                    val body = mapper.writeValueAsString(Charge(id, Money(1), Method.CARD))
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/charges")).POST(BodyPublishers.ofString(body)).build()
                    val answer = http.send(request, BodyHandlers.ofString()).body()
                    return mapper.readValue(answer, Receipt::class.java).chargeId
                }

                override fun bytes(id: String) {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/refunds/${'$'}id"))
                        .header("Content-Type", "application/json")
                        .method("PATCH", BodyPublishers.ofByteArray(mapper.writeValueAsBytes(listOf(Line(id, 1)))))
                        .build()
                    val text = http.send(request, BodyHandlers.ofString()).body()
                    val lines = mapper.readValue(text, object : TypeReference<List<Line>>() {})
                    check(lines.isNotEmpty())
                }

                override fun serial(id: String): Booking {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/bookings"))
                        .header("Content-Type", "application/json")
                        .PUT(BodyPublishers.ofString(Json.encodeToString(Booking(id))))
                        .build()
                    return Json.decodeFromString<Booking>(http.send(request, BodyHandlers.ofString()).body())
                }

                override fun text(id: String) {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/notes"))
                        .header("Content-Type", "text/plain")
                        .POST(BodyPublishers.ofString("paid ${'$'}id"))
                        .build()
                    http.send(request, BodyHandlers.discarding())
                }

                override fun empty(id: String): String {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/health")).GET().build()
                    return http.send(request, BodyHandlers.ofString()).body()
                }

                override fun helper(id: String): String {
                    val answer = send("POST", "/charges", mapper.writeValueAsString(Charge(id, Money(2), Method.BANK_TRANSFER)))
                    return mapper.readValue(answer, Receipt::class.java).chargeId
                }

                /** Sends any request and gives back the answer's body, for the caller to read. */
                private fun send(method: String, path: String, body: String): String {
                    val publisher = if (body.isEmpty()) BodyPublishers.noBody() else BodyPublishers.ofString(body)
                    val request = HttpRequest.newBuilder(URI.create(billing + path)).method(method, publisher).build()
                    return http.send(request, BodyHandlers.ofString()).body()
                }

                override fun stream(id: String, page: Int): Page<Line> {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/lines/${'$'}id?page=${'$'}page")).build()
                    val response: HttpResponse<InputStream> = http.send(request, BodyHandlers.ofInputStream())
                    return response.body().use { mapper.readValue(it, object : TypeReference<Page<Line>>() {}) }
                }

                override fun lost(id: String) {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/lost")).build()
                    remember(http.send(request, BodyHandlers.ofString()))
                }

                private fun remember(response: Any) {
                    println(response.hashCode())
                }

                override fun file(id: String) {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/files")).POST(BodyPublishers.ofFile(Path.of(id))).build()
                    http.send(request, BodyHandlers.discarding())
                }

                override fun events(): List<Event> {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/events")).build()
                    return jacksonObjectMapper().readValue<List<Event>>(http.send(request, BodyHandlers.ofString()).body())
                }

                override fun scans(): Scan {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/scans")).build()
                    return Json.decodeFromString(Scan.serializer(), http.send(request, BodyHandlers.ofString()).body())
                }

                override fun tree(): Node {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/tree")).build()
                    return mapper.readValue(http.send(request, BodyHandlers.ofString()).body(), Node::class.java)
                }

                override fun pair(): Pair<String, Int> {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/pair")).build()
                    return mapper.readValue(http.send(request, BodyHandlers.ofString()).body(), object : TypeReference<Pair<String, Int>>() {})
                }

                override fun deep(id: String): Receipt {
                    val request = HttpRequest.newBuilder(URI.create("${'$'}billing/deep/${'$'}id")).build()
                    return parse(http.send(request, BodyHandlers.ofString()).body())
                }

                private fun parse(text: String): Receipt = parseAgain(text)

                private fun parseAgain(text: String): Receipt = parseLast(text)

                private fun parseLast(text: String): Receipt = mapper.readValue(text, Receipt::class.java)

                override fun client(id: String): Booking = functions<Booking>(functionName = BOOK, payload = Charge(id, Money(3), Method.CARD))

                override fun expected(id: String): Int {
                    val count: Int = functions(functionName = "count-fn", payload = id)
                    return count
                }

                companion object {
                    const val BOOK = "book-fn"
                }
            }
        """,
        "payloads/Models.kt" to """
            package payloads

            import com.fasterxml.jackson.annotation.JsonIgnore
            import com.fasterxml.jackson.annotation.JsonProperty
            import com.fasterxml.jackson.annotation.JsonSubTypes
            import com.fasterxml.jackson.annotation.JsonTypeInfo
            import com.fasterxml.jackson.databind.PropertyNamingStrategies
            import com.fasterxml.jackson.databind.annotation.JsonNaming
            import java.time.Instant
            import java.util.UUID
            import org.example.rates.Rate

            data class Money(val cents: Long, val currency: String = "EUR", val rate: Rate? = null)

            enum class Method { CARD, @JsonProperty("bank-transfer") BANK_TRANSFER }

            data class Charge(
                val orderId: String,
                @get:JsonProperty("total") val amount: Money,
                val method: Method,
                val key: UUID? = null,
                @JsonIgnore val trace: String = "",
                @Transient val local: Int = 0,
            ) {
                var note: String? = null
                val computed: Boolean get() = note != null
            }

            @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy::class)
            data class Receipt(val chargeId: String, val takenAt: Instant, val lines: List<Line>, val totals: Map<String, Money>)

            data class Line(val sku: String, val quantity: Int)

            data class Page<T>(val items: List<T>, val next: String?)

            @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
            @JsonSubTypes(
                JsonSubTypes.Type(value = Event.Placed::class, name = "placed"),
                JsonSubTypes.Type(value = Event.Dropped::class, name = "dropped"),
            )
            sealed class Event {
                data class Placed(val at: Instant) : Event()

                data class Dropped(val reason: String?) : Event()
            }

            data class Node(val name: String, val parent: Node?, val children: List<Node>)
        """,
        "payloads/Serial.kt" to """
            package payloads

            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.Transient

            @Serializable
            data class Booking(@SerialName("booking_id") val id: String, val level: Level = Level.STANDARD, @Transient val draft: Boolean = false)

            @Serializable
            enum class Level { @SerialName("standard") STANDARD, @SerialName("express") EXPRESS }

            @Serializable
            sealed class Scan {
                @Serializable
                @SerialName("seen")
                data class Seen(val at: String) : Scan()

                @Serializable
                @SerialName("gone")
                data class Gone(val at: String) : Scan()
            }
        """,
    )

    val RESOURCES: Map<String, String> = linkedMapOf(
        "application.yaml" to """
            billing:
              url: http://billing.example.com
            "%prod":
              billing:
                url: https://billing.prod.example.com
        """,
    )

    /** The functions of the Kotlin module of Jackson that the sources use. */
    val LIBRARY: Map<String, String> = linkedMapOf(
        "com/fasterxml/jackson/module/kotlin/Extensions.kt" to """
            package com.fasterxml.jackson.module.kotlin

            import com.fasterxml.jackson.core.type.TypeReference
            import com.fasterxml.jackson.databind.ObjectMapper

            fun jacksonObjectMapper(): ObjectMapper = ObjectMapper()

            inline fun <reified T> ObjectMapper.readValue(content: String): T = readValue(content, object : TypeReference<T>() {})
        """,
    )
}
