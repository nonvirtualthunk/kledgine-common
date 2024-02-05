package arx.display.windowing

import arx.application.Application
import arx.core.*
import arx.core.Resources.image
import arx.core.bindable.BindablePatternEval
import arx.display.ascii.*
import arx.display.core.Key
import arx.display.windowing.components.DropdownSelectionChanged
import arx.display.windowing.components.FocusSettings
import arx.display.windowing.components.ascii.*
import arx.engine.*
import arx.engine.Event
import asciieditor.application.AsciiEditorApplication

class AsciiWindowingSystemComponent  : DisplayComponent(initializePriority = Priority.VeryHigh) {
    val palette = AsciiColorPalette16Bit
    lateinit var canvas : AsciiCanvas

    init {
        eventPriority = Priority.VeryHigh
        drawPriority = Priority.VeryLow
    }

    override fun initialize(world: World) {
        canvas = world[AsciiGraphics].createCanvas()

        val ws = AsciiWindowingSystem(canvas)
        world.attachData(world.globalEntity, WindowingSystem, ws)
        world.attachData(world.globalEntity, AsciiWindowingSystem, ws)

        ws.registerStandardComponents()
        ws.world.eventCallbacks = ws.world.eventCallbacks + { e -> world.fireEvent(e) }
    }

    override fun update(world: World) : Boolean {
        val windowingSystem = world[WindowingSystem]
        (windowingSystem as AsciiWindowingSystem).font = world[AsciiGraphics].font

        windowingSystem.update()

        windowingSystem.updateGeometry(Application.frameBufferSize)

        return windowingSystem.needsRerender
    }

    fun renderCommandToCanvas(com: AsciiDrawCommand, bounds: Recti) {
        canvas.clipRect = Recti(bounds.x, canvas.dimensions.y - bounds.y - bounds.height, bounds.width, bounds.height)

        when(com) {
            is AsciiDrawCommand.Blit -> {
                val pos = Vec3i(com.position.x, canvas.dimensions.y - com.position.y - com.canvas.dimensions.y * com.scale, com.position.z)

                com.canvas.blit(canvas, pos, com.scale, com.alpha)
            }
            is AsciiDrawCommand.BlitBuffer -> {
                val pos = Vec3i(com.position.x, canvas.dimensions.y - com.position.y - com.buffer.dimensions.y * com.scale, com.position.z)

                AsciiCanvas(com.buffer, canvas.palette).blit(canvas, pos, com.scale, com.alpha)
            }
            is AsciiDrawCommand.Box -> {
                val pos = Vec3i(com.position.x, canvas.dimensions.y - com.position.y - com.dimensions.y, com.position.z)

                AsciiBox.drawBox(canvas, pos, com.dimensions, com.style, com.scale.max(1),  com.edgeColor, com.fillColor, com.join)
            }
            is AsciiDrawCommand.Glyph -> {
                val x = com.position.x
                val y = canvas.dimensions.y - com.position.y - com.scale
                val z = com.position.z

                canvas.writeScaled(x,y,z, com.character, com.scale, com.foregroundColor, com.backgroundColor)
            }
            is AsciiDrawCommand.String -> {
                var x = com.position.x
                var y = canvas.dimensions.y - com.position.y - com.scale
                val z = com.position.z

                for (c in com.text.chars()) {
                    if (c.char == '\n') {
                        x = com.position.x
                        y -= c.scale ?: com.scale
                    } else {
                        canvas.writeScaled(x, y, z, c.char, c.scale ?: com.scale, c.foregroundColor ?: com.foregroundColor, c.backgroundColor ?: com.backgroundColor)
                        x += c.scale ?: com.scale
                    }
                }
            }
            is AsciiDrawCommand.GlyphRepeat -> {
                val x = com.position.x
                val y = canvas.dimensions.y - com.position.y - com.dimensions.y
                val z = com.position.z

                for (dx in 0 .. (com.dimensions.x - com.scale) step com.scale) {
                    for (dy in 0 .. (com.dimensions.y - com.scale) step com.scale) {
                        canvas.writeScaled(x + dx,y + dy,z, com.character, com.scale, com.foregroundColor, com.backgroundColor)
                    }
                }
            }
            is AsciiDrawCommand.Line -> {
                val h = tern(com.from.y == com.to.y, com.scale, 0)
                val from = Vec3i(com.from.x, canvas.dimensions.y - com.from.y - h, com.from.z)
                val to = Vec3i(com.to.x, canvas.dimensions.y - com.to.y - h, com.to.z)

                AsciiBox.drawLine(canvas, from, to, com.style, com.scale, com.foregroundColor, com.backgroundColor)
            }
            is AsciiDrawCommand.HexOutline -> {
                val h = com.dimensions.y
                val xo = com.position.x
                val yo = com.position.y
                val w = com.dimensions.x

                for (y in 0 until h/2 step com.scale) {
                    AsciiEditorApplication.canvas.writeScaled(xo + h/2 - y - 1, yo + h - y - 1, 0, '/', com.scale, com.foregroundColor, com.backgroundColor)
                    AsciiEditorApplication.canvas.writeScaled(xo + h/2 - y - 1, yo + y, 0, '\\', com.scale, com.foregroundColor, com.backgroundColor)

                    AsciiEditorApplication.canvas.writeScaled(xo + (w - h/2) + y, yo + h - y - 1, 0, '\\', com.scale, com.foregroundColor, com.backgroundColor)
                    AsciiEditorApplication.canvas.writeScaled(xo + (w - h/2) + y, yo + y, 0, '/', com.scale, com.foregroundColor, com.backgroundColor)
                }

                for (x in 0 until (w - h) step com.scale) {
                    AsciiEditorApplication.canvas.writeScaled(xo + h/2 + x, yo + h, 0, '_', com.scale, com.foregroundColor, com.backgroundColor)
                    AsciiEditorApplication.canvas.writeScaled(xo + h/2 + x, yo, 0, '_', com.scale, com.foregroundColor, com.backgroundColor)
                }
            }
        }
    }

