package io.github.iw.durableatlas.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the tables for people call workflows, where several of them have one type. */
class WorkflowNamesTest {
    @Test
    fun `a workflow type of its own is named as it is`() {
        assertEquals(
            listOf("OrderWorkflow", "ShipmentWorkflow"),
            Summaries.workflowNames(listOf(Summaries.Listed("OrderWorkflow", "com.example.orders.OrderWorkflowImpl"), Summaries.Listed("ShipmentWorkflow", "com.example.orders.ShipmentWorkflowImpl"))),
        )
        assertEquals(emptyList(), Summaries.workflowNames(emptyList()))
    }

    @Test
    fun `workflows of one type are told apart by the end of the names of their classes`() {
        val names = Summaries.workflowNames(
            listOf(
                Summaries.Listed("GreetingWorkflow", "com.example.hello.Plain.GreetingWorkflowImpl"),
                Summaries.Listed("GreetingWorkflow", "com.example.hello.Signalled.GreetingWorkflowImpl"),
                Summaries.Listed("OrderWorkflow", "com.example.orders.OrderWorkflowImpl"),
                Summaries.Listed("GreetingWorkflow", "com.example.hello.PoliteGreeting"),
            ),
        )
        // As many parts as it takes, and no more: the last one where it is enough.
        assertEquals(
            listOf(
                "GreetingWorkflow (Plain.GreetingWorkflowImpl)",
                "GreetingWorkflow (Signalled.GreetingWorkflowImpl)",
                "OrderWorkflow",
                "GreetingWorkflow (PoliteGreeting)",
            ),
            names,
        )
    }

    @Test
    fun `classes that differ only at the front are named in full, and so is a class that others end in`() {
        assertEquals(
            listOf("Sync (north.SyncImpl)", "Sync (south.SyncImpl)", "Sync (SyncImpl)"),
            Summaries.workflowNames(listOf(Summaries.Listed("Sync", "north.SyncImpl"), Summaries.Listed("Sync", "south.SyncImpl"), Summaries.Listed("Sync", "SyncImpl"))),
        )
        // Nothing tells two workflows of the same type and class apart but their files.
        assertEquals(listOf("Sync (SyncImpl)", "Sync (SyncImpl)"), Summaries.workflowNames(listOf(Summaries.Listed("Sync", "a.SyncImpl"), Summaries.Listed("Sync", "a.SyncImpl"))))
    }

    @Test
    fun `a dynamic workflow is called one, because its type is a name made up for it`() {
        val names = Summaries.workflowNames(
            listOf(
                Summaries.Listed("OrderWorkflow", "com.example.orders.OrderWorkflowImpl"),
                Summaries.Listed("dynamic-Fallback", "com.example.orders.Fallback", dynamic = true),
                Summaries.Listed("dynamic-Catcher", "com.example.north.Catcher", dynamic = true),
                Summaries.Listed("dynamic-Catcher", "com.example.south.Catcher", dynamic = true),
            ),
        )
        assertEquals(
            listOf(
                "OrderWorkflow",
                "dynamic-Fallback (dynamic workflow)",
                "dynamic-Catcher (north.Catcher, dynamic workflow)",
                "dynamic-Catcher (south.Catcher, dynamic workflow)",
            ),
            names,
        )
    }

    @Test
    fun `the names keep a table in its columns`() {
        val names = Summaries.workflowNames(listOf(Summaries.Listed("Greeting", "a.One.Impl"), Summaries.Listed("Greeting", "a.Two.Impl"), Summaries.Listed("Order", "a.OrderImpl")))
        val table = Summaries.table(listOf("Workflow type", "Nodes"), names.mapIndexed { index, name -> listOf(name, "${index + 3}") }, rightAligned = setOf(1))
        assertEquals(
            """
            Workflow type        Nodes
            Greeting (One.Impl)      3
            Greeting (Two.Impl)      4
            Order                    5
            """.trimIndent(),
            table,
        )
    }
}
