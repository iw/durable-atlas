package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try what the sample project does not contain of calls into functions of
 * the sources and of handlers, each in a file of its own and as small as its case allows. The
 * tests name nodes by their lines in these files, counted from the `package` line.
 */
internal object InliningSources {
    private const val HEADER = """package inline

import io.temporal.activity.ActivityInterface
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.Saga
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
"""

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Tasks" to """
@ActivityInterface
interface Tasks {
    fun prepare(id: String): String
    fun check(id: String): Boolean
    fun work(id: String)
    fun report(id: String)
    fun undo(id: String)
    fun cleanUp(id: String)
}
""",
        "Helpers" to """
/** Runs what it is given, and once more if that fails. */
fun <T> retrying(tasks: Tasks, block: () -> T): T {
    try {
        return block()
    } catch (e: ActivityFailure) {
        tasks.report("retry")
    }
    return block()
}

/** Starts what it is given beside the flow. */
fun <T> beside(block: () -> T): Promise<T> = Async.function(block)

class Deferral {
    var action: (() -> Unit)? = null
}

/** Keeps what it is given for later: nothing here says when it runs. */
fun later(deferral: Deferral, block: () -> Unit) {
    deferral.action = block
}

inline fun <T> measured(block: () -> T): T = block()

object Toolbox {
    fun tidy(tasks: Tasks, id: String) {
        tasks.cleanUp(id)
    }
}

fun reserve(tasks: Tasks, undo: Saga, id: String) {
    tasks.prepare(id)
    undo.addCompensation { tasks.undo(id) }
}

fun noteOnly(undo: Saga, tasks: Tasks) {
    undo.addCompensation { tasks.cleanUp("note") }
}

fun rollBack(saga: Saga) {
    saga.compensate()
}
""",
        "Wrappers" to """
@WorkflowInterface
interface Wrappers {
    @WorkflowMethod
    fun run(id: String): String
}

class WrappersImpl(private val tasks: Tasks) : Wrappers {
    override fun run(id: String): String {
        val prepared = retrying(tasks) { tasks.prepare(id) }
        val checked = beside { tasks.check(id) }
        checked.get()
        later(Deferral()) { tasks.undo(id) }
        return measured {
            if (prepared.isEmpty()) return "nothing"
            tasks.work(prepared)
            "done"
        }
    }
}
""",
        "Getters" to """
@WorkflowInterface
interface Getters {
    @WorkflowMethod
    fun run()
}

object Names {
    val DEFAULT: String
        get() = "default"
}

class GettersImpl(private val tasks: Tasks) : Getters {
    private val ready: Boolean
        get() = tasks.check("ready")
    private val name: String
        get() = Names.DEFAULT.trim()

    override fun run() {
        if (ready) {
            tasks.work(name)
        }
        tasks.report(Names.DEFAULT)
    }
}
""",
        "Members" to """
@WorkflowInterface
interface Members {
    @WorkflowMethod
    fun run(id: String)
}

abstract class Template(protected val tasks: Tasks) {
    fun runAll(id: String) {
        before(id)
        tasks.work(id)
        after(id)
    }

    protected open fun before(id: String) {}

    protected abstract fun after(id: String)
}

class MembersImpl(tasks: Tasks) : Template(tasks), Members {
    override fun run(id: String) {
        runAll(id)
        Toolbox.tidy(tasks, id)
        id.announce()
    }

    override fun before(id: String) {
        tasks.prepare(id)
    }

    override fun after(id: String) {
        tasks.report(id)
        super.before(id)
    }

    private fun String.announce() {
        tasks.check(this)
    }
}

/** A second class of the template that is no workflow: what `after` runs depends on the object. */
class OtherTemplate(tasks: Tasks) : Template(tasks) {
    override fun after(id: String) {
        tasks.undo(id)
    }
}
""",
        "Choices" to """
@WorkflowInterface
interface Choices {
    @WorkflowMethod
    fun run(id: String, fast: Boolean)
}

interface Courier {
    fun deliver(id: String)
}

class VanCourier(private val tasks: Tasks) : Courier {
    override fun deliver(id: String) {
        tasks.work(id)
    }
}

class BikeCourier(private val tasks: Tasks) : Courier {
    override fun deliver(id: String) {
        tasks.report(id)
    }
}

interface Formatter {
    fun format(id: String): String
}

class Upper : Formatter {
    override fun format(id: String): String = id.uppercase()
}

class Lower : Formatter {
    override fun format(id: String): String = id.lowercase()
}

/** No class of these sources implements this one. */
interface Hook {
    fun fire(id: String)
}

interface Auditor {
    fun audit(id: String)
}

class TaskAuditor(private val tasks: Tasks) : Auditor {
    override fun audit(id: String) {
        tasks.cleanUp(id)
    }
}

class ChoicesImpl(private val tasks: Tasks, private val hook: Hook) : Choices {
    private val auditor: Auditor = TaskAuditor(tasks)

