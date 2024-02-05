

# libraries ->
#   name
#   items
#     name
#   type


LibraryExplorer {
  type: LibraryExplorer

  useArrowNavigation: false

  width: 100%
  height: 100%


  children {
    LibraryPicker: ${LibraryPicker}
    LibraryViewer: ${LibraryViewer}
  }
}


LibraryPicker {
  type: Div
  width: 100%
  height: 100%

  tabContext: true
  acceptsFocus: false

  showing: "%(!?selectedLibrary)"

  scrollBar: true

  children {
    LibraryLabel {
      type: TextDisplay
      text: "Libraries"

      x: Centered
      y: 0

      scale : 4
      background.draw: false
    }

    TypesList {
      type : ListWidget

      x: Centered
      y: 1 below LibraryLabel
      width: WrapContent
      height: WrapContent

      listItemArchetype: LibraryExplorer.LibraryPickerItem
      listItemBinding: "libraries -> library"

      gapSize: 1

      background.draw: false
    }
  }

}

LibraryPickerItem {
  type: Div
  width: WrapContent
  height: WrapContent

  children {
    NameDisplay {
      type: Button
      text: "%(library.name)"

      signal: "SelectLibrary"
      data: "%(library)"

      scale: 3
    }

    SearchButton {
      type: Button

      x: 1 right of NameDisplay
      y: centered
      text: "-○"

      buttonSignal: "Search"
      data: "%(library)"

      tabbable: true
    }

    CreateButton {
      type: Button

      x: 1 right of SearchButton
      y: centered
      text: "*"

      buttonSignal: "CreateFromLibrary"
      data: "%(library.type)"

      tabbable: true
    }
  }
}



LibraryViewer {
  type: Div
  width: 100%
  height: 100%

  showing: "%(?selectedLibrary)"

  scrollBar: true

  tabContext: true
  acceptsFocus: false

  children {
    LibraryLabel {
      type: TextDisplay

      text: "%(selectedLibrary.name)"

      x: centered
      y: 0

      scale: 4

      background.draw: false
    }

    BackButton {
      type: Button

      text: "X"

      x: 0 from right
      signal: "ReturnToLibraryPicker"
    }

    SearchSection {
      type: Div

      x: centered
      y: 1 below LibraryLabel

      children {
        SearchLabel {
          type: TextDisplay

          text: "Search:"
          background.draw: false
          y: centered
        }

        SearchBar {
          type: AsciiTextSelector

          x : 1 right of SearchLabel

          width: 100
          clearOnSelect: true
          cycleOnSelect: true

          possibleSelections: "%(selectedLibrary.items)"
          renderFunction: "%(renderLibraryItem)"
        }

        CreateButton {
          type: Button

          x: 1 right of SearchBar
          y: centered
          text: "*"

          buttonSignal: "CreateFromLibrary"
          data: "%(selectedLibrary.type)"

          tabbable: true
        }
      }
    }

    ItemList {
      type: ListWidget

      x: Centered
      y: 1 below SearchSection
      width: WrapContent
      height: WrapContent

      listItemArchetype: LibraryExplorer.LibraryViewerItem
      listItemBinding: "selectedLibrary.items -> item"

      gapSize: 1
    }
  }
}

LibraryViewerItem {
  type: Div

  x: Centered

  children {
    Name {
      type: Button

      text: "%(item.name)"
      data: "%(item)"

      signal: "SelectItem"

      tabbable: true
      acceptsFocus: true
    }
  }
}