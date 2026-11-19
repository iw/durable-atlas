package io.github.iw.durableatlas.modelextractor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Thrown when text is not a manifest or index this version of the tool can read. */
class ModelFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Reads and writes the JSON form of [ModuleManifest] and [ModelIndex]. Output is deterministic:
 * fixed key order, two-space indentation, `\n` line endings and a trailing newline.
 */
object ModelJson {
    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(manifest: ModuleManifest): String = format.encodeToString(ModuleManifest.serializer(), manifest) + "\n"

    fun encode(index: ModelIndex): String = format.encodeToString(ModelIndex.serializer(), index) + "\n"

    fun decodeManifest(text: String): ModuleManifest =
        decode(ModuleManifest.serializer(), text, "module manifest").also {
            checkVersion(it.schemaVersion, ModuleManifest.SCHEMA_VERSION, "module manifest")
        }

    fun decodeIndex(text: String): ModelIndex =
        decode(ModelIndex.serializer(), text, "model index").also {
            checkVersion(it.schemaVersion, ModelIndex.SCHEMA_VERSION, "model index")
        }

    private fun <T> decode(serializer: KSerializer<T>, text: String, what: String): T =
        try {
            format.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw ModelFormatException("Malformed $what: ${e.message}", e)
        }

    private fun checkVersion(actual: Int, supported: Int, what: String) {
        if (actual != supported) {
            throw ModelFormatException("Unsupported $what schemaVersion $actual (supported: $supported)")
        }
    }
}
