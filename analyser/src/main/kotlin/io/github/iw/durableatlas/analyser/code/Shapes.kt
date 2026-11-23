package io.github.iw.durableatlas.analyser.code

import io.github.iw.durableatlas.ir.SourceLocation

/**
 * What a serialiser can see of a class: its properties with what decides whether and how they
 * are written, and its entries if it is an enum. See
 * [SourceBackend.shape].
 */
data class ClassShape(
    /** Fully qualified name. */
    val name: String,
    val kind: ClassKind,
    /** True for a sealed class or interface. */
    val isSealed: Boolean,
    /** True for an abstract class, a sealed one and an interface. */
    val isAbstract: Boolean,
    /** The names of the type parameters, in order. */
    val typeParameters: List<String>,
    /** The annotations of the class; for one of the sources, those whose class does not resolve by the names they are written with. */
    val annotations: List<AnnotationUse>,
    /**
     * The properties the class declares itself, in the order of the class: those of its primary
     * constructor first, then those of its body.
     */
    val properties: List<ShapeProperty>,
    /** For an enum class, its entries in order. */
    val entries: List<ShapeEntry>,
    /** Where the class is declared; `null` for a class from a library. */
    val location: SourceLocation?,
)

/** A property as a serialiser sees it. */
data class ShapeProperty(
    val name: String,
    /**
     * Its type. A class that does not resolve is named, at every level of the type, by
     * [TypeRef.unresolved]: the name an import of the file gives it, or else the name as written.
     */
    val type: TypeRef,
    /**
     * The annotations written on the property, whatever they apply to (the property, its field,
     * its getter or the constructor parameter it is declared with); those whose class does not
     * resolve by the names they are written with.
     */
    val annotations: List<AnnotationUse>,
    /** True if it is declared as a parameter of the primary constructor. */
    val inConstructor: Boolean,
    /** True if it is given a default value, so that an object can be made without it. */
    val hasDefault: Boolean,
    val isPublic: Boolean,
    /** True if the property holds a value of its own, rather than computing it in a getter. */
    val hasBackingField: Boolean,
)

/** An entry of an enum class. */
data class ShapeEntry(val name: String, val annotations: List<AnnotationUse>)
