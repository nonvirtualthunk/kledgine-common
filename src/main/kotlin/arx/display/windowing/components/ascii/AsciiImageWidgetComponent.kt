package arx.display.windowing.components.ascii

import arx.core.*
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiDrawCommand
import arx.display.ascii.AsciiImage
import arx.display.core.*
import arx.display.windowing.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.EntityData
import com.typesafe.config.ConfigValue

data class AsciiImageWidget (
    var image : Bindable<Any?> = ValueBindable.Null(),
    var color : Bindable<RGBA?> = ValueBindable.Null(),
    var scale : Int? = null,
    var drawImageBorder: Boolean = false, // Note: image borders not currently in use
    var imageBorderColor: Bindable<RGBA?> = ValueBindable.Null(),
    var imageBorderScale: Int = 1
) : DisplayData {
    companion object : DataType<AsciiImageWidget>( AsciiImageWidget(),sparse = true ), FromConfigCreator<AsciiImageWidget> {
        override fun createFromConfig(cv: ConfigValue?): AsciiImageWidget? {
            val imgcv = cv["image"]
            return if (imgcv != null) {
                AsciiImageWidget(
                    image = bindableAnyOpt(imgcv),
                    color = bindableRGBAOpt(cv["color"]),
                    scale = cv["scale"].asInt(),
                    drawImageBorder = cv["drawImageBorder"].asBool() ?: false,
                    imageBorderColor = bindableRGBAOpt(cv["imageBorderColor"]),
                    imageBorderScale = cv["imageBorderScale"].asInt() ?: 1,
                )
            } else {
                null
            }
        }
    }
    override fun dataType() : DataType<*> { return AsciiImageWidget }

    fun copy() : AsciiImageWidget {
        return copy(image = image.copyBindable(), color = color.copyBindable(), imageBorderColor = imageBorderColor.copyBindable())
    }
}


object AsciiImageWidgetComponent : WindowingComponent {

    private data class Params (val image : Image, val maxSize: Vec2i)
    private val imageCache = LRUCache<Params, AsciiCanvas>(500, 0.5f)

    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(AsciiImageWidget)
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        w[AsciiImageWidget]?.let { iw ->
            if (iw.image.update(ctx)) {
                w.markForUpdate(RecalculationFlag.Contents)
                if (w.dimensions(Axis2D.X).isIntrinsic()) {
                    w.markForUpdate(RecalculationFlag.DimensionsX)
                }
                if (w.dimensions(Axis2D.Y).isIntrinsic()) {
                    w.markForUpdate(RecalculationFlag.DimensionsY)
                }
            }
            if (iw.color.update(ctx)) {
                w.markForUpdate(RecalculationFlag.Contents)
            }
        }
    }

    fun resolveImage(w: Widget, id: AsciiImageWidget) : Any? {
        return when(val raw = id.image()) {
            is String -> {
                val resolved = Resources.imageOpt(raw) ?: Resources.amgOpt(raw)
                if (resolved == null) {
                    Noto.warn("Could not resolve image to display in ${w.identifier}: $raw")
                    null
                } else {
                    resolved
                }
            }
            is AmgRef -> raw
            is ImageRef -> {
                if (raw.isSentinel()) {
                    null
                } else {
                    raw
                }
            }
            null -> null
            else -> {
                Noto.warn("Unknown binding type for image in ${w.identifier}: $raw")
                null
            }
        }
    }

    override fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        val id = w[AsciiImageWidget] ?: return null
        val rawImg = resolveImage(w, id) ?: return null

        val scale = w.windowingSystem.forceScale ?: id.scale ?: w.windowingSystem.scale

        when (rawImg) {
            is AmgRef -> {
                val amg = rawImg.toAmg()
                return amg.dimensions[axis] * scale
            }
            is ImageRef -> {
                val img = rawImg.toImage()
                val font = (w.windowingSystem as AsciiWindowingSystem).font

                val maxClientSize = Vec2i(maxSize.x - w.resolvedClientOffsetNear.x * 2, maxSize.y - w.resolvedClientOffsetNear.y * 2)
                val dims = AsciiImage.imageToAsciiDimensions(img, maxClientSize / scale, font)
                return dims[axis] * scale + tern(id.drawImageBorder, id.imageBorderScale * 2, 0)
            }
        }
        return null
    }

    override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
        val id = w[AsciiImageWidget] ?: return

        val rawImg = resolveImage(w, id) ?: return

        val scale = ws.forceScale ?: id.scale ?: ws.scale

        val imgCanvas = when (rawImg) {
            is AmgRef -> {
                rawImg.toAmg()
            }
            is ImageRef -> {
                val img = rawImg.toImage()
                val p = Params(img, Vec2i(w.resClientWidth / scale, w.resClientHeight / scale))
                AsciiImage.renderCached(p.image, p.maxSize, ws.font, canvas.palette)
            }
            else -> {
                Noto.errAndReturn("Invalid type for image widget", SentinelAmgRef.toAmg())
            }
        }
        commandsOut.add(AsciiDrawCommand.Blit(imgCanvas, Vec3i(w.resClientX, w.resClientY, w.resolvedPosition.z), scale, alpha = id.color()?.toFloat()?.a))
    }
}