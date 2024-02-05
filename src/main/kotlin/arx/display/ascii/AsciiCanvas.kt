@file:OptIn(ExperimentalUnsignedTypes::class)

package arx.display.ascii

import arx.application.Application
import arx.application.TuiApplication
import arx.core.*
import arx.core.Resources.font
import arx.core.Resources.image
import arx.display.core.*
import arx.engine.*
import dev.romainguy.kotlin.math.ortho
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import java.io.*
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.collections.set


interface AsciiColorPalette {
    /**
     * Encode a given RGBA value into 16 bits. We return as a UInt purely for convience, all
     * but the first 16 bits will be ignored
     */
    fun encode(color: RGBA): UInt

    /**
     * Decode a 16 bit color stored in an ascii canvas to a full RGBA value
     */
    fun decode(encoded: UInt): RGBA {
        val ret = RGBA(255, 255, 255, 255)
        decode(encoded, ret)
        return ret
    }

    /**
     * Decode a 16 bit color stored in an ascii canvas to a full RGBA value, storing the
     * result in the provided out variable
     */
    fun decode(encoded: UInt, out: RGBA)


    /**
     * Mix the two given encoded colors to produce a new one base on the given fraction.
     * When f is 0 encodedA is returned, when 1 encodedB is returned, values in betwee
     * will be proportionally mixed between the two.
     */
    fun mix(encodedA: UInt, encodedB: UInt, f: Float): UInt
}

object AsciiColorPalette16Bit : AsciiColorPalette {
    override fun encode(color: RGBA): UInt {
        return color.to16BitColor().toUInt()
    }

    override fun decode(encoded: UInt, out: RGBA) {
        out.setFrom16BitColor(encoded)
    }

    override fun mix(encodedA: UInt, encodedB: UInt, f: Float): UInt {
        return RGBA5551.mix(encodedA, encodedB, f).toUInt()
    }
}

class IndexedAsciiColorPalette : AsciiColorPalette {
    private val colors: MutableList<RGBA> = mutableListOf()
    private val index: MutableMap<RGBA, UInt> = mutableMapOf()

    init {
        encode(RGBA(255, 255, 255, 0))
    }

    override fun encode(color: RGBA): UInt {
        val effColor = RGBA(((color.r / 8u) * 8u), (color.g / 8u) * 8u, (color.b / 8u) * 8u, color.a.toUInt())

        val i = index.getOrDefault(effColor, UInt.MAX_VALUE)
        return if (i == UInt.MAX_VALUE) {
            val newI = colors.size
            colors.add(effColor)
            index[effColor] = newI.toUInt()
            newI.toUInt()
        } else {
            i
        }
    }

    override fun decode(i: UInt, out: RGBA) {
        val ret = colors[i.toInt()]
        out.r = ret.r
        out.g = ret.g
        out.b = ret.b
        out.a = ret.a
    }

    override fun decode(i: UInt): RGBA {
        return colors[i.toInt()]
    }

    override fun mix(encodedA: UInt, encodedB: UInt, f: Float): UInt {
        return encode(mix(decode(encodedA), decode(encodedB), f))
    }

    val size: Int
        get() {
            return colors.size
        }

    fun colors(): Iterator<RGBA> {
        return colors.iterator()
    }

    companion object {
        fun fromImage(img: Image): AsciiColorPalette {
            val palette = IndexedAsciiColorPalette()
            for (i in 0 until img.width) {
                val rgba = img.pixel(i, 0)
                palette.encode(rgba)
            }
            return palette
        }
    }
}

// [0-4][5-9][10-14][15]
// 5 5 5 1

// Char data, by byte
// [0-1 : character] [2-3 : foreground color] [4-5 : background color] [6 : section]


@ExperimentalUnsignedTypes
class AsciiBuffer(val dimensions: Vec2i) {
    val chars = ULongArray(dimensions.x * dimensions.y)
    val zBuffer = ByteArray(dimensions.x * dimensions.y)

    fun clear() {
        for (i in 0 until chars.size) {
            chars[i] = 0U
            zBuffer[i] = 0
        }
    }

    fun copy() : AsciiBuffer {
        val ret = AsciiBuffer(Vec2i(dimensions.x, dimensions.y))
        chars.copyInto(ret.chars)
        zBuffer.copyInto(ret.zBuffer)
        return ret
    }

    fun copyInto(other: AsciiBuffer) {
        if (other.dimensions != this.dimensions) {
            Noto.err("copyInto() called for ascii buffers of different sizes: ${other.dimensions}, $dimensions")
        } else {
            chars.copyInto(other.chars)
            zBuffer.copyInto(other.zBuffer)
        }
    }

    fun index(x: Int, y: Int): Int {
        return x * dimensions.y + y
    }

