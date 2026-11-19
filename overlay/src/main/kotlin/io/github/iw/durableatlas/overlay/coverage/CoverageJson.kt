package io.github.iw.durableatlas.overlay.coverage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes the JSON form of [WorkflowCoverage]. Keys are written in a fixed order, unset
 * optional properties are left out, and the text is indented by two spaces with `\n` line endings
 * and a trailing newline, so equal coverage always gives the same bytes.
 */
object CoverageJson {
    /** Classpath location of the JSON Schema (draft 2020-12) of the format. */
    const val SCHEMA_RESOURCE: String = "io/github/iw/durableatlas/overlay/workflow-coverage.schema.json"

    @OptIn(ExperimentalSerializationApi::class)
    private val format = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(coverage: WorkflowCoverage): String = format.encodeToString(WorkflowCoverage.serializer(), coverage) + "\n"

    /** @throws IllegalArgumentException if [text] is not coverage this version of the tool wrote */
    fun decode(text: String): WorkflowCoverage =
        try {
            format.decodeFromString(WorkflowCoverage.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Malformed workflow coverage: ${e.message}", e)
        }

    fun schema(): String {
        val stream = checkNotNull(CoverageJson::class.java.classLoader.getResourceAsStream(SCHEMA_RESOURCE)) {
            "Missing classpath resource $SCHEMA_RESOURCE"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
