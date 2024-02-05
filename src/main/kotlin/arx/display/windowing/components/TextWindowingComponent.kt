package arx.display.windowing.components

import arx.application.Application
import arx.core.*
import arx.display.core.Key
import arx.display.windowing.*
import arx.display.windowing.components.ascii.AsciiRichText
import arx.engine.*
import com.typesafe.config.ConfigValue
import org.lwjgl.glfw.GLFW
import kotlin.reflect.KType
import kotlin.reflect.typeOf


internal val TextTypes = setOf("textdisplay", "textinput")

data class TextDisplay(
    var text: Bindable<RichText> = bindable(RichText()),
    var multiLine: Boolean = false,
    var color: Bindable<RGBA?> = ValueBindable.Null(),
    var typeface: ArxTypeface = TextLayout.DefaultTypeface,
    var fontSize: Int? = null,
    var horizontalAlignment: HorizontalTextAlignment = HorizontalTextAlignment.Left,
) : DisplayData {
    companion object : DataType<TextDisplay>(TextDisplay(),sparse = true), FromConfigCreator<TextDisplay> {

        fun extractTypeface(cv: ConfigValue?) : ArxTypeface? {
            return if (cv.isStr()) {
                cv.asStr()?.split(':')?.get(0)?.let { Resources.typeface(it) }
            } else {
                null
            }
        }

        fun extractFontSize(cv: ConfigValue?) : Int? {
            return cv.asInt() ?: cv.asStr()?.split(':')?.getOrNull(1)?.toIntOrNull()
        }
        override fun createFromConfig(cv: ConfigValue?): TextDisplay? {
            return if (cv["text"] != null || TextTypes.contains(cv["type"]?.asStr()?.lowercase())) {
                TextDisplay(
                    text = cv["text"].ifLet { bindableRichText(it) }.orElse { bindable(RichText()) },
                    multiLine = cv["mutliLine", "multiline"]?.asBool() ?: false,
                    color = cv["color"].ifLet { bindableRGBAOpt(it) }.orElse { bindable(Black) },
                    typeface = (cv["font"] ?: cv["typeface"])?.let { extractTypeface(it) } ?: TextLayout.DefaultTypeface,
                    fontSize = (cv["fontSize"])?.let { it.asInt() } ?: cv["font"]?.let { extractFontSize(it) },
                    horizontalAlignment = HorizontalTextAlignment(cv["horizontalTextAlignment"] ?: cv["horizontalAlignment"]) ?: HorizontalTextAlignment.Left
                )
            } else {
                null
            }
        }
    }

    val font: ArxFont get() { return typeface.withSize(fontSize ?: typeface.baseSize) }

    override fun dataType(): DataType<*> {
        return TextDisplay
    }

    fun copy() : TextDisplay {
        return TextDisplay(
            text = text.copyBindable(),
            multiLine = multiLine,
            color = color.copyBindable(),
            typeface = typeface,
            fontSize = fontSize,
            horizontalAlignment = horizontalAlignment
        )
    }
}


sealed interface EditorOperation {
    data class Append(val string: String, val position : Int) : EditorOperation
    data class Delete(val deleted: String, val position : Int) : EditorOperation
    data class Replace(val previousText: String, val newText: String, val position : Int) : EditorOperation
    data class Set(val newText: String) : EditorOperation
}

data class TextDataChanged(val operation : EditorOperation, val src: DisplayEvent?) : WidgetEvent(src) {
    val currentText : String get() {
        return widgets.firstOrNull()?.get(TextInput)?.textData?.toString() ?: ""
    }
}

data class CursorPositionChanged(val newPosition: Int, val src: DisplayEvent?) : WidgetEvent(src)

data class TextInputRequiresSync(val w: Widget) : WidgetEvent(null) {
    init {
        widgets.add(w)
    }
}

data class TextInputEnter(val currentText: String, val signal: Any?, val src: DisplayEvent?) : WidgetEvent(src)

enum class TextInputValidation(val validate: (str: String) -> kotlin.Boolean, val forType: KType) {
    Int({ s -> (s.isEmpty() || s.toIntOrNull() != null) && ! s.contains(' ') }, typeOf<kotlin.Int>()),
    Float({ s -> (s.isEmpty() || s.toFloatOrNull() != null) && ! s.contains(' ') }, typeOf<kotlin.Float>()),
    Double({ s -> (s.isEmpty() || s.toDoubleOrNull() != null) && ! s.contains(' ') }, typeOf<kotlin.Double>()),
    Long({ s -> (s.isEmpty() || s.toLongOrNull() != null) && ! s.contains(' ') }, typeOf<kotlin.Long>()),
    Boolean({ s -> (s.isEmpty() || s.lowercase().toBooleanStrictOrNull() != null) && ! s.contains(' ') }, typeOf<kotlin.Boolean>());

