package io.github.iw.durableatlas.analyser

/**
 * Workflows written with the Kotlin extensions of the Temporal SDK, each beside a twin that says
 * the same with the SDK's own functions: options as a lambda beside the builder, a call of a
 * stub that takes a type argument beside the one that is handed the class, a name asked for by
 * reference beside the name written out. A workflow and its twin are to read alike.
 *
 * Each pair is in a file of its own, the twin second. The tests name nodes by their lines in
 * these files, counted from the `package` line.
 */
internal object KotlinExtensionSources {
    private const val HEADER = """package extensions

import io.nexusrpc.Operation
import io.nexusrpc.Service
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.activity.copy
import io.temporal.activity.setRetryOptions
import io.temporal.api.enums.v1.ParentClosePolicy
import io.temporal.common.RetryOptions
import io.temporal.common.copy
import io.temporal.common.metadata.activityName
import io.temporal.common.metadata.workflowName
import io.temporal.common.metadata.workflowQueryType
import io.temporal.common.metadata.workflowSignalName
import io.temporal.failure.ActivityFailure
import io.temporal.worker.Worker
import io.temporal.worker.WorkflowImplementationOptions
import io.temporal.worker.registerWorkflowImplementationType
import io.temporal.worker.registerWorkflowImplementationTypes
import io.temporal.worker.setActivityOptions
import io.temporal.worker.setDefaultActivityOptions
import io.temporal.worker.setDefaultLocalActivityOptions
import io.temporal.worker.setLocalActivityOptions
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.ContinueAsNewOptions
import io.temporal.workflow.NexusOperationOptions
import io.temporal.workflow.NexusServiceOptions
import io.temporal.workflow.Promise
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.Saga
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import io.temporal.workflow.copy
import io.temporal.workflow.execute
import io.temporal.workflow.executeAsync
import io.temporal.workflow.setRetryOptions
import java.time.Duration
import java.util.Optional
"""

    /** The workflows written with the extensions, by the name of their file; the twin of each is called the same with `Twin` after it. */
    val WRITTEN: List<String> =
        listOf("Compensated", "Continued", "Greeted", "Named", "Optioned", "Ordered", "Parented", "Plain", "Retried", "Routed", "Stubbed", "Wired")

