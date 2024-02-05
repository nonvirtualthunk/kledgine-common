package arx.display.windowing.customwidgets

import arx.core.BindingContext
import arx.core.RGBA
import arx.core.asStr
import arx.core.get
import arx.display.ascii.CustomChars
import arx.display.core.Key
import arx.display.windowing.*
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.ascii.AsciiRichText
import arx.display.windowing.components.ascii.AsciiTextWidget
import arx.engine.DisplayEvent
import com.typesafe.config.ConfigValue


data class CheckboxChanged(val checked: Boolean, val src: DisplayEvent) : WidgetEvent(src)

object AsciiCheckbox : CustomWidget {
    override val name: String = "Checkbox"
    override val archetype: String = "AsciiCheckbox.Main"

    override fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {
        cv["label"].asStr()?.let { label -> arch.widgetData.bind("label", label) }
        val color = RGBA(cv["checkedColor"]) ?: RGBA(255,200,220,255)
        arch.widgetData.bind("checkedColor", color)
    }

    override fun propagateConfigToChildren(): Set<String> {
        return setOf("dataProperty")
    }

    val checkedART = AsciiRichText(CustomChars.replaceEscapeSequences("∑fullCaretRight∑∑fullCaretLeft∑"))

    fun toggle(w: Widget, event: DisplayEvent) {
        val checked = (w.dataProperty?.get() as? Boolean) ?: w[AsciiTextWidget]?.text?.invoke()?.plainText()?.isNotEmpty() ?: false
        if (checked) {
            w.bind("checked", checkedART)
            w.dataProperty?.set(false)
            w.fireEvent(CheckboxChanged(false, event))

        } else {
            w.bind("checked", "  ")
            w.dataProperty?.set(true)
            w.fireEvent(CheckboxChanged(true, event))
        }
    }

    override fun updateBindings(w: Widget, ctx: BindingContext) {
        (w.dataProperty?.get() as? Boolean)?.let { b ->
            if (b) {
                w.bind("checked", checkedART)
            } else {
                w.bind("checked", "  ")
            }
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        when (event) {
            is WidgetKeyPressEvent -> {
                if (event.key == Key.Enter) {
                    toggle(w, event)
                    return true
                }
            }
            is WidgetMouseReleaseEvent -> {
                if (event.isFrom("Checkbox")) {
                    toggle(w, event)
                    return true
                }
            }
        }
        return false
    }
}