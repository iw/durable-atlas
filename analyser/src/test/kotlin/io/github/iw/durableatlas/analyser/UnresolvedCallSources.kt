package io.github.iw.durableatlas.analyser

/**
 * Workflows written to try what becomes of calls that do not resolve, and the libraries they
 * call into.
 *
 * The Kotlin files are what a degraded model has of a repository: its `src/main/kotlin`, with
 * the Temporal SDK and the Kotlin standard library to resolve against and nothing else. The
 * Java files are what the build of the same repository adds to the classpath: a logging
 * library, a library of helpers, and the interfaces of an activity and a workflow that are
 * declared outside the Kotlin sources. Declared in Java, they are no code the analysis reads,
 * so with them the Kotlin files read as they would in a model made from the build.
 *
 * Each workflow is in a file of its own and as small as its case allows. The tests name nodes
 * by their lines in these files, counted from the `package` line.
 */
internal object UnresolvedCallSources {
    private const val HEADER = """package calls

import com.acme.jobs.JavaChild
import com.acme.jobs.JavaJobs
import com.acme.logging.KLogger
import com.acme.logging.KotlinLogging
import com.acme.util.Money
import com.acme.util.Retry
import com.acme.util.Stubs
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Async
import io.temporal.workflow.NewThing
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
"""

    /** The same without the imports one by one: the workflow API is imported as a whole. */
    private const val STARRED_HEADER = """package calls

import io.temporal.workflow.*
"""

    /** The files that hold no workflow of their own. */
    val SHARED: Set<String> = setOf("Chores", "LoggerExtensions")

    /** File name to text. */
    val FILES: Map<String, String> = linkedMapOf(
        "Chores" to HEADER + """
@ActivityInterface
interface Chores {
    fun one(input: String): String

    fun two(input: String): String
}

val OPTIONS: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
""",
        // A helper for logging, in a file of its own: everything it calls is the logging library's.
        "LoggerExtensions" to HEADER + """
private val log = KotlinLogging.logger("calls")

fun logStep(message: String) {
    log.info { message }
    log.atInfo().log(message)
}

fun KLogger.step(message: String) = debug { message }
""",
        // Calls into a logging library, directly and through the helper, around two activities.
        "Logged" to HEADER + """
@WorkflowInterface
interface Logged {
    @WorkflowMethod
    fun run(input: String)
}

class LoggedImpl : Logged {
    private val chores = Workflow.newActivityStub(Chores::class.java, OPTIONS)
    private val logger = KotlinLogging.logger("logged")

    override fun run(input: String) {
        logStep("starting")
        logger.step("first")
        chores.one(input)
        logger.info("done: " + chores.two(input))
    }
}
""",
        // Stubs of interfaces that are declared outside the Kotlin sources.
        "Foreign" to HEADER + """
@WorkflowInterface
interface Foreign {
    @WorkflowMethod
    fun run(input: String)
}

class ForeignImpl : Foreign {
    private val jobs = Workflow.newActivityStub(JavaJobs::class.java, OPTIONS)
    private val local: JavaJobs = Workflow.newLocalActivityStub(JavaJobs::class.java)
    private val child = Workflow.newChildWorkflowStub(JavaChild::class.java)

    override fun run(input: String) {
        jobs.charge(input)
        local.charge(input)
        Async.function(jobs::charge, input).get()
        with(jobs) { refund(input) }
        child.start(input)
        Workflow.newExternalWorkflowStub(JavaChild::class.java, "other").poke(input)
        Workflow.newContinueAsNewStub(JavaChild::class.java).start(input)
    }
}
""",
        // Code that holds steps, handed to calls into a library.
        "Handed" to HEADER + """
@WorkflowInterface
interface Handed {
    @WorkflowMethod
    fun run(input: String)
}

class HandedImpl : Handed {
    private val chores = Workflow.newActivityStub(Chores::class.java, OPTIONS)

    override fun run(input: String) {
        Retry.retrying(3) { chores.one(input) }
        val money = Money.of(2)
        money.each { chores.two(input) }
        money.text()
        tally(input)
    }
}
""",
        // A lambda that holds a step, handed to a function of collections on a collection of a type the model lacks.
        "Looped" to HEADER + """
@WorkflowInterface
interface Looped {
    @WorkflowMethod
    fun run(input: String)
}

class LoopedImpl : Looped {
    private val chores = Workflow.newActivityStub(Chores::class.java, OPTIONS)

    override fun run(input: String) {
        val money = Money.of(4)
        money.parts().forEach { part -> chores.one(part) }
        money.parts().map { it.length }
        money.parts().onEach { chores.two(it) }.size
    }
}
""",
        // Calls that name the Temporal API and that the SDK on the classpath does not have, or cannot settle.
        "Newer" to HEADER + """
@WorkflowInterface
interface Newer {
    @WorkflowMethod
    fun run(input: String)
}

class NewerImpl : Newer {
    private val chores = Workflow.newActivityStub(Chores::class.java, OPTIONS)

    override fun run(input: String) {
        Workflow.brandNew(input)
        NewThing.create(input).launch()
        Workflow.sleep(Money.of(3).pause())
        chores.one(input)
    }
}
""",
        // Functions of the sources that are called with something of a type the model lacks.
        "Priced" to HEADER + """
object Pricing {
    fun send(amount: Money, chores: Chores): String = chores.one(amount.text())

    fun pick(code: String, chores: Chores): String = chores.one(code)

    fun pick(code: Int, chores: Chores): String = chores.two(code.toString())

    fun note(code: String): String = code

    fun note(code: Int): String = code.toString()
}

@WorkflowInterface
interface Priced {
    @WorkflowMethod
    fun run(input: String)
}

class PricedImpl : Priced {
    private val chores = Workflow.newActivityStub(Chores::class.java, OPTIONS)

    override fun run(input: String) {
        val money = Money.of(1)
        Pricing.send(money, chores)
        Pricing.pick(money.text(), chores)
        Pricing.note(money.text())
    }
}
""",
        // A stub that cannot be followed to where it was made: the type it is declared with is all there is.
        "Served" to HEADER + """
class Desk(private val jobs: JavaJobs, private val log: KLogger) {
    fun serve(input: String) {
        log.info(input)
        jobs.charge(input)
    }
}

@WorkflowInterface
interface Served {
    @WorkflowMethod
    fun run(input: String)
}

class ServedImpl : Served {
    private val desks = listOf(Desk(Workflow.newActivityStub(JavaJobs::class.java, OPTIONS), KotlinLogging.logger("desk")))

    override fun run(input: String) {
        desks.first().serve(input)
    }
}
""",
        // A stub that a library makes, of an interface the sources declare.
        "Supplied" to HEADER + """
@WorkflowInterface
interface Supplied {
    @WorkflowMethod
    fun run(input: String)
}

class SuppliedImpl : Supplied {
    private val chores = Stubs.activity(Chores::class.java)

    override fun run(input: String) {
        chores.one(input)
        Stubs.activity(Chores::class.java).describe()
    }
}
""",
        // A name that nothing declares, where a package of the Temporal API is imported as a whole.
        "Starred" to STARRED_HEADER + """
@WorkflowInterface
interface Starred {
    @WorkflowMethod
    fun run(input: String)
}

class StarredImpl : Starred {
    override fun run(input: String) {
        somethingNew(input)
        Workflow.sleep(1000)
    }
}
""",
    )

