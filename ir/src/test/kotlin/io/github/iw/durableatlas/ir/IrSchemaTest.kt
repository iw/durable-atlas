package io.github.iw.durableatlas.ir

import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrSchemaTest {
    private val checker = JsonSchemaChecker.forWorkflowIr()
    private val schema = Json.parseToJsonElement(IrSchema.text()).jsonObject
    private val valid = IrJson.encode(SampleDocuments.orderWorkflow)

    @Test
    fun `encoded sample documents match the schema`() {
        checker.assertValid(valid)
        checker.assertValid(IrJson.encode(SampleDocuments.everyNodeKind))
        checker.assertValid(IrJson.encode(SampleDocuments.nestedRegions))
    }

    @Test
    fun `a document without nodes or edges matches the schema`() {
        val empty = SampleDocuments.orderWorkflow.copy(
            entryPoints = emptyList(),
            nodes = emptyList(),
            edges = emptyList(),
            stats = IrStats(0, 0),
        )

        checker.assertValid(IrJson.encode(empty))
    }

    @Test
    fun `schema rejects malformed documents`() {
        val malformed = mapOf(
            "unknown node kind" to valid.replace("\"kind\": \"await\"", "\"kind\": \"wait\""),
            "unknown edge kind" to valid.replace("\"kind\": \"wakes\"", "\"kind\": \"pokes\""),
            "unknown top-level key" to valid.replace("\"commit\":", "\"revision\":"),
            "unknown node key" to valid.replace("\"taskQueue\":", "\"queue\":"),
            "unsupported version" to valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            "unknown model mode" to valid.replace("\"gradle\"", "\"maven\""),
            "activity without a name" to valid.replace("\"name\": \"ReserveStock\",", "\"label\": \"ReserveStock\","),
            "unknown without a reason" to valid.replace("\"reason\":", "\"label\":"),
            "line zero" to valid.replace("\"line\": 42", "\"line\": 0"),
            "duplicate reads" to valid.replace("\"cancelled\",\n        \"paid\"", "\"paid\",\n        \"paid\""),
        )

        for ((description, text) in malformed) {
            assertTrue(text != valid, "$description: the sample was not changed")
            assertTrue(checker.violations(text).isNotEmpty(), "$description: expected a schema violation")
        }
    }

    @Test
    fun `schema requires a change id on version gates`() {
        val gate = SampleDocuments.orderWorkflow.copy(nodes = listOf(IrNode("n00", NodeKind.VERSION_GATE)))

        assertTrue(checker.violations(IrJson.encode(gate)).isNotEmpty())
    }

    @Test
    fun `schema enumerations list exactly the serialised enum names`() {
        assertEquals(serialNames(NodeKind.serializer()), schemaEnum("\$defs", "node", "properties", "kind"))
        assertEquals(serialNames(EdgeKind.serializer()), schemaEnum("\$defs", "edge", "properties", "kind"))
        assertEquals(serialNames(EntryPointKind.serializer()), schemaEnum("\$defs", "entryPoint", "properties", "kind"))
        assertEquals(serialNames(ModelMode.serializer()), schemaEnum("properties", "modelMode"))
        assertEquals(serialNames(WaitsFor.serializer()), schemaEnum("\$defs", "node", "properties", "waitsFor"))
        assertEquals(serialNames(Join.serializer()), schemaEnum("\$defs", "node", "properties", "join"))
        assertEquals(serialNames(Detail.serializer()), schemaEnum("properties", "detail"))
        assertEquals(LeftOutCall.REASONS.toList(), schemaEnum("\$defs", "leftOutCall", "properties", "reason"))
    }

    @Test
    fun `schema properties list exactly the serialised property names`() {
        assertEquals(serialNames(IrDocument.serializer()), schemaProperties())
        assertEquals(serialNames(EntryPoint.serializer()), schemaProperties("\$defs", "entryPoint"))
        assertEquals(serialNames(IrNode.serializer()), schemaProperties("\$defs", "node"))
        assertEquals(serialNames(SourceLocation.serializer()), schemaProperties("\$defs", "sourceLocation"))
        assertEquals(serialNames(IrEdge.serializer()), schemaProperties("\$defs", "edge"))
        assertEquals(serialNames(IrStats.serializer()), schemaProperties("\$defs", "stats"))
        assertEquals(serialNames(SkippedCall.serializer()), schemaProperties("\$defs", "skippedCall"))
        assertEquals(serialNames(LeftOutCall.serializer()), schemaProperties("\$defs", "leftOutCall"))
    }

    @Test
    fun `an overview matches the schema where it says what it is and each call it leaves out has its place, its reason and its label`() {
        val overview = SampleDocuments.orderWorkflow.copy(
            detail = Detail.OVERVIEW,
            leftOutCalls = listOf(
                LeftOutCall(SourceLocation("orders/src/main/kotlin/Orders.kt", 9), UnknownReasons.UNSUPPORTED, "withRequestContext"),
                LeftOutCall(
                    SourceLocation("orders/src/main/kotlin/Orders.kt", 12),
                    UnknownReasons.UNSEEN_CODE,
                    "Events.enriched",
                    listOf("com.example.orders.Enricher.enrich(com.example.orders.Event)"),
                ),
            ),
        )
        val text = IrJson.encode(overview)
        checker.assertValid(text)
        // An overview that left nothing out says what it is and no more, and a document may say that it shows everything.
        checker.assertValid(IrJson.encode(SampleDocuments.orderWorkflow.copy(detail = Detail.OVERVIEW)))
        checker.assertValid(IrJson.encode(SampleDocuments.orderWorkflow.copy(detail = Detail.FULL)))

        val malformed = mapOf(
            "a detail that is none" to text.replace("\"detail\": \"overview\"", "\"detail\": \"brief\""),
            "a call without a label" to text.replace("\"label\": \"withRequestContext\"", "\"label\": \"\""),
            "a call without a reason" to text.replace("      \"reason\": \"unsupported\",\n", ""),
            "a call left out for a reason that keeps its node" to text.replace("\"reason\": \"unsupported\"", "\"reason\": \"depth-limit\""),
            "a call with something else" to text.replace("\"label\": \"withRequestContext\"", "\"label\": \"withRequestContext\",\n      \"text\": \"withRequestContext(context) { }\""),
            "an empty list" to valid.trimEnd().removeSuffix("}").trimEnd() + ",\n  \"leftOutCalls\": []\n}\n",
        )
        for ((description, changed) in malformed) {
            assertTrue(changed != text && changed != valid, "$description: the sample was not changed")
            assertTrue(checker.violations(changed).isNotEmpty(), "$description: expected a schema violation")
        }
    }

    @Test
    fun `calls that were skipped match the schema where each has its place and its text`() {
        val skipping = SampleDocuments.orderWorkflow.copy(
            modelMode = ModelMode.DEGRADED,
            skippedCalls = listOf(SkippedCall(SourceLocation("orders/src/main/kotlin/Logs.kt", 9), "log.info { message }")),
        )
        val text = IrJson.encode(skipping)
        checker.assertValid(text)

        val malformed = mapOf(
            "a call without text" to text.replace("\"text\": \"log.info { message }\"", "\"text\": \"\""),
            "a call without a place" to text.replace("\"loc\": {\n        \"file\": \"orders/src/main/kotlin/Logs.kt\",\n        \"line\": 9\n      },\n      ", ""),
            "a call with something else" to text.replace("\"text\": \"log.info { message }\"", "\"text\": \"log.info { message }\",\n      \"reason\": \"library\""),
            // A document that skipped nothing leaves the list out.
            "an empty list" to valid.trimEnd().removeSuffix("}").trimEnd() + ",\n  \"skippedCalls\": []\n}\n",
        )
        for ((description, changed) in malformed) {
            assertTrue(changed != text && changed != valid, "$description: the sample was not changed")
            assertTrue(checker.violations(changed).isNotEmpty(), "$description: expected a schema violation")
        }
    }

    @Test
    fun `a call that is not drawn from the inside matches the schema, as the node for what cannot be followed and as the branch that asks whether it throws`() {
        val base = SampleDocuments.orderWorkflow
        val unseen = IrNode(
            id = "n30",
            kind = NodeKind.UNKNOWN,
            reason = UnknownReasons.UNSEEN_CODE,
            label = "Events.enriched",
            candidates = listOf("com.example.orders.Enricher.enrich(com.example.orders.Event)"),
            loc = SourceLocation("orders/src/main/kotlin/Orders.kt", 12),
        )
        val asks = IrNode("n31", NodeKind.BRANCH, label = "Events.create throws?", loc = SourceLocation("orders/src/main/kotlin/Orders.kt", 13))
        // What it may throw has more than one name: the failure is called after all of them.
        val fails = IrNode("n32", NodeKind.FAIL, label = "IllegalStateException, UnknownEvent", loc = SourceLocation("orders/src/main/kotlin/Orders.kt", 13))
        val nodes = base.nodes + unseen + asks + fails
        val edges = base.edges + IrEdge("n06", "n30") + IrEdge("n30", "n31") + IrEdge("n31", "n32", label = "yes") + IrEdge("n31", "n09", label = "no")
        val document = base.copy(nodes = nodes, edges = edges, stats = IrStats.of(nodes))
        val text = IrJson.encode(document)

        checker.assertValid(text)
        assertEquals(emptyList(), document.validate())
        assertEquals(document.normalized(), IrJson.decode(text))
        assertTrue("\"reason\": \"unseen-code\"" in text)
        // The reason is one of those the schema describes, and what it lists is described with it.
        val node = schema.at("\$defs", "node", "properties").jsonObject
        for (reason in listOf(UnknownReasons.DEPTH_LIMIT, UnknownReasons.RECURSION, UnknownReasons.DYNAMIC_DISPATCH, UnknownReasons.COMPUTED_NAME, UnknownReasons.UNSUPPORTED, UnknownReasons.UNRESOLVED, UnknownReasons.UNSEEN_CODE)) {
            assertTrue(reason in node.getValue("reason").jsonObject.getValue("description").jsonPrimitive.content, reason)
        }
        assertTrue(UnknownReasons.UNSEEN_CODE in node.getValue("candidates").jsonObject.getValue("description").jsonPrimitive.content)
        assertTrue("throws?" in node.getValue("kind").jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `a branch that asks whether a function throws needs no way to a failure, and the schema says what a fail node stands for`() {
        val base = SampleDocuments.orderWorkflow
        // All the function throws fails the workflow task, but for what a clause takes: no `yes` edge, and no `fail` node.
        val asks = IrNode("n31", NodeKind.BRANCH, label = "Events.create throws?", loc = SourceLocation("orders/src/main/kotlin/Orders.kt", 13))
        val nodes = base.nodes + asks
        val edges = base.edges + IrEdge("n06", "n31") + IrEdge("n31", "n09", label = "no") + IrEdge("n31", "n05", EdgeKind.EXCEPTION, "UnknownEvent")
        val document = base.copy(nodes = nodes, edges = edges, stats = IrStats.of(nodes))

        checker.assertValid(IrJson.encode(document))
        assertEquals(emptyList(), document.validate())
        val node = schema.at("\$defs", "node", "properties").jsonObject
        val kind = node.getValue("kind").jsonObject.getValue("description").jsonPrimitive.content
        assertTrue("the execution of the workflow fails" in kind)
        assertTrue("fails the workflow task" in kind)
        assertTrue("has no 'yes' edge" in kind)
        assertTrue("For fail" in node.getValue("name").jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `schema version constant matches the schema`() {
        val version = schema.at("properties", "schemaVersion").jsonObject.getValue("const").jsonPrimitive.content

        assertEquals(IrDocument.SCHEMA_VERSION.toString(), version)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun serialNames(serializer: KSerializer<*>): List<String> = serializer.descriptor.elementNames.toList()

    private fun schemaEnum(vararg path: String): List<String> =
        schema.at(*path).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    private fun schemaProperties(vararg path: String): List<String> =
        schema.at(*path).jsonObject.getValue("properties").jsonObject.keys.toList()

    private fun JsonElement.at(vararg path: String): JsonElement =
        path.fold(this) { element, key -> element.jsonObject.getValue(key) }
}
