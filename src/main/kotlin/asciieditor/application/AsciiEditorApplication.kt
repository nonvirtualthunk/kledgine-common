package asciieditor.application

import arx.application.Application
import arx.core.*
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiColorPalette16Bit
import arx.display.ascii.AsciiGraphicsComponent
import arx.display.core.Key
import arx.display.windowing.AsciiWindowingSystemComponent
import arx.display.windowing.WidgetKeyReleaseEvent
import arx.display.windowing.WindowingSystem
import arx.display.windowing.components.FileInputKind
import arx.display.windowing.components.ascii.AsciiCanvasMouseDragEvent
import arx.display.windowing.components.ascii.AsciiCanvasMousePressEvent
import arx.display.windowing.components.fileDialog
import arx.display.windowing.customwidgets.AsciiCustomWidgets
import arx.display.windowing.customwidgets.SelectedColorChangeEvent
import arx.display.windowing.onEventDo
import arx.engine.DisplayComponent
import arx.engine.Engine
import arx.engine.World
import arx.display.windowing.customwidgets.CharacterPickerWidget
import arx.display.windowing.customwidgets.SelectedCharacterChangeEvent
import java.io.File
import kotlin.math.sign


object AsciiEditorApplication : DisplayComponent() {

    var activeChar : Char? = null
    var activeColor : RGBA = HSL(0.5f,0.5f,0.5f,1.0f).toRGBA()

    var canvas = AsciiCanvas(Vec2i(54,30), AsciiColorPalette16Bit)

    val underlay = AsciiCanvas(canvas.dimensions, AsciiColorPalette16Bit)

    var saveFile: File? = null

    var lastMousePoint : Vec2i = Vec2i(0,0)

    override fun World.initializeWithWorld() {
        val ws = global(WindowingSystem)

        ws.desktop.background.draw = bindable(false)
        val editor = ws.desktop.createWidget("AsciiEditor.AsciiEditor")


        val h = 18
        val w = 42

        fun drawHex(xo: Int, yo: Int) {
            for (y in 0 until h/2) {
                underlay.write(xo + h/2 - y - 1, yo + h - y - 1, 0, '/', White)
                underlay.write(xo + h/2 - y - 1, yo + y, 0, '\\', White)

                underlay.write(xo + (w - h/2) + y, yo + h - y - 1, 0, '\\', White)
                underlay.write(xo + (w - h/2) + y, yo + y, 0, '/', White)
            }

            for (x in 0 until (w - h)) {
                underlay.write(xo + h/2 + x, yo + h, 0, '_', White)
                underlay.write(xo + h/2 + x, yo, 0, '_', White)
            }
        }

        drawHex((underlay.dimensions.x - w) / 2,(underlay.dimensions.y - h) / 2)

        editor.bind("canvas", canvas)
        editor.bind("underlay", underlay)
        editor.bind("scale", 2)

        editor.onEventDo<SelectedCharacterChangeEvent> {
            activeChar = it.selectedChar
        }

        editor.onEventDo<SelectedColorChangeEvent> {
            activeColor = it.selectedColor.toRGBA()
        }

        editor.onEventDo<AsciiCanvasMousePressEvent> {
            activeChar?.let { char ->
                canvas.write(it.position.x, it.position.y, 0, char, if (char == ' ') { Clear } else { activeColor })
                canvas.revision++
                lastMousePoint = it.position
            }
        }
        editor.onEventDo<AsciiCanvasMouseDragEvent> {
            activeChar?.let { char ->
                canvas.write(it.position.x, it.position.y, 0, char, if (char == ' ') { Clear } else { activeColor })
                canvas.revision++

                val p = Vec2i(it.position.x, it.position.y)
                while (p != lastMousePoint) {
                    p.x += (lastMousePoint.x - p.x).sign
                    p.y += (lastMousePoint.y - p.y).sign
                    canvas.write(p.x, p.y, 0, char, activeColor)
                }
                lastMousePoint = it.position
            }
        }

        ws.desktop.onEventDo<WidgetKeyReleaseEvent> {
            if (it.key == Key.S && it.mods.ctrl) {
                if (it.mods.shift || saveFile == null) {
                    saveFile = null
                    fileDialog(FileInputKind.Save, "/Users/sam/code/kledgine/src/main/resources/sundar/display/roguelike", "amg")?.let { file ->
                        saveFile = file
                    }
                }
                saveFile?.let { file ->
                    val trimmed = AsciiCanvas.trimmed(canvas)

                    AsciiCanvas.writeToFile(file, trimmed)
                }
            } else if (it.key == Key.L && it.mods.ctrl) {
                fileDialog(FileInputKind.Open, "/Users/sam/code/kledgine/src/main/resources/sundar/display/roguelike", "amg")?.let { file ->
                    saveFile = file
                    val newCanvas = AsciiCanvas.readFromFile(file)

                    canvas.clear()
                    val offset = (canvas.dimensions - newCanvas.dimensions) / 2
                    newCanvas.blit(canvas, Vec3i(offset.x, offset.y, 0), 1)
                    editor.bind("canvas", canvas, forceUpdate = true)
                }
            } else if (it.key == Key.Backspace && it.mods.ctrl) {
                canvas.clear()
                saveFile = null
                editor.bind("canvas", canvas, forceUpdate = true)
            } else if (it.key.isArrow) {
                val delta = when (it.key) {
                    Key.Left -> Vec2i(-1,0)
                    Key.Right -> Vec2i(1,0)
                    Key.Up -> Vec2i(0,1)
                    Key.Down -> Vec2i(0,-1)
                    else -> Vec2i(0,0)
                }

                val startX = delta.x.max(0) * (canvas.dimensions.x - 1)
                val startY = delta.y.max(0) * (canvas.dimensions.y - 1)

                var x = startX
                var y = startY

                val endX = -(delta.x - 1).sign * (canvas.dimensions.x + 1) - 1
                val endY = -(delta.y - 1).sign * (canvas.dimensions.y + 1) - 1

                val dx = (endX - x).sign
                val dy = (endY - y).sign

                while (x != endX) {
                    val fx = x - delta.x
                    while (y != endY) {
                        val fy = y - delta.y

                        if (fx >= 0 && fy >= 0 && fx < canvas.dimensions.x && fy < canvas.dimensions.y) {
                            canvas.copyGlyphFrom(canvas, fx, fy, x, y)
                        } else {
                            canvas.write(x, y, 0, Char(0), 0u, 0u)
                        }
                        y += dy
                    }
                    y = startY
                    x += dx
                }

                canvas.revision+=1
                editor.bind("canvas", canvas, forceUpdate = true)
            }
        }
    }


}



fun main() {
    AsciiCustomWidgets.registerCustomWidgets()

    Application(windowWidth = 1680, windowHeight = 1000)
        .apply {
            clearColor = RGBA(0,0,0,255)
        }
        .run(
            Engine(
                mutableListOf(),
                mutableListOf(AsciiGraphicsComponent(), AsciiWindowingSystemComponent(), AsciiEditorApplication)
            )
        )
}