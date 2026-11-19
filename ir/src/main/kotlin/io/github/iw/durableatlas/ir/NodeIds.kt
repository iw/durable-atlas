package io.github.iw.durableatlas.ir

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Derives node ids from what a node is, not from the order in which it was discovered, so an
 * unchanged workflow keeps its ids from one commit to the next.
 */
object NodeIds {
    /** Ids look like `n` followed by this many lowercase hex digits. */
    const val HEX_LENGTH: Int = 10

    private const val PREFIX = "n"

    /**
     * @param entryPointId id of the entry point whose graph the node belongs to
     * @param location source position that produced the node, or null for synthetic nodes
     * @param inliningPath one element per call site that was inlined to reach the node, outermost
     *   first; empty for code in the entry point's own body
     * @param discriminator tells apart several nodes with the same location and path, for example
     *   a role (`start`, `end`, `join`) or a column number
     */
    fun of(
        entryPointId: String,
        location: SourceLocation?,
        inliningPath: List<String> = emptyList(),
        discriminator: String = "",
    ): String {
        // Every field is length-prefixed, so no two distinct inputs share an encoding.
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeField(entryPointId)
            out.writeBoolean(location != null)
            if (location != null) {
                out.writeField(location.file)
                out.writeInt(location.line)
            }
            out.writeInt(inliningPath.size)
            inliningPath.forEach { out.writeField(it) }
            out.writeField(discriminator)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        return digest.take(HEX_LENGTH / 2).joinToString(separator = "", prefix = PREFIX) { "%02x".format(it) }
    }

    private fun DataOutputStream.writeField(value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        writeInt(utf8.size)
        write(utf8)
    }
}
