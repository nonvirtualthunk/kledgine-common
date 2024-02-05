AsciiDropdownDisplay {
  width: 100%
  height: 100%

  padding : [0,0,0]
  background.draw: false

  children {
    MainText {
      x: 0
      text: "%(selectedItem)"

      color: "%(displayTextColor)"
      scale: "%(scale)"
      horizontalAlignment: "%(horizontalAlignment)"

      background.draw: false
      width: expand to ToggleIcon
      height: 100%
      padding : [0,0,0]

    }

    ToggleIcon {
      text: "%(menuArrow)"
      color: [128,128,128,255]
      scale: "%(scale)"

      width: intrinsic
      height: intrinsic

      padding: [0,0,0]

      background.draw: false
      x: 0 from right
    }
  }
}


AsciiDropdownList {
  type : ListWidget

  x: 0
  y: 0
  z : 4
  width: WrapContent
  height: WrapContent
  border.width: 0

  showing: false

  listItemArchetype: AsciiDropdown.AsciiDropdownItem
  listItemBinding: "items -> item"
  //  separatorArchetype : AsciiCardWidgets.TextOptionDivider
  selectable: true
  ignoreBounds: true

  background.draw: true
  background.scale: 1
  background.fill: true
  gapSize: 1

  acceptsFocus: true

  selectedColor : "%(listSelectedTextColor)"
  unselectedColor: "%(listUnselectedTextColor)"
}

AsciiDropdownItem {
  padding: [1,0,0]

  background.draw : false
  width: intrinsic
  height: intrinsic
  horizontalAlignment: "%(horizontalAlignment)"

  scale: "%(scale)"
  text : "%(item)"
  color : "%(selection.color)"
}