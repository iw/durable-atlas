package io.github.iw.durableatlas.render.svg

/** Escapes text for XML. Characters that XML 1.0 cannot carry become U+FFFD. */
internal object Xml {
    fun text(value: String): String = escape(value, attribute = false)

    fun attribute(value: String): String = escape(value, attribute = true)

    private fun escape(value: String, attribute: Boolean): String {
        val out = StringBuilder(value.length + 16)
        value.codePoints().forEach { point ->
            when {
                point == '&'.code -> out.append("&amp;")
                point == '<'.code -> out.append("&lt;")
                point == '>'.code -> out.append("&gt;")
                attribute && point == '"'.code -> out.append("&quot;")
                attribute && (point == '\n'.code || point == '\r'.code || point == '\t'.code) -> out.append("&#").append(point).append(';')
                !attribute && point == '\r'.code -> out.append("&#13;")
                isAllowed(point) -> out.appendCodePoint(point)
                else -> out.append(REPLACEMENT)
            }
        }
        return out.toString()
    }

    private fun isAllowed(point: Int): Boolean =
        point == 0x9 || point == 0xA || point == 0xD || point in 0x20..0xD7FF || point in 0xE000..0xFFFD || point in 0x10000..0x10FFFF

    private const val REPLACEMENT = '�'
}
