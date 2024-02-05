Main {

  width: 100%
  height: 100%

  background.draw: false


  children {
    Label {
      type: TextDisplay

      x: Centered

      text: TEST
      background.draw: false
    }

    Image {
      type: ImageDisplay

      image: "display/images/cyber_goat_2.png"

      height: 41
      width: 80
      x: centered

      y: 1 below Label

      background.style: Internal
    }


    DebugStr {
      type: TextDisplay

      y: 0 from bottom
      z: 10
      width: ExpandToParent

      text: "%(debugStr)"
      showing: "%(?debugStr)"

      background.style: Line
    }
  }
}

