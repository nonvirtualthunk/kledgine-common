package arx.display.windowing.components

import arx.core.*
import arx.display.windowing.Widget
import arx.display.windowing.WidgetArchetype
import arx.display.windowing.WindowingComponent
import arx.display.windowing.WindowingSystem
import arx.engine.*
import com.typesafe.config.ConfigValue

interface CustomWidget {
    val name : String
    val archetype: String

    fun dataType() : DataType<EntityData>? { return null }

    fun handleEvent(w: Widget, event: DisplayEvent) : Boolean
    fun initialize(w: Widget) {}
    fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {}
    fun updateBindings(w: Widget, ctx: BindingContext) {}
    fun update(w: Widget) {}
    fun propagateConfigToChildren(): Set<String> {
        return emptySet()
    }
}

object NoCustomWidget : CustomWidget {
    override val name: String = "None"
    override val archetype: String = "None"

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        return false
    }
}

data class CustomWidgetData (
    val customWidgetKind: CustomWidget = NoCustomWidget
) : DisplayData {
    companion object : DataType<CustomWidgetData>( CustomWidgetData(), sparse = true ), FromConfigCreator<CustomWidgetData> {
        override fun createFromConfig(cv: ConfigValue?): CustomWidgetData? {
            cv["type"].asStr()?.let { typeStr ->
                CustomWidgetComponent.customWidgetTypesLower[typeStr.lowercase()]?.let { kindString ->
                    return CustomWidgetData(
                        customWidgetKind = CustomWidgetComponent.customWidgetTypes[kindString]!!
                    )
                }
            }
            return null
        }
    }
    override fun dataType() : DataType<*> { return CustomWidgetData }
}


object CustomWidgetComponent : WindowingComponent {
    internal var dataTypesRetrieved = false
    internal val dataTypes = mutableListOf<DataType<EntityData>>()
    internal val customWidgetTypes = mutableMapOf<String, CustomWidget>()
    internal val customWidgetTypesLower = mutableMapOf<String, String>()
    internal var archetypesByType = mapOf<String, String>()

    override val configArchetypesByType: Map<String, String>
        get() = archetypesByType

    override fun dataTypes(): List<DataType<EntityData>> {
        dataTypesRetrieved = true
        return listOf(CustomWidgetData) + dataTypes
    }

    override fun propagateConfigToChildren(w: WidgetArchetype): Set<String> {
        return w.data.firstNotNullOfOrNull { it as? CustomWidgetData }?.customWidgetKind?.propagateConfigToChildren() ?: emptySet()
    }

    override fun updateBindingsPriority(): Priority {
        // we want it to come after the normal updates, since this
        // doesn't generally own bindings directly
        return Priority.Last
    }

    override fun configurePriority(): Priority {
        // we want to potentially adjust configurations after everything else has
        // settled
        return Priority.Last
    }

    override fun initializeWidget(w: Widget) {
        val cwd = w[CustomWidgetData] ?: return
        val cwt = cwd.customWidgetKind

        cwt.initialize(w)
    }

    override fun configure(ws: WindowingSystem, w: WidgetArchetype, cv: ConfigValue) {
        val cwd = w.data.firstNotNullOfOrNull { it as? CustomWidgetData } ?: return
        val cwt = cwd.customWidgetKind

        cwt.configure(ws, w, cv)
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val cwd = w[CustomWidgetData] ?: return false
        val cwt = cwd.customWidgetKind

        return cwt.handleEvent(w, event)
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val cwd = w[CustomWidgetData] ?: return
        val cwt = cwd.customWidgetKind

        return cwt.updateBindings(w, ctx)
    }

    override fun update(windowingSystem: WindowingSystem) {
        for (w in windowingSystem.widgetsThatHaveData(CustomWidgetData)) {
            val cwd = w[CustomWidgetData] ?: continue
            val cwt = cwd.customWidgetKind
            cwt.update(w)
        }
    }
}

fun registerCustomWidget(cw: CustomWidget) {
    if (cw.dataType() != null && CustomWidgetComponent.dataTypesRetrieved) {
        Noto.err("Custom widget type with data registered after windowing system setup : ${cw.javaClass.simpleName}")
    }
    cw.dataType()?.let { dt -> CustomWidgetComponent.dataTypes.add(dt) }
    CustomWidgetComponent.customWidgetTypes[cw.name] = cw
    CustomWidgetComponent.customWidgetTypesLower[cw.name.lowercase()] = cw.name
    CustomWidgetComponent.archetypesByType = CustomWidgetComponent.customWidgetTypes.map { it.key.lowercase() to it.value.archetype }.toMap()

}



