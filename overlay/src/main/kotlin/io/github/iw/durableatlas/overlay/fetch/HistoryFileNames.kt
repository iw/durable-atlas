package io.github.iw.durableatlas.overlay.fetch

/**
 * Names of the files fetched histories are written to: `<workflow type>.<workflow id>.<run id>.json`.
 *
 * Workflow types and workflow ids are free text, so characters other than letters, digits, `.`,
 * `_` and `-` become `_`, and a long workflow id is cut short. The run id is what tells two
 * executions apart, so an execution has the same file name whenever it is fetched, and no two
 * executions share one.
 */
internal object HistoryFileNames {
    private const val MAX_PART = 80
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    fun of(workflowType: String, workflowId: String, runId: String): String =
        "${safe(workflowType).take(MAX_PART)}.${safe(workflowId).take(MAX_PART)}.${safe(runId)}.json"

    private fun safe(text: String): String = text.replace(UNSAFE, "_").trim('.').ifEmpty { "_" }
}
