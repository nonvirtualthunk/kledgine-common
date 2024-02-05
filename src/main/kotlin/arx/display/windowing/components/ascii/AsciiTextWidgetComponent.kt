package arx.display.windowing.components.ascii

import arx.core.*
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiDrawCommand
import arx.display.core.Key
import arx.display.core.mix
import arx.display.windowing.*
import arx.display.windowing.components.*
import arx.engine.DataType
import arx.engine.DisplayData
import arx.engine.DisplayEvent
import arx.engine.EntityData
import com.typesafe.config.ConfigValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


//
//data class AsciiTextDisplay (
//    var
//) : DisplayData {
//    companion object : DataType<AsciiTextDisplay>( AsciiTextDisplay() )
//    override fun dataType() : DataType<*> { return AsciiTextDisplay }
//}

data class AsciiLayoutGlyph(val character : Char, val characterIndex : Int, val position: Vec2i, val foregroundColor: RGBA, val backgroundColor : RGBA, val scale: Int)

@Serializable
sealed interface AsciiRichTextSegment {
    fun plainText() : String

    fun length() : Int

    fun isEmpty() : Boolean {
        return length() == 0
    }

    fun substring(start: Int, end: Int) : AsciiRichTextSegment

    fun flatten(rt: AsciiRichText) : AsciiRichTextSegment

    fun tint(c: RGBA, f : Float): AsciiRichTextSegment

    fun withForegroundColor(c: RGBA?) : AsciiRichTextSegment
    fun capitalized(): AsciiRichTextSegment

    fun isCapitalized(): Boolean
    fun merge(segment: AsciiRichTextSegment): AsciiRichTextSegment?

    fun chars(asciiRichText: AsciiRichText): Iterator<AsciiRichChar>

    companion object {
        operator fun invoke(str: String, foregroundColor: RGBA? = null, backgroundColor: RGBA? = null, scale: Int? = null, lineGap: Int? = null) : AsciiRichTextSegment {
            return AsciiStyledTextSegment(str, foregroundColor, backgroundColor, scale, lineGap)
        }
    }
}

data class AsciiRichChar(
    var char: Char,
    var foregroundColor: RGBA? = null,
    var backgroundColor: RGBA? = null,
    var scale: Int? = null,
)

