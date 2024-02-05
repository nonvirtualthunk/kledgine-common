package arx.display.windowing

import arx.application.Application
import arx.core.*
import arx.display.ascii.AsciiCanvas
import arx.display.ascii.AsciiColorPalette16Bit
import arx.display.ascii.AsciiDrawCommand
import arx.display.core.Key
import arx.display.core.KeyModifiers
import arx.display.core.MouseButton
import arx.display.windowing.RecalculationFlag.*
import arx.display.windowing.components.*
import arx.display.windowing.components.ascii.*
import arx.display.windowing.customwidgets.CharacterPickerWidget
import arx.display.windowing.customwidgets.HSLSliderWidget
import arx.engine.*
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.ortho
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import java.awt.Font
import java.lang.Integer.max
import java.lang.Integer.min
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType


enum class PropagationDirection {
    Up, // events are handled by a widget, then its parent, etc
    Down, // events are pushed recursively downward through a widget hierarchy
}

abstract class WidgetEvent(srcEvent: DisplayEvent?) : DisplayEvent(srcEvent) {
    val widgets: MutableList<Widget> = mutableListOf()

    val widget : Widget get() { return widgets.last() }
    val originWidget : Widget get() { return widgets.first() }

    fun withWidget(w : Widget) : WidgetEvent {
        widgets.add(w)
        return this
    }

    fun isFrom(ident: String): Boolean {
        return widgets.any { it.identifier == ident }
    }

    open val propagate: PropagationDirection = PropagationDirection.Up
}

data class SignalEvent(val signal: Any?, val data: Any?) : WidgetEvent(null)

interface WidgetMouseEvent {
    val position: Vec2f
}

data class WidgetKeyReleaseEvent(val key: Key, val mods: KeyModifiers, val from: KeyReleaseEvent) : WidgetEvent(from)
data class WidgetKeyPressEvent(val key: Key, val mods: KeyModifiers, val from: KeyPressEvent) : WidgetEvent(from)
data class WidgetCharInputEvent(val char: Char, val mods: KeyModifiers, val from: CharInputEvent) : WidgetEvent(from)
data class WidgetMousePressEvent(override val position: Vec2f, val button: MouseButton, val mods: KeyModifiers, val from: MousePressEvent) : WidgetEvent(from), WidgetMouseEvent
data class WidgetMouseReleaseEvent(override val position: Vec2f, val button: MouseButton, val mods: KeyModifiers, val from: MouseReleaseEvent) : WidgetEvent(from), WidgetMouseEvent
data class WidgetMouseEnterEvent(val from: MouseMoveEvent) : WidgetEvent(from)
data class WidgetMouseExitEvent(val from: MouseMoveEvent) : WidgetEvent(from)
data class WidgetMouseMoveEvent(override val position: Vec2f, val delta: Vec2f, val mods: KeyModifiers, val from: MouseMoveEvent) : WidgetEvent(from), WidgetMouseEvent
data class WidgetMouseDragEvent(override val position: Vec2f, val delta: Vec2f, val button: MouseButton, val mods: KeyModifiers, val from: MouseDragEvent) : WidgetEvent(from), WidgetMouseEvent
data class WidgetMouseScrollEvent(val position: Vec2f, val delta: Vec2f, val mods: KeyModifiers, val from: MouseScrollEvent) : WidgetEvent(from)


data class WidgetVisibilityEvent(val showing: Boolean): WidgetEvent(null)

interface WindowingComponent {
    val configArchetypesByType : Map<String, String> get() { return emptyMap() }


    fun propagateConfigToChildren(w: WidgetArchetype): Set<String> {
        return emptySet()
    }
//    /**
//     * This... bears explaining. We want to be able to create custom widgets with minimal fuss. Often
//     * this involves wrapping a couple of sub-widgets in a div and layering functionality on top. But
//     * we want to make those subcomponents configurable themselves. I.e. if we build a wrapper around
//     * a list + a button then we want "gapSize" to be configurable on instances of it. So we specify
//     * the particular kinds of data... actually. Do we just want everything other than the core widget
//     * stuff? Hold please...
//     */
//    fun propagateConfigToChildrenForTypes() : List<DataType<*>> { return emptyList() }

    fun drawPriority() : Priority {
        return Priority.Normal
    }
    fun eventPriority() : Priority {
        return Priority.Normal
    }
    fun updateBindingsPriority() : Priority {
        return Priority.Normal
    }
    fun configurePriority() : Priority {
        return Priority.Normal
    }

    fun dataTypes() : List<DataType<EntityData>> { return emptyList() }

    fun configure(ws: WindowingSystem, w: WidgetArchetype, cv: ConfigValue) {}

    fun initializeWidget(w: Widget) {}

    fun intrinsicSize(w: Widget, axis: Axis2D, minSize: Vec2i, maxSize: Vec2i): Int? {
        return null
    }

    fun clientOffsetContributionNear(w: Widget, axis: Axis2D): Int? {
        return null
    }

    fun clientOffsetContributionFar(w: Widget, axis: Axis2D): Int? {
        return clientOffsetContributionNear(w, axis)
    }

    fun render(ws: WindowingSystem, w: Widget, bounds: Recti, quadsOut: MutableList<WQuad>) {

    }

    fun renderAscii(ws: AsciiWindowingSystem, w: Widget, bounds: Recti, canvas: AsciiCanvas, commandsOut: MutableList<AsciiDrawCommand>) {

    }

    fun handleEvent(w: Widget, event: DisplayEvent) : Boolean { return false }

    fun updateBindings(ws: WindowingSystem, w: Widget, ctx: BindingContext) {}

    fun update(windowingSystem: WindowingSystem) {}

    fun geometryUpdated(ws: WindowingSystem, completedUpdates: DependencySet, triggeringUpdates: Map<Widget, EnumSet<RecalculationFlag>>) {}
}


object WindowingSystemPreregistration {
    var preregisteredComponents : List<WindowingComponent> = emptyList()
    internal var windowingSystems : List<WindowingSystem> = emptyList()

    fun registerComponent(wc: WindowingComponent) {
            preregisteredComponents += wc
            windowingSystems.forEach { it.registerComponent(wc) }
    }
}

open class WindowingSystem : DisplayData, CreateOnAccessData {
    companion object : DataType<WindowingSystem>({ WindowingSystem() }, sparse = true) {

    }

    override fun dataType(): DataType<*> {
        return WindowingSystem
    }


