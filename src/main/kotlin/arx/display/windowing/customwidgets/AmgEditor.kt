package arx.display.windowing.customwidgets

import arx.core.*
import arx.display.ascii.*
import arx.display.core.Image
import arx.display.core.Key
import arx.display.core.KeyModifiers
import arx.display.windowing.SignalEvent
import arx.display.windowing.Widget
import arx.display.windowing.WidgetKeyReleaseEvent
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.FileInputKind
import arx.display.windowing.components.ascii.AsciiCanvasMouseDragEvent
import arx.display.windowing.components.ascii.AsciiCanvasMousePressEvent
import arx.display.windowing.components.ascii.AsciiCanvasMouseReleaseEvent
import arx.display.windowing.components.fileDialog
import arx.engine.DisplayEvent
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.sign

data class ImportSettings(
    val dimensions: Vec2i = Vec2i(30,15),
    var canvas: AsciiCanvas = AsciiCanvas(dimensions,AsciiColorPalette16Bit),
    var file: File? = null,
    var image: Image? = null,
    var active: Boolean = false
)

data class AmgEditorState(
    val preferences: Preferences,
    var activeChar: Char = Ascii.FullBlockChar,
    var foregroundColor: HSL = HSL(0.0f,1.0f,0.5f,1.0f),
    var backgroundColor: HSL = HSL(0.0f,0.0f,0.0f,0.0f),
    var foregroundColorActive: Boolean = true,
    var canvas: AsciiCanvas = AsciiCanvas(preferences.canvasDimensions, AsciiColorPalette16Bit),
    var underlay: AsciiCanvas = AsciiCanvas(canvas.dimensions, canvas.palette),
    var saveFile: File? = null,
    var lastMousePoint: Vec2i = Vec2i(0,0),
    var renderOffset: Vec2i = Vec2i(0,0),
    var scale: Int = 2,
    var tools : List<AmgEditorTool> = listOf(
        AmgEditorTool.PaintBrush,
        AmgEditorTool.Eyedropper,
        AmgEditorTool.Fill
    ),
    var activeTool : AmgEditorTool = AmgEditorTool.PaintBrush,
    var importSettings: ImportSettings = ImportSettings(dimensions = preferences.importDimensions, file = preferences.importPath?.let { File(it) }),
    var lastRevision: Int = 0,
    val undoStack : MutableList<AsciiBuffer> = mutableListOf(canvas.buffer.copy()),
    val redoStack : MutableList<AsciiBuffer> = mutableListOf(),
) {
    fun effectiveFgColor() : RGBA {
        return foregroundColor.toRGBA()
    }

    fun effectiveBgColor() : RGBA {
        return backgroundColor.toRGBA()
    }
}


@Serializable
data class Preferences(
    var canvasDimensions : Vec2i = Vec2i(54, 30),
    var hexGuideDimensions : Vec2i = Vec2i(42, 18),
    var importDimensions: Vec2i = Vec2i(18, 9),
    var importPath : String? = null
)

sealed interface AmgEditorTool {
    val name: String
    val shortcut: Key

    fun AmgEditorState.mark(w: Widget, x: Int, y: Int) : Boolean

    data object PaintBrush : AmgEditorTool {
        override val name = "Paint Brush"
        override val shortcut = Key.B

        override fun AmgEditorState.mark(w: Widget, x: Int, y: Int) : Boolean {
            val cur = canvas.read(x,y)
            val modified = if (activeChar == ' ') {
                if (cur.foregroundColorEncoded != 0u || cur.backgroundColorEncoded != 0u) {
                    canvas.clear(x, y)
                    true
                } else {
                    false
                }
            } else {
                if (cur.character != activeChar || cur.foregroundColor != effectiveFgColor() || cur.backgroundColor != effectiveBgColor()) {
                    canvas.write(x, y, 0, activeChar, effectiveFgColor(), effectiveBgColor())
                    true
                } else {
                    false
                }
            }

            if (modified) {
                canvas.revision++
            }
            return modified
        }
    }

