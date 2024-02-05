package arx.display.core

import arx.core.RGBA
import arx.core.Vec2f

data class SimpleQuad(var position: Vec2f, var dimensions: Vec2f, var image: Image, var color: RGBA? = RGBA(255,255,255,255))

data class TextQuad(val position: Vec2f, val dimensions: Vec2f, val image: Image, val advance : Int = 0, var color: RGBA? = null) {
    var baselineY : Int = 0
    var ascent : Int = 0

}