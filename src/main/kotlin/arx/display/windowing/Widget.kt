package arx.display.windowing

import arx.core.*
import arx.core.bindable.BindablePatternEval
import arx.display.ascii.AsciiDrawCommand
import arx.display.core.Image
import arx.display.core.ImagePath
import arx.display.core.ImageRef
import arx.display.windowing.RecalculationFlag.*
import arx.display.windowing.components.CustomWidget
import arx.display.windowing.components.GiveUpFocusEvent
import arx.display.windowing.components.ascii.AsciiRichText
import arx.engine.*
import com.typesafe.config.ConfigValue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.functions
import kotlin.reflect.full.instanceParameter

val WidgetIdCounter = AtomicInteger(1)


enum class WidgetOrientation {
    TopLeft,
    BottomRight,
    TopRight,
    BottomLeft,
    Center;

    companion object : FromConfigCreator<WidgetOrientation> {
        fun fromString(str: String): WidgetOrientation? {
            return when (str.lowercase()) {
                "topleft", "left", "top", "above" -> TopLeft
                "bottomright" -> BottomRight
                "topright", "right" -> TopRight
                "bottomleft", "bottom", "below" -> BottomLeft
                "center" -> Center
                else -> null
            }
        }

        override fun createFromConfig(cv: ConfigValue?): WidgetOrientation? {
            return cv?.asStr()?.let { fromString(it) }
        }
    }
}

enum class RecalculationFlag {
    PositionX,
    PositionY,
    PositionZ,
    DimensionsX,
    DimensionsY,
    Contents,
    Bindings,
    Children
}

sealed interface WidgetIdentifier {
    fun toWidget(context: Widget): Widget?
}

data class WQuad(
    var position: Vec3i,
    var dimensions: Vec2i,
    var image: Image?,
    var color: arx.core.RGBA?,
    var beforeChildren: Boolean,
    var forward: Vec2f? = null,
    var subRect: Rectf? = null
)


/**
 * A widget identified by a name within the context of another widget's view
 * That is, it is intended to look up other widgets that share a parent and
 * have the given name.
 * `context` is the widget that is "interested" in another widget of the given
 * name.
 */
data class WidgetNameIdentifier(val name: String) : WidgetIdentifier {
    override fun toWidget(context: Widget): Widget? {
        return context.parent?.children?.find { it.identifier.contentEquals(name, ignoreCase = true) }
    }
}


val orientedConstantPattern = "(?i)([\\d-]+) from (.*)".toRegex()
val orientedProportionPattern = "(?i)([\\d.-]+) from (.*)".toRegex()
val rightLeftPattern = "(?i)([\\d-]+) (right|left) of ([a-zA-Z\\d]+)".toRegex()
val belowAbovePattern = "(?i)([\\d-]+) (below|above) ([a-zA-Z\\d]+)".toRegex()
val expandToParentPattern = "(?i)expand\\s?to\\s?parent(?:\\((\\d+)\\))?".toRegex()
val expandToWidgetPattern = "(?i)expand\\s?to\\s?([a-zA-Z\\d]+)(?:\\((\\d+)\\))?".toRegex()
val intrinsicPattern = "(?i)intrinsic(?:\\((\\d+)\\s?,?\\s?(\\d+)?\\))?".toRegex()
val percentagePattern = "(\\d+)%".toRegex()
val centeredPattern = "(?i)center(ed)?".toRegex()
val wrapContentPattern = "(?i)wrap\\s?content\\s?(?:\\((\\d+)\\s?,?\\s?(\\d+)?\\))?".toRegex()
val wrapContentOrFillPattern = "(?i)wrap\\s?content\\s?or\\s?fill".toRegex()
val matchPosPattern = "(?i)match ([a-zA-Z\\d]+)".toRegex()

sealed interface WidgetPosition {
    data class Fixed(val offset: Int, val relativeTo: WidgetOrientation = WidgetOrientation.TopLeft) : WidgetPosition {
        operator fun plus(x: Int) : Fixed {
            return copy(offset = offset + x)
        }
        operator fun minus(x: Int) : Fixed {
            return copy(offset = offset + x)
        }
    }
    data class Proportional(val proportion: Float, val relativeTo: WidgetOrientation = WidgetOrientation.TopLeft, val anchorToCenter: Boolean = false) : WidgetPosition
    data class Absolute(val position: Int, val anchorTo: WidgetOrientation = WidgetOrientation.TopLeft) : WidgetPosition
    data class Pixel(val pixelPosition: Vec2i, val anchorTo: WidgetOrientation = WidgetOrientation.TopLeft) : WidgetPosition
    object Centered : WidgetPosition
    data class Relative(
        val relativeTo: WidgetIdentifier,
        val offset: Int,
        val targetAnchor: WidgetOrientation = WidgetOrientation.BottomRight,
        val selfAnchor: WidgetOrientation = WidgetOrientation.TopLeft
    ) : WidgetPosition

