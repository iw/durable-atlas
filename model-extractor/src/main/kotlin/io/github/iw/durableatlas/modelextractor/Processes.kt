package io.github.iw.durableatlas.modelextractor

import java.nio.file.Path
import kotlin.concurrent.thread

internal class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs external programs to completion. */
internal object Processes {
    /**
     * Runs [command] in [directory] and waits for it. With [onLine], standard output and standard
     * error are merged and passed on line by line as they appear, and [ProcessOutput.stdout] is
     * empty; without it, both are captured.
     */
    fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String> = emptyMap(),
        unsetEnvironment: Set<String> = emptySet(),
        onLine: ((String) -> Unit)? = null,
    ): ProcessOutput {
        val builder = ProcessBuilder(command).directory(directory.toFile())
        builder.environment().keys.removeAll(unsetEnvironment)
        builder.environment().putAll(environment)
        if (onLine != null) builder.redirectErrorStream(true)

        val process = builder.start()
        // The programs run here take no input; an open pipe would let them wait for some.
        process.outputStream.close()

        var stderr = ""
        val stderrReader = if (onLine == null) {
            thread(name = "stderr-reader") { stderr = process.errorStream.readBytes().toString(Charsets.UTF_8) }
        } else {
            null
        }
        val stdout = if (onLine != null) {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            ""
        } else {
            process.inputStream.readBytes().toString(Charsets.UTF_8)
        }
        val exitCode = process.waitFor()
        stderrReader?.join()
        return ProcessOutput(exitCode, stdout, stderr)
    }
}
