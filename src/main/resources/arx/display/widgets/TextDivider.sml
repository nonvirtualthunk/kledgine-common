

Main {
  type: Div
  width: ExpandToParent
  height: WrapContent
  background.draw: false

  padding: [0,0,0]

  children {
    Text {
      type: TextDisplay
      x: centered
      z: 0
    }

    Divider {
      type: Divider

      z : 0

      width: 100%
      height: intrinsic
      horizontal: true

      background.draw: false
      y: centered
    }
  }
}