package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrFormatException
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.render.layout.LayoutBuilder
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Chooses the file names of a workflow's diagrams. */
fun interface DiagramFileNames {
    /**
     * @param irFile file name of the document, without directories
     * @return the name of the workflow's diagram files without an extension; each format adds its
     *   own. It must be unique among the workflows, also when upper and lower case are not told
     *   apart, and must not be the name of the index
     */
    fun nameFor(irFile: String, document: IrDocument): String

    companion object {
        /** The document's file name without `.ir.json` or `.json`. */
        val SAME_AS_DOCUMENT: DiagramFileNames = DiagramFileNames { irFile, _ ->
            irFile.removeSuffix(".json").removeSuffix(".ir")
        }
    }
}

/**
 * @param formats the formats to write, each to its own file per workflow; the index links them
 *   in this order
 * @param maxNodes see [RenderOptions.maxNodes]
 * @param fileNames how diagram files are named; by default after the documents they come from
 * @param progress told of each workflow as it is drawn
 */
data class DirectoryOptions(
    val formats: List<DiagramFormat> = DiagramFormats.all,
    val maxNodes: Int = RenderOptions.DEFAULT_MAX_NODES,
    val fileNames: DiagramFileNames = DiagramFileNames.SAME_AS_DOCUMENT,
    val progress: DrawingProgress = DrawingProgress.NONE,
) {
    init {
        require(formats.isNotEmpty()) { "At least one format is needed" }
        require(formats.map { it.extension.lowercase() }.distinct().size == formats.size) {
            "Formats must write files with different extensions: ${formats.joinToString { "${it.name} (.${it.extension})" }}"
        }
    }
}

/** What [DiagramDirectory.render] wrote. */
data class RenderSummary(
    val outDir: Path,
    /** File name of the index page inside [outDir]. */
    val index: String,
    /** One entry per workflow, ordered by workflow type and then file name. */
    val diagrams: List<RenderedDiagram>,
    /** JSON files in the input directory that are not workflow documents, by file name. */
    val skipped: List<String>,
)

data class RenderedDiagram(
    val workflowType: String,
    val implementation: String,
    val modelMode: ModelMode,
    /** File name of the document inside the input directory. */
    val irFile: String,
    /** The files written for this workflow inside the output directory, one per format it was drawn in. */
    val files: List<DiagramLink>,
    val nodes: Int,
    val unknown: Int,
    /**
     * Regions drawn as single nodes in at least one format, because the workflow is over the
     * node limit or because drawing it in full took too long (see [simplified]).
     */
    val collapsedRegions: Int,
    /** Whether the workflow is a class that runs for every workflow type without an implementation of its own. */
    val dynamic: Boolean = false,
    /** How many calls that did not resolve the document left out as calls into libraries; see `IrDocument.skippedCalls`. */
    val skippedCalls: Int = 0,
    /** How many calls the document has no node for because it is an overview; see `IrDocument.leftOutCalls`. */
    val leftOutCalls: Int = 0,
    /** The formats the workflow has no file in, because no diagram of it was drawn in the time the format allows. */
    val missing: List<MissingDiagram> = emptyList(),
    /** The formats whose file shows the workflow more simply than was asked for, because drawing it as asked took too long. */
    val simplified: List<SimplifiedDiagram> = emptyList(),
)

/** Renders a directory of workflow documents to a directory of diagram files. */
object DiagramDirectory {
    /**
     * Reads every workflow document directly inside [irDir] and writes, to [outDir], one file per
     * workflow and format plus one index page that links them all. [outDir] is created if needed.
     * Existing files of the same names are replaced; nothing else in [outDir] is touched. Other
     * JSON files in [irDir] are left alone and reported in the summary.
     *
     * Nothing is written unless every document can be read and is consistent. After that, the
     * files of each workflow are written as soon as it is drawn, one workflow after the other in
     * the order of the summary, and the index last. Every file is written whole or not at all
     * (see [DiagramFiles.write]), so a run that is interrupted leaves the workflows it got to,
     * complete, and no index of its own.
     *
     * A format may allow a diagram only so much time. A workflow that takes longer is drawn in
     * the first of the format's simpler ways that is done in time, and its file says what it
     * leaves out. If none is, the workflow has no file in that format: a file of that name from
     * an earlier run is removed, the workflow's files in the other formats and the index say
     * that there is none and why, and the summary lists it (see [RenderedDiagram.missing]).
     *
     * @throws IrFormatException if a workflow document cannot be read
     * @throws IllegalArgumentException if a document is inconsistent or two workflows would get
     *   the same file name
     */
    fun render(irDir: Path, outDir: Path, options: DirectoryOptions = DirectoryOptions()): RenderSummary =
        render(irDir, outDir, options, DrawingWatch(options.progress))

