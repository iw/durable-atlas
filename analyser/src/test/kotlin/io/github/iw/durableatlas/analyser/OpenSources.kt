package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try what the flow waits for and how far: races, in which the first
 * lane to end takes the flow on; steps that a call gives a promise for; a promise that is
 * waited for a time at most; scope functions whose value is dropped; waits for room in a queue,
 * on what a listener feeds, and on what the workflow method lets go of itself; a child whose
 * start is waited for from outside the region it runs in; and upserts that name only some of
 * what they write. Each is in a file of its own and as small as its case allows. The tests
 * name nodes by their lines in these files, counted from the `package` line.
 */
internal object OpenSources {
    private const val HEADER = """package open

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.ParentClosePolicy
import io.temporal.common.SearchAttributeKey
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.DynamicSignalHandler
import io.temporal.workflow.Promise
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
"""

    /** The files that hold no workflow of their own. */
    val SHARED: Set<String> = setOf("Chores")

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Chores" to """
@ActivityInterface
interface Chores {
    fun fetch(id: String): String
    fun store(id: String): String
    fun notify(id: String)
    fun audit(id: String): String
}

@WorkflowInterface
interface Errand {
    @WorkflowMethod
    fun run(id: String)
}

object Limits {
    val SHORT: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
    val ABANDON: ChildWorkflowOptions =
        ChildWorkflowOptions.newBuilder().setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON).build()
}
""",
        // Two lanes of which the first to end takes the flow on, and two that are not a race after all.
        "Fastest" to """
@WorkflowInterface
interface Fastest {
    @WorkflowMethod
    fun run(id: String): String
}

class FastestImpl(private val chores: Chores) : Fastest {
    override fun run(id: String): String {
        val here = Async.function(chores::fetch, id)
        val there = Async.function(chores::store, id)
        Promise.anyOf(here, there).get()
        chores.notify(id)
        val first = Async.function(chores::fetch, "again")
        val second = Async.function(chores::store, "again")
        first.get()
        Promise.anyOf(first, second).get()
        return second.get()
    }
}
""",
        // Lanes that are started in a scope and kept in a list; the scope is cancelled once the first of them is done.
        "Winner" to """
@WorkflowInterface
interface Winner {
    @WorkflowMethod
    fun run(id: String): String
}

class WinnerImpl(private val chores: Chores) : Winner {
    override fun run(id: String): String {
        val results = ArrayList<Promise<String>>()
        val scope = Workflow.newCancellationScope(Runnable {
            results.add(Async.function(chores::fetch, id))
            results += Async.function {
                Workflow.sleep(Duration.ofSeconds(10))
                chores.store(id)
            }
        })
        scope.run()
        val winner = Promise.anyOf(results).get()
        scope.cancel()
        return chores.store(winner)
    }
}
""",
        // One lane for each of many, of which the first to end takes the flow on.
        "Soonest" to """
@WorkflowInterface
interface Soonest {
    @WorkflowMethod
    fun run(ids: List<String>): String
}

class SoonestImpl(private val chores: Chores) : Soonest {
    override fun run(ids: List<String>): String {
        val results = ids.map { Async.function(chores::fetch, it) }
        val winner = Promise.anyOf(results).get()
        return chores.store(winner)
    }
}
""",
        // Work that is raced against a timer, which is started with a call that gives a promise for it.
        "Deadline" to """
@WorkflowInterface
interface Deadline {
    @WorkflowMethod
    fun run(id: String): String
}

class DeadlineImpl(private val chores: Chores) : Deadline {
    override fun run(id: String): String {
        val work = Async.function(chores::fetch, id)
        val limit = Workflow.newTimer(Duration.ofMinutes(1))
        Promise.anyOf(work, limit).get()
        if (!work.isCompleted) {
            chores.notify(id)
        }
        return work.get()
    }
}
""",
        // Stubs without an interface that are asked to start something and give a promise for it.
        "Handles" to """
@WorkflowInterface
interface Handles {
    @WorkflowMethod
    fun run(id: String): String
}

