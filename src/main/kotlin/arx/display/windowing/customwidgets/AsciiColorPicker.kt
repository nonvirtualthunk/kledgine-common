package arx.display.windowing.customwidgets

import arx.core.*
import arx.display.ascii.Ascii
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiDrawCommand
import arx.display.windowing.*
import arx.display.windowing.components.ButtonPressEvent
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.ascii.AsciiBackground
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.DisplayEvent
import arx.engine.EntityData
import com.typesafe.config.ConfigValue

data class SelectedColorChangeEvent(val selectedColor : HSL) : WidgetEvent(null)
data class ForegroundColorChangeEvent(val color : HSL) : WidgetEvent(null)
data class BackgroundColorChangeEvent(val color : HSL) : WidgetEvent(null)
data class ActiveColorFlippedEvent(val foregroundActive: Boolean) : WidgetEvent(null)

data class SelectedColorChannelChangeEvent(val channel : HSLChannel, val value : Float, val src: DisplayEvent) : WidgetEvent(src)

data class AsciiColorPickerState (
    var selectedColor : HSL = HSL(0.0f,1.0f,0.5f,1.0f),
    var foregroundColor : HSL = HSL(0.0f,1.0f,0.5f,1.0f),
    var backgroundColor : HSL = HSL(0.0f,0.0f,0.0f,0.0f),
    var dualColorMode : Boolean = false,
    var foregroundBorderColor: RGBA = White,
    var backgroundBorderColor: RGBA = RGBA(128,128,128,255),
    var foregroundActive : Boolean = true
) : DisplayData {
    companion object : DataType<AsciiColorPickerState>( AsciiColorPickerState() , sparse = true), FromConfigCreator<AsciiColorPickerState> {
        override fun createFromConfig(cv: ConfigValue?): AsciiColorPickerState? {
            if (cv["type"].asStr()?.lowercase() == "asciicolorpicker") {
                return AsciiColorPickerState(
                    dualColorMode = cv["dualColor", "dualColorMode", "dualColors"].asBool() ?: false
                )
            } else {
                return null
            }
        }
    }
    override fun dataType() : DataType<*> { return AsciiColorPickerState }
}


object AsciiColorPicker : CustomWidget {
    override val name: String = "AsciiColorPicker"
    override val archetype: String = "AsciiColorPicker.AsciiColorPicker"

    override fun initialize(w: Widget) {
        w.bind("state", w[AsciiColorPickerState]!!)
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val acps = w[AsciiColorPickerState] ?: return false

        when (event) {
            is SelectedColorChannelChangeEvent -> {
                acps.selectedColor = acps.selectedColor.withChannel(event.channel, event.value)
                if ((acps.selectedColor.h > 0.0f || acps.selectedColor.s > 0.0f || acps.selectedColor.l > 0.0f) && (acps.selectedColor.a == 0.0f)) {
                    acps.selectedColor = HSL(0.0f,1.0f,0.5f,1.0f).withChannel(event.channel, event.value)
                }

                if (acps.foregroundActive) {
                    acps.foregroundColor = acps.selectedColor
                } else {
                    acps.backgroundColor = acps.selectedColor
                }
                w.bind("state", acps)
                fireColorUpdateEvents(w, acps)
            }
            is ButtonPressEvent -> {
                if (event.buttonSignal == "FlipActiveColor") {
                    acps.foregroundActive = !acps.foregroundActive
                    acps.selectedColor = if (acps.foregroundActive) {
                        acps.foregroundBorderColor = White
                        acps.backgroundBorderColor = RGBA(128, 128, 128, 255)
                        acps.foregroundColor.copy()
                    } else {
                        acps.foregroundBorderColor = RGBA(128, 128, 128, 255)
                        acps.backgroundBorderColor = White
                        acps.backgroundColor.copy()
                    }
                    w.fireEvent(ActiveColorFlippedEvent(acps.foregroundActive))
                } else if (event.buttonSignal == "ClearColor") {
                    acps.selectedColor = HSL(0.0f,0.0f,0.0f,0.0f)
                    if (acps.foregroundActive) {
                        acps.foregroundColor = acps.selectedColor
                    } else {
                        acps.backgroundColor = acps.selectedColor
                    }
                }

                w.bind("state", acps)
            }
        }
        return false
    }

    private fun fireColorUpdateEvents(w: Widget, acps: AsciiColorPickerState) {
        w.fireEvent(SelectedColorChangeEvent(acps.selectedColor.copy()))
        if (acps.dualColorMode) {
            if (acps.foregroundActive) {
                w.fireEvent(ForegroundColorChangeEvent(acps.foregroundColor.copy()))
            } else {
                w.fireEvent(BackgroundColorChangeEvent(acps.backgroundColor.copy()))
            }
        }
    }

    override fun dataType(): DataType<EntityData> {
        return AsciiColorPickerState
    }

    fun forChildAsciiColorPickers(w: Widget, fn: (AsciiColorPickerState) -> Unit) {
        w.selfAndDescendants().forEach { c ->
            c[AsciiColorPickerState]?.let { cps ->
                fn(cps)

                c.bind("state", cps)
            }
        }
    }


    fun setActiveColor(w: Widget, color : HSL) {
        forChildAsciiColorPickers(w) {
            if (it.foregroundActive) {
                it.foregroundColor = color
            } else {
                it.backgroundColor = color
            }
            fireColorUpdateEvents(w, it)
        }
    }

