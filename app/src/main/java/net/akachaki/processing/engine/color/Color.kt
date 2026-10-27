package net.akachaki.processing.engine.color

import kotlin.math.floor
import kotlin.math.roundToInt

object Color {

    fun fromGrey(normalizedValue: Float): Int {
        val intValue = (normalizedValue * 255f).roundToInt()
        val colorValue = 255 - intValue.coerceAtMost(255)
        return 0xFF000000.toInt() or (colorValue shl 16) or (colorValue shl 8) or colorValue
    }

    fun fromGrey(i: Int): Int {
        val colorValue = 255 - i.coerceAtMost(255)
        return 0xFF000000.toInt() or (colorValue shl 16) or (colorValue shl 8) or colorValue
    }

    fun HSBtoRGB(hue: Float, saturation: Float, brightness: Float): Int {
        var r = 0
        var g = 0
        var b = 0
        if (saturation == 0f) {
            b = (brightness * 255.0f + 0.5f).toInt()
            g = b
            r = g
        } else {
            val h = (hue - floor(hue).toFloat()) * 6.0f
            val f = h - floor(h.toDouble()).toFloat()
            val p = brightness * (1.0f - saturation)
            val q = brightness * (1.0f - saturation * f)
            val t = brightness * (1.0f - (saturation * (1.0f - f)))
            when (h.toInt()) {
                0 -> {
                    r = (brightness * 255.0f + 0.5f).toInt()
                    g = (t * 255.0f + 0.5f).toInt()
                    b = (p * 255.0f + 0.5f).toInt()
                }

                1 -> {
                    r = (q * 255.0f + 0.5f).toInt()
                    g = (brightness * 255.0f + 0.5f).toInt()
                    b = (p * 255.0f + 0.5f).toInt()
                }

                2 -> {
                    r = (p * 255.0f + 0.5f).toInt()
                    g = (brightness * 255.0f + 0.5f).toInt()
                    b = (t * 255.0f + 0.5f).toInt()
                }

                3 -> {
                    r = (p * 255.0f + 0.5f).toInt()
                    g = (q * 255.0f + 0.5f).toInt()
                    b = (brightness * 255.0f + 0.5f).toInt()
                }

                4 -> {
                    r = (t * 255.0f + 0.5f).toInt()
                    g = (p * 255.0f + 0.5f).toInt()
                    b = (brightness * 255.0f + 0.5f).toInt()
                }

                5 -> {
                    r = (brightness * 255.0f + 0.5f).toInt()
                    g = (p * 255.0f + 0.5f).toInt()
                    b = (q * 255.0f + 0.5f).toInt()
                }
            }
        }
        return -0x1000000 or (r shl 16) or (g shl 8) or (b shl 0)
    }

    const val WHITE_50: Int = 0x88FFFFFF.toInt()
    const val LIGHT_BLUE: Int = 0xFF7777FF.toInt()
    const val BLACK: Int = 0xFF000000.toInt()
    const val BLACK_50: Int = 0x88000000.toInt()
    const val RED: Int = 0xFFFF0000.toInt()
    const val LIGHT_RED: Int = 0xFFFF7777.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val BLUE: Int = 0xFF0000FF.toInt()
    const val GREEN: Int = 0xFF00FF00.toInt()
    const val DARK_GREEN: Int = 0xFF007700.toInt()
    const val ORANGE: Int = 0xFFFFA500.toInt()
    const val TRANSPARENT: Int = 0x00FFFFFF

    val Int.alpha: Float
        get() =
            ((this and 0xFF000000.toInt()) ushr 24) / 255f

    val Int.red: Float
        get() =
            ((this and 0xFF0000) ushr 16) / 255f

    val Int.green: Float
        get() =
            ((this and 0xFF00) ushr 8) / 255f

    val Int.blue: Float
        get() =
            (this and 0xFF) / 255f

    val Int.whiteness: Float
        get() {
//    val alpha = this.alpha
//    val value = this.blue * alpha
//    return value
            return this.blue
        }


}