    val world = World()
    val desktop = Widget(this, null).apply {
        identifier = "Desktop"
        background.image = bindable(Resources.image("ui/fancyBackground.png"))
        background.drawCenter = ValueBindable.False
    }
    val widgets = mutableMapOf(desktop.entity.id to desktop)
    var focusedWidget : Widget? = null
        private set
    var lastWidgetUnderMouse : Widget = desktop
    var lastMousePosition : Vec2f = Vec2f(0.0f,0.0f)
    var scale = 3
    var forceScale: Int? = null
    val configLoadableDataTypes = mutableListOf<DataType<EntityData>>()
    val archetypes = mutableMapOf<String, WidgetArchetype>()

    val ignoreBoundsWidgets = mutableSetOf<Widget>()

    var pendingUpdates = mutableMapOf<Widget, EnumSet<RecalculationFlag>>(desktop to EnumSet.allOf(RecalculationFlag::class.java))
    internal var pendingUpdatesTmp = mutableMapOf<Widget, EnumSet<RecalculationFlag>>()

    var needsRerender = true

    var font: ArxFont = TextLayout.DefaultTypeface.baseFont

    private var components = mutableListOf<WindowingComponent>()
    private var componentsByDrawOrder = listOf<WindowingComponent>()
    private var componentsByEventOrder = listOf<WindowingComponent>()
    private var componentsByUpdateBindingsOrder = listOf<WindowingComponent>()
    private var componentsByConfigurationOrder = listOf<WindowingComponent>()

    open fun defaultStylesheet() : Config {
        return Resources.config("/arx/display/widgets/Stylesheet.sml")
    }


    fun giveFocusTo(w: Widget?, srcEvent: DisplayEvent? = null) {
        updateGeometry()
        val old = focusedWidget
        if (old != w) {
            if (w == null) {
                focusedWidget = null
                old?.fireEvent(FocusChangedEvent(false, srcEvent))
            } else {
                // Depth first, in Y, then X order
                // i.e. recursively iterates into each child, starting with the top left
                // corner and working right and then down
                val stack = mutableListOf(w)
                while (stack.isNotEmpty()) {
                    val sw = stack.removeLast()
                    if (sw.isVisible()) {
                        if (sw[FocusSettings]?.acceptsFocus == true) {
                            focusedWidget = sw
                            old?.fireEvent(FocusChangedEvent(false, srcEvent))
                            sw.fireEvent(FocusChangedEvent(true, srcEvent))
                            break
                        } else {
                            sw.children.sortedWith(Comparator.comparing { x : Widget -> x.resY }.thenComparing { x : Widget -> x.resX }.reversed()).forEach {
                                stack.add(it)
                            }
                        }
                    }
                }
            }
        }
    }

    // Bindable only jused to check whether a bindable has been copied
    class CheckerBindable(var isCopy : Boolean = false) : Bindable<Any?> {
        override fun invoke(): Any? {
            return null
        }

        override fun update(ctx: BindingContext): Boolean {
            return false
        }

        override fun copyBindable(): Bindable<Any?> {
            return CheckerBindable(true)
        }
    }

    fun registerComponent(c: WindowingComponent) {
        if (components.contains(c)) { return }

        components.add(c)
        componentsByDrawOrder = components.sortedByDescending { it.drawPriority() }
        componentsByEventOrder = components.sortedByDescending { it.eventPriority() }
        componentsByUpdateBindingsOrder = components.sortedByDescending { it.updateBindingsPriority() }
        componentsByConfigurationOrder = components.sortedByDescending { it.configurePriority() }
        c.dataTypes().forEach { dt ->
            registerDataType(dt)
        }
    }

    fun registerDataType(dt: DataType<EntityData>) {
        if (dt !is FromConfigCreator<*>) {
//            Noto.recordError("DataType registered with windowing system that is not creatable from config",
//                mapOf("dataType" to dt))
        } else {
            configLoadableDataTypes.add(dt)
        }

        val bindableProperties = dt.defaultInstance::class.memberProperties.filter {
                p -> p.getter.returnType.isSubtypeOf(Bindable::class.starProjectedType)
        }
        val hasBindables = bindableProperties.isNotEmpty()
        val copyFn = Widget.copyFn(dt.defaultInstance)

        @Suppress("KotlinConstantConditions")
        if (hasBindables && (copyFn == null || copyFn.parameters.size != 1)) {
            Noto.err("Data type has bindables but no copy function: $dt")
        } else if (hasBindables && copyFn != null) {
            val baseline = copyFn.call(dt.defaultInstance)
            bindableProperties.forEach { p ->
                (p as? KMutableProperty<*>)?.setter?.call(baseline, CheckerBindable())
            }
            val copy = copyFn.call(baseline)
            bindableProperties.forEach { p ->
                if (p.getter.call(copy) === p.getter.call(baseline)) {
                    Noto.err("Data type has bindables not explicitly copied: $dt.${p.name}")
                }
            }
        }

        world.register(dt)
    }

    open fun registerStandardComponents() {
        registerComponent(BackgroundComponent)
        registerComponent(ListWidgetComponent)
        registerComponent(TextWindowingComponent)
        registerComponent(TextInputWindowingComponent)
        registerComponent(ImageDisplayWindowingComponent)
        registerComponent(FocusWindowingComponent)
        registerComponent(FileInputWindowingComponent)
        registerComponent(DropdownWindowingComponent)
        registerComponent(PropertyEditorWindowingComponent)
        registerComponent(ButtonComponent)
        WindowingSystemPreregistration.preregisteredComponents.forEach { registerComponent(it) }
        WindowingSystemPreregistration.windowingSystems += this
    }

    fun createWidget(): Widget {
        return createWidget(desktop)
    }

    fun createWidget(parent: Widget): Widget {
        return createWidget(parent) {}
    }

    fun createWidget(parent: Widget, initFn: (Widget) -> Unit): Widget {
        val arch = archetypes.getOrPut("") {
            loadArchetype(ConfigFactory.empty().root(), "")
        }
        val w = createWidget(parent, arch)
        initFn(w)
        components.forEach { it.initializeWidget(w) }
        return w
//        val w = Widget(this, parent)
//        widgets[w.entity.id] = w
//        markForFullUpdate(w)
//        initFn(w)
//        components.forEach { it.initializeWidget(w) }
//        return w
    }

