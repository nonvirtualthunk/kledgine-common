package arx.display.components

import arx.core.Vec3f
import arx.display.core.Camera
import arx.display.core.Canvas3D
import arx.display.core.PixelCamera
import arx.engine.*


object MainCamera : CameraID<Camera> {
    override val startingCamera = PixelCamera().apply { origin = Vec3f(0.0f, 0.0f, 0.0f); scaleIncrement = 32.0f; speed = Vec3f(8.0f, 8.0f, 8.0f); scale = 5.0f }
}

class CanvasRef2D(val priority : Priority) {
    var fn : (Canvas3D) -> Unit =  {}
    var dirty: Boolean = false

    fun update(c : (Canvas3D) -> Unit) {
        dirty = true
        fn = c
    }
}

class MetaCanvas2D(internal var priority: Priority = Priority.Normal, camera: CameraID<*>, internal val init : (Canvas3D) -> Unit) {
    internal var drawFunctions: MutableList<CanvasRef2D> = mutableListOf()
    internal val canvas = Canvas3D(camera).apply { init(this) }

    fun createReference(priority: Priority = Priority.Normal) : CanvasRef2D {
        val ret = CanvasRef2D(priority)
        drawFunctions.add(ret)
        drawFunctions.sortByDescending { it.priority }
        return ret
    }
}

abstract class CanvasDefinition2D(val canvasDrawPriority: Priority = Priority.Normal, val camera : CameraID<*> = MainCamera) {
    open fun initialize(c: Canvas3D) {}
}

data class Canvases (
    internal var canvases: MutableMap<CanvasDefinition2D, MetaCanvas2D> = mutableMapOf()
) : DisplayData, CreateOnAccessData {
    companion object : DataType<Canvases>( Canvases(), sparse = true )
    override fun dataType() : DataType<*> { return Canvases }

    operator fun get(key: CanvasDefinition2D) : MetaCanvas2D {
        return canvases.getOrPut(key) {
            MetaCanvas2D(key.canvasDrawPriority, key.camera, key::initialize)
        }
    }
}


object CanvasComponent : DisplayComponent() {
    init {
        updatePriority = Priority.Last
    }

    override fun update(world: World): Boolean {
        var ret = false
        for (mc in world[Canvases].canvases.values) {
            if (mc.drawFunctions.any { it.dirty }) {
                ret = true
                mc.canvas.clear()

                for (mcr in mc.drawFunctions) {
                    mc.canvas.resetPreferences()
                    mcr.fn(mc.canvas)
                }
            }
        }

        return ret
    }

    override fun draw(world: World) {
        for (mc in world[Canvases].canvases.values.sortedByDescending { it.priority }) {
            mc.canvas.draw(world)
        }
    }
}