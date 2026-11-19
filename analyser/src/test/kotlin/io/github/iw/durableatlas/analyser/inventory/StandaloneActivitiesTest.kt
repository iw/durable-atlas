package io.github.iw.durableatlas.analyser.inventory

import io.github.iw.durableatlas.analyser.AnalysisRequest
import io.github.iw.durableatlas.analyser.Analyser
import io.github.iw.durableatlas.analyser.FIXED_COMMIT
import io.github.iw.durableatlas.analyser.SyntheticSources
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The list of activity types that no workflow calls. */
class StandaloneActivitiesTest {
    private val schema = JsonSchemaChecker.forSchema(StandaloneActivitiesJson.schema())

    private val listed = StandaloneActivities(
        commit = FIXED_COMMIT,
        modelMode = ModelMode.GRADLE,
        activities = listOf(
            StandaloneActivity(
                name = "ReportDaily",
                interfaceName = "com.example.reports.ReportActivities",
                method = "daily",
                module = ":reports",
                loc = SourceLocation("reports/src/main/kotlin/ReportActivities.kt", 9),
            ),
            // An interface from a library has neither a module nor a location.
            StandaloneActivity(name = "Ping", interfaceName = "org.example.lib.HealthActivities", method = "ping"),
        ),
        unresolvedWorkflows = listOf("ComputedWorkflow"),
    )

    @Test
    fun `the JSON form has a fixed key order, fits its schema and reads back as it was written`() {
        val json = StandaloneActivitiesJson.encode(listed)

        assertEquals(
            """
            {
              "schemaVersion": 1,
              "commit": "$FIXED_COMMIT",
              "modelMode": "gradle",
              "activities": [
                {
                  "name": "ReportDaily",
                  "interface": "com.example.reports.ReportActivities",
                  "method": "daily",
                  "module": ":reports",
                  "loc": {
                    "file": "reports/src/main/kotlin/ReportActivities.kt",
                    "line": 9
                  }
                },
                {
                  "name": "Ping",
                  "interface": "org.example.lib.HealthActivities",
                  "method": "ping"
                }
              ],
              "unresolvedWorkflows": [
                "ComputedWorkflow"
              ]
            }
            """.trimIndent() + "\n",
            json,
        )
        schema.assertValid(json)
        assertEquals(listed, StandaloneActivitiesJson.decode(json))

        val empty = StandaloneActivitiesJson.encode(listed.copy(activities = emptyList(), unresolvedWorkflows = emptyList()))
        schema.assertValid(empty)
        assertContains(empty, "\"activities\": [],")
    }

    @Test
    fun `what is not such a list is refused, by the reader and by the schema`() {
        val json = StandaloneActivitiesJson.encode(listed)

        val otherVersion = json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertContains(assertFailsWith<InventoryFormatException> { StandaloneActivitiesJson.decode(otherVersion) }.message!!, "schemaVersion 2")
        assertTrue(schema.violations(otherVersion).isNotEmpty())

        val unknownKey = json.replace("\"commit\"", "\"note\": \"x\",\n  \"commit\"")
        assertFailsWith<InventoryFormatException> { StandaloneActivitiesJson.decode(unknownKey) }
        assertTrue(schema.violations(unknownKey).isNotEmpty())

        val noName = json.replace("\"name\": \"Ping\",", "")
        assertFailsWith<InventoryFormatException> { StandaloneActivitiesJson.decode(noName) }
        assertTrue(schema.violations(noName).isNotEmpty())

        assertTrue(schema.violations(json.replace("\"module\": \":reports\"", "\"module\": \"reports\"")).isNotEmpty())
    }