    /** File name to text. */
    val FILES: Map<String, String> = linkedMapOf(
        "Chores" to HEADER + """
@ActivityInterface
interface Chores {
    fun one(input: String): String

    fun two(input: String): String
}

@WorkflowInterface
interface Sub {
    @WorkflowMethod(name = "SubFlow")
    fun start(input: String): String

    @SignalMethod(name = "nudge")
    fun poke(input: String)

    @QueryMethod
    fun state(): String
}

@Service
interface Greeter {
    @Operation
    fun hello(name: String): String
}

/** Neither an activity interface nor a workflow interface: the SDK has no name for what it declares. */
interface Bare {
    fun run(input: String): String
}

val BASE: ActivityOptions = ActivityOptions.newBuilder().setTaskQueue("base").setStartToCloseTimeout(Duration.ofSeconds(5)).build()
""",
        // Options of activities, written as a lambda, and copies of them.
        "Optioned" to HEADER + """
@WorkflowInterface
interface Optioned {
    @WorkflowMethod fun run(input: String): String
}

class OptionedImpl : Optioned {
    private val plain = Workflow.newActivityStub(
        Chores::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(7))
            setTaskQueue("written")
            setRetryOptions { setMaximumAttempts(3) }
        },
    )
    private val copied = Workflow.newActivityStub(Chores::class.java, BASE.copy { setStartToCloseTimeout(Duration.ofSeconds(9)) })
    private val twice = Workflow.newActivityStub(
        Chores::class.java,
        ActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(1)) }.copy { setStartToCloseTimeout(Duration.ofSeconds(2)) },
    )
    private val local = Workflow.newLocalActivityStub(Chores::class.java, LocalActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(3)) })
    private val localCopy = Workflow.newLocalActivityStub(
        Chores::class.java,
        LocalActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(3)) }.copy { setScheduleToCloseTimeout(Duration.ofSeconds(8)) },
    )
    override fun run(input: String): String {
        plain.one(input)
        copied.one(input)
        twice.two(input)
        local.one(input)
        return localCopy.two(input)
    }
}

@WorkflowInterface
interface OptionedTwin {
    @WorkflowMethod fun run(input: String): String
}

class OptionedTwinImpl : OptionedTwin {
    private val plain = Workflow.newActivityStub(
        Chores::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(7))
            .setTaskQueue("written")
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build(),
    )
    private val copied = Workflow.newActivityStub(Chores::class.java, BASE.toBuilder().setStartToCloseTimeout(Duration.ofSeconds(9)).build())
    private val twice = Workflow.newActivityStub(
        Chores::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(1)).build().toBuilder().setStartToCloseTimeout(Duration.ofSeconds(2)).build(),
    )
    private val local = Workflow.newLocalActivityStub(Chores::class.java, LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(3)).build())
    private val localCopy = Workflow.newLocalActivityStub(
        Chores::class.java,
        LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(3)).build().toBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(8)).build(),
    )
    override fun run(input: String): String {
        plain.one(input)
        copied.one(input)
        twice.two(input)
        local.one(input)
        return localCopy.two(input)
    }
}
""",
        // What a lambda of options sets twice, and what it sets where it may not run. The twin hands the same lambdas to `apply`.
        "Ordered" to HEADER + """
@WorkflowInterface
interface Ordered {
    @WorkflowMethod fun run(fast: Boolean): String
}

class OrderedImpl : Ordered {
    override fun run(fast: Boolean): String {
        val last = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions {
                setStartToCloseTimeout(Duration.ofSeconds(1))
                setStartToCloseTimeout(Duration.ofSeconds(2))
            },
        )
        val either = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions {
                setTaskQueue("either")
                if (fast) setStartToCloseTimeout(Duration.ofSeconds(1)) else setStartToCloseTimeout(Duration.ofSeconds(30))
            },
        )
        val after = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions {
                if (fast) setStartToCloseTimeout(Duration.ofSeconds(1))
                setStartToCloseTimeout(Duration.ofSeconds(4))
            },
        )
        last.one("a")
        either.two("b")
        return after.one("c")
    }
}

@WorkflowInterface
interface OrderedTwin {
    @WorkflowMethod fun run(fast: Boolean): String
}

class OrderedTwinImpl : OrderedTwin {
    override fun run(fast: Boolean): String {
        val last = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions.newBuilder().apply {
                setStartToCloseTimeout(Duration.ofSeconds(1))
                setStartToCloseTimeout(Duration.ofSeconds(2))
            }.build(),
        )
        val either = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions.newBuilder().apply {
                setTaskQueue("either")
                if (fast) setStartToCloseTimeout(Duration.ofSeconds(1)) else setStartToCloseTimeout(Duration.ofSeconds(30))
            }.build(),
        )
        val after = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions.newBuilder().also {
                if (fast) it.setStartToCloseTimeout(Duration.ofSeconds(1))
                it.setStartToCloseTimeout(Duration.ofSeconds(4))
            }.build(),
        )
        last.one("a")
        either.two("b")
        return after.one("c")
    }
}
""",
        // A step among what a lambda of options is made of.
        "Routed" to HEADER + """
@WorkflowInterface
interface Routed {
    @WorkflowMethod fun run(input: String): String
}

class RoutedImpl : Routed {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val routed = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions {
                setStartToCloseTimeout(Duration.ofSeconds(4))
                setTaskQueue(chores.one(input))
            },
        )
        return routed.two(input)
    }
}

@WorkflowInterface
interface RoutedTwin {
    @WorkflowMethod fun run(input: String): String
}

class RoutedTwinImpl : RoutedTwin {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val routed = Workflow.newActivityStub(
            Chores::class.java,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(4))
                .setTaskQueue(chores.one(input))
                .build(),
        )
        return routed.two(input)
    }
}
""",
        // Options of child workflows.
        "Parented" to HEADER + """
@WorkflowInterface
interface Parented {
    @WorkflowMethod fun run(input: String): String
}

class ParentedImpl : Parented {
    override fun run(input: String): String {
        val child = Workflow.newChildWorkflowStub(
            Sub::class.java,
            ChildWorkflowOptions {
                setTaskQueue("children")
                setWorkflowExecutionTimeout(Duration.ofMinutes(10))
                setRetryOptions { setMaximumAttempts(2) }
            },
        )
        val detached = Workflow.newChildWorkflowStub(
            Sub::class.java,
            ChildWorkflowOptions { setTaskQueue("children") }.copy { setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON) },
        )
        Async.function(detached::start, input)
        Workflow.getWorkflowExecution(detached).get()
        return child.start(input)
    }
}

@WorkflowInterface
interface ParentedTwin {
    @WorkflowMethod fun run(input: String): String
}

class ParentedTwinImpl : ParentedTwin {
    override fun run(input: String): String {
        val child = Workflow.newChildWorkflowStub(
            Sub::class.java,
            ChildWorkflowOptions.newBuilder()
                .setTaskQueue("children")
                .setWorkflowExecutionTimeout(Duration.ofMinutes(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                .build(),
        )
        val detached = Workflow.newChildWorkflowStub(
            Sub::class.java,
            ChildWorkflowOptions.newBuilder().setTaskQueue("children").build().toBuilder().setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON).build(),
        )
        Async.function(detached::start, input)
        Workflow.getWorkflowExecution(detached).get()
        return child.start(input)
    }
}
""",
        // Options of a Nexus service and of its operations.
        "Greeted" to HEADER + """
@WorkflowInterface
interface Greeted {
    @WorkflowMethod fun run(input: String): String
}

class GreetedImpl : Greeted {
    private val greeter = Workflow.newNexusServiceStub(
        Greeter::class.java,
        NexusServiceOptions {
            setEndpoint("greeter")
            setOperationOptions(NexusOperationOptions { setScheduleToCloseTimeout(Duration.ofMinutes(1)) })
        },
    )
    private val slow = Workflow.newNexusServiceStub(
        Greeter::class.java,
        NexusServiceOptions { setEndpoint("greeter") }.copy {
            setOperationOptions(NexusOperationOptions { setScheduleToCloseTimeout(Duration.ofMinutes(1)) }.copy { setScheduleToCloseTimeout(Duration.ofMinutes(5)) })
        },
    )
    override fun run(input: String): String = slow.hello(greeter.hello(input))
}

@WorkflowInterface
interface GreetedTwin {
    @WorkflowMethod fun run(input: String): String
}

class GreetedTwinImpl : GreetedTwin {
    private val greeter = Workflow.newNexusServiceStub(
        Greeter::class.java,
        NexusServiceOptions.newBuilder()
            .setEndpoint("greeter")
            .setOperationOptions(NexusOperationOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(1)).build())
            .build(),
    )
    private val slow = Workflow.newNexusServiceStub(
        Greeter::class.java,
        NexusServiceOptions.newBuilder().setEndpoint("greeter").build().toBuilder()
            .setOperationOptions(
                NexusOperationOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(1)).build().toBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(5)).build(),
            )
            .build(),
    )
    override fun run(input: String): String = slow.hello(greeter.hello(input))
}
""",
        // Retry options, for the retries a workflow runs itself.
        "Retried" to HEADER + """
@WorkflowInterface
interface Retried {
    @WorkflowMethod fun run(input: String): String
}

class RetriedImpl : Retried {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    private val thrice = RetryOptions { setMaximumAttempts(3) }
    override fun run(input: String): String {
        Workflow.retry(thrice, Optional.of(Duration.ofMinutes(5))) { chores.one(input) }
        return Async.retry(thrice.copy { setMaximumAttempts(5) }, Optional.empty()) { Async.function(chores::two, "b") }.get()
    }
}

@WorkflowInterface
interface RetriedTwin {
    @WorkflowMethod fun run(input: String): String
}

class RetriedTwinImpl : RetriedTwin {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    private val thrice = RetryOptions.newBuilder().setMaximumAttempts(3).build()
    override fun run(input: String): String {
        Workflow.retry(thrice, Optional.of(Duration.ofMinutes(5))) { chores.one(input) }
        return Async.retry(thrice.toBuilder().setMaximumAttempts(5).build(), Optional.empty()) { Async.function(chores::two, "b") }.get()
    }
}
""",
        // A saga made with a lambda of options.
        "Compensated" to HEADER + """
@WorkflowInterface
interface Compensated {
    @WorkflowMethod fun run(input: String): String
}

class CompensatedImpl : Compensated {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val saga = Saga { setParallelCompensation(true) }
        try {
            saga.addCompensation { chores.one("undo first") }
            val first = chores.one(input)
            saga.addCompensation { chores.two("undo second") }
            return chores.two(first)
        } catch (e: ActivityFailure) {
            saga.compensate()
            throw e
        }
    }
}

@WorkflowInterface
interface CompensatedTwin {
    @WorkflowMethod fun run(input: String): String
}

class CompensatedTwinImpl : CompensatedTwin {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val saga = Saga(Saga.Options.Builder().setParallelCompensation(true).build())
        try {
            saga.addCompensation { chores.one("undo first") }
            val first = chores.one(input)
            saga.addCompensation { chores.two("undo second") }
            return chores.two(first)
        } catch (e: ActivityFailure) {
            saga.compensate()
            throw e
        }
    }
}
""",
        // A saga made with no options at all: the extension gives it those that say nothing.
        "Plain" to HEADER + """
@WorkflowInterface
interface Plain {
    @WorkflowMethod fun run(input: String): String
}

class PlainImpl : Plain {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val saga = Saga()
        try {
            saga.addCompensation { chores.one("undo first") }
            val first = chores.one(input)
            saga.addCompensation { chores.two("undo second") }
            return chores.two(first)
        } catch (e: ActivityFailure) {
            saga.compensate()
            throw e
        }
    }
}

@WorkflowInterface
interface PlainTwin {
    @WorkflowMethod fun run(input: String): String
}

class PlainTwinImpl : PlainTwin {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val saga = Saga(Saga.Options.Builder().build())
        try {
            saga.addCompensation { chores.one("undo first") }
            val first = chores.one(input)
            saga.addCompensation { chores.two("undo second") }
            return chores.two(first)
        } catch (e: ActivityFailure) {
            saga.compensate()
            throw e
        }
    }
}
""",
        // Options to continue as new with.
        "Continued" to HEADER + """
@WorkflowInterface
interface Continued {
    @WorkflowMethod fun run(input: String): String
}

class ContinuedImpl : Continued {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val next = chores.one(input)
        if (next.isEmpty()) return next
        val options = ContinueAsNewOptions { setTaskQueue("next") }
        if (next.length > 3) Workflow.continueAsNew(options.copy { setWorkflowRunTimeout(Duration.ofHours(1)) }, next)
        return Workflow.newContinueAsNewStub(Continued::class.java, options).run(next)
    }
}

@WorkflowInterface
interface ContinuedTwin {
    @WorkflowMethod fun run(input: String): String
}

class ContinuedTwinImpl : ContinuedTwin {
    private val chores = Workflow.newActivityStub(Chores::class.java, BASE)
    override fun run(input: String): String {
        val next = chores.one(input)
        if (next.isEmpty()) return next
        val options = ContinueAsNewOptions.newBuilder().setTaskQueue("next").build()
        if (next.length > 3) Workflow.continueAsNew(ContinueAsNewOptions.newBuilder(options).setWorkflowRunTimeout(Duration.ofHours(1)).build(), next)
        return Workflow.newContinueAsNewStub(ContinuedTwin::class.java, options).run(next)
    }
}
""",
        // The calls of stubs without an interface that take the type they give back as a type argument.
        "Stubbed" to HEADER + """
@WorkflowInterface
interface Stubbed {
    @WorkflowMethod fun run(input: String): String
}

class StubbedImpl : Stubbed {
    private val untyped = Workflow.newUntypedActivityStub(ActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(6)) })
    private val local = Workflow.newUntypedLocalActivityStub(LocalActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(2)) })
    override fun run(input: String): String {
        val first = untyped.execute<String>("Named", input)
        val one = untyped.executeAsync<String>("One", first)
        val two = untyped.executeAsync<String>("Two", first)
        Promise.allOf(one, two).get()
        untyped.executeAsync<String>("Forgotten", first)
        local.execute<Unit>("Nearby", first)
        val waited = Workflow.newUntypedChildWorkflowStub("Waited", ChildWorkflowOptions { setWorkflowRunTimeout(Duration.ofMinutes(3)) })
        val second = waited.execute<String>(first)
        val beside = Workflow.newUntypedChildWorkflowStub("Beside")
        val done = beside.executeAsync<String>(second)
        untyped.execute<String>("Meanwhile", second)
        return done.get()
    }
}

@WorkflowInterface
interface StubbedTwin {
    @WorkflowMethod fun run(input: String): String
}

class StubbedTwinImpl : StubbedTwin {
    private val untyped = Workflow.newUntypedActivityStub(ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(6)).build())
    private val local = Workflow.newUntypedLocalActivityStub(LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(2)).build())
    override fun run(input: String): String {
        val first = untyped.execute("Named", String::class.java, input)
        val one = untyped.executeAsync("One", String::class.java, first)
        val two = untyped.executeAsync("Two", String::class.java, first)
        Promise.allOf(one, two).get()
        untyped.executeAsync("Forgotten", String::class.java, first)
        local.execute("Nearby", Unit::class.java, first)
        val waited = Workflow.newUntypedChildWorkflowStub("Waited", ChildWorkflowOptions.newBuilder().setWorkflowRunTimeout(Duration.ofMinutes(3)).build())
        val second = waited.execute(String::class.java, first)
        val beside = Workflow.newUntypedChildWorkflowStub("Beside")
        val done = beside.executeAsync(String::class.java, second)
        untyped.execute("Meanwhile", String::class.java, second)
        return done.get()
    }
}
""",
        // The names of activities, workflows, signals and queries, asked for by reference.
        "Named" to HEADER + """
@WorkflowInterface
interface Named {
    @WorkflowMethod fun run(input: String): String
}

class NamedImpl : Named {
    private val untyped = Workflow.newUntypedActivityStub(BASE)
    override fun run(input: String): String {
        val first = untyped.execute<String>(activityName(Chores::one), input)
        val child = Workflow.newUntypedChildWorkflowStub(workflowName<Sub>())
        val second = child.execute<String>(first)
        val other = Workflow.newUntypedChildWorkflowStub(workflowName(Sub::class.java))
        other.execute<String>(second)
        Workflow.newUntypedExternalWorkflowStub("other").signal(workflowSignalName(Sub::poke), second)
        return untyped.execute<String>(workflowQueryType(Sub::state), second)
    }
}

@WorkflowInterface
interface NamedTwin {
    @WorkflowMethod fun run(input: String): String
}

class NamedTwinImpl : NamedTwin {
    private val untyped = Workflow.newUntypedActivityStub(BASE)
    override fun run(input: String): String {
        val first = untyped.execute("One", String::class.java, input)
        val child = Workflow.newUntypedChildWorkflowStub("SubFlow")
        val second = child.execute(String::class.java, first)
        val other = Workflow.newUntypedChildWorkflowStub("SubFlow")
        other.execute(String::class.java, second)
        Workflow.newUntypedExternalWorkflowStub("other").signal("nudge", second)
        return untyped.execute("state", String::class.java, second)
    }
}
""",
        // Names the SDK has none for: it fails where the workflow asks, and nothing is drawn that is not there.
        "Nameless" to HEADER + """
@WorkflowInterface
interface Nameless {
    @WorkflowMethod fun run(input: String): String
}

class NamelessImpl : Nameless {
    private val untyped = Workflow.newUntypedActivityStub(BASE)
    override fun run(input: String): String {
        val first = untyped.execute<String>(activityName(Bare::run), input)
        Workflow.newUntypedExternalWorkflowStub("other").signal(workflowSignalName(Sub::state), first)
        return untyped.execute<String>(workflowName<Bare>(), first)
    }
}
""",
        // What a worker gives the workflows it runs, written with the extensions and without.
        "Wired" to HEADER + """
@WorkflowInterface
interface Wired {
    @WorkflowMethod fun run()
}

class WiredImpl : Wired {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val local = Workflow.newLocalActivityStub(Chores::class.java)
    override fun run() {
        chores.one("the default the worker gives")
        chores.two("the options for this activity type")
        local.one("the default for local activities")
        local.two("the options for this local activity type")
    }
}

@WorkflowInterface
interface WiredTwin {
    @WorkflowMethod fun run()
}

class WiredTwinImpl : WiredTwin {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    private val local = Workflow.newLocalActivityStub(Chores::class.java)
    override fun run() {
        chores.one("the default the worker gives")
        chores.two("the options for this activity type")
        local.one("the default for local activities")
        local.two("the options for this local activity type")
    }
}

@WorkflowInterface
interface WiredByType {
    @WorkflowMethod fun run()
}

class WiredByTypeImpl : WiredByType {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    override fun run() {
        chores.one("registered by a type argument, with options")
    }
}

@WorkflowInterface
interface WiredBare {
    @WorkflowMethod fun run()
}

class WiredBareImpl : WiredBare {
    private val chores = Workflow.newActivityStub(Chores::class.java)
    override fun run() {
        chores.one("registered by a type argument, without options")
    }
}

class Wiring(worker: Worker) {
    init {
        worker.registerWorkflowImplementationTypes(WiredImpl::class.java) {
            setDefaultActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(12)) }
            setActivityOptions("Two" to ActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(13)) })
            setDefaultLocalActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(1)) }
            setLocalActivityOptions("Two" to LocalActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(3)) })
        }
        worker.registerWorkflowImplementationTypes(
            WorkflowImplementationOptions.newBuilder()
                .setDefaultActivityOptions(ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(12)).build())
                .setActivityOptions(mapOf("Two" to ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(13)).build()))
                .setDefaultLocalActivityOptions(LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(1)).build())
                .setLocalActivityOptions(mapOf("Two" to LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(3)).build()))
                .build(),
            WiredTwinImpl::class.java,
        )
        worker.registerWorkflowImplementationType<WiredByTypeImpl> {
            setDefaultActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(14)) }
            setDefaultActivityOptions { setStartToCloseTimeout(Duration.ofSeconds(15)) }
        }
        worker.registerWorkflowImplementationType<WiredBareImpl>()
    }
}
""",
    )
}