    data object Eyedropper : AmgEditorTool {
        override val name = "Eyedropper"
        override val shortcut = Key.I

        override fun AmgEditorState.mark(w: Widget, x: Int, y: Int) : Boolean {
            val g = canvas.read(x,y)
            val to = if (KeyModifiers.activeModifiers.shift) {
                g.backgroundColor.toHSL()
            } else {
                g.foregroundColor.toHSL()
            }
            if (foregroundColorActive) {
                AsciiColorPicker.setForegroundColor(w, to)
            } else {
                AsciiColorPicker.setBackgroundColor(w, to)
            }

            return true
        }
    }

    data object Fill : AmgEditorTool {
        override val name = "Fill"
        override val shortcut = Key.G

        override fun AmgEditorState.mark(w: Widget, x: Int, y: Int) : Boolean {
            val ref = canvas.read(x,y)
            val stack = mutableListOf(Vec2i(x,y))

            val isDelete = activeChar == ' '

            if (isDelete && ref.foregroundColorEncoded == 0u && ref.backgroundColorEncoded == 0u) {
                return false
            } else if (ref.character == activeChar &&
                ref.foregroundColorEncoded == effectiveFgColor().to16BitColor().toUInt() &&
                ref.backgroundColorEncoded == effectiveBgColor().to16BitColor().toUInt()) {
                return false
            }

            var i = 0
            while (stack.isNotEmpty()) {
                val p = stack.pop()
                if (! canvas.inBounds(p)) {
                    continue
                }

                val g = canvas.read(p.x, p.y)

                if (g == ref) {
                    if (isDelete) {
                        canvas.clear(p.x, p.y)
                    } else {
                        canvas.write(p.x, p.y, 0, activeChar, effectiveFgColor(), effectiveBgColor())
                    }
                    Cardinals2D.forEach { c -> stack.add(p + c) }
                }
                i++
                if (i > 100000) {
                    Noto.err("Infinite loop in fill, eh?")
                    break
                }
            }
            return true
        }
    }
}

@ExperimentalUnsignedTypes
object AmgEditor : CustomWidget {
    override val name: String = "AmgEditor"
    override val archetype: String = "AmgEditor.AmgEditor"

