package arx.display.windowing.components

import arx.core.*
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiDrawCommand
import arx.display.core.Key
import arx.display.core.MouseButton
import arx.display.windowing.*
import arx.engine.*
import com.typesafe.config.ConfigValue
import java.util.*


enum class ScrollbarShowStrategy {
    Always,
    WhenNeeded,
    Never;

    companion object : FromConfigCreator<ScrollbarShowStrategy> {
        override fun createFromConfig(cv: ConfigValue?): ScrollbarShowStrategy? {
            return if (cv.isBool()) {
                tern(cv.asBool() ?: false, Always, Never)
            } else if (cv.isStr()) {
                when (cv.asStr()?.lowercase()) {
                    "always" -> Always
                    "never" -> Never
                    "whenneeded", "when needed" -> WhenNeeded
                    else -> {
                        Noto.warn("Invalid string representation of ScrollbarShowStrategy ${cv.asStr()}")
                        null
                    }
                }
            } else {
                Noto.warn("Invalid config representation of ScrollbarShowStrategy $cv")
                null
            }
        }
    }
}

data class ScrollbarData (
    var verticalScroll : Boolean = true,
    var horizontalScroll : Boolean = false,
    var showScrollbar : ScrollbarShowStrategy = ScrollbarShowStrategy.WhenNeeded,
    var scrollSpeed : Int = 1,
    var keyScrollActive : Vec2i = Vec2i(0,0),
    var buttonScrollActive : Vec2i = Vec2i(0,0),
    var scrollMin : Vec2i = Vec2i(0,0),
    var scrollMax : Vec2i = Vec2i(0,0)
) : DisplayData {
    companion object : DataType<ScrollbarData>( ScrollbarData(),sparse = true ), FromConfigCreator<ScrollbarData> {
        override fun createFromConfig(cv: ConfigValue?): ScrollbarData? {
            if (cv == null) {
                return null
            }

            val scrollCV = cv["scrollbar"] ?: cv["scrollBar"] ?: cv["showScrollbar"] ?: return null
            val showStrat = ScrollbarShowStrategy(scrollCV)
            if (showStrat != null) {
                return ScrollbarData(
                    showScrollbar = showStrat,
                    scrollSpeed = cv["scrollSpeed"].asInt() ?: 1,
                    verticalScroll = cv["verticalScroll"].asBool() ?: true,
                    horizontalScroll = cv["horizontalScroll"].asBool() ?: true
                )
            } else {
                return null
            }
        }
    }
    override fun dataType() : DataType<*> { return ScrollbarData }

    fun copy() : ScrollbarData {
        return copy(keyScrollActive = Vec2i(0,0))
    }
}