    @Test
    fun `an activity is listed unless a workflow has a step of its name, through whichever stub`(@TempDir directory: Path) {
        val model = SyntheticSources(directory.resolve("model")).file(":app", "standalone/Standalone.kt", SOURCES).model()
        val out = directory.resolve("ir")

        val result = Analyser().analyse(AnalysisRequest(model.directory, out))

        assertEquals(emptyList(), result.failures)
        val standalone = result.standaloneActivities
        assertEquals(FIXED_COMMIT, standalone.commit)
        assertEquals(ModelMode.DEGRADED, standalone.modelMode)
        // Called: Reserve (typed stub), Check (local activity), SendReceipt (untyped stub, by name).
        assertEquals(
            listOf("Refund" to "standalone.OrderActivities", "ReportDaily" to "standalone.ReportActivities"),
            standalone.activities.map { it.name to it.interfaceName },
        )
        assertEquals(listOf("refund", "daily"), standalone.activities.map { it.method })
        assertEquals(listOf(":app", ":app"), standalone.activities.map { it.module })
        // Each points at its method, not at the interface.
        val lines = SOURCES.trimIndent().lines()
        for (activity in standalone.activities) {
            val loc = activity.loc!!
            assertTrue(loc.file.endsWith("standalone/Standalone.kt"), loc.file)
            assertContains(lines[loc.line - 1], "fun ${activity.method}(")
        }
        // The workflow that calls an activity by a name made at run time may call any of them.
        assertEquals(listOf("ComputedWorkflow"), standalone.unresolvedWorkflows)

        // The file is next to the inventory, holds the same, and fits its schema.
        assertEquals("standalone-activities.json", result.standaloneActivitiesFile.name)
        assertEquals(result.inventoryFile.parent, result.standaloneActivitiesFile.parent)
        val text = Files.readString(result.standaloneActivitiesFile)
        schema.assertValid(text)
        assertEquals(standalone, StandaloneActivitiesJson.decode(text))
        assertTrue(text.endsWith("}\n") && '\r' !in text)

        // Like the inventory, it covers every workflow type, whichever the analysis was narrowed to.
        val narrowed = Analyser().analyse(AnalysisRequest(model.directory, directory.resolve("narrowed"), workflowType = "ComputedWorkflow"))
        assertEquals(standalone, narrowed.standaloneActivities)
        assertEquals(text, Files.readString(narrowed.standaloneActivitiesFile))
    }

    private companion object {
        val SOURCES = """
            package standalone

            import io.temporal.activity.ActivityInterface
            import io.temporal.activity.ActivityMethod
            import io.temporal.activity.ActivityOptions
            import io.temporal.activity.LocalActivityOptions
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.time.Duration

            @ActivityInterface
            interface OrderActivities {
                fun reserve(sku: String): String

                fun check(sku: String): Boolean

                @ActivityMethod(name = "SendReceipt")
                fun receipt(sku: String)

                fun refund(sku: String)
            }

            @ActivityInterface(namePrefix = "Report")
            interface ReportActivities {
                fun daily(day: String)

                // The name of an activity another interface declares, which a workflow calls.
                @ActivityMethod(name = "Reserve")
                fun reserveAgain(sku: String)
            }

            @WorkflowInterface
            interface OrderWorkflow {
                @WorkflowMethod
                fun run(sku: String)
            }

            class OrderWorkflowImpl : OrderWorkflow {
                private val options = ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
                private val orders = Workflow.newActivityStub(OrderActivities::class.java, options)
                private val local = Workflow.newLocalActivityStub(
                    OrderActivities::class.java,
                    LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build(),
                )
                private val untyped = Workflow.newUntypedActivityStub(options)

                override fun run(sku: String) {
                    if (local.check(sku)) {
                        orders.reserve(sku)
                    }
                    untyped.execute("SendReceipt", Void::class.java, sku)
                }
            }

            @WorkflowInterface
            interface ComputedWorkflow {
                @WorkflowMethod
                fun run(kind: String)
            }

            class ComputedWorkflowImpl : ComputedWorkflow {
                private val untyped = Workflow.newUntypedActivityStub(
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build(),
                )

                override fun run(kind: String) {
                    untyped.execute("Report" + kind, Void::class.java, "today")
                }
            }
        """
    }
}