    fun recursiveDraw(w: Widget, drawIgnoreBoundsWidgets: Boolean) {
        if (! w.showing()) {
            return
        }
        // short circuit
        if (w.ignoreBounds && ! drawIgnoreBoundsWidgets) {
            return
        }


        for (quad in w.asciiDrawCommands) {
            renderCommandToCanvas(quad, w.bounds)
        }

        w.sortChildren()
        for (c in w.children) {
            recursiveDraw(c, drawIgnoreBoundsWidgets)
        }
    }

    override fun draw(world: World) {
        val windowingSystem = world[WindowingSystem]
        if (windowingSystem.needsRerender) {
            canvas.clear()
            recursiveDraw(windowingSystem.desktop, false)
            for (w in windowingSystem.ignoreBoundsWidgets) {
                recursiveDraw(w, true)
            }
            windowingSystem.needsRerender = false
            canvas.revision++
        }
    }

    override fun handleEvent(world: World, event: Event): Boolean {
        val windowingSystem = world[WindowingSystem]
        when (event) {
            is DisplayEvent -> {
                if (event is KeyPressEvent) {
                    if (event.key == Key.F5) {
                        windowingSystem.desktop.markForFullUpdateAndAllDescendants()
                        return true
                    }
                }

                return windowingSystem.handleEvent(event)
            }
        }
        return false
    }
}


class TestAsciiWindowingComponent : DisplayComponent() {

    data class DropdownState(var selected: Any, var options: List<Any>)

