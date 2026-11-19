package io.github.iw.durableatlas.render.svg.text

import io.github.iw.durableatlas.render.svg.Geometry
import java.awt.Font
import java.awt.font.FontRenderContext

/**
 * How a piece of text is set.
 *
 * @param size font size in SVG user units
 * @param tracking extra space after every character, in em
 */
internal data class TextStyle(val size: Double, val weight: FontWeight, val tracking: Double = 0.0)

/** Tells how wide text will be drawn, so that cards can be sized before anything is drawn. */
internal fun interface TextMeasurer {
    fun width(text: String, style: TextStyle): Double
}

/**
 * Measures with the metrics of the bundled font, without a display. A viewer that lacks the font
 * draws with another one, so every width is taken a little larger than measured.
 */
internal object FontMetricsMeasurer : TextMeasurer {
    private val context = FontRenderContext(null, true, true)
    private val sized = HashMap<TextStyle, Font>()

    @Synchronized
    override fun width(text: String, style: TextStyle): Double {
        if (text.isEmpty()) return 0.0
        val font = sized.getOrPut(style.copy(tracking = 0.0)) { InterFont.font(style.weight).deriveFont(style.size.toFloat()) }
        var width = 0.0
        var characters = 0
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) width += font.getStringBounds(run.toString(), context).width
            run.setLength(0)
        }
        text.codePoints().forEach { point ->
            characters++
            if (font.canDisplay(point)) {
                run.appendCodePoint(point)
            } else {
                // The typeface has no such glyph and the viewer will pick one from another font.
                // Its width is a guess that does not depend on the fonts of this machine.
                flush()
                width += style.size * if (isWide(point)) WIDE else NARROW
            }
        }
        flush()
        return (width + characters * style.tracking * style.size) * Geometry.TEXT_SLACK
    }

    private fun isWide(point: Int): Boolean = when (Character.UnicodeScript.of(point)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> point >= FIRST_PICTOGRAPH
    }

    private const val WIDE = 1.0
    private const val NARROW = 0.62
    private const val FIRST_PICTOGRAPH = 0x1F000
}
