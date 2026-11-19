package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Table of contents of a model: the modules it describes, and the modules of the repository that
 * were left out and why.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ModelIndex(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Full SHA of the commit the model was extracted from. */
    val commit: String,
    val mode: ModelMode,
    /** Modules with a manifest, sorted by path. */
    val modules: List<IndexedModule>,
    /** Modules without a manifest, sorted by path. */
    val dropped: List<DroppedModule>,
) {
    companion object {
        /** Version of the JSON format written by this module. */
        const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class IndexedModule(
    val path: String,
    /** Location of the module's manifest, relative to the directory of the index. */
    val manifest: String,
)

@Serializable
data class DroppedModule(
    val path: String,
    /** One of the [DropReasons]. */
    val reason: String,
)

/** Why a module of the repository is not part of a model. */
object DropReasons {
    /** The module does not use `io.temporal`, and no module that does depends on it. */
    const val NO_TEMPORAL_USAGE: String = "no-temporal-usage"

    /** A kept module depends on it, but it has no Kotlin or Java source set to describe. */
    const val NOT_A_JVM_MODULE: String = "not-a-jvm-module"
}
