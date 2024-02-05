package arx.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.ShortArraySerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or
import kotlin.io.encoding.Base64
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.reflect.KClass


private data class LRUCacheEntry<V>(val value: V, var lastAccessed: Long)

open class LRUCache<K, V>(val maximumSize: Int, val targetLoadFactor: Float = 0.5f) {
    private val entries: MutableMap<K, LRUCacheEntry<V>> = mutableMapOf()
    private val incrementor = AtomicLong(0)

    fun getOrPut(k: K, f: (K) -> V): V {
        val entry = entries.getOrPut(k) {
            LRUCacheEntry(f(k), 0L)
        }
        entry.lastAccessed = incrementor.getAndIncrement()

        if (entries.size > maximumSize) {
            val entriesByAccess = entries.entries.sortedBy { t -> t.value.lastAccessed }
            for (i in 0 until (maximumSize.toFloat() * (1.0f - targetLoadFactor)).toInt()) {
                entries.remove(entriesByAccess[i].key)
            }
        }

        return entry.value
    }

    fun remove(k: K) {
        entries.remove(k)
    }
}

class LRULoadingCache<K, V>(maximumSize: Int, targetLoadFactor: Float, val loadingFunction: (K) -> V) : LRUCache<K, V>(maximumSize, targetLoadFactor) {

    fun getOrPut(k: K): V {
        return getOrPut(k, loadingFunction)
    }
}


//class Grid2D<T>(defaultValue : T, initialDimensions : Vec2i = Vec2i(128,128)) {
//    private var origin = Vec2i(0,0)
//    private var min = Vec2i(0,0)
//    private var max = Vec2i(0,0)
//
//    private var capacity = initialDimensions
//    val dimensions : Vec2i get() { return dimensionsI }
//
//    private var data : Array<Any?> = arrayOfNulls(capacity.x * capacity.y)
//
//
//    private fun expandCapacity(xc: Int, yc: Int) {
//        val newData : Array<Any?> = arrayOfNulls(xc * yc)
//        for (x in 0 until capacity.x) {
//            val oldColumnIndex = x * capacity.y
//            val newColumnIndex = x * yc
//            System.arraycopy(data, oldColumnIndex, newData, newColumnIndex, capacity.y)
//        }
//    }
//
//    operator fun set(x: Int, y: Int, v: T) {
//        if (x >= dimensions.x || y >= dimensions.y) {
//            var newXC = capacity.x
//            var newYC = capacity.y
//            while (newXC <= x) {
//                newXC *= 2
//            }
//            while (newYC <= y) {
//                newYC *= 2
//            }
//
//            expandCapacity(newXC, newYC)
//        }
//    }
//
//}


@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
class FiniteGrid2D<T>(val dimensions: Vec2i, internal val defaultValue: T) {
    internal var data: Array<Any?> = arrayOfNulls(dimensions.x * dimensions.y)

    operator fun set(x: Int, y: Int, v: T) {
        if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            Noto.recordError("Setting value outside of range in FiniteGrid2D")
        } else {
            data[x * dimensions.y + y] = v
        }
    }

    operator fun get(x: Int, y: Int): T {
        return if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            defaultValue
        } else {
            (data[x * dimensions.y + y] as T?) ?: defaultValue
        }
    }

    fun getOrUpdate(x: Int, y: Int, fn: () -> T): T {
        val raw = if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            null
        } else {
            (data[x * dimensions.y + y] as T?)
        }
        return if (raw == null) {
            val ret = fn()
            this[x, y] = ret
            ret
        } else {
            raw
        }
    }

    fun iterator() : Iterator<T> {
        return iterator {
            for (v in data) {
                val c = v as? T
                if (c != null) {
                    yield(c)
                }
            }
        }
    }

    inline operator fun get(v: Vec2i): T {
        return get(v.x, v.y)
    }


    val width : Int get() { return dimensions.x }
    val height : Int get() { return dimensions.y }
}


@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
@Serializable(with = FiniteGrid2DbSerializer::class)
class FiniteGrid2Db(val dimensions: Vec2i) {
    constructor(dim: Vec2i, ba : ByteArray) : this(dim) {
        ba.copyInto(data)
    }

