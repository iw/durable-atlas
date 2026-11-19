package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try the jumps that end at the end of a region further out than the one
 * they are made in: a `continue` that names an outer loop, a `break` of a loop that is the last
 * thing in the round of the loop around it, and the same in the lanes of a parallel region and in
 * a compensation. Each is in a file of its own and as small as its case allows. The tests name
 * nodes by their lines in these files, counted from the `package` line.
 */
internal object RegionEndSources {
    private const val HEADER = """package ends

import io.temporal.activity.ActivityInterface
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Async
import io.temporal.workflow.Saga
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
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
        "OuterContinue" to """
@WorkflowInterface
interface OuterContinue {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class OuterContinueImpl(private val tasks: Tasks) : OuterContinue {
    override fun run(ids: List<String>) {
        outer@ for (id in ids) {
            tasks.prepare(id)
            for (part in id.split("-")) {
                if (part.isEmpty()) continue@outer
                tasks.work(part)
            }
            tasks.report(id)
        }
    }
}
""",
        "LastLoopBreak" to """
@WorkflowInterface
interface LastLoopBreak {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class LastLoopBreakImpl(private val tasks: Tasks) : LastLoopBreak {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            inner@ for (part in id.split("-")) {
                if (part == "x") break
                if (part == "y") break@inner
                tasks.work(part)
            }
        }
        for (id in ids) {
            while (tasks.check(id)) {
                tasks.report(id)
                break
            }
        }
    }
}
""",
        "ThreeDeep" to """
@WorkflowInterface
interface ThreeDeep {
    @WorkflowMethod
    fun run(batches: List<List<String>>)
}

class ThreeDeepImpl(private val tasks: Tasks) : ThreeDeep {
    override fun run(batches: List<List<String>>) {
        for (batch in batches) {
            tasks.prepare("batch")
            middle@ for (id in batch) {
                for (part in id.split("-")) {
                    if (part == "skip") continue@middle
                    if (part == "stop") break@middle
                    if (part == "next") break
                    tasks.work(part)
                }
            }
        }
    }
}
""",
        "LoopKinds" to """
@WorkflowInterface
interface LoopKinds {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class LoopKindsImpl(private val tasks: Tasks) : LoopKinds {
    override fun run(ids: List<String>) {
        var round = 0
        outer@ while (round < ids.size) {
            round++
            do {
                if (tasks.check("again")) continue@outer
                tasks.work("again")
            } while (round < 3)
        }
        repeat(3) {
            ids.forEach { id ->
                if (id.isEmpty()) return@repeat
                tasks.report(id)
            }
        }
        ids.forEach { id ->
            for (part in id.split("-")) {
                if (part.isEmpty()) return@forEach
                tasks.cleanUp(part)
            }
        }
        ids.forEach each@{ id ->
            id.split("-").forEach { part ->
                if (part.isEmpty()) return@each
                tasks.undo(part)
            }
        }
    }
}
""",
        "LaneEnds" to """
@WorkflowInterface
interface LaneEnds {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class LaneEndsImpl(private val tasks: Tasks) : LaneEnds {
    override fun run(ids: List<String>) {
        val saga = Saga(Saga.Options.Builder().build())
        val lane = Async.procedure {
            for (id in ids) {
                if (id == "stop") break
                tasks.work(id)
            }
        }
        tasks.prepare("beside")
        lane.get()
        for (id in ids) {
            val checked = Async.function { tasks.check(id) }
            if (id.isEmpty()) continue
            tasks.report(id)
            checked.get()
        }
        saga.addCompensation {
            for (id in ids) {
                if (id == "kept") break
                tasks.undo(id)
            }
        }
        saga.compensate()
    }
}
""",
        "ThroughFinally" to """
@WorkflowInterface
interface ThroughFinally {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class ThroughFinallyImpl(private val tasks: Tasks) : ThroughFinally {
    override fun run(ids: List<String>) {
        outer@ for (id in ids) {
            for (part in id.split("-")) {
                try {
                    if (part.isEmpty()) continue@outer
                    tasks.work(part)
                } finally {
                    tasks.cleanUp(part)
                }
            }
        }
        for (id in ids) {
            try {
                for (part in id.split("-")) {
                    tasks.report(part)
                }
            } catch (e: ActivityFailure) {
            }
        }
    }
}
""",
        "InHelper" to """
@WorkflowInterface
interface InHelper {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class InHelperImpl(private val tasks: Tasks) : InHelper {
    override fun run(ids: List<String>) {
        for (id in ids) {
            tasks.prepare(id)
            parts(id)
        }
        each(ids) { id ->
            for (part in id.split("-")) {
                if (part.isEmpty()) return@each
                tasks.report(part)
            }
        }
    }

    private fun parts(id: String) {
        for (part in id.split("-")) {
            if (part == "x") return
            tasks.work(part)
        }
    }

    private fun each(ids: List<String>, block: (String) -> Unit) {
        for (id in ids) block(id)
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
