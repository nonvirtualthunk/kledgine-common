

Main {
  width: WrapContent
  height: WrapContent
  background.draw: true
  padding: [0,0,0]

  tabContext: true

  children {
    Label {
      type: TextDivider

      showing: "%(?addableListWidgetLabel)"
      text: "%(addableListWidgetLabel)"
      align: left

      y: 0
    }

    List {
      type: ListWidget

      x: 0
      y: 0 below Label

      padding: [0,0,0]

      width: WrapContentOrFill
      height: WrapContent
      background.draw: false
    }

    AddButton {
      type: Button

      x: match List
      y: 1 below List

      text: "+"

      buttonSignal: %(buttonSignal)

      width: Intrinsic
      height: Intrinsic

      tabbable: true
      acceptsFocus: true
    }

    AddMenu {
      type: ListWidget

      x: 0 right of AddButton
      y: match AddButton
      z: 1

      acceptsFocus: true
      selectable: true
      tabbable: true

      ignoreBounds: true

      showing: %(showAddMenu)

      width: WrapContent
      height: WrapContent

      selectByDefault: true

      selectedColor : [160,160,240,255]

      gapSize: 0

      listItemArchetype: AddableListWidget.AddMenuPick
      listItemBinding: "addMenuOptions -> addMenuOption"
    }
  }
}

AddMenuPick {
  text: "%(addMenuOption.name)"
  background.draw: false

  data: "%(addMenuOption)"

  color : "%(selection.color)"
}

//RemoveButtonWrapper {
//  type: Div
//
//  width: WrapContentOrFill
//  height: WrapContentOrFill
//
//  children {
//    RemovalButton {
//      type: Button
//      text: "X"
//
//      color: [255,50,50,255]
//
//      x: 0 from right
//      z: 5
//
//      signal: "RemoveFromAddableListWidget"
//    }
//  }
//}