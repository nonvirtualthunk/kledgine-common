package arx.display.core

import org.lwjgl.glfw.GLFW.*

enum class MouseButton(val code: Int) {
    Left(GLFW_MOUSE_BUTTON_LEFT),
    Right(GLFW_MOUSE_BUTTON_RIGHT),
    Middle(GLFW_MOUSE_BUTTON_MIDDLE);

    companion object {
        fun fromGLFW(v : Int) : MouseButton {
            return when (v) {
                GLFW_MOUSE_BUTTON_LEFT -> Left
                GLFW_MOUSE_BUTTON_RIGHT -> Right
                GLFW_MOUSE_BUTTON_MIDDLE -> Middle
                else -> Middle
            }
        }

        @Volatile var leftDown = false
        @Volatile var rightDown = false
        @Volatile var middleDown = false

        fun setIsDown(mb: MouseButton, isDown: Boolean) {
            when (mb) {
                Left -> leftDown = isDown
                Right -> rightDown = isDown
                Middle -> middleDown = isDown
            }
        }

        fun isDown(mb: MouseButton) : Boolean {
            return when (mb) {
                Left -> leftDown
                Right -> rightDown
                Middle -> middleDown
                else -> false
            }
        }
    }

}