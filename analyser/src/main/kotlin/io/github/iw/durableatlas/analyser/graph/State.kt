package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.discovery.TemporalTypes
import java.util.TreeSet

/**
 * What of the state of a workflow an expression stands for: the workflow object itself, or one of
 * its properties. Whatever is reached through a property counts as that property, so that
 * `items.add(x)`, `state.paid = true` and a function of `state` that assigns `paid` are all
 * writes of the one property an await on `items` or `state` reads.
 */
internal sealed interface StateRef {
    /** What a property read or written on this stands for. */
    fun member(name: String): Property

    /** The object the entry point runs on. */
    data object Workflow : StateRef {
        override fun member(name: String): Property = Property(name)
    }

    /** A property of the workflow object, by its name, or an object reached through it. */
    data class Property(val name: String) : StateRef {
        override fun member(name: String): Property = this
    }
}

/** The properties of the workflow that the code of an entry point, or a part of it, reads and writes. */
internal class StateUse {
    val reads = TreeSet<String>()
    val writes = TreeSet<String>()

    /** Those of [writes] that hold a workflow queue the code puts something into. */
    val fills = TreeSet<String>()

    /** Those of [writes] that hold a workflow queue the code takes something out of, which makes room in it. */
    val drains = TreeSet<String>()

    operator fun plusAssign(other: StateUse) {
        reads += other.reads
        writes += other.writes
        fills += other.fills
        drains += other.drains
    }

    /**
     * The properties among [waitedOn] that this code changes in a way that can end a wait on
     * them. A wait for room in a queue is ended by taking something out, a wait for something
     * to take by anything but that; any other wait by any change.
     */
    fun wakes(waitedOn: Collection<String>, queue: QueueWait?): List<String> =
        waitedOn.filter { name ->
            when (queue) {
                QueueWait.ROOM -> name in drains
                QueueWait.ITEMS -> name in writes && (name !in drains || name in fills)
                null -> name in writes
            }
        }.sorted()
}

/** Tells the calls that change the object they are made on, and the properties that are no state. */
internal object StateRules {
    /**
     * Whether [call] changes the object it is called on: a function of a library whose name says
     * so, as the functions of mutable collections, builders and atomic values do. A function of
     * the sources is not asked: what it writes is seen when it is followed.
     */
    fun mutates(call: Call): Boolean {
        val callee = call.callee ?: return false
        return !callee.inSource && (callee.function.simpleName in MUTATORS || queueUse(call) != null)
    }

    /**
     * What [call] does to the workflow queue it is made on: it waits for, or looks for,
     * something to take out ([QueueWait.ITEMS]), or for room to put something in
     * ([QueueWait.ROOM]). `null` for any other call.
     */
    fun queueUse(call: Call): QueueWait? {
        val function = call.callee?.function ?: return null
        return when {
            function.owner !in TemporalApi.QUEUE_ENDS -> null
            function.simpleName in TemporalApi.QUEUE_TAKES || function.simpleName in TemporalApi.QUEUE_POLLS -> QueueWait.ITEMS
            function.simpleName in TemporalApi.QUEUE_PUTS || function.simpleName in TemporalApi.QUEUE_OFFERS -> QueueWait.ROOM
            else -> null
        }
    }

    /**
     * Whether a property of this type is part of the state a handler may change and an await may
     * wait for. Stubs and the other objects of the SDK are not: they are how the workflow acts.
     */
    fun isState(type: TypeRef?): Boolean {
        if (type == null) return true
        // A queue, a promise, a lock or a semaphore is what the workflow waits on and a handler feeds or frees.
        if (type.name in TemporalApi.WAITED_ON) return true
        if (type.name?.startsWith(SDK_PACKAGE) == true) return false
        return STUB_ANNOTATIONS.none { type.annotations.find(it) != null }
    }

    private const val SDK_PACKAGE = "io.temporal."

    private val STUB_ANNOTATIONS = listOf(
        TemporalTypes.ACTIVITY_INTERFACE,
        TemporalTypes.WORKFLOW_INTERFACE,
        TemporalApi.NEXUS_SERVICE,
    )

    private val MUTATORS = setOf(
        // Collections and maps.
        "add", "addAll", "addFirst", "addLast", "remove", "removeAll", "removeAt", "removeIf", "removeFirst",
        "removeLast", "removeFirstOrNull", "removeLastOrNull", "retainAll", "clear", "set", "put", "putAll",
        "putIfAbsent", "compute", "computeIfAbsent", "computeIfPresent", "merge", "replace", "replaceAll",
        "getOrPut", "push", "pop", "poll", "pollFirst", "pollLast", "offer", "offerFirst", "offerLast",
        "plusAssign", "minusAssign", "sort", "sortBy", "sortByDescending", "sortDescending", "sortWith",
        "shuffle", "reverse", "fill",
        // Workflow queues and the promises a workflow completes itself.
        "cancellablePut", "cancellableOffer", "complete", "completeExceptionally", "completeFrom",
        // Workflow locks and semaphores: letting go of one is what a wait for it waits for.
        "unlock", "release",
        // Builders of text.
        "append", "appendLine", "insert", "delete", "deleteCharAt", "setLength",
        // Atomic values.
        "incrementAndGet", "decrementAndGet", "getAndIncrement", "getAndDecrement", "addAndGet", "getAndAdd",
        "getAndSet", "compareAndSet", "updateAndGet", "getAndUpdate", "accumulateAndGet", "lazySet",
    )
}
