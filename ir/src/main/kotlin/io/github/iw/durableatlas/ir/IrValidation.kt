package io.github.iw.durableatlas.ir

/**
 * Checks the referential integrity that a JSON Schema cannot express. Returns one message per
 * problem, in a stable order; an empty list means the document is consistent.
 */
fun IrDocument.validate(): List<String> {
    val problems = mutableListOf<String>()
    val nodesById = nodes.associateBy { it.id }
    val entryPointIds = entryPoints.map { it.id }.toSet()

    duplicates(nodes.map { it.id }).forEach { problems += "duplicate node id '$it'" }
    duplicates(entryPoints.map { it.id }).forEach { problems += "duplicate entry point id '$it'" }
    (nodesById.keys intersect entryPointIds).sorted().forEach {
        problems += "id '$it' is used by both a node and an entry point"
    }

    for (entryPoint in entryPoints) {
        val start = nodesById[entryPoint.start]
        when {
            start == null ->
                problems += "entry point '${entryPoint.id}' starts at missing node '${entryPoint.start}'"
            start.kind != NodeKind.START ->
                problems += "entry point '${entryPoint.id}' starts at '${start.id}', which is not a start node"
        }
    }

    for (node in nodes) {
        val parentId = node.parent ?: continue
        val parent = nodesById[parentId]
        when {
            parent == null -> problems += "node '${node.id}' has missing parent '$parentId'"
            !parent.kind.isContainer -> problems += "node '${node.id}' has parent '$parentId', which is not a container"
            node.id in ancestorsOf(node, nodesById) -> problems += "node '${node.id}' is its own ancestor"
        }
    }

    for (node in nodes) {
        if (node.keys.isNotEmpty() && !node.kind.isUpsert) {
            problems += "node '${node.id}' has keys, which only an upsert of search attributes or of the memo writes"
        }
        if (node.otherKeys && node.keys.isEmpty()) {
            problems += "node '${node.id}' is said to write more than its keys, and has none"
        }
        if (node.requests.isNotEmpty() && node.kind != NodeKind.ACTIVITY && node.kind != NodeKind.LOCAL_ACTIVITY) {
            problems += "node '${node.id}' has requests, which only an activity makes"
        }
        if (node.retry && node.kind != NodeKind.LOOP) problems += "node '${node.id}' is marked as a retry, which only a loop can be"
        if (node.join != null && node.kind != NodeKind.PARALLEL) {
            problems += "node '${node.id}' says which lanes are waited for, which only a parallel region can"
        }
        if (node.join != null && node.waitsFor != null) {
            problems += "node '${node.id}' says which lanes are waited for, and that none is"
        }
        if (node.reason == UnknownReasons.UNSEEN_CODE && (node.label == null || node.candidates.isEmpty())) {
            problems += "node '${node.id}' stands for a call behind which something cannot be followed, and does not name the call or what cannot be followed"
        }
    }

    for (edge in edges) {
        if (edge.from !in nodesById && edge.from !in entryPointIds) {
            problems += "edge '${edge.from}' -> '${edge.to}' starts at a missing node or entry point"
        }
        if (edge.to !in nodesById) {
            problems += "edge '${edge.from}' -> '${edge.to}' ends at a missing node"
        }
        if (edge.kind == EdgeKind.CANCELS && nodesById[edge.from]?.let { it.kind != NodeKind.CANCEL_SCOPE } == true) {
            problems += "cancels edge '${edge.from}' -> '${edge.to}' does not start at a cancelScope node"
        }
    }

    for (skipped in skippedCalls) {
        val place = "${skipped.loc.file}:${skipped.loc.line}"
        if (skipped.loc.line < 1) problems += "skipped call at '$place' has no line"
        if (skipped.text.isBlank()) problems += "skipped call at '$place' has no text"
    }
    duplicates(skippedCalls.map { "${it.loc.file}:${it.loc.line} ${it.text}" }).forEach { problems += "skipped call '$it' is listed twice" }
    if (skippedCalls.isNotEmpty() && modelMode != ModelMode.DEGRADED) {
        problems += "calls are skipped, which only a document of a degraded model does"
    }

    for (leftOut in leftOutCalls) {
        val place = "${leftOut.loc.file}:${leftOut.loc.line}"
        if (leftOut.loc.line < 1) problems += "call left out at '$place' has no line"
        if (leftOut.label.isBlank()) problems += "call left out at '$place' does not say what is called"
        if (leftOut.reason !in LeftOutCall.REASONS) problems += "call left out at '$place' has the reason '${leftOut.reason}', for which no call is left out"
    }
    duplicates(leftOutCalls.map { "${it.loc.file}:${it.loc.line} ${it.reason} ${it.label} ${it.candidates.sorted().joinToString(" ")}".trimEnd() })
        .forEach { problems += "call left out '$it' is listed twice" }
    if (leftOutCalls.isNotEmpty() && detail != Detail.OVERVIEW) {
        problems += "calls are left out, which only an overview does"
    }

    val expected = IrStats.of(nodes)
    if (stats != expected) {
        problems += "stats are $stats but the nodes give $expected"
    }
    return problems
}

private fun duplicates(ids: List<String>): List<String> =
    ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

private fun ancestorsOf(node: IrNode, nodesById: Map<String, IrNode>): Set<String> {
    val seen = linkedSetOf<String>()
    var current = node.parent
    while (current != null && seen.add(current)) {
        current = nodesById[current]?.parent
    }
    return seen
}
