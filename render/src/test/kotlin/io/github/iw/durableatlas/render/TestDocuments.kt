package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** The hand-written workflow documents under `src/test/resources/ir`. */
object TestDocuments {
    // Gradle runs tests with the module directory as the working directory.
    val directory: Path = Path.of("src", "test", "resources", "ir")

    const val ORDER = "OrderWorkflow"
    const val FULFILMENT = "FulfilmentWorkflow"
    const val INVOICE = "InvoiceWorkflow"
    const val ODD = "odd-workflow"

    val names: List<String> = listOf(FULFILMENT, INVOICE, ORDER, ODD)

    /**
     * A document for what the others do not hold: work that is not waited for, cancellation, and
     * a child that names the interface of its stub. It is kept in a directory of its own, so that
     * the directory the others are rendered from stays as it is.
     */
    const val DISPATCH = "DispatchWorkflow"

    /**
     * A document for what a history records without a call that looks like a step: search
     * attributes and memo entries that are set, with and without the names of what is written, a
     * wait for a lock that a signal lets go of, and a loop that is a retry. Kept beside
     * [DISPATCH], for the same reason.
     */
    const val LISTING = "ListingWorkflow"

    /**
     * A document for waits that the workflow's own code ends, and for a handler that returns in
     * more ways than one: two lanes that each wait for a permit the other lets go of, and an
     * update that waits for one for a time at most and returns when that is up. The `wakes`
     * edges of the lanes' waits and of the update's start at the workflow method. Kept beside
     * [DISPATCH], for the same reason.
     */
    const val PERMIT = "PermitWorkflow"

    /**
     * A document for what the flow waits for and how far: a race of three lanes, one of them a
     * timer, whose losers a scope cancels; a promise that is waited for a time at most and
     * whose timeout a clause catches; a region nothing waits for but for the start of a child
     * in it; and upserts that name some of what they write. Kept beside [DISPATCH], for the
     * same reason.
     */
    const val TENDER = "TenderWorkflow"
    private val more: Path = Path.of("src", "test", "resources", "ir-more")

    /** Every hand-written document. */
    val all: List<String> = names + DISPATCH + LISTING + PERMIT + TENDER

    fun text(name: String): String {
        val file = if (name in names) directory.resolve("$name.ir.json") else more.resolve("$name.ir.json")
        return Files.readAllBytes(file).toString(Charsets.UTF_8)
    }

    fun load(name: String): IrDocument = IrJson.decode(text(name))

    fun files(): List<String> = Files.list(directory).use { stream -> stream.map { it.name }.sorted().toList() }
}
