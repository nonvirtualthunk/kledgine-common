package arx.display.windowing.components

import arx.core.*
import arx.display.windowing.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.DisplayEvent
import arx.engine.EntityData
import com.typesafe.config.ConfigValue


data class PropertyEditor (
    val propertyBindingPattern: String? = null,
    val labelBindingPattern: String? = null,
    val label: String? = null,
) : DisplayData {
    companion object : DataType<PropertyEditor>( PropertyEditor(),sparse = true ), FromConfigCreator<PropertyEditor> {
        override fun createFromConfig(cv: ConfigValue?): PropertyEditor? {
            var propBinding: String? = null
            cv["property"].asStr()?.let { str ->
                stringBindingPattern.match(str).ifLet { (pattern) ->
                    propBinding = pattern
                }.orElse {
                    Noto.err("property binding $str is not a valid binding expression")
                }
            }
            val labelStr = cv["label"].asStr()
            val typeStr = cv["type"].asStr()?.lowercase()

            var labelBindingPattern : String? = null
            var label : String? = null

            labelStr?.let { str ->
                stringBindingPattern.match(str).ifLet { (pattern) ->
                    labelBindingPattern = pattern
                }.orElse {
                    label = str
                }
            }

            return if (propBinding != null || labelStr != null || typeStr == "propertyeditor") {
                PropertyEditor(
                    propertyBindingPattern = propBinding,
                    labelBindingPattern = labelBindingPattern,
                    label = label
                )
            } else {
                null
            }
        }
    }

    override fun dataType() : DataType<*> { return PropertyEditor }
}

data class PropertyEditorBinding(val label: RichText, val data: PropertyReference<*>) {
    companion object {
        inline operator fun <reified T : Any> invoke(label: RichText, noinline getter : () -> T, noinline setter: (T) -> Unit) : PropertyEditorBinding {
            return PropertyEditorBinding(label, PropertyReference(getter = getter, setter = setter))
        }

        inline operator fun <reified T : Any> invoke(label: String, noinline getter : () -> T, noinline setter: (T) -> Unit) : PropertyEditorBinding {
            return PropertyEditorBinding(RichText(label), PropertyReference(getter, setter))
        }
    }
}

inline fun <reified T : Any> Widget.bindProperty(label: RichText, noinline getter : () -> T, noinline setter: (T) -> Unit) {
    bind("property", PropertyEditorBinding(label, PropertyReference(getter, setter)))
}

inline fun <reified T : Any> Widget.bindProperty(label: String, noinline getter : () -> T, noinline setter: (T) -> Unit) {
    bind("property", PropertyEditorBinding(RichText(label), PropertyReference(getter, setter)))
}

data class PropertyEdited(val source: TextDataChanged) : WidgetEvent(source)


object PropertyEditorWidget {
    inline operator fun <reified T : Any> invoke(parent: Widget, archetype : String, label: RichText, noinline getter: () -> T, noinline setter : (T) -> Unit) : Widget {
        val w = parent.windowingSystem.createWidget(parent, archetype, "PropertyEditor.PropertyEditor")

        w.bind("data", PropertyReference(getter, setter))
        w.bind("label", label)

        return w
    }
}




object PropertyEditorWindowingComponent : WindowingComponent {

    override val configArchetypesByType: Map<String, String> = mapOf("PropertyEditor".lowercase() to "PropertyEditor.PropertyEditor")

    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(PropertyEditor)
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val propEdit = w[PropertyEditor] ?: return

        propEdit.propertyBindingPattern?.let { pat -> w.descendantWithIdentifier("Input")?.bind("property.data", BindingPointer(pat)) }
        propEdit.label?.let { str -> w.descendantWithIdentifier("Label")?.bind("property.label", str) }
        propEdit.labelBindingPattern?.let { pat -> w.descendantWithIdentifier("Label")?.bind("property.label", BindingPointer(pat)) }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        w[PropertyEditor] ?: return false

        when (event) {
            is TextDataChanged -> w.fireEvent(PropertyEdited(event))
        }

        return false
    }
}