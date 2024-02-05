TopBarButton {
  type: Button
  padding: [2,1,0]

  background.centerColor: [50,50,50,255]
}

AmgEditor {
  type: AmgEditor

  x: centered
  y: centered
  width: 100%
  height: 100%
  background {
    style: line
    scale: 1
  }

  acceptsFocus: true

  children {
    ImportWindow: ${ImportWindow}

    TopBar {
      type: Widget
      width: 100%
      height: WrapContent

      padding: [-1,-1]

      children {
        SaveButton: ${TopBarButton} {
          text: "Save"
          signal: Save
        }

        SaveAsButton: ${TopBarButton} {
          text: "Save As"
          signal: SaveAs

          x: -1 right of SaveButton
        }

        LoadButton: ${TopBarButton} {
          text: "Load"
          signal: "Load"

          x: -1 right of SaveAsButton
        }

        ImportButton: ${TopBarButton} {
          text: "Import"
          signal: "ImportWindow"

          x: -1 right of LoadButton
        }

        HexButton: ${TopBarButton} {
          text: "Hex Guide"
          signal: "HexGuide"

          x : -1 right of ImportButton
        }

        CloseButton: ${TopBarButton} {
          text: "X"
          signal: "Close"
          color: [200,0,0,255]

          showing: "%(closeable)"

          x: 0 from right
        }

        ApplyButton: ${TopBarButton} {
          text: "☺"
          signal: "ApplyButton"
          color: [50,200,50,255]

          showing: "%(applyable)"
          data: "%(state.saveFile)"

          x: 0 left of CloseButton
        }
      }
    }

    MainToolbar {
      type: Widget
      width: 90
      height: ExpandToParent
      y: 0 below TopBar

      children {
        ColorPicker {
          type: AsciiColorPicker

          width: 100%

          dualColor: true
        }

        ToolPicker {
          type: ListWidget

          width: 100%
          y: 0 below ColorPicker
          height: expand to CharacterPicker

          listItemBinding: "state.tools -> tool"
          listItemArchetype: AmgEditor.ToolWidget
        }

        CharacterPicker {
          type: CharacterPicker

          scale: 3

          width: 100%
          height: 50%
          y: 0 from bottom
        }
      }
    }

    SecondaryToolbar {
      type: Widget
      width: 10
      height: ExpandToParent
      x: 0 from right
      y: 0 below TopBar

    }

    CanvasContainer {
      type: Widget

      x: 0 right of MainToolbar
      y: 0 below TopBar
      z: 4
      width: expand to SecondaryToolbar
      height: ExpandToParent

      children {
        AsciiCanvas {
          type: AsciiCanvas

          x: centered
          y: centered
          z: 10
          width: Intrinsic
          height: Intrinsic

          canvas: "%(state.canvas)"
          renderOffset: "%(state.renderOffset)"
          scale: "%(state.scale)"

          background.drawCenter: false
        }

        AsciiUnderlay {
          type: AsciiCanvas

          x: centered
          y: centered
          z: 5
          width: Intrinsic
          height: Intrinsic

          canvas: "%(state.underlay)"
          renderOffset: "%(state.renderOffset)"
          scale: "%(state.scale)"
        }
      }


    }
  }
}


ToolWidget {
  type: Button

  text: "%(tool.name)"
  signal: "SelectTool"
  data: "%(tool)"

  background.color: "%(state.activeTool) == %(tool) ? [255,255,255,255] : [128,128,128,255]"
}


ImportWindow {
  type: Widget

  width: WrapContent
  height: WrapContent

  x: Centered
  y: Centered
  z: 20

  showing: "%(state.importSettings.active)"

  background.draw: true

  children {
    EditorSection {
      type: Div
      z: 0

      children {
        FileLabel {
          type: TextDisplay
          x: 0
          text: "File"
          background.draw: false
        }

        FileSelection {
          type: FileInput
          x: 1 right of FileLabel

          width: 80
          height: 4

          filePath: "%(state.importSettings.file)"
          changeSignal: "ImportSettingsChanged"
          twoWayBinding: true
        }

        Dimensions {
          type: Div
          y: 1 below FileSelection

          children {
            DimensionsLabel {
              type: TextDisplay
              text: "Dimensions"
            }

            XEditor {
              type: TextInput

              x: 1 right of DimensionsLabel

              text: %(state.importSettings.dimensions.x)
              changeSignal: "ImportSettingsChanged"

              cycleOnSelect: true
              tabbable: true
              integerOnly: true
              selectAllOnFocus: true
              twoWayBinding: true

              width: "intrinsic(2,20)"
              height: intrinsic
              padding: [2, 0, 0]
            }

            ByLabel {
              type: TextDisplay

              text: "x"
              x: 1 right of XEditor
              y: centered
              background.draw: false
            }

            YEditor {
              type: TextInput

              x: 1 right of ByLabel

              text: %(state.importSettings.dimensions.y)
              changeSignal: "ImportSettingsChanged"

              cycleOnSelect: true
              tabbable: true
              integerOnly: true
              selectAllOnFocus: true
              twoWayBinding: true

              width: "intrinsic(2,20)"
              height: intrinsic
              padding: [2, 0, 0]
            }
          }
        }

        Preview {
          type: AsciiCanvas

          x: Centered
          y: 1 below Dimensions

          canvas: "%(state.importSettings.canvas)"
        }
      }
    }

    ApplyButton {
      type: Button

      text: "Import"
      signal: "Import"
      data: "%(state.importSettings)"

      y: 1 below EditorSection
    }

    CancelButton {
      type: Button

      x: 1 right of ApplyButton
      y: match ApplyButton

      text: "Cancel"
      signal: "CancelImport"
      data: "%(state.importSettings)"
    }
  }
}


SpriteSettingsWindow {
  type: Widget

  width: WrapContent
  height: WrapContent

  x: Centered
  y: Centered
  z: 10

  children {
    EditorSection {
      type: Div

      children {
        DimensionsLabel {
          type: TextDisplay

          text: "Dims: "
        }
        XEditor {
          type: TextInput

          x: 1 right of DimensionsLabel

          text: %(spriteSettings.canvasDimensions.x)

          cycleOnSelect: true
          tabbable: true
          integerOnly: true
          twoWayBinding: true

          width: "intrinsic(2,8)"
          height: intrinsic
          padding: [2, 0, 0]
        }

        ByLabel {
          type: TextDisplay

          text: "x"
          x: 1 right of XEditor
        }

        YEditor {
          type: TextInput

          x : 1 right of ByLabel

          text: %(spriteSettings.canvasDimensions.y)

          cycleOnSelect: true
          tabbable: true
          integerOnly: true
          twoWayBinding: true

          width: "intrinsic(2,8)"
          height: intrinsic
          padding: [2, 0, 0]
        }
      }
    }


    ApplyButton {
      type: Button

      text: "Apply"
      signal: "ApplySpriteSettings"
      data: "%(spriteSettings)"

      y: 1 below EditorSection
    }

    CancelButton {
      type: Button

      x: 1 right of ApplyButton
      y: match ApplyButton

      text: "Cancel"
      signal: "CancelSpriteSettings"
      data: "%(spriteSettings)"
    }
  }
}