    internal var data: ByteArray = ByteArray(dimensions.x * dimensions.y)

    operator fun set(x: Int, y: Int, v: Byte) {
        if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            Noto.recordError("Setting value outside of range in FiniteGrid2Db")
        } else {
            data[x * dimensions.y + y] = v
        }
    }

    operator fun get(x: Int, y: Int): Byte {
        return if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            0
        } else {
            data[x * dimensions.y + y]
        }
    }

    inline operator fun get(v: Vec2i): Byte {
        return get(v.x, v.y)
    }


    val width : Int get() { return dimensions.x }
    val height : Int get() { return dimensions.y }

    fun raw(): ByteArray {
        return data
    }
}


@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
@Serializable(with = FiniteGrid2DsSerializer::class)
class FiniteGrid2Ds(val dimensions: Vec2i) {
    constructor(dim: Vec2i, ba : ShortArray) : this(dim) {
        ba.copyInto(data)
    }

    internal var data: ShortArray = ShortArray(dimensions.x * dimensions.y)

    operator fun set(x: Int, y: Int, v: Short) {
        if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            Noto.recordError("Setting value outside of range in FiniteGrid2Db")
        } else {
            data[x * dimensions.y + y] = v
        }
    }

    operator fun get(x: Int, y: Int): Short {
        return if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            0
        } else {
            data[x * dimensions.y + y]
        }
    }

    inline operator fun get(v: Vec2i): Short {
        return get(v.x, v.y)
    }


    val width : Int get() { return dimensions.x }
    val height : Int get() { return dimensions.y }

    fun raw(): ShortArray {
        return data
    }
}


@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
@Serializable(with = FiniteGrid2DbitsSerializer::class)
class FiniteGrid2Dbits(val dimensions: Vec2i) {
    constructor(dim: Vec2i, ba : ByteArray) : this(dim) {
        ba.copyInto(data)
    }

    internal val yMul = ((dimensions.y + 7) / 8)
    internal var data: ByteArray = ByteArray(dimensions.x * yMul)

    operator fun set(x: Int, y: Int, v: Boolean) {
        if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            Noto.recordError("Setting value outside of range in FiniteGrid2Dbits")
        } else {
            val idx = x * yMul + (y shr 3)
            val bitmask = (1 shl (y and 7)).toByte()
            if (v) {
                data[idx] = data[idx] or bitmask
            } else {
                data[idx] = data[idx] and bitmask.inv()
            }
        }
    }

    operator fun get(x: Int, y: Int): Boolean {
        return if (x < 0 || y < 0 || x >= dimensions.x || y >= dimensions.y) {
            false
        } else {
            val yi = y and 7
            (data[x * yMul + (y shr 3)] and (1 shl yi).toByte()).toInt() != 0
        }
    }

    inline operator fun get(v: Vec2i): Boolean {
        return get(v.x, v.y)
    }


    val width : Int get() { return dimensions.x }
    val height : Int get() { return dimensions.y }

    fun raw(): ByteArray {
        return data
    }
}


@OptIn(ExperimentalSerializationApi::class)
open class FiniteGrid2DSerializer<T : Any>(val elementClass : KClass<T>, val elementSerializer : KSerializer<T>) : KSerializer<FiniteGrid2D<T>> {
    override fun deserialize(decoder: Decoder): FiniteGrid2D<T> {
        return decoder.decodeStructure(descriptor) {
            val w = decodeIntElement(descriptor, 0)
            val h = decodeIntElement(descriptor, 1)
            val defVal = decodeSerializableElement(descriptor, 2, elementSerializer)

            val ret = FiniteGrid2D(Vec2i(w,h), defVal)
            ret.data = decodeSerializableElement(descriptor, 3, arraySerializer, null) as Array<Any?>

//            decoder.decodeStructure(descriptor) {
//                var tmp : T? = null
//                for (i in 0 until w * h) {
//                    tmp = decodeNullableSerializableElement(descriptor, i, elementSerializer.nullable, tmp)
//
//                    if (tmp != null) {
//                        ret.data[i] = tmp
//                    }
//                }
//            }

            ret
        }

    }

//    val listDescriptor = listSerialDescriptor(elementSerializer.descriptor)
    val arraySerializer = ArraySerializer(elementClass, elementSerializer.nullable)
    val listDescriptor = arraySerializer.descriptor

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FiniteGrid2D", elementSerializer.descriptor) {
        element<Int>("width")
        element<Int>("height")
        element("defaultValue", elementSerializer.descriptor)
        element("data", listDescriptor)
    }


    override fun serialize(encoder: Encoder, value: FiniteGrid2D<T>) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.dimensions.x)
            encodeIntElement(descriptor, 1, value.dimensions.y)
            encodeSerializableElement(descriptor, 2, elementSerializer, value.defaultValue)
            encodeSerializableElement(descriptor, 3, arraySerializer, value.data as Array<T?>)
