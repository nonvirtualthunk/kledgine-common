{

  Divider {
    padding: [0,0,0]
    background.draw: false
  }

  TextDisplay {
    padding: [2,2,0]
  }

  TextInput {
    padding: [2,2,0]
  }

  Dropdown {
    padding: [2,2,0]
  }

  ImageDisplay {
    background {
      image: "ui/singlePixelBorder.png"
      drawCenter: false
    }
  }

  Button {
    pressedImage: {
      image : "ui/buttonBackground.png"
      color : [128,128,128,255]
    }

    background {
      image: "ui/buttonBackground.png"
      color : [255,255,255,255]
    }
  }
}