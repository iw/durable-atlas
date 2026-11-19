package io.github.iw.durableatlas.render

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/** Writes the files of a run that draws diagrams. */
object DiagramFiles {
    /**
     * Writes [text] to [file], creating its directory if needed, so that the file is never there
     * half written: the text goes to a file beside it first, which then takes its place in one
     * step. A run that is interrupted leaves [file] as it was, or complete. What it can leave
     * behind is the file beside it, [partOf]; the next run that writes [file] replaces it.
     */
    fun write(file: Path, text: String) {
        file.parent?.let(Files::createDirectories)
        val part = partOf(file)
        try {
            Files.write(part, text.toByteArray(Charsets.UTF_8))
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(part)
        }
    }

    /** The file that holds what is being written to [file] until all of it is there. */
    fun partOf(file: Path): Path = file.resolveSibling(".${file.name}.part")
}
