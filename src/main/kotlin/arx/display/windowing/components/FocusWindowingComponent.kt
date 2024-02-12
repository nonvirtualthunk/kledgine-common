package arx.display.windowing.components

import arx.core.*
import arx.display.ascii.Ascii
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiDrawCommand
import arx.display.core.Key
import arx.display.core.KeyModifiers
import arx.display.windowing.*
import arx.display.windowing.components.ascii.AsciiBackground
import arx.engine.*
import com.typesafe.config.ConfigValue
import kotlin.math.absoluteValue

data class FocusSettings (
    val acceptsFocus : Boolean = false,
    val focusedOverlay : NineWayImage? = null,
    val focusedColor: RGBA? = RGBA(160,160,240,255),
    var tabbable: Boolean? = null,
    var tabContext: Boolean = false,
    var useArrowNavigation: Boolean = false,
    var tabOffset: Int = 0,
    var shortcut: Pair<Key, KeyModifiers>? = null
) : DisplayData {
    companion object : DataType<FocusSettings>( FocusSettings(), sparse = true ), FromConfigCreator<FocusSettings> {
        override fun createFromConfig(cv: ConfigValue?): FocusSettings? {
            if (cv == null || (cv["acceptsFocus"] == null && cv["focusedOverlay"] == null && cv["tabbable"] == null && cv["tabContext"] == null && cv["tabOffset"] == null && cv["shortcut"] == null)) { return null }

            val shortcut = cv["shortcut"].asStr()?.let { s ->
                try {
                    if (s.toIntOrNull() != null) {
                        Key.valueOf("Key${s.toInt()}")
                    } else {
                        Key.valueOf(s.uppercase())
                    }
                } catch (e : java.lang.IllegalArgumentException) {
                    null
                }
            }?.let { k -> k to KeyModifiers(alt = true)}

            return FocusSettings(
                acceptsFocus = cv["acceptsFocus"].asBool() ?: (cv["tabbable"].asBool()) ?: false,
                focusedOverlay = cv["focusedOverlay"]?.let { NineWayImage(it) },
                tabbable = cv["tabbable"].asBool(),
                tabContext = cv["tabContext"].asBool() ?: false,
                tabOffset = cv["tabOffset"].asInt() ?: 0,
                useArrowNavigation = cv["useArrowNavigation"].asBool() ?: false,
                shortcut = shortcut
            )
        }
    }
    override fun dataType() : DataType<*> { return FocusSettings }
}

operator fun FocusSettings?.unaryPlus() : FocusSettings {
    return this?: FocusSettings.defaultInstance
}


data class FocusChangedEvent(val hasFocus: Boolean, val src : DisplayEvent?) : WidgetEvent(src)

class RequestCycleFocusEvent(val delta: Int = 1, src: DisplayEvent? = null) : WidgetEvent(src)
class GiveUpFocusEvent() : WidgetEvent(null)


object FocusWindowingComponent : WindowingComponent {

    var lastEventToGiveFocus : WidgetEvent? = null

    override fun dataTypes(): List<DataType<EntityData>> {
        return listOf(FocusSettings)
    }

    override fun drawPriority(): Priority {
        return Priority.Low
    }

    override fun eventPriority(): Priority {
        return Priority.Low
    }

    override fun render(ws: WindowingSystem, w: Widget, bounds: Recti, quadsOut: MutableList<WQuad>) {
        val fs = w[FocusSettings] ?: return
        fs.focusedOverlay?.let { overlay ->
            if (ws.focusedWidget == w) {
                BackgroundComponent.renderNineWay(w, overlay, false, quadsOut)
            }
        }
    }

