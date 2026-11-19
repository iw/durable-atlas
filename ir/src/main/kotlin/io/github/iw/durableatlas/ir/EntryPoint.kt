package io.github.iw.durableatlas.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A method through which the workflow is entered: the main workflow method or a handler.
 *
 * The [id] shares a namespace with node ids, because a `wakes` edge starts at an entry point.
 */
@Serializable
data class EntryPoint(
    val id: String,
    val kind: EntryPointKind,
    /**
     * Workflow type, signal, query or update name as registered with Temporal. A handler that
     * takes every signal, query or update without a handler of its own is called [ANY_NAME].
     */
    val name: String,
    /** Id of the `start` node of this entry point's graph. */
    val start: String,
    /**
     * Workflow properties the handler reads, itself or in the functions it calls. A handler
     * that the workflow method registers as it runs also names the variables of the workflow
     * method that it sees and that hold a queue, a promise, a lock or a semaphore.
     */
    val reads: List<String> = emptyList(),
    /**
     * Workflow properties the handler writes, itself or in the functions it calls: by assigning
     * to them, or by changing the objects they hold.
     */
    val writes: List<String> = emptyList(),
) {
    companion object {
        /** What a handler is called that takes every name without a handler of its own. */
        const val ANY_NAME: String = "(any name)"
    }
}

@Serializable
enum class EntryPointKind {
    @SerialName("workflowMethod")
    WORKFLOW_METHOD,

    @SerialName("signal")
    SIGNAL,

    @SerialName("query")
    QUERY,

    @SerialName("update")
    UPDATE,

    @SerialName("updateValidator")
    UPDATE_VALIDATOR,
}
