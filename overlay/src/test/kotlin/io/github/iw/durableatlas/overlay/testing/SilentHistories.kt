package io.github.iw.durableatlas.overlay.testing

import io.temporal.api.history.v1.History

/**
 * Histories as executions of the workflows of [Fixtures.silentIr] would leave them, by the name
 * of the document's file, then by a name for the way through it.
 *
 * The workflows wait for a lock or a semaphore, set search attributes and memo entries, get the
 * options of their activities from somewhere other than the stub, and hand activities to the
 * workflow to run. Which events each call leaves is read off the sources of the SDK: a wait for
 * a lock leaves none, one with a time to wait for at most starts a timer if it has to wait and
 * cancels it when it is let through, an upsert leaves one event that names what it writes. The
 * order of the events inside one workflow task is assumed.
 */
object SilentHistories {
    private fun history(workflowType: String, events: Histories.() -> Unit): History = Histories(workflowType).apply(events).build()

    val all: Map<String, Map<String, History>> = linkedMapOf(
        // change: lock; fetch; unlock. tryChange: tryLock(30 seconds) or return; store; unlock. release: unlock.
        "Guarded" to linkedMapOf(
            // The lock is free: waiting for it leaves no event.
            "alone" to history("Guarded") {
                val change = updateAccepted("change")
                ran("Fetch")
                updateCompleted(change)
                completed()
            },
            // A second run of the same update waits until the first has let go.
            "queued" to history("Guarded") {
                val first = updateAccepted("change")
                val fetch = activity("Fetch")
                val second = updateAccepted("change")
                activityCompleted(fetch)
                updateCompleted(first)
                ran("Fetch")
                updateCompleted(second)
                completed()
            },
            // The lock is held when the other update asks for it: a timer runs until the signal lets go of the lock.
            "freed" to history("Guarded") {
                val change = updateAccepted("change")
                val fetch = activity("Fetch")
                val attempt = updateAccepted("tryChange")
                val wait = timer(30)
                signal("release")
                timerCanceled(wait)
                ran("Store")
                updateCompleted(attempt)
                activityCompleted(fetch)
                updateCompleted(change)
                completed()
            },
            // Nobody lets go in time: the timer fires and the update returns.
            "timed-out" to history("Guarded") {
                val change = updateAccepted("change")
                val fetch = activity("Fetch")
                val attempt = updateAccepted("tryChange")
                timerFired(timer(30))
                updateCompleted(attempt)
                activityCompleted(fetch)
                updateCompleted(change)
                completed()
            },
        ),
        // for each id: acquire; fetch; release. tryAcquire(2, 1 minute): store, or notify. tryAcquire(): notify. acquire(2).
        "Pool" to linkedMapOf(
            // Both permits are free until the workflow takes them for good; it then waits for two more, which two signals give.
            "granted" to history("Pool") {
                ran("Fetch")
                ran("Fetch")
                ran("Store")
                signal("grant")
                signal("grant")
                completed()
            },
            // With one permit free where it asks for two, the workflow waits for its minute and is given nothing.
            "short" to history("Pool") {
                ran("Fetch")
                timerFired(timer(60))
                ran("Notify")
                ran("Notify")
                signal("grant")
                completed()
            },
        ),
        "Labels" to linkedMapOf(
            "all" to history("Labels") {
                searchAttributes("Stage", "Attempts")
                memo("note")
                ran("Fetch")
                searchAttributes("Attempts", "Stage")
                searchAttributes("Stage", "Owner")
                memo("by", "note")
                searchAttributes("Region")
                memo("colour")
                searchAttributes("Tier")
                completed()
            },
            // A worker that is told to keeps the versions of the workflow in a search attribute of its own.
            "versions" to history("Labels") {
                searchAttributes("Stage", "Attempts")
                searchAttributes("TemporalChangeVersion")
                memo("note")
                ran("Fetch")
                searchAttributes("Stage", "Attempts")
                searchAttributes("Stage", "Owner")
                memo("note", "by")
                searchAttributes("Region")
                memo("colour")
                searchAttributes("Tier")
                completed()
            },
        ),
        "Defaults" to linkedMapOf("plain" to history("Defaults") { ran("Fetch"); localActivity("Store"); ran("Notify"); ran("Notify"); completed() }),
        "PerType" to linkedMapOf(
            "plain" to history("PerType") { ran("Fetch"); ran("Fetch"); ran("Store"); ran("Store"); localActivity("Notify"); localActivity("Fetch"); completed() },
        ),
        "StubMaps" to linkedMapOf("plain" to history("StubMaps") { ran("Fetch"); ran("Store"); localActivity("Store"); ran("Fetch"); completed() }),
        "Registered" to linkedMapOf(
            "plain" to history("Registered") { ran("Fetch"); ran("Store"); localActivity("Fetch"); ran("Fetch"); ran("Store"); completed() },
        ),
        "Twice" to linkedMapOf("plain" to history("Twice") { ran("Fetch"); ran("Store"); completed() }),
        // fetch; fetch; store; notify; await ready. nudge: ready; notify; store.
        "Precedence" to linkedMapOf(
            "nudged" to history("Precedence") {
                ran("Fetch")
                ran("Fetch")
                ran("Store")
                ran("Notify")
                signal("nudge")
                ran("Notify")
                ran("Store")
                completed()
            },
        ),
        // fetch; store beside a local notify, waited for; notify, not waited for.
        "Direct" to linkedMapOf(
            "all" to history("Direct") {
                ran("Fetch")
                val store = activity("Store")
                localActivity("Notify")
                activityCompleted(store)
                activity("Notify")
                completed()
            },
        ),
    )

    /**
     * Histories of the retries of [Fixtures.constructIr] and [Fixtures.patternIr] with everything
     * the SDK writes for a retry: the marker of its options where it begins, and a timer for the
     * pause before each further attempt.
     */
    val retries: Map<String, History> = linkedMapOf(
        // retry { prepare; work }; retry { check }; if (fine) report.
        "last-fails" to history("Retries") {
            retryBegins(1)
            ran("Prepare")
            activityFailed(activity("Work"))
            backOff(1)
            ran("Prepare")
            ran("Work")
            retryBegins(2)
            activityFailed(activity("Check"))
            backOff(1)
            activityFailed(activity("Check"))
            backOff(2)
            ran("Check")
            ran("Report")
            completed()
        },
        // The first step of the two fails: the next attempt begins with it again.
        "first-fails" to history("Retries") {
            retryBegins(1)
            activityFailed(activity("Prepare"))
            backOff(1)
            ran("Prepare")
            ran("Work")
            retryBegins(2)
            ran("Check")
            completed()
        },
        // A retry of prepare beside a check; then a retry of work and report.
        "beside" to history("AsyncRetries") {
            retryBegins(1)
            val prepare = activity("Prepare")
            val check = activity("Check")
            activityFailed(prepare)
            val pause = timer(1)
            activityCompleted(check)
            timerFired(pause)
            ran("Prepare")
            retryBegins(2)
            activityFailed(activity("Work"))
            backOff(1)
            ran("Work")
            ran("Report")
            completed()
        },
    )
}
