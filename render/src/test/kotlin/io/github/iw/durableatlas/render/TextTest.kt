package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.render.layout.Durations
import io.github.iw.durableatlas.render.layout.singleLine
import io.github.iw.durableatlas.render.markdown.Markdown
import io.github.iw.durableatlas.render.mermaid.MermaidIds
import io.github.iw.durableatlas.render.mermaid.MermaidText
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {
    @Test
    fun `text from a document becomes one line`() {
        assertEquals("a b c", "  a\n\tb\r\n   c  ".singleLine())
        assertEquals("a b", "a\u2028\u0007\u00a0b".singleLine())
        assertEquals("", " \n ".singleLine())
        assertEquals("x\uFFFDy", "x\uD83Dy".singleLine())
        assertEquals("😀", "😀".singleLine())
    }

    @Test
    fun `long text is cut by characters and ends in an ellipsis`() {
        assertEquals("abcdefgh", "abcdefgh".singleLine(8))
        assertEquals("abcdefg…", "abcdefghi".singleLine(8))
        assertEquals("abc def…", "abc def  ghi jkl".singleLine(8))
        assertEquals("ab…", "ab cdef".singleLine(4))
        assertEquals("😀😀😀…", "😀😀😀😀😀".singleLine(4))
    }

    @Test
    fun `characters with a meaning in Mermaid become entity codes`() {
        assertEquals("\"plain text, (round) [square] {curly} a=b a+b a-b a/b it's ok?!\"", MermaidText.quote("plain text, (round) [square] {curly} a=b a+b a-b a/b it's ok?!"))
        assertEquals("\"#quot;a#quot; #lt; b #amp;#amp; c #gt; d\"", MermaidText.quote("\"a\" < b && c > d"))
        assertEquals("\"#35;1#59; x#58; y #124; z\"", MermaidText.quote("#1; x: y | z"))
        assertEquals("\"#96;k#96; #37;#37;{init}#37;#37; #36;#36;x#36;#36; C#58;#92;temp\"", MermaidText.quote("`k` %%{init}%% \$\$x\$\$ C:\\temp"))
        assertEquals("\"naïve café → 日本語 ✓ 😀\"", MermaidText.quote("naïve café → 日本語 ✓ 😀"))
        assertEquals("\"a b\"", MermaidText.quote("a\nb"))
        assertEquals("\"one<br/>#lt;br/#gt;\"", MermaidText.quote(listOf("one", "<br/>")))
    }

    @Test
    fun `escaped text never contains a character that ends a label or starts something else`() {
        val every = (0x20..0x7e).map { it.toChar() }.joinToString("")
        val escaped = MermaidText.escape(every)
        val withoutEntities = escaped.replace(Regex("#(?:quot|amp|lt|gt|\\d+);"), "")
        assertEquals(emptyList(), withoutEntities.filter { it in "\"#;:|`%$\\<>&" }.toList())
        assertEquals(every.length - 12, withoutEntities.length)
    }

    @Test
    fun `node ids of the usual form are kept and others are rewritten without clashes`() {
        val ids = MermaidIds(listOf("n1f3a", "n1", "end", "a b", "a_b", "x\"y", "é", "n_a_b", ""))
        assertEquals("n1f3a", ids.node("n1f3a"))
        assertEquals("n1", ids.node("n1"))
        assertEquals("n_a_b", ids.node("n_a_b"))
        assertEquals("n_end", ids.node("end"))
        assertEquals("n_a_b_2", ids.node("a b"))
        assertEquals("n_a_b_3", ids.node("a_b"))
        assertEquals("n_x_y", ids.node("x\"y"))
        assertEquals("n__", ids.node("é"))
        assertEquals("n_", ids.node(""))
        assertEquals("n1_join", ids.derived("n1_join"))
        assertEquals("n1_join_2", ids.derived("n1_join"))
        assertEquals("h_sig_cancel", ids.derived("h_sig-cancel"))
        assertEquals("n1_2", ids.derived("n1"))
    }

    @Test
    fun `durations are shortened when they can be`() {
        assertEquals("30m", Durations.humanise("PT30M"))
        assertEquals("1d 2h", Durations.humanise("P1DT2H"))
        assertEquals("1h 30m 5s", Durations.humanise("PT1H30M5S"))
        assertEquals("1s 500ms", Durations.humanise("PT1.5S"))
        assertEquals("2d", Durations.humanise("PT48H"))
        assertEquals("0s", Durations.humanise("PT0S"))
        assertEquals("PT-5S", Durations.humanise("PT-5S"))
        assertEquals("PT0.0000001S", Durations.humanise("PT0.0000001S"))
        assertEquals("\${config.delay}", Durations.humanise("\${config.delay}"))
    }

    @Test
    fun `Markdown shows text as it is`() {
        assertEquals("`plain`", Markdown.code("plain"))
        assertEquals("``a `b` c``", Markdown.code("a `b` c"))
        assertEquals("`` `b` ``", Markdown.code("`b`"))
        assertEquals("` `", Markdown.code(""))
        assertEquals("`a b`", Markdown.code("a\nb"))
        assertEquals("Odd|Workflow\\<T\\> \\#1 \\*beta\\* \\`v2\\` \\[x\\]\\_y \\& z \\\\ \\~", Markdown.text("Odd|Workflow<T> #1 *beta* `v2` [x]_y & z \\ ~"))
        assertEquals("[a b \\[1\\].md](a%20b%20%5B1%5D.md)", Markdown.fileLink("a b [1].md"))
        assertEquals("[Größe.md](Gr%C3%B6%C3%9Fe.md)", Markdown.fileLink("Größe.md"))
        assertEquals("| `a\\|b` | c |", Markdown.row("`a|b`", "c"))
    }
}