    companion object : FromConfigCreator<WidgetPosition> {
        override fun createFromConfig(cv: ConfigValue?): WidgetPosition? {
            if (cv == null) {
                return null
            }
            if (cv.isNum()) {
                val num = cv.asFloat() ?: 0.0f
                return if (num >= 1.0f || num <= 0.0f) {
                    Fixed(num.roundToInt())
                } else {
                    Proportional(num)
                }
            } else if (cv.isStr()) {
                val ret = fromString(cv.asStr()!!)
                if (ret == null) {
                    Noto.recordError("Invalid string format for WidgetPosition : ${cv.asStr()}")
                }
                return ret
            } else {
                Noto.recordError("Invalid config for WidgetPosition", mapOf("config" to cv))
                return null
            }
        }

        fun fromString(str: String): WidgetPosition? {
            orientedConstantPattern.match(str)?.let { (distStr, relativeStr) ->
                val relativeTo = WidgetOrientation.fromString(relativeStr)
                return relativeTo?.let { Fixed(distStr.toInt(), it) }
            }
            orientedProportionPattern.match(str)?.let { (propStr, relativeToStr) ->
                val prop = propStr.toFloatOrNull()
                val relativeTo = WidgetOrientation.fromString(relativeToStr)
                if (prop != null && relativeTo != null) {
                    return Proportional(prop, relativeTo)
                } else {
                    return null
                }
            }
            rightLeftPattern.match(str)?.let { (distStr, dirStr, targetStr) ->
                val dir = WidgetOrientation.fromString(dirStr) ?: WidgetOrientation.TopLeft
                val selfAnchor = when(dir) {
                    WidgetOrientation.TopLeft, WidgetOrientation.BottomLeft -> WidgetOrientation.TopRight
                    else -> WidgetOrientation.TopLeft
                }
                return Relative(WidgetNameIdentifier(targetStr), distStr.toInt(), dir, selfAnchor)
            }
            belowAbovePattern.match(str)?.let { (distStr, dirStr, targetStr) ->
                val dir = WidgetOrientation.fromString(dirStr) ?: WidgetOrientation.TopLeft
                val selfAnchor = when(dir) {
                    WidgetOrientation.BottomLeft, WidgetOrientation.BottomRight -> WidgetOrientation.TopLeft
                    else -> WidgetOrientation.BottomLeft
                }
                return Relative(WidgetNameIdentifier(targetStr), distStr.toInt(), dir, selfAnchor)
            }
            matchPosPattern.match(str)?.let { (targetStr) ->
                return Relative(WidgetNameIdentifier(targetStr), 0, WidgetOrientation.TopLeft, WidgetOrientation.TopLeft)
            }
            if (centeredPattern.match(str) != null) {
                return Centered
            }
            return null
        }
    }
}

sealed interface WidgetDimensions {
    data class Fixed(val size: Int) : WidgetDimensions
    data class Relative(val delta: Int) : WidgetDimensions
    data class Proportional(val proportion: Float) : WidgetDimensions
    data class ExpandToParent(val gap: Int) : WidgetDimensions
    data class Intrinsic(val max: Int? = null, val min: Int? = null) : WidgetDimensions
    data class WrapContent(val min: Int? = null, val max: Int? = null) : WidgetDimensions
    object WrapContentOrFill : WidgetDimensions // WrapContent if parent is WrapContent, 100% if parent is something else
    data class ExpandTo(val expandTo: WidgetIdentifier, val gap: Int) : WidgetDimensions

    fun dependsOnParent(): Boolean {
        return when (this) {
            is Intrinsic -> false
            is WrapContent -> false
            is Fixed -> false
            else -> true
        }
    }

    fun isIntrinsic(): Boolean {
        return when (this) {
            is Intrinsic -> true
            else -> false
        }
    }

    companion object : FromConfigCreator<WidgetDimensions> {
        override fun createFromConfig(cv: ConfigValue?): WidgetDimensions? {
            if (cv == null) {
                return null
            }
            if (cv.isNum()) {
                val num = cv.asFloat() ?: 0.0f
                return if (num > 1.0f) {
                    Fixed(num.roundToInt())
                } else if (num < 0.0f) {
                    Relative(num.toInt() * -1)
                } else {
                    Proportional(num)
                }
            } else if (cv.isStr()) {
                val ret = fromString(cv.asStr()!!)
                if (ret == null) {
                    Noto.recordError("Invalid string format for WidgetDimensions : ${cv.asStr()}")
                }
                return ret
            } else {
                Noto.recordError("Invalid config for WidgetDimensions", mapOf("config" to cv))
                return null
            }
        }

        fun fromString(str: String): WidgetDimensions? {
            if (str.lowercase() == "intrinsic") {
                return Intrinsic()
            }
            intrinsicPattern.match(str)?.let { (firstArg, secondArg) ->
                return Intrinsic(min = firstArg.ifEmpty { null }?.toIntOrNull(), max = secondArg.ifEmpty { null }?.toIntOrNull())
            }
            expandToParentPattern.match(str)?.let { (gapStr) ->
                return ExpandToParent(gapStr.toIntOrNull() ?: 0)
            }
            expandToWidgetPattern.match(str)?.let { (widgetStr, gapStr) ->
                return ExpandTo(WidgetNameIdentifier(widgetStr), gapStr.toIntOrNull() ?: 0)
            }
            percentagePattern.match(str)?.let { (percentStr) ->
                return Proportional(percentStr.toFloat() / 100.0f)
            }
            if (wrapContentOrFillPattern.match(str) != null) {
                return WrapContentOrFill
            }
            wrapContentPattern.match(str)?.let { (firstArg, secondArg) ->
                return WrapContent(min = firstArg.ifEmpty { null }?.toIntOrNull(), max = secondArg.ifEmpty { null }?.toIntOrNull())
            }
            return null
        }
    }
}

enum class DependencyKind {
    PartialPosition,
    Position,
    Dimensions
}

fun dependencyKind(l: Long): DependencyKind {
    return when (l) {
        0L -> DependencyKind.PartialPosition
        1L -> DependencyKind.Position
        2L -> DependencyKind.Dimensions
        else -> error("Invalid dependency kind ordinal: $l")
    }
}

//data class Dependency(var widget: Widget, var kind: DependencyKind, var axis: Axis) {
//    companion object {
//        const val AxisMask = ((1 shl 2) - 1).toLong()
//        const val KindMask = ((1 shl 2) - 1).toLong()
//    }
//}

