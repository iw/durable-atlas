package io.github.iw.durableatlas.analyser.backend

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import io.github.iw.durableatlas.analyser.code.SourceAnalysisException
import io.github.iw.durableatlas.analyser.code.SourceModule
import io.github.iw.durableatlas.analyser.code.SourceSet
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

/** Finds the line of a place in a text. */
internal class Lines(text: () -> String) {
    private val starts: IntArray by lazy {
        val read = text()
        val found = ArrayList<Int>()
        found += 0
        for (index in read.indices) if (read[index] == '\n') found += index + 1
        found.toIntArray()
    }

    /** The 1-based line of the character at [offset]. */
    fun of(offset: Int): Int {
        val found = starts.binarySearch(offset)
        return if (found >= 0) found + 1 else -found - 1
    }
}

/** A Kotlin file of the analysed sources. */
internal class SourceFile(
    val psi: KtFile,
    /** Path of the module the file belongs to. */
    val module: String,
    /** Location relative to the root of the analysed repository, with `/` separators. */
    val path: String,
) {
    private val lines = Lines { psi.text }

    /** The 1-based line of the character at [offset]. */
    fun line(offset: Int): Int = lines.of(offset)
}

/** A Java file of the analysed sources. What it declares is known; its code is not read. */
internal class JavaSourceFile(
    val psi: PsiJavaFile,
    /** Path of the module the file belongs to. */
    val module: String,
    /** Location relative to the root of the analysed repository, with `/` separators. */
    val path: String,
) {
    private val lines = Lines { psi.text }

    /** The 1-based line of the character at [offset]. */
    fun line(offset: Int): Int = lines.of(offset)
}

/**
 * A standalone Analysis API session over a [SourceSet]: one source module per [SourceModule], one
 * library module per jar (shared by the source modules that list it) and one module for the JDK.
 */
