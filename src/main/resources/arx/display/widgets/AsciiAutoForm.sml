BaseTextInput {
  type: LabelledTextInput

  label: "%(fieldName)"
  text: "%(value)"

  twoWayBinding: true
  tabbable: true

  selectAllOnFocus: true
}



FloatInput : ${BaseTextInput} {
  floatOnly: true

  width: Intrinsic(8)
}

IntInput : ${BaseTextInput} {
  integerOnly: true

  width: Intrinsic(8)
}

TextInput : ${BaseTextInput} {
  width: ExpandToParent
}

TaxonInput {
  type: AsciiTextSelector

  label: "%(fieldName)"

  width: 80

  selectedItem: "%(value)"
  possibleSelections: "%(possibleTaxons)"
  renderFunction: "%(renderItem)"
}

EnumInput {
  type: LabelledAsciiDropdown


  label: "%(fieldName)"

  children.Dropdown.padding: [1,0,0]

  dropdownItems: "%(possibleEnumValue)"
  selectedItem: "%(value)"
}

ListInput {
  type: AddableListWidget

//  label: "%(fieldName)"

  width: ExpandToParent
  height: WrapContent

  listItemArchetype: AsciiAutoForm.Sub
  listItemBinding: "values -> subValue"

  dataProperty: "%(values)"
}

TextDisplay {
  type: TextDisplay

  text: "%(fieldName)"
}

Sub {
  type: AsciiAutoForm

  width: ExpandToParent
  height: WrapContent

  background.draw: true

  dataProperty : "%(subValue)"

  tabContext: true

  children {
    SubLabel {
      type: TextDivider
      text: "%(fieldName)"
      align: left
    }
  }
}

Test {
  type: AsciiAutoForm

  x: centered
  y: centered

  width: 100
  height: WrapContent

  tabContext: true

  background.draw: true

  dataProperty : "%(value.a)"
}