    fun write(x: Int, y: Int, z: Int, c: Char, foregroundEncoded: UInt, backgroundEncoded: UInt, section: ULong = 0U) {
        val idx = index(x, y)

        val srcZ = zBuffer[idx]
        if (srcZ <= z) {
            val effBGI = if (backgroundEncoded == 0U) {
                ((chars[idx] and backgroundMask) shr backgroundShift).toUInt()
            } else {
                backgroundEncoded
            }

            val combined = (c.code.toULong() and characterMask) or
                    (foregroundEncoded.toULong() shl foregroundShift) or
                    (effBGI.toULong() shl backgroundShift) or
                    (section shl sectionShift)

            chars[idx] = combined
            zBuffer[idx] = z.toByte()
        }
    }

    fun write(x: Int, y: Int, z: Int, c: Char, foreground: RGBA, background: RGBA, section: ULong = 0U) {
        write(x,y,z,c, AsciiColorPalette16Bit.encode(foreground), AsciiColorPalette16Bit.encode(background), section)
    }

    fun clear(x: Int, y: Int) {
        val idx = index(x, y)

        chars[idx] = 0UL
        zBuffer[idx] = 0
    }

    companion object {
        fun mask(start: Int, end: Int): ULong {
            var accum = 0UL
            for (i in start until end) {
                accum = accum or (1UL shl i)
            }
            return accum
        }

        val characterMask = mask(0, 16)
        val foregroundMask = mask(16, 32)
        val foregroundShift = 16
        val backgroundMask = mask(32, 48)
        val backgroundShift = 32
        val sectionMask = mask(48, 56)
        val sectionShift = 48
        val inverseSectionMask = sectionMask.inv()
        val inverseBackgroundMask = backgroundMask.inv()
        val inverseForegroundMask = foregroundMask.inv()

        fun sectionIndex(scale: Int, x: Int, y: Int): ULong {
            return (when (scale) {
                1 -> 0
                2 -> 1 + x * 2 + y
                3 -> 5 + x * 3 + y
                4 -> 14 + x * 4 + y
                else -> throw java.lang.IllegalStateException("Unsupported ascii scale : $scale")
            }).toULong()
        }

        fun extractBackground(v: ULong): ULong {
            return (v and backgroundMask) shr backgroundShift
        }

        fun setBackground(v: ULong, newBG: ULong) : ULong {
            return (v and inverseBackgroundMask) or ((newBG shl backgroundShift) and backgroundMask)
        }

        fun extractForeground(v: ULong): ULong {
            return (v and foregroundMask) shr foregroundShift
        }

        val Empty = AsciiBuffer(Vec2i(0,0))
    }
}

data class AsciiGlyph(val character: Char, val foregroundColorEncoded: UInt, val backgroundColorEncoded: UInt, val section: Int) {
    val foregroundColor: RGBA
        get() {
            return AsciiColorPalette16Bit.decode(foregroundColorEncoded)
        }

    val backgroundColor: RGBA
        get() {
            return AsciiColorPalette16Bit.decode(backgroundColorEncoded)
        }
}

class AsciiCanvas(val buffer: AsciiBuffer, val palette: AsciiColorPalette = AsciiColorPalette16Bit) {
    constructor(dim: Vec2i, pal: AsciiColorPalette = AsciiColorPalette16Bit) : this(AsciiBuffer(Vec2i(dim.x, dim.y)), pal)

    val dimensions: Vec2i = Vec2i(buffer.dimensions.x, buffer.dimensions.y)

    var revision = 1
    var renderedRevision = 0

    var name: String? = null

    fun characterDimensions(font: ArxFont): Vec2i {
        return font.maxGlyphSize
    }

    var clipRect: Recti? = null

    fun clear() {
        buffer.clear()
    }

    override fun toString(): String {
        return if (name != null) {
            name ?: "no name"
        } else {
            "AsciiCanvas(${dimensions.x}x${dimensions.y}, $palette)"
        }
    }

