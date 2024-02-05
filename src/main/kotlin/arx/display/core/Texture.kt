package arx.display.core

import arx.core.*
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL21.*
import kotlin.math.max
import kotlin.math.min


data class AtlasData(
    val location: Vec2i,
    val dimensions: Vec2i,
    val fullDimensions: Vec2i,
    val texPosition: Vec2f,
    val texDimensions: Vec2f,
    var revision: Int
) {
    val t0: Vec2f = location.toFloat() / fullDimensions.toFloat()
    val t1: Vec2f = Vec2i(location.x + dimensions.x, location.y).toFloat() / fullDimensions.toFloat()
    val t2: Vec2f = Vec2i(location.x + dimensions.x, location.y + dimensions.y).toFloat() / fullDimensions.toFloat()
    val t3: Vec2f = Vec2i(location.x, location.y + dimensions.y).toFloat() / fullDimensions.toFloat()


    fun texCoord(q: Int) : Vec2f {
        return when (q) {
            0 -> t0
            1 -> t1
            2 -> t2
            3 -> t3
            else -> error("Tex coords can only be indexed in [0,4)")
        }
    }

    fun subRectTexCoord(subRect : Rectf, q: Int, out : Vec2f) : Vec2f {
        when (q) {
            0 -> {
                out.x = t0.x + subRect.x * texDimensions.x
                out.y = t0.y + subRect.y * texDimensions.y
            }
            1 -> {
                out.x = t0.x + subRect.x * texDimensions.x + subRect.width * texDimensions.x
                out.y = t0.y + subRect.y * texDimensions.y
            }
            2 -> {
                out.x = t0.x + subRect.x * texDimensions.x + subRect.width * texDimensions.x
                out.y = t0.y + subRect.y * texDimensions.y + subRect.height * texDimensions.y
            }
            3 -> {
                out.x = t0.x + subRect.x * texDimensions.x
                out.y = t0.y + subRect.y * texDimensions.y + subRect.height * texDimensions.y
            }
            else -> error("Tex coords can only be indexed in [0,4)")
        }
        return out
    }

    operator fun get(q: Int) : Vec2f =
        when(q) {
            0 -> t0
            1 -> t1
            2 -> t2
            3 -> t3
            else -> error("Tex coords can only be indexed in [0,4)")
        }
}

class TextureBlock(size: Int, val borderWidth: Int = 1, srgbFormat : Boolean = false) : Texture(Image.ofSize(size,size), srgbFormat) {
    val dimensions = Vec2i(size, size)
    val data = image

    var openRects = mutableListOf(Recti(0,0,size,size))
//    var atlasData = mutableListOf<AtlasData>()
    var atlasDataByImage = mutableMapOf<Image, AtlasData>()
    val blankTexData : AtlasData
    internal var mayNeedDefragmenting = false

    init {
        val img = Image.ofSize(1,1).withPixels { _, _, v -> v(255u,255u,255u,255u) }
        getOrUpdate(img)
        blankTexData = atlasDataByImage[img]!!
    }

    fun toAtlasData(r: Recti, revision: Int) : AtlasData {
        return AtlasData(
            r.position,
            r.dimensions,
            dimensions,
            r.position.toFloat() / dimensions.toFloat(),
            r.dimensions.toFloat() / dimensions.toFloat(),
            revision
        )
    }

    internal fun addOpenRect(rect: Recti) {
        if (rect.width > 0 && rect.height > 0) {
            openRects.add(rect)
        }
    }

