package io.github.iw.durableatlas.analyser.code

import io.github.iw.durableatlas.ir.SourceLocation
import java.nio.file.Path

/**
 * Resolved view of a set of Kotlin sources: their declarations, and the code of their functions
 * with every name resolved. This is the only way the rest of the tool looks at Kotlin; whatever
 * does the parsing and resolving stays behind it.
 *
 * Everything returned is plain data that stays valid after [close]. The methods themselves must
 * not be called after [close], and an instance is to be used from one thread at a time.
 */
interface SourceBackend : AutoCloseable {
    /**
     * Every class, interface and object declared in the Kotlin sources, nested ones included,
     * local and anonymous ones not, ordered by name.
     */
    fun sourceClasses(): List<ClassDecl>

    /**
     * The class with this fully qualified name, from the sources or from a library; `null` if
     * there is none. A class of the Java sources has its module and its place, like one of the
     * Kotlin sources.
     */
    fun findClass(name: String): ClassDecl?

    /**
     * The functions a class declares itself, in a stable order: source order for a class of the
     * sources. Inherited functions are not included, nor are the static methods of a Java class
     * or the functions of a companion object, which is a class of its own.
     */
    fun functions(className: String): List<FunctionDecl>

    /** The properties a class declares itself, constructor properties included, in a stable order. */
    fun properties(className: String): List<PropertyDecl>

    /**
     * What a serialiser sees of the class with this fully qualified name, from the sources or
     * from a library; `null` if there is no such class, or it is not a class whose objects hold
     * values (an annotation class).
     */
    fun shape(className: String): ClassShape? = null

    /**
     * The fully qualified name that an import of the source file [file] (relative to the root of
     * the analysed repository) gives the simple name [name]; `null` if no import names it.
     */
    fun imported(file: String, name: String): String? = null

    /**
     * The declaration of a function of the sources. [id] must have come from this backend, as the
     * callee of a call or with a function of a class; `null` for any other id, and for functions
     * of libraries.
     */
    fun function(id: FunctionId): FunctionDecl?

    /**
     * The body of a function of the sources, lowered to a [Block]; an expression body becomes a
     * block that returns the expression. `null` if the function is not in the sources, has no
     * body, or [id] did not come from this backend.
     */
    fun body(id: FunctionId): Block?

    /**
     * The initialiser or delegate expression of a property of the sources, if it has one. [id]
     * must have come from this backend, with a property of a class or with a read or write.
     */
    fun initializer(id: PropertyId): Code?

    /**
     * The default value of the parameter called [parameter] of a function of the sources, if it
     * declares one. [id] must have come from this backend, as for [body].
     */
    fun defaultValue(id: FunctionId, parameter: String): Code?

    /**
     * The body of the getter of a property of the sources, lowered like the body of a function;
     * `null` if the property is not in the sources or is read without running code written for
     * it. [id] must have come from this backend, as for [initializer].
     */
    fun getter(id: PropertyId): Block?

    /**
     * The constructors of a class of the sources, the primary one first and the others in source
     * order. A class that writes none out has the one the compiler gives it. Empty for a class
     * that is not in the Kotlin sources, and for an interface.
     */
    fun constructors(className: String): List<ConstructorDecl>

    /**
     * What a constructor of the sources runs. [id] must have come from this backend, with
     * [constructors] or as the callee of [Construction.delegate]; `null` for any other id.
     */
    fun construction(id: FunctionId): Construction?

    /**
     * Every class and interface declared in the Java files of the sources, nested ones included,
     * ordered by name. Their declarations can be asked for like those of a library, and say
     * where they are; their code is not read, but for [javaCalls].
     */
    fun javaClasses(): List<ClassDecl>

    /**
     * The places in the Java files of the sources where the method [method] of the class [owner]
     * is called, or, for a [method] of `<init>`, where an object of [owner] is made with `new`,
     * in the order of the files and of their place in them. This is all that is read of the code
     * of Java files: what a call hands over is told only where it is a class literal. A call of
     * that name that does not resolve is taken for one of [owner].
     */
    fun javaCalls(owner: String, method: String): List<JavaCall>

    /**
     * The fully qualified names under which the sources declare more than one class, in Kotlin
     * or in Java, each with the places of its declarations. The first place is that of the
     * declaration that stands for the name: the one [findClass] gives and whose members are
     * read. A build allows this where the declarations lie in modules that do not see each
     * other, and a model that puts the sources of several modules into one has it wherever two
     * of them use one name.
     *
     * Code that may mean another declaration than the first is not read with the first: a call
     * of a member of such a class, or of its constructor, is then a call that did not resolve
     * (see [Unresolved.declaredTwice]), and a read of one of its properties names no property.
     */
    fun duplicateClasses(): Map<String, List<SourceLocation>>

    /** Whether any Kotlin file of the sources holds [text], anywhere in it. */
    fun mentions(text: String): Boolean

    /**
     * The code of the Kotlin sources whose text holds [text], lowered like [body], in the order
     * of the files and of its place in them: the bodies of named functions, of classes and of
     * files alike, and of functions nested in one another the outermost; and what runs when a
     * class or a file is set up, which no function holds: each `init` block, each body of a
     * constructor, and each initialiser of a property that is no local variable, as a block
     * of its one expression. This finds the little that is worth reading among all of the
     * sources where no call leads to it from anything that is read anyway. What it holds is
     * still told by what its calls resolve to, never by its text.
     */
    fun bodiesMentioning(text: String): List<Block>

    /** Releases everything the backend holds. Closing twice is harmless. */
    override fun close()
}

/** A call written in a Java file of the sources; see [SourceBackend.javaCalls]. */
data class JavaCall(
    val location: SourceLocation,
    /**
     * For each argument, in the order written, the fully qualified name of the class it names
     * where it is a class literal, `Type.class`; `null` for any other expression.
     */
    val classLiterals: List<String?>,
)

/** One module of the sources to analyse. */
data class SourceModule(
    /** Path that identifies the module, for example `:orders:workflows`. */
    val path: String,
    /** Directories that hold the module's sources. */
    val sourceRoots: List<Path>,
    /** Paths of the other modules this one sees. */
    val dependencies: List<String>,
    /** Jars on the module's compile classpath, in classpath order. */
    val jars: List<Path>,
)

/** Everything a [SourceBackend] is opened on. */
data class SourceSet(
    /** The directory that file names in source locations are relative to. */
    val root: Path,
    val modules: List<SourceModule>,
    /** Home of the JDK whose classes the sources are resolved against. */
    val jdkHome: Path,
)

/** Opens backends. Implementations have a public constructor without parameters. */
interface SourceBackendFactory {
    /**
     * @param log receives progress messages and warnings
     * @throws SourceAnalysisException if the sources cannot be opened
     */
    fun open(sources: SourceSet, log: (String) -> Unit): SourceBackend
}

/** Thrown when sources cannot be analysed at all, as opposed to containing things that do not resolve. */
class SourceAnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