//            (listDescriptor, value.dimensions.x * value.dimensions.y) {
//                for (i in 0 until value.dimensions.x * value.dimensions.y) {
//                    encodeNullableSerializableElement(descriptor, i, elementSerializer, value.data[i] as T?)
//                }
//            }
        }
    }

}

object FiniteGrid2DbSerializer : KSerializer<FiniteGrid2Db> {
    override fun deserialize(decoder: Decoder): FiniteGrid2Db {
        return decoder.decodeStructure(descriptor) {
            val w = decodeIntElement(descriptor, 0)
            val h = decodeIntElement(descriptor, 1)
            val data = decodeStringElement(descriptor, 2)

            val decodedData = java.util.Base64.getDecoder().decode(data)

            val ret = FiniteGrid2Db(Vec2i(w,h), decodedData)

            ret
        }

    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FiniteGrid2Db") {
        element<Int>("width")
        element<Int>("height")
        element<String>("data")
    }


    override fun serialize(encoder: Encoder, value: FiniteGrid2Db) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.dimensions.x)
            encodeIntElement(descriptor, 1, value.dimensions.y)
            encodeStringElement(descriptor, 2, java.util.Base64.getEncoder().encodeToString(value.raw()))
        }
    }
}

object FiniteGrid2DsSerializer : KSerializer<FiniteGrid2Ds> {
    override fun deserialize(decoder: Decoder): FiniteGrid2Ds {
        return decoder.decodeStructure(descriptor) {
            val w = decodeIntElement(descriptor, 0)
            val h = decodeIntElement(descriptor, 1)
            val data = decodeSerializableElement(descriptor, 2, ShortArraySerializer(), null)

            val ret = FiniteGrid2Ds(Vec2i(w,h), data)

            ret
        }

    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FiniteGrid2Ds") {
        element<Int>("width")
        element<Int>("height")
        element<ShortArray>("data")
    }


    override fun serialize(encoder: Encoder, value: FiniteGrid2Ds) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.dimensions.x)
            encodeIntElement(descriptor, 1, value.dimensions.y)
            encodeSerializableElement(descriptor, 2, ShortArraySerializer(), value.raw())
        }
    }
}

object FiniteGrid2DbitsSerializer : KSerializer<FiniteGrid2Dbits> {
    override fun deserialize(decoder: Decoder): FiniteGrid2Dbits {
        return decoder.decodeStructure(descriptor) {
            val w = decodeIntElement(descriptor, 0)
            val h = decodeIntElement(descriptor, 1)
            val data = decodeStringElement(descriptor, 2)

            val decodedData = java.util.Base64.getDecoder().decode(data)

            val ret = FiniteGrid2Dbits(Vec2i(w,h), decodedData)

            ret
        }

    }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FiniteGrid2Db") {
        element<Int>("width")
        element<Int>("height")
        element<String>("data")
    }


    override fun serialize(encoder: Encoder, value: FiniteGrid2Dbits) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.dimensions.x)
            encodeIntElement(descriptor, 1, value.dimensions.y)
            encodeStringElement(descriptor, 2, java.util.Base64.getEncoder().encodeToString(value.raw()))
        }
    }
}


