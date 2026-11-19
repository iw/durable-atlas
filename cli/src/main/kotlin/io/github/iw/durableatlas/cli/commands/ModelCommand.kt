package io.github.iw.durableatlas.cli.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import io.github.iw.durableatlas.cli.Completion
import io.github.iw.durableatlas.cli.StageCommand
import io.github.iw.durableatlas.cli.Stages
import io.github.iw.durableatlas.cli.Summaries
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.modelextractor.ModelRequest
import io.github.iw.durableatlas.modelextractor.ModelResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class ModelCommand : StageCommand(
    name = "model",
    summary = "Extract a manifest of source roots and dependencies for every Kotlin module of a repository.",
) {
    private val repo: Path by option("--repo", metavar = "path", help = "Repository to read. It is never modified.")
        .path(mustExist = true, canBeFile = false)
        .required()
    private val commit: String by option("--commit", metavar = "sha", help = "Commit to extract the model from.")
        .required()
    private val out: Path by option(
        "--out",
        metavar = "dir",
        help = "Directory to write to. The model goes to a subdirectory named after the commit.",
    )
        .path(canBeFile = false)
        .required()
    private val mode: ModelMode? by option(
        "--mode",
        help = "How to obtain the model. By default the repository's Gradle build is tried first, and a " +
            "degraded model is built (exit status 3) if that fails.",
    )
        .enum<ModelMode> { it.name.lowercase() }
    private val force: Boolean by option("--force", help = "Extract again even if a model for the commit exists.")
        .flag()
    private val keepScratch: Boolean by option("--keep-scratch", help = "Keep the scratch checkout afterwards.")
        .flag()

    override fun run() {
        val request = ModelRequest(
            repository = repo,
            commit = commit,
            out = out,
            mode = mode,
            force = force,
            keepScratch = keepScratch,
        )
        val result = attempt { Stages(::log).model(request) }

        if (json) echo(Summaries.format(summary(result))) else echo(describe(result))

        // Without an explicit mode the caller expected the Gradle model, so a degraded one is flagged.
        val degradedFallback = mode == null && result.mode == ModelMode.DEGRADED
        if (degradedFallback) log("completed in degraded mode")
        complete(Completion.status(degradedFallback, overThreshold = false))
    }

    private fun summary(result: ModelResult): JsonObject =
        buildJsonObject {
            put("command", commandName)
            put("commit", result.commit)
            put("mode", result.mode.name.lowercase())
            put("cached", result.cached)
            put("fallback", result.fallback)
            put("directory", result.directory.toString())
            put("modules", buildJsonArray { result.index.modules.forEach { add(it.path) } })
            put(
                "dropped",
                buildJsonArray {
                    result.index.dropped.forEach { module ->
                        add(
                            buildJsonObject {
                                put("path", module.path)
                                put("reason", module.reason)
                            },
                        )
                    }
                },
            )
            result.scratch?.let { put("scratch", it.toString()) }
        }

    private fun describe(result: ModelResult): String =
        buildString {
            append("${result.mode.name.lowercase()} model of ${result.commit}")
            if (result.cached) append(" (already extracted)")
            append(": ${result.index.modules.size} module(s) kept, ${result.index.dropped.size} left out\n")
            append(result.directory)
        }
}