@Serializable
data class AsciiRichText(
    val segments : List<AsciiRichTextSegment> = listOf(),
    val foregroundColor: RGBA? = null,
    val backgroundColor: RGBA? = null,
    val scale: Int? = null,
    val lineGap: Int? = null
) {
    companion object : FromConfigCreator<AsciiRichText> {
        operator fun invoke(str: String, foregroundColor: RGBA? = null, backgroundColor: RGBA? = null) : AsciiRichText {
            return AsciiRichText(segments = mutableListOf(AsciiStyledTextSegment(str, foregroundColor = foregroundColor, backgroundColor = backgroundColor)))
        }

        override fun createFromConfig(cv: ConfigValue?): AsciiRichText? {
            return if (cv == null) {
                null
            } else if (cv.isStr()) {
                AsciiRichText(cv.asStr()!!)
            } else {
                Noto.err("Invalid config value type for AsciiRichText: $cv")
                null
            }
        }

        fun segment(str: String, foregroundColor: RGBA? = null, backgroundColor: RGBA? = null, scale: Int? = null, lineGap: Int? = null) : AsciiRichTextSegment {
            return AsciiRichTextSegment.invoke(str, foregroundColor, backgroundColor, scale, lineGap)
        }

        val Empty = AsciiRichText()
    }

    /**
     * Returns the segments of this rich text with foreground color / background color / scale / etc
     * applied directly to the segments where the segment does not already have such a value.
     */
    fun flattenedSegments() : List<AsciiRichTextSegment> {
        return if (foregroundColor != null || backgroundColor != null || scale != null || lineGap != null) {
            segments.map { s -> s.flatten(this) }
        } else {
            segments
        }
    }

    fun chars(): Iterator<AsciiRichChar> {
        return iterator {
            for (segment in segments) {
                for (c in segment.chars(this@AsciiRichText)) {
                    yield(c)
                }
            }
        }
    }

    operator fun plus(segment: AsciiRichTextSegment) : AsciiRichText {
        return if (segments.isEmpty()) {
            copy(segments = listOf(segment))
        } else {
            val merged = segments.last().merge(segment)
            if (merged != null) {
                copy(segments = segments.dropLast(1) + merged)
            } else {
                copy(segments = segments + segment)
            }
        }
    }

    operator fun plus(str: String) : AsciiRichText {
        if (! str.isEmpty()) {
            return plus(AsciiStyledTextSegment(str))
        } else {
            return this
        }
    }

    fun prepend(str: String) : AsciiRichText {
        return copy(segments = listOf(AsciiStyledTextSegment(str)) + segments)
    }

    operator fun plus(rt: AsciiRichText) : AsciiRichText {
        if (segments.isEmpty()) {
            return copy(segments = rt.segments)
        } else {
            var ret = this
            for (s in rt.segments) {
                ret = ret.plus(s)
            }
            return ret
        }
    }

    fun tint(c: RGBA, f : Float): AsciiRichText {
        if (c == White || f <= 0.0f) {
            return this
        }
        val newSegments = segments.map { it.tint(c, f) }
        return copy(foregroundColor = foregroundColor?.let { mix(foregroundColor, c, f)} ?: c, segments = newSegments)
    }

    fun withForegroundColor(c: RGBA?) : AsciiRichText {
        return copy(segments = segments.map { it.withForegroundColor(c) })
    }

    fun capitalized() : AsciiRichText {
        return if (isNotEmpty() && ! segments.first().isCapitalized()) {
            copy(segments = segments.take(1).map { it.capitalized() } + segments.drop(1))
        } else {
            this
        }
    }

    fun plainText() : String {
        if (segments.size == 0) {
            return ""
        } else if (segments.size == 1) {
            return segments.first().plainText()
        } else {
            return segments.joinToString { it.plainText() }
        }
    }

    fun length() : Int {
        return segments.sumOf { it.length() }
    }

    fun isNotEmpty(): Boolean {
        return segments.isNotEmpty()
    }
    fun isEmpty(): Boolean {
        return segments.isEmpty() || segments.all { it.isEmpty() }
    }

    inline fun ifEmpty(defaultValue: () -> AsciiRichText?): AsciiRichText? =
        if (isEmpty()) defaultValue() else this

    fun substring(start: Int) : AsciiRichText {
        return substring(start, length())
    }
    fun substring(start: Int, end: Int) : AsciiRichText {
        if (start >= end) {
            return copy(segments = listOf())
        } else if (start == 0 && end >= length()) {
            return this
        }

        val results = mutableListOf<AsciiRichTextSegment>()
        var cursor = start
        var remainingLength = end - start + 1

        for (segment in segments) {
            if (cursor < segment.length()) {
                val subSegment = segment.substring(cursor, (cursor + remainingLength))
                results.add(subSegment)
                remainingLength -= subSegment.length()
            }
            cursor = (cursor - segment.length()).max(0)

            if (remainingLength <= 0 || cursor < 0) {
                break
            }
        }

        return copy(segments = results)
    }

    override fun toString(): String {
        return plainText()
    }
}

fun List<AsciiRichText>.join(separator: String) : AsciiRichText {
    if (this.isEmpty()) {
        return AsciiRichText()
    } else if (this.size == 1) {
        return this.first()
    } else {
        var accum = this.first()
        for (i in 1 until this.size) {
            accum += separator
            accum += this[i]
        }
        return accum
    }
}

