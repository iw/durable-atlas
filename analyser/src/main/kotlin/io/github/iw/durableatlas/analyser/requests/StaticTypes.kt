package io.github.iw.durableatlas.analyser.requests

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.analyser.code.Code
import io.github.iw.durableatlas.analyser.code.Constant
import io.github.iw.durableatlas.analyser.code.Literal
import io.github.iw.durableatlas.analyser.code.LocalRead
import io.github.iw.durableatlas.analyser.code.LocalVariable
import io.github.iw.durableatlas.analyser.code.Other
import io.github.iw.durableatlas.analyser.code.PropertyRead
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.ThisReference
import io.github.iw.durableatlas.analyser.code.TypeRef
import io.github.iw.durableatlas.analyser.graph.Trail
import io.github.iw.durableatlas.ir.TypeName

/**
 * The static types of expressions, as the requests write them: Kotlin's notation with fully
 * qualified names (see `RequestParameter`). A class that does not resolve is named as written, by
 * the name an import of the file gives it where there is one. A type parameter of the function the
 * code runs in stands for the type the call into it gave it, where that is known.
 */
internal class StaticTypes(private val backend: SourceBackend) {
    private val imports = HashMap<Pair<String, String>, String?>()

    /** The type [code] says it has, as far as the tree tells it. */
    fun of(code: Code): TypeRef? =
        when (code) {
            is LocalRead -> code.type
            is PropertyRead -> code.type
            is Call -> code.type ?: writtenType(code)
            is ThisReference -> code.type
            is LocalVariable -> code.type
            is Other -> code.type ?: when (code.operator) {
                TEMPLATE -> STRING
                else -> null
            }
            is Literal -> when (val constant = code.constant) {
                is Constant.Text -> STRING
                is Constant.Integer -> if (constant.value in Int.MIN_VALUE..Int.MAX_VALUE) TypeRef("kotlin.Int", "kotlin.Int") else TypeRef("kotlin.Long", "kotlin.Long")
                is Constant.Decimal -> TypeRef("kotlin.Double", "kotlin.Double")
                is Constant.Bool -> TypeRef("kotlin.Boolean", "kotlin.Boolean")
                else -> null
            }
            else -> null
        }

    /**
     * The most telling type along the way a value was followed back: the first that names a
     * class other than `Any`. A value handed to a parameter of type `Any` or `T` has the type of
     * the argument it was given.
     */
    fun along(trail: Trail): TypeRef? {
        var found: TypeRef? = null
        for (step in trail.passed) {
            val type = of(step.code) ?: continue
            if (found == null) found = type
            if (telling(type)) return type
        }
        return found
    }

    /** [type] as requests write it; `null` for a type that cannot be told. */
    fun name(type: TypeRef?, place: Place?, file: String?): String? = type?.let { typeName(it, place, file, 0) }?.toString()

    /** Whether [type] names a class other than `Any`. */
    fun telling(type: TypeRef): Boolean =
        (type.name != null && type.name != ANY && type.name != OBJECT) || type.unresolved != null || ERROR_CLASS.containsMatchIn(type.text)

    private fun typeName(type: TypeRef, place: Place?, file: String?, depth: Int): TypeName? {
        if (depth > MAX_DEPTH) return null
        val arguments = type.arguments.map { argument -> typeName(argument, place, file, depth + 1) ?: return null }
        val name = type.name
        if (name != null) return TypeName(name, arguments, type.nullable)
        if (type.text == TypeName.STAR) return TypeName(TypeName.STAR)
        type.unresolved?.let { written -> return TypeName(qualified(written, file), arguments, type.nullable) }
        ERROR_CLASS.find(type.text)?.let { match -> return TypeName(qualified(match.groupValues[1], file), arguments, type.text.endsWith("?")) }
        // A type parameter: what the call gave it, where that is known.
        val parameter = type.text.removeSuffix("?")
        if (!PARAMETER.matches(parameter)) return null
        val bound = place?.typeBindings?.get(parameter)?.let { typeName(it, null, file, depth + 1) }
        return when {
            bound == null -> TypeName(parameter, nullable = type.nullable)
            type.nullable -> bound.copy(nullable = true)
            else -> bound
        }
    }

    /** A name as written, qualified by an import of [file] where it has no package. */
    fun qualified(written: String, file: String?): String {
        if ('.' in written && written.first().isLowerCase()) return written
        if (file == null) return written
        val first = written.substringBefore('.')
        val imported = imports.getOrPut(file to first) { backend.imported(file, first) } ?: return written
        return imported + written.removePrefix(first)
    }

    /** The type a call that did not resolve says it gives, where its name tells it. */
    private fun writtenType(call: Call): TypeRef? =
        if (call.callee == null && call.name in TEXT_FUNCTIONS) STRING else null

    companion object {
        private const val ANY = "kotlin.Any"
        private const val OBJECT = "java.lang.Object"
        private const val TEMPLATE = "template"
        private const val MAX_DEPTH = 16
        val STRING = TypeRef("kotlin.String", "kotlin.String")
        private val ERROR_CLASS = Regex("ERROR CLASS: Symbol not found for ([\\w.]+)")
        private val PARAMETER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Functions of the libraries that read and write JSON whose result is text. */
        private val TEXT_FUNCTIONS = setOf("writeValueAsString", "encodeToString")
    }
}
