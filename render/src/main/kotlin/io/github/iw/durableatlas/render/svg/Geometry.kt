package io.github.iw.durableatlas.render.svg

/**
 * Every measurement of the SVG notation, in SVG user units. Layout and drawing read their sizes
 * from here, so the look can be tuned in one place.
 */
internal object Geometry {
    // Cards
    const val CARD_HEIGHT = 60.0
    const val HANDLER_CARD_HEIGHT = 48.0
    const val CARD_RADIUS = 10.0
    const val CARD_BORDER = 1.5
    const val CARD_MIN_WIDTH = 150.0
    const val CARD_MAX_WIDTH = 260.0
    const val CARD_WIDTH_STEP = 4.0
    const val TILE_WIDTH = 44.0
    const val ICON_SIZE = 22.0

    /** Space beside the text of a card: 10 on each side. */
    const val CARD_TEXT_PADDING = 20.0
    const val CARD_TEXT_INSET = 10.0
    const val OUTLINE_DASH = "4 3"

    // Pills
    const val PILL_HEIGHT = 56.0
    const val PILL_MIN_WIDTH = 120.0

    /** Left edge of the icon, and of the text, inside a pill. */
    const val PILL_ICON_INSET = 14.0
    const val PILL_TEXT_INSET = 46.0

    /** Room after the text, so it stays clear of the rounded end. */
    const val PILL_END_PADDING = 16.0
    const val PILL_KIND_OPACITY = 0.72

    // Text
    const val KIND_SIZE = 10.5
    const val KIND_TRACKING = 0.07
    const val NAME_SIZE = 14.5
    const val NAME_LINE_HEIGHT = 1.15

    /** Space between the kind label and the name line. */
    const val TEXT_GAP = 2.0

    /** Height of a line of text set without a line height, and the distance of its baseline from its top, in em. */
    const val LINE_NORMAL = 1.21
    const val ASCENT = 0.96875

    // Regions
    const val REGION_RADIUS = 14.0
    const val REGION_BORDER = 1.5

    /** Distance of the header's icon chip from the corner of its region. */
    const val REGION_HEADER_X = 12.0
    const val REGION_HEADER_Y = 9.0
    const val CHIP_SIZE = 20.0
    const val CHIP_RADIUS = 5.0
    const val CHIP_ICON = 14.0
    const val CHIP_GAP = 7.0

    /**
     * What the icon and the title of the region or band that fills [box] take up, in its top left corner.
     *
     * @param width the width of the header, with the room on both sides of it
     * @param lines the number of lines the title takes
     */
    fun header(box: Box, width: Double, lines: Int = 1): Box =
        Box(box.x + REGION_HEADER_X, box.y + REGION_HEADER_Y, width - 2 * REGION_HEADER_X, CHIP_SIZE + titleRoom(lines))

    /** Where the content of a region starts below its top edge: the header row and a little air. */
    const val REGION_TOP = 40.0
    const val REGION_SIDE = 14.0
    const val REGION_BOTTOM = 25.0

    /** A title longer than this goes on with a second line, and a box is never wider for its title alone. */
    const val REGION_TITLE_MAX = 300.0
    const val REGION_TITLE_LINES = 2
    const val REGION_TITLE_LINE = 14.0

    /** How much higher the header of a region is for a title of [lines] lines than for one. */
    fun titleRoom(lines: Int): Double = (lines - 1).coerceAtLeast(0) * REGION_TITLE_LINE

    // Handlers band: a box like a region, with the handlers side by side below its header.
    const val BAND_TOP = REGION_TOP
    const val BAND_SIDE = REGION_SIDE
    const val BAND_BOTTOM = REGION_SIDE
    const val BAND_CARD_GAP = 10.0

    /** Room beside the steps of a handler for the edge from its card to what it wakes. */
    const val BAND_CHANNEL = 12.0

    /** How far right of the steps of a handler the edges run that wake one of those steps. */
    const val BAND_WAKE_CHANNEL = 20.0

    /** Space between the band and the first row of the main flow, where `wakes` edges fan out. */
    const val BAND_GAP = 44.0

    // Edges
    const val EDGE_WIDTH = 1.7
    const val EDGE_HIGHLIGHT_WIDTH = 2.8
    const val TIMEOUT_DASH = "6 4"
    const val WAKES_DASH = "1.5 4"
    const val CANCELS_DASH = "7 3 1.5 3"

    /** How far before a card an edge stops, so the tip of its arrowhead touches the border. */
    const val EDGE_END_GAP = 2.0
    const val LABEL_SIZE = 12.0
    const val LABEL_HEIGHT = 16.0
    const val LABEL_PADDING = 4.0