@Serializable
@SerialName("StyledText")
data class AsciiStyledTextSegment(
    var text : String,
    var foregroundColor: RGBA? = null,
    var backgroundColor: RGBA? = null,
    var scale: Int? = null,
    var lineGap: Int? = null
) : AsciiRichTextSegment {
    override fun plainText(): String {
        return text
    }

    override fun length(): Int {
        return text.length
    }

    override fun substring(start: Int, end: Int): AsciiRichTextSegment {
        if (start == 0 && end >= text.length) {
            return this
        } else if (end <= start) {
            return copy(text = "")
        }
        return copy(text = text.substring(start, end.min(text.length)))
    }

    override fun flatten(rt: AsciiRichText): AsciiRichTextSegment {
        return copy(
            foregroundColor = foregroundColor ?: rt.foregroundColor,
            backgroundColor = backgroundColor ?: rt.backgroundColor,
            scale = scale ?: rt.scale,
            lineGap = lineGap ?: rt.lineGap
        )
    }

    override fun tint(c: RGBA, f: Float): AsciiRichTextSegment {
        if (c == White || f <= 0.0f) {
            return this
        }
        return copy(foregroundColor = foregroundColor?.let { mix(it, c, f) })
    }

    override fun withForegroundColor(c: RGBA?): AsciiRichTextSegment {
        return copy(foregroundColor = c)
    }

    override fun capitalized(): AsciiRichTextSegment {
        return if (isCapitalized()) {
            this
        } else {
            copy(text = text.replaceFirstChar { it.uppercase() })
        }
    }

    override fun isCapitalized(): Boolean {
        return text.isEmpty() || text.first().isUpperCase()
    }

    override fun merge(segment: AsciiRichTextSegment): AsciiRichTextSegment? {
        return (segment as? AsciiStyledTextSegment)?.let {
            if (foregroundColor == it.foregroundColor &&
                backgroundColor == it.backgroundColor &&
                scale == it.scale &&
                lineGap == it.lineGap
            ) {
                copy(text = text + segment.text)
            } else {
                null
            }
        }
    }

    override fun chars(art: AsciiRichText): Iterator<AsciiRichChar> {
        return iterator {
            val arc = AsciiRichChar(' ',
                foregroundColor = foregroundColor ?: art.foregroundColor,
                backgroundColor = backgroundColor ?: art.backgroundColor,
                scale = scale ?: art.scale)
            for (c in text) {
                arc.char = c
                yield(arc)
            }
        }
    }
}

class AsciiTextLayout{
    val glyphs : MutableList<AsciiLayoutGlyph> = mutableListOf()

    /**
     * Bounds in absolute coordinates of the full occupied area of the text
     * not relative to the region.
     */
    var min = Vec2i(0,0)
    var max = Vec2i(0,0)
    var endCursorPos = Vec2i(0,0)
    var lineBreaks : MutableList<Int> = ArrayList(0)
    var lineYs : MutableList<Int> = ArrayList(0)
    var lineHeight : Int = 1

    val bounds : Recti
        get() { return Recti(min.x, min.y, max.x - min.x + 1, max.y - min.y + 1)}


    data class Params(
        val text: AsciiRichText,
        val position: Vec2i,
        val region: Recti,
        val defaultForegroundColor: RGBA = White,
        val defaultBackgroundColor: RGBA = Clear,
        val defaultScale: Int = 1,
        val horizontalAlignment: HorizontalTextAlignment = HorizontalTextAlignment.Left,
        val lineGap: Int = 0
    )