@JvmInline
value class Dependency(val packed: Long) {
    constructor (widget: Widget, kind: DependencyKind, axis: Axis) :
            this(axis.ordinal.toLong() or (kind.ordinal shl 2).toLong() or (widget.entity.id shl 4).toLong())

    companion object {
        const val AxisMask = ((1 shl 2) - 1).toLong()
        const val AxisShift = 0
        const val KindMask = ((1 shl 2) - 1).toLong()
        const val KindShift = AxisShift + 2
        const val WidgetShift = KindShift + 2
    }

    val axisOrd: Int
        get() {
            return (packed and AxisMask).toInt()
        }
    val axis: Axis
        get() {
            return axis((packed and AxisMask).toInt())
        }
    val kindOrd: Int
        get() {
            return ((packed shr KindShift) and KindMask).toInt()
        }
    val kind: DependencyKind
        get() {
            return dependencyKind((packed shr KindShift) and KindMask)
        }
    val widgetId: Int
        get() {
            return (packed shr WidgetShift).toInt()
        }

    fun isEmpty(): Boolean {
        return packed == 0L
    }

    override fun toString(): String {
        return "Dependency(widget: $widgetId, kind: $kind, axis: $axis)"
    }
}


/**
 * Represents a dependent relationship. The widget containing this is dependent
 * on another widget's Position/PartialPosition/Dimensions in a particular axis
 * in order to be able to calculate its own Position/etc on an axis
 */
data class Dependent(
    var dependentWidget: WeakReference<Widget>,
    var dependentKind: DependencyKind,
    var dependentAxis: Axis,
    var sourceKind: DependencyKind,
    var sourceAxis: Axis
)


class DependencySet(initialSize: Int = 1024) {
    val intern = LongOpenHashSet(initialSize)

    fun add(d: Dependency): Boolean {
        return intern.add(d.packed)
    }

    fun add(w: Widget, k: DependencyKind, axis: Axis): Boolean {
        return intern.add(Dependency(w, k, axis).packed)
    }

    fun contains(d: Dependency): Boolean {
        return intern.contains(d.packed)
    }

    fun contains(w: Widget, k: DependencyKind, axis: Axis): Boolean {
        return intern.contains(Dependency(w, k, axis).packed)
    }

    fun forEach(fn: (Dependency) -> Unit) {
        val iter = intern.iterator()
        while (iter.hasNext()) {
            fn(Dependency(iter.nextLong()))
        }
    }

    override fun toString(): String {
        val joinedDependencies = intern.joinToString("\n") { l -> "\t" + Dependency(l).toString() }
        return """DependencySet[
    $joinedDependencies
"""
    }

    val size
        get() : Int {
            return intern.size
        }
}

data class NineWayImage(
    var image: Bindable<ImageRef>,
    var scale: Int = 1,
    var draw: Bindable<Boolean> = ValueBindable.True,
    var drawCenter: Bindable<Boolean> = ValueBindable.True,
    var drawEdges: Bindable<Boolean> = ValueBindable.True,
    var color: Bindable<RGBA?> = ValueBindable.Null(),
    var centerColor: Bindable<RGBA?> = ValueBindable.Null(),
    var edgeColor: Bindable<RGBA?> = ValueBindable.Null(),
) : FromConfig {

    companion object : FromConfigCreator<NineWayImage> {
        override fun createFromConfig(cv: ConfigValue?): NineWayImage? {
            if (cv == null) { return null }
            return NineWayImage(
                image = bindableImage(cv["image"]),
                scale = cv["scale"].asInt() ?: 1,
                draw = cv["draw"]?.let { bindableBool(it) } ?: ValueBindable.True,
                drawEdges = cv["drawEdges"]?.let { bindableBool(it) } ?: ValueBindable.True,
                drawCenter = cv["drawCenter"]?.let { bindableBool(it) } ?: ValueBindable.True,
                color = bindableRGBAOpt(cv["color"]),
                edgeColor = bindableRGBAOpt(cv["edgeColor"]),
                centerColor = bindableRGBAOpt(cv["centerColor"]),
            )
        }
    }
    override fun readFromConfig(cv: ConfigValue) {
        cv["scale"].asInt()?.let { scale = it }
        cv["draw"]?.let{ draw = bindableBool(it) }
        cv["drawCenter"]?.let { drawCenter = bindableBool(it) }
        cv["drawEdges"]?.let { drawEdges = bindableBool(it) }
        cv["color"]?.let { color = bindableRGBA(it) }
        cv["edgeColor"]?.let { edgeColor = bindableRGBA(it) }
        cv["centerColor"]?.let { centerColor = bindableRGBA(it) }
        cv["image"]?.let { image = bindableImage(it) }
    }

    fun copy() : NineWayImage {
        return NineWayImage(
            image = image.copyBindable(),
            scale = scale,
            draw = draw.copyBindable(),
            drawCenter = drawCenter.copyBindable(),
            drawEdges = drawEdges.copyBindable(),
            color = color.copyBindable(),
            centerColor = centerColor.copyBindable(),
            edgeColor = edgeColor.copyBindable()
        )
    }
}


@Retention(AnnotationRetention.RUNTIME)
annotation class PrimitiveBindingType

val PrimitiveBindingTypes = setOf(
    Int::class,
    Short::class,
    Long::class,
    String::class,
    Float::class,
    Double::class,
    Byte::class,
    Boolean::class,
    RichText::class,
    List::class,
    Set::class,
    AsciiRichText::class,
    ImagePath::class,
    Image::class,
)


data class WidgetBinding (val value: Any, val hash: Int) {
    constructor(v: Any) : this(v, v.hashCode())
}

object SentinelBinding

class Widget(val windowingSystem: WindowingSystem, var parent: Widget?) : WidgetIdentifier, FromConfig {

    companion object {
        /**
         * Make a copy of the given object if it has a copy function available
         * otherwise return the original value
         */
        fun copy(v: Any) : Any? {
            val copyFn = copyFn(v)
            return copyFn?.callBy(mapOf(copyFn.instanceParameter!! to v))
        }

        fun copyFn(v : Any) : KFunction<*>? {
            try {
                return v::class.functions.find { it.name == "copy" }
            } catch (op: java.lang.UnsupportedOperationException) {
                Noto.err("Attempted to copy object at bind time that reflection cannot act on: $v")
                return null
            }
        }
    }

    init {
        parent?.addChild(this)
    }

    var identifier: String? = null
    val entity: Entity = windowingSystem.world.createEntity()
    var children: List<Widget> = emptyList()
        private set

