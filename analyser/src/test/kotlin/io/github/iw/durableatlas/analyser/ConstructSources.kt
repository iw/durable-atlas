package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try what a workflow does outside its workflow method and what the SDK
 * runs for it in ways of its own: steps taken while the workflow object is made, stubs made
 * there, a class that runs for every workflow type, handlers registered while the workflow runs,
 * a test of what an await waited for, a saga that compensates side by side, and a retry. Each is
 * in a file of its own and as small as its case allows. The tests name nodes by their lines in
 * these files, counted from the `package` line.
 */
internal object ConstructSources {
    private const val HEADER = """package constructs

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.common.converter.EncodedValues
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.DynamicQueryHandler
import io.temporal.workflow.DynamicSignalHandler
import io.temporal.workflow.DynamicUpdateHandler
import io.temporal.workflow.DynamicWorkflow
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.Saga
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInit
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import java.util.Optional
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
    fun release(id: String)
}
""",
        // A step in the initialiser of a property, in an `init` block and in the constructor that the SDK calls.
        "InitSteps" to """
@WorkflowInterface
interface InitSteps {
    @WorkflowMethod
    fun run(id: String)
}

class InitStepsImpl : InitSteps {
    private val tasks = Workflow.newActivityStub(Tasks::class.java)
    private val prepared: String = tasks.prepare("first")
    private val checked: Boolean

    init {
        checked = tasks.check(prepared)
    }

    @WorkflowInit
    constructor(id: String) {
        if (!checked) return
        tasks.report(id)
    }

    override fun run(id: String) {
        tasks.work(id)
    }
}
""",
        // A class without a constructor of its own, made after its superclass.
        "BaseSteps" to """
@WorkflowInterface
interface BaseSteps {
    @WorkflowMethod
    fun run(id: String)
}

open class Prepared(note: String) {
    protected val tasks = Workflow.newActivityStub(Tasks::class.java)

    init {
        tasks.prepare(note)
    }
}

class BaseStepsImpl : Prepared("base"), BaseSteps {
    private val later by lazy { tasks.check("later") }

    init {
        Workflow.sleep(Duration.ofSeconds(1))
    }

    override fun run(id: String) {
        tasks.work(id)
    }
}
""",
        // Nothing says which constructor makes the object.
        "TwoConstructors" to """
@WorkflowInterface
interface TwoConstructors {
    @WorkflowMethod
    fun run(id: String)
}

class TwoConstructorsImpl : TwoConstructors {
    private val tasks = Workflow.newActivityStub(Tasks::class.java)

    constructor(first: String) {
        tasks.prepare(first)
    }

    constructor(first: String, second: String) {
        println(first + second)
    }

    override fun run(id: String) {
        tasks.work(id)
    }
}
""",
        // Stubs that get their options where the object is made, not where they are declared.
        "InitStubs" to """
@WorkflowInterface
interface InitStubs {
    @WorkflowMethod
    fun run(id: String)
}

class InitStubsImpl : InitStubs {
    private val quick: Tasks
    private val slow: Tasks

    init {
        val options = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(20)).build()
        quick = Workflow.newActivityStub(Tasks::class.java, options)
    }

    constructor() {
        val patient = ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(5)).setTaskQueue("slow").build()
        this.slow = Workflow.newActivityStub(Tasks::class.java, patient)
    }

    override fun run(id: String) {
        quick.prepare(id)
        slow.work(id)
    }
}
""",
        // A class that runs for every workflow type without an implementation of its own.
        "Fallback" to """
class Fallback : DynamicWorkflow {
    private var note: String? = null

    override fun execute(args: EncodedValues): Any {
        Workflow.registerListener(DynamicSignalHandler { _, values -> note = values.get(0, String::class.java) })
        Workflow.registerListener(DynamicQueryHandler { _, _ -> note })
        val tasks = Workflow.newUntypedActivityStub(
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build(),
        )
        Workflow.await { note != null }
        return tasks.execute("Handle", String::class.java, Workflow.getInfo().workflowType, note)
    }
}
""",
        // Handlers registered while the workflow runs: those of an object, one for every update, and one that cannot be read.
        "Listeners" to """
@WorkflowInterface
interface Listeners {
    @WorkflowMethod
    fun run(id: String)
}

@WorkflowInterface
interface Control {
    @SignalMethod
    fun pause()

    @QueryMethod
    fun isPaused(): Boolean

    @UpdateMethod
    fun resume(note: String): String

    @UpdateValidatorMethod(updateName = "resume")
    fun checkResume(note: String)
}

class ControlPanel(private val tasks: Tasks) : Control {
    var paused = false

    override fun pause() {
        paused = true
    }

    override fun isPaused(): Boolean = paused

    override fun resume(note: String): String {
        paused = false
        return tasks.prepare(note)
    }

    override fun checkResume(note: String) {
        require(note.isNotEmpty())
    }
}

class ListenersImpl(private val tasks: Tasks) : Listeners {
    private val panel = ControlPanel(tasks)
    private var extra = 0

    override fun run(id: String) {
        Workflow.registerListener(panel)
        Workflow.registerListener(
            DynamicUpdateHandler { name, _ ->
                extra++
                tasks.report(name)
            },
        )
        Workflow.registerListener(object : DynamicQueryHandler {
            override fun handle(queryType: String, args: EncodedValues): Any = extra
        })
        Workflow.await { !panel.paused }
        tasks.work(id)
    }
}
""",
        // The test after the await asks for one reason and against the other: no outcome of the await says that.
        "QueueLoop" to """
@WorkflowInterface
interface QueueLoop {
    @WorkflowMethod
    fun run(): Int

    @SignalMethod
    fun add(id: String)

    @SignalMethod
    fun finish()
}

class QueueLoopImpl(private val tasks: Tasks) : QueueLoop {
    private val queue = ArrayList<String>()
    private var done = false

    override fun run(): Int {
        var handled = 0
        while (true) {
            Workflow.await { !queue.isEmpty() || done }
            if (queue.isEmpty() && done) return handled
            tasks.work(queue.removeAt(0))
            handled++
        }
    }

    override fun add(id: String) {
        queue.add(id)
    }

    override fun finish() {
        done = true
    }
}
""",
        // The test asks for one reason alone: where both hold, that one wins, and the other is what is left.
        "FirstWins" to """
@WorkflowInterface
interface FirstWins {
    @WorkflowMethod
    fun run(id: String)

    @SignalMethod
    fun approve()

    @SignalMethod
    fun stop()
}

class FirstWinsImpl(private val tasks: Tasks) : FirstWins {
    private var approved = false
    private var stopped = false

    override fun run(id: String) {
        val inTime = Workflow.await(Duration.ofHours(1)) { approved || stopped }
        if (!inTime) return
        if (stopped) {
            tasks.cleanUp(id)
        } else {
            tasks.work(id)
        }
    }

    override fun approve() {
        approved = true
    }

    override fun stop() {
        stopped = true
    }
}
""",
        // The same in a `when`: the arm that is tested for with a reason and against another is called what it tests.
        "WhenReasons" to """
@WorkflowInterface
interface WhenReasons {
    @WorkflowMethod
    fun run(id: String)

    @SignalMethod
    fun approve()

    @SignalMethod
    fun stop()
}

class WhenReasonsImpl(private val tasks: Tasks) : WhenReasons {
    private var approved = false
    private var stopped = false

    override fun run(id: String) {
        Workflow.await { approved || stopped }
        when {
            stopped && !approved -> tasks.cleanUp(id)
            else -> tasks.work(id)
        }
    }

    override fun approve() {
        approved = true
    }

    override fun stop() {
        stopped = true
    }
}
""",
        // A saga made to compensate side by side, one made to compensate in turn, and one that does not say.
        "SideBySide" to """
@WorkflowInterface
interface SideBySide {
    @WorkflowMethod
    fun run(id: String)
}

class SideBySideImpl(private val tasks: Tasks) : SideBySide {
    override fun run(id: String) {
        val saga = Saga(Saga.Options.Builder().setParallelCompensation(true).build())
        try {
            saga.addCompensation { tasks.cleanUp("first") }
            tasks.prepare(id)
            saga.addCompensation {
                tasks.report("second")
                tasks.undo("second")
            }
            tasks.work(id)
            saga.addCompensation(tasks::release, "third")
            tasks.check(id)
        } catch (e: ActivityFailure) {
            saga.compensate()
            throw e
        }
    }
}
""",
        "InTurn" to """
@WorkflowInterface
interface InTurn {
    @WorkflowMethod
    fun run(id: String, together: Boolean)
}

class InTurnImpl(private val tasks: Tasks) : InTurn {
    override fun run(id: String, together: Boolean) {
        val plain = Saga(Saga.Options.Builder().setParallelCompensation(false).build())
        val unsaid = Saga(Saga.Options.Builder().setParallelCompensation(together).build())
        try {
            plain.addCompensation { tasks.cleanUp("first") }
            unsaid.addCompensation { tasks.report("first") }
            tasks.prepare(id)
            plain.addCompensation { tasks.cleanUp("second") }
            unsaid.addCompensation { tasks.report("second") }
            tasks.work(id)
        } catch (e: ActivityFailure) {
            plain.compensate()
            unsaid.compensate()
        }
    }
}
""",
        // What is retried, with and without a value, and with and without an end to it.
        "Retries" to """
@WorkflowInterface
interface Retries {
    @WorkflowMethod
    fun run(id: String)
}

class RetriesImpl(private val tasks: Tasks) : Retries {
    override fun run(id: String) {
        val options = RetryOptions.newBuilder().setMaximumAttempts(3).build()
        Workflow.retry(options, Optional.of(Duration.ofMinutes(10))) {
            tasks.prepare(id)
            tasks.work(id)
        }
        val fine = Workflow.retry(RetryOptions.newBuilder().build(), Optional.empty()) { tasks.check(id) }
        if (fine) tasks.report(id)
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
