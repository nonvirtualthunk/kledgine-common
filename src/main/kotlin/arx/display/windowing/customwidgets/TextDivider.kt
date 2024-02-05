package arx.display.windowing.customwidgets

import arx.core.asStr
import arx.core.bindableBool
import arx.core.get
import arx.display.windowing.Widget
import arx.display.windowing.WidgetArchetype
import arx.display.windowing.WidgetPosition
import arx.display.windowing.WindowingSystem
import arx.display.windowing.components.CustomWidget
import arx.engine.DisplayEvent
import com.typesafe.config.ConfigValue

object TextDivider : CustomWidget {
    override val name: String = "TextDivider"
    override val archetype: String = "TextDivider.Main"


    override fun propagateConfigToChildren(): Set<String> {
        return setOf("text")
    }

    override fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {
        cv["align"].asStr()?.let { a ->
            when (a.lowercase()) {
                "left" -> arch.children["Text"]?.widgetData?.x = WidgetPosition.Fixed(0)
            }
        }

        arch.children["Text"]?.widgetData?.background?.draw = bindableBool(cv["background"]["draw"])
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        return false
    }
}