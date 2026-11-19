package io.github.iw.durableatlas.analyser

/**
 * Workflows that throw in every way that matters. An exception that leaves a workflow fails its
 * execution only if it is a failure of Temporal or of a type a worker was told to fail workflows
 * on; any other fails the workflow task. Each workflow is in a file of its own; the tests name
 * nodes by their lines in these files, counted from the `package` line.
 */
internal object RealFailuresSources {
    private const val HEADER = """package failing

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Async
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.Saga
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.io.IOException
import java.time.Duration
"""

    /** The workflow types of these sources, in the order of their names. */
    val WORKFLOWS = listOf(
        "Cleaning", "Compensated", "Configured", "Cornered", "Erring", "Failing", "Guarded", "Handled", "Helped", "Laned", "Made", "Rethrown", "Thrown",
    )

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Jobs" to """
@ActivityInterface
interface Jobs {
    fun one(input: String): String
    fun two(input: String): String
}

val options: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
""",
        "Faults" to """
/** A domain exception like any other: no worker is told of it. */
open class DomainFault(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** One that a worker is told to fail workflows on, with the builder of the options. */
open class RejectedOrder(message: String) : RuntimeException(message)

/** One that extends it. */
class RejectedPayment(message: String) : RejectedOrder(message)

/** One that a worker is told of with the options written as a lambda. */
class StockFault(message: String) : RuntimeException(message)

/** One that a worker set up in Java is told of. */
class LedgerFault(message: String) : RuntimeException(message)

/** A checked exception of the domain. */
class ImportFault(message: String) : IOException(message)

/** One more that no worker is told of. */
class CodeFault(message: String) : RuntimeException(message)

/** Ordinary code without a step that throws in one way or another. */
object Checks {
    var lastFailure: String? = null

    fun positive(amount: Int): Int {
        if (amount <= 0) throw DomainFault("not positive")
        return amount
    }

    fun known(code: String): String {
        require(code.isNotBlank()) { "no code" }
        if (code.length > 8) throw CodeFault("too long")
        return code.trim().ifEmpty { error("blank code") }
    }

    fun allowed(customer: String): String {
        if (customer == "blocked") throw ApplicationFailure.newNonRetryableFailure("customer is blocked", "CustomerBlocked")
        return customer
    }

    fun inStock(item: String): String {
        if (item.isEmpty()) throw StockFault("nothing asked for")
        return item
    }

    fun payable(amount: Int): Int {
        if (amount > 1000) throw RejectedPayment("too much")
        if (amount < 0) throw DomainFault("negative")
        return amount
    }

    fun unsupported(what: String): Nothing = throw DomainFault("unsupported: " + what)

    fun refuse(what: String): Nothing = throw ApplicationFailure.newFailure("refused: " + what, "Refused")

    /** Catches whatever its own code throws, notes it and throws it again. */
    fun parsed(text: String): Int =
        try {
            positive(text.trim().toInt())
        } catch (e: Exception) {
            lastFailure = e.message
            throw e
        }

    fun imported(name: String): String {
        if (name.startsWith("/")) throw ImportFault("absolute path")
        return name
    }
}
""",
        "Guarded" to """
// The guards of the standard library are calls that return: nothing was drawn for them, and nothing is.
@WorkflowInterface
interface Guarded {
    @WorkflowMethod fun run(input: String?, count: Int): String
}

class GuardedImpl : Guarded {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val table = mapOf("a" to "b")

    override fun run(input: String?, count: Int): String {
        require(count >= 0) { "negative count" }
        val given = requireNotNull(input) { "nothing given" }
        check(given.isNotEmpty())
        val first = jobs.one(given)
        val looked = table[first]!!
        checkNotNull(table[looked]) { "no entry" }
        return jobs.two(looked)
    }
}
""",
        "Erring" to """
// Calls that never return, where a decision leads to them.
@WorkflowInterface
interface Erring {
    @WorkflowMethod fun run(input: String, count: Int): String
}

class ErringImpl : Erring {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val table = mapOf("a" to "b")

    override fun run(input: String, count: Int): String {
        val looked = table[input] ?: error("no entry")
        if (count > 10) error("too many")
        val first = jobs.one(looked)
        return when (count) {
            0 -> first
            1 -> jobs.two(first)
            2 -> TODO("two of them")
            else -> error("unexpected count")
        }
    }
}
""",
        "Thrown" to """
// Exceptions of the domain and of the standard library, thrown by the workflow method itself.
@WorkflowInterface
interface Thrown {
    @WorkflowMethod fun run(input: String, count: Int): String
}

class ThrownImpl : Thrown {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String, count: Int): String {
        if (input.isBlank()) throw DomainFault("nothing given")
        val first = jobs.one(input)
        when {
            count < 0 -> throw IllegalArgumentException("negative count")
            count == 0 -> jobs.two("none")
            count > 100 -> {
                jobs.two("too many")
                throw IllegalStateException("gave up")
            }
        }
        if (first.isEmpty()) throw RuntimeException("no answer") else jobs.two(first)
        for (index in 0 until count) {
            if (index > 50) throw DomainFault("more than fifty")
            jobs.one("round")
        }
        return first
    }
}
""",
        "Configured" to """
// Exceptions that a worker is told to fail workflows on, beside one it is not told of.
@WorkflowInterface
interface Configured {
    @WorkflowMethod fun run(input: String, count: Int): String
}

class ConfiguredImpl : Configured {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String, count: Int): String {
        if (input.isBlank()) throw RejectedOrder("nothing ordered")
        if (count > 5) throw RejectedPayment("too many")
        val first = jobs.one(input)
        if (first == "none") throw StockFault("out of stock")
        if (first == "unbooked") throw LedgerFault("not booked")
        if (first == "odd") throw DomainFault("odd answer")
        return jobs.two(first)
    }
}
""",
        "Failing" to """
