package arx.core

import arx.application.TuiApplication
import arx.core.Resources.config
import arx.display.core.Image
import arx.display.core.TextQuad
import com.typesafe.config.ConfigValue
import java.awt.*
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import java.lang.Integer.max
import java.lang.Integer.min
import java.text.AttributedString
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

val FontIdCounter = AtomicInteger(1)

class GlyphRenderer {
    val timer = Metrics.timer("GlyphRenderer.render")
    val buffer = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
    val g: Graphics2D = buffer.createGraphics().apply {
        background = Color(255,255,255,255)
    }

    fun render(shape: Shape): Image {
        val ctx = timer.time()
        g.transform = AffineTransform()
        g.composite = AlphaComposite.getInstance(AlphaComposite.CLEAR, 0.0f)
        g.color = Color(255, 255, 255, 255)
        g.fillRect(0, 0, shape.bounds.width + 1, shape.bounds.height + 1)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f)
        g.transform = AffineTransform.getTranslateInstance(-shape.bounds.minX, -shape.bounds.minY)
        g.color = Color(255, 255, 255, 255)
        g.fill(shape)
        val img = Image.ofSize(shape.bounds.width, shape.bounds.height)
        val v = RGBA()
        val rast = buffer.data
        for (y in 0 until shape.bounds.height) {
            for (x in 0 until shape.bounds.width) {
                v.r = rast.getSample(x, y, 0).toUByte()
                v.g = rast.getSample(x, y, 1).toUByte()
                v.b = rast.getSample(x, y, 2).toUByte()
                v.a = rast.getSample(x, y, 3).toUByte()

                img[x, img.height - y - 1] = v
            }
        }
        ctx.stop()
        return img
    }

    fun fontMetrics(font: Font?): FontMetrics {
        return g.getFontMetrics(font)
    }
}

class ArxTypeface(internal val baseAwtFont: Font) {
    val renderer : GlyphRenderer by lazy { GlyphRenderer() }
    val fontsBySize = mutableMapOf<Int, ArxFont>()
    var baseSize = when(baseAwtFont.family) {
        "ChevyRay - Express" -> 9
        "ChevyRay - Skullboy" -> 16
        "ChevyRay - Roundabout" -> 32
        "Straight pixel gothic" -> 32
        else -> {
            val conf = config("fonts/${baseAwtFont.family}.sml")
            conf["basePointSize"].asInt() ?: 12
        }
    }
    val pixelFont = when(baseAwtFont.family) {
        "ChevyRay - Express" -> true
        "ChevyRay - Skullboy" -> true
        "ChevyRay - Roundabout" -> true // note: roundabout does  not work very well
        "Straight pixel gothic" -> true
        else -> {
            val conf = config("fonts/${baseAwtFont.family}.sml")
            conf["pixelFont"].asBool() ?: true
        }
    }

    val baseFont = this.withSize(baseSize)

    fun withSize(size: Int) : ArxFont {
        val effSize = if (pixelFont) {
            ceil(size.toFloat() / baseSize.toFloat()).toInt() * baseSize
        } else {
            size
        }
        return fontsBySize.getOrPut(effSize) { ArxFont(baseAwtFont.deriveFont(effSize.toFloat()), effSize, this) }
    }

    companion object {
        fun load(path: String) : ArxTypeface {
            return ArxTypeface(Font.createFont(Font.TRUETYPE_FONT, File(path)))
        }

        fun load(stream: InputStream) : ArxTypeface {
            return ArxTypeface(Font.createFont(Font.TRUETYPE_FONT, stream))
        }
    }
}

class ArxFont(val font: Font, val size: Int, val typeface: ArxTypeface) {
    val id = FontIdCounter.getAndIncrement()
    internal val glyphCache = mutableMapOf<Int, Image>()
    private val glyphCharCache = mutableMapOf<Char, Image>()
    val fontMetrics : FontMetrics by lazy { typeface.renderer.fontMetrics(font) }

    val maxGlyphSize: Vec2i get() {
        return Vec2i(fontMetrics.maxAdvance, fontMetrics.maxAscent + fontMetrics.maxDescent)
    }