    companion object {
        fun validationFunctionForType(type : KType) : ((String) -> kotlin.Boolean)? {
            return TextInputValidation.values().find { it.forType == type }?.validate
        }
    }
}

data class TextInput(
    var textData: StringBuilder = StringBuilder(),
    var textBinding: PropertyBinding? = null,
    var cursorPosition: Int = -1,
    var toRichTextTransformer: (String) -> RichText = { s -> RichText(s) },
    var selectedRange: IntRange? = null,
    var undoStack : MutableList<EditorOperation> = mutableListOf(),
    var onChangeSet : (String) -> Unit = { _ -> },
    var fetchFunction : (() -> String)? = null,
    var validationFunction: ((String) -> Boolean)? = null,
    internal var lastBoundValue : Any? = null,
    var singleLine : Boolean = true,
    var emptyText : String? = null,
    var cycleOnSelect: Boolean = false,
    var selectedTextColor: RGBA? = null,
    var textInputEnterSignal: Bindable<Any?> = ValueBindable.Null(),
    var textInputChangeSignal: Bindable<Any?> = ValueBindable.Null(),
    var selectAllOnFocus: Boolean = false,
) : DisplayData {
    companion object : DataType<TextInput>(TextInput(),sparse = true), FromConfigCreator<TextInput> {
        override fun createFromConfig(cv: ConfigValue?): TextInput? {
            return if (cv["type"].asStr()?.lowercase() == "textinput") {
                var textData : String = ""
                var bindingPattern : String? = null
                var cursorPos = -1
                cv["text"].asStr()?.let { t ->
                    stringBindingPattern.match(t).ifLet { (pattern) ->
                        bindingPattern = pattern
                    }.orElse {
                        textData = t
                        cursorPos = t.length
                    }
                }

                val validation : ((String) -> Boolean)? = if ((cv["numericOnly"] ?: cv["numeric"]).asBool() == true) {
                    { s -> s == "-" || s.toDoubleOrNull() != null }
                } else if ((cv["integerOnly"] ?: cv["integer"]).asBool() == true) {
                    { s -> s == "-" || s.toLongOrNull() != null }
                } else {
                   null
                }

                val singleLine = (cv["singleLine"]?.asBool() ?: cv["multiLine", "multiline"]?.asBool()?.let { ! it })?: defaultInstance.singleLine

                TextInput(
                    textData = StringBuilder(textData),
                    textBinding = bindingPattern?.let { PropertyBinding(it, cv["twoWayBinding"].asBool() ?: false) },
                    validationFunction = validation,
                    singleLine = singleLine,
                    emptyText = cv["emptyText"]?.asStr(),
                    cycleOnSelect = cv["cycleOnSelect"]?.asBool() ?: defaultInstance.cycleOnSelect,
                    selectedTextColor = RGBA(cv["selectedTextColor"]),
                    textInputEnterSignal = bindableAnyOpt(cv["textInputEnterSignal", "enterSignal"]),
                    textInputChangeSignal = bindableAnyOpt(cv["textInputChangeSignal", "changeSignal"]),
                    selectAllOnFocus = (cv["selectAllOnFocus"]?.asBool() ?: cv["integerOnly"]?.asBool()) ?: false,
                    cursorPosition = cursorPos
                )
            } else {
                null
            }
        }
    }

    override fun dataType(): DataType<*> {
        return TextInput
    }

    val effectiveCursorPosition: Int get() {
        return if (cursorPosition == -1) {
            textData.length
        } else {
            cursorPosition.clamp(0, textData.length)
        }
    }

    fun copy() : TextInput {
        return copy(textData = StringBuilder(textData), textBinding = textBinding?.copy(), undoStack = undoStack.toMutableList(), lastBoundValue = null, textInputEnterSignal = textInputEnterSignal.copyBindable(), textInputChangeSignal = textInputChangeSignal.copyBindable())
    }

    fun isAllSelected(): Boolean {
        return selectedRange == 0 until textData.length
    }
}