    companion object {
        fun layout(params : Params) : AsciiTextLayout {
            with(params) {
                val ret = AsciiTextLayout()
                ret.min.x = position.x
                ret.min.y = position.y
                ret.max.x = position.x
                ret.max.y = position.y
                val cursor = Vec2i(position.x, position.y)
                for (segment in text.segments) {
                    layoutSegment(ret, text, segment, cursor, region, params)
                }
                ret.endCursorPos = cursor

                if (horizontalAlignment == HorizontalTextAlignment.Centered || horizontalAlignment == HorizontalTextAlignment.Right) {
                    var start = 0
                    for (i in 0 until ret.lineBreaks.size + 1) {
                        val lineBreak = if (i < ret.lineBreaks.size) {
                            ret.lineBreaks[i]
                        } else {
                            ret.glyphs.size
                        }
                        if (lineBreak - 1 >= 0 && start < ret.glyphs.size) {
                            val minX = ret.glyphs[start].position.x
                            val maxX = ret.glyphs[lineBreak - 1].let { it.position.x + it.scale - 1 }
                            val offset = if (horizontalAlignment == HorizontalTextAlignment.Centered) {
                                (region.width - (maxX - minX)) / 2
                            } else {
                                region.width - (maxX - minX)
                            }
                            for (j in start until lineBreak) {
                                ret.glyphs[j].position.x += offset
                            }
                            start = lineBreak
                        }
                    }
                }

                return ret
            }
        }


        private fun layoutTextSegment(layout: AsciiTextLayout, text: String, scale: Int, lineGap: Int, foregroundColor: RGBA, backgroundColor: RGBA, cursor: Vec2i, region: Recti) {
            layout.lineHeight = scale

            if (region.width <= 0 || region.height <= 0 || text.isEmpty()) {
                return
            }

            if (text == "\n") {
                cursor.y += scale + lineGap
                cursor.x = region.x
                layout.max.y = layout.max.y.max(cursor.y + scale)
                return
            }

            var charIndex = 0
            while (charIndex < text.length) {
                var x = cursor.x
                var eolIndex = -1 // the last valid point for the end of a line
                var i = charIndex
                while (i < text.length) {
                    when(text[i]) {
                        '\n' -> {
                            eolIndex = i + 1
                            break
                        }
                        ' ' -> eolIndex = i
                    }

                    x += scale
                    if (x > region.maxX) {
                        break
                    }
                    i++
                }

                // if we reached the end then the remaining text is our line, if there were no natural gaps then just go with whatever the last was
                if (i == text.length || eolIndex == -1) {
                    eolIndex = i
                }

                if (eolIndex == charIndex) {
                    eolIndex++
                }

                // lay out the line itself
                while (charIndex < eolIndex) {
                    val x = tern(text[charIndex] == '\n', region.x, cursor.x)
                    val y = tern(text[charIndex] == '\n', cursor.y + scale + lineGap, cursor.y)
                    layout.glyphs.add(AsciiLayoutGlyph(text[charIndex], charIndex, Vec2i(x, y), foregroundColor, backgroundColor, scale))
                    cursor.x += scale
                    layout.max.x = layout.max.x.max(cursor.x)
                    charIndex++
                }

                // end-of-line whitespace is considered a bit differently, still working on the details...
                while(charIndex < text.length && (text[charIndex] == ' ' || text[charIndex] == '\t')) {
                    layout.glyphs.add(AsciiLayoutGlyph(text[charIndex], charIndex, Vec2i(cursor.x, cursor.y), foregroundColor, backgroundColor, scale))
                    cursor.x += scale
                    layout.max.x = layout.max.x.max(cursor.x)
                    charIndex++
                }

                // if we hit the end of the line before the end of the text, advance to the next line and reset the cursor
                if (charIndex != text.length || text[charIndex - 1] == '\n') {
                    layout.lineBreaks.add(layout.glyphs.size)
                    layout.lineYs.add(cursor.y)

                    cursor.y += scale + lineGap
                    cursor.x = region.x
                }
            }

            layout.max.y = layout.max.y.max(cursor.y + scale)
        }

        private fun layoutSegment(layout: AsciiTextLayout, rt: AsciiRichText, segment: AsciiRichTextSegment, position: Vec2i, region: Recti, params: Params) {
            with(params) {
                return when (segment) {
                    is AsciiStyledTextSegment -> layoutTextSegment(layout, segment.text, segment.scale ?: rt.scale ?: defaultScale, segment.lineGap ?: rt.lineGap ?: params.lineGap, segment.foregroundColor ?: rt.foregroundColor ?: defaultForegroundColor, segment.backgroundColor ?: rt.backgroundColor ?: defaultBackgroundColor, position, region)
                    else -> {
                        Noto.err("Unsupported ascii text segment style")
                    }
                }
            }
        }
    }
}


