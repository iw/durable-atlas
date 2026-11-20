package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * What the analyser needs to know about one module: where its sources are, which sibling modules
 * it sees, and which jars are on its compile classpath.
 *
 * Property order is the key order of the JSON form.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ModuleManifest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Full SHA of the commit the module was read from. */
    val commit: String,
    /** Gradle path of the module, for example `:orders:workflows`. */
    val path: String,
    /**
     * Source directories, relative to the root of the analysed repository, with `/` separators and
     * sorted. A copy of each directory is kept under [ModelLayout.SOURCE_DIRECTORY] of the model.
     */
    val sources: List<String>,
    /** Paths of the other modules on this module's compile classpath, sorted. */
    val projectDeps: List<String>,
    /**
     * Jars on the compile classpath, in classpath order. An absolute path points outside the
     * analysed repository; a relative one names a file of the repository, copied to the model in
     * the same way as [sources].
     */
    val jars: List<String>,
    val mode: ModelMode,
    /**
     * The configuration files of the module's main resources ([CONFIGURATION_FILES]), relative to
     * the root of the analysed repository, with `/` separators and sorted. A copy of each is kept
     * under [ModelLayout.SOURCE_DIRECTORY] of the model, as for [sources]. A model made by an
     * earlier version of the tool has none.
     */
    val resources: List<String> = emptyList(),
) {
    companion object {
        /** Version of the JSON format written by this module. */
        const val SCHEMA_VERSION: Int = 1

        /**
         * The names of the files of a resource directory that a model keeps: those a service
         * reads its configuration from, in the order their values count, the first first.
         */
        val CONFIGURATION_FILES: List<String> = listOf("application.yaml", "application.yml", "application.properties")
    }
}