    fun sortChildren() {
        var prevZ = Int.MIN_VALUE
        for (c in children) {
            if (c.resZ < prevZ) {
                children = children.sortedBy { s -> s.resZ }
                return
            }
            prevZ = c.resZ
        }
    }

    private fun addChild(w: Widget) {
        children += w
        markForUpdate(Children)
        if (width is WidgetDimensions.WrapContent) {
            markForUpdate(DimensionsX)
        }
        if (height is WidgetDimensions.WrapContent) {
            markForUpdate(DimensionsY)
        }

        layout?.layout(this)
    }
    fun removeChild(w: Widget) {
        children = children - w

        layout?.layout(this)
    }

    fun createWidget(arch : String) : Widget {
        return windowingSystem.createWidget(this, arch)
    }

    fun createWidget(cw: CustomWidget) : Widget {
        return windowingSystem.createWidget(this, cw.archetype)
    }

    internal var dependents = mutableSetOf<Dependent>()

    internal var position : Vec3<WidgetPosition> = Vec3(WidgetPosition.Fixed(0), WidgetPosition.Fixed(0), WidgetPosition.Fixed(0))
        private set
    internal var dimensions = Vec2<WidgetDimensions>(WidgetDimensions.Intrinsic(), WidgetDimensions.Intrinsic())
        private set

    var padding = Vec3i()

    var showing: Bindable<Boolean> = ValueBindable.True
        set(b) {
            val before = field()
            if (b != field) {
                val after = field()
                markForFullUpdateAndAllDescendants()
                if (before != after) {
                    fireEvent(WidgetVisibilityEvent(after))
                }
            }
            field = b
        }
    var resolvedPosition = Vec3i()
    var resolvedPartialPosition = Vec3i()
    var resolvedDimensions = Vec2i()
    var resolvedClientOffsetNear = Vec3i()
    var resolvedClientOffsetFar = Vec3i()

    internal var localOffset = Vec2i()

    var layout : WidgetLayout? = null

    val bindings = mutableMapOf<String, WidgetBinding>()
    var bindingRedirects = listOf<Triple<BindablePatternEval.Node, WidgetBinding, String>>()
    var eventCallbacks: List<(DisplayEvent) -> Boolean> = emptyList()

    var destroyed: Boolean = false
    var initialized: Boolean = false

    var data: Bindable<Any?> = ValueBindable.Null()
    var dataProperty: PropertyBinding? = null

    fun hide() {
        showing = ValueBindable.False
    }
    fun show() {
        showing = ValueBindable.True
    }

    fun isVisible() : Boolean {
        return showing() && (parent?.isVisible() ?: true)
    }

    fun takeFocus() {
        windowingSystem.giveFocusTo(this)
    }
    fun giveUpFocus() {
        fireEvent(GiveUpFocusEvent())
    }
    fun hasFocus() : Boolean {
        return windowingSystem.focusedWidget == this
    }

    fun redirectBinding(a: BindablePatternEval.Node, b: String) {
        bindingRedirects += Triple(a, WidgetBinding(SentinelBinding, 0), b.trim())
    }

    fun redirectBinding(a: ConfigValue, b: String) {
        when (val s = a.asStr()) {
            null -> Noto.warn("redirectBinding(...) expects a string, but got $a (targeting $b)")
            else -> {
                val parsed = BindablePatternEval.parseOpt(s) { it }
                if (parsed == null) {
                    Noto.warn("could not parse value to redirect binding from '$s' (targeting $b)")
                } else {
                    val constEval = parsed.constantEvaluation()
                    if (constEval != null) {
                        bind(b.trim(), constEval)
                    } else {
                        bindingRedirects += Triple(parsed, WidgetBinding(SentinelBinding, 0), b.trim())
                    }
                }
            }
        }
    }

    override fun readFromConfig(cv: ConfigValue) {
        WidgetPosition(cv["x"])?.let { x = it }
        WidgetPosition(cv["y"])?.let { y = it }
        WidgetPosition(cv["z"])?.let { z = it }

        if (cv["type"].asStr()?.lowercase() == "div") {
            width = WidgetDimensions.WrapContent()
            height = WidgetDimensions.WrapContent()
            background.draw = ValueBindable.False
        }

        WidgetDimensions(cv["width"])?.let { width = it }
        WidgetDimensions(cv["height"])?.let { height = it }

        background.readFromConfig(cv["background"])

        cv["showing"]?.let { showing = bindableBool(it) }

        (cv["padding"].asVec3i() ?: cv["padding"].asVec2i()?.let { Vec3i(it.x, it.y, 0) })?.let { padding = it }

        cv["ignoreBounds"]?.asBool()?.let { ignoreBounds = it }

        data = bindableAnyOpt(cv["data"])
        dataProperty = propertyBinding(cv["dataProperty"], true)

        cv["redirectBinding"].asStr()?.let { rb ->
            val sections = rb.split("->")
            if (sections.size != 2) {
                Noto.warn("invalid redirectBinding $rb, should be of the form a -> b")
            } else {
//                bindings[sections[0].trim()] = WidgetBinding(BindingRedirect(sections[1].trim()), 0)
                bindingRedirects += Triple(BindablePatternEval.Lookup(sections[0].trim()), WidgetBinding(SentinelBinding, 0), sections[1].trim())
            }
        }

        cv["bindingPointer"].asStr()?.let { bp ->
            val sections = bp.split("->")
            if (sections.size != 2) {
                Noto.warn("invalid bindingPointer $bp, should be of the form a -> b")
            } else {
                bindings[sections[1].trim()] = WidgetBinding(BindingPointer(sections[0].trim()), 0)
            }
        }

        layout = WidgetLayout(cv["layout"])
    }

