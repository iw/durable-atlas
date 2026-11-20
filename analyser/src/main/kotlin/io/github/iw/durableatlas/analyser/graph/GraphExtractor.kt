package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.SourceLocation

/** What one entry point of a workflow gives: its graph, and the state of the workflow it touches. */
internal class Extracted(
    val graph: Graph,
    /** The properties of the workflow object that the entry point reads and writes. */
    val state: StateUse,
    /** The handlers the workflow registers itself while the entry point runs, in the order they are met. */
    val listeners: List<FlowReader.Listener> = emptyList(),
    /** What the function of the entry point changes as it runs, without what making the workflow object set before. */
    val changes: StateUse = StateUse(),
)

/**
 * Turns the body of an entry point into a graph: a node for every step of the workflow and for
 * every place where the flow forks, repeats or runs side by side, and nothing for the code in
 * between. The functions of the sources that the body calls are part of it.
 *
 * The work is done in two passes. [FlowReader] reads off the code what happens and in what
 * structure; [GraphLayout] draws that as nodes and edges.
 */
internal class GraphExtractor(
    backend: SourceBackend,
    recognizer: CallRecognizer,
    tracer: ValueTracer,
    maxDepth: Int,
    /** Is told what the code holds that no graph shows. */
    warn: (String) -> Unit = {},
    /** The options for activities that the workflow is given where a worker registers its class. */
    registered: ActivityEnvironment = ActivityEnvironment(),
    /** Tells which exceptions fail the execution of a workflow, and which only its task. */
    private val failures: FailureTypes = FailureTypes(backend),
    /** Is told the calls that have no node, where the graphs are those of an overview; `null` where every call gets its node. */
    private val leftOut: LeftOut? = null,
) {
    private val reader = FlowReader(backend, recognizer, tracer, maxDepth, warn, registered, failures)

    /**
     * @param entryPointId id of the entry point, which node ids are derived from
     * @param function the function that runs for the entry point; `null` if the class has none
     * @param implementation the class whose object the function runs on
     * @param fallback where to point if there is no function to point at
     * @param kind what the entry point is: what a handler reads and writes is looked for in
     *   everything it reaches, and what an exception that leaves it does depends on it
     * @param constructor the constructor that makes the object the workflow method runs on: its
     *   steps are the first of the graph
     */
    fun extract(
        entryPointId: String,
        function: FunctionDecl?,
        implementation: String,
        fallback: SourceLocation?,
        kind: EntryPointKind,
        constructor: FunctionId? = null,
    ): Extracted {
        val isHandler = kind != EntryPointKind.WORKFLOW_METHOD
        // The workflow method runs first, whatever is asked of the reading; what it sets for activities a handler finds set.
        val read = function?.let { reader.read(it, implementation, thorough = isHandler, constructor, main = entryPointId == WorkflowGraphs.MAIN) }
        val graph = GraphLayout(entryPointId, failures, strict(kind), leftOut).layOut(function?.location ?: fallback, read?.flow, isHandler)
        return Extracted(graph, read?.state ?: StateUse(), read?.listeners.orEmpty(), read?.changes ?: StateUse())
    }

    /** The graph of a handler the workflow registered itself. */
    fun extract(entryPointId: String, listener: FlowReader.Listener, fallback: SourceLocation?): Extracted =
        Extracted(GraphLayout(entryPointId, failures, strict(listener.kind), leftOut).layOut(listener.location ?: fallback, listener.flow, isHandler = true), listener.state)

    /**
     * Whether an exception that leaves an entry point of [kind] is drawn only where it may fail
     * the execution of the workflow, or the update: any other fails the workflow task. That is
     * so for the workflow method and for the handlers of signals and updates. A query that
     * throws fails, and a validator that throws rejects its update, whatever they throw: every
     * exception that leaves one of those is an outcome of its own.
     */
    private fun strict(kind: EntryPointKind): Boolean = kind != EntryPointKind.QUERY && kind != EntryPointKind.UPDATE_VALIDATOR

    /** The cancellation scopes that the property [property] of the workflow is given, in any of the entry points read so far. */
    fun scopesHeldBy(property: PropertyId): Set<ScopeKey> = reader.scopesHeldBy(property)

    /** Whether making an object of [implementation] through [constructor] holds a step. */
    fun constructionHoldsSteps(constructor: FunctionId, implementation: String): Boolean =
        reader.constructionHoldsSteps(constructor, implementation)
}
