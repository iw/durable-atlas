package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceAnalysisException
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.analyser.inventory.Inventory
import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.analyser.inventory.StandaloneActivities
import io.github.iw.durableatlas.analyser.inventory.StandaloneActivitiesJson
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.modelextractor.ExtractedModel
import io.github.iw.durableatlas.modelextractor.ModelFormatException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** What to analyse, and where to put the result. */
data class AnalysisRequest(
    /** Directory of an extracted model: the one that holds its `index.json`. */
    val model: Path,
    /** Output directory. */
    val out: Path,
    /** Home of the JDK the sources are resolved against; that of the running JVM if `null`. */
    val jdkHome: Path? = null,
    /**
     * Write the IR of this workflow type only, and leave the IR files of other workflow types in
     * the output directory as they are. The inventory always covers everything.
     */
    val workflowType: String? = null,
    /**
     * How many calls deep the functions of the sources are followed from an entry point of a
     * workflow. What lies deeper is not drawn; where a step is certain to lie there, an `unknown`
     * node stands at the call.
     */
    val maxDepth: Int = WorkflowGraphs.DEFAULT_MAX_DEPTH,
)

/** The IR of one workflow type as one class implements it, and the file it was written to. */
class AnalysedWorkflow(
    val document: IrDocument,
    val file: Path,
)

class AnalysisResult(
    val inventory: Inventory,
    /** The inventory file that was written. */
    val inventoryFile: Path,
    val discovery: Discovery,
    /** The IR files that were written, ordered by workflow type, interface and implementing class. */
    val workflows: List<AnalysedWorkflow>,
    /**
     * Workflow types whose IR could not be made, each with the reason. Everything else was
     * written all the same; a caller should still treat the analysis as failed.
     */
    val failures: List<String>,
    /** The activity types no workflow calls. */
    val standaloneActivities: StandaloneActivities,
    /** The file [standaloneActivities] was written to, next to the inventory. */
    val standaloneActivitiesFile: Path,
    /**
     * What the code of the workflows holds that their graphs do not show, each with its place.
     * These are logged as warnings as well; what discovery warns of is in [discovery].
     */
    val warnings: List<String> = emptyList(),
)

/** Analyses the sources of an extracted model. */
class Analyser(private val log: (String) -> Unit = {}) {
    /**
     * Finds the workflow types and activity interfaces of the model and writes them to
     * `inventory.json` in the output directory, and next to it one IR file for each class that
     * implements a workflow type (see [IrFiles] for their names), and
     * `standalone-activities.json`, the activity types that no workflow calls.
     *
     * IR files in the output directory that this run did not write are removed, so that the
     * directory holds the IR of one commit; not so when the request names a workflow type.
     *
     * @throws ModelFormatException if [AnalysisRequest.model] does not hold a readable model
     * @throws SourceAnalysisException if the sources cannot be analysed
     */
    fun analyse(request: AnalysisRequest): AnalysisResult {
        val model = ExtractedModel.read(request.model)
        val started = System.nanoTime()
        val failures = ArrayList<String>()
        val withoutGraph = ArrayList<String>()
        val warnings = ArrayList<String>()
        val (discovery, documents) = SourceBackends.open(model, request.jdkHome, log).use { backend ->
            log("the sources are ready for analysis after ${millisSince(started)} ms")
            val discovery = TemporalDiscovery.discover(backend)
            discovery.warnings.forEach { log("warning: $it") }
            log(
                "found ${discovery.workflows.size} workflow type(s) and " +
                    "${discovery.activityInterfaces.size} activity interface(s) after ${millisSince(started)} ms",
            )
            discovery to documents(backend, model, discovery, request, failures, withoutGraph, warnings)
        }
        warnings.forEach { log("warning: $it") }

        val inventory = Inventory.of(discovery, model.index.commit, model.index.mode)
        val out = request.out.toAbsolutePath().normalize()
        Files.createDirectories(out)
        val inventoryFile = out.resolve(InventoryJson.FILE_NAME)
        writeAtomically(inventoryFile, InventoryJson.encode(inventory))

        // Like the inventory, this covers every workflow type, whichever the run was narrowed to.
        val standalone = StandaloneActivities.of(discovery, documents, withoutGraph, model.index.commit, model.index.mode)
        val standaloneFile = out.resolve(StandaloneActivitiesJson.FILE_NAME)
        writeAtomically(standaloneFile, StandaloneActivitiesJson.encode(standalone))

        // Names are given among all documents of the commit, so that a file is called the same
        // whether or not the run was narrowed to its workflow type.
        val written = documents.zip(IrFiles.names(documents))
            .filter { (document, _) -> request.workflowType == null || document.workflowType == request.workflowType }
            .map { (document, name) ->
                val file = out.resolve(name)
                writeAtomically(file, IrJson.encode(document))
                AnalysedWorkflow(document, file)
            }
        if (request.workflowType == null && failures.isEmpty()) removeOtherIrFiles(out, written)
        log("wrote ${written.size} IR file(s) after ${millisSince(started)} ms")
        return AnalysisResult(inventory, inventoryFile, discovery, written, failures, standalone, standaloneFile, warnings)
    }

    /** The IR of every implemented workflow type; what goes wrong for one is noted and does not stop the others. */
    private fun documents(
        backend: SourceBackend,
        model: ExtractedModel,
        discovery: Discovery,
        request: AnalysisRequest,
        failures: MutableList<String>,
        withoutGraph: MutableList<String>,
        warnings: MutableList<String>,
    ): List<IrDocument> {
        val only = request.workflowType
        val graphs = WorkflowGraphs(backend, model.index.commit, model.index.mode, request.maxDepth)
        val documents = discovery.workflows.flatMap { workflow ->
            if (workflow.implementations.isEmpty() && (only == null || only == workflow.name)) {
                log(
                    "warning: no class in the sources implements ${workflow.declaration.name}; " +
                        "there is no IR for workflow type ${workflow.name}",
                )
            }
            workflow.implementations.mapNotNull { implementation ->
                try {
                    graphs.extract(workflow, implementation)
                } catch (e: RuntimeException) {
                    failures += failure(workflow.name, implementation.declaration.name, e)
                    withoutGraph += workflow.name
                    null
                } catch (e: StackOverflowError) {
                    failures += failure(workflow.name, implementation.declaration.name, e)
                    withoutGraph += workflow.name
                    null
                }
            }
        }
        warnings += graphs.warnings
        return documents
    }

    private fun failure(workflowType: String, implementation: String, cause: Throwable): String =
        "the IR of workflow type $workflowType ($implementation) could not be made: $cause"

    /** Removes the IR files left by earlier runs, which may be of another commit. */
    private fun removeOtherIrFiles(out: Path, written: List<AnalysedWorkflow>) {
        val current = written.map { it.file.name }.toSet()
        out.listDirectoryEntries()
            .filter { it.isRegularFile() && IrFiles.isIrFile(it.name) && it.name !in current }
            .sorted()
            .forEach { stale ->
                Files.delete(stale)
                log("removed ${stale.name}, which is not part of this analysis")
            }
    }

    private fun millisSince(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    /** A reader never sees a half-written file. */
    private fun writeAtomically(file: Path, text: String) {
        val temporary = Files.createTempFile(file.parent, ".${file.fileName}", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
