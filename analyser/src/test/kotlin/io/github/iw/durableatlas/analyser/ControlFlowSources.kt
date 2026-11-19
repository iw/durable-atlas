package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try the ways control flow is drawn that the sample project does not
 * contain, each in a file of its own and as small as its case allows. The tests name nodes by
 * their lines in these files, counted from the `package` line.
 */
internal object ControlFlowSources {
    private const val HEADER = """package flow

import io.temporal.activity.ActivityInterface
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Async
import io.temporal.workflow.Saga
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
        "LoopExits" to """
@WorkflowInterface
interface LoopExits {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class LoopExitsImpl(private val tasks: Tasks) : LoopExits {
    override fun run(ids: List<String>) {
        for (id in ids) {
            if (id.isEmpty()) continue
            if (!tasks.check(id)) break
            tasks.work(id)
        }
        while (true) {
            val next = tasks.prepare("next")
            if (next == "stop") break
            if (next == "abort") return
            tasks.work(next)
        }
        tasks.report("done")
    }
}
""",
        "Jumps" to """
@WorkflowInterface
interface Jumps {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class JumpsImpl(private val tasks: Tasks) : Jumps {
    override fun run(ids: List<String>) {
        for (id in ids) {
            try {
                if (id.isEmpty()) continue
                if (id == "last") break
                tasks.work(id)
            } finally {
                tasks.cleanUp(id)
            }
        }
        outer@ for (first in ids) {
            for (second in ids) {
                if (first == second) break@outer
                tasks.check(second)
            }
        }
        tasks.report("done")
    }
}
""",
        "NestedTry" to """
@WorkflowInterface
interface NestedTry {
    @WorkflowMethod
    fun run(id: String): String
}

class NestedTryImpl(private val tasks: Tasks) : NestedTry {
    override fun run(id: String): String {
        try {
            tasks.prepare(id)
            try {
                tasks.work(id)
                if (id == "bad") throw IllegalStateException("bad id")
            } finally {
                tasks.cleanUp(id)
            }
            tasks.report(id)
        } catch (e: ActivityFailure) {
            tasks.undo(id)
        } catch (e: IllegalStateException) {
            return "rejected"
        }
        return "done"
    }
}
""",
        "Swallow" to """
@WorkflowInterface
interface Swallow {
    @WorkflowMethod
    fun run(id: String)
}

class SwallowImpl(private val tasks: Tasks) : Swallow {
    override fun run(id: String) {
        try {
            tasks.work(id)
        } catch (e: Exception) {
            // Carry on regardless.
        }
        try {
            require(id.isNotEmpty())
        } catch (e: IllegalArgumentException) {
            tasks.report(id)
        }
        tasks.cleanUp(id)
    }
}
""",
        "FanOut" to """
@WorkflowInterface
interface FanOut {
    @WorkflowMethod
    fun run(id: String, urgent: Boolean)
}

class FanOutImpl(private val tasks: Tasks) : FanOut {
    override fun run(id: String, urgent: Boolean) {
        val prepared = Async.function { tasks.prepare(id) }
        tasks.check(id)
        prepared.get()
        Async.procedure { tasks.report(id) }
        tasks.work(id)
        if (urgent) {
            val cleaning = Async.procedure { tasks.cleanUp(id) }
            cleaning.get()
        }
    }
}
""",
        "Lanes" to """
@WorkflowInterface
interface Lanes {
    @WorkflowMethod
    fun run(id: String)
}

class LanesImpl(private val tasks: Tasks) : Lanes {
    override fun run(id: String) {
        val first = Async.function {
            try {
                tasks.work(id)
                "done"
            } catch (e: ActivityFailure) {
                tasks.undo(id)
                "undone"
            }
        }
        if (id.isEmpty()) return
        val second = Async.function { tasks.prepare(id) }
        while (!tasks.check(id)) {
            Workflow.sleep(Duration.ofSeconds(1))
        }
        io.temporal.workflow.Promise.anyOf(first, second).get()
        tasks.report(id)
    }
}
""",
        "Versions" to """
@WorkflowInterface
interface Versions {
    @WorkflowMethod
    fun run(id: String)
}

class VersionsImpl(private val tasks: Tasks) : Versions {
    override fun run(id: String) {
        when (Workflow.getVersion("pricing", Workflow.DEFAULT_VERSION, 2)) {
            Workflow.DEFAULT_VERSION -> tasks.prepare(id)
            1 -> tasks.check(id)
            else -> tasks.work(id)
        }
        if (Workflow.getVersion("reporting", 1, 3) >= 2) {
            tasks.report(id)
        }
        val audit = Workflow.getVersion("audit", Workflow.DEFAULT_VERSION, 1)
        tasks.cleanUp(id)
        if (audit == 1) tasks.undo(id)
    }
}
""",
        "Awaits" to """
@WorkflowInterface
interface Awaits {
    @WorkflowMethod
    fun run(id: String): String
}

class AwaitsImpl(private val tasks: Tasks) : Awaits {
    private var approved = false
    private var rejected = false

