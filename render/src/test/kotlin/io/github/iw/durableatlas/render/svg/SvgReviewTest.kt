package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.Emphasis
import io.github.iw.durableatlas.render.PageOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws diagrams as pictures under `build/svg-review`, for a person to look at. Nothing about
 * the look is asserted here.
 *
 * The pictures are made from files that embed the typeface, so they show the diagrams as a
 * browser with that typeface draws them.
 */
class SvgReviewTest {
    private val out = Path.of("build", "svg-review")
    private val format = SvgFormat(SvgOptions(embedFont = true))

    private fun picture(name: String, svg: String, scale: Double = 2.0) {
        val target = out.resolve("$name.png")
        Rasteriser.write(svg, target, scale)
        assertTrue(target.fileSize() > 10_000, "$target is empty")
    }

    @Test
    fun `the demo workflow`() {
        val document = SvgDocuments.demo()
        Files.createDirectories(out)
        Files.writeString(out.resolve("demo-OrderWorkflow.svg"), SvgFormat().prepare(document, PageOptions()).render())
        picture("demo-OrderWorkflow", format.prepare(document, PageOptions()).render())
    }

    @Test
    fun `the demo workflow with overlays`() {
        val taken = setOf(
            "n00-start", "n01-validate-order", "n02-fraud-check-version", "n03-fraud-check", "n04-parallel", "n05-reserve-stock",
            "n06-authorise-payment", "n07-await-payment", "n12-shipments", "n13-shipment-workflow", "n14-shipment-ready",
            "n15-invoice-number", "n16-award-points", "n17-discount-policy", "n18-returns-window", "n19-continue-as-new-suggested", "n21-end",
        )
        val coverage = object : DiagramDecorator {
            override fun node(node: IrNode): Decoration? = when {
                node.kind == NodeKind.START -> null
                node.id in taken -> Decoration(note = if (node.id.startsWith("n13") || node.id.startsWith("n14")) "×24" else "×12")
                else -> Decoration(Emphasis.DIMMED, "never run")
            }

            override fun edge(edge: IrEdge): Decoration? = if (edge.from in taken && edge.to in taken) null else Decoration(Emphasis.DIMMED)

            override fun entryPoint(entryPoint: EntryPoint): Decoration =
                if (entryPoint.name == "approvePayment") Decoration(note = "×12") else Decoration(Emphasis.DIMMED, "never run")
        }
        val path = object : DiagramDecorator {
            override fun node(node: IrNode) = Decoration(if (node.id in taken) Emphasis.HIGHLIGHTED else Emphasis.DIMMED)

            override fun edge(edge: IrEdge) = Decoration(if (edge.from in taken && edge.to in taken || edge.from == "signal-approvePayment") Emphasis.HIGHLIGHTED else Emphasis.DIMMED)

            override fun entryPoint(entryPoint: EntryPoint) = Decoration(if (entryPoint.name == "approvePayment") Emphasis.HIGHLIGHTED else Emphasis.DIMMED)
        }
        val prepared = format.prepare(SvgDocuments.demo(), PageOptions())
        picture("demo-OrderWorkflow.coverage", prepared.render(DiagramOverlay(coverage, notices = listOf("Coverage of 12 executions."))))
        picture("demo-OrderWorkflow.path", prepared.render(DiagramOverlay(path, title = "OrderWorkflow order-1001")))
    }

    @Test
    fun `every other document`() {
        for ((name, document) in SvgDocuments.all()) {
            if (name == "demo/OrderWorkflow") continue
            Files.createDirectories(out)
            Files.writeString(out.resolve("${name.replace('/', '-')}.svg"), SvgFormat().prepare(document, PageOptions()).render())
            picture(name.replace('/', '-'), format.prepare(document, PageOptions()).render(), scale = 1.5)
        }
    }
}
