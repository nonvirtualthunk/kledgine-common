Main {
  type : Div
  width: WrapContent
  height: WrapContent

  padding: [0,0,0]

  background.draw: false

  children {
    Label {
      type: TextDisplay
      x: 0
      y: centered

      showing: "%(?asciiDropdownLabel)"
      text: "%(asciiDropdownLabel)"

      background.draw: false
      padding: [1,0,0]
    }

    Dropdown {
      type: AsciiDropdown

//      width: Intrinsic
//      height: Intrinsic
      x: 1 right of Label
      y: 0
    }
  }
}