    internal fun render(irDir: Path, outDir: Path, options: DirectoryOptions, watch: DrawingWatch): RenderSummary {
        val (sources, skipped) = read(irDir)
        val named = sources
            .map { it to options.fileNames.nameFor(it.file, it.document) }
            .sortedWith(compareBy({ it.first.document.workflowType }, { it.second }))
        checkNames(named)
        for ((source, _) in named) inFile(source) { LayoutBuilder.check(source.document) }

        val render = RenderOptions(maxNodes = options.maxNodes)
        val pages = options.formats.associateWith { format ->
            fun fileOf(name: String) = "$name.${format.extension}"
            PageOptions(
                render = render,
                diagramFiles = named.groupBy({ it.first.document.workflowType }, { fileOf(it.second) }),
                indexFile = IndexPage.FILE,
                interfaceFiles = named.groupBy({ it.first.document.interfaceName }, { fileOf(it.second) }),
            )
        }

        Files.createDirectories(outDir)
        val diagrams = named.mapIndexed { index, (source, name) ->
            val document = source.document
            val step = DrawingStep(index + 1, named.size, name, document.workflowType, document.nodes.size)
            watch.draw(step) { attempt ->
                val prepared = options.formats.map { format -> format to inFile(source) { attempt.prepare(format, document, pages.getValue(format)) } }
                val missing = attempt.missing
                // What is written says of what is not that it is missing, and why.
                val overlay = if (missing.isEmpty()) DiagramOverlay.NONE else DiagramOverlay(notices = missing.map(Missing::notice))
                val files = ArrayList<DiagramLink>()
                for ((format, diagram) in prepared) {
                    val file = "$name.${format.extension}"
                    if (diagram == null) {
                        // A file an earlier run left would pass for the diagram that this run did not draw.
                        Files.deleteIfExists(outDir.resolve(file))
                        continue
                    }
                    DiagramFiles.write(outDir.resolve(file), diagram.render(overlay))
                    files += DiagramLink(format.name, file)
                }
                val entry = IndexEntry.of(document, files, missing, attempt.simplified)
                entry to RenderedDiagram(
                    workflowType = entry.workflowType,
                    implementation = entry.implementation,
                    modelMode = entry.modelMode,
                    irFile = source.file,
                    files = entry.files,
                    nodes = entry.nodes,
                    unknown = entry.unknown,
                    collapsedRegions = prepared.maxOf { it.second?.limit?.collapsedRegions ?: 0 },
                    dynamic = document.isDynamic,
                    skippedCalls = entry.skipped,
                    leftOutCalls = entry.leftOut,
                    missing = entry.missing,
                    simplified = entry.simplified,
                )
            }
        }

        DiagramFiles.write(outDir.resolve(IndexPage.FILE), IndexPage.render(diagrams.map { it.first }, options.formats.mapNotNull { it.indexSection() }))
        return RenderSummary(outDir, IndexPage.FILE, diagrams.map { it.second }, skipped)
    }

    /** Runs [work] on the document of [source]; what it finds wrong with the document names the file. */
    private fun <T> inFile(source: Source, work: () -> T): T =
        try {
            work()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${source.file}: ${e.message}", e)
        }

    private class Source(val file: String, val document: IrDocument)

    private fun read(irDir: Path): Pair<List<Source>, List<String>> {
        val files = Files.list(irDir).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".json") }.toList()
        }.sortedBy { it.name }
        val sources = mutableListOf<Source>()
        val skipped = mutableListOf<String>()
        for (file in files) {
            val text = Files.readAllBytes(file).toString(Charsets.UTF_8)
            if (!file.name.endsWith(IR_SUFFIX) && !looksLikeWorkflowIr(text)) {
                skipped += file.name
                continue
            }
            val document = try {
                IrJson.decode(text)
            } catch (e: IrFormatException) {
                throw IrFormatException("${file.name}: ${e.message}", e)
            }
            sources += Source(file.name, document)
        }
        return sources to skipped
    }

    /** Tells workflow documents from other JSON files that live next to them, such as an inventory. */
    private fun looksLikeWorkflowIr(text: String): Boolean {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return false
        }
        return root is JsonObject && "workflowType" in root && "nodes" in root
    }

    private fun checkNames(named: List<Pair<Source, String>>) {
        val index = IndexPage.FILE.substringBeforeLast('.')
        for ((source, name) in named) {
            require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' }) {
                "Diagram name '$name' for ${source.file} is not a plain file name"
            }
            require(!name.equals(index, ignoreCase = true)) {
                "Diagram name '$name' for ${source.file} is the name of the index"
            }
        }
        val clashes = named.groupBy({ it.second.lowercase() }, { it.first.file }).filterValues { it.size > 1 }
        require(clashes.isEmpty()) {
            "Several documents would be written to the same files: " +
                clashes.entries.joinToString("; ") { (name, sources) -> "$name from ${sources.joinToString(", ")}" }
        }
    }

    private const val IR_SUFFIX = ".ir.json"
}