    /**
     * Copies the raw configuration from the source widget, but not in memory state like
     * resolved position, dependencies, parents, etc
     */
    fun copyFrom(w : Widget) {
        x = w.x
        y = w.y
        z = w.z
        width = w.width
        height = w.height
        background = w.background.copy()
        overlay = w.overlay?.copy()
        showing = w.showing.copyBindable()
        padding = Vec3i(w.padding.x, w.padding.y, w.padding.z)
        ignoreBounds = w.ignoreBounds
        layout = w.layout
        dataProperty = w.dataProperty?.copy()
        data = w.data.copyBindable()
        // we pull in bindings so that archetypes can set up binding pointers or defaults
        // that are then propagated to children
        bindings.putAll(w.bindings)
        bindingRedirects = w.bindingRedirects
    }


    fun updateBindings(ctx: BindingContext) {
        data.update(ctx)
        dataProperty?.update(ctx)

        for ((src,binding,to) in bindingRedirects) {
            if (src.isDirty(ctx)) {
                bind(to, src.evaluate(ctx))
            }
        }

        val beforeShowing = showing()
        if (showing.update(ctx)) {
            if (beforeShowing != showing()) {
                fireEvent(WidgetVisibilityEvent(showing()))
                markForFullUpdateAndAllDescendants()
            }
        }
    }

    private fun bindInternal(k: String, v: Any?, forceUpdate: Boolean = false, noCopy: Boolean = true) : Boolean {
        val curBinding = bindings[k]

        (curBinding?.value as? BindingRedirect)?.let { br ->
            if (br.redirectTo != k) { // no infinite loops allowed
                return bindInternal(br.redirectTo, v, forceUpdate, noCopy)
            }
        }

        val curV = curBinding?.value
//        if (v is BindingPointer) {
////             Todo: figure out some way to efficiently detect if a pointer binding is dirty
//            return false
//        }

        val vHash = v?.hashCode()
        if (forceUpdate || curV != v || curBinding?.hash != vHash) {
            if (v != null) {
                // store a copy if this is copyable. It's still a _shallow_
                // copy so it's not perfect, but it will catch more changes
                // than the alternative
                if (noCopy) {
                    bindings[k] = WidgetBinding(value = v, hash = vHash!!)
                } else {
                    bindings[k] = WidgetBinding(value = copy(v) ?: v, hash = vHash!!)
                }
            } else {
                bindings.remove(k)
            }

            return true
        }
        return false
    }


    // +============================================================================+
    // |                            Binding                                         |
    // +============================================================================+
    fun bind(k : String, v : Any?, forceUpdate: Boolean = false, noCopy: Boolean = true) {
        if (v is Bindable<*>) {
            bind(k, v.invoke(), forceUpdate, noCopy)
        } else {
            if (bindInternal(k, v, forceUpdate, noCopy)) {
                Metrics.timer("recursiveBindingUpdate").timeStmt {
                    windowingSystem.recursivelyUpdateBindings(this, buildBindingContext(setOf(k), forceUpdate), ObjectOpenHashSet())
                }
            }
        }
    }

    fun checkBinding(k: String) {
        val curBinding = bindings[k]
        if (curBinding != null && curBinding.hash != curBinding.value.hashCode()) {
            windowingSystem.recursivelyUpdateBindings(this, buildBindingContext(setOf(k), false), ObjectOpenHashSet())
        }
    }

    fun checkBindings() {
        windowingSystem.recursivelyUpdateBindings(this, buildBindingContext(null, false), ObjectOpenHashSet())
    }

    private fun bindFieldsInternal(k: String, v : Any?) : Boolean {
        var modified = false
        if (v == null) {
            modified = bindings.remove(k) != null
        } else if ( PrimitiveBindingTypes.contains(v::class) || v::class.java.isAnonymousClass || v::class.annotations.any { it is PrimitiveBindingType }) {
            // for the moment, we're assuming that anon classes are functions, primitive, and annotated types should be bound directly
            modified = bindInternal(k, v)
        } else if (v is Map<*,*>) {
            for ((sk, sv) in v) {
                val newK = tern (k.isEmpty(), "$sk", "$k.$sk")
                if (bindFieldsInternal(newK, sv)) {
                    modified = true
                }
            }
        } else if (v is Collection<*> || v is Sequence<*>) {
            modified = bindInternal(k, v)
        } else {
            for (prop in v::class.declaredMemberProperties) {
                if (prop.visibility == KVisibility.PUBLIC) {
                    val newK = tern (k.isEmpty(), prop.name, "$k.${prop.name}")
                    if (bindFieldsInternal(newK, prop.getter.call(v))) {
                        modified = true
                    }
                }
            }
            for (fn in v::class.declaredFunctions) {
                if (fn.parameters.size == 1 && ! fn.isOperator) {
                    fn.instanceParameter?.let { inst ->
                        val newK = tern (k.isEmpty(), fn.name, "$k.${fn.name}")
                        if (bindFieldsInternal(newK, fn.callBy(mapOf(inst to v)))) {
                            modified = true
                        }
                    }
                }
            }
        }

        return modified
    }

    fun bind(vararg pairs : Pair<String, Any?>) {
        for (pair in pairs) {
            bind(pair.first, pair.second)
        }
    }

    fun bind(v: Any) {

        bindFieldsInternal("", v)
    }

    fun bindPointer(p : Pair<String, String>) {
        bind(p.first, BindingPointer(p.second))
    }

    fun bindPointer(from: String, to: String, transform: ((Any?) -> Any?)?, forceUpdate: Boolean = false) {
        bind(from, BindingPointer(to, transform), forceUpdate = forceUpdate)
    }


    fun unbind(k : String) {
        bind(k, null)
    }

    fun buildBindingContext(dirtyBindings: Set<String>?, forceUpdate: Boolean) : BindingContext {
        return BindingContext(bindings, parent?.buildBindingContext(dirtyBindings, forceUpdate), dirtyBindings, forceUpdate)
    }

    // +============================================================================+
    // |                            Drawing                                         |
    // +============================================================================+

    var background = NineWayImage(bindable(ImagePath("arx/ui/defaultBorder.png")))
    var overlay: NineWayImage? = null


