package io.github.iw.durableatlas.overlay.testing

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.overlay.history.ExecutionHistory
import io.github.iw.durableatlas.overlay.history.HistoryJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The documents and histories of the sample repository and of the demo project. The documents
 * are the ones the tests of the analyser expect it to produce, so nothing has to be analysed
 * here. Gradle runs tests with the module directory as the working directory.
 */
object Fixtures {
    /** The documents of the sample repository's workflow types. */
    val sampleIr: Path = Path.of("..", "analyser", "src", "test", "resources", "golden", "ir")
    val sampleHistories: Path = Path.of("..", "fixtures", "sample-monorepo", "histories")
    val demoIr: Path = Path.of("..", "demo", "expected")
    val demoHistories: Path = Path.of("..", "demo", "histories")

    /** The documents the analyser's tests expect for workflows whose jumps end a region further out than their own. */
    val regionEndIr: Path = sampleIr.resolve("ends")

    /** The documents the analyser's tests expect for workflows whose last steps leave their region or go on to its end. */
    val regionExitIr: Path = sampleIr.resolve("exits")

    /**
     * The documents the analyser's tests expect for workflows that take steps while they are made, register
     * handlers as they run, compensate side by side and retry.
     */
    val constructIr: Path = sampleIr.resolve("constructs")

    /**
     * The documents the analyser's tests expect for workflows that wait on what handlers feed, start work that
     * nothing waits for, cancel scopes and other workflows, chain promises and retry beside the flow.
     */
    val patternIr: Path = sampleIr.resolve("patterns")

    /**
     * The documents the analyser's tests expect for workflows that wait for a lock or a semaphore, set search
     * attributes and memo entries, get the options of their activities from elsewhere and hand activities to
     * the workflow to run.
     */
    val silentIr: Path = sampleIr.resolve("silent")

    /**
     * The documents the analyser's tests expect for workflows that run races, start steps with calls that give
     * promises, wait for room in a queue and on what their own code lets go of, and name some of what an upsert
     * writes.
     */
    val openIr: Path = sampleIr.resolve("open")

    /** The documents the analyser's tests expect for workflows whose activities get their options in roundabout ways. */
    val optionIr: Path = sampleIr.resolve("options")

    /**
     * The documents the analyser's tests expect of a degraded model for workflows that call into libraries the
     * model lacks: the calls that cannot be steps are left out and listed, the others keep a node.
     */
    val callIr: Path = sampleIr.resolve("calls")

    /**
     * The documents the analyser's tests expect of a degraded model for workflows whose steps stand among calls
     * that cannot be read: blocks handed to a library that is missing, and helpers that call what no class implements.
     */
    val shopIr: Path = sampleIr.resolve("shop")

    /** The overviews the analyser's tests expect of the same workflows: the documents without those calls. */
    val shopOverviewIr: Path = sampleIr.resolveSibling("overview").resolve("shop")

    /** The full document of one of the workflows in [shopIr], by the name of its file without `.ir.json`. */
    fun shop(file: String): IrDocument = document(shopIr.resolve("$file.ir.json"))

    /** The overview of one of the workflows in [shopIr]. */
    fun shopOverview(file: String): IrDocument = document(shopOverviewIr.resolve("$file.ir.json"))

    /** The document of one of the workflows in [callIr], by the name of its file without `.ir.json`. */
    fun call(file: String): IrDocument = document(callIr.resolve("$file.ir.json"))

    /** The document of one of the workflows in [openIr], by the name of its file without `.ir.json`. */
    fun open(file: String): IrDocument = document(openIr.resolve("$file.ir.json"))

    /** The document of one of the workflows in [silentIr], by the name of its file without `.ir.json`. */
    fun silent(file: String): IrDocument = document(silentIr.resolve("$file.ir.json"))

    /** The document of one of the workflows in [patternIr], by the name of its file without `.ir.json`. */
    fun pattern(file: String): IrDocument = document(patternIr.resolve("$file.ir.json"))

    /** The document of a workflow type of the sample repository. */
    fun sample(workflowType: String): IrDocument = document(sampleIr.resolve("$workflowType.ir.json"))

    /** The document of one of the workflows in [regionEndIr]. */
    fun regionEnd(workflowType: String): IrDocument = document(regionEndIr.resolve("$workflowType.ir.json"))

    /** The document of one of the workflows in [regionExitIr]. */
    fun regionExit(workflowType: String): IrDocument = document(regionExitIr.resolve("$workflowType.ir.json"))

    /** The document of one of the workflows in [constructIr]. */
    fun construct(workflowType: String): IrDocument = document(constructIr.resolve("$workflowType.ir.json"))

    /** A history of the sample repository, by file name without `.json`. */
    fun sampleHistory(name: String): ExecutionHistory = HistoryJson.read(sampleHistories.resolve("$name.json"))

    val demo: IrDocument get() = document(demoIr.resolve("OrderWorkflow.ir.json"))

    /** A history of the demo project, by what follows `OrderWorkflow.` in its file name. */
    fun demoHistory(path: String): ExecutionHistory = HistoryJson.read(demoHistories.resolve("OrderWorkflow.$path.json"))

    /** Every history of the sample repository, by file name without `.json`. */
    fun sampleHistoryNames(): List<String> = sampleHistories.listDirectoryEntries("*.json").map { it.name.removeSuffix(".json") }.sorted()

    private fun document(file: Path): IrDocument = IrJson.decode(Files.readAllBytes(file).toString(Charsets.UTF_8))
}
