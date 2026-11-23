package io.github.iw.durableatlas.analyser.runtime

import io.github.iw.durableatlas.analyser.code.FunctionId
import io.github.iw.durableatlas.analyser.code.PropertyId
import io.github.iw.durableatlas.analyser.code.SourceAnalysisException
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.SourceBackendFactory
import io.github.iw.durableatlas.analyser.code.SourceSet
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Enumeration
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Loads the backend that reads Kotlin sources, and with it the Kotlin Analysis API.
 *
 * The backend runs in a class loader of its own. The Analysis API needs a core of the IntelliJ
 * platform and that brings its own versions of widely used libraries (a fork of
 * kotlinx.coroutines, Guava, OpenTelemetry, a logging bridge, kotlinx.serialization); none of
 * them may meet the libraries the rest of the tool uses. The two sides share only the JDK, the
 * Kotlin standard library and the tool's own types, which is all the backend's interface is made of.
 *
 * The files to load are named by the system property `durable-atlas.analysisClasspath` (jars and
 * class directories, separated like a class path), and are otherwise the jars in the `analysis`
 * directory next to the jar this class was loaded from, which is where the distribution keeps them.
 */
internal object AnalysisRuntime {
    const val PROPERTY: String = "durable-atlas.analysisClasspath"
    const val DISTRIBUTION_DIRECTORY: String = "analysis"

    private const val FACTORY_CLASS = "io.github.iw.durableatlas.analyser.backend.KotlinBackendFactory"

    private class Loaded(val loader: ClassLoader, val factory: SourceBackendFactory)

    // The IntelliJ core keeps process-wide state, so it is loaded once and never unloaded.
    private var loaded: Loaded? = null

    /** @throws SourceAnalysisException if the backend or the sources cannot be loaded */
    fun open(sources: SourceSet, log: (String) -> Unit): SourceBackend {
        val runtime = load()
        return withContextClassLoader(runtime.loader) {
            ContextSwitchingBackend(runtime.factory.open(sources, log), runtime.loader)
        }
    }

    @Synchronized
    private fun load(): Loaded {
        loaded?.let { return it }
        val classpath = locate()
        val urls = classpath.map { it.toUri().toURL() }.toTypedArray()
        val loader = URLClassLoader("durable-atlas-analysis", urls, SharedTypes(AnalysisRuntime::class.java.classLoader))
        val factory = try {
            Class.forName(FACTORY_CLASS, true, loader).getDeclaredConstructor().newInstance() as SourceBackendFactory
        } catch (e: ReflectiveOperationException) {
            throw SourceAnalysisException("The analysis backend could not be loaded from $classpath: $e", e)
        } catch (e: LinkageError) {
            throw SourceAnalysisException("The analysis backend could not be loaded from $classpath: $e", e)
        }
        return Loaded(loader, factory).also { loaded = it }
    }

    private fun locate(): List<Path> {
        val configured = System.getProperty(PROPERTY)
        val entries = if (!configured.isNullOrBlank()) {
            configured.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }.filter { it.exists() }
        } else {
            distributionDirectory()?.listDirectoryEntries()?.filter { it.extension == "jar" }?.sorted().orEmpty()
        }
        if (entries.isEmpty()) {
            throw SourceAnalysisException(
                "The analysis backend was not found. Run the tool from its distribution, or set -D$PROPERTY",
            )
        }
        return entries.map { it.toAbsolutePath().normalize() }.distinct()
    }

    private fun distributionDirectory(): Path? {
        val location = AnalysisRuntime::class.java.protectionDomain?.codeSource?.location ?: return null
        val jar = runCatching { Path.of(location.toURI()) }.getOrNull() ?: return null
        return jar.parent?.resolve(DISTRIBUTION_DIRECTORY)?.takeIf { it.isDirectory() }
    }
}

/**
 * Parent of the backend's class loader. Of everything the tool's own class loader can see, it
 * lets through only what both sides must agree on: the Kotlin standard library and the tool's own
 * types. JDK classes come from the platform class loader.
 */
private class SharedTypes(private val tool: ClassLoader) : ClassLoader(getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (isShared(name)) tool.loadClass(name) else super.loadClass(name, resolve)

    // The backend itself must be defined by its own class loader even if the tool's could find it.
    private fun isShared(name: String): Boolean =
        SHARED_PACKAGES.any { name.startsWith(it) } && !name.startsWith(BACKEND_PACKAGE)

    // The compiler reads the declarations of built-in types from the standard library's resources.
    override fun getResource(name: String): URL? =
        if (name.startsWith(STANDARD_LIBRARY_RESOURCES)) tool.getResource(name) else super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        if (name.startsWith(STANDARD_LIBRARY_RESOURCES)) tool.getResources(name) else super.getResources(name)

    private companion object {
        val SHARED_PACKAGES = listOf("kotlin.", "io.github.iw.durableatlas.")
        const val BACKEND_PACKAGE = "io.github.iw.durableatlas.analyser.backend."
        const val STANDARD_LIBRARY_RESOURCES = "kotlin/"

        init {
            registerAsParallelCapable()
        }
    }
}

private inline fun <T> withContextClassLoader(loader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = loader
    try {
        return block()
    } finally {
        thread.contextClassLoader = previous
    }
}

/** Runs every call with the backend's class loader as the thread's context class loader. */
private class ContextSwitchingBackend(
    private val delegate: SourceBackend,
    private val loader: ClassLoader,
) : SourceBackend {
    override fun sourceClasses() = withContextClassLoader(loader) { delegate.sourceClasses() }

    override fun findClass(name: String) = withContextClassLoader(loader) { delegate.findClass(name) }

    override fun functions(className: String) = withContextClassLoader(loader) { delegate.functions(className) }

    override fun properties(className: String) = withContextClassLoader(loader) { delegate.properties(className) }

    override fun shape(className: String) = withContextClassLoader(loader) { delegate.shape(className) }

    override fun imported(file: String, name: String) = withContextClassLoader(loader) { delegate.imported(file, name) }

    override fun function(id: FunctionId) =
        withContextClassLoader(loader) { delegate.function(id) }

    override fun body(id: FunctionId) =
        withContextClassLoader(loader) { delegate.body(id) }

    override fun initializer(id: PropertyId) =
        withContextClassLoader(loader) { delegate.initializer(id) }

    override fun defaultValue(id: FunctionId, parameter: String) =
        withContextClassLoader(loader) { delegate.defaultValue(id, parameter) }

    override fun getter(id: PropertyId) =
        withContextClassLoader(loader) { delegate.getter(id) }

    override fun constructors(className: String) =
        withContextClassLoader(loader) { delegate.constructors(className) }

    override fun construction(id: FunctionId) =
        withContextClassLoader(loader) { delegate.construction(id) }

    override fun javaClasses() = withContextClassLoader(loader) { delegate.javaClasses() }

    override fun javaCalls(owner: String, method: String) = withContextClassLoader(loader) { delegate.javaCalls(owner, method) }

    override fun duplicateClasses() = withContextClassLoader(loader) { delegate.duplicateClasses() }

    override fun mentions(text: String) = withContextClassLoader(loader) { delegate.mentions(text) }

    override fun bodiesMentioning(text: String) = withContextClassLoader(loader) { delegate.bodiesMentioning(text) }

    override fun close() = withContextClassLoader(loader) { delegate.close() }
}
