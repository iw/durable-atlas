package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try what a workflow waits for and what it does not: a queue or a promise
 * that a handler feeds, work started beside the flow that nothing waits for, a child that is
 * only waited for until it has started, cancellation scopes and the places they are cancelled
 * at, another workflow that is asked to cancel, what is chained to a promise, a retry that runs
 * beside the flow, and children whose workflow type two interfaces share. Each is in a file of
 * its own and as small as its case allows. The tests name nodes by their lines in these files,
 * counted from the `package` line.
 */
internal object PatternSources {
    private const val HEADER = """package patterns

import io.temporal.activity.ActivityInterface
import io.temporal.api.enums.v1.ParentClosePolicy
import io.temporal.common.RetryOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.CanceledFailure
import io.temporal.workflow.Async
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.CompletablePromise
import io.temporal.workflow.ExternalWorkflowStub
import io.temporal.workflow.Promise
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit
"""

    /** The files that hold no workflow of their own. */
    val SHARED: Set<String> = setOf("Tasks")

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Tasks" to """
@ActivityInterface
interface Tasks {
    fun prepare(id: String): String
    fun check(id: String): Boolean
    fun work(id: String)
    fun report(id: String)
    fun cleanUp(id: String)
    fun undo(id: String)
    fun announce(id: String)
    fun load(id: String): String
    fun weigh(id: String): Boolean
    fun audit(id: String)
}
""",
        // A queue that a signal and an update fill: taken from, and asked for a time at most.
        "Inbox" to """
@WorkflowInterface
interface Inbox {
    @WorkflowMethod
    fun run(): String

    @SignalMethod
    fun add(item: String)

    @UpdateMethod
    fun push(item: String): Boolean
}

class InboxImpl(private val tasks: Tasks) : Inbox {
    private val items = Workflow.newWorkflowQueue<String>(8)

    override fun run(): String {
        val first = items.take()
        tasks.work(first)
        val second = items.poll(Duration.ofMinutes(5)) ?: return "one"
        tasks.work(second)
        val third = items.cancellablePoll(Duration.ofSeconds(30))
        if (third == null) {
            tasks.report("two")
        }
        return "all"
    }

    override fun add(item: String) {
        items.put(item)
    }

    override fun push(item: String): Boolean = items.offer(item)
}
""",
        // Promises that signals complete: waited for without an end, and for a time at most.
        "Approval" to """
@WorkflowInterface
interface Approval {
    @WorkflowMethod
    fun run(id: String): String

    @SignalMethod
    fun approve(by: String)

    @SignalMethod
    fun reject(reason: String)
}

class ApprovalImpl(private val tasks: Tasks) : Approval {
    private val decision = Workflow.newPromise<String>()
    private val second: CompletablePromise<String> = Workflow.newPromise()

    override fun run(id: String): String {
        tasks.prepare(id)
        val by = decision.get()
        tasks.work(by)
        return second.cancellableGet(1, TimeUnit.HOURS)
    }

    override fun approve(by: String) {
        if (!decision.isCompleted) decision.complete(by) else second.complete(by)
    }

    override fun reject(reason: String) {
        decision.completeExceptionally(ApplicationFailure.newFailure(reason, "Rejected"))
    }
}
""",
        // Work started beside the flow: dropped at once, kept and never asked for, and waited for.
        "Unjoined" to """
@WorkflowInterface
interface Unjoined {
    @WorkflowMethod
    fun run(id: String)
}

class UnjoinedImpl(private val tasks: Tasks) : Unjoined {
    override fun run(id: String) {
        Async.procedure(tasks::announce, id)
        Async.procedure {
            tasks.load(id)
            if (tasks.weigh(id)) throw IllegalStateException("unseen")
            tasks.audit(id)
        }
        val checked = Async.function(tasks::check, id)
        val forgotten = Async.function(tasks::prepare, "spare")
        tasks.work("main")
        checked.get()
        try {
            Async.procedure(tasks::undo, id)
            tasks.cleanUp(id)
        } catch (e: RuntimeException) {
            tasks.report("failed")
        }
    }
}
""",
        // Children started beside the flow: one that is waited for until it has started and outlives its parent,
        // one that nothing waits for, and one that the flow waits for as usual.
        "Detach" to """
@WorkflowInterface
interface Courier {
    @WorkflowMethod
    fun deliver(id: String)
}

class CourierImpl(private val tasks: Tasks) : Courier {
    override fun deliver(id: String) {
        tasks.work(id)
    }
}

@WorkflowInterface
interface Detach {
    @WorkflowMethod
    fun run(id: String)
}

class DetachImpl : Detach {
    override fun run(id: String) {
        val options = ChildWorkflowOptions.newBuilder().setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON).build()
        val courier = Workflow.newChildWorkflowStub(Courier::class.java, options)
        Async.procedure(courier::deliver, id)
        Workflow.getWorkflowExecution(courier).get()
        val other = Workflow.newChildWorkflowStub(Courier::class.java)
        Async.procedure { other.deliver("other") }
        val asked = ChildWorkflowOptions.newBuilder().setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL).build()
        Workflow.newChildWorkflowStub(Courier::class.java, asked).deliver("bound")
    }
}
""",
        // A scope whose work runs beside a timer and is cancelled when the timer has fired.
        "CancelLater" to """
@WorkflowInterface
interface CancelLater {
    @WorkflowMethod
    fun run(id: String)
}

class CancelLaterImpl(private val tasks: Tasks) : CancelLater {
    override fun run(id: String) {
        val scope = Workflow.newCancellationScope(Runnable { Async.function(tasks::prepare, id) })
        scope.run()
        Workflow.sleep(Duration.ofMinutes(1))
        scope.cancel()
        tasks.report(id)
    }
}
""",
        // A scope that starts one lane for each of many and is cancelled as soon as the first is done.
        "Race" to """
