package arx.display.core

import arx.core.*
import arx.display.components.CameraID
import arx.display.components.Cameras
import arx.display.components.MainCamera
import arx.display.components.get
import arx.engine.World
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.cos
import kotlin.math.sin

class Canvas3D(var cameraId: CameraID<*> = MainCamera) {

    val vao = VAO(MinimalVertex())
    var tb = TextureBlock(2048)
    var shader = Resources.shader("arx/shaders/minimal")
    val quadBuilder = QuadBuilder(this)
    val textBuilder = TextBuilder(this)

    internal var centered: Boolean = true

    fun clear() {
        vao.reset()
    }

    fun resetPreferences() {
        centered = true
    }

    fun centerQuads(b : Boolean) {
        centered = b
    }

    fun draw(world: World) {
        shader.bind()
        tb.bind()

        val matrix = world[Cameras][cameraId].run { projectionMatrix() * modelviewMatrix() }
        shader.setUniform("Matrix", matrix)

        vao.sync()
        vao.draw()
    }

    @OptIn(ExperimentalContracts::class)
    inline fun quad(block: QuadBuilder.() -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        quadBuilder.reset()

        quadBuilder.block()

        quadBuilder.draw()
    }

    @OptIn(ExperimentalContracts::class)
    inline fun text(block: TextBuilder.() -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }

        textBuilder.reset()

        textBuilder.block()

        textBuilder.draw()
    }

}

class TextBuilder(val c: Canvas3D) {
    val position: Vec3f = Vec3f(0.0f, 0.0f, 0.0f)
    internal var font : ArxFont = TextLayout.DefaultFont
    val color: RGBA = RGBA(0,0,0,255)
    internal var text: RichText = RichText()
    internal var region : Rectf = Rectf(0.0f,0.0f,100000.0f,100000.0f)
    internal var alignment : HorizontalTextAlignment = HorizontalTextAlignment.Left
    internal var pixelsPerUnit : Float = 1.0f

    fun font(f: ArxFont) {
        font = f
    }
    fun font(t: ArxTypeface, fontSize: Int) {
        font = t.withSize(fontSize)
    }
    fun font(name: String, fontSize: Int) {
        font = Resources.font(name, fontSize)
    }


    fun position(v: Vec3f) {
        position(v.x, v.y, v.z)
    }

    fun position(v: Vec2f) {
        position(v.x, v.y, 0.0f)
    }

    fun region(r: Rectf) {
        region = r
    }
    fun maxWidth(w: Float) {
        region.width = w
    }
    fun alignment(a : HorizontalTextAlignment) {
        alignment = a
    }

    fun reset() {
        font = TextLayout.DefaultFont
        position(0.0f,0.0f,0.0f)
        color(0,0,0,255)
        pixelsPerUnit = 1.0f
        alignment = HorizontalTextAlignment.Left
        region.x = 0f
        region.y = 0f
        region.width = 1000000f
        region.height = 1000000f
        text = RichText()
    }

    fun text(rt: RichText) {
        text = rt
    }
    fun text(str: String, color: RGBA? = null) {
        text = RichText(str, color)
    }

    fun World.setPixelsPerUnit() {
        pixelsPerUnit = this[Cameras][c.cameraId].pixelsPerUnit()
    }

    fun draw() {
        val finiteWidth = region.width < 1000.0f

        val layout = TextLayout.layout(TextLayout.Params(
            text,
            Vec2i(0,0),
            Recti((region.x * pixelsPerUnit).toInt(), (region.y * pixelsPerUnit).toInt(), (region.width * pixelsPerUnit).toInt(), (region.height * pixelsPerUnit).toInt()),
            font,
            color,
            if (finiteWidth) { alignment } else { Noto.warn("Cannot use non-left alignment without a finite width"); HorizontalTextAlignment.Left }
        ))

        val offsetX = if (finiteWidth && alignment == HorizontalTextAlignment.Centered) {
            region.width * -0.5f
        } else if (finiteWidth && alignment == HorizontalTextAlignment.Right){
            Noto.warn("right alignment not yet fully implemented")
            0.0f
        } else {
            0.0f
        }
        val lh = layout.lineHeight / pixelsPerUnit
        for (q in layout.quads) {
            val pos = (q.position / pixelsPerUnit)
            c.quad {
                position(this@TextBuilder.position.x + pos.x + offsetX, this@TextBuilder.position.y - pos.y - q.dimensions.y / pixelsPerUnit, position.z)
                dimensions(q.dimensions / pixelsPerUnit)
                q.color?.let { color(it) }
                texture(q.image)
                centered(false)
            }
        }
    }
}