// Failures of Temporal, made in the ways the SDK offers.
@WorkflowInterface
interface Failing {
    @WorkflowMethod fun run(input: String, count: Int): String
}

class FailingImpl : Failing {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String, count: Int): String {
        if (input.isBlank()) throw ApplicationFailure.newFailure("nothing given", "NothingGiven")
        if (count < 0) throw ApplicationFailure.newNonRetryableFailure("negative count", "NegativeCount")
        val first = jobs.one(input)
        if (first.isEmpty()) {
            val failure = ApplicationFailure.newBuilder().setMessage("no answer").setType("NoAnswer").setNonRetryable(true).build()
            throw failure
        }
        return jobs.two(first)
    }
}
""",
        "Compensated" to """
// A saga that catches whatever its steps raise, compensates and throws it again.
@WorkflowInterface
interface Compensated {
    @WorkflowMethod fun run(input: String): String
}

class CompensatedImpl : Compensated {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String): String {
        val saga = Saga(Saga.Options.Builder().build())
        try {
            val first = jobs.one(input)
            saga.addCompensation { jobs.two("undo") }
            return jobs.two(first)
        } catch (e: Exception) {
            saga.compensate()
            throw e
        }
    }
}
""",
        "Rethrown" to """
// Exceptions that are caught and thrown again, as they are, wrapped, or as another.
@WorkflowInterface
interface Rethrown {
    @WorkflowMethod fun run(input: String): String
}

class RethrownImpl : Rethrown {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String): String {
        val amount = try {
            Checks.positive(input.length)
        } catch (e: Exception) {
            Workflow.getLogger("rethrown").warn("not positive", e)
            throw e
        }
        val name = try {
            Checks.imported(input)
        } catch (e: IOException) {
            throw Workflow.wrap(e)
        }
        val first = try {
            jobs.one(name + amount)
        } catch (e: Exception) {
            throw Workflow.wrap(e)
        }
        val second = try {
            jobs.two(first)
        } catch (e: ActivityFailure) {
            throw DomainFault("two failed", e)
        }
        return try {
            jobs.one(second)
        } catch (e: ActivityFailure) {
            jobs.two("one failed")
            throw e
        }
    }
}
""",
        "Helped" to """
// Functions without a step that throw, called where nothing catches and where a clause does.
@WorkflowInterface
interface Helped {
    @WorkflowMethod fun run(input: String, amount: Int): String
}

class HelpedImpl : Helped {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String, amount: Int): String {
        val positive = Checks.positive(amount)
        val code = Checks.known(input)
        val parsed = Checks.parsed(input)
        val customer = Checks.allowed(code)
        val item = Checks.inStock(customer)
        val payable = Checks.payable(positive + parsed)
        val first = jobs.one(item + payable)
        val again = try {
            Checks.positive(first.length)
        } catch (e: DomainFault) {
            jobs.two("not positive")
            0
        }
        val known = try {
            Checks.known(first)
        } catch (e: CodeFault) {
            jobs.two("unknown")
            "none"
        }
        if (known == "none") Checks.unsupported(known)
        if (again > 100) Checks.refuse(known)
        return jobs.two(known)
    }
}
""",
        "Handled" to """
