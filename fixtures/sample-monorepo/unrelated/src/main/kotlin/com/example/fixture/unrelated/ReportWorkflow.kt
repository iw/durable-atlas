package com.example.fixture.unrelated

/**
 * A home-grown annotation that only shares its simple name with the Temporal SDK's
 * annotation. Nothing in this module is a Temporal workflow.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WorkflowInterface

/** A reporting pipeline step. The word "workflow" here is unrelated to Temporal. */
@WorkflowInterface
interface ReportWorkflow {
    fun run(lines: List<String>): String
}

class ReportWorkflowImpl : ReportWorkflow {
    override fun run(lines: List<String>): String {
        val total = lines.sumOf { it.length.toLong() }
        return "lines=${lines.size} total=${PriceFormatter.format(total)}"
    }
}