    /** A label longer than this goes on with another line, up to [LABEL_LINES] of them, each [LABEL_LINE] below the last. */
    const val LABEL_MAX_WIDTH = 200.0
    const val LABEL_LINES = 3
    const val LABEL_LINE = 14.0

    /** The height of a label of [lines] lines. */
    fun labelHeight(lines: Int): Double = LABEL_HEIGHT + (lines - 1).coerceAtLeast(0) * LABEL_LINE

    /** Space between an edge and a label set beside it. */
    const val LABEL_OFFSET = 6.0

    // Spacing
    const val ROW_SPACING = 36.0
    const val STACK_SPACING = 30.0
    const val BRANCH_SPACING = 26.0
    const val ROW_GAP = 50.0

    /** How far below a row its connector to the next row runs. */
    const val ROW_CONNECTOR_DROP = 22.0

    /** Distance from the rows of an edge that runs around them. */
    const val DETOUR_MARGIN = 16.0
    const val CORNER_RADIUS = 8.0

    // Legend
    const val LEGEND_GAP = 18.0
    const val LEGEND_RULE = 1.5
    const val LEGEND_PADDING = 9.0
    const val LEGEND_TITLE_WIDTH = 70.0
    const val LEGEND_TITLE_GAP = 18.0
    const val LEGEND_TITLE_TRACKING = 0.1
    const val LEGEND_SIZE = 12.5
    const val LEGEND_TILE = 20.0
    const val LEGEND_TILE_GAP = 7.0
    const val LEGEND_ROW = 20.0
    const val LEGEND_ROW_GAP = 4.0
    const val LEGEND_COLUMN_GAP = 10.0
    const val LEGEND_COLUMNS = 8
    const val LEGEND_COLUMN_MAX = 200.0
    const val LEGEND_SAMPLE = 30.0

    // Canvas
    const val MARGIN = 16.0
    const val NOTICE_SIZE = 12.5
    const val NOTICE_LINE = 20.0

    // Heading: the line above a diagram that has to say which of several workflows of one type it shows
    const val HEADING_LINE = 20.0
    const val HEADING_SIZE = NAME_SIZE
    const val HEADING_DETAIL_SIZE = 12.5

    /** Space between the workflow type and the class that implements it. */
    const val HEADING_GAP = 8.0

    // Overlay
    const val DIMMED_OPACITY = 0.35
    const val BADGE_HEIGHT = 18.0
    const val BADGE_SIZE = 10.5
    const val BADGE_PADDING = 6.0

    /** The width of a badge that counts up to three digits, such as `×999`: the room the layout keeps free for one. */
    const val BADGE_ROOM = 42.0

    /** Where the badge of what fills [box] goes: on its top right corner, sticking out a little. */
    fun badge(box: Box, width: Double): Box = Box(box.right - width + BADGE_PADDING, box.y - BADGE_HEIGHT / 2, width, BADGE_HEIGHT)

    /** Measured text is taken to be this much wider, for viewers that fall back to another typeface. */
    const val TEXT_SLACK = 1.06
}

/** Colours that do not belong to one construct. */
internal object Palette {
    const val INK = "#101820"
    const val PAPER = "#F6F4EE"
    const val WHITE = "#fff"
    const val FLOW = "#344054"

    /**
     * The way a failure takes: the red of the pill a workflow fails with. The line is solid like
     * that of the flow, because the failures of several steps usually run together into one
     * line, where the dashes of each would fall between those of the others.
     */
    const val EXCEPTION = "#B42318"
    const val TIMEOUT = "#B45309"
    const val WAKES = "#15803D"

    /** What a cancelled scope reaches: the colour of the card that cancels it. */
    const val CANCELS = "#9D174D"
    const val MUTED = "#98A2B3"
    const val RULE = "#D9D4C7"
    const val LEGEND_TITLE = "#5B6472"
    const val LEGEND_TEXT = "#1F2933"
    const val WARNING = "#C2410C"
    const val NOTE = "#475569"
    const val SHADOW = "#101820"
    const val SHADOW_OPACITY = 0.10

    /** Fill and border of each kind of region. */
    val tints: Map<String, Tint> = mapOf(
        StyleKinds.PARALLEL to Tint("#EAF1FE", "#9DB8F2"),
        StyleKinds.LOOP to Tint("#EEF0F4", "#B6BECB"),
        StyleKinds.COMPENSATION to Tint("#FDECEF", "#F2A7B5"),
        StyleKinds.HANDLERS to Tint("#EAF5EC", "#A9D3B2"),
    )

    class Tint(val fill: String, val border: String)
}
