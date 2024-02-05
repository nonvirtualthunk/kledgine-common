package arx.display.ascii

import arx.application.TuiApplication
import arx.core.*
import arx.display.ascii.Ascii.ShadingChars
import arx.display.ascii.Ascii.allChars
import arx.display.ascii.Ascii.blockCharSet
import arx.display.core.Image
import arx.display.core.mix
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor


object AsciiImage {

    @Serializable
    private data class RenderInfo(
        val glyphDim: Vec2i,
        val bits: Int,
        val longPatterns: Array<LongArray>,
        val longsPerPattern: Int,
        val errorBias: IntArray,
        val maxGlyphSize: Vec2i
    ) {
        val maxError : Int get() { return bits }
    }

    private data class RenderKey(val img: Image, val dims: Vec2i, val settings: RenderSettings)


    private val renderInfo = mutableMapOf<ArxFont, RenderInfo>()
    private val renderCache = LRUCache<RenderKey, AsciiCanvas>(500, 0.5f)

    private fun renderInfoFor(font: ArxFont) : RenderInfo {
        val inMemory = renderInfo[font]
        if (inMemory != null) {
            return inMemory
        }

        val identifier = font.typeface.baseAwtFont.name
        val cached = AppCache.load<RenderInfo>("asciiFontRenderInfo/$identifier")
        if (cached != null) {
            renderInfo[font] = cached
            TuiApplication.debugTextBuffer.add("Loaded from cache: $identifier")
            return cached
        }

        val computed = computeRenderInfo(font)
        AppCache.cache(computed, "asciiFontRenderInfo/$identifier")
        TuiApplication.debugTextBuffer.add("Computed and cached: $identifier")

        renderInfo[font] = computed

        return computed
    }

    fun imageToAsciiDimensions(img: Image, maxDimensions: Vec2i, font: ArxFont) : Vec2i {
        val cdim = renderInfoFor(font).maxGlyphSize
        val maxPWidth = (maxDimensions.x.min(5000)) * cdim.x
        val maxPHeight = (maxDimensions.y.min(5000)) * cdim.y

        val wr = maxPWidth / img.width.toFloat()
        val hr = maxPHeight / img.height.toFloat()

        val pixelDimensions = if (wr > hr) {
            Vec2i((maxPHeight * (img.width / img.height.toFloat())).toInt(), maxPHeight)
        } else {
            Vec2i(maxPWidth, (maxPWidth * (img.height / img.width.toFloat())).toInt())
        }

        return Vec2i(
            (pixelDimensions.x / cdim.x.toFloat()).ceil().toInt().min(maxDimensions.x),
            (pixelDimensions.y / cdim.y.toFloat()).ceil().toInt().min(maxDimensions.y))
    }

    private fun computeGlyphBitPatterns(font: ArxFont) : Array<BitSet> {
        val bitPatterns = Array(allChars.size) { BitSet(font.maxGlyphSize.x * font.maxGlyphSize.y) }
        for (i in 0 until allChars.size) {
            val bp = bitPatterns[i]
            val cimg = font.glyphImageFor(allChars[i])
            var b = 0
            for (x in 0 until cimg.width) {
                for (y in 0 until cimg.height) {
                    if (cimg[x,y].a > 0u) {
                        bp.set(b)
                    }
                    b++
                }
            }
        }
        return bitPatterns
    }

    private fun computeRenderInfo(font: ArxFont) : RenderInfo {
        val gdim = font.maxGlyphSize
        val bits = gdim.x * gdim.y
        val longCount = ceil(bits / 64.0f).toInt()

        val bitPatterns = computeGlyphBitPatterns(font)
        val longPatterns = bitPatterns.map { bitSetToLongArray(it, LongArray(longCount)) }

        val errorBias = bitPatterns.mapIndexed { i, bs ->
            val cardinality = bs.cardinality()
            val effCardinality = cardinality.min(bits - cardinality)
            val raw = if (effCardinality == 0) {
                bits / 5
            } else if (effCardinality < bits / 4) {
                (((bits / 4) - effCardinality) * 2) / 3
            } else {
                0
            }

            raw
        }

        return RenderInfo(
            glyphDim = gdim,
            bits = bits,
            longPatterns = longPatterns.toTypedArray(),
            errorBias = errorBias.toIntArray(),
            longsPerPattern = longCount,
            maxGlyphSize = font.maxGlyphSize
        )
    }

