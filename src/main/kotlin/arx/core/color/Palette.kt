package arx.core.color

import arx.core.HSL
import arx.core.RGBA
import arx.core.White

interface Color {
    fun toHSL() : HSL
    fun toRGBA() : RGBA {
        return toRGBA()
    }
    fun to16Bit() : UShort {
        return toRGBA().to16BitColor()
    }
}

data class RGBAPalette(val colors: List<RGBA>) {

    fun colorClosestTo(other: Color) : RGBA {
        var minError = Int.MAX_VALUE
        var minColor = White

        val otherRGBA = other.toRGBA()
        for (c in colors) {
            val error2 = RGBA.colorDistance2(c, otherRGBA)
            if (error2 < minError) {
                minError = error2
                minColor = c
            }
        }

        return minColor
    }
}