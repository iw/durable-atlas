package io.github.iw.durableatlas.render.svg

/** Builds the targets of links in a diagram. */
internal object Links {
    private const val UNRESERVED = "-._~"

    /** A relative path as a URL: every segment is percent-encoded, the slashes stay. */
    fun encodePath(path: String): String = path.replace('\\', '/').split('/').joinToString("/", transform = ::encodeSegment)

    fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (byte >= 0 && (char.isLetterOrDigit() || char in UNRESERVED)) {
                out.append(char)
            } else {
                out.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return out.toString()
    }
}