    fun destroyWidget(w: Widget) {
        w.destroyed = true

        val toDestroy = w.children.toList()
        for (c in toDestroy) {
            destroyWidget(c)
        }

        widgets.remove(w.entity.id)
        if (focusedWidget == w) {
            w.giveUpFocus()
        }
        w.parent?.apply {
            removeChild(w)
            markForFullUpdate()
        }
        // there seems to be some sort of un-cleared data that's dependent on the entity id
        // of widgets, so we can't safely reuse destroyed ones
        world.destroyEntity(w.entity, allowReuse = false)

        if (lastWidgetUnderMouse == w) {
            lastWidgetUnderMouse = desktop
        }

        pendingUpdates.remove(w)
    }

    fun loadArchetype(cv: ConfigValue, identifier: String, extraConfigForDataTypes: ConfigValue? = null) : WidgetArchetype {
        val stylesheets = listOfNotNull(
            if (Resources.projectName != null && Resources.projectName != "arx") { Resources.config("display/widgets/Stylesheet.sml") } else { null },
            defaultStylesheet(),
        )

        val widgetHolder = Widget(this, null)


        var effCV = cv
        cv["type"].asStr()?.let { typeStr ->
            for (comp in components) {
                comp.configArchetypesByType[typeStr.lowercase()]?.let { basePath ->
                    effCV = effCV.withFallback(confForWidgetPath(basePath))
                }
            }
        }



        for (stylesheet in stylesheets) {
            effCV = effCV.withFallback(stylesheet.root())
//            widgetHolder.readFromConfig(stylesheet.root())
            cv["type"].asStr()?.let { typeStr ->
//                widgetHolder.readFromConfig(stylesheet[typeStr])
                stylesheet[typeStr]?.let { effCV = effCV.withFallback(it) }
                if (typeStr.isNotEmpty() && typeStr.first().isLowerCase()) {
//                    widgetHolder.readFromConfig(stylesheet[typeStr.replaceFirstChar { it.uppercase() }])
                    stylesheet[typeStr.replaceFirstChar { it.uppercase() }]?.let { effCV = effCV.withFallback(it) }
                }
            }
        }
        val dataTypeEffCv = if (extraConfigForDataTypes != null) {
            effCV.withFallback(extraConfigForDataTypes)
        } else {
            effCV
        }

        widgetHolder.readFromConfig(dataTypeEffCv)
        val data = configLoadableDataTypes.mapNotNull { dt ->
            (dt as FromConfigCreator<*>).createFromConfig(dataTypeEffCv) as EntityData?
        }

        var arch = WidgetArchetype(data, widgetHolder, emptyMap(), identifier)
        val propagatedConfigs = components.fold(emptySet<String>()) { acc, v -> acc + v.propagateConfigToChildren(arch) }
        val childExtraConfigForDataType = if (propagatedConfigs.isNotEmpty()) {
            if (propagatedConfigs.contains("all")) {
                cv
            } else {
                cv.filterKeys { propagatedConfigs.contains(it) }
            }
        } else {
            extraConfigForDataTypes
        }

        val children = effCV["children"].map { k, v -> k to loadArchetype(v, k, childExtraConfigForDataType) }

        arch = arch.copy(children = children)
        componentsByConfigurationOrder.forEach { c -> c.configure(this, arch, effCV) }
        return arch
    }

    fun createWidget(archetype: String, baseArch: String? = null): Widget {
        return createWidget(desktop, archetype, baseArch)
    }

    fun widgetWithIdentifier(identifier: String) : Widget {
        return desktop.descendantWithIdentifier(identifier)!!
    }

    fun confForWidgetPath(path: String): Config? {
        val baseArchPath = path.substringBeforeLast('.')
        val baseArchName = path.substringAfterLast('.')
        val fullConf = Resources.config("display/widgets/$baseArchPath.sml")
        return fullConf.getConfig(baseArchName)
    }

    fun loadArchetype(archetype: String, baseArchetype: String? = null) : WidgetArchetype {
        return archetypes.getOrPut(archetype) {
            val path = archetype.substringBeforeLast('.')
            val widgetName = archetype.substringAfterLast('.')
            val baseArchConf = baseArchetype?.let { confForWidgetPath(it) }

            val conf = Resources.config("display/widgets/$path.sml")
            val rawCV = conf[widgetName]
            if (rawCV != null) {
                val cv = baseArchConf.ifLet { rawCV.withFallback(it) }.orElse { rawCV }
                loadArchetype(cv, widgetName)
            } else {
                Noto.recordError("Invalid widget archetype (no config)", mapOf("archetype" to archetype))
                WidgetArchetype(emptyList(), Widget(this, null), mapOf(), "unknown")
            }
        }
    }

    fun createWidget(parent : Widget, archetype: String, baseArchetype: String? = null): Widget {
        val arch = loadArchetype(archetype, baseArchetype)
        return createWidget(parent, arch)
    }

    fun createWidget(parent : Widget, arch: WidgetArchetype): Widget {
        val w = Widget(this, parent)
        widgets[w.entity.id] = w
        markForFullUpdate(w)

        if (arch.identifier.isNotEmpty()) {
            w.identifier = arch.identifier
        }

        arch.copyData().forEach { d -> w.attachData(d) }
        w.copyFrom(arch.widgetData)

        for ((childIdent, childArch) in arch.children) {
            val newChild = createWidget(w, childArch)
            newChild.identifier = childIdent
        }

        components.forEach { it.initializeWidget(w) }
        w.initialized = true

        return w
    }

    fun <T : EntityData> widgetsThatHaveData(dt: DataType<T>) : Iterator<Widget> {
        return iterator {
            for (ent in world.entitiesThatHaveData(dt)) {
                val w = widgets[ent.id]
                if (w != null) {
                    yield(w)
                } else {
                    Noto.err("World contained entity with no corresponding widget in windowing system")
                }
            }
        }
    }

    fun <T : EntityData> widgetsWithData(dt: DataType<T>) : Iterator<Pair<Widget, T>> {
        return iterator {
            for (ent in world.entitiesThatHaveData(dt)) {
                val w = widgets[ent.id]
                if (w != null) {
                    yield(w to w[dt]!!)
                } else {
                    Noto.err("World contained entity with no corresponding widget in windowing system")
                }
            }
        }
    }

    fun markForUpdate(w: Widget, r: RecalculationFlag) {
        pendingUpdates.getOrPut(w) { EnumSet.noneOf(RecalculationFlag::class.java) }.add(r)
    }

