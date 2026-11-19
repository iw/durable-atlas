package io.github.iw.durableatlas.modelextractor.testing

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.fail

/**
 * A throw-away git repository holding a copy of the sample project under `fixtures/`, for tests
 * that extract models. The location of the sample comes from the system property
 * `durable-atlas.fixtureDir`, which the build sets for test tasks.
 */
class SampleRepository private constructor(
    /** Top of the git working tree. */
    val root: Path,
    /** The directory that holds the sample project: [root], or a directory below it. */
    val project: Path,
) {
    /** SHA of the current commit. */
    val head: String get() = git("rev-parse", "HEAD").trim()

    /** Applies [change] to the sample project, commits everything and returns the new SHA. */
    fun commit(message: String, change: (project: Path) -> Unit): String {
        change(project)
        git("add", "--all")
        git("commit", "--quiet", "--message", message)
        return head
    }

    /** Uncommitted, untracked and ignored files, as `git status --porcelain --ignored` reports them. */
    fun status(): String = git("status", "--porcelain", "--ignored")

    /** Runs git with [arguments] at the top of the working tree and returns its output; fails the test if git does. */
    fun git(vararg arguments: String): String {
        val command = listOf("git") + IDENTITY + arguments
        val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.waitFor() != 0) fail("${command.joinToString(" ")} failed:\n$output")
        return output
    }

    companion object {
        private const val FIXTURE_PROPERTY = "durable-atlas.fixtureDir"

        // Build outputs of the sample project, if it has been built in place.
        private val SKIPPED_DIRECTORIES = setOf("build", ".gradle", ".kotlin")

        private val IDENTITY = listOf(
            "-c", "user.name=durable-atlas tests",
            "-c", "user.email=tests@example.invalid",
            "-c", "commit.gpgsign=false",
            "-c", "core.autocrlf=false",
        )

        /**
         * Creates a repository in [directory] with one commit that contains the sample project,
         * either at the top of the working tree or in [subdirectory] of it.
         */
        fun create(directory: Path, subdirectory: String? = null): SampleRepository {
            val fixture = Path.of(
                System.getProperty(FIXTURE_PROPERTY) ?: fail("System property $FIXTURE_PROPERTY is not set"),
            )
            return copyOf(fixture, directory, subdirectory)
        }

        /**
         * Creates a repository in [directory] with one commit that contains a copy of the project
         * at [source], either at the top of the working tree or in [subdirectory] of it.
         */
        fun copyOf(source: Path, directory: Path, subdirectory: String? = null): SampleRepository {
            val root = Files.createDirectories(directory).toRealPath()
            val project = if (subdirectory == null) root else root.resolve(subdirectory)
            copy(source, project)
            return SampleRepository(root, project).apply {
                git("init", "--quiet", "--initial-branch=main")
                // After a commit git may repack the repository in the background, changing .git while a test
                // compares it before and after an extraction. Set here rather than on the command line so
                // that it holds for every git command run in the repository, the extractor's included.
                git("config", "maintenance.auto", "false")
                git("config", "gc.auto", "0")
                commit("Add sample project") {}
            }
        }

        private fun copy(source: Path, target: Path) {
            Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != source && dir.fileName.toString() in SKIPPED_DIRECTORIES) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // Copying the attributes keeps the wrapper script executable.
                        val copy = target.resolve(source.relativize(file).toString())
                        Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }
}
