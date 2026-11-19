package io.github.iw.durableatlas.overlay.testing

import io.temporal.api.history.v1.History

/**
 * Histories as executions of the workflows of [Fixtures.regionExitIr] would leave them, one for
 * each way through: by workflow type, then by a name for the way.
 *
 * All of them run `for (id in ids) { … }` and most go on with a step after the loop. What the
 * round ends with is in the name of the workflow: a branch that breaks, returns or continues, a
 * `when`, a step followed by a `break`, a `return` or a `throw`, a step whose failure is caught,
 * an await that can time out, and a step beside a lane that is followed by a `break`.
 */
object RegionExitHistories {
    private fun history(workflowType: String, events: Histories.() -> Unit): History = Histories(workflowType).apply(events).build()

    val all: Map<String, Map<String, History>> = linkedMapOf(
        // prepare; if (last) break. Then report.
        "BranchBreak" to linkedMapOf(
            "two-rounds" to history("BranchBreak") { ran("Prepare"); ran("Prepare"); ran("Report"); completed() },
            "no-round" to history("BranchBreak") { ran("Report"); completed() },
        ),
        // prepare; if (last) return. Then report.
        "BranchReturn" to linkedMapOf(
            "returned" to history("BranchReturn") { ran("Prepare"); ran("Prepare"); completed() },
            "ran-out" to history("BranchReturn") { ran("Prepare"); ran("Prepare"); ran("Report"); completed() },
        ),
        // prepare; if (skip) continue. Then report.
        "BranchContinue" to linkedMapOf(
            "three-rounds" to history("BranchContinue") { ran("Prepare"); ran("Prepare"); ran("Prepare"); ran("Report"); completed() },
        ),
        // prepare; when (id) { last -> break; skip -> continue; quit -> return; work -> work; else -> nothing }. Then report.
        "WhenArms" to linkedMapOf(
            "worked-then-last" to history("WhenArms") { ran("Prepare"); ran("Work"); ran("Prepare"); ran("Report"); completed() },
            "skipped-then-quit" to history("WhenArms") { ran("Prepare"); ran("Prepare"); completed() },
        ),
        // prepare; if (last) { report; break }. Then clean up.
        "NotifyBreak" to linkedMapOf(
            "found" to history("NotifyBreak") { ran("Prepare"); ran("Prepare"); ran("Report"); ran("CleanUp"); completed() },
            "not-found" to history("NotifyBreak") { ran("Prepare"); ran("Prepare"); ran("CleanUp"); completed() },
        ),
        // work; return. Then report.
        "StepReturn" to linkedMapOf(
            "one-id" to history("StepReturn") { ran("Work"); completed() },
            "no-id" to history("StepReturn") { ran("Report"); completed() },
        ),
        // prepare; if (bad) { report; throw }.
        "NotifyThrow" to linkedMapOf(
            "bad" to history("NotifyThrow") { ran("Prepare"); ran("Prepare"); ran("Report"); failed("IllegalStateException") },
            "fine" to history("NotifyThrow") { ran("Prepare"); ran("Prepare"); completed() },
        ),
        // try { work } catch { }, then a loop of prepare whose failure is caught around the loop, by a clean-up.
        "CaughtFailure" to linkedMapOf(
            "fine" to history("CaughtFailure") { ran("Work"); ran("Work"); ran("Prepare"); ran("Prepare"); completed() },
            "failing" to history("CaughtFailure") {
                activityFailed(activity("Work")); ran("Work")
                ran("Prepare"); activityFailed(activity("Prepare")); ran("CleanUp")
                completed()
            },
        ),
        // prepare; if (!await(5 minutes) { answered }) break. Then report.
        "AwaitTimeout" to linkedMapOf(
            "answered" to history("AwaitTimeout") {
                ran("Prepare")
                val first = timer(300)
                signal("answer")
                timerCanceled(first)
                ran("Prepare")
                ran("Prepare")
                ran("Report")
                completed()
            },
            "timed-out" to history("AwaitTimeout") {
                ran("Prepare")
                timerFired(timer(300))
                ran("Report")
                completed()
            },
        ),
        // A lane that checks, beside: prepare; if (last) { report; break }; then the lane is waited for. Then clean up.
        "LaneBreak" to linkedMapOf(
            "joined" to history("LaneBreak") {
                repeat(2) {
                    val check = activity("Check")
                    ran("Prepare")
                    activityCompleted(check)
                }
                ran("CleanUp")
                completed()
            },
            "broke" to history("LaneBreak") {
                val check = activity("Check")
                ran("Prepare")
                activityCompleted(check)
                ran("Report")
                ran("CleanUp")
                completed()
            },
        ),
    )
}
