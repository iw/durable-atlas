package io.github.iw.durableatlas.render.svg.text

import io.github.iw.durableatlas.render.svg.Geometry

/** How each kind of text in a diagram is set. */
internal object TextStyles {
    val KIND = TextStyle(Geometry.KIND_SIZE, FontWeight.BOLD, Geometry.KIND_TRACKING)
    val NAME = TextStyle(Geometry.NAME_SIZE, FontWeight.SEMI_BOLD)
    val LABEL = TextStyle(Geometry.LABEL_SIZE, FontWeight.SEMI_BOLD)

    /** Legend entries are drawn at medium weight; measuring them at the next heavier one errs on the safe side. */
    val LEGEND = TextStyle(Geometry.LEGEND_SIZE, FontWeight.SEMI_BOLD)
    val LEGEND_TITLE = TextStyle(Geometry.KIND_SIZE, FontWeight.BOLD, Geometry.LEGEND_TITLE_TRACKING)
    val HEADING = TextStyle(Geometry.HEADING_SIZE, FontWeight.SEMI_BOLD)

    /** Drawn at medium weight, like the entries of the legend. */
    val HEADING_DETAIL = TextStyle(Geometry.HEADING_DETAIL_SIZE, FontWeight.SEMI_BOLD)
    val NOTICE = TextStyle(Geometry.NOTICE_SIZE, FontWeight.SEMI_BOLD)
    val BADGE = TextStyle(Geometry.BADGE_SIZE, FontWeight.BOLD)
}
