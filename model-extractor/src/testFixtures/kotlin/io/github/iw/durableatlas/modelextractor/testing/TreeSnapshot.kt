package io.github.iw.durableatlas.modelextractor.testing

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.SortedMap

/** Captures directory trees so that tests can tell whether anything in them changed. */
object TreeSnapshot {
    /**
     * [root] and every file and directory below it, hidden ones included, with its kind, size and
     * modification time. Two equal snapshots mean nothing was added, removed or rewritten; a
     * directory's modification time also gives away files that were created and deleted again.
     */
    fun metadata(root: Path): SortedMap<String, String> =
        walk(root) { path ->
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            when {
                attributes.isDirectory -> "directory modified=${attributes.lastModifiedTime().toMillis()}"
                else -> "file size=${attributes.size()} modified=${attributes.lastModifiedTime().toMillis()}"
            }
        }

    /** Every file below [root] with a digest of its bytes, and every directory. */
    fun contents(root: Path): SortedMap<String, String> =
        walk(root) { path ->
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "directory"
            } else {
                val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                digest.joinToString("") { "%02x".format(it) }
            }
        }

    private fun walk(root: Path, describe: (Path) -> String): SortedMap<String, String> =
        Files.walk(root).use { paths ->
            paths.map { root.relativize(it).joinToString("/").ifEmpty { "." } to describe(it) }
                .toList()
                .toMap()
                .toSortedMap()
        }
}