    fun blit(toCanvas: AsciiCanvas, targetPosition: Vec3i, scale: Int, rawAlpha: Float? = null) {
        val alpha = rawAlpha ?: 1.0f
        val clipRect = toCanvas.clipRect

        val overlap = if (clipRect != null) {
            val targetRect = Recti(targetPosition.x, targetPosition.y, dimensions.x * scale, dimensions.y * scale)
            targetRect.intersect(clipRect)
        } else {
            Recti(targetPosition.x, targetPosition.y, dimensions.x * scale, dimensions.y * scale).intersect(Recti(0,0,toCanvas.dimensions.x, toCanvas.dimensions.y))
        }

        if (overlap.width <= 0 || overlap.height <= 0) {
            return
        }

        val clippedStart = Vec2i(overlap.x - targetPosition.x, overlap.y - targetPosition.y) / scale
        val clippedEnd = Vec2i(overlap.maxX - targetPosition.x + scale - 1, overlap.maxY - targetPosition.y + scale - 1) / scale

//        val clippedStart = Vec2i(0,0)
//        val clippedEnd = Vec2i(dimensions.x, dimensions.y)

        val effClipRect = clipRect ?: Recti(0, 0, toCanvas.dimensions.x, toCanvas.dimensions.y)


//        var clippedStart = Vec2i((-targetPosition.x).max(0),(-targetPosition.y).max(0))
//        var clippedEnd = Vec2i(dimensions.x, dimensions.y)
//
//        if (clipRect != null) {
//            clippedStart.x = (clipRect.x - targetPosition.x).max(0)
//            clippedStart.y = (clipRect.y - targetPosition.y).max(0)
//
//            clippedEnd.x = clipRect.width
//        }
//
//        val clippedStart = Vec2i(((toCanvas.clipRect?.x ?: 0) - targetPosition.x).max(0), ((toCanvas.clipRect?.y ?: 0) - targetPosition.y).max(0))
//        val clippedEnd = Vec2i(dimensions.x.min((toCanvas.clipRect?.width ?: toCanvas.dimensions.x) - targetPosition.x),
//                                dimensions.y.min((toCanvas.clipRect?.height ?: toCanvas.dimensions.y) - targetPosition.y))

//        val clippedStart = Vec2i(0,0)
//        val clippedEnd = Vec2i(dimensions.x, dimensions.y)

        val to = toCanvas.buffer

        var alphaLeftover = 1.0f

        for (x in clippedStart.x until clippedEnd.x) {
            val tx = targetPosition.x + x * scale

            for (y in clippedStart.y until clippedEnd.y) {
                val ty = targetPosition.y + y * scale

//                if (tx >= effClipRect.x && ty >= effClipRect.y && tx <= effClipRect.maxX && ty <= effClipRect.maxY) {
                    val srcIndex = buffer.index(x, y)

                    val srcZ = (buffer.zBuffer[srcIndex] + targetPosition.z).toByte()
                    val srcChar = buffer.chars[srcIndex]

                    val srcFGColor = AsciiBuffer.extractForeground(srcChar)
                    val srcBGColor = AsciiBuffer.extractBackground(srcChar)
                    if (srcFGColor == 0uL && srcBGColor == 0uL) {
                        continue
                    }

                    if (scale == 1) {
                        val toIndex = to.index(tx, ty)

                        val toZ = to.zBuffer[toIndex]
                        if (srcZ >= toZ) {
                            if (alpha < 1.0f) {
                                TODO("unimplemented for 1:1 scale")
                            } else {

                                val write = if (srcBGColor == 0UL) {
                                    AsciiBuffer.setBackground(srcChar, AsciiBuffer.extractBackground(to.chars[toIndex]))
                                } else {
                                    srcChar
                                }

                                to.zBuffer[toIndex] = srcZ
                                to.chars[toIndex] = write
                            }
                        }
                    } else {
                        val srcCharNoSection = srcChar and AsciiBuffer.inverseSectionMask
                        if (alphaLeftover >= 0.0f) {
                            alphaLeftover -= 1.0f
                            for (sx in 0 until scale) {
                                for (sy in 0 until scale) {
                                    if (tx + sx >= overlap.x && ty + sy >= overlap.y && tx + sx < overlap.x + overlap.width && ty + sy < overlap.y + overlap.height) {
                                        val toIndex = to.index(tx + sx, ty + sy)
                                        val toZ = to.zBuffer[toIndex]

                                        if (srcZ >= toZ) {

                                            var write = srcCharNoSection or
                                                    (AsciiBuffer.sectionIndex(scale, sx, sy) shl AsciiBuffer.sectionShift)
                                            if (srcBGColor == 0UL) {
                                                write = AsciiBuffer.setBackground(write, AsciiBuffer.extractBackground(to.chars[toIndex]))
                                            }

                                            to.zBuffer[toIndex] = srcZ
                                            if (alpha >= 1.0f) {
                                                to.chars[toIndex] = write
                                            } else {
                                                to.chars[toIndex] = write
                                            }

                                            //  val toValue = to.chars[toIndex]
                                            //
                                            //  val toBgEncoded = AsciiBuffer.extractBackground(toValue)
                                            //  val fromBgEncoded = AsciiBuffer.extractBackground(srcChar)
                                            //  val fromFgEncoded = AsciiBuffer.extractForeground(srcChar)
                                            //
                                            //  val mixedBg = palette.mix(toBgEncoded.toUInt(), fromBgEncoded.toUInt(), alpha)
                                            //  val mixedFg = palette.mix(toBgEncoded.toUInt(), fromFgEncoded.toUInt(), alpha)
                                            //
                                            //  val effSrc = (srcCharNoSection and AsciiBuffer.inverseBackgroundMask and AsciiBuffer.inverseForegroundMask) or
                                            //          (AsciiBuffer.sectionIndex(scale, sx, sy) shl AsciiBuffer.sectionShift) or
                                            //          (mixedBg.toULong() shl AsciiBuffer.backgroundShift) or
                                            //          (mixedFg.toULong() shl AsciiBuffer.foregroundShift)
                                            //
                                            //  to.chars[toIndex] = effSrc
                                        }
                                    }
                                }
                            }
                        }
                        alphaLeftover += alpha
                    }
//                }
            }
        }
    }

