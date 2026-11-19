package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceAnalysisException
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.SourceModule
import io.github.iw.durableatlas.analyser.code.SourceSet
import io.github.iw.durableatlas.analyser.graph.ExtensionReadingBackend
import io.github.iw.durableatlas.analyser.runtime.AnalysisRuntime
import io.github.iw.durableatlas.modelextractor.ExtractedModel
import java.nio.file.Files
import java.nio.file.Path

/** Opens [SourceBackend]s on extracted models. */
object SourceBackends {
    /**
     * Opens the sources of [model] for analysis: one module per manifest, seeing the modules and
     * jars the manifest lists and the classes of the JDK at [jdkHome]. In the code that is handed
     * out, the Kotlin extensions of the Temporal SDK are replaced by the calls of the SDK they
     * stand for.
     *
     * @param jdkHome home of the JDK to resolve against; that of the running JVM if `null`
     * @throws SourceAnalysisException if the JDK or the analysis backend cannot be used
     */
    fun open(model: ExtractedModel, jdkHome: Path? = null, log: (String) -> Unit = {}): SourceBackend =
        ExtensionReadingBackend(AnalysisRuntime.open(sourceSet(model, jdkHome), log))

    internal fun sourceSet(model: ExtractedModel, jdkHome: Path?): SourceSet =
        SourceSet(
            root = model.sourceRoot.toAbsolutePath().normalize(),
            modules = model.manifests.map { manifest ->
                SourceModule(
                    path = manifest.path,
                    sourceRoots = model.sourceDirectories(manifest).map { it.toAbsolutePath().normalize() },
                    dependencies = manifest.projectDeps,
                    jars = model.jars(manifest).map { it.toAbsolutePath().normalize() },
                )
            },
            jdkHome = checkedJdkHome(jdkHome ?: Path.of(System.getProperty("java.home"))),
        )

    private fun checkedJdkHome(home: Path): Path {
        val absolute = home.toAbsolutePath().normalize()
        if (JDK_MARKERS.none { Files.exists(absolute.resolve(it)) }) {
            throw SourceAnalysisException("$absolute is not the home directory of a JDK")
        }
        return absolute
    }

    // A modular run-time image (JDK 9 and later), or the class library of an older JDK or JRE.
    private val JDK_MARKERS = listOf("lib/jrt-fs.jar", "jre/lib/rt.jar", "lib/rt.jar")
}
