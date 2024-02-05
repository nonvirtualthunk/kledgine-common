package arx.display.windowing.components.ascii

import arx.core.*
import arx.display.core.Key
import arx.display.windowing.*
import arx.display.windowing.components.*
import arx.engine.DisplayEvent
import com.typesafe.config.ConfigValue
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf


data class ListItemAddRequested(val option: Any?, val src: DisplayEvent) : WidgetEvent(src)

object AddableListWidget : CustomWidget {
    override val name: String = "AddableListWidget"
    override val archetype: String = "$name.Main"

    override fun propagateConfigToChildren(): Set<String> {
        return setOf(
            "gapSize",
            "listItemBinding",
            "listItemArchetype"
        )
    }

    override fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {
        val w = arch.widgetData
        // if addMenuOptions has been supplied, either bind it directly in (if constant) or
        // set it up as a pointer for the underlying binding used by the add list
        cv["addMenuOptions"]?.letDo { amo ->
            if (amo.isList()) {
                val l = amo.asPrimitiveList()
                if (l.size != amo.asList().size) {
                    Noto.warn("AddableListWidget has an addMenuOptions binding it cannot fully use: $amo")
                }
                if (l.isNotEmpty()) {
                    w.bind("addMenuOptions", l)
                }
            } else if (amo.isStr()){
                extractBindingPattern(amo)
                    .ifLet { pattern -> w.bindPointer("addMenuOptions" to pattern) }
                    .orElse { w.bindPointer("addMenuOptions" to amo.asStr()!!) }
            }
        }

        (cv["addValue", "addSignal", "addOption"])?.asPrimitiveValue()?.letDo { av ->
            if (av is String && extractBindingPattern(av) != null) {
                Noto.warn("Binding patterns are not yet supported for AddableListWidget addValue's: $av")
            }
            w.bind("buttonSignal", av)
        }

        cv["label"]?.let { lcv ->
            w.redirectBinding(lcv, "addableListWidgetLabel")
        }

//          TODO: thinking about adding automatic Remove button here
        //     Note: with the way things are set up it's quite a challenge, you'd need to be injecting in new bits of
        //      archetypes and stuff. Generally we don't have a great infrastructure for dynamic widget generation
//        arch.children["List"]?.let { l ->
//            l.data.firstNotNullOfOrNull { it as? ListWidget }?.let { lw ->
//                lw.listItemArchetype = ws.loadArchetype(lw.listItemArchetypeIdentifier)
//            }
//        }
    }

    fun giveBindingToNewItem(w : Widget) {
        // check the binding the list uses for updates after requesting additions to it
        // this allows us to (hopefully) give focus to the newly created thing
        w.descendantWithIdentifier("List")?.let { l ->
            l[ListWidget]?.let { lw ->
                w.checkBindings()
                lw.listItemChildren.lastOrNull()?.takeFocus()
            }

        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        return when (event) {
            is ButtonPressEvent -> {
                if (event.identifier == "AddButton") {
                    val pickMenu = w.descendantWithIdentifier("AddMenu")
                    val hasPickMenu = pickMenu?.let { m ->
                        m[ListWidget]?.listItemChildren?.isNotEmpty()
                    } ?: false

                    if (hasPickMenu && pickMenu != null) {
                        w.bind("showAddMenu", true)
                        w.windowingSystem.updateGeometry()
                        if (pickMenu.resY + pickMenu.resHeight > w.windowingSystem.desktop.resClientHeight) {
                            pickMenu.y = WidgetPosition.Relative(WidgetNameIdentifier("AddButton"), 0, WidgetOrientation.BottomRight, WidgetOrientation.BottomRight)
                        }
                        pickMenu.takeFocus()
                    } else {
                        w.fireEvent(ListItemAddRequested(event.buttonSignal, event))
                        giveBindingToNewItem(w)
                    }
                    true
                } else if (event.buttonSignal == "Remove") {
                    if (event.data != null) {
                        w.dataProperty?.let { dp ->
                            if (dp.twoWayBinding) {
                                when (val raw = dp.get()) {
                                    is List<*> -> {
                                        if (dp.type.isSubtypeOf(typeOf<MutableList<*>>())) {
                                            (raw as MutableList<Any>).remove(event.data)
                                        } else if (dp.type.isSubtypeOf(typeOf<List<*>>())) {
                                            dp.set(raw - event.data)
                                        } else {
                                            Noto.warn("AddableListWidget, unexpected dataProperty case on removal: $raw")
                                        }
                                    }

                                    else -> {
                                        Noto.warn("AddableListWidget has dataProperty binding on removal, but not a list")
                                    }
                                }

                                w.checkBindings()
                            }
                        }
                    }
                    false
                } else {
                    false
                }
            }
            is ListItemSelected -> {
                if (event.isFrom("AddMenu") && event.explicit) {
                    if (event.data != null) {
                        w.dataProperty?.let { dp ->
                            if (dp.twoWayBinding) {
                                when (val raw = dp.get()) {
                                    is List<*> -> {
                                        if (dp.type.isSubtypeOf(typeOf<MutableList<*>>())) {
                                            (raw as MutableList<Any>).add(event.data)
                                        } else if (dp.type.isSubtypeOf(typeOf<List<*>>())) {
                                            dp.set(raw + event.data)
                                        } else {
                                            Noto.warn("AddableListWidget, unexpected dataProperty case: $raw")
                                        }
                                    } else -> {
                                        Noto.warn("AddableListWidget has dataProperty binding, but not a list")
                                    }
                                }
                            }
                        }
                    }

                    w.fireEvent(ListItemAddRequested(event.data, event))

                    // check the binding the list uses for updates after requesting additions to it
                    // this allows us to (hopefully) give focus to the newly created thing
                    giveBindingToNewItem(w)
                    w.bind("showAddMenu", false)
                    true
                } else {
                    false
                }
            }
            is WidgetKeyPressEvent -> {
                if (event.widgets.contains(w.descendantWithIdentifier("AddMenu")) && event.key == Key.Escape) {
                    w.bind("showAddMenu", false)
                    w.takeFocus()
                    true
                } else {
                    false
                }
            }
            is FocusChangedEvent -> {
                if (!event.hasFocus && event.isFrom("AddMenu")) {
                    w.bind("showAddMenu", false)
                    return true
                }
                false
            }
            else -> false
        }
    }
}