    fun setForegroundColor(w: Widget, color : HSL) {
        forChildAsciiColorPickers(w) {
            it.foregroundColor = color
            fireColorUpdateEvents(w, it)
        }
    }

    fun setBackgroundColor(w: Widget, color : HSL) {
        forChildAsciiColorPickers(w) {
            it.backgroundColor = color
            fireColorUpdateEvents(w, it)
        }
    }

    fun setColor(w: Widget, color : HSL) {
        forChildAsciiColorPickers(w) {
            it.backgroundColor = color
            fireColorUpdateEvents(w, it)
        }
    }

    fun foregroundColorActive(w: Widget) : Boolean {
        var isForegroundActive : Boolean? = null
        forChildAsciiColorPickers(w) {
            isForegroundActive = it.foregroundActive
        }
        return isForegroundActive ?: true
    }
}


// A slider without a channel is just a display
data class HSLSliderWidget (
    var selectedColor : Bindable<HSL> = bindable(HSL(0.0f,1.0f,1.0f,1.0f)),
    var channel : HSLChannel? = HSLChannel.Hue
) : DisplayData {

    fun copy() : HSLSliderWidget {
        return copy(selectedColor = selectedColor.copyBindable())
    }

    companion object : DataType<HSLSliderWidget>( HSLSliderWidget(), sparse = true ), WindowingComponent, FromConfigCreator<HSLSliderWidget> {

        override fun createFromConfig(cv: ConfigValue?): HSLSliderWidget? {
            if (cv["type"]?.asStr()?.lowercase() == "hslslider") {
                return HSLSliderWidget(
                    selectedColor = bindableT(cv["color"] ?: cv["selectedColor"], HSL(0.5f,0.5f,0.5f,1.0f)),
                    channel = HSLChannel(cv["channel"])
                )
            } else {
                return null
            }
        }


        fun valueAt(w: Widget, rx: Int) : Float {
            return (rx.toFloat() / (w.resClientWidth - 1)).clamp(0.0f, 1.0f)
        }

        override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
            val cpw = w[HSLSliderWidget] ?: return

            val width = w.resClientWidth
            val height = w.resClientHeight

            val px = w.resClientX
            val py = w.resClientY
            val pz = w.resZ

            val selC = cpw.selectedColor()
            val tmpHSL = HSL(selC.h, selC.s, selC.l, selC.a)

            val scale = ws.effectiveScale(1)

            val selIndex = cpw.channel?.let { channel ->
                (0 until width step scale).find { x ->
                    val f = valueAt(w, x)
                    f >= selC[channel]
                } ?: (width - scale)
            }

            for (x in 0 until width step scale) {
                val f = valueAt(w, x)

                var char = Ascii.FullBlockChar
                cpw.channel?.let {
                    tmpHSL[it] = f
                    if (selIndex == x + scale) {
                        char = '▐'
                    } else if (selIndex == x - scale) {
                        char = '▌'
                    }
                }

                val fgColor = if (char == '▐' || char == '▌') {
                    RGBA(255,255,255,255)
                } else {
                    tmpHSL.toRGBA()
                }

                if (char == Ascii.FullBlockChar) {
                    for (y in 0 until height) {
                        for (dx in 0 until scale) {
                            commandsOut.add(AsciiDrawCommand.Glyph(char, Vec3i(px + x + dx, py + y, pz), 1, fgColor, tmpHSL.toRGBA()))
                        }
                    }
                } else {
                    for (y in 0 until height step scale) {
                        commandsOut.add(AsciiDrawCommand.Glyph(char, Vec3i(px + x, py + y, pz), scale, fgColor, tmpHSL.toRGBA()))
                    }
                }

                if (char == '▐' || char == '▌') {
                    val s = ws.effectiveScale(w[AsciiBackground]?.scale)
//                    commandsOut.add(AsciiDrawCommand.Glyph('┬', Vec3i(px + x + 1, w.resY, 0), s, White, Clear))
//                    commandsOut.add(AsciiDrawCommand.Glyph('┴', Vec3i(px + x + 1, w.resY + w.resHeight - 1, 0), s, White, Clear))
                    val dx = if (char == '▌') { +0 } else { +1 }
                    commandsOut.add(AsciiDrawCommand.Glyph('▄', Vec3i(px + x + dx, w.resY, pz), s, White, Clear))
                    commandsOut.add(AsciiDrawCommand.Glyph('▀', Vec3i(px + x + dx, w.resY + w.resHeight - 1, pz), s, White, Clear))
                }
            }
        }

        override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
            val cpw = w[HSLSliderWidget] ?: return
            if (cpw.selectedColor.update(ctx)) {
                w.markForUpdate(RecalculationFlag.Contents)
            }
        }

        override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
            val cpw = w[HSLSliderWidget] ?: return false

            when (event) {
                is WidgetMouseEvent -> {
                    if (event !is WidgetMouseMoveEvent) {
                        val x = event.position.x.toInt() - w.resClientX - 1
                        cpw.channel?.let {
                            w.fireEvent(SelectedColorChannelChangeEvent(it, valueAt(w, x), event))
                        }
                    }
                }
            }
            return false
        }

        override fun dataTypes(): List<DataType<EntityData>> {
            return listOf(HSLSliderWidget)
        }
    }
    override fun dataType() : DataType<*> { return HSLSliderWidget }
}