// Handlers that throw: signals, an update with its validator, another update, a query.
@WorkflowInterface
interface Handled {
    @WorkflowMethod fun run(input: String): String
    @SignalMethod fun cancel(reason: String)
    @SignalMethod fun adjust(amount: Int)
    @UpdateMethod fun change(amount: Int): Int
    @UpdateValidatorMethod(updateName = "change") fun checkChange(amount: Int)
    @UpdateMethod fun rename(name: String): String
    @QueryMethod fun total(): Int
}

class HandledImpl : Handled {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private var total = 0
    private var name = ""
    private var cancelled = false

    override fun run(input: String): String {
        Workflow.await { cancelled || total > 10 }
        return jobs.one(input + name)
    }

    override fun cancel(reason: String) {
        if (reason.isBlank()) throw ApplicationFailure.newFailure("no reason given", "NoReason")
        cancelled = true
    }

    override fun adjust(amount: Int) {
        if (amount == 0) throw IllegalArgumentException("nothing to adjust")
        if (amount > 1000) throw RejectedOrder("too much")
        total += Checks.positive(amount)
    }

    override fun change(amount: Int): Int {
        if (total + amount < 0) throw ApplicationFailure.newFailure("below zero", "BelowZero")
        if (amount == 0) throw IllegalStateException("nothing to change")
        total += amount
        jobs.two("changed")
        return total
    }

    override fun checkChange(amount: Int) {
        if (amount > 1000) throw IllegalArgumentException("too much at once")
        Checks.positive(amount)
    }

    override fun rename(name: String): String {
        this.name = Checks.known(name)
        return this.name
    }

    override fun total(): Int {
        if (cancelled) throw IllegalStateException("cancelled")
        return total
    }
}
""",
        "Laned" to """
// Code started beside the flow that throws, waited for and not.
@WorkflowInterface
interface Laned {
    @WorkflowMethod fun run(input: String): String
}

class LanedImpl : Laned {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String): String {
        val first = Async.function {
            if (input.isBlank()) throw DomainFault("nothing given")
            jobs.one(input)
        }
        val second = Async.function {
            if (input.length > 10) throw ApplicationFailure.newFailure("too long", "TooLong")
            jobs.two(input)
        }
        Async.procedure {
            if (input == "quiet") throw DomainFault("never seen")
            jobs.one("beside")
            jobs.two("beside")
        }
        return first.get() + second.get()
    }
}
""",
        "Made" to """
// What making the workflow object throws.
@WorkflowInterface
interface Made {
    @WorkflowMethod fun run(input: String): String
}

class MadeImpl : Made {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)
    private val limit: Int = System.getenv("LIMIT")?.toInt() ?: error("no limit set")
    private val region: String = System.getenv("REGION") ?: throw ApplicationFailure.newFailure("no region set", "NoRegion")

    init {
        if (limit > 100) throw DomainFault("limit too high")
    }

    override fun run(input: String): String = jobs.one(input + limit + region)
}
""",
        "Cleaning" to """
// What a `finally` runs on the way out of a failure.
@WorkflowInterface
interface Cleaning {
    @WorkflowMethod fun run(input: String): String
}

class CleaningImpl : Cleaning {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String): String {
        try {
            if (input.length > 10) throw DomainFault("too long")
            if (input.isBlank()) throw ApplicationFailure.newFailure("nothing given", "NothingGiven")
            jobs.one(input)
        } finally {
            jobs.two("done")
        }
        if (input == "stop") {
            try {
                throw DomainFault("stopped")
            } finally {
                jobs.two("stopped")
            }
        }
        return input
    }
}
""",
        "Cornered" to """
// Ways that end in a failure of the workflow task after something that is drawn.
@WorkflowInterface
interface Cornered {
    @WorkflowMethod fun run(input: String, count: Int): String
}

class CorneredImpl : Cornered {
    private val jobs = Workflow.newActivityStub(Jobs::class.java, options)

