

TaxonEditor {
  width: 200
  height: 60

  background.style: line

  data: "%(taxon)"

  tabContext: true

  children {

    IdentifierDiv {
      type: Div

      children {
        NamespaceDiv {
          type: Div

          children {
            NamespaceEditor {
              type: TextInput

              showing: "%(?!taxon.namespaceOptions)"

              width: "Intrinsic(30,160)"
              text: "%(taxon.namespace)"
              emptyText: "namespace"
            }

            NamespaceDropdown {
              type: Dropdown

              showing: "%(taxon.namespaceOptions.size) > 1"
              width: Intrinsic
              dropdownItems: "%(taxon.namespaceOptions)"
              selectedItem: "%(taxon.namespace)"
            }

            NamespaceDisplay {
              type: TextDisplay

              showing: "%(taxon.namespaceOptions.size) == 1"
              width: Intrinsic

              background.draw: false
            }
          }
        }

        Separator {
          type: TextDisplay
          x: 1 right of NamespaceDiv
          y: Centered

          text: "."

          background.draw: false
        }

        NameEditor {
          type: TextInput
          x: 1 right of Separator
          y: Centered

          width: "Intrinsic(20,100)"
          text: "%(taxon.name)"
          emptyText: "name"
        }
      }
    }

    ParentsHeading {
      type: TextDivider
      text: "is a"
      width: 50

      y: 2 below IdentifierDiv

      align: left

      background.draw: false
    }

    ParentsList {
      type: ListWidget

      x: 4
      y: 1 below ParentsHeading

      width: ExpandToParent
      height: WrapContent

      background.draw: false

      gapSize: 1

      listItemArchetype : TaxonWidgets.RemovableTaxonDisplay
      listItemBinding : taxon.parents -> taxon
    }

    ParentPicker {
      type: AsciiTextSelector

      x: 4
      y: 1 below ParentsList

      width: 80

      possibleSelections: "%(taxons)"
      renderFunction: "%(renderTaxon)"
      cycleOnSelect: false
      clearOnSelect: true
    }

    CreateButton {
      type: Button

      text: "Create"
      x: 4
      y: 1 from Bottom

      buttonSignal: "Create"
    }

    UpdateButton {
      type: Button

      text: "Update"
      x: 2 right of CreateButton
      y: match CreateButton

      buttonSignal: "Update"
    }

    DeleteButton {
      type: Button

      text: "Delete"
      x: 2 right of UpdateButton
      y: match CreateButton

      buttonSignal: "Delete"
    }

    CloseButton {
      type: Button

      text: "Close"
      x: 1 from right
      y: match CreateButton

      buttonSignal: "Close"
    }
  }
}

TaxonDisplay {
  type: TextDisplay

  text: "%(taxon.namespace).%(taxon.name)"
}

RemovableTaxonDisplay {
  type: Div
  width: WrapContent
  height: WrapContent

  children {
    TaxonDisplay : ${TaxonDisplay} {
      background.draw: false
      y: Centered

      text: "%(renderTaxon)%(taxon)"
    }

    EditButton {
      type: Button
      x: 1 right of TaxonDisplay
      text: "E"
      buttonSignal: "Edit"

      showing: "%(showEditButtons)"

      data: "%(taxon)"
    }

    RemoveButton {
      type: Button

      x: 1 right of EditButton
      y: Centered
      text: "X"
      buttonSignal: "Remove"

      data: "%(taxon)"

      tabbable: false

      color: [255, 50,50, 255]

      background.draw: false
    }
  }
}


# taxons: "%(x)
# possibleTaxons: "%(y)
# showEditButtons: true/false
TaxonListWidget {
  type: TaxonListWidget

  width: WrapContent
  height: WrapContent

  children {
    List {
      type: ListWidget

      width: 100%
      height: WrapContent

      background.draw: false

      listItemBinding: "taxons -> taxon"
      listItemArchetype: TaxonWidgets.RemovableTaxonDisplay
    }

    Selector {
      type: AsciiTextSelector

      y: 1 below List
      width: Intrinsic(80)

      possibleSelections: "%(possibleTaxons)"
      renderFunction: "%(renderTaxon)"
      cycleOnSelect: false
      clearOnSelect: true
    }
  }
}