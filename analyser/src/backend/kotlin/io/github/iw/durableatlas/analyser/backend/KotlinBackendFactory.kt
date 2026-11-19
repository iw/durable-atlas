package io.github.iw.durableatlas.analyser.backend

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.code.SourceBackendFactory
import io.github.iw.durableatlas.analyser.code.SourceSet

/**
 * Opens sources with the standalone Kotlin Analysis API.
 *
 * This package is the only code that uses the Analysis API, the Kotlin PSI or the IntelliJ
 * platform. It is loaded by a class loader of its own (the factory is looked up by name), and
 * everything it hands out is made of the types in `io.github.iw.durableatlas.analyser.code`.
 */
class KotlinBackendFactory : SourceBackendFactory {
    override fun open(sources: SourceSet, log: (String) -> Unit): SourceBackend {
        val session = AnalysisSession.open(sources, log)
        return KotlinSourceBackend(session, log)
    }
}