    private fun bitSetToLongArray(bitSet: BitSet, out : LongArray) : LongArray {
        val b = bitSet.toLongArray()
        var i = 0
        while (i < b.size) {
            out[i] = b[i]
            i++
        }
        while (i < out.size) {
            out[i] = 0L
            i++
        }
        return out
    }

//    val rdiv = 10
//    val gdiv = 6
    val rdiv = 8
    val gdiv = 8

    fun midSampleRGBA(raw : RGBA) : RGBA {
        return RGBA(
            ((raw.r.toInt() + rdiv / 2) / rdiv * rdiv),
            ((raw.g.toInt() + gdiv / 2) / gdiv * gdiv),
            ((raw.b.toInt() + rdiv / 2) / rdiv * rdiv),
            raw.a.toInt()
        )
    }

    fun lowSampleRGBA(raw : RGBA) : RGBA {
        return RGBA(
            ((raw.r.toInt()) / rdiv * rdiv),
            ((raw.g.toInt()) / gdiv * gdiv),
            ((raw.b.toInt()) / rdiv * rdiv),
            raw.a.toInt()
        )
    }

    fun highSampleRGBA(raw : RGBA) : RGBA {
        return RGBA(
            ((raw.r.toInt() + rdiv - 1) / rdiv * rdiv),
            ((raw.g.toInt() + gdiv - 1) / gdiv * gdiv),
            ((raw.b.toInt() + rdiv - 1) / rdiv * rdiv),
            raw.a.toInt()
        )
    }

    fun sampleImage(img: Image, fx: Float, fy: Float) : RGBA {
        val pfx = fx * img.width
        val pfy = fy * img.height

//        return img[pfx.roundToInt().clamp(0, img.width - 1), pfy.roundToInt().clamp(0, img.height - 1)]

        val s1 = img[pfx.toInt(), pfy.toInt()]
        val s2 = img[(pfx.toInt() + 1).min(img.width - 1), pfy.toInt()]

        val s3 = img[pfx.toInt(), (pfy.toInt() + 1).min(img.height - 1)]
        val s4 = img[(pfx.toInt() + 1).min(img.width - 1), (pfy.toInt() + 1).min(img.height - 1)]

        val b1 = mix(s1, s2, pfx - floor(pfx))
        val b2 = mix(s3, s4, pfx - floor(pfx))

        val raw = mix(b1, b2, pfy - floor(pfy))
//        return RGBA((raw.r / 8U * 8U).toUByte(), (raw.g / 8U * 8U).toUByte(), (raw.b / 8U * 8U).toUByte(), raw.a)
//        return RGBA(
//            ((raw.r.toInt() + 8) / 16 * 16).toUByte(),
//            ((raw.g.toInt() + 6) / 12 * 12).toUByte(),
//            ((raw.b.toInt() + 8) / 16 * 16).toUByte(),
//            raw.a
//        )
        return raw
//
//        return img.pixel((fx * img.width).toInt(), (fy * img.height).toInt())
    }

    data class TargetPattern(val bitPattern : BitSet, var primary: RGBA, var secondary: RGBA?, var shadingFract : Float? = null)

