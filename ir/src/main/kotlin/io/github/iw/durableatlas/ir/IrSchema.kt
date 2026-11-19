package io.github.iw.durableatlas.ir

/** Access to the JSON Schema (draft 2020-12) that describes the JSON form of [IrDocument]. */
object IrSchema {
    /** Classpath location of the schema. */
    const val RESOURCE: String = "io/github/iw/durableatlas/ir/workflow-ir.schema.json"

    fun text(): String {
        val stream = checkNotNull(IrSchema::class.java.classLoader.getResourceAsStream(RESOURCE)) {
            "Missing classpath resource $RESOURCE"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
