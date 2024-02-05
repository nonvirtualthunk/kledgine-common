Label {
  type: TextDisplay
  background.draw: false

  width: Intrinsic
  height: Intrinsic

  padding: [0,0]
}

Slider {
  height: 4

  background.scale: 1
  background.style: Line
}

AsciiColorPicker {

  width: 100%
  height: WrapContent

  padding: [1,1]

  children {


    HueLabel : ${Label} {
      text: "Hue"

      x: 0
      y: 0
    }

    Hue : ${Slider} {
      type: HSLSlider
      channel: Hue
      width: expand to RightBar(3)

      color: "%(state.selectedColor)"

      y: 0 below HueLabel
    }

    SaturationLabel : ${Label} {
      text: "Saturation"

      x: 0
      y: 0 below Hue
    }

    Saturation : ${Slider} {
      type: HSLSlider
      channel: Saturation
      width: expand to RightBar(3)

      color: "%(state.selectedColor)"

      y: 0 below SaturationLabel
    }

    LightnessLabel : ${Label} {
      type: TextDisplay
      text: "Lightness"

      x: 0
      y: 0 below Saturation
    }

    Lightness : ${Slider} {
      type: HSLSlider
      channel: Lightness
      width: expand to RightBar(3)

      color: "%(state.selectedColor)"

      y: 0 below LightnessLabel
    }

    RightBar {
      type: Div

      width: WrapContent
      height: 100%

      x: 0 from right
      y: 0

      children {
        FlipActive : {
          type: Button
          x: 0
          y: 0

          background.scale: 1
          background.style: line

          text: "↔"
          showing: "%(state.dualColorMode)"

          signal: "FlipActiveColor"
        }

        ClearButton {
          type: Button
          x: 1 right of FlipActive
          y: 0

          background.style: line

          text: "0"
          showing: "%(state.dualColorMode)"

          signal: "ClearColor"
        }

        ColorDisplay : ${Slider} {
          type: HSLSlider
          channel: None
          width: 8
          height: -2

          showing: "%(!state.dualColorMode)"

          color: "%(state.selectedColor)"

          y: 0 from bottom
          x: centered
        }

        ForegroundDisplay : ${Slider} {
          type: HSLSlider
          channel: None
          width: 8
          height: 5

          showing: "%(state.dualColorMode)"

          color: "%(state.foregroundColor)"
          background.color: "%(state.foregroundBorderColor)"
          background.style: "%(state.foregroundBorderStyle)"

          x: centered
          y: 2 below FlipActive
        }

        BackgroundDisplay : ${Slider} {
          type: HSLSlider
          channel: None
          width: 8
          height: 5

          showing: "%(state.dualColorMode)"

          color: "%(state.backgroundColor)"
          background.color: "%(state.backgroundBorderColor)"
          background.style: "%(state.backgroundBorderStyle)"

          x: centered
          y: 1 below ForegroundDisplay
        }
      }
    }
  }
}