@file:OptIn(ExperimentalContracts::class)

package arx.core

import com.typesafe.config.ConfigValue
import java.lang.Integer.max
import java.lang.Integer.min
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.sqrt


enum class Axis {
    X,Y,Z;

    fun is2D() : Boolean {
        return when (this) {
            X -> true
            Y -> true
            Z -> false
        }
    }

    fun to2D() : Axis2D? {
        return when (this) {
            X -> Axis2D.X
            Y -> Axis2D.Y
            Z -> null
        }
    }
}

fun axis(i: Int) : Axis {
    return when (i) {
        0 -> Axis.X
        1 -> Axis.Y
        2 -> Axis.Z
        else -> error("Axis only supports 3 dimensions: $i")
    }
}

enum class Axis2D(val vecf : Vec2f, val vec : Vec2i) {
    X(Vec2f(1.0f,0.0f), Vec2i(1,0)),Y(Vec2f(0.0f,1.0f), Vec2i(0,1));

    fun to3D() : Axis {
        return when(this) {
            X -> Axis.X
            Y -> Axis.Y
        }
    }

    companion object {
        val axes = arrayOf(Axis2D.X, Axis2D.Y)
    }
}


enum class HorizontalAlignment {
    Left,
    Centered,
    Right;

    companion object : FromConfigCreator<HorizontalAlignment> {
        override fun createFromConfig(cv: ConfigValue?): HorizontalAlignment? {
            return when (cv.asStr()?.lowercase() ?: "") {
                "left" -> Left
                "centered", "center" -> Centered
                "right" -> Right
                else -> null
            }
        }
    }
}

enum class VerticalAlignment {
    Bottom,
    Centered,
    Top;

    companion object : FromConfigCreator<VerticalAlignment> {
        override fun createFromConfig(cv: ConfigValue?): VerticalAlignment? {
            return when (cv.asStr()?.lowercase() ?: "") {
                "bottom" -> Bottom
                "centered", "center" -> Centered
                "top" -> Top
                else -> null
            }
        }
    }
}


//enum class Cardinals2D(val vector: Vec2i) {
//    Left(Vec2i(-1,0)), Right(Vec2i(1,0)), Up(Vec2i(0,1)), Down(Vec2i(0,-1));
//}

val Cardinals2D = arrayOf(Vec2i(-1,0), Vec2i(1,0), Vec2i(0,1), Vec2i(0, -1))

val UnitSquare2D = arrayOf(Vec2f(0.0f, 0.0f), Vec2f(1.0f, 0.0f), Vec2f(1.0f, 1.0f), Vec2f(0.0f, 1.0f))
val UnitSquare3D = arrayOf(Vec3f(0.0f, 0.0f, 0.0f), Vec3f(1.0f, 0.0f, 0.0f), Vec3f(1.0f, 1.0f, 0.0f), Vec3f(0.0f, 1.0f, 0.0f))
val CenteredUnitSquare3D = arrayOf(Vec3f(-0.5f, -0.5f, 0.0f), Vec3f(0.5f, -0.5f, 0.0f), Vec3f(0.5f, 0.5f, 0.0f), Vec3f(-0.5f, 0.5f, 0.0f))
val UnitSquare2Di = arrayOf(Vec2i(0, 0), Vec2i(1, 0), Vec2i(1, 1), Vec2i(0, 1))

/**
 * swaps the given element with the last element of the
 * list then deletes that. Does not preserve ordering of
 * the list as a result, but doesn't require linear time
 */
fun <T> MutableList<T>.swapAndPop(i: Int) {
    this[i] = this[size - 1]
    this.removeAt(size-1)
}

fun <T> MutableList<T>.pop() : T {
    val ret = this[size - 1]
    this.removeAt(size-1)
    return ret
}

fun <T> MutableList<T>.popOpt() : T? {
    if (isEmpty()) {
        return null
    }
    return pop()
}

fun <T> MutableList<T>.push(t: T) {
    add(t)
}

inline fun <reified T> List<*>.firstOfType() : T? {
    return find { it is T } as T?
}

fun <T> List<T>.insertAt(v : T, index: Int) : List<T> {
    return when (index) {
        0 -> listOf(v) + this
        size -> this + v
        else -> subList(0, index) + v + subList(index, size)
    }
}

fun <T> List<T>.removeAt(index: Int) : List<T> {
    return when (index) {
        0 -> drop(1)
        size - 1 -> dropLast(1)
        else -> subList(0, index) + subList(index + 1, size)
    }
}

fun <T> List<T>.replaceIndex(index: Int, v: T) : List<T> {
    return when (index) {
        0 -> listOf(v) + drop(1)
        size - 1 -> dropLast(1) + v
        else -> subList(0, index) + v + subList(index + 1, size)
    }
}


