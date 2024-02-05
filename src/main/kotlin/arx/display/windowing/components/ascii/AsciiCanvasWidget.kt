package arx.display.windowing.components.ascii

import arx.core.*
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiColorPalette16Bit
import arx.display.ascii.AsciiDrawCommand
import arx.display.core.KeyModifiers
import arx.display.core.MouseButton
import arx.display.windowing.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.DisplayEvent
import arx.engine.EntityData
import com.typesafe.config.ConfigValue

val EmptyCanvas = AsciiCanvas(Vec2i(1,1), AsciiColorPalette16Bit).apply {
    name = "empty"
}

data class AsciiCanvasWidgetData (
    var canvas: Bindable<Any> = bindable(AsciiCanvas(Vec2i(1,1), AsciiColorPalette16Bit)),
    var scale: Bindable<Int> = ValueBindable.One,
    val horizontalAlignment : HorizontalAlignment = HorizontalAlignment.Centered,
    val verticalAlignment : VerticalAlignment = VerticalAlignment.Centered,
    var renderOffset: Bindable<Vec2i> = bindable(Vec2i(0,0))
) : DisplayData {

    companion object : DataType<AsciiCanvasWidgetData>( AsciiCanvasWidgetData(), sparse = true ), FromConfigCreator<AsciiCanvasWidgetData> {
        override fun createFromConfig(cv: ConfigValue?): AsciiCanvasWidgetData? {
            val type = cv["type"].asStr()?.lowercase()
            return if (type == "asciicanvas" || type == "canvas") {
                val canvas = bindableAny(cv["canvas"], AsciiCanvas(Vec2i(1,1), AsciiColorPalette16Bit))
                val horizontalAlignment = HorizontalAlignment.createFromConfig(cv["horizontalAlignment"]) ?: HorizontalAlignment.Centered
                val verticalAlignment = VerticalAlignment.createFromConfig(cv["verticalAlignment"]) ?: VerticalAlignment.Centered
                val renderOffset = bindableT(cv["renderOffset"], Vec2i(0,0))
                val scale = cv["scale"]?.let { bindableInt(it) } ?: ValueBindable.One

                AsciiCanvasWidgetData(canvas, scale, horizontalAlignment, verticalAlignment, renderOffset)
            } else {
                null
            }
        }
    }

    override fun dataType() : DataType<*> { return AsciiCanvasWidgetData }

    fun copy() : AsciiCanvasWidgetData {
        return copy(canvas = canvas.copyBindable(), renderOffset = renderOffset.copyBindable(), scale = scale.copyBindable())
    }
}

data class AsciiCanvasMousePressEvent(val position: Vec2i, val button: MouseButton, val mods: KeyModifiers, val srcEvent: DisplayEvent?) : WidgetEvent(srcEvent)
data class AsciiCanvasMouseReleaseEvent(val position: Vec2i, val button: MouseButton, val mods: KeyModifiers, val srcEvent: DisplayEvent?) : WidgetEvent(srcEvent)
data class AsciiCanvasMouseDragEvent(val position: Vec2i, val button: MouseButton, val mods: KeyModifiers, val srcEvent: DisplayEvent?) : WidgetEvent(srcEvent)

object AsciiCanvasWidgetComponent : WindowingComponent {

    fun resolveCanvas(cwd: AsciiCanvasWidgetData) : AsciiCanvas {
        return when (val c = cwd.canvas()) {
            is String -> {
                if (c == "" || c == "empty") {
                    EmptyCanvas
                } else {
                    Resources.amg(c)
                }
            }
            is AsciiCanvas -> c
            else -> {
                Noto.err("Invalid binding for ascii canvas widget: $c")
                EmptyCanvas
            }
        }
    }

