package arx.display.windowing.customwidgets

import arx.core.*
import arx.display.ascii.*
import arx.display.windowing.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.DisplayEvent
import arx.engine.EntityData
import com.typesafe.config.ConfigValue


data class SelectedCharacterChangeEvent(val selectedChar : Char) : WidgetEvent(null)

data class CharacterPickerWidget (
    var selectedCharacter : Char? = null,
    var renderedCharPositions : Map<Vec3i, Char> = mapOf(),
    var scale : Int? = null
) : DisplayData {
    companion object : DataType<CharacterPickerWidget>( CharacterPickerWidget(), sparse = true ), WindowingComponent, FromConfigCreator<CharacterPickerWidget> {

        override fun createFromConfig(cv: ConfigValue?): CharacterPickerWidget? {
            if (cv["type"]?.asStr()?.lowercase() == "characterpicker") {
                return CharacterPickerWidget(scale = cv["scale"]?.asInt())
            } else {
                return null
            }
        }

        override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
            val cpw = w[CharacterPickerWidget] ?: return

            val width = w.resClientWidth
            val height = w.resClientHeight

            val px = w.resClientX
            val py = w.resClientY

            val sortedChars = Ascii.allChars.sorted() + CustomChars.customGlyphs.keys.toList()
            var index = 0
            val scale = ws.effectiveScale(cpw.scale)
            val renderedPositions = mutableMapOf<Vec3i, Char>()
            for (y in 0 until (height - scale) step (scale + 1)) {
                for (x in 0 until (width - scale) step (scale + 1)) {
                    if (index >= sortedChars.size) {
                        break
                    }

                    val pos = Vec3i(px + x, py + y, w.resZ)

                    val char = sortedChars[index]
                    if (cpw.selectedCharacter == char) {
//                        commandsOut.add(AsciiDrawCommand.Glyph(char, Vec3i(px + x, py + y, 0), 2, RGBA(255, 0, 0, 255), White))
                        commandsOut.add(AsciiDrawCommand.Box(pos - Vec3i(1,1,0), Vec2i(scale + 2,scale + 2), Ascii.BoxStyle.Line, 1, White, null))
                    }
//                    } else {
                        commandsOut.add(AsciiDrawCommand.Glyph(char, Vec3i(px + x, py + y, pos.z), scale, White, null))
//                    }
                    renderedPositions[pos] = char

                    index += 1
                }
            }
            cpw.renderedCharPositions = renderedPositions
        }

        override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
            val cpw = w[CharacterPickerWidget] ?: return false

            val scale = w.windowingSystem.effectiveScale(cpw.scale)
            when (event) {
                is WidgetMouseReleaseEvent -> {
                    val x = event.position.x.toInt()
                    val y = event.position.y.toInt()
                    for ((pos, char) in cpw.renderedCharPositions) {
                        if (x >= pos.x && y >= pos.y && x <= pos.x + scale && y <= pos.y + scale) {
                            if (cpw.selectedCharacter != char) {
                                cpw.selectedCharacter = char
                                w.markForUpdate(RecalculationFlag.Contents)
                                Noto.info("Selected character: $char")
                                w.fireEvent(SelectedCharacterChangeEvent(char))
                            }
                            return true
                        }
                    }
                }
            }
            return false
        }

        override fun dataTypes(): List<DataType<EntityData>> {
            return listOf(CharacterPickerWidget)
        }
    }
    override fun dataType() : DataType<*> { return CharacterPickerWidget }
}