    override fun run(input: String, count: Int): String {
        if (count == 1) {
            jobs.one("one")
            if (input.isBlank()) throw DomainFault("blank") else throw IllegalStateException("not blank")
        }
        if (count == 2) {
            try {
                jobs.one("two")
                throw DomainFault("after a step")
            } catch (e: DomainFault) {
                throw IllegalStateException("as another", e)
            }
        }
        if (count == 3) {
            try {
                if (input.isBlank()) throw DomainFault("with nothing before it")
                jobs.one("three")
            } catch (e: DomainFault) {
                throw IllegalStateException("as another", e)
            }
        }
        if (count == 4) {
            jobs.one("four")
            if (input.isBlank()) {
                Checks.positive(count)
                throw DomainFault("after a helper")
            } else {
                throw IllegalStateException("not blank")
            }
        }
        jobs.two(input)
        TODO("the rest of it")
    }
}
""",
        "Workers" to """
// Where workers are told which exceptions fail a workflow, beside the failures of Temporal.
fun register(worker: io.temporal.worker.Worker) {
    val failing = io.temporal.worker.WorkflowImplementationOptions.newBuilder()
        .setFailWorkflowExceptionTypes(RejectedOrder::class.java)
        .build()
    worker.registerWorkflowImplementationTypes(failing, ConfiguredImpl::class.java)
}

class Wiring(worker: io.temporal.worker.Worker) {
    init {
        worker.registerWorkflowImplementationTypes(
            io.temporal.worker.WorkflowImplementationOptions { setFailWorkflowExceptionTypes(StockFault::class.java) },
            HelpedImpl::class.java,
        )
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }

    /** A worker set up in Java, which is told an exception that fails its workflows. */
    const val JAVA_WORKERS = """
        package failing;

        import io.temporal.worker.Worker;
        import io.temporal.worker.WorkflowImplementationOptions;

        public final class JavaWorkers {
            private JavaWorkers() {}

            public static void register(Worker worker) {
                WorkflowImplementationOptions options =
                    WorkflowImplementationOptions.newBuilder().setFailWorkflowExceptionTypes(LedgerFault.class).build();
                worker.registerWorkflowImplementationTypes(options, ConfiguredImpl.class);
            }
        }
    """

    /** A worker that is told its failing exceptions through a value: which they are cannot be read. */
    const val UNREADABLE_WORKER = """
        package failing

        import io.temporal.worker.Worker
        import io.temporal.worker.WorkflowImplementationOptions

        fun registerConfigured(worker: Worker, failOn: Array<Class<out Throwable>>) {
            val configured = WorkflowImplementationOptions.newBuilder()
                .setFailWorkflowExceptionTypes(*failOn)
                .build()
            worker.registerWorkflowImplementationTypes(configured, ThrownImpl::class.java)
        }
    """

    /** A workflow registered the way the SDK keeps for tests: by a factory and without options, which fails it on whatever is thrown. */
    const val FACTORY_WORKER = """
        package failing

        import io.temporal.worker.Worker

        fun registerFactory(worker: Worker) {
            worker.addWorkflowImplementationFactory(Thrown::class.java) { ThrownImpl() }
        }
    """

    /** The same in Java. */
    const val JAVA_FACTORY_WORKER = """
        package failing;

        import io.temporal.worker.Worker;

        public final class JavaFactories {
            private JavaFactories() {}

            public static void register(Worker worker) {
                worker.addWorkflowImplementationFactory(Thrown.class, () -> new ThrownImpl());
            }
        }
    """

    /** A factory that is registered with options says nothing by itself. */
    const val OPTIONED_FACTORY_WORKER = """
        package failing

        import io.temporal.worker.Worker
        import io.temporal.worker.WorkflowImplementationOptions

        fun registerFactory(worker: Worker) {
            worker.registerWorkflowImplementationFactory(Thrown::class.java, { ThrownImpl() }, WorkflowImplementationOptions.newBuilder().build())
            worker.registerWorkflowImplementationFactory(Thrown::class.java) { ThrownImpl() }
            worker.addWorkflowImplementationFactory(WorkflowImplementationOptions.newBuilder().build(), Thrown::class.java) { ThrownImpl() }
        }
    """

    /** A worker that is told to fail a workflow on whatever is thrown. */
    const val STRICT_WORKER = """
        package failing

        import io.temporal.worker.WorkflowImplementationOptions

        fun strict(): WorkflowImplementationOptions =
            WorkflowImplementationOptions.newBuilder().setFailWorkflowExceptionTypes(Throwable::class.java).build()
    """
}
