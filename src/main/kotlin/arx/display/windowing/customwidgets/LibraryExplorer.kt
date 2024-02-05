package arx.display.windowing.customwidgets

import arx.core.*
import arx.display.core.Key
import arx.display.windowing.*
import arx.display.windowing.components.ButtonPressEvent
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.FocusSettings
import arx.display.windowing.components.ascii.TextCompletionSelected
import arx.engine.DisplayEvent

data class SelectLibraryItem(val item: Taxon, val srcEvent: DisplayEvent) : WidgetEvent(srcEvent)

object LibraryExplorer : CustomWidget {
    override val name: String = "LibraryExplorer"
    override val archetype: String = "LibraryExplorer.LibraryExplorer"

    var currentlySelectedLibrary: Any? = null
    var lastSelectedLibrary: Any? = null

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        when (event) {
            is TextCompletionSelected -> {
                if (event.explicit) {
                    (event.value as? Item).expectLet {
                        w.fireEvent(SelectLibraryItem(it.identity, event))
                    } ?: return false
                }

                return true
            }
            is ButtonPressEvent -> {
                when (event.buttonSignal) {
                    "SelectLibrary" -> {
                        lastSelectedLibrary = event.data
                        currentlySelectedLibrary = event.data
                        w.bind("selectedLibrary", event.data)
                    }
                    "SelectItem" -> {
                        (event.data as? Item).expectLet {
                            w.fireEvent(SelectLibraryItem(it.identity, event))
                        } ?: return false
                    }
                    "CreateFromLibrary" -> return false
                    "ReturnToLibraryPicker" -> {
                        currentlySelectedLibrary = null
                        w.unbind("selectedLibrary")
                    }
                }
                return true
            }
            is WidgetKeyPressEvent -> {
                if (event.key == Key.LeftBracket && event.mods.ctrl) {
                    if (currentlySelectedLibrary != null) {
                        currentlySelectedLibrary = null
                        w.unbind("selectedLibrary")
                        return true
                    }
                } else if (event.key == Key.RightBracket && event.mods.ctrl) {
                    if (currentlySelectedLibrary == null) {
                        lastSelectedLibrary?.let { lib ->
                            w.bind("selectedLibrary", lib)
                            currentlySelectedLibrary = lib
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    fun String.stripKinds() : String {
        return if (endsWith("Kinds")) {
            substringBefore("Kinds") + "s"
        } else {
            this
        }
    }

    fun <T> wrap(l : arx.game.core.Library<T>, tToName : (T) -> String) : Library {
        return Library(l.javaClass.simpleName.stripKinds().camelCaseToSpaces(), l.toMap().map { (k,v) -> Item(tToName(v), k) }, l)
    }

    fun <T> wrap(l : arx.game.core.Library<T>) : Library {
        val items = l.toMap().map { (k,v) ->
            if (v is Named) {
                Item(v.name, k)
            } else {
                Item(k.name, k)
            }
        }
        return Library(l.javaClass.simpleName.stripKinds().camelCaseToSpaces(), items, l)
    }

    data class Item(val name: String, val identity: Taxon) {
        override fun toString(): String {
            return name
        }
    }
    data class Library(val name: String, val items: List<Item>, val type: Any)
}


fun main(args: Array<String>) {


    testAsciiCustomWidget(LibraryExplorer) {
        windowingSystem.desktop.attachData(FocusSettings(tabContext = true))

        val libraries = listOf(
            LibraryExplorer.Library(
                name = "Animals",
                items = listOf(
                    LibraryExplorer.Item(name = "Reindeer", identity = Taxonomy.createTaxon("Animals", "Reindeer", emptyList())),
                    LibraryExplorer.Item(name = "Moose", identity = Taxonomy.createTaxon("Animals", "Moose", emptyList())),
                    LibraryExplorer.Item(name = "Fox", identity = Taxonomy.createTaxon("Animals", "Fox", emptyList()))
                ),
                type = Taxonomy.createTaxon("", "Animal", emptyList())
            ),
            LibraryExplorer.Library(
                name = "Minerals",
                items = listOf(
                    LibraryExplorer.Item(name = "Pyrite", identity = Taxonomy.createTaxon("Minerals", "Pyrite", emptyList())),
                    LibraryExplorer.Item(name = "Iron", identity = Taxonomy.createTaxon("Minerals", "Iron", emptyList())),
                    LibraryExplorer.Item(name = "Limestone", identity = Taxonomy.createTaxon("Minerals", "Limestone", emptyList()))
                ),
                type = Taxonomy.createTaxon("", "Mineral", emptyList())
            )
        )
        bind("libraries", libraries)

        onEventDo<SelectLibraryItem> {
            println("Selected ${it.item}")
        }
    }
}