package io.github.iw.durableatlas.analyser.discovery

import io.github.iw.durableatlas.analyser.code.SourceBackend

/** Entry point of discovery: finds everything Temporal-related that the sources declare. */
object TemporalDiscovery {
    fun discover(backend: SourceBackend): Discovery {
        val warnings = mutableListOf<String>()
        val sourceClasses = backend.sourceClasses()
        val workflows = WorkflowDiscovery(backend, warnings).discover(sourceClasses)
        // Activities may be declared and implemented in Java: what they do is not looked at either way.
        val activityInterfaces = ActivityDiscovery(backend, warnings).discover(sourceClasses + backend.javaClasses())
        warnings += declaredTwice(backend, workflows, activityInterfaces)
        return Discovery(workflows, activityInterfaces, warnings.distinct())
    }

    /**
     * What is listed although the sources declare its class more than once under one name: only
     * one of the declarations is read, so a workflow or an activity interface that another one
     * declares is not listed, and is not drawn.
     */
    private fun declaredTwice(backend: SourceBackend, workflows: List<WorkflowType>, activityInterfaces: List<ActivityInterface>): List<String> {
        val twice = backend.duplicateClasses()
        if (twice.isEmpty()) return emptyList()
        fun places(name: String): String = twice.getValue(name).joinToString(", ") { "${it.file}:${it.line}" }
        val found = ArrayList<String>()
        for (workflow in workflows) {
            val classes = listOf(workflow.declaration.name) + workflow.implementations.map { it.declaration.name }
            for (name in classes.filter { it in twice }) {
                found += "$name is declared more than once (${places(name)}); workflow type ${workflow.name} is listed and drawn from one of these alone"
            }
        }
        for (activities in activityInterfaces.filter { it.declaration.name in twice }) {
            val name = activities.declaration.name
            found += "$name is declared more than once (${places(name)}); its activity types are listed from one of these alone"
        }
        return found
    }
}