    fun computeTargetBitPattern(img: Image, cx: Int, cy: Int, tw: Int, th: Int, gdim: Vec2i, target: TargetPattern) {
        val tolerance = 64
        val colorIndexes = Array(gdim.x) { Array(gdim.y) { -1 } }

        target.bitPattern.clear()

        val colors = mutableListOf<Pair<RGBA, Int>>()

        for (gx in 0 until gdim.x) {
            val opx = cx * gdim.x + gx
            for (gy in 0 until gdim.y) {
                val opy = cy * gdim.y + gy

                val fxN = (opx.toFloat()) / tw
                val fyN = (opy.toFloat()) / th

                val fxF = ((opx + 1).toFloat() / tw).min(1.0f)
                val fyF = ((opy + 1).toFloat() / th).min(1.0f)

                val sample = Vec4i(0,0,0,0)
                var count = 0

                var ix = (fxN * img.dimensions.x).toInt().min(img.dimensions.x - 1)
                var iy = (fyN * img.dimensions.y).toInt().min(img.dimensions.y - 1)
                val fx = (fxF * img.dimensions.x).toInt().max(ix + 1)
                val fy = (fyF * img.dimensions.y).toInt().max(iy + 1)

                while (ix < fx) {
                    while (iy < fy) {
                        val p = img[ix, iy]
                        sample.r += p.r.toInt()
                        sample.g += p.g.toInt()
                        sample.b += p.b.toInt()
                        sample.a += p.a.toInt()
                        count++

                        iy++
                    }
                    ix++
                }

                val color = RGBA(sample.r / count, sample.g / count, sample.b / count, sample.a / count)


                var found = false
                for (i in 0 until colors.size) {
                    val cpair = colors[i]
                    if (color == cpair.first) {
                        colors[i] = color to cpair.second + 1
                        colorIndexes[gx][gy] = i
                        found = true
                        break
                    } else if (RGBA.colorDistance(color, cpair.first) < tolerance) {
                        colors[i] = mix(color, cpair.first, 0.5f) to (cpair.second + 1)
                        colorIndexes[gx][gy] = i
                        found = true
                        break
                    }
                }
                if (! found) {
                    colors.add(color to 1)
                    colorIndexes[gx][gy] = colors.size - 1
                }
            }
        }

        var primaryIndex = -1
        var secondaryIndex = -1

        if (colors.size == 1) {
            val mid = colors[0].first
            val low = lowSampleRGBA(colors[0].first)
            val high = highSampleRGBA(colors[0].first)

            // low * x + high * 1 - x = foo
            // 4 * x + 8 * (1 - x) = 6
            // 4 + (8 - 4) * x = 6
            // 4 * x = 2

            target.primary = low
            target.secondary = high


            val fr = (mid.r - low.r).toFloat() / (high.r - low.r).toFloat()
            val fg = (mid.g - low.g).toFloat() / (high.g - low.g).toFloat()
            val fb = (mid.b - low.b).toFloat() / (high.b - low.b).toFloat()

            target.shadingFract = (fr + fg + fb) / 3.0f
            return

//            target.primary = colors[0].first
//            target.secondary = null
//            return
        } else {
            target.shadingFract = null

            var primaryCount = 0
            var secondaryCount = 0

            for (i in 0 until colors.size) {
                val count = colors[i].second
                if (count > primaryCount) {
                    primaryCount = count
                    primaryIndex = i
                }
            }

            for (i in 0 until colors.size) {
                val count = colors[i].second
                if (i != primaryIndex && count > secondaryCount) {
                    secondaryCount = count
                    secondaryIndex = i
                }
            }
        }

        target.primary = midSampleRGBA( colors[primaryIndex].first )
        target.secondary = midSampleRGBA( colors[secondaryIndex].first )

        var bi = 0
        for (gx in 0 until gdim.x) {
            for (gy in 0 until gdim.y) {
                val colorIndex = colorIndexes[gx][gy]

                if (colorIndex == primaryIndex) {
                    target.bitPattern.set(bi)
                }
                bi++
            }
        }
    }