fun <T, U> Iterator<T>.map(fn : (T) -> U) : Iterator<U> {
    val iter = this
    return object : Iterator<U> {
        override fun hasNext(): Boolean {
            return iter.hasNext()
        }

        override fun next(): U {
            return fn(iter.next())
        }
    }
}

fun <T, U> Iterator<T>.mapNonNull(fn : (T) -> U?) : Iterator<U> {
    return iterator {
        for (v in this@mapNonNull) {
            val transformed = fn(v)
            if (transformed != null) {
                yield(transformed)
            }
        }
    }
}


fun <T> Iterator<T>.andThen(other: Iterator<T>) : Iterator<T> {
    return object : Iterator<T> {
        var activeIterator = this@andThen
        var nextIterator : Iterator<T>? = other

        override fun hasNext(): Boolean {
            return if (activeIterator.hasNext()) {
                true
            } else {
                val next = nextIterator
                if (next != null) {
                    activeIterator = next
                    activeIterator.hasNext()
                } else {
                    false
                }
            }
        }

        override fun next(): T {
            return activeIterator.next()
        }
    }
}

fun <T> Iterator<T>.any(predicate: (T) -> Boolean) : Boolean {
    while (this.hasNext()) {
        if (predicate(this.next())) {
            return true
        }
    }
    return false
}

fun <T> Iterator<T>.toList() : List<T> {
    val ret = mutableListOf<T>()
    while (this.hasNext()) {
        ret.add(this.next())
    }
    return ret
}

fun <T> Iterator<T>.toSet() : Set<T> {
    val ret = mutableSetOf<T>()
    while (this.hasNext()) {
        ret.add(this.next())
    }
    return ret
}

object NullGuardLet : GuardLet<Any> {
    override fun orElse(block: () -> Any): Any {
        return block()
    }
}

fun Int.clamp(a : Int, b: Int) : Int {
    return max(min(this, b), a)
}

fun Float.ceil() : Float {
    return kotlin.math.ceil(this)
}

fun Float.clamp(a : Float, b: Float) : Float {
    return kotlin.math.max(kotlin.math.min(this, b), a)
}


fun UInt.clamp(a : UInt, b: UInt) : UInt {
    if (this < a) {
        return a
    }
    if (this > b) {
        return b
    }
    return this
}

@Suppress("NOTHING_TO_INLINE")
inline fun Double.max(o: Double) : Double {
    return java.lang.Double.max(this, o)
}

@Suppress("NOTHING_TO_INLINE")
inline fun Float.max(o: Float) : Float {
    return java.lang.Float.max(this, o)
}

@Suppress("NOTHING_TO_INLINE")
inline fun Int.max(o: Int) : Int {
    return java.lang.Integer.max(this, o)
}

@Suppress("NOTHING_TO_INLINE")
inline fun Int.min(o: Int) : Int {
    return java.lang.Integer.min(this, o)
}


@Suppress("NOTHING_TO_INLINE")
inline fun Double.min(o: Double) : Double {
    return java.lang.Double.min(this, o)
}

@Suppress("NOTHING_TO_INLINE")
inline fun Float.min(o: Float) : Float {
    return java.lang.Float.min(this, o)
}

inline fun Float.abs() : Float {
    return kotlin.math.abs(this)
}

inline fun Int.abs() : Int {
    return kotlin.math.abs(this)
}

fun Double.clamp(a : Double, b: Double) : Double {
    return kotlin.math.max(kotlin.math.min(this, b), a)
}

fun maxu(a : UInt, b : UInt) : UInt {
    return if (a > b) { a } else { b }
}

fun minu(a : UInt, b : UInt) : UInt {
    return if (a < b) { a } else { b }
}

fun maxu(a : UByte, b : UByte) : UByte {
    return if (a > b) { a } else { b }
}

fun minu(a : UByte, b : UByte) : UByte {
    return if (a < b) { a } else { b }
}


inline fun <T, R : Comparable<R>> List<T>.minIndexBy(l : List<T>, fn : (T) -> R) : Int? {
    if (l.isEmpty()) {
        return null
    }

    var lowestValue : R = fn(l[0])
    var lowestIndex = 0
    for (i in 1 until l.size) {
        val v = fn(l[i])
        if (v < lowestValue) {
            lowestValue = v
            lowestIndex = i
        }
    }
    return lowestIndex
}

fun FloatArray.minIndex() : Int {
    val a = this
    if (a.isEmpty()) {
        return -1
    }
    var lowestValue = a[0]
    var lowestIndex = 0
    for (i in 1 until a.size) {
        val f = a[i]
        if (f < lowestValue) {
            lowestValue = f
            lowestIndex = i
        }
    }
    return lowestIndex
}

fun FloatArray.maxIndex() : Int {
    val a = this
    if (a.isEmpty()) {
        return -1
    }
    var highestValue = a[0]
    var highestIndex = 0
    for (i in 1 until a.size) {
        val f = a[i]
        if (f > highestValue) {
            highestValue = f
            highestIndex = i
        }
    }
    return highestIndex
}