class HandlesImpl : Handles {
    private val chores = Workflow.newUntypedActivityStub(Limits.SHORT)

    override fun run(id: String): String {
        val fetched = chores.executeAsync("Fetch", String::class.java, id)
        val stored = chores.executeAsync("Store", String::class.java, id)
        Promise.allOf(fetched, stored).get()
        chores.executeAsync("Notify", Void::class.java, id)
        val errand = Workflow.newUntypedChildWorkflowStub("Errand", Limits.ABANDON)
        errand.executeAsync(Void::class.java, id)
        errand.execution.get()
        val other = Workflow.newUntypedChildWorkflowStub("Errand")
        val done = other.executeAsync(Void::class.java, id)
        chores.execute("Notify", Void::class.java, "meanwhile")
        done.get()
        return chores.executeAsync("Fetch", String::class.java, "last").get()
    }
}
""",
        // A timer and a condition that the flow is given promises for, and what is chained to such promises.
        "Pauses" to """
@WorkflowInterface
interface Pauses {
    @WorkflowMethod
    fun run(id: String): String

    @SignalMethod
    fun release()
}

class PausesImpl(private val chores: Chores) : Pauses {
    private var released = false

    override fun run(id: String): String {
        val pause = Workflow.newTimer(Duration.ofMinutes(5))
        val ready = Async.await { released }
        val fetched = chores.fetch(id)
        Promise.allOf(pause, ready).get()
        val later = Workflow.newTimer(Duration.ofMinutes(1)).thenApply { chores.store(fetched) }
        val told = Async.await(Duration.ofHours(1)) { released }.thenCompose { Async.procedure(chores::notify, id) }
        Promise.allOf(later, told).get()
        Workflow.newTimer(Duration.ofSeconds(30)).get()
        Workflow.newTimer(Duration.ofDays(1))
        return later.get()
    }

    override fun release() {
        released = true
    }
}
""",
        // A promise the workflow completes itself, waited for a time at most: when the time is up, the wait fails.
        "Patience" to """
@WorkflowInterface
interface Patience {
    @WorkflowMethod
    fun run(id: String): String

    @SignalMethod
    fun approve(by: String)
}

class PatienceImpl(private val chores: Chores) : Patience {
    private val approval = Workflow.newPromise<String>()

    override fun run(id: String): String {
        val by = try {
            approval.get(30, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            chores.notify(id)
            approval.cancellableGet(1, TimeUnit.HOURS)
        }
        try {
            chores.store(by)
            approval.get(5, TimeUnit.MINUTES)
        } catch (e: Exception) {
            chores.audit(id)
        }
        return chores.fetch(id)
    }

    override fun approve(by: String) {
        approval.complete(by)
    }
}
""",
        // Scope functions that are statements: what their lambda starts is given to nobody.
        "Dropped" to """
@WorkflowInterface
interface Dropped {
    @WorkflowMethod
    fun run(id: String): String
}

class DroppedImpl(private val chores: Chores) : Dropped {
    override fun run(id: String): String {
        run { Async.procedure(chores::notify, id) }
        chores.let { Async.function(it::fetch, id) }
        with(chores) { Async.function(this::store, id) }
        Async.function(chores::fetch, "again").also { Workflow.getLogger("open").info("started") }
        id.takeIf { it.isNotEmpty() }?.let {
            Async.procedure {
                chores.fetch(it)
                chores.notify(it)
            }
        }
        val kept = run { Async.function(chores::store, "kept") }
        return kept.get()
    }
}
""",
        // A queue that holds two: whoever puts a third in waits for room, which taking something out makes.
        "Buffer" to """
@WorkflowInterface
interface Buffer {
    @WorkflowMethod
    fun run(id: String): String

    @SignalMethod
    fun add(item: String)

    @SignalMethod
    fun drain()
}

class BufferImpl(private val chores: Chores) : Buffer {
    private val items = Workflow.newWorkflowQueue<String>(2)
    private val spare = Workflow.newWorkflowQueue<String>(Int.MAX_VALUE)

