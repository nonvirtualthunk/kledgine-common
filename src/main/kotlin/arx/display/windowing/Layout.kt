package arx.display.windowing

import arx.core.*
import com.typesafe.config.ConfigValue

sealed interface WidgetLayout {
    fun layout(w : Widget)


    companion object : FromConfigCreator<WidgetLayout> {
        internal val verticalPattern = Regex("(?i)vertical\\s?(?:\\((\\d+)\\))?")
        internal val horizontalPattern = Regex("(?i)horizontal\\s?(?:\\((\\d+)\\))?")

        override fun createFromConfig(cv: ConfigValue?): WidgetLayout? {
            if (cv == null) { return null }

            if (cv.isStr())  {
                val str = cv.asStr()!!
                verticalPattern.match(str)?.let { (offsetStr) ->
                    return VerticalLayout(offsetStr.toIntOrNull() ?: 0)
                }
                horizontalPattern.match(str)?.let { (offsetStr) ->
                    return HorizontalLayout(offsetStr.toIntOrNull() ?: 0)
                }

                Noto.err("Unknown format for WidgetLayout : \"$cv\"")
                return null
            } else {
                Noto.err("Unknown format for WidgetLayout : $cv")
                return null
            }
        }
    }
}



data class VerticalLayout(val offset : Int = 0) : WidgetLayout {
    override fun layout(w: Widget) {

        for (i in 1 until w.children.size) {
            w.children[i].y = WidgetPosition.Relative(w.children[i-1], offset)
        }
    }
}

data class HorizontalLayout(val offset : Int = 0) : WidgetLayout {
    override fun layout(w: Widget) {

        for (i in 1 until w.children.size) {
            w.children[i].x = WidgetPosition.Relative(w.children[i-1], offset)
        }
    }
}