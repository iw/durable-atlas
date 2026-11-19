package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Rasteriser
import io.github.iw.durableatlas.overlay.testing.RegionEndHistories
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.svg.SvgOptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws overlays as pictures under `build/overlay-review`, for a person to look at: those of the
 * demo project, of the sample repository, and of a workflow written to try a jump out of an
 * inner loop. Nothing about the look is asserted here.
 *
 * The pictures are made from files that embed the typeface, so they show the diagrams as a
 * browser with that typeface draws them.
 */
class OverlayReviewTest {
    @TempDir
    lateinit var dir: Path

    private val out = Path.of("build", "overlay-review")

    private val formats = listOf(DiagramFormats.svg(SvgOptions(embedFont = true)))

    private fun picture(name: String, file: Path) {
        val target = out.resolve("$name.png")
        Rasteriser.write(Files.readString(file), target)
        assertTrue(target.fileSize() > 10_000, "$target is empty")
    }

    @Test
    fun `the coverage of the demo workflow and two of its executions`() {
        OverlayDirectory.write(Fixtures.demoIr, Fixtures.demoHistories, dir, OverlayOptions(formats))

        picture("demo-OrderWorkflow.coverage", dir.resolve("OrderWorkflow.coverage.svg"))
        picture("demo-OrderWorkflow.cancelled", dir.resolve("executions/OrderWorkflow.cancelled.svg"))
        picture("demo-OrderWorkflow.paid", dir.resolve("executions/OrderWorkflow.paid.svg"))
    }

    @Test
    fun `the coverage of a workflow that continues an outer loop and the execution that does so`() {
        val ir = Files.createDirectories(dir.resolve("ir"))
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.copy(Fixtures.regionEndIr.resolve("OuterContinue.ir.json"), ir.resolve("OuterContinue.ir.json"))
        Files.writeString(histories.resolve("skipped.json"), HistoryJson.encode(RegionEndHistories.outerContinueSkipping()))
        Files.writeString(histories.resolve("plain.json"), HistoryJson.encode(RegionEndHistories.outerContinuePlain()))
        OverlayDirectory.write(ir, histories, dir.resolve("out"), OverlayOptions(formats))

        picture("ends-OuterContinue.coverage", dir.resolve("out/OuterContinue.coverage.svg"))
        picture("ends-OuterContinue.skipped", dir.resolve("out/executions/skipped.svg"))
        picture("ends-OuterContinue.plain", dir.resolve("out/executions/plain.svg"))
    }

    @Test
    fun `the coverage of the composite workflow of the sample repository and one of its executions`() {
        OverlayDirectory.write(Fixtures.sampleIr, Fixtures.sampleHistories, dir, OverlayOptions(formats))

        picture("sample-OrderWorkflow.coverage", dir.resolve("OrderWorkflow.coverage.svg"))
        picture("sample-OrderWorkflow.payment-declined", dir.resolve("executions/OrderWorkflow.payment-declined.svg"))
        picture("sample-ApprovalWorkflow.coverage", dir.resolve("ApprovalWorkflow.coverage.svg"))
    }
}