fun sqrtf(f : Float) : Float {
    return sqrt(f)
}

fun distance(x1:Float, y1:Float, x2: Float, y2: Float) : Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrtf(dx*dx + dy*dy)
}

fun distance(x1:Float, y1:Float, z1:Float, x2: Float, y2: Float, z2:Float) : Float {
    val dx = x1 - x2
    val dy = y1 - y2
    val dz = z1 - z2
    return sqrtf(dx*dx + dy*dy + dz*dz)
}

fun distance(x1:Int, y1:Int, z1:Int, x2: Int, y2: Int, z2:Int) : Float {
    val dx = x1 - x2
    val dy = y1 - y2
    val dz = z1 - z2
    return sqrtf((dx*dx + dy*dy + dz*dz).toFloat())
}


fun distance(x1:Int, y1:Int, x2: Int, y2: Int) : Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrtf((dx*dx + dy*dy).toFloat())
}

fun <T> Iterator<T>.filter(fn : (T) -> Boolean) : Iterator<T> {
    return iterator {
        for (v in this@filter) {
            if (fn(v)) {
                yield(v)
            }
        }
    }
}

fun <T> MutableList<T>.addNonNull(a : T?) {
    if (a != null) {
        this.add(a)
    }
}

public inline fun <T> Iterable<T>.sumOfFloat(selector: (T) -> Float): Float {
    var sum: Float = 0.toFloat()
    for (element in this) {
        sum += selector(element)
    }
    return sum
}


sealed interface GuardLet<U> {
    infix fun orElse(block : () -> U) : U
}


@JvmInline
value class ValueGuardLet(val resultValue : Any?) : GuardLet<Any?> {
    override fun orElse(block: () -> Any?): Any? {
        return resultValue
    }
}

@OptIn(ExperimentalContracts::class)
@Suppress("UNCHECKED_CAST")
inline infix fun <T, U> T?.ifLet(block : (T) -> U) : GuardLet<U> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (this == null) {
        NullGuardLet as GuardLet<U>
    } else {
        ValueGuardLet(block(this) as Any?) as GuardLet<U>
    }
}

@OptIn(ExperimentalContracts::class)
@Suppress("UNCHECKED_CAST")
inline infix fun <T> T?.ifLetDo(block : (T) -> Unit) : GuardLet<Unit> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (this == null) {
        NullGuardLet as GuardLet<Unit>
    } else {
        ValueGuardLet(block(this) as Any?) as GuardLet<Unit>
    }
}

inline infix fun <T> T?.ifPresent(block : (T) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (this != null) {
        block(this)
    }
}

inline infix fun <T> T.letDo(block : (T) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    block(this)
}

@OptIn(ExperimentalContracts::class)
inline infix fun <T, R> T?.expectLet(block : (T) -> R) : R? {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (this != null) {
        block(this)
    } else {
        Noto.recordError("expected non-null value in expectLet")
        null
    }
}

fun String.camelCaseToSpaces() : String {
    val out = StringBuilder()
    for (i in 0 until length) {
        if (i > 0 && this[i].isUpperCase() && i < length - 1 && this[i-1].isLetter() && ! this[i-1].isUpperCase()) {
            out.append(' ')
        }
        out.append(this[i])
    }
    return out.toString()
}

fun String.capitalized() : String {
    return replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

fun String.uncapitalized() : String {
    return replaceFirstChar { if (it.isUpperCase()) it.lowercase(Locale.getDefault()) else it.toString() }
}

fun String.removeAllWhitespace() : String {
    return replace(" ", "")
}

fun String.equalsIgnoreCases(other: String) : Boolean {
    return this.equals(other, ignoreCase = true)
}

inline fun <T> tern (t : Boolean, a : T, b : T) : T {
    return if (t) { a } else { b }
}


fun Int.orHigher() : IntRange {
    return IntRange(this, Int.MAX_VALUE)
}
fun Int.orLower() : IntRange {
    return IntRange(Int.MIN_VALUE, this)
}

fun IntRange.hasOpenUpper() : Boolean {
    return this.last == Int.MAX_VALUE
}

fun IntRange.hasOpenLower() : Boolean {
    return this.first == Int.MIN_VALUE
}




fun <A, B> tuple(a: A, b : B) : Pair<A,B> {
    return Pair(a,b)
}
fun <A, B, C> tuple(a: A, b : B, c : C) : Triple<A,B,C> {
    return Triple(a,b,c)
}


fun main() {

    var x : Int? = null
    if (ThreadLocalRandom.current().nextBoolean()) {
        x = 3
    }

    val y = x ifLet {
        it * 3
    } orElse {
        1
    }

    println("$x -> $y")
}