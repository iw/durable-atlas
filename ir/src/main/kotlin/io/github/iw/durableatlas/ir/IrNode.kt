package io.github.iw.durableatlas.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One step of a workflow graph. Only [id] and [kind] are always present; the other properties
 * apply to some kinds and are left out of the JSON form when unset.
 */
@Serializable
data class IrNode(
    val id: String,
    val kind: NodeKind,
    /**
     * Temporal name of the thing invoked: activity type, child workflow type, signal name or Nexus
     * operation. A child workflow is referenced by its workflow type, and by [interfaceName]
     * where the type does not say which workflow it is; it is never embedded. For
     * [NodeKind.CANCEL_EXTERNAL], the workflow type of the workflow that is asked to cancel,
     * where the stub says it. For [NodeKind.FAIL], what the failure is called: the type given
     * to an `ApplicationFailure`, or else the class of the exception.
     */
    val name: String? = null,
    /**
     * Fully qualified name of the workflow interface the stub of a [NodeKind.CHILD_WORKFLOW] was
     * made for. It is written where the workflow type does not say it: where the analysed
     * sources hold another workflow interface of the same type. It names the document the child
     * is described by, the one with this [IrDocument.interfaceName].
     */
    @SerialName("interface")
    val interfaceName: String? = null,
    /** Nexus service name, for [NodeKind.NEXUS_OPERATION]. */
    val service: String? = null,
    val taskQueue: String? = null,
    /** ISO-8601 duration of a [NodeKind.TIMER]. */
    val duration: String? = null,
    /** ISO-8601 duration after which the step gives up, for example the timeout of an await. */
    val timeout: String? = null,
    /**
     * How far the flow waits for work that it started to run beside it, where it does not wait
     * for it to end. Unset everywhere else: the flow goes on when the step is done. See [WaitsFor].
     */
    val waitsFor: WaitsFor? = null,
    /**
     * Which of the lanes of a [NodeKind.PARALLEL] region the flow waits for, where it does not
     * wait for all of them. Unset everywhere else. See [Join].
     */
    val join: Join? = null,
    /**
     * What becomes of a [NodeKind.CHILD_WORKFLOW] when this workflow closes, where the options of
     * its stub say that it is not ended with it. See [ParentClosePolicy].
     */
    val parentClosePolicy: ParentClosePolicy? = null,
    /**
     * Workflow properties a [NodeKind.AWAIT] waits on: those its condition reads, the queue or
     * promise it waits to be given something through, the queue it waits for room in, or the
     * lock or semaphore it waits to be free. One of these that the workflow method keeps in a
     * variable of its own is named by that variable.
     */
    val reads: List<String> = emptyList(),
    /**
     * The names of what a [NodeKind.UPSERT_SEARCH_ATTRIBUTES] or [NodeKind.UPSERT_MEMO] node
     * writes: the search attributes, or the entries of the memo, that it sets or removes, in the
     * order the code gives them. Where the code names every one of them, these are all the node
     * writes. Where it names only some, these are the ones it names, and [otherKeys] says that
     * there are more. A node without keys writes something the analysis could not tell at all.
     */
    val keys: List<String> = emptyList(),
    /**
     * True for a [NodeKind.UPSERT_SEARCH_ATTRIBUTES] or [NodeKind.UPSERT_MEMO] node that writes
     * more than its [keys] name: further names that the analysis could not tell. Unset where
     * [keys] is the whole list, and where there are no keys at all.
     */
    val otherKeys: Boolean = false,
    /** Change id of a [NodeKind.VERSION_GATE]. */
    val changeId: String? = null,
    /** Free-text caption, for example the header of a loop. */
    val label: String? = null,
    /**
     * True for a [NodeKind.LOOP] that is a retry: what it holds runs once, and again for as long
     * as it fails and the retry options allow. Between two attempts the workflow pauses, and the
     * timer of that pause is a step of the loop that no node of its own stands for. Unset on
     * every other node.
     */
    val retry: Boolean = false,
    /** Why a call could not be resolved, for [NodeKind.UNKNOWN]. See [UnknownReasons]. */
    val reason: String? = null,
    /**
     * Possible targets of an unresolved call, for [NodeKind.UNKNOWN]: the classes whose function
     * the call may run, for [UnknownReasons.DYNAMIC_DISPATCH]; the functions of the analysed
     * sources that the compiler could not choose among, for [UnknownReasons.UNRESOLVED]; the
     * functions without a body that are called behind the call and that no class of the
     * analysed sources implements, for [UnknownReasons.UNSEEN_CODE].
     */
    val candidates: List<String> = emptyList(),
    /**
     * What the implementation of the activity type of an [NodeKind.ACTIVITY] or
     * [NodeKind.LOCAL_ACTIVITY] node asks of other services, in the order of its code: see
     * [Request]. Empty where it asks nothing that the analysis can see, and where no class of the
     * analysed sources implements the activity type.
     */
    val requests: List<Request> = emptyList(),
    /**
     * Id of the enclosing container node, when this node sits inside a region. An edge from this
     * node to its parent, or to a region further out, leads to the end of that region.
     */
    val parent: String? = null,
    val loc: SourceLocation? = null,
)