object TextInputWindowingComponent : WindowingComponent {
    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(TextInput)
    }

    override fun initializeWidget(w: Widget) {
        if (w[TextInput] != null && w[FocusSettings] == null) {
            w.attachData(FocusSettings(acceptsFocus = true))
        }
    }

    fun boundValueToText(cur: String, g: Any?) : String {
        return when (g) {
            is AsciiRichText -> g.plainText()
            is RichText -> g.plainText()
            is Double -> {
                val endsInDecimal = cur.endsWith(".")
                if (endsInDecimal) {
                    "${g.toInt()}."
                } else {
                    if (g.toString().endsWith(".0")) {
                        "${g.toInt()}"
                    } else {
                        "$g"
                    }
                }
            }
            is Float -> {
                val endsInDecimal = cur.endsWith(".")
                if (endsInDecimal) {
                    "${g.toInt()}."
                } else {
                    if (g.toString().endsWith(".0")) {
                        "${g.toInt()}"
                    } else {
                        "$g"
                    }
                }
            }
            null -> ""
            else -> g.toString()
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val ti = w[TextInput] ?: return

//        ti.textInputChangeSignal.update(ctx)
//        ti.textInputEnterSignal.update(ctx)

        ti.textBinding?.let { binding ->
            binding.update(ctx, { _, bound ->
                if (ws.focusedWidget != w || ti.textData.isNotEmpty()) {
                    ti.textData = StringBuilder(boundValueToText(ti.textData.toString(), bound))
//                    if (ti.cursorPosition == -1) {
                    if (ws.focusedWidget != w) {
                        moveCursorTo(w, ti, ti.textData.length, selecting = false)
                    }
//                    }
                    ws.fireEvent(TextInputRequiresSync(w))
                }
            }, { paramType, setter ->
                when (paramType) {
                    typeOf<Int>() -> {
                        ti.onChangeSet = { str -> str.toIntOrNull()?.let { v -> setter(v) } }
                    }
                    typeOf<Long>() -> {
                        ti.onChangeSet = { str -> str.toLongOrNull()?.let { v -> setter(v) } }
                    }
                    typeOf<Float>() -> {
                        ti.onChangeSet = { str -> str.toFloatOrNull()?.let { v -> setter(v) } }
                    }
                    typeOf<Double>() -> {
                        ti.onChangeSet = { str -> str.toDoubleOrNull()?.let { v -> setter(v) } }
                    }
                    typeOf<Boolean>() -> {
                        ti.onChangeSet = { str -> str.lowercase().toBooleanStrictOrNull()?.let { v -> setter(v) } }
                    }
                    typeOf<String>() -> {
                        ti.onChangeSet = { v -> setter(v) }
                    }
                    typeOf<String?>() -> {
                        ti.onChangeSet = { v -> setter(v.ifEmpty { null }) }
                    }
                    typeOf<AsciiRichText>() -> {
                        ti.onChangeSet = { v -> setter(AsciiRichText(v)) }
                    }
                    typeOf<AsciiRichText?>() -> {
                        ti.onChangeSet = { v -> setter(v.ifEmpty { null }?.let { AsciiRichText(it) }) }
                    }
                    typeOf<RichText?>(), typeOf<RichText>() -> {
                        ti.onChangeSet = { v -> setter(RichText(v)) }
                    }
                    else -> {
                        if (w.showing()) {
                            Noto.warn("unsupported type for two way binding on text input : $paramType")
                        }
                    }
                }
                // if there is no validation function, or the one supplied is a basic type-based validation fn, replace it
                if (ti.validationFunction == null || TextInputValidation.values().any { it.validate == ti.validationFunction }) {
                    ti.validationFunction = TextInputValidation.validationFunctionForType(paramType)
                }

//                ti.onChangeSet(ti.textData.toString())
            }, { returnType, getter ->
                ti.fetchFunction = {
                    boundValueToText(ti.textData.toString(), getter())
                }
            }, warnOnAbsence = w.isVisible())
        }
    }

    fun textDataChanged(w : Widget, ti: TextInput, op : EditorOperation, src : DisplayEvent) {
        ti.undoStack.add(op)
        w.fireEvent(TextInputRequiresSync(w))

        ti.onChangeSet(ti.textData.toString())
        w.windowingSystem.fireEvent(TextDataChanged(op, src).withWidget(w))
        ti.textInputChangeSignal()?.let { s -> w.fireEvent(SignalEvent(s, w.data())) }
    }

    fun validate(ti: TextInput, prior : StringBuilder) : Boolean {
        return ti.validationFunction.ifLet { v ->
            if (v(ti.textData.toString())) {
                true
            } else {
                ti.textData = prior
                false
            }
        }.orElse { true }
    }

    fun insertText(w : Widget, ti: TextInput, str: String?, char : Char?, src : DisplayEvent) {
        val original = if (ti.validationFunction != null) { StringBuilder(ti.textData) } else { StringBuilder() }

        ti.selectedRange.ifLet { sr ->
            val oldStr = ti.textData.substring(sr.first, sr.last + 1)
            val effStr = str ?: char.toString()

            ti.textData.deleteRange(sr.first, sr.last + 1)
            ti.textData.insert(sr.first, effStr)

            if (validate(ti, original)) {
                textDataChanged(w, ti, EditorOperation.Replace(oldStr, effStr, sr.first), src)
                moveCursorTo(w, ti, sr.first + effStr.length, selecting = false)
                ti.selectedRange = null
            }
        }.orElse {
            val c = ti.effectiveCursorPosition
            if (char != null) {
                ti.textData.insert(c, char)
                if (validate(ti, original)) {
                    textDataChanged(w, ti, EditorOperation.Append(char.toString(), c), src)
                    moveCursorTo(w, ti, c + 1, selecting = false)
                }
            } else if (str != null) {
                ti.textData.insert(c, str)
                if (validate(ti, original)) {
                    textDataChanged(w, ti, EditorOperation.Append(str, c), src)
                    moveCursorTo(w, ti, c + str.length, selecting = false)
                }
            } else {
                Noto.err("insertText called with neither str nor char?")
            }

        }
    }

    fun performDelete(w : Widget, ti: TextInput, src: DisplayEvent) {
        if (ti.textData.isEmpty()) {
            return
        }

        val original = if (ti.validationFunction != null) { StringBuilder(ti.textData) } else { StringBuilder() }

        ti.selectedRange.ifLet { sr ->
            val oldStr = ti.textData.substring(sr)
            ti.textData.delete(sr.first, sr.last + 1)
            if (ti.textData.isEmpty() || validate(ti, original)) {
                textDataChanged(w, ti, EditorOperation.Delete(oldStr, sr.first), src)
                moveCursorTo(w, ti, sr.first, selecting = false)
                ti.selectedRange = null
            }
        }.orElse {
            val index = ti.cursorPosition - 1
            if (index >= 0 && index < ti.textData.length) {
                val oldStr = ti.textData[index].toString()
                ti.textData.delete(index, index + 1)
                if (ti.textData.isEmpty() || validate(ti, original)) {
                    textDataChanged(w, ti, EditorOperation.Delete(oldStr, index), src)
                    moveCursorTo(w, ti, ti.cursorPosition - 1, selecting = false)
                }
            }
        }
    }


    private fun selectionRangeFrom(a: Int, b: Int) : IntRange {
        return a.min(b).max(0) until a.max(b)
    }
    private fun selectionRangeFrom(ti: TextInput, r: IntRange) : IntRange {
        return r.first.min(r.last).max(0) .. r.first.max(r.last).min(ti.textData.length - 1)
    }

    fun moveCursorTo(w: Widget, ti: TextInput, i : Int, selecting: Boolean) {
        val newPos = i.clamp(0, ti.textData.length)

        if (! selecting) {
            ti.selectedRange = null
        } else if (newPos != ti.cursorPosition) {
            val curRange = ti.selectedRange
            if (curRange == null) {
                ti.selectedRange = selectionRangeFrom(ti.cursorPosition, newPos)
            } else {
                if (newPos < ti.cursorPosition) {
                    if (ti.cursorPosition >= curRange.last) {
                        ti.selectedRange = selectionRangeFrom(ti, curRange.first until newPos)
                    } else {
                        ti.selectedRange = selectionRangeFrom(ti, newPos .. curRange.last)
                    }
                } else {
                    if (ti.cursorPosition <= curRange.first) {
                        ti.selectedRange = selectionRangeFrom(ti, newPos .. curRange.last)
                    } else {
                        ti.selectedRange = selectionRangeFrom(ti, curRange.first until newPos)
                    }
                }
//                if (newPos < curRange.first) {
//                    ti.selectedRange = newPos .. curRange.last
//                } else if (newPos > curRange.last) {
//                    ti.selectedRange = curRange.first until newPos
//                } else {
//
//                }
            }
        }

        if (newPos == ti.cursorPosition) {
            return
        }
        ti.cursorPosition = newPos
        w.fireEvent(CursorPositionChanged(ti.cursorPosition, null))
        w.markForUpdate(RecalculationFlag.Contents)
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        val ti = w[TextInput] ?: return false

        return when(event) {
            is WidgetCharInputEvent -> {
                insertText(w, ti, null, event.char, event)
                true
            }
            is WidgetKeyPressEvent -> {
                when (event.key) {
                    Key.Backspace -> performDelete(w, ti, event)
                    Key.Left -> {
                        if (ti.cursorPosition > 0) {
                            moveCursorTo(w, ti, (ti.cursorPosition - 1), selecting = event.mods.shift)
                        } else {
                            return false
                        }
                    }
                    Key.Right -> {
                        if (ti.cursorPosition < ti.textData.length) {
                            moveCursorTo(w, ti, (ti.cursorPosition + 1), selecting = event.mods.shift)
                        } else {
                            return false
                        }
                    }
                    Key.Enter -> {
                        if (ti.singleLine) {
                            w.fireEvent(TextInputEnter(ti.textData.toString(), ti.textInputEnterSignal(), event))
                            ti.textInputEnterSignal()?.let { s -> w.fireEvent(SignalEvent(s, w.data())) }
                            if (ti.cycleOnSelect) {
                                w.fireEvent(RequestCycleFocusEvent())
                            }
                        } else {
                            insertText(w, ti, null, '\n', event)
                        }
                        return true
                    }
                    Key.V -> {
                        if (event.mods.ctrl) {
                            val r = GLFW.glfwGetClipboardString(Application.window)
                            (r as? String)?.let { str ->
                                insertText(w, ti, str, null, event)
                            }
                            return true
                        }
                        return false
                    }
                    Key.C -> {
                        if (event.mods.ctrl) {
                            ti.selectedRange?.let { range ->
                                val sel = ti.textData.substring(range.first.max(0) .. range.last.min(ti.textData.length - 1))
                                GLFW.glfwSetClipboardString(Application.window, sel)
                            }
                            return true
                        }
                        return false
                    }
                    Key.A -> {
                        if (event.mods.ctrl) {
                            ti.selectedRange = selectionRangeFrom(0, ti.textData.length)
                            w.markForUpdate(RecalculationFlag.Contents)
                        }
                        return false
                    }
                    else -> return false
                }
                true
            }
            is WidgetKeyReleaseEvent -> {
                return when (event.key) {
                    Key.Up -> ti.cursorPosition > 0 && ! ti.singleLine
                    Key.Down -> ti.cursorPosition != ti.textData.length && ! ti.singleLine
                    else -> true
                }
            }
            is FocusChangedEvent -> {
                if (event.hasFocus) {
                    if (ti.cursorPosition == -1) {
                        moveCursorTo(w, ti, ti.textData.length, selecting = false)
                    }
                    if (ti.selectAllOnFocus) {
                        ti.selectedRange = selectionRangeFrom(0, ti.textData.length)
                    }
                } else {
                    ti.selectedRange = null
                }
                w.markForUpdate(RecalculationFlag.Contents)
                false
            }
            else -> false
        }
    }

    fun equivalent(sb : StringBuilder, str: String) : Boolean {
        if (str.length != sb.length) {
            return false
        }
        for (i in 0 until sb.length) {
            if (sb[i] != str[i]) {
                return false
            }
        }
        return true
    }

    override fun update(windowingSystem: WindowingSystem) {
        for (w in windowingSystem.widgetsThatHaveData(TextInput)) {
            if (! w.isVisible() || w.hasFocus()) {
                continue
            }
            val ti = w[TextInput]!!

            ti.fetchFunction?.let { fn ->
                val fetched = fn()
                if (ti.textData.isNotEmpty() && ! equivalent(ti.textData, fetched)) {
                    ti.textData = StringBuilder(fetched)
                    windowingSystem.fireEvent(TextInputRequiresSync(w))
                }
            }
        }
    }
}

