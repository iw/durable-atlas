package io.github.iw.durableatlas.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NodeIdsTest {
    private val location = SourceLocation("orders/src/main/kotlin/OrderWorkflowImpl.kt", 42)

    @Test
    fun `ids are the letter n followed by ten hex digits`() {
        val id = NodeIds.of("main", location)

        assertTrue(Regex("n[0-9a-f]{10}").matches(id), id)
    }

    @Test
    fun `ids do not change between runs or releases`() {
        // Pinned values: changing the derivation would silently renumber every published diagram.
        assertEquals("naf6484136e", NodeIds.of("main", location))
        assertEquals("nf3098edbe1", NodeIds.of("main", location, listOf("Helper.kt:10", "Util.kt:3"), "join"))
        assertEquals("n522397cce3", NodeIds.of("sig-cancel", null, discriminator = "start"))
    }

    @Test
    fun `equal inputs give equal ids`() {
        assertEquals(
            NodeIds.of("main", location.copy(), listOf("a", "b"), "x"),
            NodeIds.of("main", SourceLocation(location.file, location.line), listOf("a", "b"), "x"),
        )
    }

    @Test
    fun `each input contributes to the id`() {
        val base = NodeIds.of("main", location, listOf("a"), "x")

        assertNotEquals(base, NodeIds.of("sig-cancel", location, listOf("a"), "x"))
        assertNotEquals(base, NodeIds.of("main", location.copy(line = 43), listOf("a"), "x"))
        assertNotEquals(base, NodeIds.of("main", location.copy(file = "Other.kt"), listOf("a"), "x"))
        assertNotEquals(base, NodeIds.of("main", null, listOf("a"), "x"))
        assertNotEquals(base, NodeIds.of("main", location, listOf("b"), "x"))
        assertNotEquals(base, NodeIds.of("main", location, listOf("a", "a"), "x"))
        assertNotEquals(base, NodeIds.of("main", location, emptyList(), "x"))
        assertNotEquals(base, NodeIds.of("main", location, listOf("a"), "y"))
    }

    @Test
    fun `text moving between adjacent inputs changes the id`() {
        assertNotEquals(NodeIds.of("ab", null, listOf("c")), NodeIds.of("a", null, listOf("bc")))
        assertNotEquals(NodeIds.of("m", null, listOf("ab", "c")), NodeIds.of("m", null, listOf("a", "bc")))
        assertNotEquals(NodeIds.of("m", null, listOf("a"), ""), NodeIds.of("m", null, emptyList(), "a"))
        assertNotEquals(
            NodeIds.of("m", SourceLocation("a", 1), listOf("b")),
            NodeIds.of("m", SourceLocation("ab", 1), emptyList()),
        )
    }

    @Test
    fun `a large workflow gets distinct ids`() {
        val ids = buildSet {
            for (entryPoint in listOf("main", "sig-cancel", "upd-address")) {
                for (file in 0 until 20) {
                    for (line in 1..200) {
                        for (path in listOf(emptyList(), listOf("Helper.kt:$line"))) {
                            add(NodeIds.of(entryPoint, SourceLocation("module$file/Workflow.kt", line), path))
                        }
                    }
                }
            }
        }

        assertEquals(3 * 20 * 200 * 2, ids.size)
    }
}
