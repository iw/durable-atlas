package io.github.iw.durableatlas.modelextractor

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

internal object Zip {
    /** Unpacks [archive] into [target]. Entries that would land outside [target] are refused. */
    fun extract(archive: Path, target: Path) {
        val root = target.toAbsolutePath().normalize()
        ZipFile(archive.toFile()).use { zip ->
            for (entry in zip.entries()) {
                val file = root.resolve(entry.name).normalize()
                if (!file.startsWith(root)) {
                    throw ModelExtractionException("Archive entry ${entry.name} escapes $target")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(file)
                } else {
                    Files.createDirectories(file.parent)
                    zip.getInputStream(entry).use { Files.copy(it, file) }
                }
            }
        }
    }
}
