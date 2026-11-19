package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.find
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.ImplementedEntryPoint
import io.github.iw.durableatlas.analyser.discovery.WorkflowImplementation
import io.github.iw.durableatlas.analyser.discovery.WorkflowType
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.normalized

/**
 * Makes the IR of workflow types: one document for each class that implements one. It holds a
 * graph for the workflow method and one for each handler, the edges from the handlers to the
 * awaits they wake, and the edges from the places where a cancellation scope is cancelled to
 * what that scope holds, in whichever of the graphs it lies.
 *
 * The workflow method wakes an await as well where its own code can end the wait: that of a
 * handler, which may wait while the workflow method goes on, and one of its own that stands in
 * another lane than the code that changes what it waits on. Such an edge starts at the entry
 * point of the workflow method and is labelled with the properties in question.
 *
 * The graph of the workflow method begins with what making the workflow object does: the SDK
 * makes it on the workflow's own thread, so a step in a constructor, an `init` block or the
 * initialiser of a property is a step of the workflow, taken before the workflow method runs.
 *
 * A `fail` node stands where the execution of the workflow may fail: where an exception leaves
 * it that is a failure of Temporal or of a type a worker is told to fail workflows on (see
 * [FailTypeReader]). Any other exception fails the workflow task, and a way that only leads
 * there is not drawn (see [GraphLayout]). The workers are those of the analysed sources: what
 * a worker is told elsewhere is not seen.
 *
 * A handler that the workflow registers while it runs, with `Workflow.registerListener`, is an
 * entry point like those its class declares. One that takes every name without a handler of its
 * own is called [ANY_NAME], and its id begins with `dynamic-`.
 *
 * A document made from a degraded model lists the calls that did not resolve and were taken for
 * calls into libraries the model lacks, so that none of them goes missing unseen: those in the
 * code that was read for the workflow, and those in the functions that were looked through and
 * found to hold nothing else.
 */
