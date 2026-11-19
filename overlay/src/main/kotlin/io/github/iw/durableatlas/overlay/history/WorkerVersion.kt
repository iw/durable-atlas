package io.github.iw.durableatlas.overlay.history

import io.temporal.api.deployment.v1.WorkerDeploymentVersion
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes

/**
 * The version of a worker: a Worker Deployment Version, the build of a worker and the deployment
 * it belongs to, as a worker reports them to the server when it is given both. A worker that only
 * names its build (`WorkerOptions.setBuildId`) has no deployment.
 *
 * @param deployment the Worker Deployment; `null` for a worker that reported a build id alone
 */
data class WorkerVersion(val deployment: String?, val buildId: String) {
    /** The version as Temporal's own search attributes write it, or the build id alone. */
    override fun toString(): String = deployment?.let { "$it:$buildId" } ?: buildId
}

/**
 * Reads the versions of the workers that ran an execution from its history.
 *
 * A server records the Worker Deployment Version of the worker that completes a workflow task on
 * that task's completion (`deploymentVersion`, with `workerDeploymentName`), for workers that
 * take part in worker versioning. Where a task has no such version, the build id of the
 * `workerVersion` stamp stands in: it is the only place a server records the build id of a worker
 * that names its build with `WorkerOptions.setBuildId` and does not take part. A worker that names
 * its deployment without taking part leaves `workerDeploymentName` alone, which says nothing about
 * its build. The other older ways of naming a build (`binaryChecksum`, `deployment` and the
 * `workerDeploymentVersion` text) are not read: a worker that sets nothing but those counts as a
 * worker without a version.
 */
internal object WorkerVersions {
    /**
     * @param all the versions that completed workflow tasks, in the order they first did
     * @param deciding the version whose code the history is read against, see [of]
     */
    class Found(val all: List<WorkerVersion>, val deciding: WorkerVersion?)

    /**
     * The deciding version is that of the worker that completed the last workflow task. To
     * complete it, that worker replayed the whole history, so its code agrees with every event,
     * also with those written when workers of an earlier version ran the execution. It is `null`
     * if that worker reported no version.
     *
     * A history without a completed workflow task has run no code yet. The version it is bound
     * to run on, where the first event says so (a pinned versioning override, or the pinned
     * version inherited from a parent or an earlier run), stands in for it.
     */
    fun of(history: History): Found {
        val all = LinkedHashSet<WorkerVersion>()
        var last: WorkerVersion? = null
        var completed = false
        for (event in history.eventsList) {
            if (event.eventType != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) continue
            val attributes = event.workflowTaskCompletedEventAttributes
            completed = true
            last = (if (attributes.hasDeploymentVersion()) version(attributes.deploymentVersion, attributes.workerDeploymentName) else null)
                ?: stamped(attributes)
            last?.let(all::add)
        }
        if (completed) return Found(all.toList(), last)

        val started = history.eventsList.firstOrNull()?.takeIf { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED }
            ?.workflowExecutionStartedEventAttributes
        val bound = when {
            started == null -> null
            started.versioningOverride.pinned.hasVersion() -> version(started.versioningOverride.pinned.version, "")
            started.hasInheritedPinnedVersion() -> version(started.inheritedPinnedVersion, "")
            else -> null
        }
        return Found(listOfNotNull(bound), bound)
    }

    /** The build id of the `workerVersion` stamp, with the deployment the task names if it names one. */
    @Suppress("DEPRECATION")
    private fun stamped(attributes: WorkflowTaskCompletedEventAttributes): WorkerVersion? {
        val buildId = attributes.workerVersion.buildId
        return if (buildId.isEmpty()) null else WorkerVersion(attributes.workerDeploymentName.ifEmpty { null }, buildId)
    }

    /** @param deploymentName the deployment as the event names it next to the version, used if the version itself does not */
    private fun version(version: WorkerDeploymentVersion, deploymentName: String): WorkerVersion? {
        val deployment = version.deploymentName.ifEmpty { deploymentName }
        return if (version.buildId.isEmpty() || deployment.isEmpty()) null else WorkerVersion(deployment, version.buildId)
    }
}
