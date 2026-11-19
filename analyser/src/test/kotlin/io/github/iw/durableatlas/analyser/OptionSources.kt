package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try where the options of an activity come from when they are not written
 * out at the stub: options made from other options, options a worker is given in code that no
 * function holds, and stubs that a function makes and hands back. Each is in a file of its own
 * and as small as its case allows. The tests name nodes by their lines in these files, counted
 * from the `package` line.
 */
internal object OptionSources {
    private const val HEADER = """package options

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkflowImplementationOptions
import io.temporal.workflow.ActivityStub
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
"""

    /** The files that hold no workflow of their own. */
    val SHARED: Set<String> = setOf("Chores", "Launcher")

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Chores" to """
@ActivityInterface
interface Chores {
    fun fetch(id: String): String
    fun store(id: String)
    fun notify(id: String)
    fun sweep(id: String)
}

object Limits {
    val SHORT: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
    val LONG: ActivityOptions = ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(2)).setTaskQueue("slow").build()
    val QUICK: LocalActivityOptions = LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(2)).build()
}
""",
        // Options that are made from other options: they hold what those hold, and what their builder sets over it.
        "Copies" to """
@WorkflowInterface
interface Copies {
    @WorkflowMethod
    fun run()
}

class CopiesImpl : Copies {
    private val queued = Workflow.newActivityStub(Chores::class.java, Limits.SHORT.toBuilder().setTaskQueue("fast").build())
    private val bounded = Workflow.newActivityStub(Chores::class.java, ActivityOptions.newBuilder(Limits.LONG).setStartToCloseTimeout(Duration.ofSeconds(30)).build())
    private val shorter = Workflow.newActivityStub(Chores::class.java, Limits.LONG.toBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(1)).build())
    private val twice = Workflow.newActivityStub(Chores::class.java, ActivityOptions.newBuilder(Limits.SHORT.toBuilder().setTaskQueue("fast").build()).build())
    private val fresh = Workflow.newActivityStub(Chores::class.java, ActivityOptions.newBuilder(null).setStartToCloseTimeout(Duration.ofSeconds(7)).build())
    private val merged = Workflow.newActivityStub(Chores::class.java, ActivityOptions.newBuilder(Limits.SHORT).mergeActivityOptions(Limits.LONG).build())
    private val near = Workflow.newLocalActivityStub(Chores::class.java, Limits.QUICK.toBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(8)).build())
    private val nearer = Workflow.newLocalActivityStub(Chores::class.java, LocalActivityOptions.newBuilder(Limits.QUICK).build())

    override fun run() {
        queued.fetch("a")
        bounded.fetch("b")
        shorter.fetch("c")
        twice.fetch("d")
        fresh.fetch("e")
        merged.fetch("f")
        near.store("g")
        nearer.store("h")
        Workflow.setDefaultActivityOptions(Limits.SHORT.toBuilder().setTaskQueue("fast").build())
        Workflow.applyActivityOptions(mapOf("Notify" to Limits.LONG.toBuilder().setTaskQueue("slower").build()))
        val late = Workflow.newActivityStub(Chores::class.java)
        late.sweep("i")
        late.notify("j")
    }
}
""",
        // Options made from options that cannot be read, or by a builder that is kept: only what is certain is said.
        "Unread" to """
@WorkflowInterface
interface Unread {
    @WorkflowMethod
    fun run(given: ActivityOptions)
}

class UnreadImpl : Unread {
    override fun run(given: ActivityOptions) {
        Workflow.newActivityStub(Chores::class.java, given.toBuilder().setTaskQueue("mine").build()).fetch("a")
        Workflow.newActivityStub(Chores::class.java, ActivityOptions.newBuilder(given).setScheduleToCloseTimeout(Duration.ofMinutes(3)).build()).store("b")
        Workflow.newActivityStub(Chores::class.java, given.toBuilder().setStartToCloseTimeout(Duration.ofSeconds(3)).build()).notify("c")
        val builder = Limits.SHORT.toBuilder()
        builder.setScheduleToCloseTimeout(Duration.ofHours(1))
        Workflow.newActivityStub(Chores::class.java, builder.setTaskQueue("kept").build()).sweep("d")
        Workflow.newActivityStub(Chores::class.java, ActivityOptions.newBuilder(Limits.SHORT).mergeActivityOptions(given).build()).fetch("e")
        Workflow.newActivityStub(Chores::class.java, Limits.LONG.toBuilder().mergeActivityOptions(Limits.SHORT).setTaskQueue("after").build()).store("f")
        Workflow.setDefaultActivityOptions(given.toBuilder().setStartToCloseTimeout(Duration.ofSeconds(4)).build())
        Workflow.newActivityStub(Chores::class.java).notify("g")
    }
}
""",
        // Stubs that a function makes and hands back: each is made where the function is called.
        "Helpers" to """
@WorkflowInterface
interface Helpers {
    @WorkflowMethod
    fun run()
}

class HelpersImpl : Helpers {
    private val early = chores()

    private fun chores(): Chores = Workflow.newActivityStub(Chores::class.java)

