package io.github.iw.durableatlas.overlay.testing

import io.temporal.api.history.v1.History

/**
 * Histories as executions of the workflows of [Fixtures.regionEndIr] would leave them.
 *
 * `OuterContinue` is `outer@ for (id in ids) { prepare(id); for (part in parts of id) { if
 * (part.isEmpty()) continue@outer; work(part) }; report(id) }`.
 */
object RegionEndHistories {
    /** Three ids: one of two parts; one of a part and an empty part, which skips the report; one of one part. */
    fun outerContinueSkipping(): History = Histories("OuterContinue").apply {
        ran("Prepare"); ran("Work"); ran("Work"); ran("Report")
        ran("Prepare"); ran("Work")
        ran("Prepare"); ran("Work"); ran("Report")
        completed()
    }.build()

    /** One id of one part: nothing is skipped. */
    fun outerContinuePlain(): History = Histories("OuterContinue").apply {
        ran("Prepare"); ran("Work"); ran("Report")
        completed()
    }.build()
}
