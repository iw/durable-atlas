package io.github.iw.durableatlas.analyser.code

/** A compile-time value: an annotation argument, or an expression the compiler can evaluate. */
sealed interface Constant {
    /** A string or a character. */
    data class Text(val value: String) : Constant

    /** Any integral value. */
    data class Integer(val value: Long) : Constant

    data class Decimal(val value: Double) : Constant

    data class Bool(val value: Boolean) : Constant

    data object Null : Constant

    data class EnumEntry(val type: String, val entry: String) : Constant

    /** A class literal, by the fully qualified name of the class. */
    data class ClassReference(val name: String) : Constant

    data class Array(val elements: List<Constant>) : Constant

    data class Annotation(val use: AnnotationUse) : Constant

    /** A value the analysis could not evaluate; [text] is its source text where known. */
    data class Unsupported(val text: String) : Constant
}

/** An annotation on a declaration, with the arguments given where it is used. */
data class AnnotationUse(
    /** Fully qualified name of the annotation class, as resolved. */
    val type: String,
    /** Explicit arguments by parameter name. Parameters left at their default are absent. */
    val arguments: Map<String, Constant> = emptyMap(),
) {
    /** The text argument [name], or `null` if it is absent or not text. */
    fun text(name: String): String? = (arguments[name] as? Constant.Text)?.value
}

/** Finds the annotation of the given fully qualified type. */
fun List<AnnotationUse>.find(type: String): AnnotationUse? = firstOrNull { it.type == type }

/** A resolved type. */
data class TypeRef(
    /**
     * Fully qualified name of the class the type refers to, or `null` if it is not a class type
     * (a type parameter, for instance) or could not be resolved.
     */
    val name: String?,
    /** The type as the compiler renders it, with fully qualified names. */
    val text: String,
    val nullable: Boolean = false,
    val arguments: List<TypeRef> = emptyList(),
    /** Annotations on the declaration of the class [name]. */
    val annotations: List<AnnotationUse> = emptyList(),
    /** The name, as written, of the class the type refers to, where that class could not be resolved. */
    val unresolved: String? = null,
)
