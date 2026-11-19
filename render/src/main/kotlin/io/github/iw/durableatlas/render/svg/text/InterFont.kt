package io.github.iw.durableatlas.render.svg.text

import java.awt.Font

/** The weights text is set in. Each one is a font file of its own. */
internal enum class FontWeight(val css: Int, private val file: String) {
    SEMI_BOLD(600, "Inter-SemiBold.otf"),
    BOLD(700, "Inter-Bold.otf"),
    ;

    val resource: String
        get() = InterFont.DIRECTORY + file
}

/**
 * The Inter typeface, which the diagrams are designed for. Its font files and their licence (SIL
 * Open Font License 1.1) come from the font artifact on the class path and are used unchanged.
 */
internal object InterFont {
    const val FAMILY = "Inter"
    const val DIRECTORY = "/com/formdev/flatlaf/fonts/inter/"
    const val LICENCE = DIRECTORY + "LICENSE.txt"

    private val fonts = HashMap<FontWeight, Font>()

    init {
        // Fonts are only measured, never shown, so no display is needed and none must be opened.
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    }

    fun bytes(weight: FontWeight): ByteArray =
        checkNotNull(InterFont::class.java.getResourceAsStream(weight.resource)) { "Missing font ${weight.resource}" }
            .use { it.readBytes() }

    @Synchronized
    fun font(weight: FontWeight): Font = fonts.getOrPut(weight) {
        bytes(weight).inputStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }
}
