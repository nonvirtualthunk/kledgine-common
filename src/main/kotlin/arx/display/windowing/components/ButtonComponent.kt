package arx.display.windowing.components

import arx.core.*
import arx.display.core.Key
import arx.display.windowing.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.DisplayEvent
import arx.engine.EntityData
import com.typesafe.config.ConfigValue


data class Button (
    var pressedBackground : NineWayImage? = null,
    var unpressedBackground : NineWayImage? = null,
    var pressedColor : RGBA? = null,
    var pressed : Boolean = false,
    var buttonSignal: Bindable<Any?> = ValueBindable.Null()
) : DisplayData {
    companion object : DataType<Button>( Button(), sparse = true ), FromConfigCreator<Button> {
        override fun createFromConfig(cv: ConfigValue?): Button? {
            return if (cv["type"].asStr()?.lowercase() == "button") {
                Button(
                    pressedBackground = NineWayImage(cv["pressedBackground"] ?: cv["pressedImage"]),
                    unpressedBackground = NineWayImage(cv["unpressedBackground"] ?: cv["unpressedImage"]),
                    pressedColor = RGBA(cv["pressedColor"]),
                    buttonSignal = bindableAnyOpt(cv["buttonSignal", "signal"])
                )
            } else {
                null
            }
        }
    }
    override fun dataType() : DataType<*> { return Button }

    fun copy() : Button {
        return copy(buttonSignal = buttonSignal.copyBindable())
    }
}

class ButtonPressEvent(val identifier : String?, val buttonSignal: Any?, val data: Any?, from : DisplayEvent) : WidgetEvent(from)


object ButtonComponent : WindowingComponent {
    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(Button)
    }

    fun buttonDown(w: Widget, bd: Button) {
        bd.pressed = true
        val nw = bd.pressedBackground ?: w.background.copy(color = bindable(bd.pressedColor))
        bd.unpressedBackground = w.background
        w.background = nw

        w.markForUpdate(RecalculationFlag.Contents)
    }

    fun buttonUp(w: Widget, bd: Button, event: DisplayEvent) {
        if (bd.pressed) {
            val sig = bd.buttonSignal()
            val bpe = ButtonPressEvent(w.identifier, sig, w.data(), event)
            w.fireEvent(bpe)
            if (! bpe.consumed) {
                sig?.let { s -> w.fireEvent(SignalEvent(s, w.data())) }
            }

            bd.pressed = false
            bd.unpressedBackground?.let { w.background = it }
            w.markForUpdate(RecalculationFlag.Bindings)
            w.markForUpdate(RecalculationFlag.Contents)
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val bd = w[Button] ?: return false

        when (event) {
            is WidgetMouseReleaseEvent -> {
                buttonUp(w, bd, event)
                return true
            }
            is WidgetMousePressEvent -> {
                buttonDown(w, bd)
                return true
            }
            is WidgetKeyPressEvent -> {
                if (event.key == Key.Enter) {
                    buttonDown(w,bd)
                }
            }
            is WidgetKeyReleaseEvent -> {
                if (event.key == Key.Enter) {
                    buttonUp(w, bd, event)
                }
            }
        }

        return false
    }


    override fun render(ws: WindowingSystem, w: Widget, bounds: Recti, quadsOut: MutableList<WQuad>) {
        val bd = w[Button] ?: return

        if (bd.pressed && (bd.pressedBackground != null || bd.pressedColor != null)) {
            val nw = bd.pressedBackground ?: w.background.copy(color = bindable(bd.pressedColor))
            BackgroundComponent.renderNineWay(w, nw, true, quadsOut)
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val b = w[Button] ?: return

        b.buttonSignal.update(ctx)
    }
}