data class AsciiTextWidget (
    var text : Bindable<AsciiRichText> = ValueBindable(AsciiRichText()),
    var foregroundColor : Bindable<RGBA> = ValueBindable.White,
    var backgroundColor : Bindable<RGBA> = ValueBindable.Clear,
    var scale : Bindable<Int?> = ValueBindable.Null(),
    var horizontalAlignment: Bindable<HorizontalTextAlignment> = ValueBindable(HorizontalTextAlignment.Left),
    var lineGap: Int = 0,
    var focusedForegroundColor : Bindable<RGBA?> = ValueBindable.Null(),
    var focusedBackgroundColor : Bindable<RGBA?> = ValueBindable.Null()
) : DisplayData {
    companion object : DataType<AsciiTextWidget>( AsciiTextWidget(),sparse = true ), FromConfigCreator<AsciiTextWidget> {
        val textWidgetTypes = setOf("textinput", "textdisplay", "text", "button")

        override fun createFromConfig(cv: ConfigValue?): AsciiTextWidget? {
            val text = cv["text"].asStr()
            val type = cv["type"].asStr()?.lowercase() ?: ""

            // we treat un-typed widgets with a text field as implicitly being text widgets
            if (cv == null) {
                return null
            }
            if (textWidgetTypes.contains(type) || (text != null && type == "")) {
                return createFromConfigIgnoringType(cv)
            } else {
                return null
            }
        }

        fun createFromConfigIgnoringType(cv: ConfigValue) : AsciiTextWidget {
            val fg = cv["foregroundColor"] ?: cv["color"]
            val bg = cv["backgroundColor"]

            val cvha = (cv["horizontalAlignment"] ?: cv["textAlignment"])
            val alignment = if (cvha != null) {
                HorizontalTextAlignment(cvha)?.let { ValueBindable(it) } ?: bindableT(cvha, HorizontalTextAlignment.Left)
            } else if (cv["centered"].asBool() == true) {
                ValueBindable(HorizontalTextAlignment.Centered)
            } else {
                ValueBindable(HorizontalTextAlignment.Left)
            }

            return AsciiTextWidget(
                text = cv["text"]?.let { bindableAsciiRichText(it) } ?: ValueBindable.EmptyAsciiRichText,
                foregroundColor = fg?.let { bindableRGBA(it) } ?: ValueBindable.White,
                backgroundColor = bg?.let { bindableRGBA(it) } ?: ValueBindable.Clear,
                scale = bindableIntOpt(cv["scale"], defaultValue = 2),
                horizontalAlignment = alignment,
                lineGap = cv["lineGap"].asInt() ?: 0,
                focusedForegroundColor = bindableRGBAOpt(cv["focusedForegroundColor"] ?: cv["focusedTextColor"] ?: cv["focusedColor"]),
                focusedBackgroundColor = bindableRGBAOpt(cv["focusedBackgroundTextColor"] ?: cv["focusedBackgroundColor"])
            )
        }
    }
    override fun dataType() : DataType<*> { return AsciiTextWidget }
//
//    override fun readFromConfig(cv: ConfigValue) {
//        val fg = cv["foregroundColor"] ?: cv["textColor"] ?: cv["fontColor"] ?: cv["color"]
//        val bg = cv["backgroundColor"]
//
//        fg?.let { foregroundColor = bindableRGBA(it) }
//        bg?.let { backgroundColor = bindableRGBA(it) }
//
//        (if (cv["horizontalAlignment"] != null) {
//            HorizontalTextAlignment(cv["horizontalAlignment"])
//        } else if (cv["centered"].asBool() == true) {
//            HorizontalTextAlignment.Centered
//        } else {
//            null
//        })?.let { horizontalAlignment = it }
//
//        cv["scale"].asInt()?.let { scale = it }
//        cv["lineGap"].asInt()?.let { lineGap = it }
//        cv["text"]?.let { text = bindableAsciiRichText(it) }
//    }

    fun copy() : AsciiTextWidget {
        return copy(
            text = text.copyBindable(),
            foregroundColor = foregroundColor.copyBindable(),
            backgroundColor = backgroundColor.copyBindable(),
            horizontalAlignment = horizontalAlignment.copyBindable(),
            scale = scale.copyBindable(),
            focusedForegroundColor = focusedForegroundColor.copyBindable(),
            focusedBackgroundColor = focusedBackgroundColor.copyBindable()
        )
    }
}


