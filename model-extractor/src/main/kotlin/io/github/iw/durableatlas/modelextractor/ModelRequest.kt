package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import java.nio.file.Path

/** What to extract a model from, and where to put it. */
data class ModelRequest(
    /** The repository to analyse: a git working tree, or a directory inside one. It is only read. */
    val repository: Path,
    /** The commit to analyse: a SHA, or anything else git resolves to a commit. */
    val commit: String,
    /** Output directory. The model goes to a subdirectory named after the commit's full SHA. */
    val out: Path,
    /**
     * How to obtain the model. With `null`, the repository's Gradle build is tried first and a
     * degraded model is built if that fails.
     */
    val mode: ModelMode? = null,
    /** Extract again even if a model for the commit exists. */
    val force: Boolean = false,
    /** Leave the scratch directory, with its checkout of the commit, in place afterwards. */
    val keepScratch: Boolean = false,
    /** Where to create the scratch directory; the system's temporary directory if `null`. */
    val scratchParent: Path? = null,
)

/** A model on disk, and how it came about. */
class ModelResult(
    /** Full SHA of the commit. */
    val commit: String,
    val mode: ModelMode,
    /** The model's directory; see [ModelLayout]. */
    val directory: Path,
    val index: ModelIndex,
    /** True if a model for the commit already existed and nothing was extracted. */
    val cached: Boolean,
    /** Why the Gradle build could not be used, if a degraded model was built in its place. */
    val gradleFailure: String?,
    /** Every task the Gradle build scheduled, or `null` if Gradle was not run or failed. */
    val gradleTasks: List<String>?,
    /** The scratch directory, if it was kept. */
    val scratch: Path?,
) {
    /** True if the Gradle build was tried and a degraded model was built because it failed. */
    val fallback: Boolean get() = gradleFailure != null
}
