package io.github.iw.durableatlas.analyser.code

import io.github.iw.durableatlas.ir.SourceLocation

enum class ClassKind { CLASS, INTERFACE, OBJECT, ENUM, ANNOTATION }

/** A class, interface or object: one declared in the analysed sources, or one found in a library. */
data class ClassDecl(
    /** Fully qualified name. */
    val name: String,
    /** The name without package and outer classes. */
    val simpleName: String,
    val kind: ClassKind,
    /** True for classes that cannot be instantiated: abstract and sealed ones. */
    val isAbstract: Boolean,
    /** Path of the module whose sources declare the class; `null` for a class from a library. */
    val module: String?,
    /** Where the class is declared; `null` for a class from a library. */
    val location: SourceLocation?,
    val annotations: List<AnnotationUse>,
    /** Fully qualified names of the direct supertypes, in declaration order, without `kotlin.Any`. */
    val superTypes: List<String>,
    /** Every supertype, direct or not, nearest first and without `kotlin.Any`. */
    val allSuperTypes: List<String>,
) {
    val isSource: Boolean get() = module != null
}

/**
 * Identifies a function among the analysed sources and their libraries.
 *
 * Two functions with the same [name] differ in their [signature] or [receiver]. Only private
 * top-level functions of different files and local functions can share all three; [file] tells
 * those apart.
 */
data class FunctionId(
    /**
     * Fully qualified name: `package.Class.function`, `package.function`, or `package.Class.<init>`.
     * A function declared inside another function, or in an anonymous object, is `<local>.function`.
     */
    val name: String,
    /** Fully qualified name of the class that declares the function; `null` for top-level and local functions. */
    val owner: String?,
    /** The erased types of the parameters, such as `(kotlin.String,kotlin.Int)`. */
    val signature: String,
    /** The erased type of the extension receiver, for an extension function. */
    val receiver: String? = null,
    /**
     * Where the function is declared, for the functions a name does not identify: the file of a
     * private top-level function, relative to the root of the analysed repository, and the file
     * and line (`path:line`) of a local one.
     */
    val file: String? = null,
) {
    /** The name without package and class. */
    val simpleName: String get() = name.substringAfterLast('.')

    override fun toString(): String = name + signature + receiver?.let { " on $it" }.orEmpty()
}

data class ParameterDecl(
    val name: String,
    val type: TypeRef,
)

/** A function, without its body; see [SourceBackend.body]. */
data class FunctionDecl(
    val id: FunctionId,
    val name: String,
    /** Path of the module whose sources declare the function; `null` for a function from a library. */
    val module: String?,
    val location: SourceLocation?,
    val annotations: List<AnnotationUse>,
    /** Type of the extension receiver. */
    val receiver: TypeRef?,
    val parameters: List<ParameterDecl>,
    val returnType: TypeRef,
    /** Every function this one overrides, directly or not, nearest first. */
    val overrides: List<FunctionId>,
    /** True if the declaration has no body. */
    val isAbstract: Boolean,
)

/**
 * Identifies a property: a member of [owner], or a top-level one. Only private top-level
 * properties of different files can share a name; [file] tells those apart.
 */
data class PropertyId(
    /** Fully qualified name: `package.Class.property` or `package.property`. */
    val name: String,
    /** Fully qualified name of the class that declares the property; `null` for a top-level property. */
    val owner: String?,
    /** The file of a private top-level property, relative to the root of the analysed repository. */
    val file: String? = null,
) {
    val simpleName: String get() = name.substringAfterLast('.')

    override fun toString(): String = name
}

/** A property of a class, without its initialiser; see [SourceBackend.initializer]. */
data class PropertyDecl(
    val id: PropertyId,
    val name: String,
    val location: SourceLocation?,
    val annotations: List<AnnotationUse>,
    val type: TypeRef,
    /** True for `var`. */
    val isMutable: Boolean,
    /** True if the property is declared as a constructor parameter. */
    val isConstructorParameter: Boolean,
    /** True if the property has an initialiser or a delegate expression. */
    val hasInitializer: Boolean,
)

/** A constructor of a class of the sources, without what it runs; see [SourceBackend.construction]. */
data class ConstructorDecl(
    /** `package.Class.<init>` with the types of the parameters. */
    val id: FunctionId,
    /** Where the constructor is written, or the class, for a constructor that is not written out. */
    val location: SourceLocation?,
    val annotations: List<AnnotationUse>,
    val parameters: List<ParameterDecl>,
    /** True if the constructor can be called without arguments: it has no parameter without a default value. */
    val takesNoArguments: Boolean,
)

/** What a constructor of a class of the sources runs when an object is made through it, in this order. */
data class Construction(
    /**
     * The constructor this one hands over to before anything else: another one of its class, or
     * one of the superclass. `null` if it names none that could be resolved.
     */
    val delegate: Call?,
    /**
     * What the body of the class runs for every new object, in source order: an assignment for
     * each property with an initialiser, the expression a delegated property is made with, and a
     * block for each `init`. Empty for a constructor that hands over to another one of its
     * class, which runs them.
     */
    val initializers: List<Code>,
    /** The body of a constructor that has one. */
    val body: Block?,
)