    override fun initialize(w: Widget) {
        val prefs = AppConfig.load<Preferences>()

        val state = AmgEditorState(preferences = prefs)
        w.data = bindable(state)

        w.bind("state", state)
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val state = w.data() as? AmgEditorState
        
        if (state == null) {
            Noto.err("AmgEditor state has somehow been replaced with something else? ${w.data()}")
            return false
        }
        
        with (state) {
            return when (event) {
                is SelectedCharacterChangeEvent -> {
                    activeChar = event.selectedChar
                    true
                }
                is ForegroundColorChangeEvent -> {
                    foregroundColor = event.color
                    true
                }
                is BackgroundColorChangeEvent -> {
                    backgroundColor = event.color
                    true
                }
                is ActiveColorFlippedEvent -> {
                    foregroundColorActive = event.foregroundActive
                    true
                }
                is AsciiCanvasMousePressEvent -> {
                    with(activeTool) {
                        if (mark(w, event.position.x, event.position.y)) {
                            canvas.revision++
                        }
                    }
                    lastMousePoint = event.position
                    true
                }
                is AsciiCanvasMouseReleaseEvent -> {
                    markUndoPoint()
                    true
                }
                is AsciiCanvasMouseDragEvent -> {
                    var modified = false
                    with(activeTool) {
                        if (mark(w, event.position.x, event.position.y)) {
                            modified = true
                        }
                    }

                    val p = Vec2i(event.position.x, event.position.y)
                    while (p != lastMousePoint) {
                        p.x += (lastMousePoint.x - p.x).sign
                        p.y += (lastMousePoint.y - p.y).sign
                        with(activeTool) {
                            if (mark(w, p.x, p.y)) {
                                modified = true
                            }
                        }
                    }

                    if (modified) {
                        canvas.revision++
                    }
                    lastMousePoint = event.position
                    true
                }
                is WidgetKeyReleaseEvent -> {
                    if (event.key == Key.S && event.mods.ctrl) {
                        save(w, saveAs = event.mods.shift)
                    } else if (event.key == Key.L && event.mods.ctrl) {
                        load(w)
                    } else if (event.key == Key.I && event.mods.ctrl) {
                        import(w)
                    } else if (event.key == Key.Z && event.mods.ctrl) {
                        if (event.mods.shift) {
                            redo(w)
                        } else {
                            undo(w)
                        }
                    } else if (event.key == Key.Backspace && event.mods.ctrl) {
                        canvas.clear()
                        saveFile = null
                        w.bind("state", state, forceUpdate = true)
                    } else if (event.key.isArrow) {
                        val delta = when (event.key) {
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

                        canvas.revision += 1
                        markUndoPoint()
                        w.bind("state", state)
                    } else {
                        for (tool in tools) {
                            if (event.key == tool.shortcut) {
                                activeTool = tool
                                w.bind("state", state)
                                return true
                            }
                        }
                        return false
                    }
                    true
                }
                is SignalEvent -> {
                    when (event.signal) {
                        "Save" -> save(w, saveAs = KeyModifiers.activeModifiers.shift)
                        "SaveAs" -> save(w, saveAs = true)
                        "ApplyButton" -> {
                            // save, then send out an apply signal with the file we ended up using, if any
                            save(w, saveAs = false)
                            if (saveFile != null) {
                                w.fireEvent(SignalEvent("Apply", saveFile))
                            }
                        }
                        "Load" -> load(w)
                        "ImportWindow" -> importSettings.active = !importSettings.active
                        "CancelImport" -> importSettings.active = false
                        "Import" -> import(w)
                        "ImportSettingsChanged" -> updateImportPreview(w)
                        "HexGuide" -> {
                            val hh = preferences.hexGuideDimensions.y
                            val hw = preferences.hexGuideDimensions.x

                            fun drawHex(xo: Int, yo: Int) {
                                for (y in 0 until hh/2) {
                                    underlay.write(xo + hh/2 - y - 1, yo + hh - y - 1, 0, '/', White)
                                    underlay.write(xo + hh/2 - y - 1, yo + y, 0, '\\', White)

                                    underlay.write(xo + (hw - hh/2) + y, yo + hh - y - 1, 0, '\\', White)
                                    underlay.write(xo + (hw - hh/2) + y, yo + y, 0, '/', White)
                                }

                                for (x in 0 until (hw - hh)) {
                                    underlay.write(xo + hh/2 + x, yo + hh, 0, '_', White)
                                    underlay.write(xo + hh/2 + x, yo, 0, '_', White)
                                }
                            }

                            drawHex((underlay.dimensions.x - hw) / 2,(underlay.dimensions.y - hh) / 2)
                            underlay.revision += 1

                        }
                        "SelectTool" -> {
                            (event.data as? AmgEditorTool)?.expectLet {
                                activeTool = it
                            }
                        }
                        else -> return false
                    }
                    w.bind("state", state)
                    true
                }
//                is WidgetMouseReleaseEvent -> {
//                    Noto.info("MRE")
//                    true
//                }
                else -> false
            }
        }
    }

    private fun AmgEditorState.markUndoPoint() {
        if (canvas.revision > lastRevision) {
            undoStack.add(canvas.buffer.copy())
            redoStack.clear()
            lastRevision = canvas.revision
        }
    }

    private fun AmgEditorState.setFromBuffer(w: Widget, ub: AsciiBuffer) {
        if (canvas.dimensions != ub.dimensions) {
            canvas = AsciiCanvas(ub.dimensions)
        }
        ub.copyInto(canvas.buffer)
        canvas.revision++

        w.bind("state", this)
    }

    private fun AmgEditorState.undoRedo(w: Widget, fromStack: MutableList<AsciiBuffer>, toStack: MutableList<AsciiBuffer>) {
        fromStack.popOpt()?.let { ub ->
            if (canvas.dimensions != ub.dimensions) {
                canvas = AsciiCanvas(ub.dimensions)
            }
            ub.copyInto(canvas.buffer)
            canvas.revision++

            toStack.push(ub)

            w.bind("state", this)
        }
    }

    private fun AmgEditorState.undo(w: Widget) {
        if (undoStack.size > 1) {
            redoStack.push(undoStack.pop())
            setFromBuffer(w, undoStack.last())
        }
//        undoRedo(w, undoStack, redoStack)
//        if (undoStack.isEmpty()) {
//            undoStack.push(canvas.buffer.copy())
//        }
    }

    private fun AmgEditorState.redo(w: Widget) {
        undoRedo(w, redoStack, undoStack)
    }

    private fun AmgEditorState.load(w: Widget) {
        fileDialog(FileInputKind.Open, "/Users/sam/code/kledgine/src/main/resources/sundar/display/roguelike", "amg")?.let { file ->
            saveFile = file
            val newCanvas = AsciiCanvas.readFromFile(file)

            canvas.clear()
            val offset = (canvas.dimensions - newCanvas.dimensions) / 2
            newCanvas.blit(canvas, Vec3i(offset.x, offset.y, 0), 1)
            w.bind("state", this, forceUpdate = true)
        }
    }

    private fun AmgEditorState.save(widget: Widget, saveAs: Boolean) {
        if (saveAs || saveFile == null) {
            saveFile = null
            fileDialog(FileInputKind.Save, "/Users/sam/code/kledgine/src/main/resources/sundar/display/roguelike", "amg")?.let { file ->
                saveFile = file
            }
        }
        saveFile?.let { file ->
            val trimmed = AsciiCanvas.trimmed(canvas)

            AsciiCanvas.writeToFile(file, trimmed)

            widget.fireEvent(SignalEvent("Saved", file))
        }
    }

    private fun AmgEditorState.import(w: Widget) {
        val requiredSize = Vec2i(canvas.dimensions.x.max(importSettings.canvas.dimensions.x), canvas.dimensions.y.max(importSettings.canvas.dimensions.y))
        if (requiredSize.x > canvas.dimensions.x || requiredSize.y > canvas.dimensions.y) {
            val newCanvas = AsciiCanvas(requiredSize)
            canvas.blit(newCanvas, Vec3i(0,0,0), 1)
            canvas = newCanvas
        }

        val diff = canvas.dimensions - importSettings.canvas.dimensions
        importSettings.canvas.blit(canvas, Vec3i(diff.x / 2, diff.y / 2, 0), 1)

        canvas.revision++
        markUndoPoint()

        importSettings.active = false

        w.bind("state", this, forceUpdate = true)
    }

    private fun AmgEditorState.updateImportPreview(w: Widget) {
        preferences.importDimensions = importSettings.dimensions

        importSettings.file?.let { file ->
            preferences.importPath = file.absolutePath

            if (importSettings.image?.path != file.absolutePath) {
                importSettings.image = Image.load(file.absolutePath)
            }

            importSettings.image?.expectLet { img ->
                if (importSettings.canvas.dimensions != importSettings.dimensions)  {
                    importSettings.canvas = AsciiCanvas(importSettings.dimensions, AsciiColorPalette16Bit)
                }
                importSettings.canvas.clear()

                val dims = AsciiImage.imageToAsciiDimensions(img, importSettings.canvas.dimensions, w.windowingSystem.font)
                AsciiImage.renderToCanvas(img, Vec3i(0,0,0), dims, importSettings.canvas, w.windowingSystem.font, AsciiImage.RenderSettings())
                importSettings.canvas.revision++
            }
        }

        AppConfig.save(preferences)
    }
}

fun main (args: Array<String>) {
    testAsciiCustomWidget(AmgEditor) {

    }
}