    fun markForFullUpdate(w: Widget) {
        pendingUpdates.getOrPut(w) { EnumSet.allOf(RecalculationFlag::class.java) }.addAll(RecalculationFlag.values())
    }

    internal open fun performRender(comp: WindowingComponent, w : Widget, bounds: Recti) {
        comp.render(this, w, bounds, w.quads)
    }

    fun updateDrawData(w: Widget, bounds: Recti, needsRerender: Set<Widget>) {
        if (w.showing()) {
            w.bounds = if (w.ignoreBounds) { desktop.bounds } else { bounds }
            if (needsRerender.contains(w)) {
                w.quads.clear()
                w.asciiDrawCommands.clear()
                for (comp in componentsByDrawOrder) {
                    performRender(comp, w, bounds)
                }
            }

            val cx = w.clientOffset(Axis.X)
            val cy = w.clientOffset(Axis.Y)
            val newBounds = w.bounds.intersect(Recti(w.resX + cx, w.resY + cy, w.resWidth - cx * 2, w.resHeight - cy * 2))
            w.sortChildren()
            for (c in w.children) {
                updateDrawData(c, newBounds, needsRerender)
            }
        } else{
            w.quads.clear()
            w.asciiDrawCommands.clear()
        }
    }

    fun recursivelyUpdateBindings(w : Widget, ctx: BindingContext, finishedSet: ObjectOpenHashSet<Widget>) {
        if (finishedSet.add(w)) {
            w.updateBindings(ctx)
            for (comp in componentsByUpdateBindingsOrder) {
                comp.updateBindings(this, w, ctx)
            }

            if (w.showing() || ctx.forceUpdate) {
                for (child in w.children) {
                    recursivelyUpdateBindings(child, BindingContext(child.bindings, ctx, ctx.dirtyBindings, ctx.forceUpdate), finishedSet)
                }
            }
        }
    }

    fun update() {
        components.forEach { c -> c.update(this) }
    }

    fun updateGeometry(): Boolean {
        return updateGeometry(Application.frameBufferSize)
    }


    val geometryRecursionGuard = AtomicBoolean(false)
    fun updateGeometry(size: Vec2i): Boolean {
        return if (geometryRecursionGuard.compareAndSet(false, true)) {
            updateDesktop(size)

            val finishedBindingUpdates = ObjectOpenHashSet<Widget>()
            val bindingsToUpdate = mutableListOf<Widget>()
            for ((w, update) in pendingUpdates) {
                if (update.contains(Bindings)) {
//                    Noto.info("Bindings updates have come into being again somehow")
                    bindingsToUpdate.add(w)
                }
            }
            for (w in bindingsToUpdate) {
                recursivelyUpdateBindings(w, w.buildBindingContext(null, false), finishedBindingUpdates)
            }

            updateDependentRelationships()

            val requireRerender = ObjectOpenHashSet<Widget>()
            val requiredUpdates = recursivelyCollectRequiredDependencies(requireRerender)
            val completedUpdates = DependencySet(requiredUpdates.size)

            requiredUpdates.forEach { update -> processGeometryUpdate(update, requiredUpdates, completedUpdates, requireRerender) }

            // we do this swap because otherwise anything executed in the calls
            // to geometryUpdated(...) wouldn't be able to mark widgets for update
            // then we swap back to avoid unnecessary allocation stuff
            val swap = pendingUpdates
            pendingUpdates = pendingUpdatesTmp
            for (c in components) {
                c.geometryUpdated(this, completedUpdates, swap)
            }
            swap.clear()
            swap.putAll(pendingUpdatesTmp)
            pendingUpdates = swap
            pendingUpdatesTmp.clear()

            val ret = if (requireRerender.isNotEmpty()) {
                updateWidgetUnderMouse()
                updateDrawData(desktop, Recti(0, 0, desktop.resolvedDimensions.x, desktop.resolvedDimensions.y), requireRerender)
                needsRerender = true
                true
            } else {
                false
            }

            geometryRecursionGuard.set(false)
            ret
        } else {
            false
        }
    }

    fun updateWidgetUnderMouse() {
        val w = widgetUnderMouse(screenToW(lastMousePosition))
        if (lastWidgetUnderMouse != w) {
            val syntheticMouseMove = MouseMoveEvent(lastMousePosition, Vec2f(0.0f,0.0f), KeyModifiers(0))
            if (! lastWidgetUnderMouse.destroyed) {
                handleEvent(lastWidgetUnderMouse, WidgetMouseExitEvent(syntheticMouseMove).withWidget(lastWidgetUnderMouse))
            }
            handleEvent(w, WidgetMouseEnterEvent(syntheticMouseMove).withWidget(w))
            lastWidgetUnderMouse = w
        }
    }


    open fun desiredDesktopSize(framebufferSize : Vec2i) : Vec2i {
        return (framebufferSize) / scale
    }

    fun updateDesktop(size: Vec2i) {
        val desiredSize = desiredDesktopSize(size)
        if (desktop.resolvedDimensions != desiredSize) {
            desktop.resolvedDimensions = desiredSize
            markForUpdate(desktop, DimensionsX)
            markForUpdate(desktop, DimensionsY)
        }
    }


    fun Dependency.widget(): Widget? {
        return widgets[widgetId]
    }

    fun processGeometryUpdate(d: Dependency, requiredUpdates: DependencySet, completedUpdates: DependencySet, requireRerender: ObjectOpenHashSet<Widget>) {
        d.widget()?.let { w ->
            when (d.kind) {
                DependencyKind.PartialPosition -> updatePartialPosition(w, d.axis, requiredUpdates, completedUpdates, requireRerender)
                DependencyKind.Position -> updatePosition(w, d.axis, requiredUpdates, completedUpdates, requireRerender)
                DependencyKind.Dimensions -> updateDimensions(w, d.axis, requiredUpdates, completedUpdates, requireRerender, false)
            }
        }
    }


    fun updateClientOffset(w: Widget, axis: Axis) {
        axis.to2D()?.let { ax2d ->
            var newNearValue = 0
            var newFarValue = 0
            for (comp in components) {
                newNearValue += comp.clientOffsetContributionNear(w, ax2d) ?: 0
                newFarValue += comp.clientOffsetContributionFar(w, ax2d) ?: 0
            }

            w.resolvedClientOffsetNear[axis] = newNearValue
            w.resolvedClientOffsetFar[axis] = newFarValue
        }
    }