    val quads : MutableList<WQuad> = ArrayList(0)
    val asciiDrawCommands : MutableList<AsciiDrawCommand> = ArrayList(0)
    var bounds = Recti(0, 0, 0, 0)
    var ignoreBounds = false
        set(v) {
            if (field != v) {
                field = v
                if (v) {
                    windowingSystem.ignoreBoundsWidgets.add(this)
                } else {
                    windowingSystem.ignoreBoundsWidgets.remove(this)
                }
            }
        }

    // +============================================================================+
    // |                            Access                                          |
    // +============================================================================+

    fun markForUpdate(d: RecalculationFlag) {
        windowingSystem.markForUpdate(this, d)
    }

    fun markForFullUpdate() {
        windowingSystem.markForFullUpdate(this)
    }

    fun markForFullUpdateAndAllDescendants() {
        windowingSystem.markForFullUpdate(this)
        for (c in children) {
            c.markForFullUpdateAndAllDescendants()
        }
    }

    fun position(axis: Axis): WidgetPosition {
        return position[axis]
    }

    fun dimensions(axis: Axis): WidgetDimensions {
        return dimensions[axis]
    }

    fun dimensions(axis: Axis2D): WidgetDimensions {
        return dimensions[axis]
    }

    fun clientOffset(axis: Axis): Int {
        return resolvedClientOffsetNear[axis] + padding[axis]
    }

    fun clientOffsetFar(axis: Axis): Int {
        return resolvedClientOffsetFar[axis] + padding[axis]
    }

    var x: WidgetPosition
        get() {
            return position.x
        }
        set(v) {
            if (position.x != v) {
                markForUpdate(PositionX)
                position.x = v
            }
        }
    var y: WidgetPosition
        get() {
            return position.y
        }
        set(v) {
            if (position.y != v) {
                markForUpdate(PositionY)
                position.y = v
            }
        }
    var z: WidgetPosition
        get() {
            return position.z
        }
        set(v) {
            if (position.z != v) {
                markForUpdate(PositionZ)
                position.z = v
            }
        }

    var width: WidgetDimensions
        get() {
            return dimensions.x
        }
        set(v) {
            if (dimensions.x != v) {
                markForUpdate(DimensionsX)
                dimensions.x = v
            }
        }
    var height: WidgetDimensions
        get() {
            return dimensions.y
        }
        set(v) {
            if (dimensions.y != v) {
                markForUpdate(DimensionsY)
                dimensions.y = v
            }
        }

    var localX: Int
        get() {
            return localOffset.x
        }
        set(v) {
            if (localOffset.x != v) {
                markForUpdate(PositionX)
                localOffset.x = v
            }
        }

    var localY: Int
        get() {
            return localOffset.y
        }
        set(v) {
            if (localOffset.y != v) {
                markForUpdate(PositionY)
                localOffset.y = v
            }
        }

    val resX: Int
        get() {
            return resolvedPosition.x
        }
    val resY: Int
        get() {
            return resolvedPosition.y
        }
    val resClientX: Int
        get() {
            return resolvedPosition.x + clientOffset(Axis.X)
        }
    val resClientY: Int
        get() {
            return resolvedPosition.y + clientOffset(Axis.Y)
        }
    val resZ: Int
        get() {
            return resolvedPosition.z
        }
    val resWidth: Int
        get() {
            return resolvedDimensions.x
        }
    val resHeight: Int
        get() {
            return resolvedDimensions.y
        }
    val resClientWidth: Int
        get() {
            return resolvedDimensions.x - clientOffset(Axis.X) - clientOffsetFar(Axis.X)
        }
    val resClientHeight: Int
        get() {
            return resolvedDimensions.y - clientOffset(Axis.Y) - clientOffsetFar(Axis.Y)
        }

    fun resClientDim(axis: Axis) : Int {
        return resolvedDimensions[axis] - clientOffset(axis) - clientOffsetFar(axis)
    }

    operator fun <T : EntityData> get(dt: DataType<T>): T? {
        return windowingSystem.world.data(entity, dt)
    }

    fun <T : EntityData> attachData(t: T) {
        windowingSystem.world.attachData(entity, t)
    }

    fun addDependent(dep: Dependent) {
        dependents.add(dep)
    }

    fun removeDependent(dep: Dependent) {
        dependents.remove(dep)
    }

    internal fun forEachDependent(fn: (Dependent) -> Unit) {
        for (dep in dependents) {
            fn(dep)
        }
    }

    fun onEvent(stmt: (DisplayEvent) -> Boolean) {
        eventCallbacks = eventCallbacks + stmt
    }

    fun fireEvent(event : DisplayEvent) {
        if (event is WidgetEvent && event.widgets.isEmpty()) {
            event.widgets.add(this)
        }
        windowingSystem.fireEvent(event)
    }

    fun childWithIdentifier(ident: String): Widget? {
        for (c in children) {
            if (c.identifier == ident) {
                return c
            }
        }

        return null
    }

    fun descendantWithIdentifier(ident: String): Widget? {
        val direct = childWithIdentifier(ident)
        if (direct != null) {
            return direct
        }

        for (c in children) {
            val d = c.descendantWithIdentifier(ident)
            if (d != null) {
                return d
            }
        }
        return null
    }

    fun descendantsWithIdentifier(ident: String): Iterator<Widget> {
        return descendants().filter { it.identifier == ident }
    }

    fun hasDescendant(other: Widget): Boolean {
        if (children.contains(other)) {
            return true
        } else {
            for (c in children) {
                if (c.hasDescendant(other)) {
                    return true
                }
            }
            return false
        }
    }

    fun ancestors() : Iterator<Widget> {
        return iterator {
            var p = parent
            while (p != null) {
                yield(p)
                p = p.parent
            }
        }
    }

    fun isDescendantOf(w: Widget) : Boolean {
        return parent == w || parent?.isDescendantOf(w) == true
    }

    fun siblingsAndTheirDescendants() : Iterator<Widget> {
        return iterator {
            parent?.let { p ->
                val queue = ArrayDeque(p.children)
                while (queue.isNotEmpty()) {
                    val w = queue.removeFirst()
                    if (w != this@Widget) {
                        yield(w)
                        for (c in w.children) {
                            queue.addLast(c)
                        }
                    }
                }
            }
        }
    }