    fun write(x: Int, y: Int, z: Int, c: Char, foregroundEncoded: UInt, backgroundEncoded: UInt, section: ULong = 0U) {
        val cr = clipRect
        if (cr == null || x >= cr.x && x < cr.x + cr.width && y >= cr.y && y < cr.y + cr.height) {
            buffer.write(x, y, z, c, foregroundEncoded, backgroundEncoded, section)
        }
    }

    fun copyGlyphFrom(from: AsciiCanvas, srcX: Int, srcY: Int, toX: Int, toY: Int) {
        val fromIndex = from.buffer.index(srcX, srcY)
        val toIndex = buffer.index(toX, toY)
        buffer.chars[toIndex] = from.buffer.chars[fromIndex]
        buffer.zBuffer[toIndex] = from.buffer.zBuffer[fromIndex]
    }

    /**
     * write with bounds checking
     */
    fun writeBC(x: Int, y: Int, z: Int, c: Char, foregroundColor: RGBA, backgroundColor: RGBA? = null, section: ULong = 0U) {
        if (x < 0 || x >= dimensions.x || y < 0 || y >= dimensions.y) {
            return
        }

        val fgEncoded = palette.encode(foregroundColor)
        val bgEncoded = palette.encode(backgroundColor ?: Clear)

        write(x, y, z, c, fgEncoded, bgEncoded, section)
    }

    fun write(x: Int, y: Int, z: Int, c: Char, foregroundColor: RGBA, backgroundColor: RGBA? = null, section: ULong = 0U) {
        val fgEncoded = palette.encode(foregroundColor)
        val bgEncoded = palette.encode(backgroundColor ?: Clear)

        write(x, y, z, c, fgEncoded, bgEncoded, section)
    }

    fun writeScaled(x: Int, y: Int, z: Int, c: Char, scale: Int, foregroundColor: RGBA, backgroundColor: RGBA? = null) {
        val fgEncoded = palette.encode(foregroundColor)
        val bgEncoded = palette.encode(backgroundColor ?: Clear)

        writeScaled(x, y, z, c, scale, fgEncoded, bgEncoded)
    }

    fun writeScaled(x: Int, y: Int, z: Int, c: Char, scale: Int, foregroundColor: UInt, backgroundColor: UInt) {
        for (sx in 0 until scale) {
            for (sy in 0 until scale) {
                write(x + sx, y + sy, z, c, foregroundColor, backgroundColor, AsciiBuffer.sectionIndex(scale = scale, x = sx, y = sy))
            }
        }
    }

    fun clear(x: Int, y: Int, scale: Int = 1) {
        for (sx in 0 until scale) {
            for (sy in 0 until scale) {
                buffer.clear(x + sx, y + sy)
            }
        }
    }

    fun read(x: Int, y: Int): AsciiGlyph {
        val idx = buffer.index(x, y)
        val c = buffer.chars[idx]

        val char = Char((c and AsciiBuffer.characterMask).toInt())
        val foregroundIndex = ((c and AsciiBuffer.foregroundMask) shr AsciiBuffer.foregroundShift).toUInt()
        val backgroundIndex = ((c and AsciiBuffer.backgroundMask) shr AsciiBuffer.backgroundShift).toUInt()
        val section = (c and AsciiBuffer.sectionMask) shr AsciiBuffer.sectionShift

        return AsciiGlyph(char, foregroundIndex, backgroundIndex, section.toInt())
    }

    fun inBounds(v: Vec2i) : Boolean {
        return v.x >= 0 && v.y >= 0 && v.x < dimensions.x && v.y < dimensions.y
    }

