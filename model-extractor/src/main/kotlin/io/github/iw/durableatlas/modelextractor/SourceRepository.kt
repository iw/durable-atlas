package io.github.iw.durableatlas.modelextractor

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The repository to analyse, reached only through git commands that read it. Nothing here writes
 * to the repository, its working tree or its `.git` directory.
 *
 * The directory handed to the tool may be a subdirectory of the git working tree; paths are then
 * relative to that subdirectory, which is where the Gradle build is expected.
 */
internal class SourceRepository private constructor(
    /** Top of the git working tree. */
    private val toplevel: Path,
    /** Path from [toplevel] to the directory to analyse, with `/` separators; empty if they coincide. */
    val subdirectory: String,
) {
    /** Resolves [revision] (a SHA, tag or branch) to the full SHA of a commit. */
    fun resolveCommit(revision: String): String {
        if (revision.isBlank() || revision.startsWith("-")) {
            throw ModelExtractionException("'$revision' is not a commit")
        }
        val result = git(toplevel, "rev-parse", "--verify", "--quiet", "$revision^{commit}")
        val sha = result.stdout.trim()
        if (result.exitCode != 0 || !SHA.matches(sha)) {
            throw ModelExtractionException("'$revision' is not a commit of the repository at $toplevel")
        }
        return sha
    }

    /** Every directory of the analysed tree at [commit], relative to it. */
    fun directories(commit: String): Set<String> =
        checked(git(toplevel, "ls-tree", "-r", "-d", "-z", "--name-only", treeish(commit)), "list directories")
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Copies [paths] (files or directories, relative to the analysed tree) as they are at [commit]
     * into [target], keeping their relative locations. [workDirectory] receives a temporary file.
     */
    fun export(commit: String, paths: List<String>, target: Path, workDirectory: Path) {
        Files.createDirectories(target)
        if (paths.isEmpty()) return
        val archive = Files.createTempFile(workDirectory, "source", ".zip")
        try {
            val archiveCommand = listOf("--literal-pathspecs", "archive", "--format=zip", "-0", "--output=$archive")
            checked(git(toplevel, *(archiveCommand + treeish(commit) + "--" + paths).toTypedArray()), "export sources")
            Zip.extract(archive, target)
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    /**
     * Clones the repository into [directory], which must not exist, and checks out [commit] there.
     * The clone borrows the objects of the original where git allows it, so nothing is copied and
     * nothing is written to the original. Returns the directory in the clone that corresponds to
     * the directory to analyse.
     */
    fun cloneAt(commit: String, directory: Path): Path {
        checked(
            git(toplevel, "clone", "--quiet", "--shared", "--no-checkout", toplevel.toString(), directory.toString()),
            "clone the repository",
        )
        checked(
            git(directory, "-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", commit),
            "check out $commit",
        )
        return if (subdirectory.isEmpty()) directory else directory.resolve(subdirectory)
    }

    private fun treeish(commit: String): String = if (subdirectory.isEmpty()) commit else "$commit:$subdirectory"

    companion object {
        private val SHA = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

        // Variables that would point git at another repository, for example when run from a hook.
        private val INHERITED_GIT_VARIABLES = setOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_COMMON_DIR",
            "GIT_PREFIX",
        )

        // Without optional locks, read-only commands do not refresh the index of the repository.
        private val GIT_ENVIRONMENT = mapOf("GIT_OPTIONAL_LOCKS" to "0", "GIT_TERMINAL_PROMPT" to "0")

        fun open(directory: Path): SourceRepository {
            if (!Files.isDirectory(directory)) throw ModelExtractionException("$directory is not a directory")
            val result = git(directory, "rev-parse", "--show-toplevel", "--show-prefix")
            if (result.exitCode != 0) {
                throw ModelExtractionException("$directory is not inside a git repository: ${result.stderr.trim()}")
            }
            val lines = result.stdout.lines()
            return SourceRepository(Path.of(lines[0]), lines.getOrElse(1) { "" }.trimEnd('/'))
        }

        private fun git(directory: Path, vararg arguments: String): ProcessOutput =
            try {
                Processes.run(listOf("git") + arguments, directory, GIT_ENVIRONMENT, INHERITED_GIT_VARIABLES)
            } catch (e: IOException) {
                throw ModelExtractionException("Could not run git: ${e.message}", e)
            }

        private fun checked(result: ProcessOutput, action: String): String {
            if (result.exitCode != 0) {
                throw ModelExtractionException("git failed to $action: ${result.stderr.trim()}")
            }
            return result.stdout
        }
    }
}
