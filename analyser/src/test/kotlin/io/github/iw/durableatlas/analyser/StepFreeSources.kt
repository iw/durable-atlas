package io.github.iw.durableatlas.analyser

/**
 * Workflows that call ordinary code of the sources around their steps: a wrapper around an
 * object mapper, a factory of events, chains of calls, and helpers of their own. Most of that
 * code holds no step of a workflow: it branches, loops and throws. Each workflow is in a file
 * of its own and as small as its case allows; the tests name nodes by their lines in these
 * files, counted from the `package` line.
 */
internal object StepFreeSources {
    private const val HEADER = """package plain

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
"""

    /** The workflow types of these sources, in the order of their names. */
    val WORKFLOWS = listOf("Caught", "Circling", "Deep", "Escaping", "Forked", "Handing", "Keeping", "Looped", "Made", "Published", "Unseen")

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Jobs" to """
@ActivityInterface
interface Jobs {
    fun one(input: String): String
    fun two(input: String): String
}

val options: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
""",
        "Mapper" to """
class MappingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A wrapper around an object mapper: no step of a workflow anywhere in it. */
class Mapper(private val mapper: ObjectMapper = ObjectMapper()) {
    fun write(value: Any?): String {
        if (value == null) return "null"
        return guarded("write") { mapper.writeValueAsString(value) }
    }

    fun <T> read(json: String, type: Class<T>): T = guarded("read") { mapper.readValue(json, type) }

    /** What `read` throws is caught here: nothing comes out. */
    fun <T> readOrNull(json: String, type: Class<T>): T? =
        try {
            read(json, type)
        } catch (e: MappingException) {
            null
        }

    /** Hands each element to what it is given. */
    fun <T, R> each(values: List<T>, transform: (T) -> R): List<R> {
        val out = ArrayList<R>()
        for (value in values) {
            out += guarded("map") { transform(value) }
        }
        return out
    }

    private inline fun <T> guarded(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: JsonProcessingException) {
            throw MappingException("cannot ${'$'}what", e)
        }
}
""",
        "Events" to """
sealed interface Event {
    val id: String
}

data class Placed(override val id: String, val total: Long) : Event
data class Paid(override val id: String) : Event
data class Shipped(override val id: String) : Event

class UnknownEvent(type: String) : IllegalArgumentException("unknown event ${'$'}type")

/** Builds one kind of event; the sources hold a class for some of the kinds. */
interface Builder<T : Event> {
    fun build(id: String, fields: Map<String, String>): T
}

class PlacedBuilder : Builder<Placed> {
    override fun build(id: String, fields: Map<String, String>): Placed {
        val total = fields["total"]?.toLongOrNull() ?: throw IllegalStateException("no total")
        return Placed(id, total)
    }
}

class PaidBuilder : Builder<Paid> {
    override fun build(id: String, fields: Map<String, String>): Paid = Paid(id)
}

/** No class of these sources implements this one. */
interface Enricher {
    fun enrich(event: Event): Event
}

/** A factory of events: no step of a workflow anywhere in it. */
class Events(private val mapper: Mapper = Mapper(), private val enricher: Enricher? = null) {
    private val builders: Map<String, Builder<out Event>> = mapOf("placed" to PlacedBuilder(), "paid" to PaidBuilder())

    fun create(type: String, id: String, fields: Map<String, String>): Event =
        when (type) {
            "placed" -> placed(id, fields)
            "paid" -> Paid(id)
            "shipped" -> Shipped(id)
            else -> throw UnknownEvent(type)
        }

    /** The same through the interface: which class builds is only known when it runs. */
    fun build(type: String, id: String, fields: Map<String, String>): Event {
        val builder = builders[type] ?: throw UnknownEvent(type)
        return builder.build(id, fields)
    }

    fun toJson(event: Event): String =
        when (event) {
            is Placed -> mapper.write(mapOf("id" to event.id, "total" to event.total))
            is Paid -> mapper.write(event.id)
            is Shipped -> mapper.write(event)
        }

    fun enriched(event: Event): Event = enricher?.enrich(event) ?: event

    fun describe(event: Event): String = if (event is Placed) "placed ${'$'}{event.total}" else event.id

