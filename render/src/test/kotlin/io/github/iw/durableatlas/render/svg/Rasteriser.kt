package io.github.iw.durableatlas.render.svg

import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import java.awt.image.BufferedImage
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Turns SVG text into a PNG file, for people to look at. */
object Rasteriser {
    /** @param scale pixels per SVG user unit */
    fun write(svg: String, target: Path, scale: Double = 2.0) {
        val width = Regex("<svg[^>]* width=\"([0-9.]+)\"").find(svg)!!.groupValues[1].toDouble()
        val transcoder = object : ImageTranscoder() {
            var image: BufferedImage? = null

            override fun createImage(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

            override fun writeImage(image: BufferedImage, output: TranscoderOutput?) {
                this.image = image
            }
        }
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, (width * scale).toFloat())
        transcoder.transcode(TranscoderInput(StringReader(svg)), null)
        Files.createDirectories(target.parent)
        ImageIO.write(transcoder.image, "png", target.toFile())
    }
}
