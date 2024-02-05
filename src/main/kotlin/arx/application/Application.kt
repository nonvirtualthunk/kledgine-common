package arx.application

import arx.core.*
import arx.display.core.Key
import arx.display.core.KeyModifiers
import arx.display.core.KeyModifiers.Companion.activeModifiers
import arx.display.core.MouseButton
import arx.engine.*
import org.lwjgl.Version
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWVidMode
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL20
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer
import java.util.concurrent.locks.LockSupport

class Application(val windowWidth : Int = 800, val windowHeight : Int = 800) {
    private var engine: Engine = Engine()

    private var mousePosition: Vec2f = Vec2f(0.0f, 0.0f)

    @Volatile
    var clearColor : RGBA = RGBAf(0.5f,0.5f,0.5f,1.0f)

    init {
        System.setProperty("java.awt.headless", "true")
        ConfigRegistration
    }

    companion object {
        var frameBufferSize = Vec2i(0,0)
        var windowSize = Vec2i(0,0)
        var window : Long = 0L
        var cursorShowing : Boolean = true
        var tui: Boolean = false
        var tuiSize = Vec2i(0,0)
    }

    fun run(engine: Engine, postSetup: World.() -> Unit = {}) {
        this.engine = engine

        System.out.println("Hello LWJGL " + Version.getVersion() + "!")
        init()

        engine.world.postSetup()

        loop()

        // Free the window callbacks and destroy the window
        Callbacks.glfwFreeCallbacks(window)
        glfwDestroyWindow(window)

        // Terminate GLFW and free the error callback
        glfwTerminate()

        glfwSetErrorCallback(null)!!.free()
    }

    private fun init() {
        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set()

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        check(glfwInit()) { "Unable to initialize GLFW" }

        // Configure GLFW
        glfwDefaultWindowHints() // optional, the current window hints are already the default
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE) // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE) // the window will be resizable

        // Create the window
        window = glfwCreateWindow(windowWidth, windowHeight, "Hello World!", MemoryUtil.NULL, MemoryUtil.NULL)
        if (window == MemoryUtil.NULL) throw RuntimeException("Failed to create the GLFW window")

        // Setup a key callback. It will be called every time a key is pressed, repeated or released.
        glfwSetKeyCallback(window) { window, key, scancode, action, mods ->
            if (key == GLFW_KEY_Q && action == GLFW_PRESS && (mods and GLFW_MOD_CONTROL) != 0) {
                glfwSetWindowShouldClose(window, true)
            } else if (key == GLFW_KEY_F7) {
                Metrics.print()
            }
            val ke = Key.fromGLFW(key)
            activeModifiers = KeyModifiers(mods)

            when (action) {
                GLFW_PRESS -> {
                    engine.handleEvent(KeyPressEvent(ke, activeModifiers))
                    Key.setIsDown(ke, true)
                }
                GLFW_RELEASE -> {
                    engine.handleEvent(KeyReleaseEvent(ke, activeModifiers))
                    Key.setIsDown(ke, false)
                }
                GLFW_REPEAT -> engine.handleEvent(KeyPressEvent(ke, activeModifiers, isRepeat = true))
            }
        }

        glfwSetCharCallback(window) { window, codepoint ->
            engine.handleEvent(CharInputEvent(codepoint.toChar()))
        }

        glfwSetMouseButtonCallback(window) { _, button, action, mods ->
            activeModifiers = KeyModifiers(mods)
            val mb = MouseButton.fromGLFW(button)
            when (action) {
                GLFW_PRESS -> {
                    MouseButton.setIsDown(mb, true)
                    engine.handleEvent(MousePressEvent(mousePosition, mb, activeModifiers))
                }
                GLFW_RELEASE -> {
                    MouseButton.setIsDown(mb, false)
                    engine.handleEvent(MouseReleaseEvent(mousePosition, mb, activeModifiers))
                }
            }
        }

