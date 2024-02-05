package arx.display.windowing.customwidgets

import arx.display.windowing.components.registerCustomWidget

object AsciiCustomWidgets {
    fun registerCustomWidgets() {
        registerCustomWidget(AsciiCheckbox)
        registerCustomWidget(AsciiColorPicker)
        registerCustomWidget(TextDivider)
        registerCustomWidget(LabelledTextInput)
        registerCustomWidget(LabelledAsciiDropdown)
        registerCustomWidget(TaxonEditor)
        registerCustomWidget(LibraryExplorer)
        registerCustomWidget(AmgEditor)
        registerCustomWidget(TaxonListWidget)
    }
}