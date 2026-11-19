package io.github.iw.durableatlas.modelextractor

import java.nio.file.Files
import java.nio.file.Path

/** A model read back from disk: its index, its manifests, and the means to locate their files. */
class ExtractedModel private constructor(
    /** The directory that holds the index. */
    val directory: Path,
    val index: ModelIndex,
    /** Manifests in the order of [ModelIndex.modules]. */
    val manifests: List<ModuleManifest>,
) {
    /** The root under which the paths in [ModuleManifest.sources] resolve. */
    val sourceRoot: Path = directory.resolve(ModelLayout.SOURCE_DIRECTORY)

    /** Absolute locations of the source directories of [manifest]. */
    fun sourceDirectories(manifest: ModuleManifest): List<Path> =
        manifest.sources.map { sourceRoot.resolve(it).normalize() }

    /** Absolute locations of the jars of [manifest], in classpath order. */
    fun jars(manifest: ModuleManifest): List<Path> =
        manifest.jars.map { jar ->
            val path = Path.of(jar)
            if (path.isAbsolute) path else sourceRoot.resolve(path).normalize()
        }

    companion object {
        /** Reads the model in [directory], which is the directory that holds `index.json`. */
        fun read(directory: Path): ExtractedModel {
            val indexFile = directory.resolve(ModelLayout.INDEX_FILE)
            if (!Files.isRegularFile(indexFile)) throw ModelFormatException("No model index at $indexFile")
            val index = ModelJson.decodeIndex(Files.readString(indexFile))
            val manifests = index.modules.map { module ->
                val file = directory.resolve(module.manifest)
                if (!Files.isRegularFile(file)) throw ModelFormatException("Missing module manifest $file")
                ModelJson.decodeManifest(Files.readString(file)).also {
                    if (it.path != module.path) {
                        throw ModelFormatException("$file describes ${it.path}, not ${module.path} as the index says")
                    }
                }
            }
            return ExtractedModel(directory, index, manifests)
        }
    }
}