    override fun initialize(world: World) {
        val ws = world[WindowingSystem]

        val w = ws.createWidget()

        w.x = WidgetPosition.Fixed(0)
        w.y = WidgetPosition.Fixed(0)
        w.z = WidgetPosition.Fixed(1)

        w.width = WidgetDimensions.Fixed(40)
        w.height = WidgetDimensions.Fixed(30)


        val t = ws.createWidget()

        t.x = WidgetPosition.Fixed(5)
        t.y = WidgetPosition.Fixed(40)
        t.z = WidgetPosition.Fixed(1)

        t.width = WidgetDimensions.Intrinsic()
        t.height = WidgetDimensions.Intrinsic()

        t.attachData(AsciiTextWidget(text = ValueBindable(AsciiRichText("Test Text")), scale = ValueBindable(1)))

        val iw = ws.createWidget()

        iw.x = WidgetPosition.Fixed(-200)
        iw.y = WidgetPosition.Fixed(-100)

        iw.width = WidgetDimensions.Fixed(1200)
        iw.height = WidgetDimensions.Fixed(600)

//        val img = image("test/display/images/thorn_prince_2.png")
//        val img = image("sundar/display/images/alley_2.png")
        val img = image("sundar/display/images/map_5.png")
        iw.background.centerColor = ValueBindable(RGBA(120,120,120,255))
        iw.attachData(AsciiImageWidget(image = ValueBindable(img)))


        val dd = ws.createWidget(ws.desktop) {
            it.attachData(AsciiDropdown(
                items = TypedListPatternBindable(BindablePatternEval.Lookup("dropdown.options"), Any::class.java),
                selectedItemBinding = PropertyBinding("dropdown.selected", true)
            ))
            it.attachData(FocusSettings(
                acceptsFocus = true,

            ))
            it.x = WidgetPosition.Fixed(50)
            it.y = WidgetPosition.Fixed(4)

            it.width = WidgetDimensions.Intrinsic()
            it.height = WidgetDimensions.Intrinsic()

            it.padding = Vec3i(2,0,0)
        }

        val dropdownState = DropdownState(3, listOf("Test A", "Longer test option", 3))

        ws.desktop.onEventDo<DropdownSelectionChanged> {
            println("Selected value: ${it.selected}, $dropdownState")
        }

        dd.bind("dropdown", dropdownState)

        ws.desktop.onEventDo<WidgetKeyReleaseEvent> {
            when (it.key) {
                Key.Up -> {
                    iw.width = (iw.width as WidgetDimensions.Fixed).run { copy(size = (size * 1.1).toInt()) }
                    iw.height = (iw.height as WidgetDimensions.Fixed).run { copy(size = (size * 1.1).toInt()) }
                }
                Key.Down -> {
                    iw.width = (iw.width as WidgetDimensions.Fixed).run { copy(size = (size * 0.9).toInt() ) }
                    iw.height = (iw.height as WidgetDimensions.Fixed).run { copy(size = (size * 0.9).toInt() ) }
                }
                Key.Left -> {
                    iw[AsciiImageWidget]?.scale = ((iw[AsciiImageWidget]?.scale ?: 0) - 1).max(1)
                    iw.markForFullUpdate()
                }
                Key.Right -> {
                    iw[AsciiImageWidget]?.scale = ((iw[AsciiImageWidget]?.scale ?: 0) + 1).min(3)
                    iw.markForFullUpdate()
                }
                Key.LeftBracket -> {
                    val a = iw[AsciiImageWidget]?.color?.invoke()?.toFloat()?.a ?: 1.0f
                    iw[AsciiImageWidget]?.color = ValueBindable(RGBAf(1.0f,1.0f,1.0f,(a - 0.1f).clamp(0.0f, 1.0f)))
                    iw.markForFullUpdate()
                }
                Key.RightBracket -> {
                    val a = iw[AsciiImageWidget]?.color?.invoke()?.toFloat()?.a ?: 1.0f
                    iw[AsciiImageWidget]?.color = ValueBindable(RGBAf(1.0f,1.0f,1.0f,(a + 0.1f).clamp(0.0f, 1.0f)))
                    iw.markForFullUpdate()
                }
                Key.W, Key.S, Key.A, Key.D -> {
                    val delta = when (it.key) {
                        Key.W -> Vec2i(0,1)
                        Key.S -> Vec2i(0,-1)
                        Key.A -> Vec2i(1,0)
                        Key.D -> Vec2i(-1,0)
                        else -> Vec2i(0,0)
                    }

                    iw.x = (iw.x as WidgetPosition.Fixed).let { p -> p.copy(offset = p.offset + delta.x * 20) }
                    iw.y = (iw.y as WidgetPosition.Fixed).let { p -> p.copy(offset = p.offset + delta.y * 20) }
                }
                else -> {
                    // do nothing
                }
            }
        }
    }
}

fun main() {
    Application(windowWidth = 1680, windowHeight = 1000)
        .apply {
            clearColor = RGBA(0,0,0,255)
        }
        .run(
            Engine(
                mutableListOf(),
                mutableListOf(AsciiGraphicsComponent(), AsciiWindowingSystemComponent(), TestAsciiWindowingComponent())
            )
        )
}