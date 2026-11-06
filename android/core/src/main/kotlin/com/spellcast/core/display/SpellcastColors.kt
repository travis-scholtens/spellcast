package com.spellcast.core.display

import com.badlogic.gdx.graphics.Color
import kotlin.math.floor

object SpellcastColors {
    private val namedColors: Map<String, Color> = mapOf(
        "black" to rgb(0, 0, 0),
        "white" to rgb(255, 255, 255),
        "gray" to rgb(128, 128, 128),
        "grey" to rgb(128, 128, 128),
        "darkgray" to rgb(169, 169, 169),
        "darkgrey" to rgb(169, 169, 169),
        "lightgray" to rgb(211, 211, 211),
        "lightgrey" to rgb(211, 211, 211),
        "red" to rgb(255, 0, 0),
        "darkred" to rgb(139, 0, 0),
        "crimson" to rgb(220, 20, 60),
        "indianred" to rgb(205, 92, 92),
        "lightcoral" to rgb(240, 128, 128),
        "orangered" to rgb(255, 69, 0),
        "orange" to rgb(255, 165, 0),
        "darkorange" to rgb(255, 140, 0),
        "darkgoldenrod" to rgb(184, 134, 11),
        "yellow" to rgb(255, 255, 0),
        "lemonchiffon" to rgb(255, 250, 205),
        "tan" to rgb(210, 180, 140),
        "peru" to rgb(205, 133, 63),
        "brown" to rgb(165, 42, 42),
        "saddlebrown" to rgb(139, 69, 19),
        "olive" to rgb(128, 128, 0),
        "green" to rgb(0, 128, 0),
        "darkgreen" to rgb(0, 100, 0),
        "lime" to rgb(0, 255, 0),
        "lawngreen" to rgb(124, 252, 0),
        "lightgreen" to rgb(144, 238, 144),
        "springgreen" to rgb(0, 255, 127),
        "darkseagreen" to rgb(143, 188, 143),
        "cadetblue" to rgb(95, 158, 160),
        "cyan" to rgb(0, 255, 255),
        "lightblue" to rgb(173, 216, 230),
        "dodgerblue" to rgb(30, 144, 255),
        "blue" to rgb(0, 0, 255),
        "magenta" to rgb(255, 0, 255),
        "orchid" to rgb(218, 112, 214),
        "pink" to rgb(255, 192, 203),
        "lightpink" to rgb(255, 182, 193),
        "hotpink" to rgb(255, 105, 180)
    )

    private val rgbRegex = Regex("""rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)""")

    private fun rgb(r: Int, g: Int, b: Int): Color =
        Color(r / 255f, g / 255f, b / 255f, 1f)

    fun resolve(spec: String?, defaultColor: Color = Color.WHITE): Color {
        if (spec.isNullOrBlank()) return defaultColor
        val trimmed = spec.trim().lowercase()
        namedColors[trimmed]?.let { return it }

        if (trimmed.startsWith("#")) {
            val hex = trimmed.removePrefix("#")
            if (hex.length == 3) {
                val r = hex[0].digitToIntOrNull(16) ?: return defaultColor
                val g = hex[1].digitToIntOrNull(16) ?: return defaultColor
                val b = hex[2].digitToIntOrNull(16) ?: return defaultColor
                return rgb(r * 17, g * 17, b * 17)
            } else if (hex.length == 6) {
                val r = hex.substring(0, 2).toIntOrNull(16) ?: return defaultColor
                val g = hex.substring(2, 4).toIntOrNull(16) ?: return defaultColor
                val b = hex.substring(4, 6).toIntOrNull(16) ?: return defaultColor
                return rgb(r, g, b)
            }
        }

        val m = rgbRegex.matchEntire(trimmed)
        if (m != null) {
            val (r, g, b) = m.destructured
            return rgb(
                r.toInt().coerceIn(0, 255),
                g.toInt().coerceIn(0, 255),
                b.toInt().coerceIn(0, 255)
            )
        }
        return defaultColor
    }

    /**
     * Exact port of `Game.logoColor(x, y)` in `app.js`.
     */
    fun logoColor(xNorm: Double, yNorm: Double): String {
        val r = 255
        val g = if (yNorm > 0.5) 255 else floor(255.0 * yNorm / 0.5).toInt().coerceIn(0, 255)
        val b = if (yNorm < 0.5) 0 else floor(255.0 * (yNorm - 0.5) / 0.5).toInt().coerceIn(0, 255)
        return "rgb($r,$g,$b)"
    }
}
