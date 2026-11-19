package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try what a history records, or what holds the flow up, without a call
 * that looks like a step: waits for a workflow lock or semaphore, search attributes and memo
 * entries that are set, options for activities that are set somewhere other than at the stub,
 * and activities that are run by handing the call of a stub to `Workflow.executeActivity`.
 * Each is in a file of its own and as small as its case allows. The tests name nodes by their
 * lines in these files, counted from the `package` line.
 */
internal object SilentSources {
    private const val HEADER = """package silent

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.common.SearchAttributeKey
import io.temporal.common.SearchAttributeUpdate
import io.temporal.worker.Worker
import io.temporal.worker.WorkflowImplementationOptions
import io.temporal.workflow.ActivityInvocationOptions
import io.temporal.workflow.Functions
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
"""

    /** The files that hold no workflow of their own. */
    val SHARED: Set<String> = setOf("Jobs", "Workers")

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Jobs" to """
@ActivityInterface
interface Jobs {
    fun fetch(id: String): String
    fun store(id: String)
    fun notify(id: String)
}

object Timeouts {
    val SHORT: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
    val LONG: ActivityOptions = ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(2)).setTaskQueue("slow").build()
    val QUICK: LocalActivityOptions = LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(2)).build()
}
""",
        // A lock that keeps the runs of two updates apart, and that a signal can let go of.
        "Guarded" to """
@WorkflowInterface
interface Guarded {
    @WorkflowMethod
    fun run()

    @UpdateMethod
    fun change(id: String): String

    @UpdateMethod
    fun tryChange(id: String): String

    @SignalMethod
    fun release()
}

class GuardedImpl(private val jobs: Jobs) : Guarded {
    private val lock = Workflow.newWorkflowLock()

    override fun run() {
        Workflow.await { Workflow.isEveryHandlerFinished() }
    }

    override fun change(id: String): String {
        lock.lock()
        try {
            return jobs.fetch(id)
        } finally {
            lock.unlock()
        }
    }

    override fun tryChange(id: String): String {
        if (!lock.tryLock(Duration.ofSeconds(30))) return "busy"
        jobs.store(id)
        lock.unlock()
        return if (lock.tryLock()) "again" else "held"
    }

    override fun release() {
        if (lock.isHeld) lock.unlock()
    }
}
""",
        // A semaphore that the workflow method waits for, with and without a time, and that a signal adds to.
        "Pool" to """
@WorkflowInterface
interface Pool {
    @WorkflowMethod
    fun run(ids: List<String>)

    @SignalMethod
    fun grant()
}

class PoolImpl(private val jobs: Jobs) : Pool {
    private val permits = Workflow.newWorkflowSemaphore(2)

    override fun run(ids: List<String>) {
        for (id in ids) {
            permits.acquire()
            jobs.fetch(id)
            permits.release()
        }
        val both = permits.tryAcquire(2, Duration.ofMinutes(1))
        if (both) {
            jobs.store("all")
        } else {
            jobs.notify("busy")
        }
        if (permits.tryAcquire()) jobs.notify("one more")
        permits.acquire(2)
    }

    override fun grant() {
        permits.release()
    }
}
""",
        // Search attributes and memo entries: set, removed, named by constants and not named at all.
        "Labels" to """
private val STAGE = SearchAttributeKey.forKeyword("Stage")

@WorkflowInterface
interface Labels {
    @WorkflowMethod
    fun run(extra: Map<String, Any>)
}

class LabelsImpl(private val jobs: Jobs) : Labels {
    private val attempts = SearchAttributeKey.forLong("Attempts")

    override fun run(extra: Map<String, Any>) {
        Workflow.upsertTypedSearchAttributes(STAGE.valueSet("started"), attempts.valueSet(1L))
        Workflow.upsertMemo(mapOf("note" to "started"))
        jobs.fetch("a")
        Workflow.upsertTypedSearchAttributes(SearchAttributeUpdate.valueSet(STAGE, "fetched"), attempts.valueUnset())
        Workflow.upsertSearchAttributes(mapOf("Stage" to "done", "Owner" to "ops"))
        Workflow.upsertMemo(java.util.Map.of("note", "done", "by", "ops"))
        Workflow.upsertSearchAttributes(extra)
        Workflow.upsertMemo(extra)
        Workflow.upsertTypedSearchAttributes(SearchAttributeKey.forKeyword(extra.keys.first()).valueSet("x"))
    }
}
""",
        // Defaults that the workflow sets itself, for the stubs it makes afterwards.
        "Defaults" to """
@WorkflowInterface
interface Defaults {
    @WorkflowMethod
    fun run()
}

class DefaultsImpl : Defaults {
    override fun run() {
        Workflow.setDefaultActivityOptions(Timeouts.SHORT)
        Workflow.setDefaultLocalActivityOptions(Timeouts.QUICK)
        val jobs = Workflow.newActivityStub(Jobs::class.java)
        val near = Workflow.newLocalActivityStub(Jobs::class.java)
        jobs.fetch("a")
        near.store("a")
        Workflow.setDefaultActivityOptions(ActivityOptions.newBuilder().setTaskQueue("other").build())
        Workflow.newActivityStub(Jobs::class.java).notify("a")
        jobs.notify("b")
    }
}
""",
        // Options for single activity types, which lie over those of a stub from the moment they are set.
        "PerType" to """
@WorkflowInterface
interface PerType {
    @WorkflowMethod
    fun run()
}

