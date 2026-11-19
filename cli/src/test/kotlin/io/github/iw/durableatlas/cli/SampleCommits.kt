package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import java.nio.file.Files

/** Further commits of the sample repository, for tests that need more than one. */
object SampleCommits {
    /** The workflow type the commit of [withoutTimerWorkflow] no longer has. */
    const val REMOVED_WORKFLOW_TYPE: String = "TimerWorkflow"

    private const val SOURCES = "workflows/src/main/kotlin/com/example/fixture/workflows"

    /**
     * Commits the removal of the workflow type [REMOVED_WORKFLOW_TYPE]: its file, and the lines
     * that register and start it. Everything else stays as it is.
     *
     * @return the SHA of the new commit
     */
    fun withoutTimerWorkflow(repository: SampleRepository): String =
        repository.commit("Remove $REMOVED_WORKFLOW_TYPE") { project ->
            val sources = project.resolve(SOURCES)
            Files.delete(sources.resolve("primitives/$REMOVED_WORKFLOW_TYPE.kt"))
            for (name in listOf("worker/FixtureWorkers.kt", "worker/ServerRun.kt")) {
                val file = sources.resolve(name)
                val lines = Files.readAllLines(file)
                val kept = lines.filterNot { REMOVED_WORKFLOW_TYPE in it }
                check(kept.size < lines.size) { "$name does not mention $REMOVED_WORKFLOW_TYPE" }
                Files.write(file, kept)
            }
        }
}
