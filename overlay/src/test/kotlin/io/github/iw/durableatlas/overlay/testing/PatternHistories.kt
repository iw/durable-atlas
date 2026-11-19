package io.github.iw.durableatlas.overlay.testing

import io.temporal.api.history.v1.History

/**
 * Histories as executions of the workflows of [Fixtures.patternIr] would leave them, one for
 * each way through: by the name of the document's file, then by a name for the way.
 *
 * The workflows wait on queues and promises that handlers feed, start work that nothing waits
 * for, cancel scopes and other workflows, chain promises and retry beside the flow.
 */
object PatternHistories {
    private fun history(workflowType: String, events: Histories.() -> Unit): History = Histories(workflowType).apply(events).build()

    /** A workflow that runs one activity and is done. */
    private fun one(workflowType: String, activity: String): Map<String, History> =
        linkedMapOf("plain" to history(workflowType) { ran(activity); completed() })

    val all: Map<String, Map<String, History>> = linkedMapOf(
        // take; work; poll(5 minutes) ?: return; work; poll(30 seconds); if nothing: report.
        "Inbox" to linkedMapOf(
            "all" to history("Inbox") {
                signal("add")
                ran("Work")
                val wait = timer(300)
                signal("add")
                timerCanceled(wait)
                ran("Work")
                timerFired(timer(30))
                ran("Report")
                completed()
            },
            "one" to history("Inbox") { signal("add"); ran("Work"); timerFired(timer(300)); completed() },
            // Both were there before they were asked for: no timer is started.
            "pushed" to history("Inbox") {
                updateCompleted(updateAccepted("push"))
                updateCompleted(updateAccepted("push"))
                ran("Work")
                ran("Work")
                val wait = timer(30)
                signal("add")
                timerCanceled(wait)
                completed()
            },
        ),
        // prepare; wait for the decision; work; wait an hour at most for the second.
        "Approval" to linkedMapOf(
            "approved" to history("Approval") {
                ran("Prepare")
                signal("approve")
                ran("Work")
                val wait = timer(3600)
                signal("approve")
                timerCanceled(wait)
                completed()
            },
            // The promise fails, and the workflow with it: no node says so.
            "rejected" to history("Approval") { ran("Prepare"); signal("reject"); failed("Rejected") },
        ),
        // announce, not waited for; a lane of three that nothing waits for; check beside prepare and work; undo, not waited for; clean up.
        "Unjoined" to linkedMapOf(
            "fine" to history("Unjoined") {
                val announce = activity("Announce")
                val check = activity("Check")
                val spare = activity("Prepare")
                val work = activity("Work")
                // The lane begins when the workflow method first waits.
                val load = activity("Load")
                activityCompleted(announce)
                activityCompleted(load)
                ran("Weigh")
                activityCompleted(check)
                ran("Audit")
                activityCompleted(spare)
                activityCompleted(work)
                val undo = activity("Undo")
                ran("CleanUp")
                activityCompleted(undo)
                completed()
            },
            // What nobody waits for fails, and nothing comes of it; the lane is still running when the workflow is done.
            "unseen" to history("Unjoined") {
                val announce = activity("Announce")
                val check = activity("Check")
                val spare = activity("Prepare")
                val work = activity("Work")
                val load = activity("Load")
                activityFailed(announce)
                activityCompleted(check)
                activityFailed(spare)
                activityCompleted(work)
                val undo = activity("Undo")
                activityFailed(undo)
                ran("CleanUp")
                activityCompleted(load)
                activity("Weigh")
                completed()
            },
        ),
        "Courier" to one("Courier", "Work"),
        // A child that is waited for until it has started, one that is not waited for, and one that is.
        "Detach" to linkedMapOf(
            "started" to history("Detach") {
                val first = child("Courier", "courier-1")
                childStarted(first)
                val second = child("Courier", "courier-2")
                val third = child("Courier", "courier-3")
                childStarted(second)
                childStarted(third)
                childCompleted(third)
                completed()
            },
        ),
        // A scope starts prepare; a minute later it is cancelled; report.
        "CancelLater" to linkedMapOf(
            "cancelled" to history("CancelLater") {
                val prepare = activity("Prepare")
                timerFired(timer(60))
                activityCancelRequested(prepare)
                val report = activity("Report")
                activityCanceled(prepare)
                activityCompleted(report)
                completed()
            },
            // Done before the minute is up: cancelling the scope finds nothing to cancel.
            "done-first" to history("CancelLater") {
                val prepare = activity("Prepare")
                val wait = timer(60)
                activityCompleted(prepare)
                timerFired(wait)
                ran("Report")
                completed()
            },
        ),
        // One lane for each of three; when the first is done the scope is cancelled, and the other two with it.
        "Race" to linkedMapOf(
            "three" to history("Race") {
                val first = activity("Prepare")
                val second = activity("Prepare")
                val third = activity("Prepare")
                activityCompleted(second)
                activityCancelRequested(first)
                activityCancelRequested(third)
                val report = activity("Report")
                activityCanceled(first)
                activityCanceled(third)
                activityCompleted(report)
                completed()
            },
        ),
        // prepare; an hour's sleep; work, all in a scope that a signal cancels.
        "Stoppable" to linkedMapOf(
            "stopped" to history("Stoppable") {
                ran("Prepare")
                val wait = timer(3600)
                signal("stop")
                timerCanceled(wait)
                ran("CleanUp")
                completed()
            },
            "stopped-at-work" to history("Stoppable") {
                ran("Prepare")
                timerFired(timer(3600))
                val work = activity("Work")
                signal("stop")
                activityCancelRequested(work)
                activityCanceled(work)
                ran("CleanUp")
                completed()
            },
            "ran-through" to history("Stoppable") { ran("Prepare"); timerFired(timer(3600)); ran("Work"); completed() },
        ),
        // Three scopes: one cancels itself if a check fails, one after five seconds, and one cannot be told.
        "SelfStop" to linkedMapOf(
            "cancelled" to history("SelfStop") {
                val work = activity("Work")
                ran("Check")
                activityCancelRequested(work)
                val prepare = activity("Prepare")
                timerFired(timer(5))
                activityCancelRequested(prepare)
                val report = activity("Report")
                // Cancelled by the scope that cannot be told: the event has no node.
                activityCancelRequested(report)
                activityCanceled(work)
                activityCanceled(prepare)
                activityCanceled(report)
                completed()
            },
            "checked" to history("SelfStop") {
                val work = activity("Work")
                ran("Check")
                val prepare = activity("Prepare")
                activityCompleted(work)
                activityCompleted(prepare)
                timerFired(timer(5))
                ran("Report")
                completed()
            },
        ),
        "Carrier" to one("Carrier", "Work"),
        // Two other workflows are asked to cancel: the first is, the second is not there.
        "CallOff" to linkedMapOf(
            "asked" to history("CallOff") {
                cancelDelivered(cancelRequested("other"))
                cancelFailed(cancelRequested("carrier-7"))
                completed()
            },
        ),
        // report then clean up, not waited for; prepare then work, or undo; check then prepare, then report if either failed.
        "Chains" to linkedMapOf(
            "fine" to history("Chains") {
                val report = activity("Report")
                val prepare = activity("Prepare")
                val check = activity("Check")
                activityCompleted(report)
                val cleanUp = activity("CleanUp")
                activityCompleted(prepare)
                val work = activity("Work")
                activityCompleted(check)
                val composed = activity("Prepare")
                activityCompleted(cleanUp)
                activityCompleted(work)
                activityCompleted(composed)
                completed()
            },
            "failed" to history("Chains") {
                val report = activity("Report")
                val prepare = activity("Prepare")
                val check = activity("Check")
                activityFailed(report)
                activityFailed(prepare)
                val undo = activity("Undo")
                activityFailed(check)
                val told = activity("Report")
                activityCompleted(undo)
                activityCompleted(told)
                completed()
            },
        ),
        // A retry beside a check; then a retry of work and report.
        "AsyncRetries" to linkedMapOf(
            "at-once" to history("AsyncRetries") {
                val prepare = activity("Prepare")
                val check = activity("Check")
                activityCompleted(prepare)
                activityCompleted(check)
                ran("Work")
                ran("Report")
                completed()
            },
            "retried" to history("AsyncRetries") {
                val prepare = activity("Prepare")
                val check = activity("Check")
                activityFailed(prepare)
                val again = activity("Prepare")
                activityCompleted(check)
                activityCompleted(again)
                ran("Work")
                activityFailed(activity("Report"))
                ran("Work")
                ran("Report")
                completed()
            },
        ),
        "Notice--patterns.NotifyImpl" to one("Notice", "Report"),
        "Notice--patterns.RemindImpl" to linkedMapOf("plain" to history("Notice") { ran("Check"); ran("Report"); completed() }),
        // A child of each of two interfaces of one type, and one of a type of its own.
        "Notices" to linkedMapOf(
            "three" to history("Notices") {
                childCompleted(child("Notice"))
                childCompleted(child("Notice"))
                childCompleted(child("Courier"))
                completed()
            },
        ),
    )
}
