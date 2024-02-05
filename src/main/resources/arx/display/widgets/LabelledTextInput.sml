
Main {
  type : Div
  width: WrapContent
  height: WrapContent

  padding: [0,0,0]

  background.draw: false

  tabbable: false

  children {
    Label {
      type: TextDisplay
      x: 0
      y: centered

      width: Intrinsic
      height: Intrinsic

      showing: "%(?label)"

      text: "%(label) "

      background.draw: false
      padding: [1,0,0]
      tabbable: false
    }

    Input {
      type: TextInput

      x: 0 right of Label
      y: 0

      background.draw: true
      padding: [1,0,0]
    }
  }
}