@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
class FiniteGrid3D<T>(val dimensions: Vec3i, private val defaultValue: T) {
    val yzMul = dimensions.y * dimensions.z
    private var data: Array<Any?> = arrayOfNulls(dimensions.x * dimensions.y * dimensions.z)

    operator fun set(x: Int, y: Int, z: Int, v: T) {
        if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            Noto.recordError("Setting value outside of range in FiniteGrid2D")
        } else {
            data[x * yzMul + y * dimensions.z + z] = v
        }
    }

    operator fun get(x: Int, y: Int, z: Int): T {
        return if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            defaultValue
        } else {
            (data[x * yzMul + y * dimensions.z + z] as T?) ?: defaultValue
        }
    }

    fun getOrUpdate(x: Int, y: Int, z: Int, fn: () -> T): T {
        val raw = if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            null
        } else {
            (data[x * yzMul + y + z] as T?)
        }
        return if (raw == null) {
            val ret = fn()
            this[x, y, z] = ret
            ret
        } else {
            raw
        }
    }

    inline operator fun get(v: Vec3i): T {
        return get(v.x, v.y, v.z)
    }

}

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
class FiniteGrid3Df(val dimensions: Vec3i, val defaultValue : Float = 0.0f) {
    val yzMul = dimensions.y * dimensions.z
    private val data: FloatArray = FloatArray(dimensions.x * dimensions.y * dimensions.z)


    fun setAll(f : Float) {
        for (i in 0 until data.size) {
            data[i] = f
        }
    }
    operator fun set(x: Int, y: Int, z: Int, v: Float) {
        if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            Noto.recordError("Setting value outside of range in FiniteGrid2D")
        } else {
            data[x * yzMul + y * dimensions.z + z] = v
        }
    }

    operator fun get(x: Int, y: Int, z: Int): Float {
        return if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            defaultValue
        } else {
            data[x * yzMul + y * dimensions.z + z]
        }
    }

    fun getOrUpdate(x: Int, y: Int, z: Int, fn: () -> Float): Float {
        val raw = if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            defaultValue
        } else {
            data[x * yzMul + y + z]
        }
        return if (raw == defaultValue) {
            val ret = fn()
            this[x, y, z] = ret
            ret
        } else {
            raw
        }
    }

    inline operator fun get(v: Vec3i): Float {
        return get(v.x, v.y, v.z)
    }

    fun getRaw(i : Int) : Float {
        return data[i]
    }

    fun setRaw(i : Int, f : Float) {
        data[i] = f
    }

}


@OptIn(ExperimentalUnsignedTypes::class)
@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
class FiniteGrid3Dbits(val dimensions: Vec3i) {
    val zMul = ((dimensions.z+31) / 32)
    val yzMul = dimensions.y * zMul
    private val data: UIntArray = UIntArray(dimensions.x * dimensions.y * zMul)


    fun setAll(f : Boolean) {
        if (f) {
            for (i in 0 until data.size) {
                data[i] = UInt.MAX_VALUE
            }
        } else {
            for (i in 0 until data.size) {
                data[i] = 0u
            }
        }
    }
    operator fun set(x: Int, y: Int, z: Int, v: Boolean) {
        if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            Noto.recordError("Setting value outside of range in FiniteGrid2D")
        } else {
            val zi = z shr 5
            val bi = z and 31
            val i = x * yzMul + y * zMul + zi
            if (v) {
                data[i] = data[i] or (1 shl bi).toUInt()
            } else {
                data[i] = data[i] and (1 shl bi).inv().toUInt()
            }
        }
    }

    operator fun get(x: Int, y: Int, z: Int): Boolean {
        return if (x < 0 || y < 0 || z < 0 || x >= dimensions.x || y >= dimensions.y || z >= dimensions.z) {
            false
        } else {
            val zi = z shr 5
            val bi = z and 31
            val i = x * yzMul + y * zMul + zi
            (data[i] and (1 shl bi).toUInt()) != 0u
        }
    }

    inline operator fun get(v: Vec3i): Boolean {
        return get(v.x, v.y, v.z)
    }

}



