package arx.engine


abstract class EngineComponent(val initializePriority : Priority = Priority.Normal) {
    var engine : Engine? = null
    var eventPriority : Priority = Priority.Normal

    fun fireEvent(event : Event) {
        engine!!.handleEvent(event)
    }

    open fun World.isActive() : Boolean {
        return true
    }
}


enum class Priority {
    Last,
    VeryLow,
    Low,
    Normal,
    High,
    VeryHigh,
    First
}

abstract class DisplayComponent(initializePriority : Priority = Priority.Normal) : EngineComponent(initializePriority) {

    var drawPriority : Priority = Priority.Normal
    var updatePriority : Priority = Priority.Normal

    open fun World.initializeWithWorld() {}

    open fun initialize(world: World) {}

    open fun update(world: World) : Boolean { return false }

    open fun World.updateWithWorld() : Boolean { return false }

    open fun draw(world: World) {}

    open fun World.drawWithWorld() {}

    open fun handleEvent(world: World, event: Event): Boolean {
        return false
    }

    open fun World.handleEventWithWorld(event: Event): Boolean {
        return false
    }
}


class ModificationContext(val modificationsByDataType: Array<MutableSet<EntityId>>, var keyFrameSet : Boolean, var keyFrameDuration : Float) {
    fun <T>modified(dt: DataType<T>, e : Entity) {
        modificationsByDataType[dt.index].add(e.id)
    }

    /**
     * Indicate that the point we are currently at is a key frame that should be animated between,
     * also indicates the overall duration that that transition ought to take
     */
    fun makeKeyFrame(duration: Float) {
        keyFrameSet = true
        keyFrameDuration = duration
    }
}

interface AnimatingDisplayComponent {
    fun markAffected(world: World, event: GameEvent, ctx: ModificationContext)

    fun updateData(entity: Entity, dataType: DataType<*>)
}

abstract class GameComponent(initializePriority : Priority = Priority.Normal) : EngineComponent(initializePriority) {
    abstract fun initialize(world: GameWorld)

    abstract fun update(world: GameWorld)

    abstract fun handleEvent(world: GameWorld, event: GameEvent)
}




class Engine(
    val gameComponents : List<GameComponent> = emptyList(),
    val displayComponents : List<DisplayComponent> = emptyList(),
) {


    var gameComponentsByEventPriority = gameComponents.sortedByDescending { it.eventPriority }
    var displayComponentsByEventPriority = displayComponents.sortedByDescending { it.eventPriority }
    var displayComponentsByDrawPriority = displayComponents.sortedByDescending { it.drawPriority }
    var displayComponentsByUpdatePriority = displayComponents.sortedByDescending { it.updatePriority }

    var world = World().apply {
        eventCallbacks = eventCallbacks + { e -> handleEvent(e) }
    }

    val components : List<EngineComponent> get() { return gameComponents + displayComponents }

    init {
        components.forEach { it.engine = this }
    }

    fun initialize() {
        for (gc in gameComponents.sortedByDescending { it.initializePriority }) {
            gc.initialize(world)
        }
        for (dc in displayComponents.sortedByDescending { it.initializePriority }) {
            dc.initialize(world)
            with (dc) {
                world.initializeWithWorld()
            }
        }

        gameComponentsByEventPriority = gameComponents.sortedByDescending { it.eventPriority }
        displayComponentsByEventPriority = displayComponents.sortedByDescending { it.eventPriority }
        displayComponentsByDrawPriority = displayComponents.sortedByDescending { it.drawPriority }
        displayComponentsByUpdatePriority = displayComponents.sortedByDescending { it.updatePriority }
    }

    fun updateGameState() {
        for (gc in gameComponents) {
            if (with(gc) { world.isActive() }) {
                gc.update(world)
            }
        }
    }

    fun updateDisplayState() : Boolean {
        var anyNeedsRedraw = false
        for (dc in displayComponentsByUpdatePriority) {
            if (with(dc) { world.isActive() }) {
                if (dc.update(world) || with(dc) { world.updateWithWorld() }) {
                    anyNeedsRedraw = true
                }
            }
        }
        return anyNeedsRedraw
    }

    fun draw() {
        for (dc in displayComponentsByDrawPriority) {
            if (with(dc) { world.isActive() }) {
                dc.draw(world)
                with(dc) { world.drawWithWorld() }
            }
        }
    }

    fun handleEvent(event: Event) {
        if (event is GameEvent) {
            for (gc in gameComponentsByEventPriority) {
                if (with(gc) { world.isActive() }) {
                    if (!event.consumed) {
                        gc.handleEvent(world, event)
                    }
                }
            }
        }

        for (dc in displayComponentsByEventPriority) {
            if (!event.consumed) {
                if (with(dc) { world.isActive() }) {
                    if (dc.handleEvent(world, event)) {
                        event.consume()
                    }
                    with(dc) {
                        if (world.handleEventWithWorld(event)) {
                            event.consume()
                        }
                    }
                }
            }
        }
    }
}