package io.github.iw.durableatlas.analyser.inventory

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Thrown when text is not an inventory this version of the tool can read. */
class InventoryFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Reads and writes the JSON form of [Inventory]. Output is deterministic: fixed key order, unset
 * optional properties left out, two-space indentation, `\n` line endings and a trailing newline.
 */
object InventoryJson {
    /** Name of the inventory file inside the directory the analyser writes to. */
    const val FILE_NAME: String = "inventory.json"

    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(inventory: Inventory): String = format.encodeToString(Inventory.serializer(), inventory) + "\n"

    fun decode(text: String): Inventory {
        val inventory = try {
            format.decodeFromString(Inventory.serializer(), text)
        } catch (e: SerializationException) {
            throw InventoryFormatException("Malformed inventory: ${e.message}", e)
        }
        if (inventory.schemaVersion != Inventory.SCHEMA_VERSION) {
            throw InventoryFormatException(
                "Unsupported inventory schemaVersion ${inventory.schemaVersion} (supported: ${Inventory.SCHEMA_VERSION})",
            )
        }
        return inventory
    }
}