    companion object {
        internal val version = 1
        internal val versionBits = 4
        internal val paletteBit = 5

        internal val currentVersionWith16BitPalette : Byte = (version or (1 shl paletteBit)).toByte()

        fun writeToFile(file : File, canvas: AsciiCanvas) {
            FileOutputStream(file).use { fos ->
                GZIPOutputStream(fos).use { gzip ->
                    BufferedOutputStream(gzip).use { bos ->
                        DataOutputStream(bos).use { dos ->
                            dos.writeByte(currentVersionWith16BitPalette.toInt())
                            dos.writeInt(canvas.dimensions.x)
                            dos.writeInt(canvas.dimensions.y)
                            val bb = ByteBuffer.allocate(canvas.buffer.chars.size * 8)
                            bb.asLongBuffer().put(canvas.buffer.chars.asLongArray())
                            dos.write(bb.array())
                        }
                    }
                }
            }
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        fun readFrom(fis: InputStream) : AsciiCanvas {
            GZIPInputStream(fis).use { gzip ->
                BufferedInputStream(gzip).use { bis ->
                    DataInputStream(bis).use { dis ->
                        val header = dis.readByte()
                        if (header != currentVersionWith16BitPalette) {
                            throw IllegalStateException("Invalid serialized canvas header: $header")
                        }
                        val w = dis.readInt()
                        val h = dis.readInt()
                        val canvas = AsciiCanvas(Vec2i(w, h), AsciiColorPalette16Bit)
                        for (i in 0 until w * h) {
                            canvas.buffer.chars[i] = dis.readLong().toULong()
                        }
                        return canvas
                    }
                }
            }
        }

        fun readFromFile(file: File) : AsciiCanvas {
            return FileInputStream(file).use { fis ->
                readFrom(fis)
            }
        }

        fun trimmed(canvas: AsciiCanvas) : AsciiCanvas {
            val min = Vec2i(canvas.dimensions.x - 1, canvas.dimensions.y - 1)
            val max = Vec2i(0, 0)

            for (x in 0 until canvas.dimensions.x) {
                for (y in 0 until canvas.dimensions.y) {
                    val g = canvas.read(x,y)
                    if (g.foregroundColorEncoded != 0u || g.backgroundColorEncoded != 0u) {
                        min.x = min.x.min(x)
                        min.y = min.y.min(y)
                        max.x = max.x.max(x)
                        max.y = max.y.max(y)
                    }
                }
            }

            val newDims = max - min + 1
            val ret = AsciiCanvas(newDims, canvas.palette)

            for (x in 0 until newDims.x) {
                for (y in 0 until newDims.y) {
                    ret.copyGlyphFrom(canvas, x + min.x, y + min.y, x, y)
                }
            }

            return ret
        }
    }
}


class AsciiGraphics(
    var font : ArxFont = font("arx/fonts/Px437_SanyoMBC775-2y.ttf", 16),
    val canvases: MutableSet<AsciiCanvas> = mutableSetOf(),
    var rootCanvas: AsciiCanvas = AsciiCanvas(Vec2i(1, 1), AsciiColorPalette16Bit),
) : DisplayData, CreateOnAccessData {
    companion object : DataType<AsciiGraphics>(AsciiGraphics(), sparse = true)

    override fun dataType(): DataType<*> {
        return AsciiGraphics
    }

    fun renderCanvas(canvas: AsciiCanvas) {
        canvases.add(canvas)
    }

    fun createCanvas(): AsciiCanvas {
        val canvas = AsciiCanvas(rootCanvas.dimensions, rootCanvas.palette)
        renderCanvas(canvas)
        return canvas
    }
}


@ExperimentalUnsignedTypes
open class AsciiGraphicsComponentBase : DisplayComponent(initializePriority = Priority.First) {
    val baseScale = 1

    var backBuffer : AsciiBuffer? = null

    override fun initialize(world: World) {
        val palette = AsciiColorPalette16Bit
        val font = font("arx/fonts/Px437_SanyoMBC775-2y.ttf", 16)

        val dimensions = if (Application.tui) {
            Application.tuiSize
        } else {
            val charDimensions = font.glyphImageFor('█').dimensions * baseScale
            val pixelDimensions: Vec2i = Application.frameBufferSize
            Vec2i(pixelDimensions.x / charDimensions.x, pixelDimensions.y / charDimensions.y)
        }

        val rootCanvas = AsciiCanvas(dimensions, palette)

        println("Full Dimensions: $dimensions")
        val AG = AsciiGraphics(
            font = font,
            rootCanvas = rootCanvas,
        )
        world.attachData(AG)
    }

    private fun createSplitSubRects(n: Int, ii: Int, out: Array<Rectf>): Int {
        var i = ii
        val d = 1.0f / n
        for (x in 0 until n) {
            for (y in 0 until n) {
                out[i++] = Rectf(x * d, y * d, d, d)
            }
        }
        return i
    }

    @Suppress("UNUSED_VALUE")
    val sectionSubRects = run {
        val ret = Array(1 + 4 + 9 + 16) { Rectf(0.0f, 0.0f, 1.0f, 1.0f) }

        var i = 0
        i = createSplitSubRects(1, i, ret)
        i = createSplitSubRects(2, i, ret)
        i = createSplitSubRects(3, i, ret)
        i = createSplitSubRects(4, i, ret)

        ret
    }


    override fun update(world: World): Boolean {
        val AG = world[AsciiGraphics]
        val needsUpdate = AG.canvases.any { it.revision > it.renderedRevision }
        if (needsUpdate) {
            AG.rootCanvas.clear()
            for (canvas in AG.canvases) {
                canvas.blit(AG.rootCanvas, Vec3i(0, 0, 0), 1)
                canvas.renderedRevision = canvas.revision
            }

            updateBuffers(AG)
        }
        return needsUpdate
    }

    open fun updateBuffers(AG: AsciiGraphics) {}