    fun glyphImageFor(c: Char) : Image {
        return glyphCharCache.getOrPut(c) {
            Metrics.timer("glyphRenderer").timeStmt {
                val quads = mutableListOf<TextQuad>()
                val renderer = RecordingGlyphRenderGraphics(this, quads)
                renderer.drawString("$c", 0, 0)

                val quad = quads[0]
                val img = Image.ofSize(fontMetrics.maxAdvance, fontMetrics.maxAscent + fontMetrics.maxDescent)

                val baseline = fontMetrics.maxDescent

                val startX = quad.position.x.toInt()
                val startY = baseline - (quad.position.y.toInt() + quad.dimensions.y.toInt())
                for (x in 0 until quad.image.width) {
                    for (y in 0 until quad.image.height) {
                        val targetX = x + startX
                        val targetY = y + startY

                        if (targetX >= 0 && targetY >= 0 && targetX < img.width && targetY < img.height) {
                            img[targetX, targetY] = quad.image.pixel(x,y)
                        }
                    }
                }

                img
            }
        }
    }

    fun withSize(size: Int) : ArxFont {
        return typeface.withSize(size)
    }
}



sealed interface RichTextSegment {
    fun isEmpty(): Boolean

    fun plainText() : String
}

data class SimpleTextSegment (val text: String) : RichTextSegment {
    override fun isEmpty(): Boolean {
        return text.isEmpty()
    }

    override fun plainText(): String {
        return text
    }
}
data class StyledTextSegment (val text: String, val color: RGBA? = null, val font: ArxFont? = null) : RichTextSegment {
    override fun isEmpty(): Boolean {
        return text.isEmpty()
    }

    override fun plainText(): String {
        return text
    }
}
data class ImageSegment (val image: Image, val color: RGBA? = null, val size: Int? = null) : RichTextSegment {
    override fun isEmpty(): Boolean {
        return false
    }

    override fun plainText(): String {
        return ""
    }
}

data class RichText(
    val segments : MutableList<RichTextSegment> = mutableListOf(),
    var color: RGBA? = null,
    var font: ArxFont? = null
) {
    constructor (str: String, color: RGBA? = null, font: ArxFont? = null) : this() {
        if (str.isNotEmpty()) {
            if (color != null || font != null) {
                segments.add(StyledTextSegment(str, color, font))
            } else {
                segments.add(SimpleTextSegment(str))
            }
        }
    }

    fun isEmpty() : Boolean {
        return segments.isEmpty() || segments.all { it.isEmpty() }
    }

    fun plainText() : String {
        return segments.joinToString { it.plainText() }
    }

    fun add(r : RichTextSegment) : RichText {
        segments.add(r)
        return this
    }

    fun add(r : RichText) : RichText {
        segments.addAll(r.segments)
        return this
    }
}

enum class HorizontalTextAlignment {
    Left,
    Centered,
    Right;

    companion object : FromConfigCreator<HorizontalTextAlignment> {
        override fun createFromConfig(cv: ConfigValue?): HorizontalTextAlignment? {
            return when (cv.asStr()?.lowercase() ?: "") {
                "left" -> Left
                "centered", "center" -> Centered
                "right" -> Right
                else -> null
            }
        }
    }
}

class TextLayout {
    val quads = mutableListOf<TextQuad>()

    /**
     * Bounds in absolute coordinates of the full occupied area of the text
     * not relative to the region.
     */
    var min = Vec2i(0,0)
    var max = Vec2i(0,0)
    var endCursorPos = Vec2i(0,0)
    var lineBreaks : List<Int> = listOf()
    var lineYs : List<Int> = listOf()
    var lineHeight : Int = 1

    val bounds : Recti
        get() { return Recti(min.x, min.y, max.x - min.x + 1, max.y - min.y + 1)}

    fun empty(): Boolean {
        return min == max
    }