    private fun placed(id: String, fields: Map<String, String>): Placed {
        val total = fields["total"]?.toLongOrNull() ?: throw IllegalStateException("no total")
        if (total < 0) throw IllegalArgumentException("a negative total")
        return Placed(id, total)
    }
}
""",
        "Chains" to """
/** Four deep, and the last one throws. */
fun checked1(value: String): String = checked2(value.trim())
fun checked2(value: String): String = checked3(value)
fun checked3(value: String): String = checked4(value)
fun checked4(value: String): String {
    if (value.isEmpty()) throw IllegalArgumentException("nothing to check")
    return value
}

/** Four deep, and nothing at the end. */
fun plain1(value: String): String = plain2(value.trim())
fun plain2(value: String): String = plain3(value)
fun plain3(value: String): String = plain4(value)
fun plain4(value: String): String = value.lowercase()

/** Four deep, and the last one runs an activity. */
fun stepped1(jobs: Jobs, value: String): String = stepped2(jobs, value.trim())
fun stepped2(jobs: Jobs, value: String): String = stepped3(jobs, value)
fun stepped3(jobs: Jobs, value: String): String = stepped4(jobs, value)
fun stepped4(jobs: Jobs, value: String): String = jobs.two(value)

/** Calls itself and may throw; no step. */
fun depthOf(value: String, seen: Int = 0): Int {
    if (seen > 100) throw IllegalStateException("too deep")
    return if (value.isEmpty()) seen else depthOf(value.drop(1), seen + 1)
}

/** Calls itself around an activity. */
fun drain(jobs: Jobs, value: String): String {
    if (value.isEmpty()) return value
    return drain(jobs, jobs.one(value).drop(1))
}

/** Never returns. */
fun fail(message: String): Nothing = throw IllegalStateException(message)
""",
        "Published" to """
@WorkflowInterface
interface Published {
    @WorkflowMethod
    fun run(type: String, id: String): String
}

class PublishedImpl : Published {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val events = Events()
    private val mapper = Mapper()

    override fun run(type: String, id: String): String {
        val event = events.create(type, id, emptyMap())
        val stored = jobs.one(events.toJson(event))
        if (events.describe(event).isEmpty()) {
            jobs.two(stored)
        }
        val echoed = mapper.readOrNull(stored, String::class.java) ?: "none"
        return jobs.two(echoed)
    }
}
""",
        "Caught" to """
@WorkflowInterface
interface Caught {
    @WorkflowMethod
    fun run(type: String): String
}

class CaughtImpl : Caught {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val events = Events()
    private val mapper = Mapper()

    override fun run(type: String): String {
        val event = try {
            events.create(type, "1", emptyMap())
        } catch (e: UnknownEvent) {
            jobs.two("unknown")
            return "skipped"
        }
        return try {
            jobs.one(mapper.write(event))
        } catch (e: MappingException) {
            jobs.two("unmapped")
        }
    }
}
""",
        "Escaping" to """
@WorkflowInterface
interface Escaping {
    @WorkflowMethod
    fun run(input: String): String
}

class EscapingImpl : Escaping {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val table = HashMap<String, String>()
    private val limit: Int
        get() = if (table.isEmpty()) throw IllegalStateException("no limit") else table.size

    override fun run(input: String): String {
        validate(input)
        val first = jobs.one(input)
        val found = table[first] ?: fail("not found")
        return jobs.two(found.take(limit))
    }

    private fun validate(input: String) {
        if (input.isBlank()) throw IllegalArgumentException("nothing given")
        if (input.length > 100) throw IllegalArgumentException("too long")
    }
}
""",
        "Deep" to """
@WorkflowInterface
interface Deep {
    @WorkflowMethod
    fun run(input: String): String
}

class DeepImpl : Deep {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String): String {
        val checked = checked1(input)
        val plain = plain1(checked)
        val first = jobs.one(plain)
        return stepped1(jobs, first)
    }
}
""",
        "Handing" to """
@WorkflowInterface
interface Handing {
    @WorkflowMethod
    fun run(input: List<String>): String
}

class HandingImpl : Handing {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val mapper = Mapper()

    override fun run(input: List<String>): String {
        val sent = mapper.each(input) { item -> jobs.one(item) }
        val upper = mapper.each(sent) { item -> item.uppercase() }
        return timed("last") { jobs.two(upper.joinToString()) }
    }

