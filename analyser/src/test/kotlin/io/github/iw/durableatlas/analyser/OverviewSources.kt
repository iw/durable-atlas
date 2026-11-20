package io.github.iw.durableatlas.analyser

/**
 * Workflows of a shop whose steps stand among calls the analysis cannot read: blocks handed to
 * the functions of a library that is not there (`support`: a request context, a tracer, a retry
 * helper), and helpers of the sources that call interfaces no class implements (a mapper, a
 * repository, a pricing client). An overview leaves those calls out; the calls behind which a
 * step is certain stay.
 *
 * The first files are shared. Then comes one small workflow for each rule, and at the end four
 * larger ones that mix them as a service does. Each workflow is in a file of its own; the tests
 * name nodes by their lines in these files, counted from the `package` line.
 */
internal object OverviewSources {
    private const val HEADER = """package shop

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Saga
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import support.Locks
import support.RemoteSteps
import support.RequestContext
import support.Retrier
import support.Tracer
import support.either
import support.retrying
import support.traced
import support.withRequestContext
"""

    /** The files that hold no workflow. */
    val SHARED = setOf("Steps", "Ports", "Helper", "Notifiers", "Deep")

    /** The small workflows, one for each rule, in the order of their names. */
    val RULES = listOf(
        "Blocked", "Caught", "Chained", "Cleaned", "Decided", "Delegated", "Direct", "Emptied", "Forever", "Handed", "Helped", "Kept",
        "Lanes", "Looped", "Nested", "OneLine", "Parted", "Signalled", "Stored", "Thrown", "Twice", "Wrapped",
    )

    /** The larger workflows, in the order of their names. */
    val SERVICES = listOf("Fulfilment", "Quotes", "Refunds", "Renewal")

