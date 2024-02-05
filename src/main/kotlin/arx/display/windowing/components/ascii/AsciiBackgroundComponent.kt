package arx.display.windowing.components.ascii

import arx.core.*
import arx.display.ascii.Ascii
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiDrawCommand
import arx.display.windowing.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.EntityData
import com.typesafe.config.ConfigValue

data class AsciiBackground (
    var single : Boolean = true,
    var join: Boolean = false,
    var scale: Bindable<Int?> = ValueBindable.Null(),
    var style: Bindable<Ascii.BoxStyle> = ValueBindable(Ascii.BoxStyle.SolidExternal),
    var drawCenter: Bindable<Boolean> = ValueBindable.False
) : DisplayData {
    companion object : DataType<AsciiBackground>( AsciiBackground(), sparse = false ), FromConfigCreator<AsciiBackground> {
        override fun createFromConfig(cv: ConfigValue?): AsciiBackground? {
            if (cv == null) {
                return null
            }

            val bgcv = cv["background"]
            return if (bgcv == null) {
                AsciiBackground()
            } else {
                AsciiBackground(
                    single = bgcv["single"].asBool() ?: true,
                    join = bgcv["join"].asBool() ?: false,
                    scale = bindableIntOpt(bgcv["scale"]),
//                    style = Ascii.BoxStyle(bgcv["style"]) ?: Ascii.BoxStyle.SolidExternal,
                    style = bindableT(bgcv["style"], { Ascii.BoxStyle.createFromConfig(it) } ,Ascii.BoxStyle.SolidExternal),
                    drawCenter = (cv["background"]["drawCenter"] ?: cv["background"]["fill"])?.let { bindableBool(it) } ?: ValueBindable.True,
                )
            }
        }
    }
    override fun dataType() : DataType<*> { return AsciiBackground }

    fun copy() : AsciiBackground {
        return copy(scale = scale.copyBindable(), drawCenter = drawCenter.copyBindable(), style = style.copyBindable())
    }
}

operator fun AsciiBackground?.unaryPlus() : AsciiBackground {
    return this?: AsciiBackground.defaultInstance
}


//data class AsciiWindowingSystemData (
//    val baseScale : Int = 1,
//) : DisplayData, CreateOnAccessData {
//    companion object : DataType<AsciiWindowingSystemData>( AsciiWindowingSystemData() )
//    override fun dataType() : DataType<*> { return AsciiWindowingSystemData }
//}
//
//operator fun AsciiWindowingSystemData?.unaryPlus() : AsciiWindowingSystemData {
//    return this?: AsciiWindowingSystemData.defaultInstance
//}


object AsciiBackgroundComponent : WindowingComponent {

    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(AsciiBackground)
    }

    override fun clientOffsetContributionNear(w: Widget, axis: Axis2D): Int {
        return if (w.background.draw() && w.background.drawEdges()) {
            return w.windowingSystem.forceScale ?: w[AsciiBackground]?.scale?.invoke() ?: w.windowingSystem.scale
        } else {
            0
        }
    }

    override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
        if (w.background.draw() && w.background.drawEdges()) {
//            val wd = ws.world[AsciiWindowingSystemData]
            val ab = w[AsciiBackground]

            commandsOut.add(
                AsciiDrawCommand.Box(
                    position = w.resolvedPosition,
                    dimensions = w.resolvedDimensions,
                    style = ab?.style?.invoke() ?: Ascii.BoxStyle.SolidExternal,
                    scale = w.windowingSystem.forceScale ?: ab?.scale?.invoke() ?: ws.scale,
                    edgeColor = w.background.edgeColor() ?: w.background.color() ?: White,
                    fillColor = tern((ab?.drawCenter?.invoke() ?: false) || w.background.centerColor() != null, w.background.centerColor() ?: Black, Clear),
                    join = false
                )
            )
        }
    }

    override fun updateBindings(ws: WindowingSystem, w : Widget, ctx: BindingContext) {
        val preClientOffsetsX = clientOffsetContributionNear(w, Axis2D.X)
        val preClientOffsetsY = clientOffsetContributionNear(w, Axis2D.Y)

        val ab = w[AsciiBackground]

        val abfUpdated = ab?.scale?.update(ctx) == true
        val abStyleUpdated = ab?.style?.update(ctx) == true

        if (Bindable.updateBindableFields(w.background, ctx) || abfUpdated || abStyleUpdated) {
            w.markForUpdate(RecalculationFlag.Contents)
            if (preClientOffsetsX != clientOffsetContributionNear(w, Axis2D.X)) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
                w.markForUpdate(RecalculationFlag.PositionX)
            }
            if (preClientOffsetsY != clientOffsetContributionNear(w, Axis2D.Y)) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
                w.markForUpdate(RecalculationFlag.PositionY)
            }
        }
    }
}

