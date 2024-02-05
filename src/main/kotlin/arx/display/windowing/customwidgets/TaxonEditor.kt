package arx.display.windowing.customwidgets

import arx.core.*
import arx.core.Taxonomy.UnknownThing
import arx.core.Taxonomy.taxon
import arx.display.windowing.*
import arx.display.windowing.components.ButtonPressEvent
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.ListWidget
import arx.display.windowing.components.ascii.AsciiTextSelector
import arx.display.windowing.components.ascii.TextCompletionSelected
import arx.engine.DisplayEvent
import com.typesafe.config.ConfigValue
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf

object TaxonEditor : CustomWidget {

    data class TaxonData(var namespaceOptions: List<String>?, var namespace : String, var name : String, var parents : List<Taxon>)

    abstract class Event : WidgetEvent(null)

    data class Create(val namespace: String, val name: String, val parents: List<Taxon>) : Event()
    data class Update(val taxon: Taxon, val namespace: String, val name: String, val parents: List<Taxon>) : Event()
    data class Delete(val taxon: Taxon) : Event()

    class Close : Event()


    override val name: String = "TaxonEditor"
    override val archetype: String = "TaxonWidgets.TaxonEditor"

    fun renderTaxon(t: Taxon) : String {
        if (t.namespace.isNotEmpty()) {
            return "Φ${t.namespace}.${t.name}"
        } else {
            return t.name
        }
    }

    override fun initialize(w: Widget) {
        w.bind("taxons", Taxonomy.taxonsByName.values.flatten())
        w.bind("renderTaxon", ::renderTaxon)
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        when (event) {
            is RefreshEntities -> {
                w.bind("taxons", Taxonomy.taxonsByName.values.flatten())
            }
        }

        if (! w.isVisible()) {
            return false
        }

        val data = w.data() ?: return false
        val dataTaxon = (data as? TaxonData) ?: return Noto.errAndReturn("non-taxon-data bound to taxon editor: ${w.data()}", false)
        val resolvedTaxon = taxon(dataTaxon.name, namespaceHint = dataTaxon.namespace, warnOnAbsence = false)

        when (event) {
            is TextCompletionSelected -> {
                if (event.explicit) {
                    val p = taxon(event.text, warnOnAbsence = false)
                    if (p != UnknownThing) {
                        if (! dataTaxon.parents.contains(p)) {
                            dataTaxon.parents += p
                            w.markForUpdate(RecalculationFlag.Bindings)
                        }
                    }
                    return true
                }
            }
            is ButtonPressEvent -> {
                when (event.buttonSignal) {
                    "Remove" -> {
                        val r = (event.data as? TaxonRef)?.invoke() ?: return Noto.errAndReturn("invalid remove signal in taxon editor", false)
                        dataTaxon.parents -= r
                        w.markForUpdate(RecalculationFlag.Bindings)
                        return true
                    }
                    "Create" -> {
                        w.fireEvent(Create(
                            namespace = dataTaxon.namespace,
                            name = dataTaxon.name,
                            parents = dataTaxon.parents
                        ))
                        return true
                    }
                    "Update" -> {
                        if (resolvedTaxon != UnknownThing) {
                            w.fireEvent(
                                Update(
                                    taxon = resolvedTaxon,
                                    namespace = dataTaxon.namespace,
                                    name = dataTaxon.name,
                                    parents = dataTaxon.parents
                                )
                            )
                        }
                        return true
                    }
                    "Delete" -> {
                        if (resolvedTaxon != UnknownThing) {
                            Delete(resolvedTaxon)
                        }
                        return true
                    }
                    "Close" -> w.fireEvent(Close())
                }
            }

        }
        return false
    }
}

fun Widget.bindToTaxonEditor(taxon: Taxon) {
    this.bind("taxon", TaxonEditor.TaxonData(null, taxon.namespace, taxon.name, taxon.parents))
}

fun Widget.bindToTaxonEditor(namespace : String = "", name: String = "", parents: List<Taxon> = emptyList()) {
    this.bind("taxon", TaxonEditor.TaxonData(null, namespace, name, parents))
}

