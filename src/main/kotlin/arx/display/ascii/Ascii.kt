package arx.display.ascii

import arx.core.*
import arx.display.windowing.components.ascii.AsciiRichText
import com.typesafe.config.ConfigValue

object Ascii {

    val ShadingChars = arrayOf('░', '▒', '▓', '█')

//    val allCharInts = intArrayOf(948,945,9524,171,9632,49,9563,9554,178,99,125,931,167,52,246,68,9561,966,51,9532,61,116,9835,176,9787,232,9644,108,98,182,181,50,101,9484,35,251,9574,118,238,255,105,64,120,9580,198,224,34,44,97,220,964,106,9492,122,191,241,9568,242,65,9578,163,8735,39,250,9516,103,8993,8734,9572,8976,46,233,9616,162,96,8252,63,243,92,9619,119,223,9660,112,8729,9786,249,60,69,235,236,9488,920,37,33,9569,234,186,9573,915,8616,9559,66,8962,121,209,9560,40,42,9579,9824,94,9600,9834,87,231,45,9571,228,9556,934,8319,54,963,9474,172,123,9794,9612,47,56,9557,9555,8801,247,110,91,9689,161,9553,183,239,237,55,9788,107,36,244,9567,9650,9575,9792,949,402,8593,9658,8597,104,189,9566,93,197,90,9496,57,165,53,960,8226,230,9608,100,9675,187,8594,102,9564,8595,111,8776,38,114,9617,117,115,32,9552,9472,9570,937,8730,201,214,9558,8359,8992,48,8592,9688,83,41,9604,9500,113,8745,170,9827,199,9830,9508,188,9668,229,62,252,8596,9829,196,9577,9576,226,9565,9562,9618,109)
    val allCharInts = intArrayOf(948,945,9524,171,9632,49,9563,9554,178,99,125,931,167,52,246,68,9561,966,51,9532,61,116,9835,176,9787,232,9644,108,98,182,181,50,101,9484,35,251,9574,118,238,255,105,64,120,9580,198,224,34,44,97,220,964,106,9492,122,191,241,9568,242,65,9578,163,8735,39,250,9516,103,8993,8734,9572,8976,46,233,9616,162,96,8252,63,243,92,9619,119,223,9660,112,8729,9786,249,60,69,235,236,9488,920,37,33,9569,234,186,9573,915,8616,9559,66,8962,121,209,9560,40,42,9579,9824,94,9600,9834,87,231,45,9571,228,9556,934,8319,54,963,9474,172,123,9794,9612,47,56,9557,9555,8801,247,110,91,9689,161,9553,183,239,237,55,9788,107,36,244,9567,9650,9575,9792,949,402,8593,9658,8597,104,189,9566,93,197,90,9496,57,165,53,960,8226,230,9608,100,9675,187,8594,102,9564,8595,111,8776,38,114,9617,117,115,32,9552,9472,9570,937,8730,201,214,9558,8359,8992,48,8592,9688,83,41,9604,9500,113,8745,170,9827,199,9830,9508,188,9668,229,62,252,8596,9829,196,9577,9576,226,9565,9562,9618,109,43,58,59,67,70,71,72,73,74,75,76,77,78,79,80,81,82,84,85,86,88,89,95,124,126)

    val allChars = run {
        val chars = CharArray(allCharInts.size)
        for (i in 0 until allCharInts.size) {
            chars[i] = Char(allCharInts[i])
        }
        chars
    }

    val FullBlockChar = '█'
    val blockChars = listOf('▀','▄','█','▌','▐', '░', '▒', '▓') //  '◘', '◙', '│', '─', '/', '\\'
    val blockCharSet = blockChars.toSet()

    enum class BoxPieces {
        None,
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
        HorizontalBottom,
        HorizontalTop,
        VerticalLeft,
        VerticalRight,
        Cross,
        RightJoin,
        LeftJoin,
        TopJoin,
        BottomJoin,
    }

    enum class BoxStyle {
        SolidInternal,
        SolidExternal,
        CornersInternal,
        Line;

        companion object : FromConfigCreator<BoxStyle> {
            override fun createFromConfig(cv: ConfigValue?): BoxStyle? {
                if (cv == null) { return null }

                return cv.asStr()?.let { str ->
                    when (str.lowercase().replace(" ","")) {
                        "solidinternal", "internal" -> SolidInternal
                        "cornersinternal", "corners", "internalcorners" -> CornersInternal
                        "solidexternal", "solid", "external" -> SolidExternal
                        "line" -> Line
                        else -> null
                    }
                }
            }
        }
    }
}

sealed class AsciiDrawCommand {
    // todo: implement (if needed)
//    var ignoreBounds = false
    var identifier: kotlin.String? = null
    fun withIdentifier(id: kotlin.String) : AsciiDrawCommand {
        identifier = id
        return this
    }

    data class Box (val position : Vec3i, val dimensions: Vec2i, val style: Ascii.BoxStyle, val scale : Int, val edgeColor: RGBA, val fillColor: RGBA?, val join: Boolean = false) : AsciiDrawCommand()

    data class Line (val from : Vec3i, val to: Vec3i, val style: Ascii.BoxStyle, val scale: Int, val foregroundColor: RGBA, val backgroundColor: RGBA?) : AsciiDrawCommand()

    data class Blit (val canvas : AsciiCanvas, val position: Vec3i, val scale: Int, val alpha: Float? = null) : AsciiDrawCommand()

    data class BlitBuffer (val buffer : AsciiBuffer, val position: Vec3i, val scale: Int, val alpha: Float? = null) : AsciiDrawCommand()

    data class Glyph(val character: Char, val position: Vec3i, val scale: Int, val foregroundColor: RGBA, val backgroundColor: RGBA?) : AsciiDrawCommand()

    data class String(val text: AsciiRichText, val position: Vec3i, val scale: Int, val foregroundColor: RGBA, val backgroundColor: RGBA?) : AsciiDrawCommand()

    data class GlyphRepeat(val character: Char, val position: Vec3i, val dimensions: Vec2i, val scale: Int, val foregroundColor: RGBA, val backgroundColor: RGBA?) : AsciiDrawCommand()

    data class HexOutline(val position: Vec3i, val dimensions: Vec2i, val scale: Int, val foregroundColor: RGBA, val backgroundColor: RGBA?) : AsciiDrawCommand()
}