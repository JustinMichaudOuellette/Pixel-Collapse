package net.akachaki.processing.engine.color

import kotlin.math.roundToInt

/**
 * Utility for converting an ARGB packed Int color into a human-readable
 * description like "red", "light grey", or "dark green 90%".
 */
object ColorNamer {

    private data class NamedColor(val display: String, val r: Int, val g: Int, val b: Int)

    // All 148 standard CSS/X11 color names (147 classic extended keywords + rebeccapurple)
    private val COLORS: List<NamedColor> = listOf(
        NamedColor("alice blue", 0xF0, 0xF8, 0xFF),
        NamedColor("antique white", 0xFA, 0xEB, 0xD7),
        NamedColor("aqua", 0x00, 0xFF, 0xFF),
        NamedColor("aquamarine", 0x7F, 0xFF, 0xD4),
        NamedColor("azure", 0xF0, 0xFF, 0xFF),
        NamedColor("beige", 0xF5, 0xF5, 0xDC),
        NamedColor("bisque", 0xFF, 0xE4, 0xC4),
        NamedColor("black", 0x00, 0x00, 0x00),
        NamedColor("blanched almond", 0xFF, 0xEB, 0xCD),
        NamedColor("blue", 0x00, 0x00, 0xFF),
        NamedColor("blue violet", 0x8A, 0x2B, 0xE2),
        NamedColor("brown", 0xA5, 0x2A, 0x2A),
        NamedColor("burlywood", 0xDE, 0xB8, 0x87),
        NamedColor("cadet blue", 0x5F, 0x9E, 0xA0),
        NamedColor("chartreuse", 0x7F, 0xFF, 0x00),
        NamedColor("chocolate", 0xD2, 0x69, 0x1E),
        NamedColor("coral", 0xFF, 0x7F, 0x50),
        NamedColor("cornflower blue", 0x64, 0x95, 0xED),
        NamedColor("cornsilk", 0xFF, 0xF8, 0xDC),
        NamedColor("crimson", 0xDC, 0x14, 0x3C),
        NamedColor("cyan", 0x00, 0xFF, 0xFF),
        NamedColor("dark blue", 0x00, 0x00, 0x8B),
        NamedColor("dark cyan", 0x00, 0x8B, 0x8B),
        NamedColor("dark goldenrod", 0xB8, 0x86, 0x0B),
        NamedColor("dark gray", 0xA9, 0xA9, 0xA9),
        NamedColor("dark green", 0x00, 0x64, 0x00),
        NamedColor("dark grey", 0xA9, 0xA9, 0xA9),
        NamedColor("dark khaki", 0xBD, 0xB7, 0x6B),
        NamedColor("dark magenta", 0x8B, 0x00, 0x8B),
        NamedColor("dark olive green", 0x55, 0x6B, 0x2F),
        NamedColor("dark orange", 0xFF, 0x8C, 0x00),
        NamedColor("dark orchid", 0x99, 0x32, 0xCC),
        NamedColor("dark red", 0x8B, 0x00, 0x00),
        NamedColor("dark salmon", 0xE9, 0x96, 0x7A),
        NamedColor("dark sea green", 0x8F, 0xBC, 0x8F),
        NamedColor("dark slate blue", 0x48, 0x3D, 0x8B),
        NamedColor("dark slate gray", 0x2F, 0x4F, 0x4F),
        NamedColor("dark slate grey", 0x2F, 0x4F, 0x4F),
        NamedColor("dark turquoise", 0x00, 0xCE, 0xD1),
        NamedColor("dark violet", 0x94, 0x00, 0xD3),
        NamedColor("deep pink", 0xFF, 0x14, 0x93),
        NamedColor("deep sky blue", 0x00, 0xBF, 0xFF),
        NamedColor("dim gray", 0x69, 0x69, 0x69),
        NamedColor("dim grey", 0x69, 0x69, 0x69),
        NamedColor("dodger blue", 0x1E, 0x90, 0xFF),
        NamedColor("firebrick", 0xB2, 0x22, 0x22),
        NamedColor("floral white", 0xFF, 0xFA, 0xF0),
        NamedColor("forest green", 0x22, 0x8B, 0x22),
        NamedColor("fuchsia", 0xFF, 0x00, 0xFF),
        NamedColor("gainsboro", 0xDC, 0xDC, 0xDC),
        NamedColor("ghost white", 0xF8, 0xF8, 0xFF),
        NamedColor("gold", 0xFF, 0xD7, 0x00),
        NamedColor("goldenrod", 0xDA, 0xA5, 0x20),
        NamedColor("gray", 0x80, 0x80, 0x80),
        NamedColor("grey", 0x80, 0x80, 0x80),
        NamedColor("green", 0x00, 0x80, 0x00),
        NamedColor("green yellow", 0xAD, 0xFF, 0x2F),
        NamedColor("honeydew", 0xF0, 0xFF, 0xF0),
        NamedColor("hot pink", 0xFF, 0x69, 0xB4),
        NamedColor("indian red", 0xCD, 0x5C, 0x5C),
        NamedColor("indigo", 0x4B, 0x00, 0x82),
        NamedColor("ivory", 0xFF, 0xFF, 0xF0),
        NamedColor("khaki", 0xF0, 0xE6, 0x8C),
        NamedColor("lavender", 0xE6, 0xE6, 0xFA),
        NamedColor("lavender blush", 0xFF, 0xF0, 0xF5),
        NamedColor("lawn green", 0x7C, 0xFC, 0x00),
        NamedColor("lemon chiffon", 0xFF, 0xFA, 0xCD),
        NamedColor("light blue", 0xAD, 0xD8, 0xE6),
        NamedColor("light coral", 0xF0, 0x80, 0x80),
        NamedColor("light cyan", 0xE0, 0xFF, 0xFF),
        NamedColor("light goldenrod yellow", 0xFA, 0xFA, 0xD2),
        NamedColor("light gray", 0xD3, 0xD3, 0xD3),
        NamedColor("light green", 0x90, 0xEE, 0x90),
        NamedColor("light grey", 0xD3, 0xD3, 0xD3),
        NamedColor("light pink", 0xFF, 0xB6, 0xC1),
        NamedColor("light salmon", 0xFF, 0xA0, 0x7A),
        NamedColor("light sea green", 0x20, 0xB2, 0xAA),
        NamedColor("light sky blue", 0x87, 0xCE, 0xFA),
        NamedColor("light slate gray", 0x77, 0x88, 0x99),
        NamedColor("light slate grey", 0x77, 0x88, 0x99),
        NamedColor("light steel blue", 0xB0, 0xC4, 0xDE),
        NamedColor("light yellow", 0xFF, 0xFF, 0xE0),
        NamedColor("lime", 0x00, 0xFF, 0x00),
        NamedColor("lime green", 0x32, 0xCD, 0x32),
        NamedColor("linen", 0xFA, 0xF0, 0xE6),
        NamedColor("magenta", 0xFF, 0x00, 0xFF),
        NamedColor("maroon", 0x80, 0x00, 0x00),
        NamedColor("medium aquamarine", 0x66, 0xCD, 0xAA),
        NamedColor("medium blue", 0x00, 0x00, 0xCD),
        NamedColor("medium orchid", 0xBA, 0x55, 0xD3),
        NamedColor("medium purple", 0x93, 0x70, 0xDB),
        NamedColor("medium sea green", 0x3C, 0xB3, 0x71),
        NamedColor("medium slate blue", 0x7B, 0x68, 0xEE),
        NamedColor("medium spring green", 0x00, 0xFA, 0x9A),
        NamedColor("medium turquoise", 0x48, 0xD1, 0xCC),
        NamedColor("medium violet red", 0xC7, 0x15, 0x85),
        NamedColor("midnight blue", 0x19, 0x19, 0x70),
        NamedColor("mint cream", 0xF5, 0xFF, 0xFA),
        NamedColor("misty rose", 0xFF, 0xE4, 0xE1),
        NamedColor("moccasin", 0xFF, 0xE4, 0xB5),
        NamedColor("navajo white", 0xFF, 0xDE, 0xAD),
        NamedColor("navy", 0x00, 0x00, 0x80),
        NamedColor("old lace", 0xFD, 0xF5, 0xE6),
        NamedColor("olive", 0x80, 0x80, 0x00),
        NamedColor("olive drab", 0x6B, 0x8E, 0x23),
        NamedColor("orange", 0xFF, 0xA5, 0x00),
        NamedColor("orange red", 0xFF, 0x45, 0x00),
        NamedColor("orchid", 0xDA, 0x70, 0xD6),
        NamedColor("pale goldenrod", 0xEE, 0xE8, 0xAA),
        NamedColor("pale green", 0x98, 0xFB, 0x98),
        NamedColor("pale turquoise", 0xAF, 0xEE, 0xEE),
        NamedColor("pale violet red", 0xDB, 0x70, 0x93),
        NamedColor("papaya whip", 0xFF, 0xEF, 0xD5),
        NamedColor("peach puff", 0xFF, 0xDA, 0xB9),
        NamedColor("peru", 0xCD, 0x85, 0x3F),
        NamedColor("pink", 0xFF, 0xC0, 0xCB),
        NamedColor("plum", 0xDD, 0xA0, 0xDD),
        NamedColor("powder blue", 0xB0, 0xE0, 0xE6),
        NamedColor("purple", 0x80, 0x00, 0x80),
        NamedColor("rebecca purple", 0x66, 0x33, 0x99),
        NamedColor("red", 0xFF, 0x00, 0x00),
        NamedColor("rosy brown", 0xBC, 0x8F, 0x8F),
        NamedColor("royal blue", 0x41, 0x69, 0xE1),
        NamedColor("saddle brown", 0x8B, 0x45, 0x13),
        NamedColor("salmon", 0xFA, 0x80, 0x72),
        NamedColor("sandy brown", 0xF4, 0xA4, 0x60),
        NamedColor("sea green", 0x2E, 0x8B, 0x57),
        NamedColor("seashell", 0xFF, 0xF5, 0xEE),
        NamedColor("sienna", 0xA0, 0x52, 0x2D),
        NamedColor("silver", 0xC0, 0xC0, 0xC0),
        NamedColor("sky blue", 0x87, 0xCE, 0xEB),
        NamedColor("slate blue", 0x6A, 0x5A, 0xCD),
        NamedColor("slate gray", 0x70, 0x80, 0x90),
        NamedColor("slate grey", 0x70, 0x80, 0x90),
        NamedColor("snow", 0xFF, 0xFA, 0xFA),
        NamedColor("spring green", 0x00, 0xFF, 0x7F),
        NamedColor("steel blue", 0x46, 0x82, 0xB4),
        NamedColor("tan", 0xD2, 0xB4, 0x8C),
        NamedColor("teal", 0x00, 0x80, 0x80),
        NamedColor("thistle", 0xD8, 0xBF, 0xD8),
        NamedColor("tomato", 0xFF, 0x63, 0x47),
        NamedColor("turquoise", 0x40, 0xE0, 0xD0),
        NamedColor("violet", 0xEE, 0x82, 0xEE),
        NamedColor("wheat", 0xF5, 0xDE, 0xB3),
        NamedColor("white", 0xFF, 0xFF, 0xFF),
        NamedColor("white smoke", 0xF5, 0xF5, 0xF5),
        NamedColor("yellow", 0xFF, 0xFF, 0x00),
        NamedColor("yellow green", 0x9A, 0xCD, 0x32),
    )

    /**
     * Returns the closest standard color name for [color], an ARGB packed Int
     * (e.g. as produced by `0xFFRRGGBB.toInt()` or Kotlin/Java Color.argb equivalents).
     * If [color] is null, returns "null".
     * If the alpha channel isn't fully opaque (0xFF), appends the alpha as a percentage,
     * e.g. "dark green 90%".
     */
    fun closestColorName(color: Int?): String {
        if (color == null) return "null"

        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF

        var best: NamedColor = COLORS[0]
        var bestDist = Long.MAX_VALUE

        for (c in COLORS) {
            // "Redmean" weighted Euclidean distance — closer to human perception
            // than plain RGB Euclidean distance.
            val rMean = (r + c.r) / 2
            val dr = (r - c.r).toLong()
            val dg = (g - c.g).toLong()
            val db = (b - c.b).toLong()
            val dist = (((512 + rMean) * dr * dr) shr 8) +
                    4 * dg * dg +
                    (((767 - rMean) * db * db) shr 8)

            if (dist < bestDist) {
                bestDist = dist
                best = c
            }
        }

        return if (a == 0xFF) {
            best.display
        } else {
            val percent = ((a / 255.0) * 100).roundToInt()
            "${best.display} $percent%"
        }
    }
}