fun Widget.setTextInputContents(textData: String, moveCursorToEnd: Boolean = true, skipEvents: Boolean = false) {
    val w = this
    val ti = w[TextInput] ?: return

    if (textData != ti.textData.toString()) {
        ti.textData = StringBuilder(textData)
        if (moveCursorToEnd) {
            ti.cursorPosition = ti.textData.length
        }
        w.fireEvent(TextInputRequiresSync(w))

        ti.onChangeSet(ti.textData.toString())
        if (skipEvents) {
            w.fireEvent(TextDataChanged(EditorOperation.Set(textData), null))
        }
    }
}

object TextWindowingComponent : WindowingComponent {

    val layoutCache = LRULoadingCache<TextLayout.Params, TextLayout>(500, 0.5f) { TextLayout.layout(it) }

    override fun dataTypes() : List<DataType<EntityData>> {
        return listOf(TextDisplay)
    }


    fun params(td: TextDisplay, region: Recti, alignment : HorizontalTextAlignment) : TextLayout.Params {
        return TextLayout.Params(
            text = td.text(),
            defaultFont = td.font,
            region = region,
            defaultColor = td.color(),
            horizontalAlignment = alignment,
            position = region.position
        )
    }


    fun intrinsicSizeFor(params : TextLayout.Params, axis : Axis2D) : Int {
        val layout = if (params.text.isEmpty()) {
            layoutCache.getOrPut(params.copy(text = RichText("|")))
        } else {
            layoutCache.getOrPut(params)
        }

        val raw = layout.max[axis] - layout.min[axis]
        return when(axis) {
            Axis2D.X -> raw + 1
            Axis2D.Y -> raw
        }
    }

