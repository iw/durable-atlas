package io.github.iw.durableatlas.render.endpoints

import io.github.iw.durableatlas.ir.ModelType
import io.github.iw.durableatlas.ir.Request
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the endpoints page takes from the inventory that the analysis writes next to the workflow
 * documents: the requests of every activity type, also of those no workflow of the sources runs,
 * the interface each is declared in, and the `types` table of the models the requests send and
 * receive. Read as plain JSON, so that rendering needs nothing of the analysis but its files.
 */
internal class InventoryPart(
    val commit: String?,
    val activities: List<InventoryActivity>,
    val types: Map<String, ModelType>,
) {
    class InventoryActivity(val interfaceName: String, val name: String, val requests: List<Request>)

    companion object {
        /** All of [parts] as one, the activities in the order of the parts and each type as the first part that has it gives it; `null` for none. */
        fun merged(parts: List<InventoryPart>): InventoryPart? =
            when (parts.size) {
                0 -> null
                1 -> parts.single()
                else -> InventoryPart(
                    parts.mapNotNull { it.commit }.distinct().singleOrNull(),
                    parts.flatMap { it.activities },
                    parts.fold(LinkedHashMap<String, ModelType>()) { all, part -> part.types.forEach { (name, type) -> all.putIfAbsent(name, type) }; all },
                )
            }

        @OptIn(ExperimentalSerializationApi::class)
        private val format = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        /**
         * The part of [text] the page uses, where it is an inventory: a JSON object with an array of
         * `activityInterfaces` or a `types` object; `null` for any other JSON file.
         *
         * @throws IllegalArgumentException where it looks like an inventory but its requests or
         *   types cannot be read
         */
        fun read(text: String): InventoryPart? {
            val root = try {
                format.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            val interfaces = root["activityInterfaces"] as? JsonArray
            val types = root["types"] as? JsonObject
            if (interfaces == null && types == null) return null
            try {
                val activities = interfaces.orEmpty().flatMap { element ->
                    val declared = element as? JsonObject ?: return@flatMap emptyList()
                    val name = (declared["interface"] as? JsonPrimitive)?.content ?: return@flatMap emptyList()
                    (declared["activities"] as? JsonArray).orEmpty().mapNotNull { activity ->
                        val entry = activity as? JsonObject ?: return@mapNotNull null
                        val type = (entry["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val requests = entry["requests"]?.let { format.decodeFromJsonElement(ListSerializer(Request.serializer()), it) }.orEmpty()
                        InventoryActivity(name, type, requests)
                    }
                }
                val table = types.orEmpty().mapValues { (_, value) -> format.decodeFromJsonElement(ModelType.serializer(), value) }
                return InventoryPart((root["commit"] as? JsonPrimitive)?.content, activities, table)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("The requests or types of the inventory cannot be read: ${e.message}", e)
            }
        }
    }
}
