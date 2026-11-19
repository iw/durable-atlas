package io.github.iw.durableatlas.overlay.version

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Thrown when a version map names a commit by the start of its name and several directories of
 * workflow documents begin with it.
 *
 * @param commit the commit as the map writes it
 * @param candidates the names of the directories that begin with it, in order
 */
class AmbiguousCommitException(
    val commit: String,
    val candidates: List<String>,
    message: String,
) : IllegalArgumentException(message)

/**
 * The directories that hold the workflow documents of the commits a version map names.
 *
 * A map may write a commit in full or abbreviate it, as git does with the first characters of a
 * SHA. The directory of a commit is the one called exactly what the map writes; if there is none,
 * and what the map writes looks like an abbreviated SHA (four or more lowercase hexadecimal
 * digits), it is the only directory whose name begins with that. Other names are only ever taken
 * in full, so a directory named `v1` is never found among `v10` and `v11`.
 */
class CommitDirectories private constructor(private val directories: Map<String, String>) {
    /** The name of the directory of [commit], given as the map writes it; `null` if no directory is its own. */
    fun of(commit: String): String? = directories[commit]

    companion object {
        /**
         * Looks in [irDir] for the directory of every commit [versions] names, whether a history
         * is sent there or not: a commit that could be several directories is a mistake in the
         * map, whichever histories are at hand.
         *
         * @throws AmbiguousCommitException if several directories begin with what the map writes
         *   for a commit and none is called exactly that
         */
        fun find(irDir: Path, versions: VersionMap): CommitDirectories {
            val commits = (versions.versions.map { it.commit } + listOfNotNull(versions.unversioned)).distinct()
            val present: List<String> by lazy { directoriesOf(irDir) }
            val found = LinkedHashMap<String, String>()
            for (commit in commits) {
                if (irDir.resolve(commit).isDirectory()) {
                    found[commit] = commit
                    continue
                }
                if (!ABBREVIATED_SHA.matches(commit)) continue
                val candidates = present.filter { it.startsWith(commit) }
                when (candidates.size) {
                    0 -> Unit
                    1 -> found[commit] = candidates.single()
                    else -> throw AmbiguousCommitException(
                        commit,
                        candidates,
                        "the version map names commit $commit, which several directories in $irDir begin with: " +
                            "${candidates.joinToString(", ")}; the map has to write more of the commit",
                    )
                }
            }
            return CommitDirectories(found)
        }

        /** What git writes for a commit at its shortest. */
        private val ABBREVIATED_SHA = Regex("[0-9a-f]{4,}")

        private fun directoriesOf(irDir: Path): List<String> {
            if (!irDir.isDirectory()) return emptyList()
            return Files.list(irDir).use { entries -> entries.filter { it.isDirectory() }.map { it.name }.toList() }.sorted()
        }
    }
}