    private fun <T> timed(what: String, block: () -> T): T {
        if (what.isEmpty()) throw IllegalArgumentException("no name")
        val started = Workflow.currentTimeMillis()
        try {
            return block()
        } finally {
            Workflow.getLogger("timed").info("{} took {}", what, Workflow.currentTimeMillis() - started)
        }
    }
}
""",
        "Forked" to """
@WorkflowInterface
interface Forked {
    @WorkflowMethod
    fun run(input: String): String
}

class ForkedImpl : Forked {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String): String {
        val normal = normalise(input)
        val first = maybeOne(normal)
        return label(first)
    }

    private fun normalise(input: String): String = if (input.startsWith(" ")) input.trim() else input

    private fun maybeOne(input: String): String {
        if (input.length < 3) return input
        return jobs.one(input)
    }

    private fun label(value: String): String =
        when {
            value.isEmpty() -> "empty"
            value.length > 10 -> "long"
            else -> "short"
        }
}
""",
        "Circling" to """
@WorkflowInterface
interface Circling {
    @WorkflowMethod
    fun run(input: String): String
}

class CirclingImpl : Circling {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String): String {
        val depth = depthOf(input)
        return drain(jobs, input + depth)
    }
}
""",
        "Keeping" to """
@WorkflowInterface
interface Keeping {
    @WorkflowMethod
    fun run(input: String): String

    @SignalMethod
    fun approve(by: String)

    @QueryMethod
    fun status(): String
}

class KeepingImpl : Keeping {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private var approvedBy: String? = null
    private var stage = "new"

    override fun run(input: String): String {
        advance("waiting")
        Workflow.await { isApproved() }
        advance("approved")
        return jobs.one(input + approver())
    }

    override fun approve(by: String) {
        record(by)
    }

    override fun status(): String = describe()

    private fun advance(next: String) {
        if (next == stage) throw IllegalStateException("already there")
        stage = next
    }

    private fun isApproved(): Boolean = approvedBy != null

    private fun approver(): String = approvedBy ?: throw IllegalStateException("nobody approved")

    private fun record(by: String) {
        if (by.isBlank()) throw IllegalArgumentException("nobody")
        approvedBy = by
    }

    private fun describe(): String = if (approvedBy == null) stage else "${'$'}stage by ${'$'}approvedBy"
}
""",
        "Unseen" to """
@WorkflowInterface
interface Unseen {
    @WorkflowMethod
    fun run(input: String): String
}

class UnseenImpl(private val enricher: Enricher, private val builder: Builder<out Event>) : Unseen {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val events = Events()

    override fun run(input: String): String {
        val event = events.enriched(Paid(input))
        enricher.enrich(event)
        val built = events.build("paid", input, emptyMap())
        builder.build(input, emptyMap())
        tell(built)
        return jobs.one(announce(built))
    }

    /** Calls what cannot be followed, and may throw. */
    private fun announce(event: Event): String {
        if (event.id.isEmpty()) throw IllegalArgumentException("no id")
        return enricher.enrich(event).id
    }

    /** Calls what cannot be followed, and runs an activity. */
    private fun tell(event: Event): String {
        val enriched = enricher.enrich(event)
        return jobs.two(enriched.id)
    }
}
""",
        "Looped" to """
@WorkflowInterface
interface Looped {
    @WorkflowMethod
    fun run(input: List<String>): String
}

class LoopedImpl : Looped {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val events = Events()
    private val mapper = Mapper()

    override fun run(input: List<String>): String {
        for (item in input) {
            val event = events.create(item, "1", emptyMap())
            jobs.one(events.describe(event))
            mapper.write(event)
        }
        input.forEach { events.create(it, "2", emptyMap()) }
        return jobs.two("done")
    }
}
""",
        "Made" to """
@WorkflowInterface
interface Made {
    @WorkflowMethod
    fun run(input: String): String
}

fun limitOf(text: String): Int = text.toIntOrNull() ?: throw IllegalArgumentException("no number")

class MadeImpl : Made {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val limit = limitOf("seven")
    private val first = jobs.one("made")

    override fun run(input: String): String = jobs.two(first.take(limit))
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
