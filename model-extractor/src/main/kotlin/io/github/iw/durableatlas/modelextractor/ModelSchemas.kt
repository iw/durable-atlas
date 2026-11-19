package io.github.iw.durableatlas.modelextractor

/** Access to the JSON Schemas (draft 2020-12) of the files that make up a model. */
object ModelSchemas {
    /** Classpath location of the schema of a [ModuleManifest]. */
    const val MANIFEST_RESOURCE: String = "io/github/iw/durableatlas/modelextractor/module-manifest.schema.json"

    /** Classpath location of the schema of a [ModelIndex]. */
    const val INDEX_RESOURCE: String = "io/github/iw/durableatlas/modelextractor/model-index.schema.json"

    fun manifestText(): String = Resources.text(MANIFEST_RESOURCE)

    fun indexText(): String = Resources.text(INDEX_RESOURCE)
}

internal object Resources {
    fun bytes(path: String): ByteArray {
        val stream = checkNotNull(Resources::class.java.classLoader.getResourceAsStream(path)) {
            "Missing classpath resource $path"
        }
        return stream.use { it.readBytes() }
    }

    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)
}
