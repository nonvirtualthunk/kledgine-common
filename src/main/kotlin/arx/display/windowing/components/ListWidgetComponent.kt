package arx.display.windowing.components


import arx.core.*
import arx.display.core.Key
import arx.display.windowing.*
import arx.display.windowing.components.ascii.AsciiTextWidget
import arx.engine.*
import com.typesafe.config.ConfigValue

val bindingPattern = Regex("([a-zA-Z\\d.]+)\\s?->\\s?([a-zA-Z\\d.]+)")

data class ListWidget(
    var sourceBinding : String,
    var targetBinding : String,
    var listItemArchetypeIdentifier : String,
    var listItemArchetype: WidgetArchetype? = null,
    var gapSize: Int = 0,
    var separatorArchetypeIdentifier : String? = null,
    var separatorArchetype: WidgetArchetype? = null,
    var selectable : Boolean = false,
    var listItemChildren : MutableList<Widget> = mutableListOf(),
    var listItemData : List<Any?> = emptyList(),
    var separatorChildren : MutableList<Widget> = mutableListOf(),
    var horizontal : Boolean = false,
    var selectedIndex : Int? = null,
    var selectedColor : Bindable<RGBA?> = ValueBindable.Null(),
    var unselectedColor : Bindable<RGBA?> = ValueBindable.Null(),
    var selectionBinding : Bindable<Any?>? = null,
    var onListItemChildCreated : (Widget) -> Unit = {},
    var typingSelection : String = "",
) : DisplayData {
    companion object : DataType<ListWidget>(ListWidget("", "", ""),sparse = true), FromConfigCreator<ListWidget> {
        override fun createFromConfig(cv: ConfigValue?): ListWidget? {
            if (cv["type"].asStr()?.lowercase() != "listwidget") {
                return null
            }

            val bindingStr = cv["listItemBinding"].asStr()
            val listItemArchetype = cv["listItemArchetype"].asStr()

            if ((bindingStr == null || listItemArchetype == null) && (bindingStr != null || listItemArchetype != null)) {
                Noto.err("ListWidget must have both listItemBinding and listItemArchetype")
            }
            if (bindingStr == null || listItemArchetype == null) {
                return null
            }

            val (sourceBinding, targetBinding) = bindingPattern.match(bindingStr) ?: return Noto.errAndReturn("Invalid pattern binding for list widget : $bindingStr", null)

            return ListWidget(
                sourceBinding = sourceBinding,
                targetBinding = targetBinding,
                listItemArchetypeIdentifier = listItemArchetype,
                separatorArchetypeIdentifier = cv["separatorArchetype"].asStr(),
                gapSize = (cv["gapSize"] ?: cv["gap"]).asInt() ?: 0,
                selectable = cv["selectable"].asBool() ?: false,
                horizontal = cv["horizontal"].asBool() ?: false,
                selectedColor = bindableRGBAOpt(cv["selectedColor"]),
                unselectedColor = bindableRGBAOpt(cv["unselectedColor"]),
                selectedIndex = tern(cv["selectByDefault"].asBool() ?: false, 0, null),
                selectionBinding = (cv["selectedItem"])?.let { c -> bindableAnyOpt(c) }
            )
        }
    }

    override fun dataType(): DataType<*> {
        return ListWidget
    }

    fun copy() : ListWidget {
        return ListWidget(
            sourceBinding = sourceBinding,
            targetBinding = targetBinding,
            listItemArchetypeIdentifier = listItemArchetypeIdentifier,
            listItemArchetype = listItemArchetype,
            gapSize = gapSize,
            separatorArchetypeIdentifier = separatorArchetypeIdentifier,
            separatorArchetype = separatorArchetype,
            selectable = selectable,
            listItemChildren = mutableListOf(),
            separatorChildren = mutableListOf(),
            horizontal = horizontal,
            selectedIndex = selectedIndex,
            selectedColor = selectedColor.copyBindable(),
            unselectedColor = unselectedColor.copyBindable(),
            selectionBinding = selectionBinding?.copyBindable()
        )
    }
}

data class ListWidgetItem (var data : Any? = null, val index : Int = 0) : DisplayData {
    companion object : DataType<ListWidgetItem>( ListWidgetItem(),sparse = true )
    override fun dataType() : DataType<*> { return ListWidgetItem }
}

