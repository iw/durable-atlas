package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.iw.durableatlas.cli.commands.AnalyseCommand
import io.github.iw.durableatlas.cli.commands.FetchCommand
import io.github.iw.durableatlas.cli.commands.ModelCommand
import io.github.iw.durableatlas.cli.commands.OverlayCommand
import io.github.iw.durableatlas.cli.commands.RenderCommand
import io.github.iw.durableatlas.cli.commands.RunCommand

/** Root command. It does nothing itself; each stage of the pipeline is a subcommand. */
class DurableAtlas : CliktCommand(name = "durable-atlas") {
    override val printHelpOnEmptyArgs: Boolean = true

    override fun help(context: Context): String =
        "Draws diagrams of the Temporal workflows in a Kotlin repository, using static analysis."

    override fun run() = Unit
}

/**
 * Builds the complete command tree.
 *
 * @param environment the environment variables `fetch` reads its connection settings from
 */
fun durableAtlas(environment: Map<String, String> = System.getenv()): CliktCommand =
    DurableAtlas().subcommands(
        ModelCommand(),
        AnalyseCommand(),
        RenderCommand(),
        OverlayCommand(),
        FetchCommand(environment),
        RunCommand(),
    )
