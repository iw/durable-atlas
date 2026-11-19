package io.github.iw.durableatlas.analyser.inventory

import io.github.iw.durableatlas.analyser.FIXED_COMMIT
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InventoryJsonTest {
    private val schema = JsonSchemaChecker.forSchema(InventorySchema.text())

    private val inventory = Inventory(
        commit = FIXED_COMMIT,
        modelMode = ModelMode.GRADLE,
        workflows = listOf(
            InventoryWorkflow(
                workflowType = "OrderWorkflow",
                interfaceName = "com.example.orders.OrderWorkflow",
                module = ":orders:api",
                loc = SourceLocation("orders/api/src/main/kotlin/OrderWorkflow.kt", 12),
                entryPoints = listOf(
                    InventoryEntryPoint(EntryPointKind.WORKFLOW_METHOD, "OrderWorkflow", "processOrder"),
                    InventoryEntryPoint(EntryPointKind.SIGNAL, "cancel", "cancelOrder"),
                ),
                implementations = listOf(
                    InventoryImplementation(
                        className = "com.example.orders.OrderWorkflowImpl",
                        module = ":orders:workflows",
                        loc = SourceLocation("orders/workflows/src/main/kotlin/OrderWorkflowImpl.kt", 20),
                        additionalEntryPoints = listOf(InventoryEntryPoint(EntryPointKind.QUERY, "health", "health")),
                    ),
                ),
            ),
            // An interface from a library has neither a module nor a location.
            InventoryWorkflow(
                workflowType = "Shared",
                interfaceName = "com.example.shared.SharedWorkflow",
                entryPoints = listOf(InventoryEntryPoint(EntryPointKind.WORKFLOW_METHOD, "Shared", "run")),
                implementations = emptyList(),
            ),
        ),
        activityInterfaces = listOf(
            InventoryActivityInterface(
                interfaceName = "com.example.orders.PaymentActivities",
                module = ":orders:api",
                loc = SourceLocation("orders/api/src/main/kotlin/PaymentActivities.kt", 8),
                namePrefix = "Payment",
                activities = listOf(InventoryActivity("PaymentCharge", "charge")),
                implementations = listOf(InventoryImplementation("com.example.orders.PaymentActivitiesImpl", ":orders:workflows")),
            ),
        ),
    )

    @Test
    fun `the JSON form has a fixed key order, leaves out what is unset and ends with a newline`() {
        val json = InventoryJson.encode(inventory)

        schema.assertValid(json)
        assertTrue(json.endsWith("}\n"))
        assertTrue('\r' !in json)
        assertEquals(
            listOf("schemaVersion", "commit", "modelMode", "workflows", "activityInterfaces"),
            Regex("""^ {2}"(\w+)":""", RegexOption.MULTILINE).findAll(json).map { it.groupValues[1] }.toList(),
        )
        assertContains(
            json,
            """
            |    {
            |      "workflowType": "Shared",
            |      "interface": "com.example.shared.SharedWorkflow",
            |      "entryPoints": [
            |        {
            |          "kind": "workflowMethod",
            |          "name": "Shared",
            |          "method": "run"
            |        }
            |      ],
            |      "implementations": []
            |    }
            """.trimMargin(),
        )
        assertEquals(1, Regex("\"additionalEntryPoints\"").findAll(json).count())
        assertEquals(inventory, InventoryJson.decode(json))
    }

    @Test
    fun `text that is not an inventory of this version is refused`() {
        val json = InventoryJson.encode(inventory)

        val newer = assertFailsWith<InventoryFormatException> {
            InventoryJson.decode(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
        assertContains(newer.message.orEmpty(), "Unsupported inventory schemaVersion 2")
        assertFailsWith<InventoryFormatException> { InventoryJson.decode("[]") }
        assertFailsWith<InventoryFormatException> { InventoryJson.decode(json.replace("\"commit\"", "\"comit\"")) }
    }

    @Test
    fun `the schema refuses what the format does not allow`() {
        val json = InventoryJson.encode(inventory)

        fun violations(change: (String) -> String): List<String> =
            schema.violations(change(json).also { check(it != json) { "the change did not apply" } })

        assertEquals(emptyList(), schema.violations(json))
        assertTrue(violations { it.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2") }.isNotEmpty())
        assertTrue(violations { it.replace("\"modelMode\": \"gradle\"", "\"modelMode\": \"guessed\"") }.isNotEmpty())
        assertTrue(violations { it.replace("\"kind\": \"signal\"", "\"kind\": \"timer\"") }.isNotEmpty())
        assertTrue(violations { it.replace("\"module\": \":orders:api\"", "\"module\": \"orders/api\"") }.isNotEmpty())
        assertTrue(violations { it.replace("\"line\": 12", "\"line\": 0") }.isNotEmpty())
        assertTrue(violations { it.replace("\"workflowType\": \"Shared\"", "\"workflowType\": \"\"") }.isNotEmpty())
        assertTrue(violations { it.replace("\"namePrefix\": \"Payment\"", "\"prefix\": \"Payment\"") }.isNotEmpty())
        // The first entry point of a workflow is its workflow method.
        assertTrue(
            violations {
                it.replace("\"kind\": \"workflowMethod\",\n          \"name\": \"Shared\"", "\"kind\": \"signal\",\n          \"name\": \"Shared\"")
            }.isNotEmpty(),
        )
    }
}
