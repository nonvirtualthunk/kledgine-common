package arx.display.windowing.components

import arx.core.*
import arx.display.windowing.*
import arx.engine.*
import com.typesafe.config.ConfigValue
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf

data class DropdownItem(val value : Any, val text : RichText)

data class DropdownSelectionChanged(val selected : Any, val source : DisplayEvent) : WidgetEvent(source)

data class Dropdown(
    var items : List<DropdownItem>? = null,
    var selectedItem : DropdownItem? = null,
    var itemsBinding: String? = null,
    var selectedItemBinding: PropertyBinding? = null,
    internal var onChangeSet : (Any) -> Unit = { _ -> },
    internal var listWidget : Widget? = null,
    internal var toRichTextFn : Bindable<(Any) -> RichText> = ValueBindable { RichText(it.toString()) }
) : DisplayData {
    companion object : DataType<Dropdown>(Dropdown(),sparse = true), FromConfigCreator<Dropdown> {
        override fun createFromConfig(cv: ConfigValue?): Dropdown? {
            return if (cv["type"].asStr()?.lowercase() == "dropdown") {
                var itemsBindingPattern : String? = null
                var fixedChoices : List<String>? = null
                cv["dropdownItems"].ifLet { ddi ->
                    if (ddi.isStr()) {
                        ddi.asStr()?.let { t ->
                            stringBindingPattern.match(t).ifLet { (pattern) ->
                                itemsBindingPattern = pattern
                            }.orElse {
                                Noto.warn("Dropdown had a string 'dropdownItems' but it was not a binding pattern : $ddi")
                            }
                        }
                    } else if (ddi.isList()) {
                        fixedChoices = ddi.asList().mapNotNull { it.asStr() }
                    } else {
                        Noto.warn("Dropdown's 'dropdownItems' only supports lists and strings : $ddi")
                    }
                }.orElse {
                    Noto.warn("Dropdown widget defined without any 'dropdownItems' : $cv")
                }

                val selectedItemsBindingPattern = cv["selectedItem"]?.asStr()?.let { stringBindingPattern.match(it) }?.let { (b) -> PropertyBinding(b, true)}


                Dropdown(
                    itemsBinding = itemsBindingPattern,
                    items = fixedChoices?.map { DropdownItem(it, RichText(it)) },
                    selectedItemBinding = selectedItemsBindingPattern,
                    toRichTextFn = bindableT(cv["itemTextFunction"]) { RichText(it.toString()) }
                )
            } else {
                null
            }
        }
    }

    fun copy() : Dropdown {
        return copy(
            selectedItemBinding = selectedItemBinding?.copy(),
            toRichTextFn = toRichTextFn.copyBindable())
    }

    override fun dataType(): DataType<*> {
        return Dropdown
    }
}

object DropdownWindowingComponent : WindowingComponent {

    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(Dropdown)
    }

    override fun initializeWidget(w: Widget) {
        w[Dropdown]?.let { dd ->
            if (w[TextDisplay] == null) {
                w.attachData(TextDisplay())
            }
            if (dd.listWidget == null) {
                dd.listWidget = w.windowingSystem.createWidget(w, "Dropdown.DropdownList").apply {
                    showing = bindable(false)
                }

                dd.items?.map { it.text }?.let {
                    dd.listWidget?.bind("items", it)
                    if (it.isNotEmpty() && dd.selectedItemBinding?.twoWayBinding != true) {
                        dd.selectedItem = dd.items!!.first()
                        syncDisplayToData(w, dd, w[TextDisplay]!!)
                    }
                }
            }
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val dd = w[Dropdown] ?: return
        val td = w[TextDisplay] ?: return

        if (dd.toRichTextFn.update(ctx)) {
            syncDisplayToData(w, dd, td)
        }

        dd.itemsBinding?.let { binding ->
            ctx.resolve(binding)?.let { bound ->
                when(bound) {
                    is Iterable<*> -> {
                        dd.items = bound.mapNotNull { itemRaw ->
                            when (itemRaw) {
                                null -> null
                                is DropdownItem -> itemRaw
                                else -> DropdownItem(itemRaw, dd.toRichTextFn()(itemRaw))
                            }
                        }
                        dd.items?.map { it.text }?.let {
                            dd.listWidget?.bind("items", it)
                        }
                    }
                    else -> Noto.warn("Bound items for Dropdown is not an iterable")
                }
                syncDisplayToData(w, dd, td)
            }
        }

        dd.selectedItemBinding?.update(ctx, { _, bound ->
            if (bound != null) {
//            if (dd.selectedItem == null) {
                when (bound) {
                    is DropdownItem -> dd.selectedItem = bound
                    is String -> dd.selectedItem = dd.items?.find { it.value == bound } ?: dd.items?.find { it.text.plainText() == bound }
                    else -> dd.selectedItem = dd.items?.find { it.value == bound }
                }
//                if (dd.selectedItem == null) {
//                    if (w.showing()) {
//                        Noto.warn("binding for selected item did not match any items in the dropdown : $bound")
//                    }
//                }
                syncDisplayToData(w, dd, td)
//            }
            }
        }, { paramType, setter ->
            when (paramType) {
                typeOf<DropdownItem>() -> {
                    dd.onChangeSet = { ddi -> setter(ddi) }
                }
                else -> {
                    dd.onChangeSet = { av ->
                        if (paramType.jvmErasure.java.isAssignableFrom(av.javaClass)) {
                            setter(av)
                        } else {
                            Noto.warn("unsupported type for two way binding on dropdown : $paramType <- ${av.javaClass}")
                        }
                    }
                }
            }

            dd.selectedItem?.let {
                dd.onChangeSet(it.value)
            }
        }, { _, _ -> })
    }

    override fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        val dd = w[Dropdown] ?: return null
        val td = w[TextDisplay] ?: return null

        return dd.items?.maxOfOrNull { item ->
            TextWindowingComponent.intrinsicSizeFor(
                TextLayout.Params(
                    item.text,
                    Vec2i(0, 0),
                    Recti(0, 0, 1000, 1000),
                    td.font,
                ), axis
            )
        }
    }

    fun syncDisplayToData(w: Widget, dd: Dropdown, td: TextDisplay) {
        val transformedBase = dd.selectedItem?.text
        val transformed = if (transformedBase != null) {
            transformedBase
        } else {
            val rawValue = dd.selectedItemBinding?.lastBoundValue
            if (rawValue != null) {
                val fn = dd.toRichTextFn()
                fn(rawValue)
            } else {
                RichText("?")
            }
        }

        if (transformed != td.text()) {
            td.text = bindable(transformed)
            w.markForUpdate(RecalculationFlag.Contents)
            if (w.width.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if (w.height.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val dd = w[Dropdown] ?: return false
        val td = w[TextDisplay] ?: return false

        return when(event) {
            is ListItemSelected -> {
                dd.items?.get(event.index)?.let { selected ->
                    dd.selectedItem = selected
                    dd.listWidget?.showing = bindable(false)
                    syncDisplayToData(w, dd, td)
                    dd.onChangeSet(selected.value)
                    w.fireEvent(DropdownSelectionChanged(selected, event))
                }
                true
            }
            is WidgetMouseReleaseEvent -> {
                if (event.originWidget == w) {
                    dd.listWidget?.showing = bindable(true)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
}