    fun absoluteCanvasPosition(w: Widget, cwd: AsciiCanvasWidgetData) : Vec3i {
        val rc = resolveCanvas(cwd)
        val scale = cwd.scale()

        val hOffset = when(cwd.horizontalAlignment) {
            HorizontalAlignment.Left -> 0
            HorizontalAlignment.Centered -> (w.resolvedDimensions.x - rc.dimensions.x * scale) / 2
            HorizontalAlignment.Right -> w.resolvedDimensions.x - rc.dimensions.x * scale
        }

        val vOffset = when(cwd.horizontalAlignment) {
            HorizontalAlignment.Left -> 0
            HorizontalAlignment.Centered -> (w.resolvedDimensions.y - rc.dimensions.y * scale) / 2
            HorizontalAlignment.Right -> w.resolvedDimensions.y - rc.dimensions.y * scale
        }

        return w.resolvedPosition + Vec3i(hOffset, vOffset, 0) + Vec3i(cwd.renderOffset().x, cwd.renderOffset().y, 0)
    }

    override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
        val cwd = w[AsciiCanvasWidgetData] ?: return

        val rc = resolveCanvas(cwd)
        val scale = cwd.scale()

        if (rc.name != "empty") {
            rc.name = w.identifier
        }

        val position = absoluteCanvasPosition(w, cwd)

        commandsOut.add(AsciiDrawCommand.Blit(rc, position, scale = scale))

        rc.renderedRevision = rc.revision

    }

    override fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        val cwd = w[AsciiCanvasWidgetData] ?: return null
        val rc = resolveCanvas(cwd)
        return when (axis) {
            Axis2D.X -> rc.dimensions.x * cwd.scale()
            Axis2D.Y -> rc.dimensions.y * cwd.scale()
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val cwd = w[AsciiCanvasWidgetData] ?: return

        val scaleUpdated = cwd.scale.update(ctx)
        val canvasUpdated = cwd.canvas.update(ctx)
        if (canvasUpdated || scaleUpdated) {
            w.markForUpdate(RecalculationFlag.Contents)
            if (w.dimensions.x.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if (w.dimensions.y.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }
    }

    override fun update(windowingSystem: WindowingSystem) {
        windowingSystem.widgetsWithData(AsciiCanvasWidgetData).forEach { (w, cwd) ->
            val rc = resolveCanvas(cwd)
            if (rc.renderedRevision < rc.revision) {
                w.markForUpdate(RecalculationFlag.Contents)
            }
        }
    }

    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(AsciiCanvasWidgetData)
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val cwd = w[AsciiCanvasWidgetData] ?: return false
        val rc = resolveCanvas(cwd)

        fun transformPosition(eventPos: Vec2f) : Vec2i? {
            val absPos = absoluteCanvasPosition(w, cwd)
            if (cwd.scale() == 0) {
                Noto.err("Bad scale?")
            }
            val dx = (eventPos.x.toInt() - absPos.x) / cwd.scale().max(1)
            val dy = (eventPos.y.toInt() - absPos.y) / cwd.scale().max(1)

            return if (dx < rc.dimensions.x && dy < rc.dimensions.y && dx >= 0 && dy >= 0) {
                Vec2i(dx, rc.dimensions.y - dy - 1)
            } else {
                null
            }
        }

        when (event) {
            is WidgetMousePressEvent -> {
                transformPosition(event.position)?.let { pos ->
                    w.fireEvent(AsciiCanvasMousePressEvent(pos, event.button, event.mods, event))
                }
            }
            is WidgetMouseReleaseEvent -> {
                transformPosition(event.position)?.let { pos ->
                    w.fireEvent(AsciiCanvasMouseReleaseEvent(pos, event.button, event.mods, event))
                }
            }
            is WidgetMouseDragEvent -> {
                transformPosition(event.position)?.let { pos ->
                    w.fireEvent(AsciiCanvasMouseDragEvent(pos, event.button, event.mods, event))
                }
            }
        }

        return false
    }
}

//object AsciiCanvasWidget : CustomWidget {
//    override val name: String = "AsciiCanvas"
//    override val archetype: String = "AsciiCanvas"
//
//    override fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {
//        arch.data
//    }
//
//    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
//        return false
//    }
//}