object AsciiTextWidgetComponent : WindowingComponent {

    val layoutCache = LRULoadingCache<AsciiTextLayout.Params, AsciiTextLayout>(500, 0.5f) { params -> AsciiTextLayout.layout(params) }

    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(AsciiTextWidget)
    }

    override fun initializeWidget(w: Widget) {
        if (w[TextInput] != null && w[FocusSettings] == null) {
            w.attachData(FocusSettings(acceptsFocus = true))
        }
    }

    fun effectiveText(td: AsciiTextWidget, ti : TextInput?) : AsciiRichText {
        return td.text().let { if (it.isEmpty()) { ti?.emptyText?.let { et -> AsciiRichText(et, foregroundColor = RGBA(128,128,128,255)) } ?: it } else { it } }
    }

    fun params(td: AsciiTextWidget, ti: TextInput?, region: Recti, defaultScale: Int, alignment: HorizontalTextAlignment, focused: Boolean, forceScale: Int?) : AsciiTextLayout.Params {
        val text = effectiveText(td, ti)

        val ffg = if (focused) { td.focusedForegroundColor() } else { null }
        val fbg = if (focused) { td.focusedBackgroundColor() } else { null }

        return AsciiTextLayout.Params(
            text = text,
            region = region,
            defaultForegroundColor = ffg ?: td.foregroundColor(),
            defaultBackgroundColor = fbg ?: td.backgroundColor(),
            defaultScale = forceScale ?: td.scale() ?: defaultScale,
            horizontalAlignment = alignment,
            position = region.position,
            lineGap = td.lineGap
        )
    }

    fun intrinsicSizeOf(params: AsciiTextLayout.Params) : Vec2i {
        val layout = layoutCache.getOrPut(params)
        return Vec2i(layout.max.x - layout.min.x, layout.max.y - layout.min.y)
    }

    override fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        val td = w[AsciiTextWidget] ?: return null
        val ti = w[TextInput]

        val defaultScale = w.windowingSystem.scale

        val text = effectiveText(td, ti)
        if (text.isEmpty()) {
            if (axis == Axis2D.Y) {
                return w.windowingSystem.forceScale ?: td.scale() ?: defaultScale
            } else {
                return tern(ti == null, 0, 1)
            }
        }

        val bounds = Recti(0, 1, maxSize.x, maxSize.y)


        val layout = layoutCache.getOrPut(params(td, ti, bounds, defaultScale, HorizontalTextAlignment.Left, w.hasFocus(), w.windowingSystem.forceScale))

        val raw = layout.max[axis] - layout.min[axis]
        if (ti != null && axis == Axis2D.X) {
            return raw + 1
        } else {
            return raw
        }
    }

    override fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {
        val td = w[AsciiTextWidget] ?: return
        val ti = w[TextInput]

        val cursorPos = ti?.cursorPosition

        val defaultScale = w.windowingSystem.scale

        val region = Recti(w.resClientX, w.resClientY, w.resClientWidth, w.resClientHeight)
        val layout = layoutCache.getOrPut(params(td, ti, region, defaultScale, td.horizontalAlignment(), w.hasFocus(), w.windowingSystem.forceScale))

        val drawCursor = ti != null && ws.focusedWidget == w && ((System.currentTimeMillis() - lastTyped) < 1000L || (counter / 60) % 2 == 0)
        var cursorDrawn = false
        layout.glyphs.forEach { glyph ->
            val bgColor = if (ti?.selectedRange?.contains(glyph.characterIndex) == true) {
                ti.selectedTextColor ?: RGBA(100, 100, 210, 255)
            } else {
                null
            }

            commandsOut.add(AsciiDrawCommand.Glyph(glyph.character, Vec3i(glyph.position.x, glyph.position.y, w.resolvedPosition.z), glyph.scale, glyph.foregroundColor, bgColor ?: glyph.backgroundColor))
            if (drawCursor && cursorPos != null && ! cursorDrawn && glyph.characterIndex >= cursorPos) {
                for (y in 0 until glyph.scale) {
                    commandsOut.add(AsciiDrawCommand.Glyph('█', Vec3i(glyph.position.x, glyph.position.y + y, w.resolvedPosition.z), 1, td.foregroundColor(), td.backgroundColor()))
                }
                cursorDrawn = true
            }
        }
        if (drawCursor && ! cursorDrawn) {
            val scale = layout.glyphs.lastOrNull()?.scale ?: td.scale() ?: defaultScale
            val pos = layout.glyphs.lastOrNull()?.position.ifLet {
                it + Vec2i(scale,0)
            }.orElse {
                when (td.horizontalAlignment()) {
                    HorizontalTextAlignment.Left -> Vec2i(w.resClientX, w.resClientY)
                    HorizontalTextAlignment.Centered -> Vec2i(w.resClientX + w.resClientWidth / 2, w.resClientY)
                    HorizontalTextAlignment.Right -> Vec2i(w.resClientX + w.resClientWidth - 2, w.resClientY)
                }
            }
            for (y in 0 until scale) {
                commandsOut.add(AsciiDrawCommand.Glyph('█', Vec3i(pos.x, pos.y + y, w.resolvedPosition.z), 1, td.foregroundColor(), td.backgroundColor()))
            }
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val td = w[AsciiTextWidget] ?: return

        val scaleUpdate = td.scale.update(ctx)
        val textUpdate = td.text.update(ctx)

        if (textUpdate || scaleUpdate) {
            w.markForUpdate(RecalculationFlag.Contents)
            if (w.dimensions(Axis2D.X).isIntrinsic() ) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if( w.dimensions(Axis2D.Y).isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }

        if (td.foregroundColor.update(ctx)) {
            w.markForUpdate(RecalculationFlag.Contents)
        }

        if (td.backgroundColor.update(ctx)) {
            w.markForUpdate(RecalculationFlag.Contents)
        }

        if (td.horizontalAlignment.update(ctx)) {
            w.markForUpdate(RecalculationFlag.Contents)
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val td = w[AsciiTextWidget] ?: return false

        when (event) {
            is TextInputRequiresSync -> {
                val ti = w[TextInput] ?: return false
                val td = w[AsciiTextWidget] ?: return false

                lastTyped = System.currentTimeMillis()

                val raw = ti.textData.toString()
                val txt = AsciiRichText(raw)
                if (txt != td.text()) {
                    td.text = bindable(txt)
                    w.markForUpdate(RecalculationFlag.Contents)
                    if (w.width.isIntrinsic()) {
                        w.markForUpdate(RecalculationFlag.DimensionsX)
                    }
                    if (w.height.isIntrinsic()) {
                        w.markForUpdate(RecalculationFlag.DimensionsY)
                    }
                }

                return true
            }
            is FocusChangedEvent -> {
                w[TextInput] ?: return false
                w[AsciiTextWidget] ?: return false

                lastTyped = System.currentTimeMillis()
            }
            is CursorPositionChanged -> {
                lastTyped = System.currentTimeMillis()
            }
            is WidgetMousePressEvent -> {
                if (event.originWidget != w || ! w.hasFocus()) {
                    return false
                }
                val ti = w[TextInput] ?: return false
                val newCursorPos = closestCursorPositionTo(w, td, ti, Vec2i(event.position.x.toInt(), event.position.y.toInt()))
                TextInputWindowingComponent.moveCursorTo(w, ti, newCursorPos, event.mods.shift)
                return true
            }
            is WidgetMouseDragEvent -> {
                if (event.originWidget != w || ! w.hasFocus()) {
                    return false
                }

                val ti = w[TextInput] ?: return false
                val newCursorPos = closestCursorPositionTo(w, td, ti, Vec2i(event.position.x.toInt(), event.position.y.toInt()))
                TextInputWindowingComponent.moveCursorTo(w, ti, newCursorPos, selecting = true)
                return true
            }
            is WidgetKeyPressEvent -> {
                val ti = w[TextInput] ?: return false

                when (event.key) {
                    Key.Up, Key.Down -> {
                        if (ti.singleLine) { return false }
                        val isUp = event.key == Key.Up

                        val layout = generalLayout(w, td, ti)
                        val curGlyph = layout.glyphs.getOrNull(ti.cursorPosition.min(ti.textData.length - 1))
                        val newCursorPos = if (curGlyph == null) {
                            if (isUp) {
                                0
                            } else {
                                ti.textData.length
                            }
                        } else {
                            val dy = curGlyph.scale + td.lineGap
                            val delta = tern(isUp, Vec2i(0, -dy), Vec2i(0, dy))
                            closestCursorPositionTo(w, td, ti, curGlyph.position + delta)
                        }

                        if (newCursorPos != ti.cursorPosition) {
                            TextInputWindowingComponent.moveCursorTo(w, ti, newCursorPos, event.mods.shift)
                            return true
                        }
                    }
                    else -> {}
                }
                return false
            }
        }
        return false
    }


    fun generalLayout(w: Widget, td: AsciiTextWidget, ti: TextInput?): AsciiTextLayout {
        val defaultScale = w.windowingSystem.scale
        val region = Recti(w.resClientX, w.resClientY, w.resClientWidth, w.resClientHeight)
        return layoutCache.getOrPut(params(td, ti, region, defaultScale, td.horizontalAlignment(), w.hasFocus(), w.windowingSystem.forceScale))
    }

    fun closestCursorPositionTo(w: Widget, td: AsciiTextWidget, ti: TextInput, pos: Vec2i) : Int {
        val layout = generalLayout(w, td, ti)

        var lastMatchingY = -1
        for (i in 0 until layout.glyphs.size) {
            val glyph = layout.glyphs[i]
            val min = glyph.position - Vec2i(0, td.lineGap)
            val max = glyph.position + Vec2i(glyph.scale, glyph.scale + td.lineGap)
            if (pos.y >= min.y && pos.y < max.y) {
                lastMatchingY = i
                if (pos.x < max.x) {
                    if (pos.x <= min.x) {
                        return i
                    } else {
                        return i + 1
                    }
                }
            }
        }
        if (lastMatchingY != -1) {
            if (lastMatchingY == ti.textData.length - 1) {
                return ti.textData.length
            } else {
                return lastMatchingY
            }
        } else {
            return ti.textData.length
        }
    }


    val start = System.currentTimeMillis()
    var counter = 0
    var lastTyped = 0L
    override fun update(windowingSystem: WindowingSystem) {
        counter = ((System.currentTimeMillis() - start) / 16.66667).toInt()
        if (counter % 60 == 0) {
            for (w in windowingSystem.widgetsThatHaveData(TextInput)) {
                if (windowingSystem.focusedWidget == w) {
                    w.markForUpdate(RecalculationFlag.Contents)
                }
            }
        }
    }
}