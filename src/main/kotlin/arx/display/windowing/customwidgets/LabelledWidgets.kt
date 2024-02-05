package arx.display.windowing.customwidgets

import arx.core.get
import arx.display.windowing.Widget
import arx.display.windowing.WidgetArchetype
import arx.display.windowing.WidgetDimensions
import arx.display.windowing.WindowingSystem
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.FocusSettings
import arx.engine.DisplayEvent
import com.typesafe.config.ConfigValue


object LabelledTextInput : CustomWidget {
    override val name: String = "LabelledTextInput"
    override val archetype: String = "LabelledTextInput.Main"

    override fun propagateConfigToChildren(): Set<String> {
        return setOf("all")
    }

    override fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {
        cv["label"]?.let {
            arch.widgetData.redirectBinding(it, "label")
        }
        if (arch.widgetData.width !is WidgetDimensions.ExpandToParent) {
            arch.widgetData.width = WidgetDimensions.WrapContent()
        }
        arch[FocusSettings]?.tabbable = false
//        cv["label"].asStr()?.let { label -> arch.widgetData.bind("label", label) }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        return false
    }
}

object LabelledAsciiDropdown : CustomWidget {
    override val name: String = "LabelledAsciiDropdown"
    override val archetype: String = "LabelledAsciiDropdown.Main"

    override fun propagateConfigToChildren(): Set<String> {
        return setOf("all")
    }

    override fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {
        cv["label"]?.let {
            arch.widgetData.redirectBinding(it, "asciiDropdownLabel")
        }
        if (arch.widgetData.width !is WidgetDimensions.ExpandToParent) {
            arch.widgetData.width = WidgetDimensions.WrapContent()
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        return false
    }
}

