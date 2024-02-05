package arx.display.components

import arx.display.core.Camera
import arx.engine.*


interface CameraID<T : Camera> {
    val startingCamera : T
}

data class Cameras (
    val cameras : MutableMap<CameraID<*>, Camera> = mutableMapOf()
) : DisplayData, CreateOnAccessData {
    companion object : DataType<Cameras>( Cameras(), sparse = true )
    override fun dataType() : DataType<*> { return Cameras }
}

operator fun <T : Camera> Cameras?.get (c: CameraID<T>) : T {
    @Suppress("UNCHECKED_CAST")
    return (this?.cameras?.getOrPut(c) { c.startingCamera } ?: c.startingCamera) as T
}

operator fun <T : Camera> Cameras.set (c: CameraID<T>, cam : T) {
    cameras[c] = cam
}

object CameraComponent : DisplayComponent() {
    init {
        eventPriority = Priority.Last
    }

    override fun handleEvent(world: World, event: Event): Boolean {
        world.global(Cameras).apply {
            if (event is DisplayEvent) {
                for (camera in cameras.values) {
                    if (camera.handleEvent(event)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun update(world: World): Boolean {
        var ret = false
        world.global(Cameras).apply {
            for (camera in cameras.values) {
                if (camera.update()) {
                    ret = true
                }
            }
        }
        return ret
    }
}