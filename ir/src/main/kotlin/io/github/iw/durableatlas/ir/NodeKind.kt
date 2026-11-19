package io.github.iw.durableatlas.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NodeKind {
    @SerialName("start")
    START,

    @SerialName("end")
    END,

    @SerialName("activity")
    ACTIVITY,

    @SerialName("localActivity")
    LOCAL_ACTIVITY,

    @SerialName("childWorkflow")
    CHILD_WORKFLOW,

    @SerialName("signalExternal")
    SIGNAL_EXTERNAL,

    @SerialName("nexusOperation")
    NEXUS_OPERATION,

    @SerialName("timer")
    TIMER,

    @SerialName("await")
    AWAIT,

    @SerialName("sideEffect")
    SIDE_EFFECT,

    @SerialName("versionGate")
    VERSION_GATE,

    @SerialName("branch")
    BRANCH,

    @SerialName("loop")
    LOOP,

    @SerialName("parallel")
    PARALLEL,

    @SerialName("compensation")
    COMPENSATION,

    @SerialName("continueAsNew")
    CONTINUE_AS_NEW,

    /**
     * The execution of the workflow fails: an exception leaves the workflow method, or the
     * handler of a signal, that is a failure of Temporal (an `ApplicationFailure`, the failure
     * of an activity or of a child workflow, a cancellation) or of a type that a worker of the
     * analysed sources is told to fail workflows on. [IrNode.name] is what the failure is
     * called: the type given to an `ApplicationFailure`, or else the class of the exception;
     * where one place may fail with several, [IrNode.label] lists them. In the graph of the
     * handler of an update the node says that the update fails, which the workflow outlives;
     * in that of a query or of an update validator it stands for any exception, which fails
     * the query or rejects the update.
     *
     * An exception of any other type fails the workflow task, which is tried again until the
     * code is fixed: the execution neither fails nor goes on. A way that only leads there is
     * not part of the graph, and neither is a decision that has no other way. Only where a
     * node has no other way to go is such an end written as a node of this kind as well, since
     * nothing else says that the flow ends there. Workers that are set up outside the analysed
     * sources may be told of further types, which a document cannot know of.
     */
    @SerialName("fail")
    FAIL,

    @SerialName("unknown")
    UNKNOWN,

    /**
     * A cancellation scope is cancelled: what was started in it and still runs is asked to
     * stop. `cancels` edges lead from the node to what the scope holds.
     */
    @SerialName("cancelScope")
    CANCEL_SCOPE,

    /** Another workflow is asked to cancel, through an external workflow stub. */
    @SerialName("cancelExternal")
    CANCEL_EXTERNAL,

    /**
     * Search attributes of the workflow are set or removed. [IrNode.keys] names them where the
     * code does.
     */
    @SerialName("upsertSearchAttributes")
    UPSERT_SEARCH_ATTRIBUTES,

    /** Entries of the workflow's memo are set or removed. [IrNode.keys] names them where the code does. */
    @SerialName("upsertMemo")
    UPSERT_MEMO,
    ;

    /** Whether other nodes may name a node of this kind as their [IrNode.parent]. */
    val isContainer: Boolean
        get() = this == LOOP || this == PARALLEL || this == COMPENSATION

    /** Whether a node of this kind writes what [IrNode.keys] names: search attributes, or entries of the memo. */
    val isUpsert: Boolean
        get() = this == UPSERT_SEARCH_ATTRIBUTES || this == UPSERT_MEMO

    /** Whether a node of this kind ends the execution path that reaches it. */
    val isTerminal: Boolean
        get() = this == END || this == CONTINUE_AS_NEW || this == FAIL
}
