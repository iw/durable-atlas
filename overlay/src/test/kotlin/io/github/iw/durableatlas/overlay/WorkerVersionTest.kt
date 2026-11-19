package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Histories
import io.temporal.api.common.v1.WorkerVersionStamp
import io.temporal.api.deployment.v1.Deployment
import io.temporal.api.deployment.v1.WorkerDeploymentVersion
import io.temporal.api.enums.v1.VersioningBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which worker version a history is read against, from the fields a 1.32 server writes. */
class WorkerVersionTest {
    private val a = WorkerVersion("orders", "build-a")
    private val b = WorkerVersion("orders", "build-b")

    private fun read(history: Histories) = HistoryJson.of(history.build(), "h.json")

    @Test
    fun `the version is the one on the completed workflow tasks`() {
        val history = Histories().apply {
            workflowTask("orders", "build-a")
            ran("A")
            workflowTask("orders", "build-a")
            completed()
        }

        val read = read(history)

        assertEquals(listOf(a), read.workerVersions)
        assertEquals(a, read.workerVersion)
        assertEquals("orders:build-a", read.workerVersion.toString())
    }

    @Test
    fun `a history whose workers report no version has none`() {
        val read = read(Histories().apply { workflowTask(); ran("A"); workflowTask(); completed() })

        assertEquals(emptyList(), read.workerVersions)
        assertNull(read.workerVersion)

        // The histories of the sample repository come from a test environment, whose workers have no version.
        val saved = Fixtures.sampleHistory("OrderWorkflow")
        assertEquals(emptyList(), saved.workerVersions)
        assertNull(saved.workerVersion)
    }

    @Test
    fun `when the version changes during the run the last one decides, and all are listed in the order they came`() {
        val history = Histories().apply {
            workflowTask("orders", "build-a")
            ran("A")
            workflowTask("orders", "build-b")
            ran("B")
            workflowTask("orders", "build-a")
            ran("C")
            workflowTask("orders", "build-b")
            completed()
        }

        val read = read(history)

        assertEquals(listOf(a, b), read.workerVersions)
        assertEquals(b, read.workerVersion)
    }

    @Test
    fun `a last workflow task by a worker without a version leaves the history without one`() {
        val read = read(Histories().apply { workflowTask("orders", "build-a"); ran("A"); workflowTask(); completed() })

        assertEquals(listOf(a), read.workerVersions)
        assertNull(read.workerVersion)
    }

    @Test
    fun `the deployment is taken from the name next to the version if the version does not say it`() {
        val history = Histories().apply {
            val task = workflowTask()
            change(task) {
                workflowTaskCompletedEventAttributesBuilder
                    .setWorkerDeploymentName("orders")
                    .setVersioningBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
                    .setDeploymentVersion(WorkerDeploymentVersion.newBuilder().setBuildId("build-a"))
            }
        }

        assertEquals(a, read(history).workerVersion)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `where a task has no deployment version, the build id a worker names with setBuildId stands in`() {
        // As a 1.32 server records a worker that sets nothing but WorkerOptions.setBuildId.
        val alone = Histories().apply {
            val task = workflowTask()
            change(task) { workflowTaskCompletedEventAttributesBuilder.setWorkerVersion(WorkerVersionStamp.newBuilder().setBuildId("build-a")) }
            ran("A")
            val last = workflowTask()
            change(last) { workflowTaskCompletedEventAttributesBuilder.setWorkerVersion(WorkerVersionStamp.newBuilder().setBuildId("build-b")) }
            completed()
        }
        val read = read(alone)
        assertEquals(listOf(WorkerVersion(null, "build-a"), WorkerVersion(null, "build-b")), read.workerVersions)
        assertEquals(WorkerVersion(null, "build-b"), read.workerVersion)
        assertEquals("build-b", read.workerVersion.toString())

        // The deployment the task names goes with it.
        val named = Histories().apply {
            val task = workflowTask()
            change(task) {
                workflowTaskCompletedEventAttributesBuilder.setWorkerDeploymentName("orders").setWorkerVersion(WorkerVersionStamp.newBuilder().setBuildId("build-a"))
            }
        }
        assertEquals(a, read(named).workerVersion)

        // A deployment version goes before the stamp.
        val both = Histories().apply {
            val task = workflowTask("orders", "build-b")
            change(task) { workflowTaskCompletedEventAttributesBuilder.setWorkerVersion(WorkerVersionStamp.newBuilder().setBuildId("build-a")) }
        }
        assertEquals(listOf(b), read(both).workerVersions)

        // A stamp without a build id, as a worker without any options leaves it, is no version.
        val empty = Histories().apply {
            val task = workflowTask()
            change(task) { workflowTaskCompletedEventAttributesBuilder.setWorkerVersion(WorkerVersionStamp.getDefaultInstance()) }
        }
        assertNull(read(empty).workerVersion)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the other older ways of naming a build are not read`() {
        val history = Histories().apply {
            val task = workflowTask()
            change(task) {
                workflowTaskCompletedEventAttributesBuilder
                    .setBinaryChecksum("build-a")
                    .setDeployment(Deployment.newBuilder().setSeriesName("orders").setBuildId("build-a"))
                    .setWorkerDeploymentVersion("orders.build-a")
            }
            started { setInheritedBuildId("build-a").setParentPinnedWorkerDeploymentVersion("orders.build-a") }
        }

        val read = read(history)

        assertEquals(emptyList(), read.workerVersions)
        assertNull(read.workerVersion)
    }

    @Test
    fun `before the first workflow task the version the execution is pinned to stands in`() {
        val pinned = Histories().started {
            versioningOverrideBuilder.pinnedBuilder.setVersion(WorkerDeploymentVersion.newBuilder().setDeploymentName("orders").setBuildId("build-b"))
            setInheritedPinnedVersion(WorkerDeploymentVersion.newBuilder().setDeploymentName("orders").setBuildId("build-a"))
        }
        assertEquals(b, read(pinned).workerVersion)
        assertEquals(listOf(b), read(pinned).workerVersions)

        val inherited = Histories().started {
            setInheritedPinnedVersion(WorkerDeploymentVersion.newBuilder().setDeploymentName("orders").setBuildId("build-a"))
        }
        assertEquals(a, read(inherited).workerVersion)

        // Once a workflow task is completed, the worker that completed it is what counts.
        val ran = Histories().started {
            setInheritedPinnedVersion(WorkerDeploymentVersion.newBuilder().setDeploymentName("orders").setBuildId("build-a"))
        }.apply { workflowTask() }
        assertNull(read(ran).workerVersion)

        assertNull(read(Histories()).workerVersion)
    }
}
