package io.github.iw.durableatlas.ir.testing

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.iw.durableatlas.ir.IrSchema
import kotlin.test.fail

/**
 * Validates JSON text against a JSON Schema (draft 2020-12). Shared by the tests of every module
 * that writes a schema-backed file.
 */
class JsonSchemaChecker private constructor(private val schema: Schema) {
    /** Returns one message per violation, sorted; an empty list means [json] is valid. */
    fun violations(json: String): List<String> =
        schema.validate(json, InputFormat.JSON)
            .map { error -> "${error.instanceLocation}: ${error.message}" }
            .sorted()

    fun assertValid(json: String) {
        val violations = violations(json)
        if (violations.isNotEmpty()) {
            fail("JSON does not match its schema:\n" + violations.joinToString("\n") { "  $it" })
        }
    }

    companion object {
        fun forSchema(schemaJson: String): JsonSchemaChecker =
            JsonSchemaChecker(
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaJson),
            )

        /** Loads the schema from the classpath of the calling test. */
        fun forResource(path: String): JsonSchemaChecker {
            val loader = Thread.currentThread().contextClassLoader ?: JsonSchemaChecker::class.java.classLoader
            val stream = requireNotNull(loader.getResourceAsStream(path)) { "Missing classpath resource $path" }
            return forSchema(stream.use { it.readBytes().toString(Charsets.UTF_8) })
        }

        /** Checker for workflow IR documents. */
        fun forWorkflowIr(): JsonSchemaChecker = forSchema(IrSchema.text())
    }
}