    override fun run(id: String, fast: Boolean) {
        val courier: Courier = if (fast) BikeCourier(tasks) else VanCourier(tasks)
        courier.deliver(id)
        val formatter: Formatter = if (fast) Upper() else Lower()
        formatter.format(id)
        val van: Courier = VanCourier(tasks)
        van.deliver(id)
        hook.fire(id)
        auditor.audit(id)
    }
}
""",
        "Cycles" to """
@WorkflowInterface
interface Cycles {
    @WorkflowMethod
    fun run(count: Int)
}

class Link(val next: Link?)

/** Calls itself and holds no step. */
fun length(link: Link?): Int = if (link == null) 0 else 1 + length(link.next)

fun ping(tasks: Tasks, count: Int) {
    if (count > 0) {
        tasks.work("ping")
        pong(tasks, count - 1)
    }
}

fun pong(tasks: Tasks, count: Int) {
    tasks.report("pong")
    ping(tasks, count)
}

class CyclesImpl(private val tasks: Tasks) : Cycles {
    override fun run(count: Int) {
        length(Link(null))
        ping(tasks, count)
        measured { measured { tasks.check("nested") } }
    }
}
""",
        "Levels" to """
@WorkflowInterface
interface Levels {
    @WorkflowMethod
    fun run(id: String)
}

fun label(id: String): String = id.trim()

fun outer(tasks: Tasks, id: String) {
    label(id)
    tasks.prepare(id)
    inner(tasks, label(id))
}

fun inner(tasks: Tasks, id: String) {
    tasks.work(label(id))
}

class LevelsImpl(private val tasks: Tasks) : Levels {
    override fun run(id: String) {
        outer(tasks, id)
        outer(tasks, label(id))
    }
}
""",
        "Undoing" to """
@WorkflowInterface
interface Undoing {
    @WorkflowMethod
    fun run()
}

class UndoingImpl(private val tasks: Tasks) : Undoing {
    override fun run() {
        val saga = Saga(Saga.Options.Builder().build())
        try {
            noteOnly(saga, tasks)
            reserve(tasks, saga, "first")
            reserve(tasks, saga, "second")
            tasks.work("all")
        } catch (e: ActivityFailure) {
            rollBack(saga)
            throw e
        }
    }
}
""",
        "Stateful" to """
@WorkflowInterface
interface Stateful {
    @WorkflowMethod
    fun run(): String

    @SignalMethod
    fun pay()

    @SignalMethod
    fun cancel()

    @SignalMethod
    fun add(item: String)

    @SignalMethod
    fun count(key: String)

    @UpdateMethod
    fun retry(): Int

    @UpdateMethod
    fun rename(name: String)

    @UpdateValidatorMethod(updateName = "rename")
    fun validateRename(name: String)

    @QueryMethod
    fun describe(): String
}

class Progress {
    var paid = false
    val notes = ArrayList<String>()

    fun markPaid() {
        paid = true
    }
}

class StatefulImpl(private val tasks: Tasks) : Stateful, Pausable {
    private val progress = Progress()
    private val items = ArrayList<String>()
    private val counts = HashMap<String, Int>()
    private var cancelled = false
    private var attempts = 0
    private var label = ""

    override fun run(): String {
        Workflow.await { progress.paid || isCancelled() }
        Workflow.await { items.isNotEmpty() }
        waitFor { counts.size > 2 }
        Workflow.await { attempts > 3 }
        return label
    }

    private fun isCancelled(): Boolean = cancelled

    private fun waitFor(condition: () -> Boolean) {
        Workflow.await(Duration.ofMinutes(5)) { condition() }
    }

    override fun pay() {
        progress.markPaid()
    }

    override fun cancel() {
        markCancelled()
    }

    private fun markCancelled() {
        cancelled = true
    }

    override fun add(item: String) {
        items += item
    }

    override fun count(key: String) {
        counts[key] = 1
    }

    override fun retry(): Int {
        attempts++
        Workflow.await { cancelled }
        return attempts
    }

    override fun rename(name: String) {
        with(progress) { notes.add(name) }
        val previous = label
        label = name
        tasks.report(previous)
    }

    override fun validateRename(name: String) {
        require(name != label) { "the name is the same" }
    }

    override fun describe(): String = label + progress.notes.size

    override fun pause() {
        cancelled = true
    }
}

/** Handlers of a second interface, which the class registers as well. */
@WorkflowInterface
interface Pausable {
    @SignalMethod
    fun pause()
}
""",
        "Rounds" to """
@WorkflowInterface
interface Rounds {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class RoundsImpl(private val tasks: Tasks) : Rounds {
    override fun run(ids: List<String>) {
        val saga = Saga(Saga.Options.Builder().build())
        for (id in ids) {
            tasks.prepare(id)
            if (id.isEmpty()) {
                tasks.report(id)
            }
        }
        outer@ for (id in ids) {
            for (part in id.split("-")) {
                if (part.isEmpty()) continue@outer
                if (part == "x") break
                tasks.work(part)
            }
        }
        val lane = Async.procedure {
            if (ids.isEmpty()) tasks.cleanUp("none")
        }
        lane.get()
        saga.addCompensation {
            if (ids.size > 1) tasks.undo("many")
        }
        saga.compensate()
    }
}
""",
        "Boundaries" to """
@WorkflowInterface
interface Boundaries {
    @WorkflowMethod
    fun run(id: String)
}

@WorkflowInterface
interface Neighbour {
    @WorkflowMethod
    fun start(id: String)

    @QueryMethod
    fun state(): String
}

class NeighbourImpl(private val tasks: Tasks) : Neighbour {
    override fun start(id: String) {
        tasks.work(id)
    }

    override fun state(): String {
        tasks.report("asked")
        return "running"
    }
}

@ActivityInterface
interface Clock {
    fun tick()
}

class ClockImpl : Clock {
    override fun tick() {
        Workflow.sleep(Duration.ofSeconds(1))
    }
}

class BoundariesImpl(private val tasks: Tasks) : Boundaries {
    private val neighbour = Workflow.newExternalWorkflowStub(Neighbour::class.java, "neighbour")
    private val clock = ClockImpl()

    override fun run(id: String) {
        neighbour.state()
        clock.tick()
        tasks.cleanUp(id)
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