    fun computeMedianCutTargetPattern(img: Image, cx: Int, cy: Int, tw: Int, th: Int, gdim: Vec2i, target: TargetPattern) {
        val colorSamples = Array(gdim.x) { Array(gdim.y) { RGBA(255,255,255,255) } }

        target.bitPattern.clear()

        val colors = mutableMapOf<RGBA, Float>()

        for (gx in 0 until gdim.x) {
            val opx = cx * gdim.x + gx
            for (gy in 0 until gdim.y) {
                val opy = cy * gdim.y + gy

                val fxN = (opx.toFloat()) / tw
                val fyN = (opy.toFloat()) / th

                val fxF = ((opx + 1).toFloat() / tw).min(1.0f)
                val fyF = ((opy + 1).toFloat() / th).min(1.0f)

                val sample = Vec4i(0,0,0,0)
                var count = 0

                var ix = (fxN * img.dimensions.x).toInt().min(img.dimensions.x - 1)
                var iy = (fyN * img.dimensions.y).toInt().min(img.dimensions.y - 1)
                val fx = (fxF * img.dimensions.x).toInt().max(ix + 1)
                val fy = (fyF * img.dimensions.y).toInt().max(iy + 1)

                while (ix < fx) {
                    while (iy < fy) {
                        val p = img[ix, iy]
                        sample.r += RGBA5551.quantizeRGB(p.r).toInt()
                        sample.g += RGBA5551.quantizeRGB(p.g).toInt()
                        sample.b += RGBA5551.quantizeRGB(p.b).toInt()
                        sample.a += RGBA5551.quantizeA(p.a).toInt()
                        count++

                        iy++
                    }
                    ix++
                }

                val color = RGBA(sample.r / count, sample.g / count, sample.b / count, sample.a / count)
                color.setFrom16BitColor(color.to16BitColor())

                colors.compute(color) { _,existing -> (existing ?: 0.0f) + 1.0f }
                colorSamples[gx][gy] = color
            }
        }

        val palette = if (colors.size == 1) {
            listOf(colors.keys.first())
        } else {
            medianCutPalette(MedianCutParams(colors, 2, oneBitAlpha = false)).colors
        }
//        for (c in palette) {
//            if (c.a < 255u) {
//                c.a = 0u
//            }
//        }

        if (palette.size == 1) {
            val mid = palette[0]
            val low = lowSampleRGBA(palette[0])
            val high = highSampleRGBA(palette[0])

            target.primary = low
            target.secondary = high

            val fr = (mid.r - low.r).toFloat() / (high.r - low.r).toFloat()
            val fg = (mid.g - low.g).toFloat() / (high.g - low.g).toFloat()
            val fb = (mid.b - low.b).toFloat() / (high.b - low.b).toFloat()

            target.shadingFract = (fr + fg + fb) / 3.0f
            return
        } else {
            target.shadingFract = null

            target.primary = palette[0]
            target.secondary = palette[1]
        }

        var bi = 0
        for (gx in 0 until gdim.x) {
            for (gy in 0 until gdim.y) {
                val c = colorSamples[gx][gy]
                val secondary = target.secondary
                if (secondary == null || RGBA.colorDistance(c, target.primary) < RGBA.colorDistance(c, secondary)) {
                    target.bitPattern.set(bi)
                }
                bi++
            }
        }
    }



//    data class PreprocessorPipeline(
//        val srcImgStage : SourceImage = SourceImage(),
//        val downscaleStage : LinearDownscale = LinearDownscale().apply {
//            targetSizeParam.value = 1024
//        },
//        val paletteStage : ReducePalette = ReducePalette().apply {
//            medianCutParam.value = true
//            paletteSizeParam.value = 64
//        },
//        val saturatePaletteStage : AdjustPaletteSaturation = AdjustPaletteSaturation().apply {
//            saturationAdjustmentParam.value = 1.5f
//        },
//        val reduceStage : ReduceImage = ReduceImage().apply {
//            ditherFactionParam.value = 0.4f
//            scaleFactorParam.value = 4
//        },
//    ) {
//        fun process(path: String) : Image {
//            val p = Pipeline().apply {
//                addStage(srcImgStage)
//                srcImgStage.pathParam.value = File(path)
//
//                addStage(downscaleStage)
//                setInputMapping(srcImgStage, srcImgStage.imageKey, downscaleStage, downscaleStage.imageInKey)
//
//                addStage(paletteStage)
//                setInputMapping(downscaleStage, downscaleStage.imageOutKey, paletteStage, paletteStage.imageKey)
//
//                addStage(saturatePaletteStage)
//                setInputMapping(paletteStage, paletteStage.paletteKey, saturatePaletteStage, saturatePaletteStage.paletteInKey)
//
//                addStage(reduceStage)
//                setInputMapping(saturatePaletteStage, saturatePaletteStage.paletteOutKey, reduceStage, reduceStage.paletteKey)
//                setInputMapping(downscaleStage, downscaleStage.imageOutKey, reduceStage, reduceStage.imageInKey)
//            }
//
//            p.update()
//            return p.artifacts[Pipeline.SlotIdentifier(reduceStage, reduceStage.imageOutKey)]!!.image
//        }
//    }
//
//    val pipeline = PreprocessorPipeline()