fun Widget.bindToTaxonEditor(namespaceOptions : List<String>, name: String = "", parents: List<Taxon> = emptyList()) {
    if (namespaceOptions.isEmpty()) {
        bindToTaxonEditor(name = name, parents = parents)
    } else {
        this.bind("taxon", TaxonEditor.TaxonData(namespaceOptions, namespaceOptions.first(), name, parents))
    }
}





@Suppress("UNCHECKED_CAST")
object TaxonListWidget : CustomWidget {
    override val name: String = "TaxonListWidget"
    override val archetype: String = "TaxonWidgets.TaxonListWidget"


    data class AddTaxonEvent(val taxon: Taxon, val src: DisplayEvent) : WidgetEvent(src)
    data class RemoveTaxonEvent(val taxon: Taxon, val src: DisplayEvent) : WidgetEvent(src)

    override fun configure(ws: WindowingSystem, arch: WidgetArchetype, cv: ConfigValue) {
        val taxonsBinding = cv["taxons"]
        val possibleTaxonsBinding = cv["possibleTaxons"]

        if (taxonsBinding == null) {
            Noto.err("TaxonListWidget must specify taxons (${arch.identifier})")
            return
        }

        cv["renderFunction"]?.let { fncv -> arch.widgetData.redirectBinding(fncv, "renderTaxon") }

        val pat = extractBindingPattern(taxonsBinding) ?: return Noto.err("Invalid pattern for TaxonListWidget taxons : $taxonsBinding")
        arch.children["List"]!![ListWidget]?.apply {
            sourceBinding = pat
            targetBinding = "taxon"
        }

        arch.widgetData.dataProperty = propertyBinding(taxonsBinding, twoWay = cv["twoWayBinding"]?.asBool() ?: true)

        possibleTaxonsBinding?.let { pt ->
            arch.children["Selector"]!![AsciiTextSelector]?.let { ats ->
                ats.possibleSelections = untypedListBindable(pt)
            }
        }

        if (cv["showEditButtons"]?.asBool() == true) {
            arch.widgetData.bind("showEditButtons", true)
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val prop = w.dataProperty ?: return false


        fun opOnBackingList(mut: (MutableList<Taxon>) -> Unit, immut: (List<Taxon>) -> List<Taxon>) {
            var isMutableList = false
            if (prop.type.isSubtypeOf(typeOf<MutableList<*>>())) {
                isMutableList = true
            } else if (prop.type.isSubtypeOf(typeOf<List<*>>())) {
                // do nothing for immutable case
            } else {
                if (prop.type != typeOf<Unit>()) {
                    Noto.warn("TaxonListWidget, unexpected dataProperty case on removal: ${prop.type}")
                }
                return
            }

            val retrieved = prop.getter()
            if (isMutableList) {
                mut(retrieved as MutableList<Taxon>)
            } else {
                prop.setter(immut(retrieved as List<Taxon>))
            }
        }


        when (event) {
            is TextCompletionSelected -> {
                if (event.explicit) {
                    val p = (event.value as? Taxon) ?: return Noto.errAndReturn("Text completion for taxon list has non-taxon value ${event.value}", false)
                    if (p != UnknownThing) {
                        if (prop.twoWayBinding) {
                            opOnBackingList({ it.add(p) }, { it + p })
                            w.markForUpdate(RecalculationFlag.Bindings)
                        }
                        w.fireEvent(AddTaxonEvent(p, event))
                    }
                    return true
                }
            }
            is ButtonPressEvent -> {
                when (event.buttonSignal) {
                    "Remove" -> {
                        val r = (event.data as? TaxonRef)?.invoke() ?: return Noto.errAndReturn("invalid remove signal in taxon list", false)
                        if (prop.twoWayBinding) {
                            opOnBackingList({ it.remove(r) }, { it - r })
                            w.markForUpdate(RecalculationFlag.Bindings)
                        }
                        w.fireEvent(RemoveTaxonEvent(r, event))
                        return true
                    }
                }
            }

        }
        return false
    }
}