    fun getOrUpdate(img: Image) : AtlasData {
        val existingAtlasData = atlasDataByImage[img]
        if (existingAtlasData != null) {
            return if (existingAtlasData.revision >= img.revision) {
                existingAtlasData
            } else {
                data.copyFrom(img, existingAtlasData.location)
                data.revision++
                existingAtlasData
            }
        }

        val requiredSize = img.dimensions + borderWidth * 2
        var minDim = max(dimensions.x+1,dimensions.y+1)
        var chosenIndex = -1
        for (i in openRects.indices) {
            val r = openRects[i]
            if (r.width >= requiredSize.x && r.height >= requiredSize.y) {
                val potentialMin = min(r.width, r.height)
                if (potentialMin < minDim) {
                    chosenIndex = i
                    minDim = potentialMin
                }
            }
        }

        if (chosenIndex != -1) {
            val chosenRect = openRects[chosenIndex]
            openRects.swapAndPop(chosenIndex)

            if (chosenRect.width - img.width < chosenRect.height - img.height) {
                addOpenRect(Recti(chosenRect.position + Vec2i(0, requiredSize.y),
                                    chosenRect.dimensions - Vec2i(0, requiredSize.y)))
                addOpenRect(Recti(chosenRect.position + Vec2i(requiredSize.x, 0),
                                    Vec2i(chosenRect.width - requiredSize.x, requiredSize.y)))
            } else {
                addOpenRect(Recti(chosenRect.position + Vec2i(requiredSize.x, 0),
                                    chosenRect.dimensions - Vec2i(requiredSize.x, 0)))
                addOpenRect(Recti(chosenRect.position + Vec2i(0, requiredSize.y),
                                    Vec2i(requiredSize.x, chosenRect.height - requiredSize.y)))
            }

            data.copyFrom(img, chosenRect.position + borderWidth)
            data.revision++

            val ad = toAtlasData(Recti(chosenRect.position + borderWidth, img.dimensions), img.revision)
//            atlasData.add(ad)
            atlasDataByImage[img] = ad
            return ad
        } else {
            val toRemove = atlasDataByImage.keys.filter { it.destroyed }
            for (destroyedImage in toRemove) {
                atlasDataByImage.remove(destroyedImage)?.let { data ->
                    val reclaimedRect = Recti(data.location - borderWidth, data.dimensions + borderWidth * 2)
                    println("Reclaiming $reclaimedRect")
                    openRects.add(reclaimedRect)
                }
            }
            // if we theoretically freed up space then try again
            return if (toRemove.isNotEmpty()) {
                // if we've freed up space this way we have also potentially fragmented our blocks, since we
                // don't consolidate rects when we free. We could do this incrementally that way rather than
                // doing periodic defragmentations, but this is simpler for now
                mayNeedDefragmenting = true
                getOrUpdate(img)
            } else if (mayNeedDefragmenting) {
                // if fragmentation is a possibility then attempt to join as many of the open rects as possible
                mayNeedDefragmenting = false

                // join any rects that have the same x dimensions and are adjacent
                var sortedByX = openRects.sortedBy { it.x * 100000 + it.y }
                var i = 0
                while (i < sortedByX.size) {
                    val srcRect = sortedByX[i]
                    var joined = false
                    for (j in i + 1 until sortedByX.size) {
                        val targetRect = sortedByX[j]
                        if (srcRect.x != targetRect.x) {
                            break
                        }
                        if (targetRect.y == srcRect.y + srcRect.height && targetRect.width == srcRect.width) {
                            println("Joining $srcRect and $targetRect")
                            sortedByX = sortedByX - targetRect
                            srcRect.height += targetRect.height
                            joined = true
                            break
                        }
                    }
                    if (!joined) {
                        i += 1
                    }
                }

                openRects = sortedByX.toMutableList()


                // join any rects that have the same y dimensions and are adjacent
                var sortedByY = openRects.sortedBy { it.y * 100000 + it.x }
                i = 0
                while (i < sortedByY.size) {
                    val srcRect = sortedByY[i]
                    var joined = false
                    for (j in i + 1 until sortedByY.size) {
                        val targetRect = sortedByY[j]
                        if (srcRect.y != targetRect.y) {
                            break
                        }
                        if (targetRect.x == srcRect.x + srcRect.width && targetRect.height == srcRect.height) {
                            println("Joining $srcRect and $targetRect")
                            sortedByY = sortedByY - targetRect
                            srcRect.width += targetRect.width
                            joined = true
                            break
                        }
                    }
                    if (!joined) {
                        i += 1
                    }
                }

                openRects = sortedByY.toMutableList()


                return getOrUpdate(img)
            } else {
                Noto.err("Could not find space in texture block for image $img")
                blankTexture()
            }
        }
    }

    fun blankTexture(): AtlasData = blankTexData
}


open class Texture(val image: Image, srgbFormat: Boolean = false) {
    val internalFormat = if(srgbFormat) { GL_SRGB_ALPHA } else { GL_RGBA }
    val dataFormat = GL_RGBA

    var name: Int = 0
    var magFilter = GL_NEAREST
    var minFilter = GL_NEAREST

    private var syncedRevision = 0

    fun sync() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minFilter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, magFilter)
        if (syncedRevision == 0) {
//            println("Syncing texture initially (and writing debug texture to disk)")
//            image.writeToFile("/tmp/texture_block.png")
            GL11.glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, image.width, image.height, 0, dataFormat, GL_UNSIGNED_BYTE, image.data)
        } else {
            GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, image.width, image.height, dataFormat, GL_UNSIGNED_BYTE, image.data)
        }
        syncedRevision = image.revision
    }

    fun bind(textureIndex: Int = 0, autoSync : Boolean = true) {
        if (name == 0) {
            name = glGenTextures()
        }
        GL.activeTexture(textureIndex)
        GL.bindTexture(name)

        if (autoSync && syncedRevision < image.revision) {
            sync()
        }
    }
}