internal class AnalysisSession private constructor(
    private val disposable: Disposable,
    /** Source modules by path. */
    val modules: Map<String, KaSourceModule>,
    /** Every Kotlin file of the sources, ordered by path. */
    val files: List<SourceFile>,
    /** Every Java file of the sources, ordered by path. */
    val javaFiles: List<JavaSourceFile>,
) : AutoCloseable {
    private val byPsi: Map<KtFile, SourceFile> = files.associateByTo(IdentityHashMap()) { it.psi }
    private val javaByPsi: Map<PsiFile, JavaSourceFile> = javaFiles.associateByTo(IdentityHashMap()) { it.psi }
    private var closed = false

    /** The source file [psi] stands for, or `null` if it is not a file of the analysed sources. */
    fun sourceFile(psi: KtFile): SourceFile? = byPsi[psi]

    /** The Java file [psi] stands for, or `null` if it is not a Java file of the analysed sources. */
    fun javaFile(psi: PsiFile?): JavaSourceFile? = psi?.let(javaByPsi::get)

    override fun close() {
        if (closed) return
        closed = true
        Disposer.dispose(disposable)
    }

    companion object {
        fun open(sources: SourceSet, log: (String) -> Unit): AnalysisSession {
            val disposable = Disposer.newDisposable("durable-atlas analysis session")
            try {
                return build(sources, disposable, log)
            } catch (e: SourceAnalysisException) {
                Disposer.dispose(disposable)
                throw e
            } catch (e: Exception) {
                Disposer.dispose(disposable)
                throw SourceAnalysisException("The sources could not be opened for analysis: $e", e)
            }
        }

        private fun build(sources: SourceSet, disposable: Disposable, log: (String) -> Unit): AnalysisSession {
            val jvm = JvmPlatforms.defaultJvmPlatform
            val ordered = inDependencyOrder(sources.modules, log)
            val sourceModules = LinkedHashMap<String, KaSourceModule>()
            val libraries = LinkedHashMap<Path, KaLibraryModule>()
            val missingJars = LinkedHashSet<Path>()

            val session = buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    platform = jvm
                    val jdk = addModule(
                        buildKtSdkModule {
                            addBinaryRootsFromJdkHome(sources.jdkHome, isJre = isJre(sources.jdkHome))
                            platform = jvm
                            libraryName = "JDK ${sources.jdkHome}"
                        },
                    )

                    fun library(jar: Path): KaLibraryModule? {
                        if (!Files.exists(jar)) {
                            missingJars.add(jar)
                            return null
                        }
                        return libraries.getOrPut(jar) {
                            addModule(
                                buildKtLibraryModule {
                                    addBinaryRoot(jar)
                                    platform = jvm
                                    libraryName = jar.toString()
                                },
                            )
                        }
                    }

                    for (module in ordered) {
                        sourceModules[module.path] = addModule(
                            buildKtSourceModule {
                                moduleName = module.path
                                platform = jvm
                                addSourceRoots(module.sourceRoots.filter { Files.isDirectory(it) })
                                addRegularDependency(jdk)
                                for (dependency in module.dependencies) {
                                    // A dependency that is not built yet is the back edge of a cycle.
                                    sourceModules[dependency]?.let(::addRegularDependency)
                                }
                                for (jar in module.jars) library(jar)?.let(::addRegularDependency)
                            },
                        )
                    }
                }
            }

            if (missingJars.isNotEmpty()) {
                log(
                    "warning: ${missingJars.size} jar(s) named by the model no longer exist, " +
                        "for example ${missingJars.first()}; what they declare will not resolve",
                )
            }

            val root = sources.root.toString().replace('\\', '/').trimEnd('/') + "/"
            val files = sourceModules.flatMap { (path, module) ->
                session.modulesWithFiles[module].orEmpty().filterIsInstance<KtFile>().map { file ->
                    SourceFile(file, path, file.virtualFile.path.removePrefix(root))
                }
            }.sortedBy { it.path }
            val javaFiles = sourceModules.flatMap { (path, module) ->
                session.modulesWithFiles[module].orEmpty().filterIsInstance<PsiJavaFile>().map { file ->
                    JavaSourceFile(file, path, file.virtualFile.path.removePrefix(root))
                }
            }.sortedBy { it.path }
            log(
                "analysing ${files.size} Kotlin file(s) in ${sourceModules.size} module(s) " +
                    "against ${libraries.size} jar(s) and the JDK at ${sources.jdkHome}",
            )
            if (files.isEmpty() && javaFiles.isNotEmpty()) {
                log(
                    "warning: the sources hold ${javaFiles.size} Java file(s) and no Kotlin file. Only code written in Kotlin " +
                        "is analysed, so no workflow is drawn; what the Java files declare is listed in the inventory",
                )
            }
            return AnalysisSession(disposable, sourceModules, files, javaFiles)
        }

        /** Before JDK 9 a JRE kept its class library in `lib`, and a JDK kept a JRE in `jre`. */
        private fun isJre(home: Path): Boolean =
            !Files.exists(home.resolve("lib/jrt-fs.jar")) && !Files.isDirectory(home.resolve("jre"))

        /** Modules ordered so that each comes after the modules it depends on. */
        private fun inDependencyOrder(modules: List<SourceModule>, log: (String) -> Unit): List<SourceModule> {
            val byPath = modules.associateBy { it.path }
            val ordered = LinkedHashMap<String, SourceModule>()
            val visiting = HashSet<String>()

            fun visit(module: SourceModule) {
                if (module.path in ordered) return
                if (!visiting.add(module.path)) {
                    log("warning: the dependencies of module ${module.path} form a cycle; one of them is ignored")
                    return
                }
                for (dependency in module.dependencies) {
                    val target = byPath[dependency]
                    if (target == null) {
                        log("warning: module ${module.path} depends on $dependency, which the model does not describe")
                    } else {
                        visit(target)
                    }
                }
                visiting.remove(module.path)
                ordered[module.path] = module
            }
            modules.sortedBy { it.path }.forEach(::visit)
            return ordered.values.toList()
        }
    }
}
