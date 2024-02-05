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


data class TextCompletionSelected(val text: String, val value: Any, val explicit: Boolean, val src: DisplayEvent?) : WidgetEvent(src)

data class AsciiTextSelector(
    var possibleSelections: Bindable<List<Any>> = ValueBindable.EmptyList(),
    var possibleSelectionStrings: List<String> = emptyList(),
    var selectedSelection: Int = 0,
    var matchingCompletions: List<Pair<String, Any>> = emptyList(),
    var selectedItemBinding: PropertyBinding? = null,
    var onChangeSet: (Any) -> Unit = {},
    var renderFn: Bindable<(Any?) -> String> = ValueBindable { x -> x.toString() },
    var cycleOnSelect: Boolean = false,
    var clearOnSelect: Boolean = false,
    var fillInputOnSelect: Boolean = true,
    var initialLoadItem: Any? = null,
    var selectedItemGetter: (() -> Any?)? = null,
    var onlyUpdateOnExplicit: Boolean = true // removed because this requires more thought to interact properly with all the other pieces
) : DisplayData {
    lateinit var inputWidget: Widget

    companion object : DataType<AsciiTextSelector>(AsciiTextSelector(),sparse = true), FromConfigCreator<AsciiTextSelector> {
        override fun createFromConfig(cv: ConfigValue?): AsciiTextSelector? {
            if (cv == null || cv["type"]?.asStr()?.lowercase() != "asciitextselector") {
                return null
            }

            return AsciiTextSelector(
                possibleSelections = untypedListBindable(cv["possibleSelections"]),
                selectedItemBinding = propertyBinding(cv["selectedItem"], twoWay = true),
                cycleOnSelect = cv["cycleOnSelect"]?.asBool() ?: defaultInstance.cycleOnSelect,
                clearOnSelect = cv["clearOnSelect"]?.asBool() ?: defaultInstance.clearOnSelect,
                fillInputOnSelect = cv["fillInputOnSelect"]?.asBool() ?: defaultInstance.fillInputOnSelect,
                renderFn = bindableT(cv["renderFunction"]) { it.toString() },
                onlyUpdateOnExplicit = cv["onlyUpdateOnExplicit"]?.asBool() ?: defaultInstance.onlyUpdateOnExplicit
            )
        }
    }

    override fun dataType(): DataType<*> {
        return AsciiTextSelector
    }

    fun copy(): AsciiTextSelector {
        return copy(
            possibleSelections = possibleSelections.copyBindable(),
            selectedItemBinding = selectedItemBinding?.copy(),
            renderFn = renderFn.copyBindable()
        )
    }
}


object AsciiTextSelectorComponent : WindowingComponent {