    fun preprocess(inputImage: Image, tw: Int, th: Int) : Image {
        val ratio = inputImage.width.toFloat() / tw.toFloat()
        val scaledImg = if (ratio > 1.0f) {
//            val scaled = Image.ofSize(tw, th)
//
//            DownscaleAlgorithm.Bilinear.downscale(inputImage, scaled)
//
//            scaled
            inputImage
        } else {
            inputImage
        }

        val colorCounts = mutableMapOf<UShort, Float>()
        scaledImg.forEachPixel{ _,_,c -> colorCounts.compute(c.to16BitColor()) { _, p -> (p ?: 0.0f) + 1.0f } }

        // Just median cut palette
//        val newPalette = medianCutPalette(MedianCutParams(colorCounts.mapKeys { e -> RGBA.from16BitColor(e.key) }, 32))
//
//        val img = Image.ofSize(scaledImg.dimensions).withPixels { x,y,r ->
//            r.setFrom(newPalette.colorClosestTo(scaledImg[x,y]))
//        }


        // Full pipeline
//        val img = if (inputImage.path != null) {
//            pipeline.process(inputImage.path!!)
//        } else {
//            inputImage
//        }

        return scaledImg
    }

    fun renderToCanvas(inputImage: Image, position: Vec3i, dimensions: Vec2i, canvas: AsciiCanvas, font: ArxFont, renderSettings : RenderSettings) {

        val renderInfo = renderInfoFor(font)
        val gdim = renderInfo.glyphDim

        val longs = LongArray(renderInfo.longsPerPattern)

        val tw = dimensions.x * gdim.x
        val th = dimensions.y * gdim.y

        val img = preprocess(inputImage, tw, th)


        val debugImage = Image.ofSize(tw, th)

        val targetPattern = TargetPattern(BitSet(renderInfo.bits), White, null)

        for (cx in 0 until dimensions.x) {
            for (cy in 0 until dimensions.y) {

//                computeTargetBitPattern(img, cx, cy, tw, th, gdim, targetPattern)
                computeMedianCutTargetPattern(img, cx, cy, tw, th, gdim, targetPattern)

                var bi = 0
                for (gx in 0 until gdim.x) {
                    for (gy in 0 until gdim.y) {
                        val color = if (targetPattern.bitPattern.get(bi)) {
                            targetPattern.primary
                        } else {
                            targetPattern.secondary ?: targetPattern.primary
                        }

                        debugImage[cx * gdim.x + gx, cy * gdim.y + gy] = color

                        bi ++
                    }
                }


                val secondary = targetPattern.secondary
                val shadingFract = targetPattern.shadingFract
                if (shadingFract != null) {
                    val char = if (shadingFract < 0.33) {
                        ShadingChars[0]
                    } else if (shadingFract < 0.66) {
                        ShadingChars[1]
                    } else if (shadingFract < 0.9) {
                        ShadingChars[2]
                    } else {
                        ShadingChars[3]
                    }

                    canvas.write(cx + position.x, cy + position.y, position.z, char, targetPattern.primary, secondary)
                } else if (secondary == null) {
                    canvas.write(cx + position.x, cy + position.y, position.z, '█', targetPattern.primary, null)
                } else {
                    val targetLongPattern = bitSetToLongArray(targetPattern.bitPattern, longs)

                    var minError = renderInfo.maxError + 1
                    var invert = false
                    var bestChar = '|'
                    for (i in 0 until allChars.size) {
                        val c = allChars[i]
                        if (renderSettings.blocksOnly && ! blockCharSet.contains(c)) {
                            continue
                        }

//                        if (c == '█' || c == ' ') {
//                            continue
//                        }
                        if (c == ' ') {
                            continue
                        }
                        val charLongPattern = renderInfo.longPatterns[i]

                        var error = 0
                        for (bl in 0 until targetLongPattern.size) {
                            error += java.lang.Long.bitCount(targetLongPattern[bl] xor charLongPattern[bl])
                        }

                        val bias = (renderInfo.errorBias[i] * renderSettings.errorBias).toInt()

                        if (error + bias < minError) {
                            minError = error + bias
                            invert = false
                            bestChar = c
//                        }
                        } else if (renderInfo.maxError - error + bias < minError) {
                            minError = renderInfo.maxError - error + bias
                            invert = true
                            bestChar = c
                        }
                    }

                    // This didn't end up working particularly well, we don't have a great way of detecting gradient situations at present
//                    if (bestChar == Ascii.FullBlockChar) {
//                        val ratio = targetPattern.bitPattern.cardinality() / (gdim.x * gdim.y).toFloat()
//                        if (ratio > 0.9f) {
//                            bestChar = Ascii.FullBlockChar
//                        } else if (ratio > 0.75f) {
//                            bestChar = ShadingChars[2]
//                        } else if (ratio > 0.5f) {
//                            bestChar = ShadingChars[1]
//                        } else {
//                            bestChar = ShadingChars[0]
//                        }
//                    }

                    val fg = tern(invert, secondary, targetPattern.primary)
                    val bg = tern(invert, targetPattern.primary, secondary)
                    canvas.write(cx + position.x, cy + position.y, position.z, bestChar, fg, bg)
                }
            }
        }

        debugImage.writeToFile("/tmp/debug.png")
    }