    private fun near(): Chores {
        val stub = Workflow.newLocalActivityStub(Chores::class.java)
        return stub
    }

    private fun given(options: ActivityOptions): Chores = Workflow.newActivityStub(Chores::class.java, options)

    private fun untyped(): ActivityStub = Workflow.newUntypedActivityStub(Limits.LONG)

    private fun <T> stub(type: Class<T>): T = Workflow.newActivityStub(type)

    private fun wrapped(): Chores = chores()

    private fun kept(): Chores = early

    override fun run() {
        Workflow.setDefaultActivityOptions(Limits.SHORT)
        Workflow.setDefaultLocalActivityOptions(Limits.QUICK)
        Workflow.applyActivityOptions(mapOf("Sweep" to ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(9)).build()))
        early.fetch("a")
        early.sweep("a")
        kept().sweep("b")
        val late = chores()
        late.fetch("c")
        chores().fetch("d")
        near().store("e")
        given(Limits.LONG).notify("f")
        untyped().execute("Fetch", String::class.java, "g")
        stub(Chores::class.java).notify("h")
        wrapped().store("i")
        Workflow.setDefaultActivityOptions(Limits.LONG)
        late.store("j")
        chores().store("k")
    }
}
""",
        // Workflows that are given their options where a worker registers their class, in code that no function holds.
        "Wired" to """
@WorkflowInterface
interface ByInit {
    @WorkflowMethod
    fun run()
}

class ByInitImpl : ByInit {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val near = Workflow.newLocalActivityStub(Chores::class.java)

    override fun run() {
        chores.fetch("a")
        chores.store("a")
        near.fetch("a")
    }
}

@WorkflowInterface
interface ByProperty {
    @WorkflowMethod
    fun run()
}

class ByPropertyImpl : ByProperty {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val near = Workflow.newLocalActivityStub(Chores::class.java)

    override fun run() {
        chores.fetch("a")
        chores.store("a")
        near.fetch("a")
    }
}

@WorkflowInterface
interface ByConstructor {
    @WorkflowMethod
    fun run()
}

class ByConstructorImpl : ByConstructor {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val near = Workflow.newLocalActivityStub(Chores::class.java)

    override fun run() {
        chores.fetch("a")
        chores.store("a")
        near.fetch("a")
    }
}

@WorkflowInterface
interface ByFile {
    @WorkflowMethod
    fun run()
}

class ByFileImpl : ByFile {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val near = Workflow.newLocalActivityStub(Chores::class.java)

    override fun run() {
        chores.fetch("a")
        chores.store("a")
        near.fetch("a")
    }
}

@WorkflowInterface
interface Branched {
    @WorkflowMethod
    fun run()
}

class BranchedImpl : Branched {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val near = Workflow.newLocalActivityStub(Chores::class.java)

    override fun run() {
        chores.fetch("a")
        chores.store("a")
        near.fetch("a")
    }
}

@WorkflowInterface
interface Handed {
    @WorkflowMethod
    fun run()
}

class HandedImpl : Handed {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val near = Workflow.newLocalActivityStub(Chores::class.java)

    override fun run() {
        chores.fetch("a")
        chores.store("a")
        near.fetch("a")
    }
}
""",
        "Launcher" to """
class Launcher(worker: Worker, quick: Boolean) {
    private val base = WorkflowImplementationOptions.newBuilder()
        .setDefaultActivityOptions(Limits.SHORT)
        .setActivityOptions(mapOf("Store" to Limits.LONG))
        .setDefaultLocalActivityOptions(Limits.QUICK)
        .build()

    init {
        worker.registerWorkflowImplementationTypes(base.toBuilder().setDefaultActivityOptions(Limits.LONG).build(), ByInitImpl::class.java)
        if (quick) {
            worker.registerWorkflowImplementationTypes(base, BranchedImpl::class.java)
        } else {
            worker.registerWorkflowImplementationTypes(
                WorkflowImplementationOptions.newBuilder(base).setDefaultActivityOptions(Limits.LONG).build(),
                BranchedImpl::class.java,
            )
        }
    }

    private val tuned: Worker = worker.also {
        val options = WorkflowImplementationOptions.newBuilder(base).setActivityOptions(mapOf("Fetch" to Limits.LONG)).build()
        it.registerWorkflowImplementationTypes(options, ByPropertyImpl::class.java)
    }

    constructor(worker: Worker) : this(worker, false) {
        worker.registerWorkflowImplementationTypes(base, ByConstructorImpl::class.java)
    }

    fun hand(worker: Worker, given: WorkflowImplementationOptions) {
        worker.registerWorkflowImplementationTypes(given.toBuilder().setDefaultActivityOptions(Limits.SHORT).build(), HandedImpl::class.java)
    }
}

object Hub {
    lateinit var worker: Worker
}

private val plain = WorkflowImplementationOptions.newBuilder().setDefaultActivityOptions(Limits.SHORT).build()

val ready: Boolean = Hub.worker.let {
    it.registerWorkflowImplementationTypes(plain, ByFileImpl::class.java)
    true
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
