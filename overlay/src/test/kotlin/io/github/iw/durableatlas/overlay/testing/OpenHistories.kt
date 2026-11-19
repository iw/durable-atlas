package io.github.iw.durableatlas.overlay.testing

import io.temporal.api.history.v1.History

/**
 * Histories as executions of the workflows of [Fixtures.openIr] would leave them, by the name
 * of the document's file, then by a name for the way through it.
 *
 * The workflows run races, start steps with calls that give promises, wait for a promise a
 * time at most, wait for room in a queue and on what their own code lets go of, wait for the
 * start of a child inside what nothing waits for, and name some of what an upsert writes. Which
 * events each call leaves is read off the sources of the SDK: `Promise.anyOf` and waiting for
 * it leave none; a step that is given a promise for leaves what the step always leaves, where
 * it is started; a wait for a time at most starts a timer if it has to wait and cancels it when
 * it is let through; putting into a queue without a time leaves nothing. The order of the
 * events inside one workflow task is assumed.
 */
object OpenHistories {
    private fun history(workflowType: String, events: Histories.() -> Unit): History = Histories(workflowType).apply(events).build()

    val all: Map<String, Map<String, History>> = linkedMapOf(
        // fetch and store raced; notify; fetch and store again, both waited for.
        "Fastest" to linkedMapOf(
            // The fetch wins; the store that lost ends later, beside what follows.
            "loser-ends" to history("Fastest") {
                val fetch = activity("Fetch")
                val store = activity("Store")
                activityStarted(fetch)
                activityStarted(store)
                activityCompleted(fetch)
                ran("Notify")
                val again = activity("Fetch")
                val other = activity("Store")
                activityCompleted(store)
                activityCompleted(again)
                activityCompleted(other)
                completed()
            },
            // The store wins; the fetch that lost is still running when the workflow is done.
            "loser-runs-on" to history("Fastest") {
                activity("Fetch")
                val store = activity("Store")
                activityCompleted(store)
                ran("Notify")
                val again = activity("Fetch")
                val other = activity("Store")
                activityCompleted(again)
                activityCompleted(other)
                completed()
            },
        ),
        // In a scope: fetch, and beside it ten seconds and then store. The first to end wins; the scope is cancelled; store.
        "Winner" to linkedMapOf(
            // The fetch is done within the ten seconds: cancelling the scope cancels the timer of the other lane.
            "fetch" to history("Winner") {
                val fetch = activity("Fetch")
                val nap = timer(10)
                activityCompleted(fetch)
                timerCanceled(nap)
                ran("Store")
                completed()
            },
            // The other lane is done first: cancelling the scope asks the fetch to cancel.
            "store" to history("Winner") {
                val fetch = activity("Fetch")
                val nap = timer(10)
                timerFired(nap)
                val store = activity("Store")
                activityCompleted(store)
                activityCancelRequested(fetch)
                val last = activity("Store")
                activityCanceled(fetch)
                activityCompleted(last)
                completed()
            },
        ),
        // A fetch for each of three, of which the first is waited for; store.
        "Soonest" to linkedMapOf(
            "three" to history("Soonest") {
                activity("Fetch")
                val second = activity("Fetch")
                activity("Fetch")
                activityCompleted(second)
                ran("Store")
                completed()
            },
        ),
        // A fetch raced against a minute; a notice if the minute won.
        "Deadline" to linkedMapOf(
            "in-time" to history("Deadline") {
                val work = activity("Fetch")
                timer(60)
                activityCompleted(work)
                completed()
            },
            // The minute is up first: the notice goes out, and the fetch, which lost, ends beside it.
            "late" to history("Deadline") {
                val work = activity("Fetch")
                val limit = timer(60)
                timerFired(limit)
                val notice = activity("Notify")
                activityCompleted(work)
                activityCompleted(notice)
                completed()
            },
        ),
        // Fetch and store beside each other; a notice nobody waits for; a child whose start is waited for;
        // another child beside a notice; a last fetch.
        "Handles" to linkedMapOf(
            "all" to history("Handles") {
                val fetch = activity("Fetch")
                val store = activity("Store")
                activityCompleted(fetch)
                activityCompleted(store)
                val told = activity("Notify")
                val errand = child("Errand")
                childStarted(errand)
                val other = child("Errand")
                val meanwhile = activity("Notify")
                childStarted(other)
                activityCompleted(told)
                activityCompleted(meanwhile)
                childCompleted(other)
                ran("Fetch")
                completed()
            },
        ),
        // Five minutes and a wait for the signal beside a fetch; then a minute and a store beside an hour's wait
        // and a notice; thirty seconds; a day nobody waits for.
        "Pauses" to linkedMapOf(
            // The signal comes early: the second wait finds its condition met and starts no timer.
            "released" to history("Pauses") {
                val pause = timer(300)
                val fetch = activity("Fetch")
                activityCompleted(fetch)
                signal("release")
                timerFired(pause)
                val minute = timer(60)
                val notice = activity("Notify")
                timerFired(minute)
                val store = activity("Store")
                activityCompleted(notice)
                activityCompleted(store)
                val nap = timer(30)
                timerFired(nap)
                timer(86400)
                completed()
            },
        ),
        // The promise waited for thirty seconds; if that is up, a notice and an hour more. A store and five
        // minutes more for a promise that is done by then; a fetch.
        "Patience" to linkedMapOf(
            "approved" to history("Patience") {
                val wait = timer(30)
                signal("approve")
                timerCanceled(wait)
                ran("Store")
                ran("Fetch")
                completed()
            },
            "late" to history("Patience") {
                val wait = timer(30)
                timerFired(wait)
                ran("Notify")
                val longer = timer(3600)
                signal("approve")
                timerCanceled(longer)
                ran("Store")
                ran("Fetch")
                completed()
            },
            // Nobody approves: the second wait gives up with a failure nothing catches, and the workflow gets no further.
            "expired" to history("Patience") {
                val wait = timer(30)
                timerFired(wait)
                ran("Notify")
                val longer = timer(3600)
                timerFired(longer)
                terminated()
            },
        ),
        // Four activities nobody waits for, one of them with a notice after it; a store that is waited for.
        "Dropped" to linkedMapOf(
            "all" to history("Dropped") {
                val notice = activity("Notify")
                val fetch = activity("Fetch")
                val store = activity("Store")
                val again = activity("Fetch")
                val inner = activity("Fetch")
                val kept = activity("Store")
                activityCompleted(notice)
                activityCompleted(fetch)
                activityCompleted(store)
                activityCompleted(again)
                activityCompleted(inner)
                val told = activity("Notify")
                activityCompleted(kept)
                activityCompleted(told)
                completed()
            },
        ),
        // put; offer for ten seconds, or fetch and return; put; offer for five seconds; store.
        "Buffer" to linkedMapOf(
            // There is room every time: nothing waits, and nothing is written.
            "room" to history("Buffer") {
                signal("add")
                ran("Store")
                completed()
            },
            // The queue stays full for the ten seconds.
            "full" to history("Buffer") {
                val wait = timer(10)
                timerFired(wait)
                ran("Fetch")
                completed()
            },
            // Both offers have to wait, and both times the signal that takes something out makes room.
            "drained" to history("Buffer") {
                val wait = timer(10)
                signal("drain")
                timerCanceled(wait)
                val second = timer(5)
                signal("drain")
                timerCanceled(second)
                ran("Store")
                completed()
            },
        ),
        // A queue and a promise in variables, fed by a handler for every signal.
        "Listener" to linkedMapOf(
            "fed" to history("Listener") {
                signal("item")
                ran("Store")
                signal("stop")
                completed()
            },
        ),
        // The lock; two lanes that take turns with one permit; the permit again and a notice. The update waits for
        // the lanes to be done and for the lock.
        "Permits" to linkedMapOf(
            "alone" to history("Permits") {
                ran("Fetch")
                ran("Store")
                ran("Notify")
                completed()
            },
            // The update comes while the workflow method holds the lock, and runs once that has let go.
            "update" to history("Permits") {
                val change = updateAccepted("change")
                ran("Fetch")
                ran("Store")
                val audit = activity("Audit")
                val notice = activity("Notify")
                activityCompleted(audit)
                updateCompleted(change)
                activityCompleted(notice)
                completed()
            },
        ),
        // A lane: fetch, the child, a notice. The flow waits for the child to have started; store; another lane
        // that nothing waits for.
        "Begun" to linkedMapOf(
            "all" to history("Begun") {
                ran("Fetch")
                val errand = child("Errand")
                childStarted(errand)
                val store = activity("Store")
                childCompleted(errand)
                val notice = activity("Notify")
                activityCompleted(store)
                activity("Fetch")
                activityCompleted(notice)
                completed()
            },
        ),
        // Five upserts around a notice; three of them write more than the code names.
        "Tags" to linkedMapOf(
            "all" to history("Tags") {
                searchAttributes("Stage", "Rank")
                searchAttributes("Stage", "Region", "Owner")
                memo("note", "team", "by")
                ran("Notify")
                memo("team")
                searchAttributes("Stage", "Rank")
                completed()
            },
        ),
    )
}