    fun render(image: Image, maxDimensions: Vec2i, font: ArxFont, palette: AsciiColorPalette, renderSettings: RenderSettings = DefaultRenderSettings) : AsciiCanvas {
        val dims = imageToAsciiDimensions(image, maxDimensions, font)

        val out = AsciiCanvas(dims, palette)
        renderToCanvas(image, Vec3i(0,0,0), dims, out, font, renderSettings)
        return out
    }

    fun renderCached(image: Image, maxDimensions: Vec2i, font: ArxFont, palette: AsciiColorPalette, renderSettings: RenderSettings = DefaultRenderSettings) : AsciiCanvas {
        val dims = imageToAsciiDimensions(image, maxDimensions, font)

        val key = RenderKey(image, dims, renderSettings)
        val out = renderCache.getOrPut(key) {
            val out = AsciiCanvas(dims, palette)
            renderToCanvas(image, Vec3i(0,0,0), dims, out, font, renderSettings)
            out
        }

        return if (out.revision > 1) {
            Noto.warn("canvas created by render has been used and modified ($image)")
            renderCache.remove(key)
            renderCached(image, maxDimensions, font, palette, renderSettings)
        } else {
            out
        }
    }

    data class RenderSettings(val errorBias: Float = 0.45f, val blocksOnly: Boolean = false)
    val DefaultRenderSettings = RenderSettings()
}