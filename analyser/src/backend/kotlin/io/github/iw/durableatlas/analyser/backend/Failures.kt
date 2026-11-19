package io.github.iw.durableatlas.analyser.backend

/**
 * Keeps failures of the Analysis API from ending an analysis. Its implementation trips over some
 * code it does not expect; what it trips over is then left unresolved or left out, and reported.
 * Only the first few failures are reported one by one.
 */
internal class Failures(private val log: (String) -> Unit) {
    private var count = 0

    /** Runs [query]; if it fails, reports the failure and returns `null`. */
    fun <T> guard(what: () -> String, query: () -> T): T? =
        try {
            query()
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            count++
            if (count <= REPORTED_ONE_BY_ONE) log("warning: the Kotlin Analysis API failed on ${what()}: $e")
            null
        }

    /** Reports how many failures were not reported one by one. */
    fun summarise() {
        val unreported = count - REPORTED_ONE_BY_ONE
        if (unreported > 0) log("warning: the Kotlin Analysis API failed on $unreported further piece(s) of code")
        count = 0
    }

    private companion object {
        const val REPORTED_ONE_BY_ONE = 10
    }
}
