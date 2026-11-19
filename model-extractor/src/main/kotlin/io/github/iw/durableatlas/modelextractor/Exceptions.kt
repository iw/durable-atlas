package io.github.iw.durableatlas.modelextractor

/** Thrown when no model can be produced: the repository or commit is unusable, or a step failed. */
open class ModelExtractionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown when the analysed repository's Gradle build could not be queried. Unlike other failures,
 * this one leaves the option of building a degraded model instead.
 */
class GradleExtractionException(message: String, cause: Throwable? = null) : ModelExtractionException(message, cause)