    override fun run(id: String): String {
        items.put(id)
        if (!items.offer(id, Duration.ofSeconds(10))) {
            return chores.fetch("full")
        }
        items.cancellablePut(id)
        val added = items.cancellableOffer(id, Duration.ofSeconds(5))
        items.offer(id)
        spare.put(id)
        return chores.store(added.toString())
    }

    override fun add(item: String) {
        items.put(item)
    }

    override fun drain() {
        items.poll()
    }
}
""",
        // A queue and a promise that the workflow method keeps in variables of its own, and a handler it registers feeds.
        "Listener" to """
@WorkflowInterface
interface Listener {
    @WorkflowMethod
    fun run(): String
}

class ListenerImpl(private val chores: Chores) : Listener {
    override fun run(): String {
        val items = Workflow.newWorkflowQueue<String>(Int.MAX_VALUE)
        val done = Workflow.newPromise<String>()
        Workflow.registerListener(DynamicSignalHandler { name, args ->
            if (name == "stop") done.complete(name) else items.put(args.get(0, String::class.java))
        })
        val first = items.take()
        chores.store(first)
        return done.get()
    }
}
""",
        // What the workflow method lets go of itself: a permit in another lane, and a lock while an update waits for it.
        "Permits" to """
@WorkflowInterface
interface Permits {
    @WorkflowMethod
    fun run(id: String)

    @UpdateMethod
    fun change(id: String): String
}

class PermitsImpl(private val chores: Chores) : Permits {
    private val permits = Workflow.newWorkflowSemaphore(1)
    private val lock = Workflow.newWorkflowLock()
    private var ready = false

    override fun run(id: String) {
        lock.lock()
        val one = Async.procedure {
            permits.acquire()
            chores.fetch(id)
            permits.release()
        }
        val other = Async.procedure {
            permits.acquire()
            chores.store(id)
            permits.release()
        }
        Promise.allOf(one, other).get()
        ready = true
        lock.unlock()
        permits.acquire()
        chores.notify(id)
        permits.release()
    }

    override fun change(id: String): String {
        Workflow.await { ready }
        lock.lock()
        try {
            return chores.audit(id)
        } finally {
            lock.unlock()
        }
    }
}
""",
        // A child that runs in what nothing waits for, and whose start the flow outside waits for.
        "Begun" to """
@WorkflowInterface
interface Begun {
    @WorkflowMethod
    fun run(id: String)
}

class BegunImpl(private val chores: Chores) : Begun {
    override fun run(id: String) {
        val errand = Workflow.newChildWorkflowStub(Errand::class.java, Limits.ABANDON)
        Async.procedure {
            chores.fetch(id)
            errand.run(id)
            chores.notify(id)
        }
        Workflow.getWorkflowExecution(errand).get()
        chores.store(id)
        val inner = Workflow.newChildWorkflowStub(Errand::class.java)
        Async.procedure {
            chores.fetch("inner")
            inner.run(id)
            Workflow.getWorkflowExecution(inner).get()
        }
    }
}
""",
        // Search attributes and memo entries of which the code names some, and leaves the rest to what it is handed.
        "Tags" to """
private val STAGE = SearchAttributeKey.forKeyword("Stage")
private val RANK = SearchAttributeKey.forLong("Rank")

@WorkflowInterface
interface Tags {
    @WorkflowMethod
    fun run(extra: Map<String, Any>, key: String)
}

class TagsImpl(private val chores: Chores) : Tags {
    override fun run(extra: Map<String, Any>, key: String) {
        val rank = if (key.isEmpty()) RANK.valueUnset() else RANK.valueSet(1L)
        Workflow.upsertTypedSearchAttributes(STAGE.valueSet("started"), rank)
        Workflow.upsertSearchAttributes(mapOf("Stage" to "running", key to "x", "Owner" to "ops"))
        Workflow.upsertMemo(mapOf("note" to "running") + extra + ("by" to "ops"))
        chores.notify(key)
        Workflow.upsertMemo(extra + extra)
        Workflow.upsertTypedSearchAttributes(STAGE.valueSet("done"), RANK.valueUnset())
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