/**
 * How far the flow waits for something it started to run beside it and never waits for to end:
 * what is handed to `Async`, or started through a call that gives a promise, and whose promise
 * nothing waits on.
 *
 * On a step, the step is started where it stands and the flow goes on from it at once. On a
 * [NodeKind.PARALLEL] region, the lanes are started and the flow goes on with what follows the
 * region; the lanes end on their own.
 */
@Serializable
enum class WaitsFor {
    /**
     * The flow waits until the step has started and then goes on: a child workflow whose
     * execution is waited for. On a [NodeKind.PARALLEL] region, the flow goes on once the child
     * workflows among its direct children that say the same have started; the lane such a
     * child stands in waits for it to end, as a lane does for each of its steps.
     */
    @SerialName("start")
    START,

    /** The flow does not wait at all. */
    @SerialName("nothing")
    NOTHING,
}

/**
 * Which lanes of a parallel region the flow waits for before it goes on, where that is not all
 * of them.
 */
@Serializable
enum class Join {
    /**
     * The flow goes on as soon as the first lane has ended, as with `Promise.anyOf`. The other
     * lanes run on beside what follows, until they end or something cancels them. Where the
     * region stands in a loop that starts one lane in each round, the lanes of all the rounds
     * are meant.
     */
    @SerialName("first")
    FIRST,
}

/**
 * What becomes of a child workflow when its parent closes, where the child is not ended with it.
 * A child that is terminated then, which is the default, or asked to cancel has none.
 */
@Serializable
enum class ParentClosePolicy {
    /** The child runs on: it outlives the workflow that started it. */
    @SerialName("abandon")
    ABANDON,
}

/** A position in the analysed sources. */
@Serializable
data class SourceLocation(
    /** Path relative to the root of the analysed repository, with `/` separators. */
    val file: String,
    /** 1-based line number. */
    val line: Int,
)

/** Reasons for [NodeKind.UNKNOWN] nodes that every consumer can rely on. */
object UnknownReasons {
    /**
     * The call was not followed because it lies deeper than the inlining limit, and a step is
     * certain to lie in the function called or behind it; [IrNode.label] names the function.
     */
    const val DEPTH_LIMIT: String = "depth-limit"

    /** The call leads back into a function that is already being followed, and that holds a step. */
    const val RECURSION: String = "recursion"

    /**
     * The call may run one of several functions, depending on the class of the object it is made
     * on, and a step is certain to lie in one of them; [IrNode.candidates] lists the classes.
     * Without candidates, no class that implements the function is among the analysed sources.
     */
    const val DYNAMIC_DISPATCH: String = "dynamic-dispatch"

    /** The name of the activity, workflow, signal or operation is only known at run time. */
    const val COMPUTED_NAME: String = "computed-name"

    /** The construct is one the analysis does not model; what it contains follows the node. */
    const val UNSUPPORTED: String = "unsupported"

    /**
     * The call is one of a function of the analysed sources that is not drawn from the inside,
     * because no step is certain to lie in it, and that calls, itself or through the functions
     * it calls, a function without a body that no class of the analysed sources implements: a
     * step may lie behind that. [IrNode.label] names the function called and
     * [IrNode.candidates] lists the functions that could not be followed. Where the code that
     * is drawn makes such a call itself, the node has the reason [DYNAMIC_DISPATCH].
     */
    const val UNSEEN_CODE: String = "unseen-code"

    /**
     * The call could not be resolved to a declaration. In a document of a degraded model, which
     * lacks the libraries the sources use, only a call that may be a step has such a node: one
     * made through a stub whose interface did not resolve, one that names the Temporal API, or
     * one that names several functions of the sources, which [IrNode.candidates] lists. The
     * others are in [IrDocument.skippedCalls].
     *
     * In a document of either kind of model, a call also has such a node where it is a call of
     * a function, or of a function or constructor of a class, that the sources declare more than
     * once under one fully qualified name, and nothing tells which declaration it means: two
     * modules that do not see each other may each declare one.
     */
    const val UNRESOLVED: String = "unresolved"
}
