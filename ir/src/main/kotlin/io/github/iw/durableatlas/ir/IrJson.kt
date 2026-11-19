package io.github.iw.durableatlas.ir

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Thrown when text is not a workflow IR document this version of the tool can read. */
class IrFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Reads and writes the JSON form of [IrDocument].
 *
 * Output is deterministic: the document is put into canonical order (see [normalized]), keys are
 * written in a fixed order, unset optional properties are left out, and the text is indented by
 * two spaces with `\n` line endings and a trailing newline.
 */
object IrJson {
    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(document: IrDocument): String =
        format.encodeToString(IrDocument.serializer(), document.normalized()) + "\n"

    /**
     * Parses [text] strictly: unknown keys, missing required keys and unsupported schema versions
     * are all rejected with an [IrFormatException]. The result is returned as written, without
     * reordering.
     */
    fun decode(text: String): IrDocument {
        try {
            val root = format.parseToJsonElement(text) as? JsonObject
                ?: throw IrFormatException("Workflow IR must be a JSON object")
            val version = (root["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw IrFormatException("Workflow IR has no integer schemaVersion")
            if (version != IrDocument.SCHEMA_VERSION) {
                throw IrFormatException(
                    "Unsupported workflow IR schemaVersion $version (supported: ${IrDocument.SCHEMA_VERSION})",
                )
            }
            return format.decodeFromJsonElement(IrDocument.serializer(), root)
        } catch (e: SerializationException) {
            throw IrFormatException("Malformed workflow IR: ${e.message}", e)
        }
    }
}