    override fun draw(world: World) {
        val AG = world[AsciiGraphics]
        val out = AnsiConsole.out()

        val back = if (AG.rootCanvas.dimensions != backBuffer?.dimensions) { null } else { backBuffer }
        val buf = AG.rootCanvas.buffer

        out.print(Ansi.ansi().cursor(1,1))

        var prevFg : RGBA? = null
        var prevBg : RGBA? = null

        var skippedCount = 0

        val ansi = Ansi.ansi()
        var prevX = -1
        for (y in buf.dimensions.y - 1 downTo 0) {
            ansi.cursor(buf.dimensions.y - y, 1)
            for (x in 0 until buf.dimensions.x) {
                val c = buf.chars[buf.index(x, y)]
                if (back == null || back.chars[buf.index(x,y)] != c) {
                    val char = Char((c and AsciiBuffer.characterMask).toInt())
                    val foregroundEncoded = ((c and AsciiBuffer.foregroundMask) shr AsciiBuffer.foregroundShift).toUInt()
                    val backgroundEncoded = ((c and AsciiBuffer.backgroundMask) shr AsciiBuffer.backgroundShift).toUInt()

                    if (prevX != x + 1) {
                        ansi.cursorToColumn(x + 1)
                    }
                    prevX = x + 1

                    val backgroundColor = AsciiColorPalette16Bit.decode(backgroundEncoded)
                    val foregroundColor = AsciiColorPalette16Bit.decode(foregroundEncoded)

                    if (prevFg != foregroundColor) {
                        ansi.fgRgb(foregroundColor.r.toInt(), foregroundColor.g.toInt(), foregroundColor.b.toInt())
                        prevFg = foregroundColor
                    }


                    if (backgroundColor.a > 0u) {
                        if (prevBg != backgroundColor) {
                            ansi.bgRgb(backgroundColor.r.toInt(), backgroundColor.g.toInt(), backgroundColor.b.toInt())
                            prevBg = backgroundColor
                        }
                    } else {
                        if (prevBg != Black) {
                            ansi.bgRgb(0, 0, 0)
                            prevBg = Black
                        }
                    }

                    if (char == Char(0) || char == '\n') {
                        ansi.a(' ')
                    } else {
                        ansi.a(char)
                    }
                } else {
                    skippedCount++
                }
            }
        }
        out.print(ansi)
        out.flush()


        if (back == null) {
            backBuffer = AsciiBuffer(buf.dimensions)
        }
        buf.copyInto(backBuffer!!)
    }
}


class AsciiGraphicsComponent : AsciiGraphicsComponentBase() {
    val vao = VAO(MinimalVertex())
    val shader = Resources.shader("arx/shaders/minimal")
    val textureBlock = TextureBlock(1024)


    override fun updateBuffers(AG: AsciiGraphics) {
        vao.reset()

        val backgroundTC = textureBlock.blankTexture()

        val font = font("arx/fonts/Px437_SanyoMBC775-2y.ttf", 16)

        val canvas = AG.rootCanvas
        val charDim = canvas.characterDimensions(font)

        val buf = canvas.buffer
        for (x in 0 until buf.dimensions.x) {
            for (y in 0 until buf.dimensions.y) {
                val c = buf.chars[buf.index(x, y)]

                val char = Char((c and AsciiBuffer.characterMask).toInt())
                val foregroundEncoded = ((c and AsciiBuffer.foregroundMask) shr AsciiBuffer.foregroundShift).toUInt()
                val backgroundEncoded = ((c and AsciiBuffer.backgroundMask) shr AsciiBuffer.backgroundShift).toUInt()
                val section = (c and AsciiBuffer.sectionMask) shr AsciiBuffer.sectionShift


                val backgroundColor = canvas.palette.decode(backgroundEncoded)
                val foregroundColor = canvas.palette.decode(foregroundEncoded)

                val img = CustomChars.customGlyphs[char]?.toImage() ?: font.glyphImageFor(char)

                val baseX = (x * charDim.x).toFloat()
                val baseY = (y * charDim.y).toFloat()
                for (q in 0 until 4) {
                    val v = vao.addV()
                    v.color = backgroundColor
                    v.texCoord = backgroundTC.texCoord(q)
                    v.vertex = Vec3f(baseX + charDim.x * UnitSquare2D[q].x, baseY + charDim.y * UnitSquare2D[q].y, 0.0f)
                }
                vao.addIQuad()

                val outV = Vec2f()
                val tc = textureBlock.getOrUpdate(img)
                for (q in 0 until 4) {
                    val v = vao.addV()
                    v.color = foregroundColor
                    tc.subRectTexCoord(sectionSubRects[section.toInt()], q, outV)
                    v.texCoord = outV
                    v.vertex = Vec3f(baseX + img.width * baseScale * UnitSquare2D[q].x, baseY + img.height * baseScale * UnitSquare2D[q].y, 0.0f)
                }
                vao.addIQuad()
            }
        }
    }