    override val configArchetypesByType: Map<String, String> = mapOf("AsciiTextSelector".lowercase() to "AsciiTextSelector.AsciiTextSelector")

    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(AsciiTextSelector)
    }

    override fun propagateConfigToChildren(w: WidgetArchetype): Set<String> {
        return emptySet()
    }

    override fun configure(ws: WindowingSystem, w: WidgetArchetype, cv: ConfigValue) {
        if (w.data.firstOfType<AsciiTextSelector>() != null) {
            cv["label"]?.let {
                w.widgetData.redirectBinding(it, "textSelectorLabel")
            }
        }
    }

    override fun initializeWidget(w: Widget) {
        val ats = w[AsciiTextSelector] ?: return
        ats.inputWidget = w.descendantWithIdentifier("Input")!!

        ats.inputWidget.onEvent {
            var ret = false
            when (it) {
                is WidgetKeyPressEvent -> {
                    if (it.key == Key.Escape) {
                        if (ats.matchingCompletions.isNotEmpty()) {
                            clearPossibleSelections(w, w[AsciiTextSelector]!!)
                            ret = true
                        }
                        w.fireEvent(RequestCycleFocusEvent())
                    }
                }
            }
            ret
        }

        w[FocusSettings]?.let { fs ->
            if (fs.tabbable == true) {
                fs.tabbable = false
                Noto.warn("Don't set 'tabbable' directly on an ascii text selector, the sub-pieces themselves are what is tabbable")
            } else if (fs.tabbable == false) {
                w.descendants().forEach { w -> w[FocusSettings]?.tabbable = false }
            }
        }
//        w[FocusSettings]?.tabOffset?.let { ats.inputWidget[FocusSettings]?.tabOffset = it }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val ats = w[AsciiTextSelector] ?: return


        val renderUpdated = ats.renderFn.update(ctx)
        val possibleSelectionsUpdated = ats.possibleSelections.update(ctx)

        ats.selectedItemBinding?.update(ctx, { _, v ->
//            ats.inputWidget.setTextInputContents(ats.renderFn()(v))
            if (ats.matchingCompletions.indexOfFirst { it.second == v } == ats.selectedSelection) {
                // do nothing
            } else {
                ats.possibleSelections().indexOfFirst { it == v }.takeIf { it != -1 }.ifLetDo { selIdx ->
                    ats.selectedSelection = selIdx
                    updateSelectionColors(w, ats)
                    ats.possibleSelectionStrings.getOrNull(ats.selectedSelection)?.let {
                        ats.inputWidget.setTextInputContents(it, skipEvents = true)
                    }
                }.orElse {
                    ats.initialLoadItem = v
                }
            }

        }, { paramType, setter ->
            ats.onChangeSet = { v ->
                if (paramType.jvmErasure.java.isAssignableFrom(v.javaClass)) {
                    setter(v)
                } else {
                    Noto.warn("unsupported type for two way binding on text selector : $paramType <- ${v.javaClass}")
                }
            }
        }, { paramType, getter ->
            ats.selectedItemGetter = getter
        }, warnOnAbsence = w.isVisible())

        if (possibleSelectionsUpdated || renderUpdated) {
            val previouslyEmpty = ats.possibleSelectionStrings.isEmpty()
            ats.possibleSelectionStrings = ats.possibleSelections().map { ats.renderFn()(it) }

            updatePossibleSelections(w, ats)

            if (previouslyEmpty) {
                ats.initialLoadItem?.let { il ->
                    ats.possibleSelections().indexOfFirst { it == il }.takeIf { it != -1 }?.let { selIdx ->
                        ats.selectedSelection = selIdx
                        updateSelectionColors(w, ats)
                        ats.inputWidget.setTextInputContents(ats.renderFn()(il), skipEvents = true)
                    }
                }
            }

            w.markForFullUpdateAndAllDescendants()
        }
    }

    fun matchDistance(input: String, poss: String): Int? {
        return if (poss.isEmpty()) {
            null
        } else {
            var jumps = 0
            var i = 0
            var j = 0

            if (poss.contains(input, ignoreCase = true)) {
                return 0
            }

            while (i < input.length) {
                var tmpJumps = 0
                while (j >= poss.length || poss[j].lowercase() != input[i].lowercase()) {
                    if (i != 0) {
                        tmpJumps += 1
                        if (j < poss.length && poss[j].lowercase() == input[i - 1].lowercase()) {
                            tmpJumps = 0
                        }
                    }

                    j++
                    if (j >= poss.length) {
                        return null
                    }
                }
                jumps += tmpJumps
                j++
                i++
            }
            jumps
        }
    }

    fun matchingCompletions(w: Widget, ats: AsciiTextSelector): List<Pair<String, Any>>? {
        val fw = w.windowingSystem.focusedWidget
        if (fw == null || !w.hasDescendant(fw)) {
            return null
        }

        val raw = ats.possibleSelectionStrings
        val rawItems = ats.possibleSelections()

        val ti = ats.inputWidget[TextInput]
        val input = ti?.textData?.toString()
        if (ti?.isAllSelected() == true || input.isNullOrEmpty()) {
            return raw.zip(rawItems)
        }

        val matching = mutableListOf<Triple<String, Any, Int>>()
        for (i in raw.indices) {
            matchDistance(input, raw[i])?.let { dist ->
                matching.add(Triple(raw[i], rawItems[i], dist))
            }
        }
        matching.sortBy { it.third }
        return matching.map { it.first to it.second }
    }

    data class PossibleCompletion(
        val text: AsciiRichText,
        val color: RGBA?,
    )

    fun clearPossibleSelections(w: Widget, ats: AsciiTextSelector) {
        ats.matchingCompletions = emptyList()
        ats.selectedSelection = 0
        updateSelectionColors(w, ats)
    }

    fun updatePossibleSelections(w: Widget, ats: AsciiTextSelector) {
        val matching = matchingCompletions(w, ats)
        ats.matchingCompletions = matching ?: emptyList()
        ats.selectedSelection = ats.selectedSelection.clamp(0, ats.matchingCompletions.size - 1)
        updateSelectionColors(w, ats)
    }

    fun updateSelectionColors(w: Widget, ats: AsciiTextSelector) {
        val bindings = ats.matchingCompletions.mapIndexed { i, m ->
            val c = if (ats.selectedSelection == i) {
                RGBA(100, 128, 200, 255)
            } else {
                RGBA(255, 255, 255, 255)
            }

//            var i = 0
//            var j = 0
//            while (i < input.length) {
//                while (m[j].lowercase() != m[i].lowercase()) {
//                    j++
//                    if (j >= poss.length) {
//                        return false
//                    }
//                }
//                i++
//            }
//            true
//
            PossibleCompletion(AsciiRichText(m.first), c)
        }
        w.bind("possibleCompletions", bindings.ifEmpty { null })
    }

    fun activeCompletion(ats: AsciiTextSelector): Pair<String, Any>? {
        return ats.matchingCompletions.let { matching ->
            (matching.getOrNull(ats.selectedSelection) ?: matching.firstOrNull())
        }
    }

    fun fireSelectionEvent(w: Widget, ats: AsciiTextSelector, explicit: Boolean, src: DisplayEvent?) {
        activeCompletion(ats)?.let { matched ->
            w.fireEvent(TextCompletionSelected(matched.first, matched.second, explicit, src))
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val ats = w[AsciiTextSelector] ?: return false

        return when (event) {
            is ListItemSelected -> {
                ats.selectedSelection = event.index

                explicitlySelectCompletion(w, ats, event)

                true
            }
            is GiveUpFocusEvent -> {
                clearPossibleSelections(w, ats)
                false
            }
            is TextDataChanged -> {
                updatePossibleSelections(w, ats)
                nonExplicitlySelectCompletion(w, ats, event)
                true
            }

            is FocusChangedEvent -> {
                updatePossibleSelections(w, ats)
                true
            }
            is WidgetKeyPressEvent -> {
                when (event.key) {
                    Key.Down -> {
                        if (ats.selectedSelection < ats.matchingCompletions.size - 1) {
                            ats.selectedSelection = (ats.selectedSelection + 1).clamp(0, ats.matchingCompletions.size)
                            updatePossibleSelections(w, ats)
                            nonExplicitlySelectCompletion(w, ats, event)
                            true
                        } else {
                            false
                        }
                    }
                    Key.Up -> {
                        if (ats.selectedSelection > 0) {
                            ats.selectedSelection = (ats.selectedSelection - 1).clamp(0, ats.matchingCompletions.size)
                            updatePossibleSelections(w, ats)
                            nonExplicitlySelectCompletion(w, ats, event)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            is TextInputEnter -> {
                explicitlySelectCompletion(w, ats, event)

                true
            }
            is TextCompletionSelected -> {
                false
            }
            else -> false
        }
    }

    private fun nonExplicitlySelectCompletion(w: Widget, ats: AsciiTextSelector, event: DisplayEvent) {
        if (! ats.onlyUpdateOnExplicit) {
            activeCompletion(ats)?.let { t ->
                ats.onChangeSet(t.second)
            }
        }

        fireSelectionEvent(w, ats, explicit = false, event)
    }

    private fun explicitlySelectCompletion(w: Widget, ats: AsciiTextSelector, event: DisplayEvent) {
        activeCompletion(ats)?.let { t ->
            ats.onChangeSet(t.second)

            if (ats.fillInputOnSelect) {
                ats.inputWidget.setTextInputContents(t.first)
            }
        }

        fireSelectionEvent(w, ats, explicit = true, event)

        if (ats.cycleOnSelect) {
            clearPossibleSelections(w, ats)
            w.fireEvent(RequestCycleFocusEvent())
        }

        if (ats.clearOnSelect) {
            ats.inputWidget.setTextInputContents("")
            updatePossibleSelections(w, ats)
        }
    }

    override fun update(windowingSystem: WindowingSystem) {
        for (w in windowingSystem.widgetsThatHaveData(AsciiTextSelector)) {
            if (! w.isVisible()) {
                continue
            }
            val ats = w[AsciiTextSelector]!!

            if (ats.inputWidget.hasFocus()) {
                continue
            }

            ats.selectedItemGetter?.let { fn ->
                val v = fn()
                val cur = ats.possibleSelections().getOrNull(ats.selectedSelection)
                if (cur != v) {
                    ats.possibleSelections().indexOfFirst { it == v }.takeIf { it != -1 }.ifLetDo { selIdx ->
                        ats.selectedSelection = selIdx
                        updateSelectionColors(w, ats)
                        ats.possibleSelectionStrings.getOrNull(ats.selectedSelection)?.let {
                            ats.inputWidget.setTextInputContents(it, skipEvents = true)
                        }
                    }
                }
            }
        }
    }
}