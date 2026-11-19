package io.github.iw.durableatlas.render.svg

import kotlin.math.ceil
import kotlin.math.roundToLong

/** Rounds to one decimal place, the precision of everything that is written to an SVG file. */
internal fun Double.rounded(): Double = (this * 10).roundToLong() / 10.0

/** A coordinate as it is written: one decimal place at most, and none for whole numbers. */
internal fun Double.text(): String {
    val tenths = (this * 10).roundToLong()
    val whole = tenths / 10
    val rest = (if (tenths < 0) -tenths else tenths) % 10
    return when {
        rest == 0L -> whole.toString()
        tenths < 0 && whole == 0L -> "-0.$rest"
        else -> "$whole.$rest"
    }
}

internal fun Double.roundedUpTo(step: Double): Double = ceil(this / step - 1e-9) * step

internal fun Box.rounded(): Box = Box(x.rounded(), y.rounded(), width.rounded(), height.rounded())

internal fun Point.rounded(): Point = Point(x.rounded(), y.rounded())