    override fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        val td = w[TextDisplay] ?: return null

        val region = Recti(0, 1, maxSize.x, maxSize.y)

        val params = params(td, region, HorizontalTextAlignment.Left)

        val cursorExcess = tern(axis == Axis2D.X && w[TextInput] != null, 1, 0)
        return intrinsicSizeFor(params, axis) + cursorExcess
    }

    override fun render(ws: WindowingSystem, w: Widget, bounds: Recti, quadsOut: MutableList<WQuad>) {
        val td = w[TextDisplay] ?: return
        val ti = w[TextInput]

        val region = Recti(w.resClientX, w.resClientY + 1, w.resClientWidth + 2, w.resClientHeight + 1)
        val layout = layoutCache.getOrPut(params(td, region, td.horizontalAlignment))



        var cursorPosition = if (layout.quads.isNotEmpty()) {
            Vec2i(layout.quads[0].position.x.toInt() - 1, layout.quads[0].baselineY - layout.quads[0].ascent - 1)
        } else {
            Vec2i(w.resClientX, w.resClientY)
        }

        for (i in 0 .. layout.quads.size) {
            val cursorQuad = if (ti != null && i == ti.cursorPosition && ws.focusedWidget == w) {
                WQuad(
                    position = Vec3i(cursorPosition.x, cursorPosition.y, 0),
                    dimensions = Vec2i(2, layout.lineHeight.max(10)),
                    image = null,
                    color = RGBA(128,128,128,255),
                    beforeChildren = true
                )
            } else { null }

            quadsOut.addNonNull(cursorQuad)

            if (i < layout.quads.size) {
                val squad = layout.quads[i]
                if (squad.dimensions.x > 0 && squad.dimensions.y > 0) {
                    quadsOut.add(
                        WQuad(
                            position = Vec3i(squad.position.x.toInt(), squad.position.y.toInt(), 0),
                            dimensions = Vec2i(squad.dimensions.x.toInt(), squad.dimensions.y.toInt()),
                            image = squad.image,
                            color = squad.color ?: Black,
                            beforeChildren = true,
                            subRect = Rectf(0.0f, 1.0f, 1.0f, -1.0f)
                        )
                    )
                }
                cursorPosition = Vec2i(squad.position.x.toInt() + squad.advance - 1, squad.baselineY - squad.ascent - 1)
            }
        }
    }

    override fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {
        val td = w[TextDisplay] ?: return

        if (td.text.update(ctx)) {
            w.markForUpdate(RecalculationFlag.Contents)
            if (w.dimensions(Axis2D.X).isIntrinsic() ) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if( w.dimensions(Axis2D.Y).isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }
    }

    override fun handleEvent(w: Widget, event: DisplayEvent): Boolean {
        when (event) {
            is TextInputRequiresSync -> {
                val ti = w[TextInput] ?: return false
                val td = w[TextDisplay] ?: return false
                syncDisplayToData(w, ti, td)
                return true
            }
        }
        return false
    }

    fun syncDisplayToData(w: Widget, ti: TextInput, td: TextDisplay) {
        val transformed = ti.toRichTextTransformer(ti.textData.toString())
        if (transformed != td.text()) {
            td.text = bindable(transformed)
            w.markForUpdate(RecalculationFlag.Contents)
            if (w.width.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsX)
            }
            if (w.height.isIntrinsic()) {
                w.markForUpdate(RecalculationFlag.DimensionsY)
            }
        }
    }

}