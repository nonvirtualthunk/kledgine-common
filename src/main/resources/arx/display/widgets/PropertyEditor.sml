

PropertyEditor {
  type: PropertyEditor

  width: WrapContent
  height: WrapContent

  background.draw: false

  children {
    Label {
      type: TextDisplay

      text: "%(property.label)"

      background.draw: false

      x: 0
      y: centered

      width: intrinsic
      height: intrinsic
    }

    Input {
      type: TextInput

      text: "%(property.data)"
      twoWayBinding: true


      x: 1 right of Label
      y: 0

      width: intrinsic
      height: intrinsic
    }
  }
}