class WorkflowGraphs(
    private val backend: SourceBackend,
    /** Commit of the analysed repository, for the documents to carry. */
    private val commit: String,
    private val modelMode: ModelMode,
    /**
     * How many calls deep the functions of the sources are followed from an entry point. A call
     * nested deeper becomes an `unknown` node, if a step is certain to lie behind it.
     */
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    private val names = TemporalNames(backend)
    private val tracer = ValueTracer(backend)
    private val constants = Constants(tracer)

    /**
     * A degraded model has the Temporal SDK and its Kotlin extensions on its classpath and none
     * of the other libraries the sources use, so a call that does not resolve there is as a rule a call into one of
     * those: it is judged, and drawn only where it may be a step (see [UnresolvedCalls]). In a
     * model made from the build every such call is drawn as what it is, a call nothing is known of.
     */
    private val lenient = modelMode == ModelMode.DEGRADED
    private val stubs = Stubs(tracer, constants, lenient)

    /** The classes that did not resolve and that the sources make stubs of: the same for every workflow, and found once. */
    private val stubClasses: Set<String> by lazy(LazyThreadSafetyMode.NONE) { UnresolvedCalls.stubClasses(backend) }
    private val registrations = WorkerRegistrations(backend, tracer, stubs.activities)
    private val noted = LinkedHashSet<String>()

    /**
     * Which exceptions fail the execution of a workflow: the failures of Temporal, and what the
     * workers of the sources are told to fail workflows on. The same for every workflow, and
     * found once. Where a place says so with something that cannot be read, any exception may.
     */
    private val failures: FailureTypes by lazy(LazyThreadSafetyMode.NONE) {
        val configured = FailTypeReader(backend, tracer).read()
        configured.unreadable.firstOrNull()?.let { first ->
            val more = configured.unreadable.size - 1
            noted += "${first.file}:${first.line}: which exceptions fail a workflow here cannot be told" +
                (if (more > 0) " (nor in $more more place(s))" else "") +
                "; every exception that may leave a workflow is drawn as its failure"
        }
        FailureTypes(backend, configured)
    }

    /**
     * What the code of the workflows read so far holds that their graphs do not show, each said
     * once and with its place, in the order it was met.
     */
    val warnings: List<String> get() = noted.toList()

    init {
        require(maxDepth >= 0) { "the depth to follow calls to must not be negative: $maxDepth" }
    }

    /**
     * The documents of every workflow type in [discovery], ordered by workflow type, interface
     * and implementing class. A workflow type without an implementing class in the sources has
     * no code to look at and gives none.
     */
    fun extract(discovery: Discovery): List<IrDocument> =
        discovery.workflows.flatMap { workflow -> workflow.implementations.map { extract(workflow, it) } }

    /** The document of [workflow] as [implementation] implements it, in canonical order. */
    fun extract(workflow: WorkflowType, implementation: WorkflowImplementation): IrDocument {
        val recognizer = CallRecognizer(
            backend,
            names,
            stubs,
            constants,
            tracer,
            workflow.name,
            if (lenient) UnresolvedCalls(backend, tracer, stubs) { stubClasses } else null,
        )
        val extractor = GraphExtractor(
            backend,
            recognizer,
            tracer,
            maxDepth,
            noted::add,
            registrations.optionsFor(implementation.declaration.name),
            failures,
        )
        val className = implementation.declaration.name
        val declared = implementation.declaration.location
        val entryPoints = ArrayList<EntryPoint>()
        val nodes = ArrayList<IrNode>()
        val edges = ArrayList<IrEdge>()
        val writers = ArrayList<Pair<String, StateUse>>()
        val queueWaits = HashMap<String, QueueWait>()
        val wokenByFlow = HashMap<String, List<String>>()
        val owners = HashMap<String, String>()
        var flow = StateUse()
        val registered = ArrayList<FlowReader.Listener>()
        val held = LinkedHashMap<ScopeKey, MutableSet<String>>()
        val cancels = ArrayList<Cancellation>()

        fun add(id: String, kind: EntryPointKind, name: String, extracted: Extracted) {
            val isHandler = kind != EntryPointKind.WORKFLOW_METHOD
            nodes += extracted.graph.nodes
            edges += extracted.graph.edges
            extracted.graph.scopes.forEach { (scope, ids) -> held.getOrPut(scope) { LinkedHashSet() } += ids }
            cancels += extracted.graph.cancels
            queueWaits += extracted.graph.queueWaits
            wokenByFlow += extracted.graph.wokenByFlow
            extracted.graph.nodes.forEach { owners[it.id] = id }
            if (id == MAIN) flow = extracted.changes
            entryPoints += EntryPoint(
                id = id,
                kind = kind,
                name = name,
                start = extracted.graph.start,
                // What the workflow method itself reads and writes is its own business.
                reads = if (isHandler) extracted.state.reads.toList() else emptyList(),
                writes = if (isHandler) extracted.state.writes.toList() else emptyList(),
            )
            // A signal or an update changes what the workflow waits for; a query and a validator must not.
            if (kind == EntryPointKind.SIGNAL || kind == EntryPointKind.UPDATE) writers += id to extracted.state
            registered += extracted.listeners
        }

        val taken = HashSet<String>()
        for ((id, implemented) in entryPointsOf(implementation, taken)) {
            val kind = implemented.entryPoint.kind
            val isHandler = kind != EntryPointKind.WORKFLOW_METHOD
            val constructor = if (isHandler) null else constructorOf(implementation, extractor)
            val extracted = extractor.extract(id, implemented.function, className, declared, kind, constructor)
            add(id, kind, if (isHandler) implemented.entryPoint.name else workflow.name, extracted)
        }
        // A handler registered by a handler is registered as well; each is looked at once.
        var next = 0
        while (next < registered.size) {
            val listener = registered[next++]
            val id = if (listener.name == null) "$DYNAMIC-${prefixOf(listener.kind)}" else "${prefixOf(listener.kind)}-${listener.name}"
            // Temporal knows one handler of each kind by a name: the one that was there first.
            if (taken.add(id)) add(id, listener.kind, listener.name ?: ANY_NAME, extractor.extract(id, listener, declared))
        }

        // A handler wakes an await if it writes a property that the await's condition reads, in a way that can end the wait.
        for (await in nodes.filter { it.kind == NodeKind.AWAIT }) {
            val queue = queueWaits[await.id]
            for ((id, state) in writers) {
                if (state.wakes(await.reads, queue).isNotEmpty()) edges += IrEdge(from = id, to = await.id, kind = EdgeKind.WAKES)
            }
            // The workflow method does where it runs beside the wait: beside every handler, and beside its own other lanes.
            val own = if (owners[await.id] == MAIN) wokenByFlow[await.id].orEmpty() else flow.wakes(await.reads, queue)
            if (own.isNotEmpty()) edges += IrEdge(from = MAIN, to = await.id, kind = EdgeKind.WAKES, label = own.joinToString(", "))
        }

        // The place where a scope is cancelled cancels what the scope holds, in whichever entry point that runs.
        // What lies around the place itself is left out: an edge from a node to a region around it says where the flow goes.
        val parents = nodes.associate { it.id to it.parent }
        for (cancel in cancels) {
            val id = cancel.node
            val scope = cancel.scope
            val scopes = if (scope is PropertyId) extractor.scopesHeldBy(scope) else setOf(scope)
            val around = generateSequence(parents[id]) { parents[it] }.toSet()
            // A step the flow has waited for on its way to the place is over: nothing of it is left to cancel.
            val cancelled = scopes.flatMap { held[it].orEmpty() }.distinct().filter { it != id && it !in around && it !in cancel.done }
            cancelled.forEach { edges += IrEdge(from = id, to = it, kind = EdgeKind.CANCELS) }
            if (scope is PropertyId && scopes.isEmpty()) {
                val place = nodes.first { it.id == id }.loc?.let { "${it.file}:${it.line}" } ?: className
                noted += "$place: which cancellation scope this cancels cannot be told; the place is drawn without what it cancels"
            }
        }

        return IrDocument(
            commit = commit,
            modelMode = modelMode,
            workflowType = workflow.name,
            interfaceName = workflow.declaration.name,
            implementation = className,
            // A class of the sources always has a module; the fallbacks keep the document valid.
            module = implementation.declaration.module ?: workflow.declaration.module ?: ROOT_MODULE,
            entryPoints = entryPoints,
            nodes = nodes,
            edges = edges,
            // Everything that was read or looked through for this document, handlers included.
            skippedCalls = recognizer.skipped,
        ).normalized()
    }

    /**
     * The entry points of a class, each with its id: `main` for the workflow method, and the
     * kind and name of a handler for the handler, as in `signal-cancelOrder`. A workflow type
     * whose class has no workflow method still gets a `main` graph, which says so.
     */
    private fun entryPointsOf(implementation: WorkflowImplementation, taken: MutableSet<String>): List<Pair<String, ImplementedEntryPoint>> {
        val all = implementation.entryPoints + implementation.additionalEntryPoints
        return all.mapNotNull { implemented ->
            val entryPoint = implemented.entryPoint
            val id = if (entryPoint.kind == EntryPointKind.WORKFLOW_METHOD) MAIN else "${prefixOf(entryPoint.kind)}-${entryPoint.name}"
            // Temporal knows one handler of each kind by a name; a second one is never reached.
            if (taken.add(id)) id to implemented else null
        }
    }

    /** What the id of a handler of [kind] begins with. */
    private fun prefixOf(kind: EntryPointKind): String =
        when (kind) {
            EntryPointKind.WORKFLOW_METHOD -> MAIN
            EntryPointKind.SIGNAL -> "signal"
            EntryPointKind.QUERY -> "query"
            EntryPointKind.UPDATE -> "update"
            EntryPointKind.UPDATE_VALIDATOR -> "validator"
        }

    /**
     * The constructor that makes the object of a workflow, the way the SDK picks it: the one
     * marked as the workflow's initialiser, or else the one that takes no arguments. A class
     * with one constructor is taken to be made through it, by whatever makes it. Where none of
     * that settles it and a step is at stake, a warning says that the step is not drawn.
     */
    private fun constructorOf(implementation: WorkflowImplementation, extractor: GraphExtractor): FunctionId? {
        val className = implementation.declaration.name
        val constructors = backend.constructors(className)
        val chosen = constructors.firstOrNull { it.annotations.find(TemporalApi.WORKFLOW_INIT) != null }
            ?: constructors.firstOrNull { it.takesNoArguments }
            ?: constructors.singleOrNull()
        if (chosen != null) return chosen.id
        for (constructor in constructors.filter { extractor.constructionHoldsSteps(it.id, className) }) {
            val place = constructor.location?.let { "${it.file}:${it.line}" } ?: className
            noted += "$place: $className has several constructors and none says that it makes the workflow; the steps in this one are not drawn"
        }
        return null
    }

    companion object {
        /** Id of the entry point that is the workflow method. */
        const val MAIN: String = "main"

        /** What the id of a handler begins with that takes every name without a handler of its own, as in `dynamic-signal`. */
        const val DYNAMIC: String = "dynamic"

        /** What such a handler is called, in place of the name of a signal, query or update. */
        const val ANY_NAME: String = EntryPoint.ANY_NAME

        /** How many calls deep functions are followed unless told otherwise. */
        const val DEFAULT_MAX_DEPTH: Int = 8

        private const val ROOT_MODULE = ":"
    }
}