    /** File name to text; every file starts with the same lines of package and imports. */
    val FILES: Map<String, String> = linkedMapOf(
        "Steps" to """
@ActivityInterface
interface Steps {
    fun reserve(id: String): String
    fun charge(id: String): String
    fun ship(id: String): String
    fun notify(id: String)
    fun refund(id: String)
    fun release(id: String)
    fun audit(id: String)
}

val options: ActivityOptions = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()

class Order(
    val id: String,
    val express: Boolean = false,
    val gift: Boolean = false,
    val note: String? = null,
    val lines: List<String> = emptyList(),
    val channel: String = "email",
)
""",
        "Ports" to """
/** No class of these sources implements any of these: what runs behind them cannot be seen. */
interface OrderMapper {
    fun toText(order: Order): String
}

interface OrderRepository {
    fun save(order: Order)
    fun find(id: String): Order?
    fun next(): Order?
}

interface PricingClient {
    fun quote(order: Order): Long
}

interface Transactions {
    fun <T> inTransaction(block: () -> T): T
}
""",
        "Helper" to """
/** Ordinary code around the ports: no step of a workflow is certain anywhere in it. */
class Helper(private val mapper: OrderMapper, private val repository: OrderRepository, private val pricing: PricingClient) {
    fun describe(order: Order): String = mapper.toText(order)

    fun remember(order: Order) {
        repository.save(order)
    }

    fun price(order: Order): Long = pricing.quote(order)

    fun known(id: String): Boolean = repository.find(id) != null

    fun poll(): Order? = repository.next()

    /** Hands its block to the library, which runs it or not: the block is neither run here nor handed to a function of the sources. */
    fun <T> audited(action: String, block: () -> T): T = traced(action, block)
}
""",
        "Notifiers" to """
/** Two classes implement this one, and each holds a step. */
interface Notifier {
    fun send(id: String)
}

class EmailNotifier : Notifier {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun send(id: String) {
        steps.notify(id)
    }
}

class SmsNotifier : Notifier {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun send(id: String) {
        steps.audit(id)
        steps.notify(id)
    }
}
""",
        "Deep" to """
/** A chain of calls with a step at its end, deeper than calls are followed, and a function that calls itself. */
class Deep(private val steps: Steps) {
    fun one(id: String) = two(id)

    fun two(id: String) = three(id)

    fun three(id: String) = four(id)

    fun four(id: String) = five(id)

    fun five(id: String) = six(id)

    fun six(id: String) = seven(id)

    fun seven(id: String) = eight(id)

    fun eight(id: String) = nine(id)

    fun nine(id: String) = ten(id)

    fun ten(id: String) {
        steps.audit(id)
    }

    fun again(id: String, left: Int) {
        steps.charge(id)
        if (left > 0) again(id, left - 1)
    }
}
""",
        // ---- one workflow for each rule
        "Wrapped" to """
@WorkflowInterface
interface Wrapped {
    @WorkflowMethod
    fun run(id: String)
}

class WrappedImpl : Wrapped {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val context = RequestContext("shop", "worker")

    override fun run(id: String) {
        withRequestContext(context) {
            steps.reserve(id)
        }
        steps.ship(id)
    }
}
""",
        "Twice" to """
@WorkflowInterface
interface Twice {
    @WorkflowMethod
    fun run(id: String)
}

class TwiceImpl : Twice {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(id: String) {
        either({ steps.charge(id) }, { steps.refund(id) })
        steps.notify(id)
    }
}
""",
        "Nested" to """
@WorkflowInterface
interface Nested {
    @WorkflowMethod
    fun run(id: String)
}

class NestedImpl : Nested {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val context = RequestContext("shop", "worker")
    private val tracer = Tracer("shop")

    override fun run(id: String) {
        tracer.span("order") {
            withRequestContext(context) {
                steps.reserve(id)
                retrying(3) {
                    steps.charge(id)
                }
            }
            steps.ship(id)
        }
    }
}
""",
        "Looped" to """
@WorkflowInterface
interface Looped {
    @WorkflowMethod
    fun run(order: Order)
}

class LoopedImpl(private val helper: Helper) : Looped {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        for (line in order.lines) {
            traced("line") {
                steps.reserve(line)
            }
        }
        // A loop that holds nothing but a call that is left out.
        for (line in order.lines) {
            helper.remember(order)
        }
        // One that is left by a `break` and holds nothing else.
        while (true) {
            if (helper.known(order.id)) break
        }
        steps.ship(order.id)
    }
}
""",
        "Blocked" to """
@WorkflowInterface
interface Blocked {
    @WorkflowMethod
    fun run(order: Order)
}

class BlockedImpl(private val helper: Helper) : Blocked {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val context = RequestContext("shop", "worker")

    override fun run(order: Order) {
        if (order.express) {
            withRequestContext(context) {
                steps.ship(order.id)
            }
        } else {
            steps.notify(order.id)
        }
        // The only thing on either way is left out: no branch.
        if (order.gift) {
            helper.remember(order)
        }
        // One way keeps a step, the other holds only what is left out.
        if (order.note != null) {
            steps.audit(order.id)
        } else {
            helper.remember(order)
        }
        when (order.channel) {
            "email" -> helper.describe(order)
            "sms" -> steps.notify(order.id)
            else -> helper.remember(order)
        }
        order.note?.let { helper.describe(order) }
    }
}
""",
        "Helped" to """
@WorkflowInterface
interface Helped {
    @WorkflowMethod
    fun run(order: Order): Long
}

class HelpedImpl(private val helper: Helper) : Helped {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order): Long {
        val text = helper.describe(order)
        steps.reserve(order.id)
        helper.remember(order)
        val price = helper.price(order)
        steps.charge(order.id + text)
        return price
    }
}
""",
        "Direct" to """
@WorkflowInterface
interface Direct {
    @WorkflowMethod
    fun run(order: Order)
}

class DirectImpl(private val mapper: OrderMapper, private val transactions: Transactions, private val notifier: Notifier) : Direct {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        // The workflow's own code calls a function that no class implements.
        mapper.toText(order)
        steps.reserve(order.id)
        // The same, handed code: the code is drawn where the call is made.
        transactions.inTransaction {
            steps.charge(order.id)
        }
        // One of two classes runs, and each holds a step: the node stays.
        notifier.send(order.id)
    }
}
""",
        "Emptied" to """
@WorkflowInterface
interface Emptied {
    @WorkflowMethod
    fun run(order: Order)
}

class EmptiedImpl(private val helper: Helper) : Emptied {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        val saga = Saga(Saga.Options.Builder().build())
        saga.addCompensation { helper.remember(order) }
        // A lane that nothing waits for, and one that the flow waits for: neither holds a step.
        Async.procedure { helper.remember(order) }
        val priced = Async.function { helper.price(order) }
        priced.get()
        try {
            steps.charge(order.id)
        } catch (e: ActivityFailure) {
            saga.compensate()
        }
    }
}
""",
        "Lanes" to """
@WorkflowInterface
interface Lanes {
    @WorkflowMethod
    fun run(order: Order)
}

class LanesImpl(private val helper: Helper) : Lanes {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        // Two lanes run a race; one of them holds nothing that an overview draws.
        val priced = Async.function { helper.price(order) }
        val charged = Async.function { steps.charge(order.id) }
        Promise.anyOf(priced, charged).get()
        // A lane that nothing waits for, with a step after a call that is left out.
        Async.procedure {
            helper.remember(order)
            steps.audit(order.id)
        }
        steps.ship(order.id)
    }
}
""",
        "Kept" to """
@WorkflowInterface
interface Kept {
    @WorkflowMethod
    fun run(id: String, kind: String)
}

class KeptImpl(private val notifier: Notifier) : Kept {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val remote = Workflow.newActivityStub(RemoteSteps::class.java, options)
    private val untyped = Workflow.newUntypedActivityStub(options)
    private val deep = Deep(steps)

    override fun run(id: String, kind: String) {
        deep.one(id)
        deep.again(id, 2)
        untyped.execute("Check" + kind, String::class.java, id)
        notifier.send(id)
        remote.push(id)
    }
}
""",
        "Delegated" to """
@WorkflowInterface
interface Delegated {
    @WorkflowMethod
    fun run(id: String)

    @SignalMethod
    fun stop()
}

/** Leaves both functions to the object it is given: neither has a body to read. */
class DelegatedImpl(inner: Delegated) : Delegated by inner
""",
        "Caught" to """
@WorkflowInterface
interface Caught {
    @WorkflowMethod
    fun run(order: Order)
}

class CaughtImpl(private val helper: Helper) : Caught {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        steps.reserve(order.id)
        try {
            // The first thing that may fail here is left out: its failure leaves from the step before it.
            helper.remember(order)
            steps.charge(order.id)
        } catch (e: ActivityFailure) {
            steps.release(order.id)
        }
        try {
            // Nothing else may fail here.
            helper.remember(order)
        } catch (e: ActivityFailure) {
            steps.refund(order.id)
        }
    }
}
""",
        "Cleaned" to """
@WorkflowInterface
interface Cleaned {
    @WorkflowMethod
    fun run(order: Order)
}

class CleanedImpl(private val helper: Helper) : Cleaned {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        steps.reserve(order.id)
        try {
            try {
                helper.remember(order)
            } finally {
                steps.release(order.id)
            }
        } catch (e: ActivityFailure) {
            steps.notify(order.id)
        }
    }
}
""",
        "Forever" to """
@WorkflowInterface
interface Forever {
    @WorkflowMethod
    fun run()
}

class ForeverImpl(private val helper: Helper) : Forever {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run() {
        steps.audit("start")
        // The flow never gets past this loop, and all it holds is a call that would be left out.
        while (true) {
            helper.poll()
        }
    }
}
""",
        "Chained" to """
@WorkflowInterface
interface Chained {
    @WorkflowMethod
    fun run(id: String, pending: Promise<String>)
}

class ChainedImpl : Chained {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(id: String, pending: Promise<String>) {
        // A promise of which nothing is known: what is chained to it runs at some time.
        pending.thenApply { steps.audit(it) }
        steps.ship(id)
    }
}
""",
        "Stored" to """
@WorkflowInterface
interface Stored {
    @WorkflowMethod
    fun run(id: String)
}

class StoredImpl : Stored {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(id: String) {
        // A lambda kept as a value.
        val later = { steps.audit(id) }
        steps.ship(id)
        later()
    }
}
""",
        "Handed" to """
@WorkflowInterface
interface Handed {
    @WorkflowMethod
    fun run(id: String)
}

class HandedImpl(private val helper: Helper) : Handed {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(id: String) {
        // A function of the sources that hands its block to the library.
        helper.audited("charge") {
            steps.charge(id)
        }
        steps.ship(id)
    }
}
""",
        "Thrown" to """
@WorkflowInterface
interface Thrown {
    @WorkflowMethod
    fun run(id: String)
}

class ThrownImpl : Thrown {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(id: String) {
        // The block never completes: what follows it is reached past it alone.
        traced<Unit>("check") {
            steps.audit(id)
            throw ApplicationFailure.newFailure("not allowed", "Rejected")
        }
        steps.ship(id)
    }
}
""",
        "Parted" to """
@WorkflowInterface
interface Parted {
    @WorkflowMethod
    fun run(order: Order)
}

class PartedImpl(private val helper: Helper) : Parted {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private var paid = false

    override fun run(order: Order) {
        val inTime = Workflow.await(Duration.ofHours(1)) { paid }
        // Only tests how the wait ended, and all it holds is left out.
        if (!inTime) {
            helper.remember(order)
        }
        steps.ship(order.id)
    }
}
""",
        "Decided" to """
@WorkflowInterface
interface Decided {
    @WorkflowMethod
    fun run(order: Order)
}

class DecidedImpl(private val helper: Helper) : Decided {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        // What decides is a call that is left out; the branch has a step and stays.
        if (helper.known(order.id)) {
            steps.notify(order.id)
        }
        if (helper.price(order) > 100) return
        steps.ship(order.id)
    }
}
""",
        "OneLine" to """
@WorkflowInterface
interface OneLine {
    @WorkflowMethod
    fun run(order: Order)
}

class OneLineImpl(private val helper: Helper, private val notifier: Notifier) : OneLine {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)

    override fun run(order: Order) {
        // Two calls on one line that cannot be read, and two decisions: the second of each keeps the id it has beside the first.
        traced("tell") { notifier.send(order.id) }
        if (order.gift) helper.remember(order); if (order.express) steps.ship(order.id)
    }
}
""",
        "Signalled" to """
@WorkflowInterface
interface Signalled {
    @WorkflowMethod
    fun run(order: Order)

    @SignalMethod
    fun paid(order: Order)
}

class SignalledImpl(private val helper: Helper) : Signalled {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private var paid = false

    override fun run(order: Order) {
        Workflow.await { paid }
        steps.ship(order.id)
    }

    override fun paid(order: Order) {
        if (helper.known(order.id)) {
            helper.remember(order)
        }
        paid = true
    }
}
""",
        // ---- workflows that mix the rules as a service does
        "Fulfilment" to """
@WorkflowInterface
interface Fulfilment {
    @WorkflowMethod
    fun run(order: Order): String

    @SignalMethod
    fun cancel(reason: String)
}

class FulfilmentImpl(private val helper: Helper, private val mapper: OrderMapper, private val transactions: Transactions, private val notifier: Notifier) : Fulfilment {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val context = RequestContext("shop", "fulfilment")
    private val tracer = Tracer("fulfilment")
    private val retrier = Retrier(3)
    private val locks = Locks()
    private val deep = Deep(steps)
    private var cancelled = false

    override fun run(order: Order): String {
        val text = withRequestContext(context) { helper.describe(order) }
        if (!helper.known(order.id)) {
            helper.remember(order)
        }
        val reserved = tracer.span("reserve") {
            withRequestContext(context) {
                retrier.run { steps.reserve(order.id) }
            }
        }
        if (order.gift) {
            helper.remember(order)
        } else {
            mapper.toText(order)
        }
        for (line in order.lines) {
            tracer.span("line") {
                if (helper.known(line)) {
                    withRequestContext(context) { steps.reserve(line) }
                } else {
                    helper.remember(order)
                }
            }
        }
        val price = helper.price(order)
        if (price > 1000) {
            traced("review") { steps.audit(order.id) }
        }
        try {
            transactions.inTransaction<Unit> {
                helper.remember(order)
                retrying(3) { steps.charge(order.id) }
            }
        } catch (e: ActivityFailure) {
            withRequestContext(context) { steps.release(order.id) }
            order.note?.let { helper.describe(order) }
            throw ApplicationFailure.newFailure("payment failed", "PaymentFailed")
        }
        if (cancelled) {
            locks.guarded(order.id) {
                steps.refund(order.id)
                steps.release(order.id)
            }
            helper.remember(order)
            return "cancelled"
        }
        when (order.channel) {
            "email" -> helper.describe(order)
            "sms" -> tracer.span("sms") { notifier.send(order.id) }
            "post" -> helper.remember(order)
            else -> mapper.toText(order)
        }
        val shipped = if (order.express) {
            tracer.span("express") { withRequestContext(context) { steps.ship(order.id) } }
        } else {
            helper.audited("ship") { steps.ship(order.id) }
        }
        deep.one(order.id)
        helper.remember(order)
        withRequestContext(context) { tracer.span("done") { steps.notify(order.id) } }
        return reserved + shipped + text
    }

    override fun cancel(reason: String) {
        cancelled = true
    }
}
""",
        "Quotes" to """
@WorkflowInterface
interface Quotes {
    @WorkflowMethod
    fun run(order: Order): Long
}

/** Mostly decisions around code that cannot be read: two steps and the failure of a quote that makes no sense. */
class QuotesImpl(
    private val helper: Helper,
    private val mapper: OrderMapper,
    private val repository: OrderRepository,
    private val pricing: PricingClient,
) : Quotes {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val context = RequestContext("shop", "quotes")

    override fun run(order: Order): Long {
        val known = repository.find(order.id) ?: helper.poll()
        if (known == null) {
            helper.remember(order)
        } else {
            mapper.toText(known)
        }
        val text = if (order.gift) helper.describe(order) else mapper.toText(order)
        val base = if (order.express) pricing.quote(order) else helper.price(order)
        steps.audit(order.id)
        var total = base
        for (line in order.lines) {
            val linePrice = when {
                line.isEmpty() -> 0L
                line.startsWith("gift") -> helper.price(order)
                line.startsWith("bulk") -> pricing.quote(order) / 2
                else -> pricing.quote(order)
            }
            if (linePrice > 100 && helper.known(line)) {
                helper.remember(order)
            }
            total += linePrice
        }
        order.note?.let { note -> if (note.length > 10) helper.describe(order) else mapper.toText(order) }
        if (total > 1000 || helper.known(order.id)) {
            withRequestContext(context) {
                if (order.express) helper.remember(order) else steps.notify(order.id)
            }
        }
        val discounted = if (text.isEmpty()) total else total - helper.price(order)
        when (order.channel) {
            "email" -> if (order.gift) helper.describe(order) else helper.remember(order)
            "sms" -> helper.remember(order)
            else -> if (helper.known(order.id)) mapper.toText(order)
        }
        if (discounted < 0) {
            helper.remember(order)
            throw ApplicationFailure.newFailure("negative total", "BadQuote")
        }
        steps.charge(order.id)
        return discounted
    }
}
""",
        "Refunds" to """
@WorkflowInterface
interface Refunds {
    @WorkflowMethod
    fun run(orders: List<Order>)
}

class RefundsImpl(private val helper: Helper, private val mapper: OrderMapper) : Refunds {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val context = RequestContext("shop", "refunds")
    private val tracer = Tracer("refunds")
    private val deep = Deep(steps)

    override fun run(orders: List<Order>) {
        for (order in orders) {
            val known = withRequestContext(context) { helper.known(order.id) }
            if (!known) {
                helper.remember(order)
                continue
            }
            val text = mapper.toText(order)
            if (order.gift) {
                helper.describe(order)
            }
            tracer.span("refund") {
                try {
                    withRequestContext(context) { steps.refund(order.id) }
                    helper.remember(order)
                } catch (e: ActivityFailure) {
                    helper.describe(order)
                    traced("failed") { steps.audit(order.id + text) }
                }
            }
            for (line in order.lines) {
                if (helper.price(order) > 0) {
                    retrying(2) { steps.release(line) }
                }
                helper.remember(order)
            }
            when {
                order.express -> withRequestContext(context) { steps.notify(order.id) }
                order.note != null -> helper.describe(order)
                else -> helper.remember(order)
            }
            deep.again(order.id, 1)
        }
        tracer.span("summary") {
            helper.remember(orders.first())
        }
        steps.audit("refunds")
    }
}
""",
        "Renewal" to """
@WorkflowInterface
interface Renewal {
    @WorkflowMethod
    fun run(order: Order, kind: String)

    @SignalMethod
    fun changed(order: Order)
}

class RenewalImpl(private val helper: Helper, private val notifier: Notifier) : Renewal {
    private val steps = Workflow.newActivityStub(Steps::class.java, options)
    private val remote = Workflow.newActivityStub(RemoteSteps::class.java, options)
    private val untyped = Workflow.newUntypedActivityStub(options)
    private val context = RequestContext("shop", "renewal")
    private val tracer = Tracer("renewal")
    private var changes = 0

    override fun run(order: Order, kind: String) {
        var round = 0
        while (round < 12) {
            round++
            val changedInTime = Workflow.await(Duration.ofDays(30)) { changes > 0 }
            if (changedInTime) {
                helper.remember(order)
            }
            val price = tracer.span("price") { helper.price(order) }
            if (price == 0L) {
                helper.describe(order)
                break
            }
            val charged = Async.function { withRequestContext(context) { steps.charge(order.id) } }
            val told = Async.procedure { helper.remember(order) }
            Promise.allOf(charged, told).get()
            if (helper.known(order.id)) {
                untyped.execute("Renew" + kind, String::class.java, order.id)
            } else {
                helper.remember(order)
            }
            tracer.span("tell") {
                notifier.send(order.id)
                remote.push(order.id)
            }
            Workflow.sleep(Duration.ofDays(1))
        }
        withRequestContext(context) { steps.audit(order.id) }
    }

    override fun changed(order: Order) {
        withRequestContext(context) {
            helper.remember(order)
        }
        changes++
    }
}
""",
    ).mapValues { (_, text) -> HEADER + text }
}
