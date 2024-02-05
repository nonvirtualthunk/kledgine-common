package arx.display.windowing.customwidgets

import arx.application.Application
import arx.core.RGBA
import arx.display.ascii.AsciiGraphicsComponent
import arx.display.windowing.AsciiWindowingSystemComponent
import arx.display.windowing.Widget
import arx.display.windowing.WindowingSystem
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.registerCustomWidget
import arx.engine.DisplayComponent
import arx.engine.Engine
import arx.engine.World

object CustomWidgets {
    fun registerCustomWidgets() {
        registerCustomWidget(LabelledTextInput)
    }
}



fun testAsciiCustomWidget(cw: CustomWidget, f : Widget.() -> Unit) {
    testAsciiCustomWidget(cw, cw.archetype, f)
}



fun testAsciiCustomWidget(cw: CustomWidget, arch: String, f : Widget.() -> Unit) {
    CustomWidgets.registerCustomWidgets()
    AsciiCustomWidgets.registerCustomWidgets()
    registerCustomWidget(cw)


    Application(windowWidth = 1680, windowHeight = 1000)
        .apply {
            clearColor = RGBA(0,0,0,255)
        }
        .run(
            Engine(
                mutableListOf(),
                mutableListOf(AsciiGraphicsComponent(), AsciiWindowingSystemComponent(), object : DisplayComponent() {
                    override fun World.initializeWithWorld() {
                        val ws = global(WindowingSystem)
                        val w = ws.createWidget(arch)

                        w.f()
                    }
                })
            )
        )

}