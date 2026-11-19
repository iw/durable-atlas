package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.analyser.AnalysisRequest
import io.github.iw.durableatlas.analyser.Analyser
import io.github.iw.durableatlas.analyser.testing.SampleModels
import java.nio.file.Files
import java.nio.file.Path

/**
 * The IR of the sample project, analysed once per test JVM from its degraded model. Tests only
 * read the directory.
 */
object SampleIr {
    val degraded: Path by lazy {
        val directory = Files.createTempDirectory("durable-atlas-sample-ir-")
        Runtime.getRuntime().addShutdownHook(Thread { directory.toFile().deleteRecursively() })
        Analyser().analyse(AnalysisRequest(SampleModels.degraded.directory, directory)).inventoryFile.parent
    }
}