/**
 * Explicit indicates whether this should be treated as definitely choosing to select
 * this particular index, as opposed to simply indicating this is the actively considered
 * value
 */
data class ListItemSelected(val index: Int, val data : Any?, val explicit: Boolean, val from : DisplayEvent?) : WidgetEvent(from)

data class ListItemMousedOver(val index: Int, val dat : Any?, val from : WidgetMouseEnterEvent) : WidgetEvent(from)

/**
 * Explicit means the same thing as in ListItemSelected
 */
data class SelectListItem(val index: Int, val explicit: Boolean) : WidgetEvent(null)

object ListWidgetComponent : WindowingComponent {

    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(ListWidget)
    }


    fun bindSelectionColor(w: Widget, lw: ListWidget, index: Int) {
        if (index == lw.selectedIndex) {
            w.bind("selection.color", lw.selectedColor)
        } else {
            w.bind("selection.color", lw.unselectedColor)
        }
    }

    override fun initializeWidget(w: Widget) {
        val lw = w[ListWidget] ?: return
        if (lw.listItemArchetype == null) {
            lw.listItemArchetype = w.windowingSystem.loadArchetype(lw.listItemArchetypeIdentifier)
        }
        if (lw.separatorArchetype == null) {
            lw.separatorArchetype = lw.separatorArchetypeIdentifier?.let { w.windowingSystem.loadArchetype(it) }
        }

    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val lw = w[ListWidget] ?: return

        val selColorUpdate = lw.selectedColor.update(ctx)
        val unselColorUpdate = lw.unselectedColor.update(ctx)
        if (selColorUpdate || unselColorUpdate) {
            for ((index, li) in lw.listItemChildren.withIndex()) {
                bindSelectionColor(li, lw, index)
            }
        }

        val listItemDataMaybeChanged = ctx.isDirty(lw.sourceBinding)
        if (listItemDataMaybeChanged) {
            val boundSrcValue = ctx.resolve(lw.sourceBinding) ?: return
            if (boundSrcValue is List<*>) {
                lw.listItemData = boundSrcValue
                for ((i, value) in boundSrcValue.withIndex()) {
                    if (lw.listItemChildren.size <= i) {
                        val newItem = ws.createWidget(w, lw.listItemArchetypeIdentifier)
                        newItem.bind("index", i)
                        newItem.attachData(ListWidgetItem(value, i))
                        newItem.identifier = "${w.identifier}.item[$i]"
                        if (lw.selectable) {
                            newItem.handleEvent<WidgetMouseReleaseEvent> { mre ->
                                lw.selectedIndex = i
                                // provide a binding of the selected color to the selected index, white to all others
                                // if a selected color has been set
                                for ((index, li) in lw.listItemChildren.withIndex()) {
                                    bindSelectionColor(li, lw, index)
                                }
                                newItem.fireEvent(ListItemSelected(i, newItem[ListWidgetItem]?.data, true, mre))
                                true
                            }
                            newItem.handleEvent<WidgetMousePressEvent> { mpe -> true }
                        }
                        newItem.onEventDo<WidgetMouseEnterEvent> { mee ->
                            ws.fireEvent(ListItemMousedOver(i, newItem[ListWidgetItem]?.data, mee).withWidget(newItem))
                        }
                        bindSelectionColor(newItem, lw, i)

                        lw.listItemChildren.add(newItem)
                        lw.onListItemChildCreated(newItem)

                        if (i != 0) {
                            val axis = if (lw.horizontal) {
                                Axis.X
                            } else {
                                Axis.Y
                            }

                            lw.separatorArchetypeIdentifier.ifLet { sepArch ->
                                val separator = ws.createWidget(w, sepArch)
                                separator.identifier = "${w.identifier}.separator[${i - 1}]"
                                lw.separatorChildren.add(separator)
                                separator.position[axis] = WidgetPosition.Relative(lw.listItemChildren[i - 1], lw.gapSize)
                                newItem.position[axis] = WidgetPosition.Relative(separator, lw.gapSize)
                            }.orElse {
                                newItem.position[axis] = WidgetPosition.Relative(lw.listItemChildren[i - 1], lw.gapSize)
                            }
                        }
                    }

                    if (value != null) {
                        val childItem = lw.listItemChildren[i]
//                        childItem.bind(lw.targetBinding, value)
                        childItem.bindPointer(lw.targetBinding, "${lw.sourceBinding}[$i]", transform = null, forceUpdate = true)
                        w.buildBindingContext(null, false).resolve("${lw.sourceBinding}[$i]")
                    }
                }

                for (i in boundSrcValue.size until lw.listItemChildren.size) {
                    lw.listItemChildren[i].destroy()
                    lw.separatorChildren.getOrNull(i - 1)?.destroy()
                }

                while (lw.listItemChildren.size > boundSrcValue.size) {
                    lw.listItemChildren.removeLast()
                }
                while (lw.separatorChildren.isNotEmpty() && lw.separatorChildren.size >= boundSrcValue.size) {
                    lw.separatorChildren.removeLast()
                }
            } else {
                Noto.warn("non-list value provided as binding for list widget : ${lw.sourceBinding}")
            }
        }

        lw.selectionBinding?.let { sb ->
            if (sb.update(ctx) || listItemDataMaybeChanged) {
                val sel = sb()
                val selIndex = lw.listItemData.indexOf(sel)
                if (selIndex == -1) {
//                    Noto.warn("selectedItem binding provided value not in data : $sel out of ${lw.listItemData}")
                } else {
                    selectIndex(w, lw, selIndex, false, null)
                }
            }
        }
    }

    fun selectIndex(w: Widget, lw: ListWidget, idx: Int, explicit: Boolean, event: DisplayEvent?) {
        if (lw.selectedIndex != idx) {
            if (idx >= 0 && idx < lw.listItemChildren.size) {
                lw.selectedIndex = idx
                for ((index, li) in lw.listItemChildren.withIndex()) {
                    bindSelectionColor(li, lw, index)
                }
                w.windowingSystem.fireEvent(ListItemSelected(idx, lw.listItemData[idx], explicit, event).withWidget(w))
            } else {
                Noto.err("Attempt to set invalid index on list widget: $idx")
            }
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val lw = w[ListWidget] ?: return false

        return when (event) {
            is SelectListItem -> {
                selectIndex(w, lw, event.index, event.explicit, event)
                true
            }
            is WidgetKeyPressEvent -> {
                when (event.key) {
                    Key.Up -> {
                        if (lw.selectable) {
                            selectIndex(w, lw, ((lw.selectedIndex ?: 1000000) - 1).clamp(0, lw.listItemChildren.size - 1), explicit = false, event)
                            true
                        } else {
                            false
                        }
                    }
                    Key.Down -> {
                        if (lw.selectable) {
                            selectIndex(w, lw, ((lw.selectedIndex ?: -1000) + 1).clamp(0, lw.listItemChildren.size - 1), explicit = false, event)
                            true
                        } else {
                            false
                        }
                    }
                    Key.Enter -> {
                        lw.selectedIndex?.let { idx ->
                            if (idx >= 0 && idx < lw.listItemData.size) {
                                w.windowingSystem.fireEvent(ListItemSelected(idx, lw.listItemData[idx], true, event).withWidget(w))
                                true
                            } else {
                                false
                            }
                        } ?: false
                    }
                    else -> false
                }
            }
            is WidgetCharInputEvent -> {
                lw.typingSelection += event.char

                if (! selectByText(lw, w, event)) {
                    lw.typingSelection = "${event.char}"
                    if (!selectByText(lw, w, event)) {
                        lw.typingSelection = ""
                    }
                }

                true
            }
            else -> false
        }
    }

    private fun selectByText(lw: ListWidget, w: Widget, event: DisplayEvent) : Boolean {
        val toSel = lw.listItemChildren.indexOfFirst { c ->
            c.selfAndDescendants().any {
                it[TextDisplay]?.text?.invoke()?.plainText()?.lowercase()?.startsWith(lw.typingSelection.lowercase()) == true ||
                        it[AsciiTextWidget]?.text?.invoke()?.plainText()?.lowercase()?.startsWith(lw.typingSelection.lowercase()) == true
            }
        }

        return if (toSel != -1) {
            selectIndex(w, lw, toSel, false, event)
            true
        } else {
            false
        }
    }
}