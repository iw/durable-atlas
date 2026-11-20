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
    /** Fully qualified name of the annotation class, as resolved; for a [written] one, the name as written. */
    val type: String,
    /** Explicit arguments by parameter name. Parameters left at their default are absent. */
    val arguments: Map<String, Constant> = emptyMap(),
    /**
     * True for an annotation of the sources whose class does not resolve, as in a model that
     * lacks the library that declares it. Its [type] is then the name it is written with, and
     * its arguments are those written out that are constants, an argument without a name being
     * `value`. Only the declarations of the sources carry such annotations, and not the types.
     */
    val written: Boolean = false,
) {
    /** The text argument [name], or `null` if it is absent or not text. */
    fun text(name: String): String? = (arguments[name] as? Constant.Text)?.value
}

/** Finds the annotation of the given fully qualified type, among those whose class resolves. */
fun List<AnnotationUse>.find(type: String): AnnotationUse? = firstOrNull { !it.written && it.type == type }

/**
 * Finds the annotation of the given fully qualified type, or else one whose class does not
 * resolve and that is written with the simple name of that type or with the type itself.
 */
fun List<AnnotationUse>.findWritten(type: String): AnnotationUse? =
    find(type) ?: firstOrNull { it.written && writtenAs(it.type, type) }

/** Whether an annotation written as [name] names [type] where its class does not resolve: by the type itself, or by its simple name. */
private fun writtenAs(name: String, type: String): Boolean = name == type || name == type.substringAfterLast('.')

/**
 * The annotations the analysis reads where their class does not resolve, by the name they are
 * written with ([findWritten]): those that MicroProfile Config and SmallRye Config give the keys
 * of configuration properties with. An annotation of the sources that does not resolve is worth
 * a warning, since what depends on it is not recognised, unless it is one of these.
 */
object WrittenAnnotations {
    const val CONFIG_PROPERTY = "org.eclipse.microprofile.config.inject.ConfigProperty"
    const val CONFIG_MAPPING = "io.smallrye.config.ConfigMapping"
    const val WITH_NAME = "io.smallrye.config.WithName"
    const val WITH_DEFAULT = "io.smallrye.config.WithDefault"

    private val types = listOf(CONFIG_PROPERTY, CONFIG_MAPPING, WITH_NAME, WITH_DEFAULT)

    /** Whether an annotation whose class does not resolve, written as [name], is read all the same. */
    fun isRead(name: String): Boolean = types.any { writtenAs(name, it) }
}

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
