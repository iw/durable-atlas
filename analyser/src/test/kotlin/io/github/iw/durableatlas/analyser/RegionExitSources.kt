package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try the last step of a region: whether it goes on to the end of the
 * region, leaves the region for good, or can do either. The last step of a loop is a branch with
 * an arm that leaves the loop, a step that is followed by a `break`, a `return` or a `throw`, a
 * step whose failure is caught, or an await that can time out; the same in what a flow does
 * beside a lane. Each is in a file of its own and as small as its case allows. The tests name
 * nodes by their lines in these files, counted from the `package` line.
 */
internal object RegionExitSources {
    private const val HEADER = """package exits

import io.temporal.activity.ActivityInterface
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Async
import io.temporal.workflow.SignalMethod
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
    fun cleanUp(id: String)
}
""",
        "BranchBreak" to """
@WorkflowInterface
interface BranchBreak {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class BranchBreakImpl(private val tasks: Tasks) : BranchBreak {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            if (id == "last") break
        }
        tasks.report("done")
    }
}
""",
        "BranchReturn" to """
@WorkflowInterface
interface BranchReturn {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class BranchReturnImpl(private val tasks: Tasks) : BranchReturn {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            if (id == "last") return
        }
        tasks.report("none")
    }
}
""",
        "BranchContinue" to """
@WorkflowInterface
interface BranchContinue {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class BranchContinueImpl(private val tasks: Tasks) : BranchContinue {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            if (id == "skip") continue
        }
        tasks.report("done")
    }
}
""",
        "WhenArms" to """
@WorkflowInterface
interface WhenArms {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class WhenArmsImpl(private val tasks: Tasks) : WhenArms {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            when (id) {
                "last" -> break
                "skip" -> continue
                "quit" -> return
                "work" -> tasks.work(id)
                else -> {}
            }
        }
        tasks.report("done")
    }
}
""",
        "NotifyBreak" to """
@WorkflowInterface
interface NotifyBreak {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class NotifyBreakImpl(private val tasks: Tasks) : NotifyBreak {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            if (id == "last") {
                tasks.report(id)
                break
            }
        }
        tasks.cleanUp("after")
    }
}
""",
        "StepReturn" to """
@WorkflowInterface
interface StepReturn {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class StepReturnImpl(private val tasks: Tasks) : StepReturn {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.work(id)
            return
        }
        tasks.report("none")
    }
}
""",
        "NotifyThrow" to """
@WorkflowInterface
interface NotifyThrow {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class NotifyThrowImpl(private val tasks: Tasks) : NotifyThrow {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            if (id == "bad") {
                tasks.report(id)
                throw IllegalStateException("bad")
            }
        }
    }
}
""",
        "CaughtFailure" to """
@WorkflowInterface
interface CaughtFailure {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class CaughtFailureImpl(private val tasks: Tasks) : CaughtFailure {
    override fun run(ids: List<String>) {
        for (id in ids) {
            try {
                tasks.work(id)
            } catch (e: ActivityFailure) {
            }
        }
        try {
            for (id in ids) {
                tasks.prepare(id)
            }
        } catch (e: ActivityFailure) {
            tasks.cleanUp("failed")
        }
    }
}
""",
        "AwaitTimeout" to """
@WorkflowInterface
interface AwaitTimeout {
    @WorkflowMethod
    fun run(ids: List<String>)

    @SignalMethod
    fun answer()
}

class AwaitTimeoutImpl(private val tasks: Tasks) : AwaitTimeout {
    private var answered = false

    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            if (!Workflow.await(Duration.ofMinutes(5)) { answered }) break
        }
        tasks.report("done")
    }

    override fun answer() {
        answered = true
    }
}
""",
        "LaneBreak" to """
@WorkflowInterface
interface LaneBreak {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class LaneBreakImpl(private val tasks: Tasks) : LaneBreak {
    override fun run(ids: List<String>) {
        for (id in ids) {
            val checked = Async.function { tasks.check(id) }
            tasks.prepare(id)
            if (id == "last") {
                tasks.report(id)
                break
            }
            checked.get()
        }
        tasks.cleanUp("after")
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