    override fun draw(world: World) {
        shader.bind()
        val fbs = Application.frameBufferSize
        val orthoMatrix = ortho(0.0f, fbs.x.toFloat() + 0.5f, 0.0f, fbs.y.toFloat() + 0.5f, 0.0f, 100.0f)
        shader.setUniform("Matrix", orthoMatrix)

        textureBlock.bind()
        vao.sync()
        vao.draw()
    }
}


object TestAsciiDrawing : DisplayComponent() {

    lateinit var canvas: AsciiCanvas

    override fun initialize(world: World) {
        val AG = world[AsciiGraphics]
        canvas = AG.createCanvas()
    }

    override fun update(world: World): Boolean {
        if (canvas.revision == 1) {
            val bigHeading = "This is a BIG Heading"
            val heading = "This is a HeadingypT"
            val text = "This is a longer period of smaller text, how much is that legible? Can this be read? This is kind of an interesting challenge is it not"

            val baseX = 160
            val baseY = 64

            for (i in 0 until heading.length) {
                var s = 1UL
                for (x in 0 until 2) {
                    for (y in 0 until 2) {
                        canvas.write(baseX + i * 2 + x, baseY + y, 0, heading[i], White, Black, s++)
                    }
                }
            }


            for (i in 0 until bigHeading.length) {
                var s = 1UL + 4UL
                for (x in 0 until 3) {
                    for (y in 0 until 3) {
                        canvas.write(baseX + i * 3 + x, baseY + 2 + y, 0, bigHeading[i], White, Black, s++)
                    }
                }
            }

            var y = baseY - 1
            var x = baseX
            for (i in 0 until text.length) {
                canvas.write(x, y, 0, text[i], White, Black, 0UL)
                x++
                if (x > baseX + 16) {
                    x = baseX
                    y -= 1
                }
            }

//            val img = image("/Users/sbock/Downloads/ai/art_deco_temples.png")
//            val img = image("/Users/sbock/Downloads/ai/fir_tree_1.png")
            x = 0
            val size = 40

//            x = renderImage(x, 5, image("/Users/sbock/Downloads/ai/prince_of_thorns_512.png"), Vec2i(size*3, size), canvas, 2)
//            x = renderImage(x, 5, image("/Users/sbock/Downloads/ai/prince_of_thorns_512.png"), Vec2i(size*3, size), canvas, 1)

//            x = renderImage(x, 5, image("/Users/sbock/Downloads/ai/Samson_a_steep_stone_staircase_winding_down_stepped_mountains_i_3dda5829-a43c-46d1-bc37-cc3f21843520.png"), Vec2i(size*3, size), canvas, 2)
//            x = renderImage(x, 5, image("/Users/sbock/Downloads/ai/Samson_a_steep_stone_staircase_winding_down_stepped_mountains_i_3dda5829-a43c-46d1-bc37-cc3f21843520.png"), Vec2i(size*3, size), canvas, 1)

            x = renderImage(x, 5, image("sundar/display/images/alley_2.png"), Vec2i(size * 3, size), canvas, font("arx/fonts/Px437_SanyoMBC775-2y.ttf", 16), 2)


            AsciiBox.drawBox(canvas, Vec3i(x + 4, 5, 0), Vec2i(20, 20), Ascii.BoxStyle.Line, 2, RGBA(255, 128, 64, 255), null, false)

//            x = renderImage(x, 5, image("/Users/sbock/Downloads/ai/prince_of_thorns.png"), Vec2i(size, size), canvas)
//            x = renderImage(x, 5, image("/Users/sbock/Downloads/ai/prince_of_thorns_512.png"), Vec2i(size, size), canvas)
//            x = renderImage(x, 5, image("/Users/sbock/Downloads/ai/prince_of_thorns_256.png"), Vec2i(size, size), canvas)

            Metrics.print("ascii")

            canvas.revision++

            return true
        } else {
            return false
        }
    }


