

AsciiTextSelector {
  type: AsciiTextSelector

  width: 10
  height: WrapContent

  background.draw: false

  children {
    Label {
      type: TextDisplay
      y: centered

      text: "%(textSelectorLabel) "
      showing: "%(?textSelectorLabel)"

      background.draw: false
    }

    Input {
      type: TextInput

      x: 0 right of Label

      width: ExpandToParent
      height: intrinsic
      singleLine: true

      background.scale: 1

      tabbable: true
      cycleOnSelect: false // false since the overall selector cycles on enter
      selectAllOnFocus: true
    }

//    MutateSection {
//      type: Div
//
//      children {
//        EditButton {
//          type: Button
//          x: 0 from Right
//          text: "E"
//          buttonSignal: "Edit"
//
//          showing: "%(showEditButton)"
//        }
//
//        CreateButton {
//          type: 0 from Right
//        }
//      }
//    }

    Completions {
      type: ListWidget
      x: match Input
      y: 0 below Input
      z: 1

      width: ExpandToParent
      height: WrapContent

      ignoreBounds: true

      showing: "%(?possibleCompletions)"

      listItemArchetype: AsciiTextSelector.Completion
      listItemBinding: "possibleCompletions -> completion"

      background.scale: 1
      selectable: true
    }
  }
}

Completion {
  text: "%(completion.text)"
  color: "%(completion.color)"

  background.draw: false
}