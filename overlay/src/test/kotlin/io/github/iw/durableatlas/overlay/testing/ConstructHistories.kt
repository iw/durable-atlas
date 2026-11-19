package io.github.iw.durableatlas.overlay.testing

import io.temporal.api.history.v1.History

/**
 * Histories as executions of the workflows of [Fixtures.constructIr] would leave them, one for
 * each way through: by workflow type, then by a name for the way.
 *
 * The workflows take steps while their object is made, register handlers as they run, test what
 * an await waited for, compensate side by side and retry what fails.
 */
object ConstructHistories {
    private fun history(workflowType: String, events: Histories.() -> Unit): History = Histories(workflowType).apply(events).build()

    val all: Map<String, Map<String, History>> = linkedMapOf(
        // Made with: prepare; check; if (!checked) return; report. Then work.
        "InitSteps" to linkedMapOf(
            "reported" to history("InitSteps") { ran("Prepare"); ran("Check"); ran("Report"); ran("Work"); completed() },
            "returned" to history("InitSteps") { ran("Prepare"); ran("Check"); ran("Work"); completed() },
        ),
        // Made with: prepare, by the superclass; a lazy value that nothing asks for; a timer. Then work.
        "BaseSteps" to linkedMapOf(
            "not-asked" to history("BaseSteps") { ran("Prepare"); timerFired(timer(1)); ran("Work"); completed() },
        ),
        // Which constructor runs is not known: its step has no node.
        "TwoConstructors" to linkedMapOf(
            "plain" to history("TwoConstructors") { ran("Work"); completed() },
        ),
        "InitStubs" to linkedMapOf(
            "plain" to history("InitStubs") { ran("Prepare"); ran("Work"); completed() },
        ),
        // Waits for a note, which any signal brings. The history names the workflow type that was started.
        "dynamic-Fallback" to linkedMapOf(
            "noted" to history("Greeting") { signal("anything"); ran("Handle"); completed() },
        ),
        // Handlers of an object and one for every update, registered before an await.
        "Listeners" to linkedMapOf(
            "paused-and-resumed" to history("Listeners") {
                signal("pause")
                val resumed = updateAccepted("resume")
                ran("Prepare")
                updateCompleted(resumed)
                ran("Work")
                completed()
            },
            "another-update" to history("Listeners") {
                val other = updateAccepted("recount")
                ran("Report")
                updateCompleted(other)
                ran("Work")
                completed()
            },
        ),
        // while (true) { await { queue or done }; if (queue empty and done) return; work }
        "QueueLoop" to linkedMapOf(
            "two-then-finish" to history("QueueLoop") { signal("add"); ran("Work"); signal("add"); ran("Work"); signal("finish"); completed() },
            "finished-at-once" to history("QueueLoop") { signal("finish"); completed() },
        ),
        // await(1 hour) { approved or stopped }; in time: stopped -> clean up, else work.
        "FirstWins" to linkedMapOf(
            "stopped" to history("FirstWins") { val wait = timer(3600); signal("stop"); timerCanceled(wait); ran("CleanUp"); completed() },
            "approved" to history("FirstWins") { val wait = timer(3600); signal("approve"); timerCanceled(wait); ran("Work"); completed() },
            "timed-out" to history("FirstWins") { timerFired(timer(3600)); completed() },
        ),
        // await { approved or stopped }; when { stopped and not approved -> clean up; else -> work }
        "WhenReasons" to linkedMapOf(
            "stopped" to history("WhenReasons") { signal("stop"); ran("CleanUp"); completed() },
            "approved" to history("WhenReasons") { signal("approve"); ran("Work"); completed() },
        ),
        // prepare; work; check, each after registering a compensation. On a failure all that are registered run at once.
        "SideBySide" to linkedMapOf(
            "fine" to history("SideBySide") { ran("Prepare"); ran("Work"); ran("Check"); completed() },
            "all-undone" to history("SideBySide") {
                ran("Prepare")
                ran("Work")
                activityFailed(activity("Check"))
                val first = activity("CleanUp")
                val second = activity("Report")
                val third = activity("Release")
                activityCompleted(third)
                activityCompleted(second)
                activityCompleted(first)
                ran("Undo")
                failed()
            },
            "first-undone" to history("SideBySide") { activityFailed(activity("Prepare")); ran("CleanUp"); failed() },
        ),
        // Two sagas that compensate in turn, one after the other.
        "InTurn" to linkedMapOf(
            "fine" to history("InTurn") { ran("Prepare"); ran("Work"); completed() },
            "undone" to history("InTurn") {
                ran("Prepare")
                activityFailed(activity("Work"))
                ran("CleanUp"); ran("CleanUp")
                ran("Report"); ran("Report")
                completed()
            },
        ),
        // retry { prepare; work }; retry { check }; if (fine) report.
        "Retries" to linkedMapOf(
            "at-once" to history("Retries") { ran("Prepare"); ran("Work"); ran("Check"); ran("Report"); completed() },
            "retried" to history("Retries") {
                ran("Prepare")
                activityFailed(activity("Work"))
                ran("Prepare")
                ran("Work")
                activityFailed(activity("Check"))
                ran("Check")
                completed()
            },
        ),
    )
}
