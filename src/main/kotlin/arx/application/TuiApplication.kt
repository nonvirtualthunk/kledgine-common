package arx.application

import arx.core.*
import arx.display.ascii.Ascii
import arx.display.ascii.AsciiGraphics
import arx.display.ascii.AsciiGraphicsComponent
import arx.display.ascii.AsciiGraphicsComponentBase
import arx.display.core.Key
import arx.display.core.KeyModifiers
import arx.display.windowing.AsciiWindowingSystemComponent
import arx.display.windowing.WidgetPosition
import arx.display.windowing.WindowingSystem
import arx.display.windowing.components.ascii.AsciiBackground
import arx.display.windowing.components.ascii.AsciiRichText
import arx.display.windowing.components.registerCustomWidget
import arx.display.windowing.customwidgets.LabelledTextInput
import arx.display.windowing.onEventDo
import arx.engine.*
import org.fusesource.jansi.AnsiConsole
import org.jline.jansi.Ansi
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.awt.Font
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport


data class TuiData (
    var debugText: AsciiRichText
) : DisplayData, CreateOnAccessData {
    companion object : DataType<TuiData>( TuiData(AsciiRichText()), sparse = true )
    override fun dataType() : DataType<*> { return TuiData }
}

data class TerminalInput (var codes: List<Char>, var unhandled: Boolean = false) : DisplayEvent()


class TuiApplication(val terminal: Terminal) {
    init {
        Application.tui = true
        AnsiConsole.systemInstall()
    }

    private var engine: Engine = Engine()

    private var mousePosition: Vec2f = Vec2f(0.0f, 0.0f)

    @Volatile
    var clearColor: RGBA = RGBAf(0.5f, 0.5f, 0.5f, 1.0f)

    @Volatile
    var shouldClose: Boolean = false

    companion object {
        val debugTextBuffer : ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    }

    init {
//        System.setProperty("java.awt.headless", "true")
        ConfigRegistration
    }

    fun run(engine: Engine, postSetup: World.() -> Unit = {}) {
        this.engine = engine

        init()

        engine.world.postSetup()

        loop()
    }

    private fun init() {
        Application.tuiSize = Vec2i(terminal.width - 1, terminal.height - 1)

        engine.initialize()
    }


    private fun loop() {
        val eventBuffer = ConcurrentLinkedQueue<Event>()

        val debugStr = AtomicReference("")

        var carryover : Char? = null

        val inputThread = Thread {
            val ba = ByteArray(100)
            var remaining = -1
            while (!shouldClose) {
                if (carryover != null) {
                    ba[0] = carryover!!.code.toByte()
                    carryover = null
                } else {
                    remaining = terminal.input().read(ba, 0, 1)
                    if (remaining <= 0) {
                        break
                    }
                }

                val b = ba[0]

                var code : Int = b.toInt()
                val c = b.toInt().toChar()
                var modifiers = KeyModifiers()
                if (c.isLetter()) {
                    if (c.isUpperCase()) {
                        modifiers = KeyModifiers(true, modifiers.ctrl, modifiers.alt)
                    }
                    code = c.uppercaseChar().code
                }

                if (code == 27) { // esc
                    val codes = mutableListOf<Char>(27.toChar())
                    while (true) {
                        val next = terminal.reader().read(1)
                        if (next == -1 || next == -2) {
                            break
                        }

                        if (next == 27) {
                            carryover = next.toChar()
                            break
                        } else {
                            codes.add(next.toChar())
                        }
                    }
//
                    if (codes.size > 1) {
                        var handled = false
                        if (codes[1].code == 91) {
                            var i = 2
                            if (codes.size > i) {
                                if (codes[i].code == 49) {
                                    modifiers = KeyModifiers(true, modifiers.ctrl, modifiers.alt)
                                    i += 3
                                }

                                if (codes.size > i) {
                                    val key = when (codes[i].code) {
                                        68 -> Key.Left
                                        67 -> Key.Right
                                        65 -> Key.Up
                                        66 -> Key.Down
                                        else -> null
                                    }

                                    if (key != null) {
                                        handled = true
                                        eventBuffer.add(KeyPressEvent(key, modifiers))
                                        eventBuffer.add(KeyReleaseEvent(key, modifiers))
                                    }
                                }
                            }
                        }

                        eventBuffer.add(TerminalInput(codes, ! handled))
                        continue
                    }
                }


                val (key, char) = when(code) {
                    127 -> Key.Backspace to null
                    10, 13 -> Key.Enter to '\n'
                    17 -> {
                        shouldClose = true
                        modifiers = KeyModifiers(modifiers.shift, true, modifiers.alt)
                        Key.Q to 'q'
                    }
                    else -> {
                        Key.codesToEnums[code] to c
                    }
                }

//                debugStr.set(debugStr.get() + "," + code)

                key?.let {
                    eventBuffer.add(KeyPressEvent(it, modifiers, false))
                    eventBuffer.add(KeyReleaseEvent(it, KeyModifiers()))
                }

                eventBuffer.add(TerminalInput(listOf(c)))
                char?.let { eventBuffer.add(CharInputEvent(it)) }
            }
        }

        inputThread.start()


        var lastUpdated = System.currentTimeMillis() * 1000.0
        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while (!shouldClose) {
            while (System.currentTimeMillis() * 1000.0 - lastUpdated < 0.0166) {
                val delta = 0.01666666666 - (System.currentTimeMillis() * 1000.0 - lastUpdated)
                val effDelta = Math.max(delta * 0.5f, 0.0001)
                LockSupport.parkNanos((effDelta * 1000000000L).toLong())
            }

            lastUpdated = System.currentTimeMillis() * 1000.0

            engine.updateGameState()

            if (engine.updateDisplayState()) {
                engine.draw()
            } else {
                Thread.sleep(5)
            }

            AnsiConsole.out().print(Ansi.ansi().fgRgb(255,255,255).cursor(1,1).a(debugStr.get()))

            while (eventBuffer.isNotEmpty()) {
                val e = eventBuffer.remove()
                engine.handleEvent(e)
            }

            while (debugTextBuffer.isNotEmpty()) {
                engine.world[TuiData].debugText = AsciiRichText(debugTextBuffer.remove())
            }
        }

        inputThread.interrupt()
    }
}



