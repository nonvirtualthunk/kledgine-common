package arx.display.ascii

import arx.core.Noto
import arx.core.Resources
import arx.display.core.Image
import arx.display.core.ImagePath

data class CustomChar(val char : Char, val stringSequence: String, val imgPath: ImagePath)

object CustomChars {
    private val path = "fonts/customChars"
    private val charStart = Char(10000)
    val Helm = CustomChar(charStart + 1, "helm", ImagePath("$path/helm.png"))
    val Shield = CustomChar(charStart + 2, "shield", ImagePath("$path/shield.png"))
    val Aim = CustomChar(charStart + 3, "aim", ImagePath("$path/aim.png"))
    val BrokenHeart = CustomChar(charStart + 4, "brokenHeart", ImagePath("$path/broken_heart.png"))
    val BrokenSword = CustomChar(charStart + 5, "brokenSword", ImagePath("$path/broken_sword.png"))
    val FullCaretLeft = CustomChar(charStart + 6, "fullCaretLeft", ImagePath("$path/full_caret_left.png"))
    val FullCaretRight = CustomChar(charStart + 7, "fullCaretRight", ImagePath("$path/full_caret_right.png"))
    val Summation = CustomChar('∑', "summation", ImagePath("$path/summation.png"))
    val LeftSlope = CustomChar(charStart + 8, "leftSlope", ImagePath("$path/left_slope.png"))
    val RightSlope = CustomChar(charStart + 9, "rightSlope", ImagePath("$path/right_slope.png"))

    val allCustomGlyphs = listOf(
        Helm,
        Shield,
        Aim,
        BrokenHeart,
        BrokenSword,
        FullCaretLeft,
        FullCaretRight,
        Summation,
        LeftSlope,
        RightSlope
    )


    val customGlyphs = allCustomGlyphs.associate { it.char to it.imgPath }

    val customCharsBySequence = allCustomGlyphs.associate { it.stringSequence to it.char }


    fun replaceEscapeSequences(str: String) : String {
        if (! str.contains('∑')) {
            return str
        }
        var inEscape = false
        val out = java.lang.StringBuilder()
        val escapeBuilder = java.lang.StringBuilder()

        for (i in 0 until str.length) {
            val c = str[i]

            if (c == '∑') {
                if (inEscape) {
                    val cc = customCharsBySequence[escapeBuilder.toString()]
                    if (cc != null) {
                        out.append(cc)
                    } else {
                        Noto.warn("Unknown custom char with escape sequence $escapeBuilder")
                    }
                    inEscape = false
                    escapeBuilder.clear()
                } else {
                    inEscape = true
                }
            } else {
                if (inEscape) {
                    escapeBuilder.append(c)
                } else {
                    out.append(c)
                }
            }
        }
        if (inEscape) {
            out.append('∑')
            out.append(escapeBuilder)
        }

        return out.toString()
    }
}