    fun descendants() : Iterator<Widget> {
        return iterator {
            val queue = ArrayDeque(children)
            while (queue.isNotEmpty()) {
                val w = queue.removeFirst()
                yield(w)
                for (c in w.children) {
                    queue.addLast(c)
                }
            }
        }
    }

    fun selfAndDescendants() : Iterator<Widget> {
        return iterator {
            yield(this@Widget)
            val queue = ArrayDeque(children)
            while (queue.isNotEmpty()) {
                val w = queue.removeFirst()
                yield(w)
                for (c in w.children) {
                    queue.addLast(c)
                }
            }
        }
    }

    fun selfAndDescendantsDepthFirst() : Iterator<Widget> {
        return iterator {
            yield(this@Widget)
            val stack = children.reversed().toMutableList()
            while (stack.isNotEmpty()) {
                val w = stack.removeLast()
                yield(w)
                for (i in (w.children.size - 1) downTo 0) {
                    stack.add(w.children[i])
                }
            }
        }
    }


    fun selfAndAncestors() : Iterator<Widget> {
        return iterator {
            yield(this@Widget)
            var p = parent
            while (p != null) {
                yield(p)
                p = p.parent
            }
        }
    }

    override fun toWidget(context: Widget): Widget {
        return this
    }

    override fun toString(): String {
        return "Widget(${identifier ?: entity.id})"
    }

    fun destroy() {
        destroyed = true
        windowingSystem.destroyWidget(this)
    }

    fun allData() : List<Any> {
        return windowingSystem.world.dataTypes.mapNotNull { dt ->
            dt?.let { this[it as DataType<EntityData>] }
        }
    }

}


data class WidgetArchetype (var data : List<EntityData>, val widgetData: Widget, val children : Map<String, WidgetArchetype>, val identifier: String) {
    var copyFunctions = data.map { it.javaClass.kotlin.functions.find { f -> f.name == "copy" } }
    fun copyData() : List<EntityData> {
        if (copyFunctions.size != data.size) {
            copyFunctions = data.map { it.javaClass.kotlin.functions.find { f -> f.name == "copy" } }
        }

        return data.indices.mapNotNull { i ->
            val f = copyFunctions[i]
            if (f == null) {
                Noto.recordError("WidgetArchetype made use of data type with no copy function : ${data[i].javaClass.simpleName}")
                null
            } else {
                f.callBy(mapOf(f.instanceParameter!! to data[i])) as EntityData
            }
        }
    }


    operator fun <T : EntityData> get(dt: DataType<T>): T? {
        return data.find { d -> d.dataType() == dt } as T?
    }

}

inline fun <reified T : DisplayEvent> Widget.handleEvent(crossinline stmt: (T) -> Boolean) {
    val callback = { d: DisplayEvent ->
        if (d is T) {
            stmt(d)
        } else {
            false
        }
    }
    onEvent(callback)
}

inline fun <reified T : DisplayEvent> Widget.onEventDo(crossinline stmt: (T) -> Unit) {
    val callback = { d: DisplayEvent ->
        if (d is T) {
            stmt(d)
        }
        false
    }
    onEvent(callback)
}

inline fun <reified T : DisplayEvent> Widget.onEventConsume(crossinline stmt: (T) -> Unit) {
    val callback = { d: DisplayEvent ->
        if (d is T) {
            stmt(d)
            true
        } else {
            false
        }
    }
    onEvent(callback)
}


fun isFarSide(axis: Axis, orientation: WidgetOrientation): Boolean {
    return (axis == Axis.X && (orientation == WidgetOrientation.TopRight || orientation == WidgetOrientation.BottomRight)) ||
            (axis == Axis.Y && (orientation == WidgetOrientation.BottomRight || orientation == WidgetOrientation.BottomLeft))
}

internal fun oppositeAxis(axis: Axis): Axis {
    return when (axis) {
        Axis.X -> Axis.Y
        Axis.Y -> Axis.X
        else -> error("Only can take opposite of 2d axes")
    }
}


inline fun forEachPositionDep(w: Widget, axis: Axis, fn: (Widget, DependencyKind, Axis) -> Unit) {
    w.parent?.let { parent ->
        val p = w.position(axis)

        when (p) {
            is WidgetPosition.Fixed -> {
                fn(parent, DependencyKind.Position, axis)
                if (isFarSide(axis, p.relativeTo)) {
                    fn(parent, DependencyKind.Dimensions, axis)
                    fn(w, DependencyKind.Dimensions, axis)
                }
            }
            is WidgetPosition.Proportional -> {
                fn(parent, DependencyKind.Position, axis)
                fn(parent, DependencyKind.Dimensions, axis)
                if (isFarSide(axis, p.relativeTo) || p.relativeTo == WidgetOrientation.Center) {
                    fn(w, DependencyKind.Dimensions, axis)
                }
            }
            WidgetPosition.Centered -> {
                fn(parent, DependencyKind.Dimensions, axis)
                fn(w, DependencyKind.Dimensions, axis)
                fn(parent, DependencyKind.Position, axis)
            }
            is WidgetPosition.Relative -> {
                val rel = p.relativeTo.toWidget(w)
                if (rel != null) {
                    fn(rel, DependencyKind.Position, axis)
                    if (isFarSide(axis, p.targetAnchor) || p.targetAnchor == WidgetOrientation.Center) {
                        fn(rel, DependencyKind.Dimensions, axis)
                    }
                    if (isFarSide(axis, p.selfAnchor) || p.selfAnchor == WidgetOrientation.Center) {
                        fn(w, DependencyKind.Dimensions, axis)
                    }
                } else {
                    Noto.warn("Relative widget position could not resolve relativeTo: ${p.relativeTo}")
                }
            }
            is WidgetPosition.Absolute -> {
                if (isFarSide(axis, p.anchorTo) || p.anchorTo == WidgetOrientation.Center) {
                    fn(w, DependencyKind.Dimensions, axis)
                }
            }
            is WidgetPosition.Pixel -> {
                if (isFarSide(axis, p.anchorTo) || p.anchorTo == WidgetOrientation.Center) {
                    fn(w, DependencyKind.Dimensions, axis)
                }
            }
        }
    }
}

