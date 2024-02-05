package arx.display.windowing.components.ascii

import arx.core.*
import arx.core.Taxonomy.UnknownThing
import arx.core.bindable.BindablePatternEval
import arx.display.windowing.Widget
import arx.display.windowing.WidgetPosition
import arx.display.windowing.WindowingComponent
import arx.display.windowing.WindowingSystem
import arx.display.windowing.components.*
import arx.display.windowing.customwidgets.LabelledAsciiDropdown
import arx.display.windowing.customwidgets.LabelledTextInput
import arx.display.windowing.customwidgets.testAsciiCustomWidget
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.EntityData
import com.typesafe.config.ConfigValue
import kotlin.reflect.KCallable
import kotlin.reflect.KType
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.typeOf


object AutoForm {
    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Ignore

    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class UseWidget(val archetype: String, val targetBinding: String)

    @Target(AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Taxon(val childrenOf: String)

}


object AsciiAutoForm : WindowingComponent {
    data class State (
        var dataProperty: PropertyBinding = PropertyBinding("test", false),
        var subWidgets: MutableMap<KCallable<*>, Widget> = mutableMapOf(),
        var activeType: KType? = null
    ) : DisplayData {
        companion object : DataType<State>( State(), sparse = true ), FromConfigCreator<State> {
            val types = setOf("asciiautoform", "autoform")

            override fun createFromConfig(cv: ConfigValue?): State? {
                if (types.contains(cv["type"].asStr()?.lowercase())) {
                    return propertyBinding(cv["dataProperty"], true)?.expectLet {
                        State(dataProperty = it)
                    }
                }
                return null
            }
        }
        override fun dataType() : DataType<*> { return State }
    }


    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(State)
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val state = w[State] ?: return

        state.dataProperty.update(ctx,
            { type, bound ->

                if (state.activeType != type) {
                    state.subWidgets.values.forEach { it.destroy() }
                    state.subWidgets.clear()
                    state.activeType = type

                    recreateSubwidgetsAsNecessary(w, state)
                }
            }, { _, _ -> }, { _, _ -> }
        )
    }

    private fun recreateSubwidgetsAsNecessary(w: Widget, state: State) {
        with(state) {
            var previous : Widget? = w.childWithIdentifier("SubLabel")


            when (activeType) {
                typeOf<String?>(), typeOf<AsciiRichText?>(), typeOf<String>(), typeOf<AsciiRichText>() -> {
                    w.createWidget("AsciiAutoForm.TextInput").apply {
                        bind("fieldName", "-")

                        selfAndDescendants().forEach { c ->
                            c[TextInput]?.textBinding = PropertyBinding("subValue", true)
                        }

                        previous?.let { p -> y = WidgetPosition.Relative(p, 1) }
                    }
                    return
                }
                else -> {

                }
            }

            val ordering = activeType?.jvmErasure?.primaryConstructor?.parameters?.associate {
                it.name to it.index
            } ?: emptyMap()

            activeType?.jvmErasure?.memberProperties?.sortedBy { ordering[it.name] ?: 100 }
                ?.filter { member ->
                    member.name != "identity" && ! member.hasAnnotation<AutoForm.Ignore>()
                }
                ?.forEach { member ->
                    val subBinding =  state.dataProperty.patternString + "." + member.name
                    val subPropertyBinding = { state.dataProperty.createMemberBinding(member) }

                    val c = when (val uwa = member.findAnnotation<AutoForm.UseWidget>()) {
                        null -> {
                            when (val rt = member.returnType) {
                                typeOf<String?>(), typeOf<AsciiRichText?>(), typeOf<String>(), typeOf<AsciiRichText>() -> w.createWidget("AsciiAutoForm.TextInput")
                                typeOf<Float?>(), typeOf<Double?>(), typeOf<Float>(), typeOf<Double>() -> w.createWidget("AsciiAutoForm.FloatInput")
                                typeOf<Int?>(), typeOf<Short?>(), typeOf<Long?>(), typeOf<Int>(), typeOf<Short>(), typeOf<Long>() -> w.createWidget("AsciiAutoForm.IntInput")
                                typeOf<Taxon>(), typeOf<TaxonRef>() -> w.createWidget("AsciiAutoForm.TaxonInput").apply {
                                    val taxons = member.findAnnotation<AutoForm.Taxon>()?.let {
                                        Taxonomy.childrenOf(t(it.childrenOf))
                                    } ?: Taxonomy.allTaxons()
                                    bind("possibleTaxons", taxons)
                                }
                                else -> {
                                    if (rt.isSubtypeOf(typeOf<List<*>>())) {
                                        w.createWidget("AsciiAutoForm.ListInput").apply {
                                            this.selfAndDescendants().forEach { c ->
                                                c[ListWidget]?.let { lw ->
                                                    lw.sourceBinding = subBinding
                                                    lw.targetBinding = "subValue"
                                                }
                                            }
                                        }
                                    } else if (rt.jvmErasure.java.isEnum) {
                                        w.createWidget("AsciiAutoForm.EnumInput").apply {
                                            bind("possibleEnumValue", rt.jvmErasure.java.enumConstants.toList())
                                        }
                                    } else {
                                        w.createWidget("AsciiAutoForm.Sub")
                                    }
                                }
                            }
                        }
                        else -> {
                            w.createWidget(uwa.archetype).apply {
                                redirectBinding(BindablePatternEval.Lookup(subBinding), uwa.targetBinding)
                            }
                        }
                    }

                    val othersToUpdateDataProps : List<Widget> = when (val cwk = c[CustomWidgetData]?.customWidgetKind) {
                        LabelledTextInput -> c.descendants().toList()
                        LabelledAsciiDropdown -> c.descendants().toList()
                        else -> emptyList()
                    }


                    for (sc in (othersToUpdateDataProps + c)) {
                        sc.dataProperty = subPropertyBinding()

                        sc[TextInput]?.textBinding = subPropertyBinding()
                        sc[AsciiTextSelector]?.selectedItemBinding = subPropertyBinding()
                        sc[AsciiDropdown]?.selectedItemBinding = subPropertyBinding()
                        sc[State]?.dataProperty = subPropertyBinding()
                    }

                    c.bind("fieldName", member.name)
                    c.identifier = member.name

                    previous?.let { p -> c.y = WidgetPosition.Relative(p, 1) }

                    previous = c
                }
        }
    }
}

internal enum class EnumTest {
    Alpha,
    Beta,
    Gamma
}
internal data class Bar(var nestedX : Int,
                        var nestedY: Int,
                        @AutoForm.Taxon(childrenOf = "fruit") var taxon: Taxon,
                        var enumTest: EnumTest = EnumTest.Alpha)
internal data class Foo(var name: String, var x : Int, var y: Float, var bar: Bar, var listTest: List<String>)
internal data class Wrapper(var a : Foo)

fun main (args: Array<String>) {
    Taxonomy.createTaxon("Tmp", "fruit", emptyList())
    Taxonomy.createTaxon("Tmp", "vegetable", emptyList())
    Taxonomy.createTaxon("Tmp", "Apple", listOf(t("fruit")))
    Taxonomy.createTaxon("Tmp", "Pear", listOf(t("fruit")))
    Taxonomy.createTaxon("Tmp", "Broccoli", listOf(t("vegetable")))

    registerCustomWidget(LabelledAsciiDropdown)
    registerCustomWidget(AddableListWidget)
    val v = Wrapper(a = Foo("no name yet", 1, 3.2f, Bar(0, 0, t("apple")), listOf("first")))
    testAsciiCustomWidget(LabelledTextInput, "AsciiAutoForm.Test") {
        bind("value", v)
    }
}