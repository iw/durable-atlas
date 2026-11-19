package io.github.iw.durableatlas.analyser.inventory

/** Access to the JSON Schema (draft 2020-12) that describes the JSON form of [Inventory]. */
object InventorySchema {
    /** Classpath location of the schema. */
    const val RESOURCE: String = "io/github/iw/durableatlas/analyser/inventory/workflow-inventory.schema.json"

    fun text(): String {
        val stream = checkNotNull(InventorySchema::class.java.classLoader.getResourceAsStream(RESOURCE)) {
            "Missing classpath resource $RESOURCE"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
