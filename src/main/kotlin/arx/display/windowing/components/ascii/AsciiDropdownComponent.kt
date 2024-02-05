package arx.display.windowing.components.ascii

import arx.core.*
import arx.display.core.Key
import arx.display.windowing.*
import arx.display.windowing.components.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.DisplayEvent
import arx.engine.EntityData
import com.typesafe.config.ConfigValue
import kotlin.reflect.jvm.jvmErasure


data class AsciiDropdown (
    var items : Bindable<List<Any>> = ValueBindable.EmptyList(),
    var selectedItemBinding: PropertyBinding? = null,
    var showMenuArrow: Boolean = true,
    internal var textDisplaySettings : AsciiTextWidget = AsciiTextWidget(),
    internal var selectedItem: Any? = null,
    internal var onChangeSet : (Any?) -> Unit = { _ -> },
    internal var renderFn : Bindable<(Any) -> AsciiRichText> = ValueBindable { AsciiRichText(it.toString()) },
    internal var fetchFn : (() -> Any?)? = null,
    var cycleOnSelect: Boolean = false,
    internal var typingSelection : String = ""
) : DisplayData {
    lateinit var listWidget : Widget
    lateinit var displayWidget : Widget

    companion object : DataType<AsciiDropdown>( AsciiDropdown(),sparse = true ), FromConfigCreator<AsciiDropdown> {
        override fun createFromConfig(cv: ConfigValue?): AsciiDropdown? {
            if (cv == null) { return null }

            return if (cv["type"].asStr()?.lowercase() == "dropdown" || cv["type"].asStr()?.lowercase() == "asciidropdown") {

                AsciiDropdown(
                    items = untypedListBindable(cv["dropdownItems"]),
                    selectedItemBinding = propertyBinding(cv["selectedItem"], cv["twoWayBinding"].asBool() ?: false),
                    renderFn = bindableT(cv["itemTextFunction"]) { AsciiRichText(it.toString()) },
                    showMenuArrow = cv["showMenuArrow"].asBool() ?: true,
                    textDisplaySettings = AsciiTextWidget.createFromConfigIgnoringType(cv),
                    cycleOnSelect = cv["cycleOnSelect"].asBool() ?: defaultInstance.cycleOnSelect
                )
            } else {
                null
            }
        }
    }
    override fun dataType() : DataType<*> { return AsciiDropdown }

    fun copy() : AsciiDropdown {
        return copy(
            items = items.copyBindable(),
            selectedItemBinding = selectedItemBinding?.copy(),
            renderFn = renderFn.copyBindable(),
            textDisplaySettings = textDisplaySettings.copy()
        )
    }
}