        glfwSetCursorPosCallback(window) { _, x, y ->
            val p = Vec2f(x.toFloat(), y.toFloat())
            val delta = p - mousePosition
            mousePosition = p
            if (MouseButton.isDown(MouseButton.Left)) {
                engine.handleEvent(MouseDragEvent(p, delta, MouseButton.Left, activeModifiers))
            } else if (MouseButton.isDown(MouseButton.Right)) {
                engine.handleEvent(MouseDragEvent(p, delta, MouseButton.Right, activeModifiers))
            } else if (MouseButton.isDown(MouseButton.Middle)) {
                engine.handleEvent(MouseDragEvent(p, delta, MouseButton.Middle, activeModifiers))
            } else {
                engine.handleEvent(MouseMoveEvent(p, delta, activeModifiers))
            }
        }

        glfwSetScrollCallback(window) { _, dx, dy ->
            engine.handleEvent(MouseScrollEvent(mousePosition, Vec2f(dx.toFloat(), dy.toFloat()), activeModifiers))
        }

        glfwSetFramebufferSizeCallback(window) { _, width, height ->
            frameBufferSize = Vec2i(width, height)
            engine.handleEvent(FrameBufferSizeChangedEvent(frameBufferSize))
        }

        glfwSetWindowSizeCallback(window) { _, width, height ->
            windowSize = Vec2i(width, height)
            engine.handleEvent(WindowSizeChangedEvent(frameBufferSize))
        }

        MemoryStack.stackPush().use { stack ->
            val pWidth: IntBuffer = stack.mallocInt(1) // int*
            val pHeight: IntBuffer = stack.mallocInt(1) // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight)
            windowSize = Vec2i(pWidth[0], pHeight[0])

            // Get the resolution of the primary monitor
            val vidmode: GLFWVidMode = glfwGetVideoMode(glfwGetPrimaryMonitor())!!

            // Center the window
            glfwSetWindowPos(
                window,
                (vidmode.width() - pWidth[0]) / 2,
                (vidmode.height() - pHeight[0]) / 2
            )

            glfwGetFramebufferSize(window, pWidth, pHeight)
            frameBufferSize = Vec2i(pWidth[0], pHeight[0])
        }

        // Make the OpenGL context current
        glfwMakeContextCurrent(window)

        // Enable v-sync
        glfwSwapInterval(1)

        // Make the window visible
        glfwShowWindow(window)

        engine.initialize()
    }


    private fun loop() {
        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        GL.createCapabilities()

        val cc = clearColor.toFloat()
        // Set the clear color
        GL20.glClearColor(cc.r, cc.g, cc.b, cc.a)
        GL20.glEnable(GL20.GL_BLEND)
        GL20.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        GL20.glViewport(0, 0, frameBufferSize.x, frameBufferSize.y)
        arx.display.core.GL.checkError()

        GL20.glDisable(GL20.GL_DEPTH_TEST)
        GL20.glDisable(GL20.GL_CULL_FACE)
        arx.display.core.GL.checkError()

        var lastUpdated = glfwGetTime()
        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while (!glfwWindowShouldClose(window)) {
            while (glfwGetTime() - lastUpdated < 0.0166) {
                val delta = 0.01666666666 - (glfwGetTime() - lastUpdated)
                val effDelta = Math.max(delta * 0.5f, 0.0001)
                LockSupport.parkNanos((effDelta * 1000000000L).toLong())
            }
//            if (curTime - lastUpdated < 0.01666666666) {
//                val delta = 0.01666666666 - (curTime - lastUpdated)
//                LockSupport.parkNanos((delta * 1000000000L).toLong())
////                Thread.sleep((delta * 1000L).toLong())
//                println("Sleeping for $delta, sleep so far: ${curTime - lastUpdated}, now: ${glfwGetTime() - lastUpdated}")
//            }
            lastUpdated = glfwGetTime()

            engine.updateGameState()

            if (engine.updateDisplayState()) {
                GL20.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT) // clear the framebuffer

                engine.draw()

                glfwSwapBuffers(window) // swap the color buffers
            } else {
                Thread.sleep(5)
            }

            // Poll for window events. The key callback above will only be
            // invoked during this call.
            glfwPollEvents()
        }
    }
}

fun enableCursor() {
    if (!Application.cursorShowing) {
        GLFW.glfwSetInputMode(Application.window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
        Application.cursorShowing = true
    }
}

fun disableCursor() {
    if (Application.cursorShowing) {
        GLFW.glfwSetInputMode(Application.window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN)
        Application.cursorShowing = false
    }
}