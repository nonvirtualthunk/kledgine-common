package arx.display.windowing.components.ascii

import arx.core.*
import arx.display.ascii.Ascii
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiDrawCommand
import arx.display.windowing.AsciiWindowingSystem
import arx.display.windowing.Widget
import arx.display.windowing.WindowingComponent
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.EntityData
import com.typesafe.config.ConfigValue


data class AsciiDivider (
    val style : Ascii.BoxStyle = Ascii.BoxStyle.Line,
    val horizontal : Boolean = true,
    val scale : Int? = null,
    val foregroundColor : Bindable<RGBA> = ValueBindable(White),
    val backgroundColor : Bindable<RGBA?> = ValueBindable(null),
) : DisplayData {
    companion object : DataType<AsciiDivider>( AsciiDivider(),sparse = true ), FromConfigCreator<AsciiDivider> {
        override fun createFromConfig(cv: ConfigValue?): AsciiDivider? {
            return if (cv["type"].asStr()?.lowercase() == "divider") {
                AsciiDivider(
                    style = Ascii.BoxStyle(cv["style"] ?: cv["dividerStyle"]) ?: defaultInstance.style,
                    horizontal = cv["horizontal"].asBool() ?: defaultInstance.horizontal,
                    scale = cv["scale"].asInt(),
                    foregroundColor = (cv["foregroundColor"] ?: cv["color"])?.let { bindableRGBA(it) } ?: ValueBindable.White,
                    backgroundColor = bindableRGBAOpt(cv["backgroundColor"])
                )
            } else {
                null
            }
        }
    }
    override fun dataType() : DataType<*> { return AsciiDivider }

    fun copy() : AsciiDivider {
        return copy(
            foregroundColor = foregroundColor.copyBindable(),
            backgroundColor = backgroundColor.copyBindable()
        )
    }
}


object AsciiDividerComponent : WindowingComponent {

    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(AsciiDivider)
    }

    override fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        val ad = w[AsciiDivider] ?: return null

        if (axis == Axis2D.Y && ad.horizontal) {
            return ad.scale ?: w.windowingSystem.scale
        } else if (axis == Axis2D.X && !ad.horizontal){
            return ad.scale ?: w.windowingSystem.scale
        } else {
            return null
        }
    }

    override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
        val ad = w[AsciiDivider] ?: return

        val clientPos = Vec3i(w.resClientX, w.resClientY, w.resZ)
        if (ad.horizontal) {
            commandsOut.add(
                AsciiDrawCommand.Line(
                    clientPos,
                    clientPos + Vec3i(w.resWidth, 0, 0),
                    ad.style,
                    ad.scale ?: ws.scale,
                    ad.foregroundColor(),
                    ad.backgroundColor()
                )
            )
        } else {
            commandsOut.add(
                AsciiDrawCommand.Line(
                    clientPos,
                    clientPos + Vec3i(0, w.resHeight, 0),
                    ad.style,
                    ad.scale ?: ws.scale,
                    ad.foregroundColor(),
                    ad.backgroundColor()
                )
            )
        }
    }
}