class PerTypeImpl : PerType {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, Timeouts.SHORT)
    private val near = Workflow.newLocalActivityStub(Jobs::class.java, Timeouts.QUICK)

    override fun run() {
        jobs.fetch("before")
        Workflow.applyActivityOptions(mapOf("Fetch" to Timeouts.LONG))
        jobs.fetch("after")
        jobs.store("a")
        Workflow.setActivityOptions(mapOf("Store" to ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(9)).build()))
        jobs.store("b")
        Workflow.applyLocalActivityOptions(mapOf("Notify" to LocalActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(8)).build()))
        near.notify("a")
        near.fetch("a")
    }
}
""",
        // Stubs made with a map of options for single activity types, which stands in for what the workflow sets.
        "StubMaps" to """
@WorkflowInterface
interface StubMaps {
    @WorkflowMethod
    fun run(options: Map<String, ActivityOptions>)
}

class StubMapsImpl : StubMaps {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, Timeouts.SHORT, mapOf("Store" to Timeouts.LONG))
    private val near = Workflow.newLocalActivityStub(
        Jobs::class.java,
        Timeouts.QUICK,
        mapOf("Store" to LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(4)).build()),
    )

    override fun run(options: Map<String, ActivityOptions>) {
        Workflow.applyActivityOptions(mapOf("Fetch" to Timeouts.LONG))
        jobs.fetch("a")
        jobs.store("a")
        near.store("a")
        val other = Workflow.newActivityStub(Jobs::class.java, Timeouts.SHORT, options)
        other.fetch("b")
    }
}
""",
        // Workflows that get their options where a worker registers their class: one once, one twice.
        "Registered" to """
@WorkflowInterface
interface Registered {
    @WorkflowMethod
    fun run()
}

class RegisteredImpl : Registered {
    private val jobs = Workflow.newActivityStub(Jobs::class.java)
    private val near = Workflow.newLocalActivityStub(Jobs::class.java)
    private val own = Workflow.newActivityStub(Jobs::class.java, ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(1)).build())

    override fun run() {
        jobs.fetch("a")
        jobs.store("a")
        near.fetch("a")
        own.fetch("b")
        own.store("b")
    }
}

@WorkflowInterface
interface Twice {
    @WorkflowMethod
    fun run()
}

class TwiceImpl : Twice {
    private val jobs = Workflow.newActivityStub(Jobs::class.java)

    override fun run() {
        jobs.fetch("a")
        jobs.store("a")
    }
}
""",
        "Workers" to """
object Workers {
    fun register(worker: Worker, other: Worker) {
        val options = WorkflowImplementationOptions.newBuilder()
            .setDefaultActivityOptions(Timeouts.SHORT)
            .setActivityOptions(mapOf("Store" to Timeouts.LONG))
            .setDefaultLocalActivityOptions(Timeouts.QUICK)
            .build()
        worker.registerWorkflowImplementationTypes(options, RegisteredImpl::class.java, TwiceImpl::class.java)
        other.registerWorkflowImplementationTypes(
            WorkflowImplementationOptions.newBuilder()
                .setDefaultActivityOptions(Timeouts.LONG)
                .setActivityOptions(mapOf("Store" to Timeouts.LONG))
                .build(),
            TwiceImpl::class.java,
        )
        other.registerWorkflowImplementationTypes(DefaultsImpl::class.java)
    }
}
""",
        // What is set when, and what is certain of it: before and after a stub is made, in a branch, and for a handler.
        "Precedence" to """
@WorkflowInterface
interface Precedence {
    @WorkflowMethod
    fun run(fast: Boolean)

    @SignalMethod
    fun nudge()
}

class PrecedenceImpl : Precedence {
    private val early = Workflow.newActivityStub(Jobs::class.java)
    private var ready = false

    init {
        Workflow.setDefaultActivityOptions(Timeouts.SHORT)
    }

    private val late = Workflow.newActivityStub(Jobs::class.java)

    override fun run(fast: Boolean) {
        early.fetch("a")
        late.fetch("a")
        if (fast) {
            Workflow.setDefaultActivityOptions(Timeouts.LONG)
        }
        Workflow.newActivityStub(Jobs::class.java).store("a")
        Workflow.setDefaultActivityOptions(ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build())
        Workflow.newActivityStub(Jobs::class.java).notify("a")
        Workflow.await { ready }
    }

    override fun nudge() {
        ready = true
        Workflow.newActivityStub(Jobs::class.java).notify("b")
        late.store("b")
    }
}
""",
        // Activities run by handing the call of a stub to the workflow: in place, and beside the flow.
        "Direct" to """
@WorkflowInterface
interface Direct {
    @WorkflowMethod
    fun run(id: String): String
}

class DirectImpl : Direct {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, Timeouts.SHORT)
    private val near = Workflow.newLocalActivityStub(Jobs::class.java, Timeouts.QUICK)

    override fun run(id: String): String {
        val once = ActivityInvocationOptions.newBuilder().setActivityId("fetch-" + id).build()
        val fetched = Workflow.executeActivity(Functions.Func1(jobs::fetch), once, id)
        val stored = Workflow.executeActivityAsync(Functions.Proc1(jobs::store), ActivityInvocationOptions.getDefaultInstance(), fetched)
        Workflow.executeActivity(near::notify, ActivityInvocationOptions.getDefaultInstance(), id)
        stored.get()
        Workflow.executeActivityAsync(Functions.Proc { jobs.notify(id) }, ActivityInvocationOptions.getDefaultInstance())
        return fetched
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