    data class Params(
        val text: RichText,
        val position: Vec2i,
        val region: Recti,
        val defaultFont: ArxFont = DefaultFont,
        val defaultColor: RGBA? = null,
        val horizontalAlignment: HorizontalTextAlignment = HorizontalTextAlignment.Left
    )

    companion object {
        val frc = FontRenderContext(AffineTransform(), false, false)
        val DefaultTypeface = Resources.typeface("arx/fonts/ChevyRayExpress.ttf")
        val DefaultFont = DefaultTypeface.withSize(9)

        fun layout(text: RichText, position: Vec2i, region: Recti, defaultFont: ArxFont = DefaultFont) : TextLayout {
            return layout(Params(text = text, position = position, region = region, defaultFont = defaultFont))
        }

        fun layout(params : Params) : TextLayout {
            with(params) {
                val ret = TextLayout()
                ret.min.x = position.x
                ret.min.y = position.y
                ret.max.x = position.x
                ret.max.y = position.y
                val cursor = Vec2i(position.x, position.y + defaultFont.fontMetrics.maxAscent)
                for (segment in text.segments) {
                    layoutSegment(ret, text, segment, cursor, region, params)
                }
                ret.endCursorPos = cursor

                if (horizontalAlignment == HorizontalTextAlignment.Centered || horizontalAlignment == HorizontalTextAlignment.Right) {
                    val effLineBreaks = ret.lineBreaks + ret.quads.size

                    var start = 0
                    for (lineBreak in effLineBreaks) {
                        if (lineBreak - 1 >= 0) {
                            var minX = ret.quads[start].position.x
                            var maxX = ret.quads[lineBreak - 1].position.x + ret.quads[lineBreak - 1].advance
                            for (i in start until lineBreak) {
                                minX = minX.min(ret.quads[i].position.x)
                                maxX = maxX.max(ret.quads[i].position.x + ret.quads[i].advance)
                            }
                            val offset = if (horizontalAlignment == HorizontalTextAlignment.Centered) {
                                (region.width - (maxX - minX)) / 2
                            } else {
                                region.width - (maxX - minX)
                            }
                            for (i in start until lineBreak) {
                                ret.quads[i].position.x += offset
                            }
                            start = lineBreak
                        }
                    }
                }

                return ret
            }
        }

        private fun layoutTextSegment(layout: TextLayout, rt: RichText, text: String, color: RGBA?, cursor: Vec2i, region: Recti, font: ArxFont) {
            layout.lineHeight = layout.lineHeight.max(font.fontMetrics.height)

            if (region.width <= 0 || region.height <= 0 || text.isEmpty()) {
                return
            }

            val g = RecordingGlyphRenderGraphics(font, layout.quads)
            val attrStr = AttributedString(text)
            attrStr.addAttribute(TextAttribute.FONT, font.font)
            val iter = attrStr.iterator
            val lbm = LineBreakMeasurer(iter, frc)

            val quadsPreIndex = layout.quads.size
            while (lbm.position < iter.endIndex) {
                // track the index prior to rendering this line so we can target just the quads this line adds
                val quadsPreLineIndex = layout.quads.size

                // we have to check min here because we may be starting mid-line then rolling over onto the next
                // line at the beginning
                layout.min.x = min(layout.min.x, cursor.x)
                // check the max y here since we don't want to count it up after a line break if there isn't anything
                // _on_ that next line
                layout.max.y = max(layout.max.y, cursor.y + font.fontMetrics.descent)

                // use the line break measurer to lay out which glyphs will be on this line
                val tl = lbm.nextLayout((region.x + region.width - cursor.x).toFloat())

                // this draw call uses the "recording" renderer to turn rendering commands into TextQuad instances in our
                // list we supplied above
                tl.draw(g, cursor.x.toFloat(), cursor.y.toFloat())

                // retroactively apply font-ish information to the quads created by the draw above
                for (qi in quadsPreLineIndex until g.quads.size) {
                    g.quads[qi].baselineY = cursor.y
                    g.quads[qi].ascent = font.fontMetrics.ascent

                }

                // if our position is less than the end index then there are still subsequent lines to come, and we should
                // move the cursor down and left, mark our line breaks, etc. Otherwise, just advance x by the advance
                if (lbm.position < iter.endIndex) {
                    layout.lineBreaks = layout.lineBreaks + g.quads.size
                    layout.lineYs = layout.lineYs + cursor.y

                    cursor.x = region.x
                    cursor.y += font.fontMetrics.height
                } else {
                    cursor.x += tl.advance.toInt()
                }
            }

            // apply color information and check maximum x extent on every quad we've added across all lines
            for (qi in quadsPreIndex until g.quads.size) {
                g.quads[qi].color = color ?: rt.color
                layout.max.x = max(layout.max.x, g.quads[qi].position.x.toInt() + g.quads[qi].advance)

            }
        }

        private fun layoutSegment(layout: TextLayout, rt: RichText, segment: RichTextSegment, position: Vec2i, region: Recti, params: Params) {
            with(params) {
                return when (segment) {
                    is SimpleTextSegment -> layoutTextSegment(layout, rt, segment.text, rt.color ?: defaultColor, position, region, rt.font ?: defaultFont)
                    is StyledTextSegment -> layoutTextSegment(layout, rt, segment.text, segment.color ?: rt.color ?: defaultColor, position, region, segment.font ?: rt.font ?: defaultFont)
                    is ImageSegment -> TODO("Image segments not yet implemented")
                }
            }
        }
    }

