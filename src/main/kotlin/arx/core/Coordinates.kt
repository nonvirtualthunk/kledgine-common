package arx.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.ShortArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.roundToInt


//class Iso(x : Float = 0.0f, y : Float = 0.0f, z : Float = 0.0f) : Vec3f(x,y,z) {
//    companion object {
//        fun toScreenSpace(x : Float, y : Float, z : Float) {
//
//        }
//    }
//}
//
//class Isoi(x : Int = 0, y : Int = 0, z : Int = 0) : Vec3i(x,y,z)


object IsoCoord2DSerializer : KSerializer<IsoCoord2D> {
    override fun deserialize(decoder: Decoder): IsoCoord2D {
        val ba = decoder.decodeSerializableValue(serializer)
        return IsoCoord2D(ba[0], ba[1])
    }

    val serializer = IntArraySerializer()
    override val descriptor: SerialDescriptor = SerialDescriptor("IsoCoord2D",serializer.descriptor)

    override fun serialize(encoder: Encoder, value: IsoCoord2D) {
        val sa = IntArray(2)
        sa[0] = value.x
        sa[1] = value.y

        encoder.encodeSerializableValue(serializer, sa)
    }
}

@Serializable(with = IsoCoord2DSerializer::class)
class IsoCoord2D(x : Int, y : Int) : Vec2i(x,y) {
    companion object {
        init {
            registerCustomTypeRender<IsoCoord2D>()
        }
        fun project(x : Int, y : Int) : Vec2f {
            return Vec2f(x * 0.5f - (y * 0.5f), x * 0.25f + y * 0.25f)
        }

        fun project(x : Int, y : Int, v : Vec2f) {
            v.x = x * 0.5f - (y * 0.5f)
            v.y = x * 0.25f + y * 0.25f
        }

        fun unproject(v : Vec2f) : IsoCoord2D {
            return IsoCoord2D(kotlin.math.round(v.x + v.y * 2.0f).toInt(), kotlin.math.round(v.y * 2.0f - v.x).toInt())
        }
    }

    fun project() : Vec2f {
        return IsoCoord2D.project(x,y)
    }

    fun distanceTo(to: IsoCoord2D): Float {
        val d2 = (to.toFloat() - this.toFloat()).magnitude2()
        return if (d2 != 0.0f) {
            kotlin.math.sqrt(d2)
        } else {
            return 0.0f
        }
    }

    override fun toString(): String {
        return "IsoCoord2D($x, $y)"
    }
}

@Serializable
class IsoCoord() : Vec3i(0,0,0) {
    constructor(x : Int, y : Int, z : Int): this() {
        this.x = x
        this.y = y
        this.z = z
    }
    constructor (c2d: IsoCoord2D, z: Int): this() {
        this.x = c2d.x
        this.y = c2d.y
        this.z = z
    }

    override val xy : IsoCoord2D get() { return IsoCoord2D(x,y) }

    companion object {
        init {
            registerCustomTypeRender<IsoCoord>()
        }

        fun project(x : Int, y : Int, z : Int) : Vec3f {
            return Vec3f(x * 0.5f - (y * 0.5f), x * 0.25f + y * 0.25f + z * 0.25f, 0.0f)
        }

        fun project(x : Int, y : Int, z : Int, v : Vec3f) {
            v.x = x * 0.5f - (y * 0.5f)
            v.y = x * 0.25f + y * 0.25f + z * 0.25f
            v.z = 0.0f
        }

        fun unproject(v : Vec2f) : IsoCoord {
            return IsoCoord(kotlin.math.round(v.x + v.y * 2.0f).toInt(), kotlin.math.round(v.y * 2.0f - v.x).toInt(), 0)
        }
    }

    fun project() : Vec3f {
        return IsoCoord.project(x,y,z)
    }

    fun project(v: Vec3f) {
        return IsoCoord.project(x,y,z, v)
    }

    fun distanceTo(to: IsoCoord): Float {
        val d2 = (to.toFloat() - this.toFloat()).magnitude2()
        return if (d2 != 0.0f) {
            kotlin.math.sqrt(d2)
        } else {
            return 0.0f
        }
    }

    fun toIsoCoordf() : IsoCoordf {
        return IsoCoordf(x.toFloat(), y.toFloat(), z.toFloat())
    }