object ScrollWindowingComponent : WindowingComponent {
    val ArrowColor = RGBA(50, 50, 50, 255)

    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(ScrollbarData)
    }

    fun shouldShowScrollbar(w: Widget, sb: ScrollbarData) : Boolean {
        return when(sb.showScrollbar) {
            ScrollbarShowStrategy.Always -> true
            ScrollbarShowStrategy.WhenNeeded -> {
                var ret = false
                for (c in w.children) {
                    if ((sb.horizontalScroll && sb.scrollMin.x != sb.scrollMax.x) ||
                        (sb.verticalScroll && sb.scrollMin.y != sb.scrollMax.y)) {
                        ret = true
                        break
                    }
                }
                ret
            }
            ScrollbarShowStrategy.Never -> false
        }
    }


    override fun clientOffsetContributionNear(w: Widget, axis: Axis2D): Int? {
        return null
    }

    override fun clientOffsetContributionFar(w: Widget, axis: Axis2D): Int? {
        val sb = w[ScrollbarData] ?: return null

        if (shouldShowScrollbar(w, sb)) {
            if (sb.verticalScroll && axis == Axis2D.X) {
                return w.windowingSystem.effectiveScale()
            } else if (sb.horizontalScroll && axis == Axis2D.Y) {
                return w.windowingSystem.effectiveScale()
            } else {
                return null
            }
        } else {
            return null
        }
    }

    override fun drawPriority(): Priority {
        return Priority.Low
    }

    override fun eventPriority(): Priority {
        return Priority.Low
    }

    override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
        val sb = w[ScrollbarData] ?: return

        if (shouldShowScrollbar(w, sb)) {
            val scale = w.windowingSystem.effectiveScale()
            if (sb.verticalScroll) {
                val x = w.resClientX + w.resClientWidth + w.padding.x
                val y = w.resClientY
                val z = w.resZ
                // note: this repeat isn't quite right, but we're glossing over that for the moment
                commandsOut.add(AsciiDrawCommand.GlyphRepeat('█', Vec3i(x,y,z), Vec2i(scale, w.resClientHeight), scale, White, Clear))
                commandsOut.add(AsciiDrawCommand.Glyph('▲', Vec3i(x,y,z), scale, ArrowColor, White))
                commandsOut.add(AsciiDrawCommand.Glyph('▬', Vec3i(x,y + w.resClientHeight / 2, z), scale, ArrowColor, White))
                commandsOut.add(AsciiDrawCommand.Glyph('▼', Vec3i(x,y + w.resClientHeight - scale,z), scale, ArrowColor, White))
            }
            // TODO: Draw horizontal scroll
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val sb = w[ScrollbarData] ?: return false

        val show = lazy { shouldShowScrollbar(w, sb) }

        return when (event) {
            is WidgetKeyPressEvent -> {
                if (event.key == Key.Up && sb.verticalScroll && show.value) {
                    if (w.localY < -sb.scrollMin.y || sb.keyScrollActive.y != 0) {
                        sb.keyScrollActive.y = 1
                        true
                    } else {
                        false
                    }
                } else if (event.key == Key.Down && sb.verticalScroll && show.value) {
                    if (w.localY > -sb.scrollMax.y || sb.keyScrollActive.y != 0) {
                        sb.keyScrollActive.y = -1
                        true
                    } else {
                        false
                    }
                } else if (event.key == Key.Left && sb.horizontalScroll && show.value) {
                    if (w.localX < -sb.scrollMin.x || sb.keyScrollActive.x != 0) {
                        sb.keyScrollActive.x = 1
                        true
                    } else {
                        false
                    }
                } else if (event.key == Key.Right && sb.horizontalScroll && show.value) {
                    if (w.localX > -sb.scrollMax.x || sb.keyScrollActive.x != 0) {
                        sb.keyScrollActive.x = -1
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            is WidgetMousePressEvent -> {
                // todo: detect hit of button area
                false
            }
            is WidgetMouseScrollEvent -> {
                scroll(w, sb, (event.delta.x * 10.0f).toInt(), (event.delta.y * 10.0f).toInt())
                true
            }
            is FocusChangedEvent -> {
                if (event.hasFocus) {
                    val o = event.originWidget
                    if (o.resY < w.resClientY) {
                        scroll(w, sb, 0, w.resClientY - o.resY)
                    } else if (o.resY + o.resHeight > w.resClientY + w.resClientHeight) {
                        val wy = o.resY + o.resHeight
                        val py = w.resClientY + w.resClientHeight
                        scroll(w, sb, 0, py - wy)
                    }

                    if (o.resX < w.resClientX) {
                        scroll(w, sb, w.resClientX - o.resX, 0)
                    } else if (o.resX + o.resWidth > w.resClientX + w.resClientWidth) {
                        val wx = o.resX + o.resWidth
                        val px = w.resClientX + w.resClientWidth
                        scroll(w, sb, px - wx, 0)
                    }
                }
                false
            }
            else -> false
        }
    }

    fun scroll(w: Widget, sb: ScrollbarData, rawDx: Int, rawDy: Int) {
        val dx = if (sb.horizontalScroll) { rawDx } else { 0 }
        val dy = if (sb.verticalScroll) { rawDy } else { 0 }
        if (dx == 0 && dy == 0) {
            return
        }

        updateScrollRegion(w)

        w.localX = (w.localX + dx).clamp(-sb.scrollMax.x, -sb.scrollMin.x)
        w.localY = (w.localY + dy).clamp(-sb.scrollMax.y, -sb.scrollMin.y)
    }

    var widgetIdsWithScroll = mutableSetOf<Int>()
    var counter = 0
    override fun update(windowingSystem: WindowingSystem) {
        counter++

        if (counter % 2 == 0) {
            for (w in windowingSystem.widgetsThatHaveData(ScrollbarData)) {
                widgetIdsWithScroll.add(w.entity.id)
                val sb = w[ScrollbarData]!!

                if (sb.keyScrollActive.y != 0) {
                    if (!Key.isDown(Key.Down) && !Key.isDown(Key.Up)) {
                        sb.keyScrollActive.y = 0
                    }
                }
                if (sb.buttonScrollActive.y != 0) {
                    if (!MouseButton.leftDown) {
                        sb.buttonScrollActive.y = 0
                    }
                }

                if (sb.keyScrollActive.y != 0) {
                    scroll(w, sb, 0, sb.scrollSpeed * sb.keyScrollActive.y)
                } else if (sb.buttonScrollActive.y != 0) {
                    scroll(w, sb, 0, sb.scrollSpeed * sb.buttonScrollActive.y)
                }



                if (sb.keyScrollActive.x != 0) {
                    if (!Key.isDown(Key.Left) && !Key.isDown(Key.Right)) {
                        sb.keyScrollActive.x = 0
                    }
                }
                if (sb.buttonScrollActive.x != 0) {
                    if (!MouseButton.leftDown) {
                        sb.buttonScrollActive.x = 0
                    }
                }


                val xScrollMult = if (windowingSystem is AsciiWindowingSystem) {
                    2
                } else {
                    1
                }
                if (sb.keyScrollActive.x != 0) {
                    scroll(w, sb, sb.scrollSpeed * sb.keyScrollActive.x * xScrollMult, 0)
                } else if (sb.buttonScrollActive.x != 0) {
                    scroll(w, sb, sb.scrollSpeed * sb.buttonScrollActive.x * xScrollMult, 0)
                }
            }
        }
    }

    override fun geometryUpdated(ws: WindowingSystem, completedUpdates: DependencySet, triggeringUpdates: Map<Widget, EnumSet<RecalculationFlag>>) {
        completedUpdates.forEach { d ->
            ws.widgets[d.widgetId]?.let { w ->
                w[ScrollbarData]?.let { sb ->
                    updateScrollRegion(w)
                }
            }
        }
    }

    private fun updateScrollRegion(w: Widget) {
        var minX = 0
        var minY = 0
        var maxX = 0
        var maxY = 0


        val ox = w.resClientX + w.localX
        val oy = w.resClientY + w.localY

        for (c in w.children) {
            minX = (c.resX - ox).min(minX)
            minY = (c.resY - oy).min(minY)
            maxX = maxX.max(c.resX - ox + c.resWidth)
            maxY = maxY.max(c.resY - oy + c.resHeight)
        }

        maxX = (maxX - w.resClientWidth).max(0)
        maxY = (maxY - w.resClientHeight).max(0)

        w[ScrollbarData]?.let { sb ->
            val newMin = Vec2i(minX, minY)
            val newMax = Vec2i(maxX, maxY)

            val showingBefore = shouldShowScrollbar(w, sb)
            if (newMin != sb.scrollMin || newMax != sb.scrollMax) {
                sb.scrollMin = newMin
                sb.scrollMax = newMax
            }
            val showingAfter = shouldShowScrollbar(w, sb)
            if (showingBefore != showingAfter) {
                w.markForFullUpdate()
            }

            w.localX = (w.localX).clamp(-sb.scrollMax.x, -sb.scrollMin.x)
            w.localY = (w.localY).clamp(-sb.scrollMax.y, -sb.scrollMin.y)
        }
    }

//    override fun geometryUpdated(ws: WindowingSystem, completedUpdates: DependencySet, triggeringUpdates: Map<Widget, EnumSet<RecalculationFlag>>) {
//        var toUpdate = setOf<Widget>()
//        completedUpdates.forEach { dep ->
//            ws.widgets[dep.widgetId]?.let { dw ->
//                if (widgetIdsWithScroll.contains(dw.entity.id)) {
//                    toUpdate += dw
//                } else {
//                    dw.parent?.let { dwp ->
//                        if (widgetIdsWithScroll.contains(dwp.entity.id)) {
//                            toUpdate += dwp
//                        }
//                    }
//                }
//            }
//
//            if (dep.kind  widgetIdsWithScroll.contains(dep.widgetId)) {
//                ws.widgets[dep.widgetId]?.let { w -> updateScrollRegion(w) }
//            }
//        }
//    }
}