    /**
     * Partial position is effectively: the position we can estimate without requiring information about
     * our parent or complex layout considerations. Necessary in order to make things like WrapContent
     * function along with right aligned widgets. Can be best effort, should try to avoid requiring
     * resolving other dependencies, where necessary only partial position should be used if possible
     */
    fun updatePartialPosition(w: Widget, axis: Axis, requiredUpdates: DependencySet, completedUpdates: DependencySet, requireRerender: ObjectOpenHashSet<Widget>) {
        // only perform the update if we haven't already (completedUpdates) and an update is actually required (requiredUpdates)
        if (completedUpdates.add(w, DependencyKind.PartialPosition, axis) && requiredUpdates.contains(w, DependencyKind.PartialPosition, axis)) {
            // only the top level Desktop widget should have no parent, it is handled differently, so only
            // process for parent'd widgets
            w.parent?.let { _ ->
                val pos = w.position(axis)
                w.resolvedPartialPosition[axis] = when (pos) {
                    is WidgetPosition.Fixed -> if (isFarSide(axis, pos.relativeTo)) 0 else pos.offset
                    is WidgetPosition.Proportional -> 0
                    is WidgetPosition.Centered -> 0
                    is WidgetPosition.Relative -> {
                        val relativeWidget = pos.relativeTo.toWidget(w)
                        if (relativeWidget != null) {
                            updatePartialPosition(relativeWidget, axis, requiredUpdates, completedUpdates, requireRerender)

                            // This is a little odd, but the usual use case is that you want to flow a layout left to right
                            // or top to bottom, and in that case if what you're basing off of isn't showing you just want
                            // to take its place, not offset relative to where it would have been, so set offset ot 0 in that case
                            val offset = tern(relativeWidget.showing(), pos.offset, 0)

                            val relativeAnchor = if (isFarSide(axis, pos.targetAnchor)) {
                                updateDimensions(relativeWidget, axis, requiredUpdates, completedUpdates, requireRerender)
                                relativeWidget.resolvedPartialPosition[axis] + relativeWidget.resolvedDimensions[axis] + offset
                            } else {
                                relativeWidget.resolvedPartialPosition[axis] - offset
                            }

                            if (isFarSide(axis, pos.selfAnchor)) {
                                updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                                relativeAnchor - w.resolvedDimensions[axis]
                            } else {
                                relativeAnchor
                            }
                        } else {
                            0
                        }
                    }
                    is WidgetPosition.Absolute -> pos.position
                    is WidgetPosition.Pixel -> screenToW(pos.pixelPosition.toFloat())[axis].toInt()
                }
            }
        }
    }

    fun updatePosition(w: Widget, axis: Axis, requiredUpdates: DependencySet, completedUpdates: DependencySet, requireRerender: ObjectOpenHashSet<Widget>) {
        // only perform the update if we haven't already (completedUpdates) and an update is actually required (requiredUpdates)
        if (completedUpdates.add(w, DependencyKind.Position, axis) && requiredUpdates.contains(w, DependencyKind.Position, axis)) {
            updateClientOffset(w, axis)
            w.parent?.let { parent ->
                val pos = w.position(axis)
                // everything other than absolute pixel positions and positions relative
                // to widgets other than the parent require the parent's position
                if (pos !is WidgetPosition.Absolute && pos !is WidgetPosition.Relative) {
                    updatePosition(parent, axis, requiredUpdates, completedUpdates, requireRerender)
                }
                val parentV = parent.resolvedPosition[axis] + parent.clientOffset(axis) + (if (axis.is2D()) { parent.localOffset[axis] } else { 0 })

                val newPos = when (pos) {
                    is WidgetPosition.Fixed -> {
                        if (isFarSide(axis, pos.relativeTo)) {
                            updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                            updateDimensions(parent, axis, requiredUpdates, completedUpdates, requireRerender)
                            val parentD = if (axis.is2D()) parent.resClientDim(axis) else 1000
                            parentV + parentD - pos.offset - w.resolvedDimensions[axis]
                        } else {
                            parentV + pos.offset
                        }
                    }
                    is WidgetPosition.Proportional -> {
//                        updatePosition(parent, axis, requiredUpdates, completedUpdates)
                        if (isFarSide(axis, pos.relativeTo) || pos.anchorToCenter) {
                            updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                        }
                        updateDimensions(parent, axis, requiredUpdates, completedUpdates, requireRerender)
                        val parentD = if (axis.is2D()) parent.resClientDim(axis) else 1000
                        val primaryPoint = if (isFarSide(axis, pos.relativeTo)) {
                            parentV + parentD - (parentD.toFloat() * pos.proportion).toInt() - w.resolvedDimensions[axis]
                        } else {
                            parentV + (parentD.toFloat() * pos.proportion).toInt()
                        }

                        if (pos.anchorToCenter) {
                            if (isFarSide(axis, pos.relativeTo)) {
                                primaryPoint - w.resolvedDimensions[axis]
                            } else {
                                primaryPoint + w.resolvedDimensions[axis]
                            }
                        } else {
                            primaryPoint
                        }
                    }
                    is WidgetPosition.Centered -> {
                        updatePosition(parent, axis, requiredUpdates, completedUpdates, requireRerender)
                        updateDimensions(parent, axis, requiredUpdates, completedUpdates, requireRerender)
                        updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                        val parentD = if (axis.is2D()) parent.resClientDim(axis) else 1000
                        parentV + (parentD - w.resolvedDimensions[axis]) / 2
                    }
                    is WidgetPosition.Relative -> {
                        val relativeWidget = pos.relativeTo.toWidget(w)
                        if (relativeWidget != null) {
                            updatePosition(relativeWidget, axis, requiredUpdates, completedUpdates, requireRerender)

                            // This is a little odd, but the usual use case is that you want to flow a layout left to right
                            // or top to bottom, and in that case if what you're basing off of isn't showing you just want
                            // to take its place, not offset relative to where it would have been, so set offset ot 0 in that case
                            val offset = tern(relativeWidget.showing(), pos.offset, 0)

                            val relativeAnchor = if (isFarSide(axis, pos.targetAnchor)) {
                                updateDimensions(relativeWidget, axis, requiredUpdates, completedUpdates, requireRerender)
                                relativeWidget.resolvedPosition[axis] + relativeWidget.resolvedDimensions[axis] + offset
                            } else {
                                relativeWidget.resolvedPosition[axis] - offset
                            }

                            if (isFarSide(axis, pos.selfAnchor)) {
                                updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                                relativeAnchor - w.resolvedDimensions[axis]
                            } else {
                                relativeAnchor
                            }
                        } else {
                            0
                        }
                    }
                    is WidgetPosition.Absolute -> {
                        if (pos.anchorTo == WidgetOrientation.Center) {
                            updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                            pos.position - w.resolvedDimensions[axis] / 2
                        } else if (isFarSide(axis, pos.anchorTo)) {
                            updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                            pos.position - w.resolvedDimensions[axis]
                        } else {
                            pos.position
                        }
                    }
                    is WidgetPosition.Pixel -> {
                        val raw = screenToW(pos.pixelPosition.toFloat())[axis].toInt()
                        if (pos.anchorTo == WidgetOrientation.Center) {
                            updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                            raw - w.resolvedDimensions[axis] / 2
                        } else if (isFarSide(axis, pos.anchorTo)) {
                            updateDimensions(w, axis, requiredUpdates, completedUpdates, requireRerender)
                            raw - w.resolvedDimensions[axis]
                        } else {
                            raw
                        }
                    }
                }
                if (newPos != w.resolvedPosition[axis]) {
                    w.resolvedPosition[axis] = newPos
                    requireRerender.add(w)
                }
            }
        }
    }