object TuiTest : DisplayComponent(initializePriority = Priority.Last) {



    override fun initialize(world: World) {
        eventPriority = Priority.First

        val ws = world[WindowingSystem]
        ws.forceScale = 1
        ws.desktop[AsciiBackground]?.style = ValueBindable(Ascii.BoxStyle.Line)

        registerCustomWidget(LabelledTextInput)

        ws.desktop.createWidget("TUITest.Main").apply {
            takeFocus()
        }

        val AG = world[AsciiGraphics]
//        val font = Font("monaco", 0, 12)
//        AG.font = ArxFont(font, 12, ArxTypeface(font))
//        ws.font = AG.font
    }

    var t = 0
    override fun update(world: World): Boolean {
        val ws = world[WindowingSystem]

        if (world[TuiData].debugText.isNotEmpty()) {
            ws.desktop.bind("debugStr", world[TuiData].debugText)
        } else {
            ws.desktop.unbind("debugStr")
        }


        t++
        if (t % 20 == 0) {
            ws.desktop.descendantWithIdentifier("Image")?.let { w ->
                w.x = WidgetPosition.Fixed(t / 20 + 1)
            }
        }

        return false
    }

    override fun handleEvent(world: World, event: Event): Boolean {
        val tuid = world[TuiData]

        when (event) {
            is TerminalInput -> {
                val unhandledClause = if (event.unhandled) { " [unhandled]" } else { "" }
                tuid.debugText = AsciiRichText("Most recent codes: ${event.codes.map { it.code }}$unhandledClause")
            }
            is CharInputEvent -> {
                tuid.debugText = tuid.debugText + AsciiRichText(" | char input: ${event.char}")
            }
        }
        return false
    }
}

fun main(args: Array<String>) {
    AnsiConsole.systemInstall()

    val terminal = TerminalBuilder.builder()
        .system(true)
        .build()

    try {

        AnsiConsole.out().print("\u001B[?25l")

        terminal.enterRawMode()

        TuiApplication(terminal).apply {

        }.run(
            Engine(
                mutableListOf(),
                mutableListOf(AsciiGraphicsComponentBase(), AsciiWindowingSystemComponent(), TuiTest)
            )
        )

    } finally {
        AnsiConsole.out().print("\u001B[?25h")
        terminal.close()
    }





//    val tui = TuiApplication()
//
//
//    Application
//
//    val out = AnsiConsole.out()
//
//    out.println(Ansi.ansi().cursor(1,1).eraseScreen().reset())
//
//    out.println(Ansi.ansi().fg(5).cursorToColumn(1).a("T"))
//    out.println(Ansi.ansi().fg(5).cursorToColumn(2).a("H"))
//    out.println(Ansi.ansi().fg(5).cursorToColumn(3).a("E"))
////    out.println(Ansi.ansi().cursor)
//
//    out.print("\u001B[?25l")
//
//    for (i in 0 until 10) {
//        val sb = StringBuilder()
//        for(j in 0 until i) {
//            sb.append(Ascii.FullBlockChar)
//        }
//        out.println(Ansi.ansi().cursorToColumn(0).a(sb.toString()))
//    }
//
//    out.print(Ansi.ansi().fgRgb(200,200,200).cursor(5,15).a("${terminal.width} x ${terminal.height}"))
//
//    terminal.enterRawMode()
//
//    val ba = ByteArray(1)
//    while (terminal.input().read(ba, 0, 1) > 0) {
//        val b = ba[0]
//
//        out.println(Ansi.ansi().fgRgb(100, 200, 100).cursor(20,20).a("${b.toInt().toChar()}"))
//        out.flush()
//    }
//
//Thread.sleep(5000)
//
//    AnsiConsole.systemUninstall()
}