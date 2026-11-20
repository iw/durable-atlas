package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.graph.IrFiles
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.Detail
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.ir.testing.OverviewContract
import kotlin.test.assertEquals

/**
 * Reads the workflows of [discovery] once in full and once as overviews, and holds each
 * overview to the full document of the same workflow: it is well formed, it has the same steps
 * with the same ids, no way between two of them that the full document lacks, and it lists
 * what it leaves out (see [OverviewContract]).
 *
 * An overview that differs from its full document in more than its name is compared with the
 * golden file `<golden>/<name>.ir.json`; one that does not has no such file.
 *
 * @param golden the directory of the golden files of the overviews of these sources, as in `overview/calls`
 * @param only the names of the documents that have golden files, where not all of them do
 * @return the names of the documents whose overview differs, in the order of the documents
 */
fun assertOverviewsKeepTheSteps(
    backend: SourceBackend,
    discovery: Discovery,
    golden: String,
    mode: ModelMode = ModelMode.DEGRADED,
    only: Collection<String>? = null,
): List<String> {
    val full = WorkflowGraphs(backend, FIXED_COMMIT, mode).extract(discovery)
    val overviews = WorkflowGraphs(backend, FIXED_COMMIT, mode, detail = Detail.OVERVIEW).extract(discovery)
    return assertOverviewsKeepTheSteps(full, overviews) { name, overview ->
        val file = "$golden/$name.ir.json"
        when {
            overview == null -> GoldenFiles.assertAbsent(file)
            only == null || name in only -> GoldenFiles.assertMatches(file, IrJson.encode(overview))
        }
    }
}

/**
 * Holds each of [overviews] to the document of [full] it stands beside, as above.
 *
 * @param pinned is handed the name of each document, with its overview if that differs from it and `null` if not
 */
fun assertOverviewsKeepTheSteps(full: List<IrDocument>, overviews: List<IrDocument>, pinned: (String, IrDocument?) -> Unit): List<String> {
    assertEquals(full.map { it.implementation }, overviews.map { it.implementation })
    val names = IrFiles.names(full).map { it.removeSuffix(IrFiles.SUFFIX) }
    assertEquals(IrFiles.names(full), IrFiles.names(overviews))
    val differing = ArrayList<String>()
    for ((index, document) in full.withIndex()) {
        val overview = overviews[index]
        val name = names[index]
        assertEquals(null, document.detail, name)
        assertEquals(emptyList(), document.leftOutCalls, name)
        assertWellFormed(overview)
        assertEquals(emptyList(), OverviewContract.violations(document, overview), name)
        val differs = OverviewContract.differs(document, overview)
        if (differs) differing += name
        pinned(name, overview.takeIf { differs })
    }
    return differing
}