    override fun toString(): String {
        return "IsoCoord($x, $y, $z)"
    }

    fun adjacent2D() : Iterator<IsoCoord2D> {
        return iterator {
            yield(IsoCoord2D(x-1,y))
            yield(IsoCoord2D(x+1,y))
            yield(IsoCoord2D(x,y-1))
            yield(IsoCoord2D(x,y+1))
        }
    }

    fun adjacentSameZ() : Iterator<IsoCoord> {
        return iterator {
            yield(IsoCoord(x-1,y,z))
            yield(IsoCoord(x+1,y,z))
            yield(IsoCoord(x,y-1,z))
            yield(IsoCoord(x,y+1,z))
        }
    }

    operator fun plus (mc : IsoCoord) : IsoCoord {
        return IsoCoord(x + mc.x, y + mc.y, z + mc.z)
    }

    operator fun minus (mc : IsoCoord) : IsoCoord {
        return IsoCoord(x - mc.x, y - mc.y, z - mc.z)
    }
}

class IsoCoordf(x : Float, y : Float, z : Float) : Vec3f(x,y,z) {
    constructor (v: Vec3f) : this(v.x, v.y, v.z)

    companion object {
        init {
            registerCustomTypeRender<IsoCoordf>()
        }
        fun project(x : Float, y : Float, z : Float) : Vec3f {
            return Vec3f(x * 0.5f - (y * 0.5f), x * 0.25f + y * 0.25f + z * 0.25f, 0.0f)
        }

        fun unproject(v : Vec3f) : IsoCoordf {
            return IsoCoordf(v.x + v.y * 2.0f, v.y * 2.0f - v.x, 0.0f)
        }

        fun unproject(v : Vec2f) : IsoCoordf {
            return IsoCoordf(v.x + v.y * 2.0f, v.y * 2.0f - v.x, 0.0f)
        }
    }

    fun project() : Vec3f {
        return IsoCoordf.project(x,y,z)
    }

    fun toIsoCoord(round : Boolean = true) : IsoCoord {
        return if (round) {
            IsoCoord(x.roundToInt(), y.roundToInt(), z.roundToInt())
        } else {
            IsoCoord(x.toInt(), y.toInt(), z.toInt())
        }
    }

    fun toIsoCoord2D(round : Boolean = true) : IsoCoord2D {
        return if (round) {
            IsoCoord2D(x.roundToInt(), y.roundToInt())
        } else {
            IsoCoord2D(x.toInt(), y.toInt())
        }
    }

    override operator fun times(scalar: Float): IsoCoordf {
        return IsoCoordf(elem0 * scalar, elem1 * scalar, elem2 * scalar)
    }

    operator fun plus(other: IsoCoordf): IsoCoordf {
        return IsoCoordf(x + other.x, y + other.y, z + other.z)
    }

    operator fun minus(other: IsoCoordf): IsoCoordf {
        return IsoCoordf(x - other.x, y - other.y, z - other.z)
    }

    override fun toString(): String {
        return "IsoCoordf($x, $y, $z)"
    }
}

@Serializable
enum class IsoDirection(val vec: IsoCoord2D) {
    N(IsoCoord2D(1, 0)),
    NE(IsoCoord2D(1, -1)),
    E(IsoCoord2D(0, -1)),
    SE(IsoCoord2D(-1, -1)),
    S(IsoCoord2D(-1, 0)),
    SW(IsoCoord2D(-1, 1)),
    W(IsoCoord2D(0, 1)),
    NW(IsoCoord2D(1, 1));

    val bitPattern = 1 shl this.ordinal

    companion object {
        fun bitmaskOf(vararg dirs: IsoDirection): Short {
            var i = 0
            for (dir in dirs) {
                i = i or dir.bitPattern
            }
            return i.toShort()
        }


        fun fromDelta(delta: Vec3i): IsoDirection {
            return fromDelta(delta.xy)
        }

        fun fromDelta(delta: Vec2i): IsoDirection {
            return if (delta.x > 0 && delta.y == 0) {
                N
            } else if (delta.x < 0 && delta.y == 0) {
                S
            } else if (delta.x == 0 && delta.y > 0) {
                W
            } else if (delta.x == 0 && delta.y < 0) {
                E
            } else {
                Noto.err("Invalid direction delta: $delta")
                N
            }
        }

        val numDirections = values().size
    }
}