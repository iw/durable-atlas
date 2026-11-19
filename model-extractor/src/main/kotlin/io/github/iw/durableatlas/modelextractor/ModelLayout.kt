package io.github.iw.durableatlas.modelextractor

import java.nio.file.Path

/**
 * Where the files of a model live. Every model has a directory of its own, named after the commit
 * it was extracted from, inside the output directory given to the extractor:
 *
 * ```
 * <out>/<sha>/index.json            the ModelIndex
 * <out>/<sha>/modules/<name>.json   one ModuleManifest per kept module
 * <out>/<sha>/source/...            the kept modules' source directories, as of the commit
 * ```
 */
object ModelLayout {
    const val INDEX_FILE: String = "index.json"
    const val MODULES_DIRECTORY: String = "modules"
    const val SOURCE_DIRECTORY: String = "source"

    /** Directory of the model for [commit] (a full SHA) inside the output directory [out]. */
    fun modelDirectory(out: Path, commit: String): Path = out.resolve(commit)

    /**
     * Manifest file names for [paths], relative to the model directory. A name is the module path
     * with `:` replaced by `_`; if two paths collapse to the same name, the later one in sort order
     * gets a numeric suffix.
     */
    fun manifestNames(paths: Collection<String>): Map<String, String> {
        val taken = mutableSetOf<String>()
        return paths.sorted().associateWith { path ->
            val base = path.replace(':', '_')
            var name = base
            var suffix = 2
            while (!taken.add(name)) name = "$base-${suffix++}"
            "$MODULES_DIRECTORY/$name.json"
        }
    }
}