    /** What the build of the repository adds to the classpath, as Java files: path below the source root to text. */
    val LIBRARY: Map<String, String> = linkedMapOf(
        "com/acme/logging/KLogger.java" to """
            package com.acme.logging;

            import java.util.function.Supplier;

            public interface KLogger {
                void info(String message);

                void info(Supplier<String> message);

                void debug(Supplier<String> message);

                KLogger atInfo();

                void log(String message);
            }
        """,
        "com/acme/logging/KotlinLogging.java" to """
            package com.acme.logging;

            public final class KotlinLogging {
                public static KLogger logger(String name) {
                    return null;
                }
            }
        """,
        "com/acme/jobs/JavaJobs.java" to """
            package com.acme.jobs;

            import io.temporal.activity.ActivityInterface;

            @ActivityInterface
            public interface JavaJobs {
                String charge(String input);

                String refund(String input);
            }
        """,
        "com/acme/jobs/JavaChild.java" to """
            package com.acme.jobs;

            import io.temporal.workflow.SignalMethod;
            import io.temporal.workflow.WorkflowInterface;
            import io.temporal.workflow.WorkflowMethod;

            @WorkflowInterface
            public interface JavaChild {
                @WorkflowMethod
                void start(String input);

                @SignalMethod
                void poke(String input);
            }
        """,
        "com/acme/util/Money.java" to """
            package com.acme.util;

            import java.time.Duration;
            import java.util.List;
            import java.util.function.Supplier;

            public final class Money {
                public static Money of(int amount) {
                    return new Money();
                }

                public String text() {
                    return "";
                }

                public Duration pause() {
                    return Duration.ZERO;
                }

                public <T> void each(Supplier<T> block) {
                    block.get();
                }

                public List<String> parts() {
                    return List.of();
                }
            }
        """,
        "com/acme/util/Retry.java" to """
            package com.acme.util;

            import java.util.function.Supplier;

            public final class Retry {
                public static <T> T retrying(int times, Supplier<T> block) {
                    return block.get();
                }
            }
        """,
        "com/acme/util/Stubs.java" to """
            package com.acme.util;

            public final class Stubs {
                public static <T> T activity(Class<T> type) {
                    return null;
                }
            }
        """,
    )
}
