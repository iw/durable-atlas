package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.render.MissingDiagram
import io.github.iw.durableatlas.render.RenderSummary
import io.github.iw.durableatlas.render.RenderedDiagram
import io.github.iw.durableatlas.render.SimplifiedDiagram
import io.github.iw.durableatlas.render.limit.Limits
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/** Formats the machine-readable summary a command prints with `--json`. */
internal object Summaries {
    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun format(summary: JsonObject): String = format.encodeToString(JsonElement.serializer(), summary)

    /** The files written for one workflow, each with its format and its full path. */
    fun files(summary: RenderSummary, diagram: RenderedDiagram): JsonArray =
        buildJsonArray {
            diagram.files.forEach { link ->
                add(
                    buildJsonObject {
                        put("format", link.format)
                        put("file", summary.outDir.resolve(link.file).toString())
                    },
                )
            }
        }

    /**
     * A workflow as a table lists it.
     *
     * @param dynamic whether it is a class that runs for every workflow type without an
     *   implementation of its own, so that [type] is a name made up for it
     */
    class Listed(val type: String, val implementation: String, val dynamic: Boolean = false)

    /**
     * What a table for people calls each of [workflows], which are listed together: its type and,
     * in brackets, what the type alone does not say. Where another of them has the same type,
     * that is the class that implements it, as `Type (Outer.Impl)`: of the class's qualified
     * name only the end, as much as tells it from the others of its type. A dynamic workflow is
     * called one, as `dynamic-Impl (dynamic workflow)`, because its type is no registered name.
     */
    fun workflowNames(workflows: List<Listed>): List<String> {
        val implementations = workflows.groupBy({ it.type }, { it.implementation })
        return workflows.map { workflow ->
            val sharing = implementations.getValue(workflow.type)
            val said = listOfNotNull(
                distinctEnd(workflow.implementation, sharing).takeIf { sharing.size > 1 },
                "dynamic workflow".takeIf { workflow.dynamic },
            )
            if (said.isEmpty()) workflow.type else "${workflow.type} (${said.joinToString(", ")})"
        }
    }

    /** The shortest end of the qualified [name], in whole parts, that none of the [others] ends in as well. */
    private fun distinctEnd(name: String, others: List<String>): String {
        val parts = name.split('.')
        return (1..parts.size).asSequence()
            .map { parts.takeLast(it).joinToString(".") }
            .firstOrNull { end -> others.none { it != name && (it == end || it.endsWith(".$end")) } }
            ?: name
    }

    /**
     * What the diagrams of one workflow lack because drawing them took too long.
     *
     * @param name the workflow as the command names it to people
     */
    class Shortfall(val name: String, val missing: List<MissingDiagram>, val simplified: List<SimplifiedDiagram>)

    /**
     * The formats of a run as its sentences name them: each by its name, and one that some
     * workflows have no diagram in with how many have it, so that no sentence says more was
     * drawn than was. Where nothing is missing it is the names alone.
     */
    fun formatsDrawn(formats: List<String>, workflows: List<Shortfall>): String =
        formats.joinToString(", ") { format ->
            val without = workflows.count { workflow -> workflow.missing.any { it.format == format } }
            if (without == 0) format else "$format (${workflows.size - without} of ${workflows.size})"
        }

    /**
     * One line for people for each format and time limit that left workflows without a diagram,
     * and one for each that left them a simpler one, naming the workflows. Empty where every
     * diagram was drawn as asked.
     */
    fun shortfallLines(workflows: List<Shortfall>): List<String> {
        val missing = workflows.flatMap { workflow -> workflow.missing.map { it to workflow.name } }
            .groupBy({ it.first.format to it.first.limit }, { it.second })
            .map { (diagram, names) ->
                "${names.size} workflow(s) without ${diagram.first} diagram, which was not drawn within ${Limits.text(diagram.second)}: ${names.joinToString(", ")}"
            }
        val simplified = workflows.flatMap { workflow -> workflow.simplified.map { it to workflow.name } }
            .groupBy({ it.first.format to it.first.simplification.limit }, { "${it.second} (${it.first.simplification.level.words})" })
            .map { (diagram, names) ->
                "${names.size} workflow(s) with a simpler ${diagram.first} diagram, because the one asked for was not drawn within " +
                    "${Limits.text(diagram.second)}: ${names.joinToString(", ")}"
            }
        return missing + simplified
    }

    /**
     * The same for a machine, as keys of the object of the workflow: `notDrawn` and `simplified`,
     * each only where there is something to list, so that a workflow drawn as asked says nothing.
     */
    fun shortfall(json: JsonObjectBuilder, missing: List<MissingDiagram>, simplified: List<SimplifiedDiagram>) {
        if (missing.isNotEmpty()) {
            json.put(
                "notDrawn",
                buildJsonArray {
                    missing.forEach {
                        add(
                            buildJsonObject {
                                put("format", it.format)
                                put("limitSeconds", it.limit.toNanos() / NANOS)
                            },
                        )
                    }
                },
            )
        }
        if (simplified.isNotEmpty()) {
            json.put(
                "simplified",
                buildJsonArray {
                    simplified.forEach {
                        add(
                            buildJsonObject {
                                put("format", it.format)
                                put("how", it.simplification.level.key)
                                put("limitSeconds", it.simplification.limit.toNanos() / NANOS)
                            },
                        )
                    }
                },
            )
        }
    }

    private const val NANOS = 1e9

    /** What a command says of the calls that the overviews it made have no node for, where there are any. */
    fun leftOut(count: Int): String = "$count call(s) that could not be read left out of the overview (--detail full draws each as an unknown node)"

    /** A table for people: columns as wide as their widest cell, two spaces apart. */
    fun table(header: List<String>, rows: List<List<String>>, rightAligned: Set<Int> = emptySet()): String {
        val lines = listOf(header) + rows
        val widths = header.indices.map { column -> lines.maxOf { it[column].length } }
        return lines.joinToString("\n") { cells ->
            cells.mapIndexed { column, cell ->
                if (column in rightAligned) cell.padStart(widths[column]) else cell.padEnd(widths[column])
            }.joinToString("  ").trimEnd()
        }
    }

    /** [path] as every command prints it: absolute, without `.` and `..`. */
    fun shown(path: Path): Path = path.toAbsolutePath().normalize()
}