internal inline fun forEachDimensionDep(w: Widget, axis: Axis, fn: (Widget, DependencyKind, Axis) -> Unit) {
    w.parent?.let { parent ->
        if (axis == Axis.X || axis == Axis.Y) {
            val d = w.dimensions(axis)
            when (d) {
                is WidgetDimensions.Proportional -> fn(parent, DependencyKind.Dimensions, axis)
                is WidgetDimensions.Relative -> fn(parent, DependencyKind.Dimensions, axis)
                is WidgetDimensions.ExpandToParent -> {
                    fn(parent, DependencyKind.Dimensions, axis)
                    fn(parent, DependencyKind.Position, axis)
                    fn(w, DependencyKind.Position, axis)
                }
                is WidgetDimensions.Intrinsic -> {
                    if (w.dimensions(oppositeAxis(axis)) !is WidgetDimensions.Intrinsic) {
                        fn(w, DependencyKind.Dimensions, oppositeAxis(axis))
                    }
                }
                is WidgetDimensions.WrapContent -> {
                    for (c in w.children) {
                        if (! c.ignoreBounds) {
                            fn(c, DependencyKind.PartialPosition, axis)
                            fn(c, DependencyKind.Dimensions, axis)
                        }
                    }
                }
                WidgetDimensions.WrapContentOrFill -> {
                    for (c in w.children) {
                        if (! c.ignoreBounds) {
                            fn(c, DependencyKind.PartialPosition, axis)
                            fn(c, DependencyKind.Dimensions, axis)
                        }
                    }
                    fn(parent, DependencyKind.Dimensions, axis)
                }
                is WidgetDimensions.ExpandTo -> {
                    val expandTo = d.expandTo.toWidget(w)
                    fn(w, DependencyKind.Position, axis)
                    if (expandTo != null) {
                        fn(expandTo, DependencyKind.Position, axis)
                        fn(expandTo, DependencyKind.Dimensions, axis)
                    } else {
                        Noto.warn("ExpandTo could not find target: ${d.expandTo}")
                    }
                }
                is WidgetDimensions.Fixed -> {}
            }
        }
    }
}


//internal fun dependenciesFor(w: Widget, axis: Axis): Sequence<Dependency> {
//    return sequence {
//        w.parent?.let { parent ->
//            val p = w.position[axis]
//
//            when (p) {
//                is WidgetPosition.Fixed -> {
//                    yield(Dependency(parent, DependencyKind.Position, axis))
//                    if (isFarSide(axis, p.relativeTo)) {
//                        yield(Dependency(parent, DependencyKind.Dimensions, axis))
//                        yield(Dependency(w, DependencyKind.Dimensions, axis))
//                    }
//                }
//                is WidgetPosition.Proportional -> {
//                    yield(Dependency(parent, DependencyKind.Position, axis))
//                    yield(Dependency(parent, DependencyKind.Dimensions, axis))
//                    if (isFarSide(axis, p.relativeTo) || p.relativeTo == WidgetOrientation.Center) {
//                        yield(Dependency(w, DependencyKind.Dimensions, axis))
//                    }
//                }
//                is WidgetPosition.Centered -> {
//                    yield(Dependency(parent, DependencyKind.Dimensions, axis))
//                    yield(Dependency(w, DependencyKind.Dimensions, axis))
//                    yield(Dependency(parent, DependencyKind.Position, axis))
//                }
//                is WidgetPosition.Relative -> {
//                    yield(Dependency(p.relativeTo, DependencyKind.Position, axis))
//                    if (isFarSide(axis, p.targetAnchor) || p.targetAnchor == WidgetOrientation.Center) {
//                        yield(Dependency(p.relativeTo, DependencyKind.Dimensions, axis))
//                    }
//                    if (isFarSide(axis, p.selfAnchor) || p.selfAnchor == WidgetOrientation.Center) {
//                        yield(Dependency(w, DependencyKind.Dimensions, axis))
//                    }
//                }
//                is WidgetPosition.Absolute -> {
//                    if (isFarSide(axis, p.anchorTo) || p.anchorTo == WidgetOrientation.Center) {
//                        yield(Dependency(w, DependencyKind.Dimensions, axis))
//                    }
//                }
//            }
//
//            if (axis == Axis.X || axis == Axis.Y) {
//                val d = w.dimensions[axis]
//                when (d) {
//                    is WidgetDimensions.Proportional -> yield(Dependency(parent, DependencyKind.Dimensions, axis))
//                    is WidgetDimensions.Relative -> yield(Dependency(parent, DependencyKind.Dimensions, axis))
//                    is WidgetDimensions.ExpandToParent -> {
//                        yield(Dependency(parent, DependencyKind.Dimensions, axis))
//                        yield(Dependency(w, DependencyKind.Dimensions, axis))
//                    }
//                    is WidgetDimensions.Intrinsic -> {
//                        if (w.dimensions[oppositeAxis(axis)] !is WidgetDimensions.Intrinsic) {
//                            yield(Dependency(w, DependencyKind.Dimensions, oppositeAxis(axis)))
//                        }
//                    }
//                    is WidgetDimensions.WrapContent -> {
//                        for (c in w.children) {
//                            yield(Dependency(c, DependencyKind.PartialPosition, axis))
//                            yield(Dependency(c, DependencyKind.Dimensions, axis))
//                        }
//                    }
//                    is WidgetDimensions.ExpandTo -> {
//                        yield(Dependency(d.expandTo, DependencyKind.Position, axis))
//                        yield(Dependency(d.expandTo, DependencyKind.Dimensions, axis))
//                    }
//                    is WidgetDimensions.Fixed -> {}
//                }
//            }
//        }
//    }
//}