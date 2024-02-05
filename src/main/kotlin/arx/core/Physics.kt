package arx.core

import arx.engine.DataType
import arx.engine.GameData
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.collision.shapes.Shape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import kotlin.math.atan2

class Physics : org.jbox2d.dynamics.World(Vec2(0.0f, -9.8f)), GameData {
    companion object : DataType<Physics>( Physics(), sparse = false )
    override fun dataType() : DataType<*> { return Physics }
}


data class PhysicsObject (
    val body: Body
) : GameData {
    companion object : DataType<PhysicsObject>( PhysicsObject(Body(BodyDef(), null)), sparse = true )
    override fun dataType() : DataType<*> { return PhysicsObject }
}


fun createBoxShape(from: Vec2f, to: Vec2f, width: Float) : Shape {
    val poly = PolygonShape()
    val distance = distance(from.x, from.y, to.x, to.y)
    val midpoint = (from + to) * 0.5f
    val delta = (to - from)
    delta.normalizeSafe()
    val angle = if (delta.x != 0.0f || delta.y != 0.0f) {
        atan2(delta.y, delta.x)
    } else {
        0.0f
    }
    poly.setAsBox(distance * 0.5f, width * 0.5f, midpoint.toBox2d(), angle)
    return poly
}