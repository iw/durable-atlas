package io.github.iw.durableatlas.ir

private val entryPointOrder: Comparator<EntryPoint> =
    compareBy<EntryPoint> { it.kind }.thenBy { it.id }

private val skippedOrder: Comparator<SkippedCall> =
    compareBy<SkippedCall> { it.loc.file }.thenBy { it.loc.line }.thenBy { it.text }

private val edgeOrder: Comparator<IrEdge> =
    compareBy<IrEdge> { it.from }
        .thenBy { it.to }
        .thenBy { it.kind }
        .thenBy(nullsFirst()) { it.label }

/**
 * Returns this document in canonical order, so that equal graphs always serialise to the same
 * bytes regardless of the order in which they were built:
 *
 * - entry points by kind (the workflow method first), then id
 * - nodes by id
 * - edges by from, to, kind, label
 * - property and candidate lists sorted, without duplicates
 * - skipped calls by file, line and text, without duplicates
 *
 * Strings compare by UTF-16 code unit, independent of locale.
 */
fun IrDocument.normalized(): IrDocument =
    copy(
        entryPoints = entryPoints
            .map { it.copy(reads = it.reads.canonical(), writes = it.writes.canonical()) }
            .sortedWith(entryPointOrder),
        nodes = nodes
            .map { it.copy(reads = it.reads.canonical(), candidates = it.candidates.canonical()) }
            .sortedBy { it.id },
        edges = edges.sortedWith(edgeOrder),
        skippedCalls = skippedCalls.distinct().sortedWith(skippedOrder),
    )

private fun List<String>.canonical(): List<String> = distinct().sorted()
