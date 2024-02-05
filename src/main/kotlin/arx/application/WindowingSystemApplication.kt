package arx.application

import arx.core.*
import arx.display.windowing.*
import arx.display.windowing.components.*
import arx.engine.Engine


internal object State {
    data class Foo (var i : Int) {
        override fun toString(): String {
            return "Item $i"
        }
    }


    class SelectionState {
        var items = listOf(Foo(1), Foo(2))
        var selection = items[0]
    }

    var selectionState = SelectionState()
}

data class WindowingSystemDebugComponent(val updateFns : List<() -> Unit>) : WindowingComponent {

    override fun update(windowingSystem: WindowingSystem) {
        updateFns.forEach { fn -> fn() }
    }
}

fun main() {
    val engine = Engine(
        mutableListOf(),
        mutableListOf(WindowingSystemComponent)
    )


    var x = 3
    var y = "Hello?"

    engine.world.attachData(WindowingSystem().apply {
        registerStandardComponents()

        val childA = createWidget("Widgets.ChildA")
        val childB = createWidget("Widgets.ChildB")
        val list = createWidget("Widgets.ListThing")

        childB.bind("test", "Test Text")

        list.bind("listItems", listOf(mapOf("text" to "List item A"), mapOf("text" to "Second list item")))

        desktop.bind("dropdownState", State.selectionState)

        desktop.onEventDo<WidgetMouseReleaseEvent> { e ->
            println("Widget mouse release : ${e.position} <- ${e.from.position}")
            childB.bind("test", "Mouse Release : ${e.position}")
        }
        desktop.onEventDo<DropdownSelectionChanged> {
            println("Dropdown selection changed : ${State.selectionState.selection}")
        }


        val xeditor = childA.descendantWithIdentifier("XEditor")!!
        val yeditor = childA.descendantWithIdentifier("YEditor")!!
        xeditor.onEventDo<PropertyEdited> {
            println("Property edited to : $x")
        }
        yeditor.onEventDo<PropertyEdited> {
            println("Property edited to : $y")
        }

        childA.descendantWithIdentifier("XEditor")?.bindProperty("X", { x }, { x = it })
        childA.descendantWithIdentifier("YEditor")?.bind("prop.value", PropertyReference({ y }, { y = it }))

//        val propWidget = PropertyEditorWidget(childA, "Widgets.PropertyEditor", RichText("X"), { x }, { x = it })
//        propWidget.x = WidgetPosition.Relative(WidgetNameIdentifier("Dropdown"), 0, targetAnchor = WidgetOrientation.TopLeft)
//        propWidget.y = WidgetPosition.Relative(WidgetNameIdentifier("Dropdown"), 5)
    })

    Application()
        .run(engine)
}