@JvmInline
@Serializable(BitVec32Serializer::class)
value class BitVec32 (val v : Int = 0) {
    operator fun get(i : Int) : Boolean {
        return (v and (1 shl i)) != 0
    }

    fun withBit(i : Int, b : Boolean) : BitVec32 {
        val bit = 1 shl i
        return BitVec32(if (b) {
            v or bit
        } else {
            val invBit = bit.inv()
            v and invBit
        })
    }

    fun setBit(i: Int): BitVec32 {
        return BitVec32(v or (1 shl i))
    }
}

@Serializer(BitVec32::class)
object BitVec32Serializer : KSerializer<BitVec32> {
    override fun deserialize(decoder: Decoder): BitVec32 {
        return BitVec32(decoder.decodeInt())
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BitVec32", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: BitVec32) {
        encoder.encodeInt(value.v)
    }
}


class Watcher<T>(val fn: () -> T) {
    var previousValue: T? = null
    var first = true
    fun hasChanged(): Boolean {
        val v = fn()
        return if (first) {
            previousValue = v
            first = false
            true
        } else {
            val ret = v != previousValue
            previousValue = v
            ret
        }
    }
}

class HashWatcher<T>(val fn: () -> T) {
    var previousHash: Int? = null
    var first = true
    fun hasChanged(): Boolean {
        val v = fn()
        return if (first) {
            previousHash = v.hashCode()
            first = false
            true
        } else {
            val hash = v.hashCode()
            val ret = hash != previousHash
            previousHash = hash
            ret
        }
    }
}

class IdentityWatcher<T> internal constructor (val fn: () -> T) {
    var previousValue: T? = null
    var first = true
    fun hasChanged(): Boolean {
        val v = fn()
        return if (first) {
            previousValue = v
            first = false
            true
        } else {
            val ret = v !== previousValue
            previousValue = v
            ret
        }
    }
}

class IdentityWatcher2<T, U>(val fn: () -> Pair<T,U>) {
    var previousValue: Pair<T, U>? = null
    var first = true
    fun hasChanged(): Boolean {
        val v = fn()
        return if (first) {
            previousValue = v
            first = false
            true
        } else {
            val ret = v.first !== previousValue?.first || v.second !== previousValue?.second
            previousValue = v
            ret
        }
    }
}


class Watcher1<C, T>(val fn: C.() -> T) {
    var previousValue: T? = null
    var first = true
    fun hasChanged(c : C): Boolean {
        val v = c.fn()
        return if (first) {
            previousValue = v
            first = false
            true
        } else {
            val ret = v != previousValue
            previousValue = v
            ret
        }
    }
}



class Multimap<K,V> : MutableMap<K, List<V>> by HashMap() {

    fun put(k : K, v : V) {
        (getOrPut(k) { mutableListOf() } as MutableList<V>).add(v)
    }

}



class ShortRingBuffer(initialSize: Int) {
    private var po2 = log2(initialSize.toFloat()).roundToInt()
    private var po2m1 = (1 shl po2) - 1

    private var buffer = ShortArray(1 shl po2)

    private var head = 0
    private var tail = 0
    var size = 0
        private set

    fun enqueue(s: Short) {
        val ntail = (tail + 1) and po2m1
        if (ntail == head) {
            val newBuffer = ShortArray(1 shl (po2 + 1))
            for (i in 0 until size) {
                newBuffer[i] = buffer[(head + i) and po2m1]
            }
            po2 += 1
            po2m1 = (1 shl po2) - 1

            head = 0
            tail = size
            buffer = newBuffer
            enqueue(s)
        } else {
            buffer[tail] = s
            tail = ntail
            size++
        }
    }

    fun dequeue(): Short {
        if (head == tail) {
            throw java.lang.IllegalStateException("Attempting to dequeue from an empty ring buffer")
        }
        val ret = buffer[head]
        head = (head + 1) and po2m1
        size--
        return ret
    }
}


data class Either<A,B> private constructor (val a: A?, val b : B?) {

    companion object {
        fun <A,B> left(a : A) : Either<A,B> {
            return Either(a, null)
        }
    }
}