    fun updateDimensions(w: Widget, axis: Axis, requiredUpdates: DependencySet, completedUpdates: DependencySet, requireRerender: ObjectOpenHashSet<Widget>, ignoreIfDependsOnParent: Boolean = false) {
        val dim = w.dimensions(axis)
        if (ignoreIfDependsOnParent && dim.dependsOnParent()) {
            return
        }

        // only perform the update if we haven't already (completedUpdates) and an update is actually required (requiredUpdates)
        if (completedUpdates.add(w, DependencyKind.Dimensions, axis) && requiredUpdates.contains(w, DependencyKind.Dimensions, axis)) {
            w.parent?.let { parent ->
                if (! w.showing()) {
                    if (w.resolvedDimensions[axis] != 0) {
                        w.resolvedDimensions[axis] = 0
                        requireRerender.add(w)
                    }
                    return
                }

                updateClientOffset(w, Axis.X)
                updateClientOffset(w, Axis.Y)

                if (dim is WidgetDimensions.Relative ||
                    dim is WidgetDimensions.Proportional ||
                    dim is WidgetDimensions.ExpandToParent ||
                    (dim is WidgetDimensions.WrapContentOrFill && parent.dimensions(axis) !is WidgetDimensions.WrapContent)
                ) {
                    updateDimensions(parent, axis, requiredUpdates, completedUpdates, requireRerender)
                }
                val parentD = parent.resClientDim(axis)

                val newDim = when (dim) {
                    is WidgetDimensions.Fixed -> dim.size
                    is WidgetDimensions.Relative -> parentD - dim.delta
                    is WidgetDimensions.Proportional -> (parentD.toFloat() * dim.proportion).toInt()
                    is WidgetDimensions.ExpandToParent -> {
                        updatePosition(parent, axis, requiredUpdates, completedUpdates, requireRerender)
                        updatePosition(w, axis, requiredUpdates, completedUpdates, requireRerender)
                        val relPos = w.resolvedPosition[axis] - (parent.resolvedPosition[axis] + parent.clientOffset(axis) + (if (axis.is2D()) { parent.localOffset[axis] } else { 0 }))
                        parentD - dim.gap - relPos
                    }
                    is WidgetDimensions.ExpandTo -> {
                        val expandTo = dim.expandTo.toWidget(w)
                        if (expandTo != null) {
                            updatePosition(w, axis, requiredUpdates, completedUpdates, requireRerender)
                            updatePosition(expandTo, axis, requiredUpdates, completedUpdates, requireRerender)
                            expandTo.resolvedPosition[axis] - w.resolvedPosition[axis] - dim.gap
                        } else {
                            0
                        }
                    }
                    is WidgetDimensions.WrapContent, WidgetDimensions.WrapContentOrFill -> {
                        if (parent.dimensions(axis) !is WidgetDimensions.WrapContent && dim == WidgetDimensions.WrapContentOrFill) {
                            parentD
                        } else {
                            var min = 10000000
                            var max = 0
                            for (c in w.children) {
                                if (!c.ignoreBounds) {
                                    updatePartialPosition(c, axis, requiredUpdates, completedUpdates, requireRerender)
                                    updateDimensions(c, axis, requiredUpdates, completedUpdates, requireRerender, ignoreIfDependsOnParent = true)
                                    min = min(c.resolvedPartialPosition[axis], min)
                                    max = max(c.resolvedPartialPosition[axis] + c.resolvedDimensions[axis], max)
                                }
                            }

                            var raw = max(max - min, 0)
                            if (dim is WidgetDimensions.WrapContent) {
                                raw = raw.clamp(dim.min ?: -10000000, dim.max ?: 10000000)
                            }

                            raw + w.clientOffset(axis) + w.clientOffsetFar(axis)
                        }
                    }
                    is WidgetDimensions.Intrinsic -> {
                        val minimums = Vec2i(0, 0)
                        val maximums = Vec2i(1000000000, 1000000000)
                        if (w.dimensions(oppositeAxis(axis)) !is WidgetDimensions.Intrinsic) {
                            updateDimensions(w, oppositeAxis(axis), requiredUpdates, completedUpdates, requireRerender)
                        }
                        val xdim = w.dimensions(Axis.X)
                        val ydim = w.dimensions(Axis.Y)
                        if (xdim is WidgetDimensions.Intrinsic) {
                            minimums.x = xdim.min ?: 0
                            maximums.x = xdim.max ?: 1000000000
                        } else {
//                            maximums.x = w.resolvedDimensions[Axis.X]
                            maximums.x = w.resClientWidth
                        }
                        if (ydim is WidgetDimensions.Intrinsic) {
                            minimums.y = ydim.min ?: 0
                            maximums.y = ydim.max ?: 1000000000
                        } else {
//                            maximums.y = w.resolvedDimensions[Axis.Y]
                            maximums.y = w.resClientHeight
                        }

                        var result = 0
                        axis.to2D()?.let { ax2d ->
                            for (component in components) {
                                val isize = component.intrinsicSize(w, ax2d, minimums, maximums)
                                if (isize != null) {
                                    result = max(result, isize + w.clientOffset(axis) + w.clientOffsetFar(axis))
                                }
                            }
                            result.clamp(minimums[ax2d], maximums[ax2d])
                        } ?: Noto.errAndReturn("Axis was not 2d in intrinsic calculation", 0)
                    }
                }
                if (newDim != w.resolvedDimensions[axis]) {
                    w.resolvedDimensions[axis] = newDim
                    requireRerender.add(w)
                }
            }
            if (w.parent == null) {
                updateClientOffset(w, Axis.X)
                updateClientOffset(w, Axis.Y)
            }
        }
    }