class QuadBuilder(val c: Canvas3D) {
    val position: Vec3f = Vec3f(0.0f, 0.0f, 0.0f)
    internal var vertices : Array<Vec3f>? = null
    val dimensions: Vec2f = Vec2f(1.0f, 1.0f)
    var rotation: Float = 0.0f
    internal var textureV: ImageRef? = null
    internal var textureSubRectV: Rectf? = null
    internal val texCoordOut: Vec2f = Vec2f(0.0f, 0.0f)
    val colors: Array<RGBA> = Array(4) { RGBA(255, 255, 255, 255) }
    internal var centered : Boolean? = null

    fun centered(b: Boolean) {
        centered = b
    }

    fun texture(t: ImageRef) {
        textureV = t
    }
    fun texture(t: String) {
        textureV = ImagePath(t)
    }

    fun textureSubRect(r: Rectf) {
        textureSubRectV = r
    }

    fun position(v: Vec2f) {
        position.x = v.x
        position.y = v.y
    }

    fun position(v: Vec3f) {
        position.x = v.x
        position.y = v.y
        position.z = v.z
    }

    fun dimensions(v: Vec2f) {
        dimensions.x = v.x
        dimensions.y = v.y
    }

    fun color(v: RGBA) {
        for (i in 0 until 4) {
            colors[i](v.r, v.g, v.b, v.a)
        }
    }

    fun rotation(f: Float) {
        rotation = f
    }

    fun color(r: Int, g: Int, b: Int, a: Int) {
        for (i in 0 until 4) {
            colors[i](r,g,b,a)
        }
    }

    fun color(r: Float, g: Float, b: Float, a: Float) {
        color((r * 255).toInt().clamp(0, 255), (g * 255).toInt().clamp(0, 255), (b * 255).toInt().clamp(0, 255), (a * 255).toInt().clamp(0, 255))
    }

    fun vertices(v: Array<Vec3f>) {
        vertices = v
    }

    fun reset() {
        position(0.0f, 0.0f, 0.0f)
        dimensions(1.0f, 1.0f)
        rotation = 0.0f
        textureV = null
        textureSubRectV = null
        vertices = null
        centered = null
        for (i in 0 until 4) {
            colors[i](255, 255, 255, 255)
        }
    }

    @Suppress("UnnecessaryVariable")
    fun draw() {
        val atlasData = textureV?.let { c.tb.getOrUpdate(it.toImage()) }

        if (vertices != null) {
            for (q in 0 until 4) {
                val v = c.vao.addV()
                v.color = colors[q]

                if (atlasData == null) {
                    v.texCoord = c.tb.blankTexData.texCoord(q)
                } else if (textureSubRectV != null) {
                    v.texCoord = atlasData.subRectTexCoord(textureSubRectV!!, q, texCoordOut)
                } else {
                    v.texCoord = atlasData.texCoord(q)
                }

                v.vertex = vertices!![q]
            }
        } else {
            val forwardX = cos(rotation)
            val forwardY = sin(rotation)

            val orthoX = -forwardY
            val orthoY = forwardX

            val halfForwardDim = dimensions.x * 0.5f
            val halfOrthoDim = dimensions.y * 0.5f

            for (q in 0 until 4) {
                val v = c.vao.addV()
                v.color = colors[q]

                if (atlasData == null) {
                    v.texCoord = c.tb.blankTexData.texCoord(q)
                } else if (textureSubRectV != null) {
                    v.texCoord = atlasData.subRectTexCoord(textureSubRectV!!, q, texCoordOut)
                } else {
                    v.texCoord = atlasData.texCoord(q)
                }

                if (! (centered ?: c.centered)) {
                    v.vertex = when (q) {
                        0 -> position
                        1 -> position + Vec3f(forwardX * dimensions.x, forwardY * dimensions.y, 0.0f)
                        2 -> position + Vec3f(forwardX * dimensions.x + orthoX * dimensions.y, forwardY * dimensions.x + orthoY * dimensions.y, 0.0f)
                        3 -> position + Vec3f(orthoX * dimensions.y, orthoY * dimensions.y, 0.0f)
                        else -> throw IllegalStateException()
                    }
                } else {
                    v.vertex = when (q) {
                        0 -> position + Vec3f(forwardX * -halfForwardDim + orthoX * -halfOrthoDim, forwardY * -halfForwardDim + orthoY * -halfOrthoDim, 0.0f)
                        1 -> position + Vec3f(forwardX * halfForwardDim + orthoX * -halfOrthoDim, forwardY * halfForwardDim + orthoY * -halfOrthoDim, 0.0f)
                        2 -> position + Vec3f(forwardX * halfForwardDim + orthoX * halfOrthoDim, forwardY * halfForwardDim + orthoY * halfOrthoDim, 0.0f)
                        3 -> position + Vec3f(forwardX * -halfForwardDim + orthoX * halfOrthoDim, forwardY * -halfForwardDim + orthoY * halfOrthoDim, 0.0f)
                        else -> throw IllegalStateException()
                    }
                }
            }
        }


        c.vao.addIQuad()
    }
}