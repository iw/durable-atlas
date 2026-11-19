package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/**
 * Base of every subcommand. Standard output is reserved for the command's result, so anything
 * meant for a person watching the run goes through [log] to standard error.
 */
abstract class StageCommand(name: String, private val summary: String) : CliktCommand(name = name) {
    protected val json: Boolean by option("--json", help = "Print a machine-readable summary on stdout.").flag()

    override fun help(context: Context): String = summary

    protected fun log(message: String) {
        echo("$commandName: $message", err = true)
    }

    /** Says why the command cannot go on and ends it as a failure. Nothing has been printed on stdout by then. */
    protected fun fail(message: String?): Nothing {
        log("failed: ${message ?: "unknown error"}")
        throw ProgramResult(ExitCode.FAILURE)
    }

    /** Runs [stage], and ends the command as a failure if the stage gives up. */
    protected fun <T> attempt(stage: () -> T): T =
        try {
            stage()
        } catch (e: StageFailure) {
            fail(e.message)
        }

    /** Ends the command with the status of a run that got to its end, see [Completion]. */
    protected fun complete(status: Int) {
        if (status != ExitCode.SUCCESS) throw ProgramResult(status)
    }

    protected fun notImplemented(): Nothing {
        log("not implemented yet")
        throw ProgramResult(ExitCode.FAILURE)
    }
}