    fun recursivelyCollectRequiredDependencies(requireRerender: ObjectOpenHashSet<Widget>): DependencySet {
        val ret = DependencySet()
        for ((w, flags) in pendingUpdates) {
            for (flag in flags) {
                if (flag == Contents) {
                    // content updates always necessitate re-rendering the widget itself
                    requireRerender.add(w)
                    // and require dimensional updates when the dimensions are based on the content itself
                    for (axis in Axis2D.values()) {
                        if (w.dimensions(axis) is WidgetDimensions.Intrinsic) {
                            recursivelyCollectRequiredDependencies(w, DependencyKind.Dimensions, axis.to3D(), ret)
                        }
                    }
                } else {
                    // convert the update flag into the appropriate dependency representation, then
                    // recursively collect dependencies based on it. Could we just always use the
                    // dependency representation rather than having the two different ones? Probably?
                    val (dw, dk, da) = when (flag) {
                        PositionX -> Triple(w, DependencyKind.Position, Axis.X)
                        PositionY -> Triple(w, DependencyKind.Position, Axis.Y)
                        PositionZ -> Triple(w, DependencyKind.Position, Axis.Z)
                        DimensionsX -> Triple(w, DependencyKind.Dimensions, Axis.X)
                        DimensionsY -> Triple(w, DependencyKind.Dimensions, Axis.Y)
                        else -> continue
                    }
                    recursivelyCollectRequiredDependencies(dw, dk, da, ret)
                    if (dk == DependencyKind.Position) {
                        recursivelyCollectRequiredDependencies(dw, DependencyKind.PartialPosition, da, ret)
                    }
                }
            }
        }
        return ret
    }

    fun recursivelyCollectRequiredDependencies(w: Widget, kind: DependencyKind, axis: Axis, resultSet: DependencySet) {
        if (resultSet.add(Dependency(w, kind, axis))) {
            w.forEachDependent { dependent ->
                if (dependent.sourceAxis == axis && dependent.sourceKind == kind) {
                    dependent.dependentWidget.get()?.let { dw ->
                        recursivelyCollectRequiredDependencies(dw, dependent.dependentKind, dependent.dependentAxis, resultSet)
                        if (dependent.dependentKind == DependencyKind.Position) {
                            recursivelyCollectRequiredDependencies(dw, DependencyKind.PartialPosition, dependent.dependentAxis, resultSet)
                        }
                    }
                }
            }
        }
    }

    fun updateDependentRelationships() {
        // Note: this makes dependency purely additive, a dependent relationship
        // cannot currently be removed
        for ((w, flags) in pendingUpdates) {
            if (flags.contains(DimensionsX)) {
                forEachDimensionDep(w, Axis.X) { depW, k, a -> depW.addDependent(Dependent(WeakReference(w), DependencyKind.Dimensions, Axis.X, k, a)) }
            }
            if (flags.contains(DimensionsY)) {
                forEachDimensionDep(w, Axis.Y) { depW, k, a -> depW.addDependent(Dependent(WeakReference(w), DependencyKind.Dimensions, Axis.Y, k, a)) }
            }
            if (flags.contains(PositionX)) {
                forEachPositionDep(w, Axis.X) { depW, k, a -> depW.addDependent(Dependent(WeakReference(w), DependencyKind.Position, Axis.X, k, a)) }
            }
            if (flags.contains(PositionY)) {
                forEachPositionDep(w, Axis.Y) { depW, k, a -> depW.addDependent(Dependent(WeakReference(w), DependencyKind.Position, Axis.Y, k, a)) }
            }
            if (flags.contains(PositionZ)) {
                forEachPositionDep(w, Axis.Z) { depW, k, a -> depW.addDependent(Dependent(WeakReference(w), DependencyKind.Position, Axis.Z, k, a)) }
            }
        }
    }

    fun widgetContainsPosition(w: Widget, position: Vec2f) : Boolean {
        return w.resX <= position.x && w.resY <= position.y && w.resX + w.resWidth >= position.x && w.resY + w.resHeight >= position.y
    }

    fun widgetUnderMouse(position: Vec2f) : Widget {
        // look at all the widgets that float, i.e. ignore bounds
        for (w in ignoreBoundsWidgets.sortedByDescending { it.resZ }) {
            if (widgetContainsPosition(w, position)) {
                return widgetUnderMouse(w, position)
            }
        }
        return widgetUnderMouse(desktop, position)
    }

    internal fun widgetUnderMouse(startingFrom: Widget, position: Vec2f) : Widget {
        var w = startingFrom
        while(true) {
            var newW : Widget? = null
//            for (c in w.children.sortedByDescending { it.resZ }) {
            for (c in w.children.reversed()) {
                if (c.showing() && widgetContainsPosition(c, position)) {
                    newW = c
                    break
                }
            }
            if (newW == null) {
                break
            } else {
                w = newW
            }
        }
        return w
    }

    private fun handleEvent(w: Widget, event: DisplayEvent) : Boolean {
        if (! w.initialized) {
            return false
        }

//        if (event is WidgetMouseReleaseEvent) {
//            println("WMRE")
//        }

        for (callback in w.eventCallbacks) {
            if (callback(event)) {
                event.consume()
                if (event is WidgetEvent) {
                    event.parentEvent?.consume()
                }
                return true
            }
        }

        for (comp in componentsByEventOrder) {
            if (comp.handleEvent(w, event)) {
                event.consume()
                return true
            }
        }

        if ((event as? WidgetEvent)?.propagate == PropagationDirection.Down) {
            for (c in w.children) {
                if (handleEvent(c, event)) {
                    return true
                }
            }
            return false
        } else {
            val p = w.parent
            return if (p != null) {
                if (event is WidgetEvent) {
                    event.widgets.add(p)
                }
                handleEvent(p, event)
            } else {
                false
            }
        }
    }