@WorkflowInterface
interface Race {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class RaceImpl(private val tasks: Tasks) : Race {
    override fun run(ids: List<String>) {
        val results = ArrayList<Promise<String>>()
        val scope = Workflow.newCancellationScope(Runnable {
            for (id in ids) results.add(Async.function(tasks::prepare, id))
        })
        scope.run()
        Promise.anyOf(results).get()
        scope.cancel()
        tasks.report("first")
    }
}
""",
        // A scope that the workflow keeps in a property, and that a signal cancels.
        "Stoppable" to """
@WorkflowInterface
interface Stoppable {
    @WorkflowMethod
    fun run(id: String)

    @SignalMethod
    fun stop()
}

class StoppableImpl(private val tasks: Tasks) : Stoppable {
    private var scope: CancellationScope? = null

    override fun run(id: String) {
        try {
            Workflow.newCancellationScope { _ ->
                tasks.prepare(id)
                Workflow.sleep(Duration.ofHours(1))
                tasks.work(id)
            }.also { scope = it }.run()
        } catch (e: CanceledFailure) {
            Workflow.newDetachedCancellationScope(Runnable { tasks.cleanUp(id) }).run()
        }
    }

    override fun stop() {
        scope?.cancel()
    }
}
""",
        // A scope that cancels itself, the scope it is handed, and one that cannot be told.
        "SelfStop" to """
@WorkflowInterface
interface SelfStop {
    @WorkflowMethod
    fun run(id: String)
}

class SelfStopImpl(private val tasks: Tasks) : SelfStop {
    override fun run(id: String) {
        Workflow.newCancellationScope(Runnable {
            Async.procedure(tasks::work, id)
            if (!tasks.check(id)) CancellationScope.current().cancel()
        }).run()
        Workflow.newCancellationScope { own ->
            Async.procedure(tasks::prepare, id)
            Workflow.sleep(Duration.ofSeconds(5))
            own.cancel()
        }.run()
        val scopes = listOf(Workflow.newCancellationScope(Runnable { Async.procedure(tasks::report, id) }))
        scopes.forEach { it.run() }
        scopes.first().cancel()
        CancellationScope.current().cancel("done")
    }
}
""",
        // Other workflows that are asked to cancel: one known by its id alone, and one of a known type.
        "CallOff" to """
@WorkflowInterface
interface Carrier {
    @WorkflowMethod
    fun carry(id: String)
}

class CarrierImpl(private val tasks: Tasks) : Carrier {
    override fun carry(id: String) {
        tasks.work(id)
    }
}

@WorkflowInterface
interface CallOff {
    @WorkflowMethod
    fun run(id: String, other: String)
}

class CallOffImpl : CallOff {
    override fun run(id: String, other: String) {
        Workflow.newUntypedExternalWorkflowStub(other).cancel()
        val carrier = Workflow.newExternalWorkflowStub(Carrier::class.java, id)
        ExternalWorkflowStub.fromTyped(carrier).cancel()
    }
}
""",
        // What is chained to a promise: after a success, after a failure, and either way.
        "Chains" to """
@WorkflowInterface
interface Chains {
    @WorkflowMethod
    fun run(id: String): String
}

class ChainsImpl(private val tasks: Tasks) : Chains {
    override fun run(id: String): String {
        Async.procedure(tasks::report, id).thenApply { tasks.cleanUp(id) }
        val prepared = Async.function(tasks::prepare, id)
            .thenApply { name -> tasks.work(name); name }
            .exceptionally { tasks.undo(id); "none" }
        val checked = Async.function(tasks::check, id)
            .thenCompose { fine -> Async.function(tasks::prepare, if (fine) "a" else "b") }
            .handle { name, failure -> if (failure != null) tasks.report(id); name }
        Promise.allOf(prepared, checked).get()
        return prepared.get()
    }
}
""",
        // What is started beside the flow and started again when it fails.
        "AsyncRetries" to """
@WorkflowInterface
interface AsyncRetries {
    @WorkflowMethod
    fun run(id: String): String
}

class AsyncRetriesImpl(private val tasks: Tasks) : AsyncRetries {
    override fun run(id: String): String {
        val options = RetryOptions.newBuilder().setMaximumAttempts(3).build()
        val prepared = Async.retry(options, Optional.of(Duration.ofMinutes(10))) { Async.function(tasks::prepare, id) }
        tasks.check(id)
        val name = prepared.get()
        Async.retry(RetryOptions.newBuilder().build(), Optional.empty()) {
            Async.procedure(tasks::work, name).thenApply { tasks.report(name) }
        }.get()
        return name
    }
}
""",
        // Two workflow interfaces of one workflow type, and a workflow that starts a child of each.
        "Notices" to """
@WorkflowInterface
interface Notify {
    @WorkflowMethod(name = "Notice")
    fun send(id: String)
}

class NotifyImpl(private val tasks: Tasks) : Notify {
    override fun send(id: String) {
        tasks.report(id)
    }
}

@WorkflowInterface
interface Remind {
    @WorkflowMethod(name = "Notice")
    fun send(id: String)
}

class RemindImpl(private val tasks: Tasks) : Remind {
    override fun send(id: String) {
        tasks.check(id)
        tasks.report(id)
    }
}

@WorkflowInterface
interface Notices {
    @WorkflowMethod
    fun run(id: String)
}

class NoticesImpl : Notices {
    override fun run(id: String) {
        Workflow.newChildWorkflowStub(Notify::class.java).send(id)
        Workflow.newChildWorkflowStub(Remind::class.java).send(id)
        Workflow.newChildWorkflowStub(Courier::class.java).deliver(id)
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