object AsciiDropdownComponent : WindowingComponent {
    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(AsciiDropdown)
    }

    fun setSelectedItem(dd: AsciiDropdown, a : Any?) {
        if (dd.selectedItem != a) {
            if (a != null && !dd.items().contains(a)) {
                setSelectedItem(dd, null)
            } else {
                dd.selectedItem = a
                dd.onChangeSet(a)
                dd.displayWidget.bind("selectedItem", a?.let { dd.renderFn()(it) })
                val items = dd.items()
                val index = items.indexOf(a).clamp(0, items.size - 1)
                if (index < items.size) {
                    dd.listWidget.fireEvent(SelectListItem(index, false))
                }
            }
        }
    }

    fun updateItemList(dd: AsciiDropdown) {
        val renderFn = dd.renderFn()
        dd.items().map { renderFn(it) }.let { items ->
            dd.listWidget.bind("items", items)
            if (dd.selectedItemBinding?.twoWayBinding != true) {
                setSelectedItem(dd, dd.items().firstOrNull())
            }
        }
    }

    override fun configure(ws: WindowingSystem, w: WidgetArchetype, cv: ConfigValue) {
        // Note: this would probably require us to switch to customwidget to work properly
//        cv["label"]?.let {
//            w.widgetData.redirectBinding(it, "dropdownLabel")
//        }
    }

    override fun initializeWidget(w: Widget) {
        val dd = w[AsciiDropdown] ?: return

        dd.displayWidget = w.windowingSystem.createWidget(w, "AsciiDropdown.AsciiDropdownDisplay")
        dd.listWidget = w.windowingSystem.createWidget(w, "AsciiDropdown.AsciiDropdownList")
        dd.listWidget.y = WidgetPosition.Relative(w, 0, targetAnchor = WidgetOrientation.BottomLeft, selfAnchor = WidgetOrientation.TopLeft)
        dd.listWidget.x = WidgetPosition.Relative(w, 0, targetAnchor = WidgetOrientation.BottomLeft, selfAnchor = WidgetOrientation.BottomLeft)
        dd.listWidget.background = w.background
        w[AsciiBackground]?.let { ab -> dd.listWidget.attachData(ab) }

        dd.displayWidget.bind("selectedItem", dd.selectedItem?.let { dd.renderFn()(it) })

        if (dd.showMenuArrow) {
            dd.displayWidget.bind("menuArrow", "►")
        }

        if (w[FocusSettings] == null) {
            w.attachData(FocusSettings(acceptsFocus = true))
        }

        updateItemList(dd)

        propagateBindings(w, dd)
    }

    override fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        val dd = w[AsciiDropdown] ?: return null

        return dd.items().maxOfOrNull { i ->
            val rendered = dd.renderFn()(i)
            val raw = AsciiTextWidgetComponent.intrinsicSizeOf(
                AsciiTextLayout.Params(
                    text = rendered + tern(dd.showMenuArrow, "►", ""),
                    region = Recti(0,0,1000,10000),
                    defaultScale = dd.textDisplaySettings.scale.invoke() ?: w.windowingSystem.scale,
                    position = Vec2i(0,0),
                )
            )

            val extra = if (dd.showMenuArrow && axis == Axis2D.X) {
                dd.textDisplaySettings.scale() ?: w.windowingSystem.scale
            } else {
                0
            }

            raw[axis] + extra
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val dd = w[AsciiDropdown] ?: return

        if (dd.items.update(ctx)) {
            updateItemList(dd)
            if (w.width.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if (w.height.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }

        dd.textDisplaySettings.foregroundColor.update(ctx)
        dd.textDisplaySettings.backgroundColor.update(ctx)
        if (dd.textDisplaySettings.scale.update(ctx)) {
            if (w.width.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if (w.height.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }
        dd.textDisplaySettings.horizontalAlignment.update(ctx)

        var switchToSelection : Any? = null
        dd.selectedItemBinding?.update(ctx, { _, bound ->
            switchToSelection = bound
        },{ paramType, setter ->
            dd.onChangeSet = { av ->
                if (av == null) {
                    if (paramType.isMarkedNullable) {
                        setter(null)
                    }
                } else {
                    if (paramType.jvmErasure.java.isAssignableFrom(av.javaClass)) {
                        setter(av)
                    } else {
                        Noto.warn("unsupported type for two way binding on dropdown : $paramType <- ${av.javaClass}")
                    }
                }
            }
        }, { _, getter ->
            dd.fetchFn = getter
        }, warnOnAbsence = w.isVisible())

        switchToSelection?.let { s -> setSelectedItem(dd, dd.items().find { it == s }) }

        if (dd.renderFn.update(ctx)) {
            dd.displayWidget.bind("selectedItem", dd.selectedItem?.let { dd.renderFn()(it) })
        }


        propagateBindings(w, dd)
    }

    private fun propagateBindings(w: Widget, dd: AsciiDropdown) {
        w.bind("displayTextColor", dd.textDisplaySettings.foregroundColor())
        w.bind("scale", dd.textDisplaySettings.scale)
        w.bind("listUnselectedTextColor", dd.textDisplaySettings.foregroundColor())
        w.bind("listSelectedTextColor", RGBA(160,160,240,255))
        w.bind("horizontalAlignment", dd.textDisplaySettings.horizontalAlignment)
    }

    override fun update(windowingSystem: WindowingSystem) {
        for (w in windowingSystem.widgetsThatHaveData(AsciiDropdown)) {
            val dd = w[AsciiDropdown]!!

            if (w.isVisible()) {
                dd.fetchFn?.let { fn -> setSelectedItem(dd, fn()) }
            }
        }
    }


    fun handleListEvent(w: Widget, dd: AsciiDropdown, event: DisplayEvent) : Boolean {
        when (event) {
            is WidgetKeyPressEvent -> {
                when (event.key) {
                    Key.Enter -> {
                        w.windowingSystem.giveFocusTo(w.parent, event)
                        hideList(dd)
                        return true
                    }
                    Key.Escape -> {
                        if (w.showing()) {
                            if (w.windowingSystem.focusedWidget == w) {
                                w.windowingSystem.giveFocusTo(w.parent, event)
                            }
                            hideList(dd)
                            return true
                        }
                    }
                    else -> {}
                }
            }
            is FocusChangedEvent -> {
                if (! event.hasFocus) {
                    hideList(dd)
                }
            }
        }
        return false
    }

    fun showList(w: Widget, dd: AsciiDropdown) {
        dd.listWidget.showing = ValueBindable.True
        dd.listWidget.windowingSystem.updateGeometry()
        if (dd.listWidget.resY + dd.listWidget.resHeight > dd.displayWidget.windowingSystem.desktop.resClientHeight) {
            dd.listWidget.y = WidgetPosition.Relative(w, 0, targetAnchor = WidgetOrientation.TopLeft, selfAnchor = WidgetOrientation.BottomLeft)
        } else {
            dd.listWidget.y = WidgetPosition.Relative(w, 0, targetAnchor = WidgetOrientation.BottomLeft, selfAnchor = WidgetOrientation.TopLeft)
        }
        if (dd.showMenuArrow) {
            dd.displayWidget.bind("menuArrow", "▼")
        }
    }

    fun hideList(dd: AsciiDropdown) {
        dd.listWidget.showing = ValueBindable.False
        dd.typingSelection = ""
        if (dd.showMenuArrow) {
            dd.displayWidget.bind("menuArrow", "►")
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        w.parent?.get(AsciiDropdown)?.let { pdd ->
            if (w == pdd.listWidget) {
                if (handleListEvent(w, pdd, event)) {
                    return true
                }
            }
        }

        val dd = w[AsciiDropdown] ?: return false

        return when(event) {
            is ListItemSelected -> {
                dd.items().getOrNull(event.index)?.let { selected ->
                    setSelectedItem(dd, selected)
                    // if we've explicitly chosen a particular value then hide the list, but if we've
                    // simply moved up and down while navigating we update but leave the list open
                    if (event.explicit) {
                        w.takeFocus()
                        hideList(dd)
                        if (dd.cycleOnSelect && event.parentEvent !is WidgetMouseReleaseEvent) {
                            w.fireEvent(RequestCycleFocusEvent())
                        }
                    }
                    w.fireEvent(DropdownSelectionChanged(selected, event))
                }
                true
            }
            is WidgetMouseReleaseEvent -> {
                if (dd.listWidget.showing()) {
                    hideList(dd)
                } else {
                    showList(w, dd)
                    dd.listWidget.takeFocus()
                }
                true
            }
            is FocusChangedEvent -> {
//                if (event.hasFocus && event.parentEvent is WidgetMouseReleaseEvent) {
//                    dd.listWidget.showing = ValueBindable.True
//                    w.windowingSystem.giveFocusTo(dd.listWidget)
//                }
                false
            }
            is WidgetKeyPressEvent -> {
                when (event.key) {
                    Key.Enter -> {
                        showList(w, dd)
                        w.windowingSystem.giveFocusTo(dd.listWidget, event)
                        true
                    }
                    else -> false
                }
            }
//            is WidgetCharInputEvent -> {
//                if (dd.listWidget.showing()) {
//                    dd.typingSelection += event.char
//                    val toSel = dd.items().find { item -> dd.renderFn()(item).plainText().lowercase().startsWith(dd.typingSelection.lowercase()) }
//                    if (toSel != null) {
//                        setSelectedItem(dd, toSel)
//                    } else {
//                        dd.typingSelection = ""
//                    }
//
//                    true
//                } else {
//                    false
//                }
//            }
            else -> false
        }
    }
}