    fun projectionMatrix(): Mat4 {
        return ortho(desktop.resX.toFloat(), (desktop.resX + desktop.resWidth + 1).toFloat(), (desktop.resY + desktop.resHeight).toFloat(), desktop.resY.toFloat(), 0.0f, 100.0f)
    }

    open fun screenToW(p : Vec2f) : Vec2f {
        val screenSpace = Float4((p.x / Application.windowSize.x) * 2.0f - 1.0f, ((Application.windowSize.y - p.y - 1) / Application.windowSize.y) * 2.0f - 1.0f, 0.0f, 1.0f)
        val res = inverse(projectionMatrix()).times(screenSpace)
        return Vec2f(res.x, res.y)
    }


    var lastConsumedKeyPress : Pair<WidgetKeyPressEvent, Long>? = null
    private fun blockCharInput(ci: CharInputEvent) : Boolean {
        return lastConsumedKeyPress?.let { (e, t) ->
            (t - System.currentTimeMillis()).absoluteValue <= 2
        } ?: false
    }

    private fun mapEvent(event: DisplayEvent) : WidgetEvent? {
        return when (event) {
            is KeyReleaseEvent -> WidgetKeyReleaseEvent(event.key, event.mods, event)
            is KeyPressEvent -> WidgetKeyPressEvent(event.key, event.mods, event)
            is MousePressEvent -> WidgetMousePressEvent(screenToW(event.position), event.button, event.mods, event)
            is MouseReleaseEvent -> {
                WidgetMouseReleaseEvent(screenToW(event.position), event.button, event.mods, event)
            }
            is MouseMoveEvent -> WidgetMouseMoveEvent(screenToW(event.position), event.delta, event.mods, event)
            is MouseDragEvent -> WidgetMouseDragEvent(screenToW(event.position), event.delta, event.button, event.mods, event)
            is MouseScrollEvent -> WidgetMouseScrollEvent(screenToW(event.position), event.delta, event.mods, event)
            is CharInputEvent -> {
                if (! blockCharInput(event)) {
                    WidgetCharInputEvent(event.char, KeyModifiers.activeModifiers, event)
                } else {
                    null
                }
            }
            is WidgetEvent -> event
            else -> null
        }
    }

    /**
     * Process the given event through the various widgets in the windowing system. Returns true
     * if one of those widgets consumed the event, false otherwise (including if the event was
     * already consumed before this was called).
     */
    fun handleEvent(event : DisplayEvent) : Boolean {
        if (event.consumed) { return false }

        when (event) {
            is MouseMoveEvent -> {
                lastMousePosition = event.position
                val w = widgetUnderMouse(screenToW(event.position))
                if (lastWidgetUnderMouse != w) {
                    handleEvent(lastWidgetUnderMouse, WidgetMouseExitEvent(event).withWidget(lastWidgetUnderMouse))
                    handleEvent(w, WidgetMouseEnterEvent(event).withWidget(w))
                    lastWidgetUnderMouse = w
                }
            }
        }

        val target = when (event) {
            is MouseEvent -> lastWidgetUnderMouse
            is KeyEvent, is CharInputEvent -> focusedWidget ?: desktop
            is WidgetEvent -> event.widget
            else -> focusedWidget ?: desktop
        }

        val ret = if (event is WidgetEvent) {
            handleEvent(target, event)
        } else {
            mapEvent(event)?.let { effEvent ->
                if (effEvent.widgets.isEmpty()) {
                    effEvent.widgets.add(target)
                }
                fireEvent(effEvent)
                effEvent.consumed
            } ?: false
        }

        if ((ret || event.consumed) && event is WidgetKeyPressEvent) {
            lastConsumedKeyPress = event to System.currentTimeMillis()
        }

        return ret
    }

    fun fireEvent(event : DisplayEvent) {
        world.fireEvent(event)
    }
}


class AsciiWindowingSystem(val asciiCanvas : AsciiCanvas) : WindowingSystem() {
    init {
//        val tmpFont = Font("monaco", 0, 12)
//        font = ArxFont(tmpFont, 12, ArxTypeface(tmpFont))
        font = Resources.font("arx/fonts/Px437_SanyoMBC775-2y.ttf", 16)
    }

    companion object : DataType<AsciiWindowingSystem>({ AsciiWindowingSystem(AsciiCanvas(Vec2i(1,1), AsciiColorPalette16Bit)) }, sparse = true)
    override fun dataType(): DataType<*> {
        return AsciiWindowingSystem
    }

    init {
        scale = 2
    }

    override fun registerStandardComponents() {
        registerComponent(AsciiBackgroundComponent)
        registerComponent(AsciiTextWidgetComponent)
        registerComponent(TextInputWindowingComponent)
        registerComponent(AsciiImageWidgetComponent)
        registerComponent(ListWidgetComponent)
        registerComponent(FocusWindowingComponent)
        registerComponent(AsciiTextSelectorComponent)
        registerComponent(ButtonComponent)
        registerComponent(AsciiDropdownComponent)
        registerComponent(CustomWidgetComponent)
        registerComponent(AsciiDividerComponent)
        registerComponent(ScrollWindowingComponent)
        registerComponent(AsciiCanvasWidgetComponent)
        registerComponent(HSLSliderWidget)
        registerComponent(CharacterPickerWidget)
        registerComponent(FileInputWindowingComponent)
        registerComponent(AsciiAutoForm)
        WindowingSystemPreregistration.preregisteredComponents.forEach { registerComponent(it) }
        WindowingSystemPreregistration.windowingSystems += this
    }

    override fun desiredDesktopSize(framebufferSize: Vec2i): Vec2i {
        return asciiCanvas.dimensions
    }

    override fun performRender(comp: WindowingComponent, w: Widget, bounds: Recti) {
        comp.renderAscii(this, w, bounds, asciiCanvas, w.asciiDrawCommands)
    }

    override fun screenToW(p: Vec2f): Vec2f {
        val screenSpace = Float4((p.x / Application.windowSize.x), p.y / Application.windowSize.y, 0.0f, 1.0f)
        return Vec2f(screenSpace.x * asciiCanvas.dimensions.x, screenSpace.y * asciiCanvas.dimensions.y)
    }

    override fun defaultStylesheet() : Config {
        return Resources.config("/arx/display/widgets/AsciiStylesheet.sml")
    }
}
