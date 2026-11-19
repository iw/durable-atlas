package io.github.iw.durableatlas.ir

import io.github.iw.durableatlas.ir.testing.GoldenFiles
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrJsonTest {
    private val document = SampleDocuments.orderWorkflow

    @Test
    fun `encoded sample matches the golden file`() {
        GoldenFiles.assertMatches("order-workflow.ir.json", IrJson.encode(document))
    }

    @Test
    fun `decoding encoded text gives back the normalized document`() {
        for (sample in listOf(document, SampleDocuments.everyNodeKind, SampleDocuments.nestedRegions)) {
            assertEquals(sample.normalized(), IrJson.decode(IrJson.encode(sample)))
        }
    }

    @Test
    fun `encoding is independent of the order the document was built in`() {
        val shuffled = document.copy(
            entryPoints = document.entryPoints.reversed(),
            nodes = document.nodes.reversed().map { it.copy(reads = it.reads.reversed(), candidates = it.candidates.reversed()) },
            edges = document.edges.reversed(),
        )

        assertEquals(IrJson.encode(document), IrJson.encode(shuffled))
    }

    @Test
    fun `nodes edges and entry points are written in canonical order`() {
        val decoded = IrJson.decode(IrJson.encode(document))

        assertEquals(listOf("main", "sig-cancel"), decoded.entryPoints.map { it.id })
        assertEquals(decoded.nodes.map { it.id }.sorted(), decoded.nodes.map { it.id })
        assertEquals(
            listOf("n02 n03 FLOW", "n02 n09 TIMEOUT", "n03 n04 FLOW", "n03 n09 FLOW"),
            decoded.edges.filter { it.from in setOf("n02", "n03") }.map { "${it.from} ${it.to} ${it.kind}" },
        )
        assertEquals(listOf("cancelled", "paid"), decoded.nodes.single { it.kind == NodeKind.AWAIT }.reads)
    }

    @Test
    fun `edges between the same nodes are ordered by kind then label`() {
        val edges = listOf(
            IrEdge("n01", "n02", EdgeKind.EXCEPTION, "TimeoutFailure"),
            IrEdge("n01", "n02", EdgeKind.FLOW, "retry"),
            IrEdge("n01", "n02", EdgeKind.EXCEPTION, "ActivityFailure"),
            IrEdge("n01", "n02", EdgeKind.FLOW),
        )

        val ordered = document.copy(edges = edges).normalized().edges

        assertEquals(
            listOf("FLOW null", "FLOW retry", "EXCEPTION ActivityFailure", "EXCEPTION TimeoutFailure"),
            ordered.map { "${it.kind} ${it.label}" },
        )
    }

    @Test
    fun `output uses two space indent unix line endings and a trailing newline`() {
        val text = IrJson.encode(document)

        assertTrue(text.startsWith("{\n  \"schemaVersion\": 1,\n  \"commit\": "))
        assertTrue(text.endsWith("\n}\n"))
        assertFalse('\r' in text)
        assertFalse('\t' in text)
    }

    @Test
    fun `unset optional properties and the default edge kind are left out`() {
        val text = IrJson.encode(document)

        assertFalse("null" in text)
        assertFalse("\"flow\"" in text)
        assertFalse("\"reads\": []" in text)
        assertContains(text, "\"kind\": \"wakes\"")
    }

    @Test
    fun `top level keys keep their fixed order`() {
        val keys = Regex("""^ {2}"(\w+)":""", RegexOption.MULTILINE).findAll(IrJson.encode(document)).map { it.groupValues[1] }

        assertEquals(
            listOf(
                "schemaVersion", "commit", "modelMode", "workflowType", "interface", "implementation",
                "module", "entryPoints", "nodes", "edges", "stats",
            ),
            keys.toList(),
        )
    }

    @Test
    fun `stats default to the node counts`() {
        assertEquals(IrStats(nodes = 10, unknown = 1), document.stats)
    }

    @Test
    fun `compact hand written json decodes to the same model`() {
        val text = """
            {"stats": {"unknown": 1, "nodes": 2}, "schemaVersion": 1, "commit": "abc", "modelMode": "degraded",
             "workflowType": "W", "interface": "a.W", "implementation": "a.WImpl", "module": ":a",
             "entryPoints": [{"id": "main", "kind": "workflowMethod", "name": "W", "start": "n0"}],
             "nodes": [{"kind": "start", "id": "n0"},
                       {"id": "n1", "kind": "unknown", "reason": "recursion", "loc": {"file": "a/W.kt", "line": 7}}],
             "edges": [{"from": "n0", "to": "n1"}, {"from": "n1", "to": "n0", "kind": "exception", "label": "E"}]}
        """.trimIndent()

        val expected = IrDocument(
            commit = "abc",
            modelMode = ModelMode.DEGRADED,
            workflowType = "W",
            interfaceName = "a.W",
            implementation = "a.WImpl",
            module = ":a",
            entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "W", "n0")),
            nodes = listOf(
                IrNode("n0", NodeKind.START),
                IrNode("n1", NodeKind.UNKNOWN, reason = "recursion", loc = SourceLocation("a/W.kt", 7)),
            ),
            edges = listOf(IrEdge("n0", "n1"), IrEdge("n1", "n0", EdgeKind.EXCEPTION, "E")),
        )
        assertEquals(expected, IrJson.decode(text))
    }

    @Test
    fun `what a document from before these were added leaves out is read as unset, and is not written`() {
        val child = IrNode("n1", NodeKind.CHILD_WORKFLOW, name = "Shipment")
        val before = document.copy(nodes = listOf(IrNode("n00", NodeKind.START), child), edges = listOf(IrEdge("n00", "n1")), stats = IrStats(2, 0))
        val text = IrJson.encode(before)

        assertFalse("\"interface\": \"com.example.Shipment\"" in text)
        assertFalse("waitsFor" in text)
        assertFalse("parentClosePolicy" in text)
        assertEquals(before.normalized(), IrJson.decode(text))

        val told = child.copy(interfaceName = "com.example.Shipment", waitsFor = WaitsFor.START, parentClosePolicy = ParentClosePolicy.ABANDON)
        val after = IrJson.encode(before.copy(nodes = listOf(before.nodes[0], told)))
        // The keys follow the name and the timeout, where a reader looks for them.
        assertContains(after, "\"name\": \"Shipment\",\n      \"interface\": \"com.example.Shipment\",\n      \"waitsFor\": \"start\",\n      \"parentClosePolicy\": \"abandon\"")
        assertEquals(told, IrJson.decode(after).nodes.single { it.id == "n1" })
    }

    @Test
    fun `what an upsert writes and that a loop is a retry are written where they are set, and nowhere else`() {
        val step = IrNode("n1", NodeKind.UPSERT_SEARCH_ATTRIBUTES)
        val loop = IrNode("n2", NodeKind.LOOP, label = "retry on failure")
        val before = document.copy(
            nodes = listOf(IrNode("n00", NodeKind.START), step, loop),
            edges = listOf(IrEdge("n00", "n1"), IrEdge("n1", "n2")),
            stats = IrStats(3, 0),
        )
        val text = IrJson.encode(before)

        assertFalse("\"keys\"" in text)
        assertFalse("\"retry\"" in text)
        assertEquals(before.normalized(), IrJson.decode(text))

        // The keys stay in the order the code gives them: they are not sorted like what an await reads.
        val told = before.copy(nodes = listOf(before.nodes[0], step.copy(keys = listOf("Stage", "Owner")), loop.copy(retry = true)))
        val after = IrJson.encode(told)
        assertContains(after, "\"kind\": \"upsertSearchAttributes\",\n      \"keys\": [\n        \"Stage\",\n        \"Owner\"\n      ]")
        assertContains(after, "\"label\": \"retry on failure\",\n      \"retry\": true")
        assertEquals(told.normalized(), IrJson.decode(after))
    }

    @Test
    fun `which lanes a region waits for and that an upsert writes more are written where they are set, and nowhere else`() {
        val region = IrNode("n1", NodeKind.PARALLEL)
        val step = IrNode("n2", NodeKind.UPSERT_MEMO, keys = listOf("note"))
        val before = document.copy(
            nodes = listOf(IrNode("n00", NodeKind.START), region, step),
            edges = listOf(IrEdge("n00", "n1"), IrEdge("n1", "n2")),
            stats = IrStats(3, 0),
        )
        val text = IrJson.encode(before)

        assertFalse("\"join\"" in text)
        assertFalse("\"otherKeys\"" in text)
        assertEquals(before.normalized(), IrJson.decode(text))

        val told = before.copy(nodes = listOf(before.nodes[0], region.copy(join = Join.FIRST), step.copy(otherKeys = true)))
        val after = IrJson.encode(told)
        assertContains(after, "\"kind\": \"parallel\",\n      \"join\": \"first\"")
        // That there is more follows the names that are known.
        assertContains(after, "\"keys\": [\n        \"note\"\n      ],\n      \"otherKeys\": true")
        assertEquals(told.normalized(), IrJson.decode(after))
    }

    @Test
    fun `calls that were skipped are written last, in order and each once, and only where there are some`() {
        val before = IrJson.encode(document)
        assertFalse("skippedCalls" in before)
        assertEquals(emptyList(), IrJson.decode(before).skippedCalls)

        val helper = SkippedCall(SourceLocation("orders/src/main/kotlin/Logs.kt", 9), "log.info { message }")
        val own = SkippedCall(SourceLocation("orders/src/main/kotlin/Order.kt", 12), "logger.info(\"paid\")")
        val sameLine = SkippedCall(SourceLocation("orders/src/main/kotlin/Logs.kt", 9), "log.atInfo()")
        val skipping = document.copy(modelMode = ModelMode.DEGRADED, skippedCalls = listOf(own, helper, sameLine, helper))
        val text = IrJson.encode(skipping)

        assertEquals(listOf(sameLine, helper, own), IrJson.decode(text).skippedCalls)
        assertEquals(skipping.normalized(), IrJson.decode(text))
        // The list follows the counts, at the end of the document.
        assertContains(
            text,
            "  \"stats\": {\n    \"nodes\": 10,\n    \"unknown\": 1\n  },\n  \"skippedCalls\": [\n    {\n      \"loc\": {\n" +
                "        \"file\": \"orders/src/main/kotlin/Logs.kt\",\n        \"line\": 9\n      },\n      \"text\": \"log.atInfo()\"\n    },",
        )
        assertTrue(text.endsWith("      \"text\": \"logger.info(\\\"paid\\\")\"\n    }\n  ]\n}\n"))
        // The nodes are counted as before: a call that was skipped is no node.
        assertEquals(document.stats, IrJson.decode(text).stats)
    }

    @Test
    fun `an edge from the workflow method to an await it wakes keeps its label`() {
        val woken = document.copy(edges = document.edges + IrEdge("main", "n02", EdgeKind.WAKES, "paid"))
        val text = IrJson.encode(woken)

        assertContains(text, "\"from\": \"main\",\n      \"to\": \"n02\",\n      \"kind\": \"wakes\",\n      \"label\": \"paid\"")
        assertEquals(woken.normalized(), IrJson.decode(text))
        assertEquals(emptyList(), woken.validate())
    }

    @Test
    fun `decode rejects an unsupported schema version`() {
        val text = IrJson.encode(document).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        val error = assertFailsWith<IrFormatException> { IrJson.decode(text) }
        assertContains(error.message.orEmpty(), "schemaVersion 2")
    }

    @Test
    fun `decode rejects unknown keys missing keys and non objects`() {
        val encoded = IrJson.encode(document)

        assertFailsWith<IrFormatException> { IrJson.decode(encoded.replace("\"commit\":", "\"revision\":")) }
        assertFailsWith<IrFormatException> { IrJson.decode(encoded.replace("\"kind\": \"await\"", "\"kind\": \"wait\"")) }
        assertFailsWith<IrFormatException> { IrJson.decode("[]") }
        assertFailsWith<IrFormatException> { IrJson.decode("{\"schemaVersion\": \"1\"}") }
        assertFailsWith<IrFormatException> { IrJson.decode("{ not json") }
    }
}
