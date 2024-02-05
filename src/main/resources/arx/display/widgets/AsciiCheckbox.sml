
Main {
  type : Div
  width: WrapContent
  height: WrapContent
  background.draw: false

  padding: [0,0,0]

  children {
    Label {
      type: TextDisplay
      y: centered

      showing: "%(?label)"

      text: "%(label)"

      background.draw: false
      padding: [1,0,0]
    }

    Checkbox {
      type: TextDisplay

      width: Intrinsic
      height: Intrinsic
      x: 0 right of Label

      text: "%(checked)"

      color: "%(checkedColor)"

      background.draw: true
      background.style: internal
      background.scale: 1
      padding: [0,0,0]

      tabbable: true
    }
  }
}