    fun renderImage(x: Int, y: Int, img: Image, maxDims: Vec2i, canvas: AsciiCanvas, font: ArxFont, scale: Int): Int {
        Metrics.timer("img2ascii").timeStmt {
            val buf = AsciiImage.render(img, maxDims, font, canvas.palette)

//            val deflater = Deflater()
//
//            val chars = buf.buffer.chars
//            val bb = ByteBuffer.allocate(
//                chars.size * ULong.SIZE_BYTES +
//                buf.palette.size * 4 +
//                4
//            )
//            bb.putInt(buf.palette.size)
//            for (c in buf.palette.colors()) {
//                bb.put(c.r.toByte())
//                bb.put(c.g.toByte())
//                bb.put(c.b.toByte())
//                bb.put(c.a.toByte())
//            }
//            for (c in chars) {
//                bb.putLong(c.toLong())
//            }
//            bb.flip()
//
//            deflater.setInput(bb)
//            deflater.finish()
//
//            val output = ByteBuffer.allocate(bb.capacity())
//            val compressedLength = deflater.deflate(output)
//            deflater.end()
//
//            File("/tmp/image.aimg").outputStream().use {
//                DataOutputStream(it).use { dos ->
//                    dos.writeInt(bb.capacity())
//                    dos.write(output.array(), 0, compressedLength)
//                }
//            }
//
//            File("/tmp/image.aimg").inputStream().use {
//                DataInputStream(it).use { dis ->
//                    val uncompressedSize = dis.readInt()
//                    val decompressor = Inflater()
//                    val bytes = dis.readBytes()
//                    decompressor.setInput(bytes)
//                    val result = ByteArray(uncompressedSize)
//                    decompressor.inflate(result)
//                    decompressor.end()
//
//                    val raw = ByteBuffer.wrap(result)
//
//                    val paletteSize = raw.getInt()
//                    for (i in 0 until paletteSize) {
//                        val c = RGBA(raw.get().toUByte(), raw.get().toUByte(), raw.get().toUByte(), raw.get().toUByte())
//                    }
//
//                    for (i in 0 until chars.size) {
//                        val l = raw.getLong().toULong()
//                        chars[i] = l
//                    }
//                }
//            }
//
//            println("Palette size : ${buf.palette.size}, render out: ${buf.dimensions}")

            //        val basePalette = AsciiColorPalette.fromImage(image("arx/palettes/aurora-1x.png"))
            //
            //        val paletteMap = mutableMapOf<UInt, UInt>()
            //        for (i in 0 until buf.palette.size ) {
            //            val srcColor = buf.palette.rgba(i.toUInt())
            //
            //            var minError = 1000.0
            //            var bestMatch = White
            //            for (color in basePalette.colors()) {
            //                val error = RGBA.colorDistance(color, srcColor)
            //                if (error < minError) {
            //                    minError = error
            //                    bestMatch = color
            //                }
            //                if (minError == 0.0) {
            //                    break
            //                }
            //            }
            //
            //            paletteMap[i.toUInt()] = buf.palette.getOrUpdate(bestMatch)
            //        }
            //
            //        for (sx in 0 until dims.x) {
            //            for (sy in 0 until dims.y) {
            //                val g = buf.read(sx,sy)
            //
            //                buf.write(sx,sy,0, g.character, paletteMap[g.foregroundColorIndex]!!, paletteMap[g.backgroundColorIndex]!!, g.section)
            //            }
            //        }

            buf.blit(canvas, Vec3i(x, y, 0), scale)
            return x + buf.dimensions.x * scale
        }
    }




//    fun writeToFile(canvas: AsciiCanvas, file: File) {
//        val deflater = Deflater()
//
//        val buf = canvas
//
//        val headerByte : Byte = currentVersionWith16BitPalette
//
//        val chars = buf.buffer.chars
//        val bb = ByteBuffer.allocate(
//            1 + // header byte
//            4 + // width short
//            4 + // height short
//            chars.size * ULong.SIZE_BYTES
//        )
//        bb.put(headerByte)
//        bb.putInt(buf.dimensions.x)
//        bb.putInt(buf.dimensions.y)
//        for (c in chars) {
//            bb.putLong(c.toLong())
//        }
//        bb.flip()
//
//        deflater.setInput(bb)
//        deflater.finish()
//
//        val output = ByteBuffer.allocate(bb.capacity())
//        val compressedLength = deflater.deflate(output)
//        deflater.end()
//
//        file.outputStream().use {
//            DataOutputStream(it).use { dos ->
//                dos.writeInt(bb.capacity())
//                dos.write(output.array(), 0, compressedLength)
//            }
//        }
//    }
//
//    fun readFromFile(file: File, font: ArxFont) : AsciiCanvas {
//        File("/tmp/image.aimg").inputStream().use {
//            DataInputStream(it).use { dis ->
//                val uncompressedSize = dis.readInt()
//                val decompressor = Inflater()
//                val bytes = dis.readBytes()
//                decompressor.setInput(bytes)
//                val result = ByteArray(uncompressedSize)
//                decompressor.inflate(result)
//                decompressor.end()
//
//                val raw = ByteBuffer.wrap(result)
//
//                val headerByte = raw.get()
//                if (headerByte != currentVersionWith16BitPalette) {
//                    throw IllegalStateException("Unhandled canvas serialized version : ${headerByte}")
//                }
//                val width = raw.getInt()
//                val height = raw.getInt()
//
//                val ret = AsciiCanvas(Vec2i(width, height), AsciiColorPalette16Bit, font)
//                for (i in 0 until chars.size) {
//                    val l = raw.getLong().toULong()
//                    chars[i] = l
//                }
//            }
//        }
//    }
}

fun main() {
    Application(windowWidth = 1680, windowHeight = 1000)
        .apply {
            clearColor = RGBA(0, 0, 0, 255)
        }
        .run(
            Engine(
                mutableListOf(),
                mutableListOf(AsciiGraphicsComponent(), TestAsciiDrawing)
            )
        )
}