    override fun run(id: String): String {
        Workflow.await { approved || rejected }
        if (rejected) return "rejected"
        tasks.work(id)
        val inTime = Workflow.await(Duration.ofHours(1)) { !approved }
        when {
            !inTime -> tasks.report(id)
            else -> tasks.cleanUp(id)
        }
        Workflow.await(Duration.ofMinutes(5)) { rejected }
        approved = false
        if (approved) tasks.undo(id)
        return "done"
    }
}
""",
        "DeadArms" to """
@WorkflowInterface
interface DeadArms {
    @WorkflowMethod
    fun run(id: String)
}

class DeadArmsImpl(private val tasks: Tasks) : DeadArms {
    private var paid = false
    private var cancelled = false

    override fun run(id: String) {
        Workflow.await { paid || cancelled }
        if (paid && cancelled) tasks.undo(id)
        when (Workflow.getVersion("layout", 1, 2)) {
            1 -> tasks.work(id)
            2 -> tasks.report(id)
            else -> throw IllegalStateException("unsupported version")
        }
    }
}
""",
        "Elvis" to """
@WorkflowInterface
interface Elvis {
    @WorkflowMethod
    fun run(id: String?): String
}

class ElvisImpl(private val tasks: Tasks) : Elvis {
    override fun run(id: String?): String {
        val known = id ?: throw ApplicationFailure.newFailure("no id", "MissingId")
        val prepared = tasks.prepare(known).takeIf { it.isNotEmpty() } ?: return "nothing"
        return prepared
    }
}
""",
        "Sagas" to """
@WorkflowInterface
interface Sagas {
    @WorkflowMethod
    fun run(ids: List<String>, careful: Boolean)
}

class SagasImpl(private val tasks: Tasks) : Sagas {
    override fun run(ids: List<String>, careful: Boolean) {
        val saga = Saga(Saga.Options.Builder().build())
        val other = Saga(Saga.Options.Builder().build())
        try {
            for (id in ids) {
                tasks.work(id)
                saga.addCompensation { tasks.undo(id) }
            }
            if (careful) {
                saga.addCompensation(tasks::cleanUp, "all")
            }
            tasks.report("done")
        } catch (e: ActivityFailure) {
            saga.compensate()
            Workflow.newDetachedCancellationScope(Runnable { other.compensate() }).run()
        }
    }
}
""",
        "Collections" to """
@WorkflowInterface
interface Collections {
    @WorkflowMethod
    fun run(ids: List<String>)
}

class CollectionsImpl(private val tasks: Tasks) : Collections {
    override fun run(ids: List<String>) {
        ids.forEach(tasks::work)
        ids.forEachIndexed { index, id -> if (index > 0) tasks.report(id) }
        ids.filter { tasks.check(it) }
        repeat(3) { tasks.cleanUp("attempt") }
        ids.forEach { id ->
            if (id.isEmpty()) return@forEach
            tasks.undo(id)
        }
        val cache = HashMap<String, String>()
        cache.getOrPut("key") { tasks.prepare("key") }
    }
}
""",
        "Scopes" to """
@WorkflowInterface
interface Scopes {
    @WorkflowMethod
    fun run(id: String?)
}

class ScopesImpl(private val tasks: Tasks) : Scopes {
    override fun run(id: String?) {
        Workflow.newCancellationScope { _ -> tasks.work("scoped") }.run()
        id?.let {
            if (it.isEmpty()) return@let
            tasks.report(it)
        }
        tasks.cleanUp("end")
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
