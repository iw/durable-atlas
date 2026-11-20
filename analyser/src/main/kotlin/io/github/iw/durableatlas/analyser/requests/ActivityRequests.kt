package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.ClassDecl
import io.github.iw.durableatlas.analyser.code.FunctionDecl
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.ActivityType
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.Request

/**
 * What the implementation of each activity type asks of other services: for every activity
 * interface, the classes of the sources that implement it, and in each the function that runs
 * for the activity type. Where several classes implement it, each request names its class. An
 * activity type that no class of the sources implements has no requests.
 *
 * @param configuration the configuration of the service a class is part of, which gives the
 *   hosts of its requests
 */
internal class ActivityRequests(
    private val backend: SourceBackend,
    clients: List<ClientSpec>,
    maxDepth: Int,
    private val configuration: (ClassDecl) -> Configuration,
) {
    private val reader = RequestReader(backend, clients, maxDepth)

    /** The requests of every activity type the sources implement, by activity type; types without any are left out. */
    fun of(discovery: Discovery): Map<String, List<Request>> {
        val found = LinkedHashMap<String, LinkedHashSet<Request>>()
        for (activityInterface in discovery.activityInterfaces) {
            for (activity in activityInterface.activities) {
                val implemented = activityInterface.implementations.mapNotNull { implementation ->
                    implementation(implementation, activity)?.let { implementation to it }
                }
                for ((implementation, function) in implemented) {
                    val requests = reader.read(function, implementation.name, configuration(implementation))
                    val named = if (implemented.size > 1) requests.map { it.copy(className = implementation.name) } else requests
                    if (named.isNotEmpty()) found.getOrPut(activity.name) { LinkedHashSet() } += named
                }
            }
        }
        return found.mapValues { it.value.toList() }
    }

    /** The function of the sources that runs for [activity] on an object of [implementation]. */
    private fun implementation(implementation: ClassDecl, activity: ActivityType): FunctionDecl? {
        val method = activity.method.id
        for (type in listOf(implementation.name) + implementation.allSuperTypes) {
            val found = backend.functions(type).firstOrNull { !it.isAbstract && (it.id == method || method in it.overrides) }
            if (found != null) return found.takeIf { it.module != null }
        }
        return null
    }

    companion object {
        /** [document] with the requests of each node that runs one of the activity types of [requests]. */
        fun attach(document: IrDocument, requests: Map<String, List<Request>>): IrDocument {
            if (requests.isEmpty()) return document
            var changed = false
            val nodes = document.nodes.map { node ->
                val made = node.name?.let(requests::get)
                if (made != null && (node.kind == NodeKind.ACTIVITY || node.kind == NodeKind.LOCAL_ACTIVITY)) {
                    changed = true
                    node.copy(requests = made)
                } else {
                    node
                }
            }
            return if (changed) document.copy(nodes = nodes) else document
        }
    }
}