    override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
        if (ws.focusedWidget == w && w.background.draw()) {
            val fs = w[FocusSettings] ?: return
            val ab = w[AsciiBackground]
            fs.focusedColor?.let { color ->
                commandsOut.add(
                    AsciiDrawCommand.Box(
                        position = w.resolvedPosition,
                        dimensions = w.resolvedDimensions,
                        style = ab?.style?.invoke() ?: Ascii.BoxStyle.SolidExternal,
                        scale = ws.effectiveScale(ab?.scale),
                        edgeColor = color,
                        fillColor = Clear,
                        join = false
                    )
                )
            }
        }
    }

    data class TabbingOrder(val primary: Int, val secondary: Int) : Comparable<TabbingOrder> {
        constructor(w: Widget) : this(w.resY - (w[FocusSettings]?.tabOffset ?: 0), w.resX)

        override fun compareTo(other: TabbingOrder): Int {
            return compareValuesBy(this, other, TabbingOrder::primary, TabbingOrder::secondary)
        }
    }

    private fun allTabbableOptions(w: Widget, accum : MutableList<Pair<Widget, TabbingOrder>> = mutableListOf()) : List<Pair<Widget, TabbingOrder>> {
        for (c in w.children) {
            if (! c.showing()) {
                continue
            }

            val fs = c[FocusSettings]
            if (fs != null) {
                // if we hit a sub-tab-context then don't delve any further, just add it to the list
                if (fs.tabContext) {
                    accum.add(c to TabbingOrder(c))
                    continue
                } else if (fs.tabbable == true && fs.acceptsFocus) {
                    accum.add(c to TabbingOrder(c))
                }
            }
            allTabbableOptions(c, accum)
        }

        return accum
    }

    fun cycleTabWithinTabContext(delta: Int, tc: Widget, startPoint: TabbingOrder?, rollover: Boolean = false) : Boolean {
        val ws = tc.windowingSystem

        val allTabbableOptions = allTabbableOptions(tc)

        val sortedTabbable = allTabbableOptions.sortedBy { it.second }

        if (rollover) {
            if (delta > 0) {
                sortedTabbable.firstOrNull()
            } else {
                sortedTabbable.lastOrNull()
            }?.let { (s, _) ->
                if (s[FocusSettings]?.tabContext == true) {
                    cycleTabWithinTabContext(delta, s, null)
                } else {
                    cycleFocusTo(s)
                }
            }
            return true
        }

        val fw = ws.focusedWidget
        val curPoint = startPoint ?: if (fw == null || !fw.isDescendantOf(tc)) {
            TabbingOrder(-1000 * delta, -1000 * delta)
        } else {
            TabbingOrder(fw)
        }

        if (delta > 0) {
            for ((s, to) in sortedTabbable) {
                if (to > curPoint) {
                    if (s[FocusSettings]?.tabContext == true) {
                        cycleTabWithinTabContext(delta, s, null)
                    } else {
                        cycleFocusTo(s)
                    }
                    return true
                }
            }
            return false
        } else {
            for ((s, to) in sortedTabbable.reversed()) {
                if (to < curPoint) {
                    if (s[FocusSettings]?.tabContext == true) {
                        cycleTabWithinTabContext(delta, s, null)
                    } else {
                        cycleFocusTo(s)
                    }
                    return true
                }
            }
            return false
        }
    }

    fun cycleTab(w: Widget, delta: Int?, vec: Vec2i? = null) : Boolean {
        val tabContexts = w.selfAndAncestors().filter { it[FocusSettings]?.tabContext == true }.toList()

        if (delta != null) {
            var startPoint : TabbingOrder? = null
            for (i in 0 until tabContexts.size) {
                val tc = tabContexts[i]

                if (cycleTabWithinTabContext(delta, tc, startPoint)) {
                    return true
                } else if (i == tabContexts.size - 1) {
                    cycleTabWithinTabContext(delta, tc, startPoint = null, rollover = true)
                    return false
                }
                startPoint = TabbingOrder(tc)
            }
            return false
        }


        for (i in 0 until tabContexts.size) {
            val tc = tabContexts[i]

            val ws = tc.windowingSystem
//            val curPoint = ws.focusedWidget?.let { TabbingOrder(it) } ?: TabbingOrder(-1000,-1000)

            val allTabbableOptions = tc.descendants()
                .filter { it[FocusSettings]?.tabbable == true && it[FocusSettings]?.acceptsFocus == true && it.isVisible() }
                .map { it to TabbingOrder(it) }
                .toList()

            if (vec != null) {
                val basePoint = ws.focusedWidget?.resolvedPosition?.xy ?: Vec2i(0,0)
                val baseDims = ws.focusedWidget?.resolvedDimensions ?: Vec2i(0,0)
                val farPoint = basePoint + baseDims
                val midPoint = (basePoint + farPoint) / 2

                fun overlaps(a: Widget, axis: Axis2D) : Boolean {
                    val b = ws.focusedWidget ?: return true

                    val na = a.resolvedPosition[axis]
                    val fa = na + a.resolvedDimensions[axis]

                    val nb = b.resolvedPosition[axis]
                    val fb = nb + b.resolvedDimensions[axis]


//                        [0   5]
//                           [3   10]
                    return fa >= nb && fb >= na
                }

                val filteredTabbableOptions = allTabbableOptions.filter { t ->
//                        (vec.x == 0 || vec.x.sign == (t.first.resX - basePoint.x).sign) &&
//                        (vec.y == 0 || vec.y.sign == (t.first.resY - basePoint.y).sign)

                    if (vec.x > 0) {
                        t.first.resX >= farPoint.x && overlaps(t.first, Axis2D.Y)
                    } else if (vec.y > 0) {
                        t.first.resY >= farPoint.y && overlaps(t.first, Axis2D.X)
                    } else if (vec.x < 0) {
                        t.first.resX + t.first.resWidth <= basePoint.x && overlaps(t.first, Axis2D.Y)
                    } else {
                        t.first.resY + t.first.resHeight <= basePoint.y && overlaps(t.first, Axis2D.X)
                    }
                }.ifEmpty {
                    allTabbableOptions.filter { t ->
                        if (vec.x > 0) {
                            t.first.resX >= farPoint.x
                        } else if (vec.y > 0) {
                            t.first.resY >= farPoint.y
                        } else if (vec.x < 0) {
                            t.first.resX + t.first.resWidth <= basePoint.x
                        } else {
                            t.first.resY + t.first.resHeight <= basePoint.y
                        }
                    }
                }


                filteredTabbableOptions.minByOrNull { t ->
                    val tx = t.first.resX
                    val ty = t.first.resY
                    val fx = tx + t.first.resWidth
                    val fy = ty + t.first.resHeight
                    val mx = tx + t.first.resWidth / 2
                    val my = ty + t.first.resHeight / 2

                    val d = if (vec.x > 0) {
                        (tx - farPoint.x).absoluteValue * 10 + (my - midPoint.y).absoluteValue
                    } else if (vec.y > 0) {
                        (ty - farPoint.y).absoluteValue * 10 + (mx - midPoint.x).absoluteValue
                    } else if (vec.x < 0) {
                        (fx - basePoint.x).absoluteValue * 10 + (my - midPoint.y).absoluteValue
                    } else {
                        (fy - basePoint.y).absoluteValue * 10 + (mx - midPoint.x).absoluteValue
                    }
                    d.absoluteValue
                }?.let { closest ->
                    cycleFocusTo(closest.first)
                    return true
                }
            }
            return false
        }
        return false
    }

    fun cycleFocusTo(w: Widget) {
        w.takeFocus()
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val fs = w[FocusSettings]
        when(event) {
            is WidgetMousePressEvent -> {
                val focusSettings = fs ?: return false
                if (focusSettings.acceptsFocus && w.isVisible() && lastEventToGiveFocus != event) {
                    lastEventToGiveFocus = event
                    w.windowingSystem.giveFocusTo(w, event)
                }
            }
            is WidgetKeyPressEvent -> {
                if (event.key == Key.Tab) {
                    cycleTab(w, tern(event.mods.shift, -1, 1))
                    return true
                } else if (event.key == Key.Escape) {
                    if (w.windowingSystem.focusedWidget == w) {
                        w.giveUpFocus()
                    }
                } else {
                    // if we've reached a tab context then look for shortcuts and
                    // arrow based tabbing navigation
                    if (w[FocusSettings]?.tabContext == true) {
                        when (event.key) {
                            Key.Down, Key.Up, Key.Left, Key.Right -> {
                                if (fs?.useArrowNavigation == true) {
                                    val vec = when (event.key) {
                                        Key.Up -> Vec2i(0, -1)
                                        Key.Down -> Vec2i(0, 1)
                                        Key.Left -> Vec2i(-1, 0)
                                        Key.Right -> Vec2i(1, 0)
                                        else -> throw java.lang.IllegalStateException("Invalid direction")
                                    }
                                    return cycleTab(w, null, vec)
                                }
                            }
                            else -> {
                                for (d in w.descendants()) {
                                    d[FocusSettings]?.shortcut?.let { shortcut ->
                                        if (shortcut.first == event.key && shortcut.second == event.mods && d.isVisible()) {
                                            d.takeFocus()
                                            return true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            is FocusChangedEvent -> {
                if (w[FocusSettings] != null) {
                    w.markForUpdate(RecalculationFlag.Contents)
                }
            }
            is WidgetVisibilityEvent -> {
                if (! event.showing && event.originWidget == w) {
                    w.windowingSystem.focusedWidget?.let { fw ->
                        if (w == fw || w.hasDescendant(fw)) {
                            cycleTab(fw, -1)
                        }
                    }
                }
            }
            is RequestCycleFocusEvent -> {
                w.windowingSystem.focusedWidget?.let { fw ->
                    cycleTab(fw, event.delta)
                }
                return true
            }
            is GiveUpFocusEvent -> {
                for (a in w.ancestors()) {
                    if (a.isVisible() && a[FocusSettings]?.tabContext == true) {
                        w.windowingSystem.giveFocusTo(null, event)
                        cycleTab(a, 1)
                        return true
                    }
                }

                for (a in w.siblingsAndTheirDescendants()) {
                    if (a.isVisible() && a[FocusSettings]?.acceptsFocus == true) {
                        w.windowingSystem.giveFocusTo(a, event)
                        return true
                    }
                }

                for (a in w.ancestors()) {
                    if ((a.isVisible() && a[FocusSettings]?.acceptsFocus == true) || a.parent == null) {
                        w.windowingSystem.giveFocusTo(a, event)
                        return true
                    }
                }
                w.windowingSystem.giveFocusTo(null, event)
                return true
            }
        }
        return false
    }

    override fun update(windowingSystem: WindowingSystem) {
        windowingSystem.focusedWidget?.let { fw ->
            if (! fw.isVisible()) {
                fw.giveUpFocus()
            }
        }
    }
}