    override fun toString(): String {
        val quadStr = quads.joinToString("\n\t\t") { q -> "pos: ${q.position}, dim: ${q.dimensions}" }
        return """TextLayout {
            |   bounds: $bounds
            |   rects: [
            |       $quadStr
        |       ]
            |}
        """.trimMargin()
    }


}


fun main() {
    System.setProperty("java.awt.headless", "true")

    val testText = "this is a jest, the quick brown fox jumped over the lazy dog. THE QUICK BROWN FOX JUMPED OVER THE LAZY DOG"

    val rt = RichText(testText)

    val layout = TextLayout.layout(rt, Vec2i(100,100), Recti(50,50,300, 300))
    println(layout)

//    Metrics.print()
}

class RecordingGlyphRenderGraphics(val font: ArxFont, val quads : MutableList<TextQuad> = mutableListOf()) : UnimplementedGraphics() {

    override fun drawGlyphVector(g: GlyphVector?, x: Float, y: Float) {
        if (g != null) {
            val cache = font.glyphCache

            for (i in 0 until g.numGlyphs) {
                val shape = g.getGlyphOutline(i, 0.0f, 0.0f)
                val code = g.getGlyphCode(i)
                val advance = g.getGlyphMetrics(i).advanceX.toInt()
                val glyphPos = g.getGlyphPosition(i)

                var img = cache[code]
                if (img == null) {
                    img = if (shape.bounds.width == 0) {
                        Image.ofSize(0,0)
                    } else {
                        font.typeface.renderer.render(shape)
                    }

                    cache[code] = img
                }
                val bounds = shape.bounds

                val pos = Vec2f(bounds.x + x, bounds.y.toFloat() + y)

                quads.add(
                    TextQuad(
                        position = pos,
                        dimensions = Vec2f(bounds.width.toFloat(), bounds.height.toFloat()),
                        color = RGBA(0,0,0,255),
                        image = img,
                        advance = advance
                    )
                )
            }

        }
    }

    override fun drawString(str: String, x: Int, y: Int) {
        val vec = font.font.layoutGlyphVector(fontRenderContext, str.toCharArray(), 0, str.length, Font.LAYOUT_LEFT_TO_RIGHT)
        drawGlyphVector(vec, x.toFloat(), y.toFloat())
    }

    override fun getFont(): Font {
        return font.font
    }

    override fun getFontRenderContext(): FontRenderContext {
        return FontRenderContext(AffineTransform(), false, false)
    }

    override fun getFontMetrics(f: Font?): FontMetrics {
        if (f === font.font) {
            return font.fontMetrics
        } else {
            error("Recording glyph renderer must use the same font as it is constructed with")
        }
    }
}