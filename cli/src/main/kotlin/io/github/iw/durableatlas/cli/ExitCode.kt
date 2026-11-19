package io.github.iw.durableatlas.cli

/** Process exit codes shared by every command. */
object ExitCode {
    const val SUCCESS: Int = 0

    /** Bad arguments, a failed Gradle run or a crash. */
    const val FAILURE: Int = 1

    /**
     * The command completed, but at least one workflow exceeded a threshold: one the caller set
     * on its unknown nodes or, for an overlay, on the events of its histories that have no node;
     * or the time a diagram is allowed, so that the workflow has no diagram in a format that was
     * asked for. A diagram that was drawn more simply to keep within that time is no such case:
     * the workflow has every file, and each is right.
     */
    const val UNRESOLVED_THRESHOLD_EXCEEDED: Int = 2

    /** The command completed in degraded mode after Gradle extraction failed. */
    const val DEGRADED: Int = 3
}
