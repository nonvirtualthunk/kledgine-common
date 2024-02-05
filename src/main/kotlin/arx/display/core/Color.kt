package arx.display.core


import arx.core.RGBA
import arx.core.clamp
import kotlin.math.roundToInt



fun mix(a: RGBA, b : RGBA, f : Float) : RGBA {
    val invF = 1.0f - f
    val newA = (a.a.toFloat() * invF + b.a.toFloat() * f).roundToInt().clamp(0, 255).toUByte()

    if (a.a <= 1u) {
        return RGBA(b.r, b.g, b.b, newA)
    } else if (b.a <= 1u) {
        return RGBA(a.r, a.g, a.b, newA)
    }

    val newR = (a.r.toFloat() * invF + b.r.toFloat() * f).roundToInt().clamp(0, 255).toUByte()
    val newG = (a.g.toFloat() * invF + b.g.toFloat() * f).roundToInt().clamp(0, 255).toUByte()
    val newB = (a.b.toFloat() * invF + b.b.toFloat() * f).roundToInt().clamp(0, 255).toUByte()

    return RGBA(newR, newG, newB, newA)
}
