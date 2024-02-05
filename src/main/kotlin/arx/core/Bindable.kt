package arx.core

import arx.core.bindable.BindablePatternEval
import arx.display.ascii.CustomChars.replaceEscapeSequences
import arx.display.core.Image
import arx.display.core.ImageRef
import arx.display.core.SentinelImageRef
import arx.display.windowing.WidgetBinding
import arx.display.windowing.components.ascii.AsciiRichText
import arx.display.windowing.components.ascii.AsciiRichTextSegment
import arx.display.windowing.components.ascii.AsciiStyledTextSegment
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValue
import java.lang.StringBuilder
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.reflect


val stringBindingPattern = Regex("%\\([?!]?[?!]?\\s*([a-zA-Z\\d.]*\\s*)\\)")
val stringBindingPatternWithDefault = Regex("%\\([?!]?[?!]?\\s*([a-zA-Z\\d.]*)\\s*(?:\\?:\\s?(.*))?\\s*\\)")

fun main() {
    val m = stringBindingPatternWithDefault.match("%(outcome.forResult ?: Any Default)")
    println(m)
}

data class BindingPointer(val targetPattern: String, val transform: ((Any?) -> Any?)? = null)

data class BindingRedirect(val redirectTo: String)

@Suppress("MoveVariableDeclarationIntoWhen")
class BindingContext(val mappings: Map<String, WidgetBinding>, val parent: BindingContext?, val dirtyBindings: Set<String>?, val forceUpdate: Boolean) {

    fun isDirty(bindingPattern: String) : Boolean {
        if (dirtyBindings == null || dirtyBindings.contains(bindingPattern)) { return true }

        for (section in bindingPattern.splitToSequence('.')) {
            if (dirtyBindings.contains(section)) {
                return true
            }
        }
        return false
//        if (! mappings.containsKey(bindingPattern)) {
//            return parent?.isDirty(bindingPattern) ?: false
//        } else {
//            return false
//        }
    }


//    fun resolveToAbsolutePattern(bindingPattern: String) : String {
//        val raw = resolveRaw(bindingPattern, false)
//        if (raw != null) {
//            if (raw is BindingPointer) {
//                return resolveToAbsolutePattern(raw.targetPattern)
//            } else {
//                return bindingPattern
//            }
//        }
//
//        val patternSections = bindingPattern.split('.')
//        if (patternSections.size == 1) {
//            return bindingPattern
//        }
//
    // Note: unfinished
////        var value: Any? = resolveRaw(patternSections[0], false)
////        var rawI = 1
////        var accumulate = patternSections[0]
////        while (rawI < patternSections.size - 1 && value == null) {
////            accumulate += "." + patternSections[rawI]
////            value = resolveRaw(accumulate, false)
////            rawI += 1
////        }
////        if (value == null) {
////            return null
////        }
//    }


    internal fun patternSubSections(str: String) : List<String> {
        val ret = mutableListOf<String>()

        val accum = StringBuilder()
        var i = 0
        while (i < str.length) {
            val c = str[i]
            when (c) {
                '.', '[', ']' -> {
                    if (accum.isNotEmpty()) {
                        ret.add(accum.toString())
                        accum.clear()
                    }
                }
                else -> accum.append(c)
            }
            i += 1
        }

        if (accum.isNotEmpty()) {
            ret.add(accum.toString())
        }

        return ret
    }

    fun resolveRaw(bindingPattern: String, followPointers: Boolean): Any? {
        // resolve against our mappings, then full parent resolution if not present
        val baseResolution = mappings[bindingPattern]?.value ?: parent?.resolveRaw(bindingPattern, followPointers)
        // if the base resolution is a pointer, resolve that, otherwise return the actual value
        return when (baseResolution) {
            is BindingPointer -> if (followPointers) {
                val res = resolve(baseResolution.targetPattern, followPointers)
                baseResolution.transform?.let { tr ->
                    tr(res)
                } ?: res
            } else {
                baseResolution
            }
            else -> baseResolution
        }
    }

    // Note: this treats A.B differently than A.B.C, it assumes that either the top level binding is a complex
    // object, or it should resolve the full string as the binding string. This is fine most of the time but
    // it may bite us in the future
    fun resolve(bindingPattern: String, followPointers: Boolean = true): Any? {
        val rawResolve = resolveRaw(bindingPattern, followPointers)
        if (rawResolve != null) {
            return rawResolve
        }

        val patternSections = patternSubSections(bindingPattern)
        if (patternSections.size == 1) {
            return null
        }

        // note: this makes it so if you have a complex object Foo(a,b,c) and you bind it at "value"
        // you cannot then bind something to "value.d.e" and have it be seen, because the complex value
        // will start down the subresolution path and not reconsider the next. Simply "value.d" should work
        // since it will hit an exact match.
        var value: Any? = resolveRaw(patternSections[0], followPointers)
        var rawI = 1
        var accumulate = patternSections[0]
        while (rawI < patternSections.size - 1 && value == null) {
            // if we haven't found anything by the time we attempt to do numeric indexing there's no chance of finding things
            if (patternSections[rawI][0].isDigit()) {
                return null
            }
            accumulate += "." + patternSections[rawI]
            value = resolveRaw(accumulate, followPointers)
            rawI += 1
        }
        if (value == null) {
            return null
        }

        for (i in rawI until patternSections.size) {
            val curValue = value ?: return null

            value = if (patternSections[i] == "className") {
                curValue.javaClass.simpleName
            } else if (patternSections[i] == "enumValues") {
                curValue.javaClass.enumConstants.toList()
            } else if (curValue is Map<*, *>) {
                curValue[patternSections[i]]
            } else {
                val secondaryPattern = patternSections[i]

                if (secondaryPattern.isNotEmpty() && secondaryPattern[0].isDigit()) {
                    secondaryPattern.toIntOrNull()?.let { index ->
                        if (curValue is List<*>) {
                            curValue[index]
                        } else {
                            null
                        }
                    }
                } else {
                    val fn = curValue.javaClass.declaredMethods.find { it.name == secondaryPattern }
                    if (fn != null) {
                        fn.invoke(curValue)
                    } else {
                        val prop = curValue.javaClass.kotlin.declaredMemberProperties.find { it.name == secondaryPattern }
                        if (prop != null) {
                            prop.get(curValue)
                        } else {
                            curValue::class.functions.find { it.name == secondaryPattern }?.let { fn ->
                                fn.instanceParameter?.let { instParam ->
                                    fn.callBy(mapOf(instParam to curValue))
                                }
                            }
                        }
                    }
                }
            }
        }
        return value
    }
}

val EmptyBindingContext = BindingContext(mapOf(), null, emptySet(), false)


interface Bindable<out T> {
    companion object {
        val updateFunctionsByType = mutableMapOf<Class<*>, (Any, BindingContext) -> Boolean>()

        fun updateBindableFields(v: Any, ctx: BindingContext): Boolean {
            val fn = updateFunctionsByType.getOrPut(v.javaClass) {
                val relevantFields = (v::class.memberProperties
                    .filter { p -> p.getter.returnType.isSubtypeOf(Bindable::class.starProjectedType) })

                { a, c ->
                    var anyChanged = false
                    for (field in relevantFields) {
                        val bindable = field.getter.call(a) as Bindable<*>
                        if (bindable.update(c)) {
                            anyChanged = true
                        }

                    }
                    anyChanged
                }
            }

            return fn(v, ctx)
        }
    }

    operator fun invoke(): T

    fun update(ctx: BindingContext): Boolean

    fun copyBindable(): Bindable<T>
}

fun bindableAny(cv: ConfigValue?, defaultValue: Any): Bindable<Any> {
    return if (cv.isStr()) {
        val str = cv.asStr() ?: ""
        AnyPatternBindable(BindablePatternEval.parse(str) { it }, defaultValue) as Bindable<Any>
    } else if (cv.isInt()) {
        ValueBindable(cv.asInt()!!)
    } else if (cv.isNum()) {
        ValueBindable(cv.asFloat()!!)
    } else if (cv.isBool()) {
        ValueBindable(cv.asBool()!!)
    } else if (cv.isList()) {
        ValueBindable(cv.asPrimitiveList())
    } else {
        ValueBindable(defaultValue)
    }
}

fun bindableAnyOpt(cv: ConfigValue?): Bindable<Any?> {
    return if (cv.isStr()) {
        val str = cv.asStr() ?: ""
        AnyOptPatternBindable(BindablePatternEval.parse(str) { it }, null) as Bindable<Any?>
    } else if (cv.isList()) {
        ValueBindable(cv.asPrimitiveList())
    } else {
        val pv = cv.asPrimitiveValue()
        if (pv != null) {
            ValueBindable(pv)
        } else {
            ValueBindable.Null()
        }
    }
}

class AnyPatternBindable(pattern: BindablePatternEval.Node, defaultValue: Any) : BasePatternBindable<Any>(pattern, defaultValue) {
    override fun copyBindable(): Bindable<Any> {
        return AnyPatternBindable(pattern, defaultValue)
    }

    override fun transform(a: Any?): Any? {
        return a
    }
}

class AnyOptPatternBindable(pattern: BindablePatternEval.Node, defaultValue: Any?) : BasePatternBindable<Any?>(pattern, defaultValue) {
    override fun copyBindable(): Bindable<Any?> {
        return AnyOptPatternBindable(pattern, defaultValue)
    }

    override fun transform(a: Any?): Any? {
        return a
    }
}

inline fun <reified T : Any?> bindableT(cv: ConfigValue?, defaultValue: T): Bindable<T> {
    return if (cv.isStr()) {
        val str = cv.asStr() ?: ""
        typedPatternBindable(str, defaultValue) as Bindable<T>
    } else {
        ValueBindable(defaultValue)
    }
}

inline fun <reified T : Any?> bindableT(cv: ConfigValue?, crossinline parse: (ConfigValue) -> T?, defaultValue: T): Bindable<T> {
    return if (cv.isStr()) {
        val str = cv.asStr() ?: ""
        val node = BindablePatternEval.parse(cv.asStr() ?: "") {
            val conf = ConfigFactory.parseString("{ v: $str }")
            conf["v"]?.let { parse(it) }
        }
        typedPatternBindable(node, defaultValue)
    } else {
        cv?.let { parse(it) }?.let { ValueBindable(it) } ?: ValueBindable(defaultValue)
//        ValueBindable(defaultValue)
    }
}



fun bindableIntOpt(cv: ConfigValue?, defaultValue: Int = 0): Bindable<Int?> {
    return if (cv == null) {
        ValueBindable.Null()
    } else if (cv.isNum()) {
        ValueBindable(cv.asInt() ?: defaultValue)
    } else if (cv.isStr()) {
        IntPatternBindable(BindablePatternEval.parse(cv.asStr() ?: "") { it.toIntOrNull() }, defaultValue)
    } else {
        Noto.warn("Invalid config for bindable int: $cv")
        ValueBindable.Zero
    }
}

fun bindableInt(cv: ConfigValue?): Bindable<Int> {
    return if (cv.isNum()) {
        ValueBindable(cv.asInt() ?: 0)
    } else if (cv.isStr()) {
        IntPatternBindable(BindablePatternEval.parse(cv.asStr() ?: "") { it.toIntOrNull() }, 0)
    } else {
        Noto.warn("Invalid config for bindable int: $cv")
        ValueBindable.Zero
    }
}

fun bindableFloat(cv: ConfigValue?): Bindable<Float> {
    return if (cv.isNum()) {
        ValueBindable(cv.asFloat() ?: 0.0f)
    } else if (cv.isStr()) {
        FloatPatternBindable(BindablePatternEval.parse(cv.asStr() ?: "") { it.toFloatOrNull() }, 0.0f)
    } else {
        Noto.warn("Invalid config for bindable float: $cv")
        ValueBindable.Zerof
    }
}

internal fun parseListOfStrings(str: String) : List<String> {
    val conf = ConfigFactory.parseString("{ value: $str }")
    return conf["value"].asList().map { it.asStr() ?: "" }
}

internal fun parseConf(str: String, fn : FromConfigCreator<*>) : Any? {
    val conf = ConfigFactory.parseString("{ value: $str }")
    return fn.createFromConfig(conf["value"] ?: return null)
}

fun bindableListOfString(cv: ConfigValue?): Bindable<List<String>> {
    return if (cv.isList()) {
        ValueBindable(cv.asList().mapNotNull { it.asStr() })
    } else if (cv.isStr()) {
        ListOfStringPatternBindable(BindablePatternEval.parse(cv.asStr() ?: "") { parseListOfStrings(it) }, emptyList())
    } else {
        Noto.warn("Invalid config for bindable float: $cv")
        ValueBindable.EmptyList()
    }
}

fun bindableRGBA(cv: ConfigValue?): Bindable<RGBA> {
    val directParsed = RGBA(cv)
    return if (directParsed != null) {
        ValueBindable(directParsed)
    } else {
        if (cv.isStr()) {
            RGBAPatternBindable(BindablePatternEval.parse(cv.asStr() ?: "") { parseConf(it, RGBA) }, White)
        } else {
            Noto.warn("Invalid config for bindable rgba: $cv")
            ValueBindable.White
        }
    }
}

fun bindableRGBAOpt(cv: ConfigValue?): Bindable<RGBA?> {
    if (cv == null) {
        return ValueBindable.Null()
    }
    val directParsed = RGBA(cv)
    return if (directParsed != null) {
        ValueBindable(directParsed)
    } else {
        if (cv.isStr()) {
            RGBAOptPatternBindable(BindablePatternEval.parse(cv.asStr() ?: "") { parseConf(it, RGBA) }, White)
        } else {
            Noto.warn("Invalid config for bindable rgba?: $cv")
            ValueBindable.Null()
        }
    }
}

fun extractBindingPattern(str: String): String? {
    return stringBindingPattern.match(str)?.component1()
}

fun extractBindingPattern(cv: ConfigValue): String? {
    val str = cv.asStr()
    return str?.let { s ->
        extractBindingPattern(s)
    }
}

fun bindableString(cv: ConfigValue?): Bindable<String> {
    return if (cv.isStr()) {
        val str = cv.asStr() ?: ""
        if (stringBindingPattern.containsMatchIn(str)) {
            StringPatternBindable(str, str)
        } else {
            ValueBindable(str)
        }
    } else {
        Noto.warn("Invalid config for bindable string : $cv")
        ValueBindable("")
    }
}

fun bindableRichText(cv: ConfigValue?): Bindable<RichText> {
    return if (cv.isStr()) {
        val str = cv.asStr() ?: ""
        if (stringBindingPattern.containsMatchIn(str)) {
            RichTextPatternBindable(str)
        } else {
            ValueBindable(RichText(str))
        }
    } else {
        Noto.warn("Invalid config for bindable rich text : $cv")
        ValueBindable(RichText(""))
    }
}

fun bindableAsciiRichText(cv: ConfigValue?): Bindable<AsciiRichText> {
    return if (cv.isStr()) {
        val str = cv.asStr() ?: ""
        if (stringBindingPatternWithDefault.containsMatchIn(str)) {
            AsciiRichTextPatternBindable(str)
        } else {
            ValueBindable(AsciiRichText(str))
        }
    } else {
        Noto.warn("Invalid config for bindable ascii rich text : $cv")
        ValueBindable(AsciiRichText(""))
    }
}

fun bindableImage(cv: ConfigValue?): Bindable<Image> {
    return if (cv.isStr()) {
        ImagePattern(BindablePatternEval.parse(cv.asStr() ?: "") { Resources.image(it) })
    } else {
        Noto.warn("Invalid config for bindable image : $cv")
        ValueBindable(SentinelImageRef.toImage())
    }
}

fun bindableImageOpt(cv: ConfigValue?): Bindable<Image?> {
    return if (cv.isStr()) {
        ImageOptPattern(BindablePatternEval.parse(cv.asStr() ?: "") {
            Resources.image(it)
        })
    } else {
        Noto.warn("Invalid config for bindable image : $cv")
        ValueBindable.Null()
    }
}


val boolBindingPattern = Regex("%\\([?!]?[?!]?\\s*([a-zA-Z\\d.]*\\s*)\\)(?:\\s*(==|!=|>|<)\\s*(.+))?")

fun bindableBool(cv: ConfigValue?): Bindable<Boolean> {
    return if (cv.isBool()) {
        ValueBindable(cv.asBool()!!)
    } else if (cv.isStr()) {
        SimpleBooleanPatternBindable(BindablePatternEval.parse(cv.asStr() ?: "") { it.toBoolean() })
    } else {
        Noto.warn("Invalid config for bindable boolean : $cv")
        ValueBindable(false)
    }
}


//inline fun <reified T>patternBindable(defaultValue: T, pattern: String) : Bindable<T> {
//    return object : Bindable<T> {
//        var currentValue: T = defaultValue
//        override fun invoke(): T {
//            return currentValue
//        }
//
//        override fun update(ctx: BindingContext) : Boolean {
//            val bound = ctx.resolve(pattern)
//            if (bound != null) {
//                return if (T::class.java.isAssignableFrom(bound.javaClass)) {
//                    if (currentValue != bound) {
//                        currentValue = bound as T
//                        true
//                    } else {
//                        false
//                    }
//                } else {
//                    Noto.recordError("bound value of incorrect type", mapOf("desiredType" to T::class.simpleName, "actualType" to bound.javaClass.simpleName))
//                    false
//                }
//            } else {
//                return if (currentValue != null) {
//                    currentValue = defaultValue
//                    true
//                } else {
//                    false
//                }
//            }
//        }
//
//    }
//}


fun <T> bindable(b: () -> T): Bindable<T> {
    return FunctionBindable(b)
}

fun <T> bindable(b: T): Bindable<T> {
    return ValueBindable(b)
}

data class ConstantBindable<T>(val value: T) : Bindable<T> {
    override fun invoke(): T {
        return value
    }

    override fun update(ctx: BindingContext): Boolean {
        return false
    }

    override fun copyBindable(): Bindable<T> {
        return this
    }
}

data class ValueBindable<T>(val value: T) : Bindable<T> {
    var first = true

    companion object {
        val True = ConstantBindable(true)
        val False = ConstantBindable(false)
        val Zero = ConstantBindable(0)
        val One = ConstantBindable(1)
        val Zerof = ConstantBindable(0.0f)
        private val NullRef = ConstantBindable(null)
        private val EmptyListRef = ConstantBindable(listOf<Any>())
        val White = ConstantBindable(RGBA(255, 255, 255, 255))
        val Black = ConstantBindable(RGBA(0, 0, 0, 255))
        val Clear = ConstantBindable(RGBA(255, 255, 255, 0))
        val EmptyString = ConstantBindable("")
        val EmptyAsciiRichText = ConstantBindable(AsciiRichText())

        @Suppress("UNCHECKED_CAST")
        fun <T> Null(): Bindable<T?> {
            return NullRef
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> EmptyList(): Bindable<List<T>> {
            return EmptyListRef as Bindable<List<T>>
        }
    }

    override fun invoke(): T {
        return value
    }

    override fun update(ctx: BindingContext): Boolean {
        if (first) {
            first = false
            return true
        }
        // do nothing, we have a fixed value
        return false
    }

    override fun copyBindable(): Bindable<T> {
        return ValueBindable(value)
    }
}

data class FunctionBindable<T>(val fn: () -> T) : Bindable<T> {
    var prevValue: T? = null
    var updated: Boolean = false
    override fun invoke(): T {
//        if (prevValue == null) {
//            prevValue = fn()
//            updated = true
//        }
//        return prevValue!!
        return fn()
    }

    override fun update(ctx: BindingContext): Boolean {
        val newValue = fn()
        val modified = if (prevValue != newValue) {
            prevValue = newValue
            true
        } else {
            false
        }
        val ret = modified || updated
        updated = false
        return ret
    }

    override fun copyBindable(): Bindable<T> {
        return copy()
    }
}

interface PatternBindable<T> : Bindable<T> {
    val pattern : BindablePatternEval.Node
}

abstract class BasePatternBindable<T>(override val pattern: BindablePatternEval.Node, var value: T) : PatternBindable<T> {

    val defaultValue = value
    var first = true

    override fun invoke(): T {
        if (first) {
            pattern.constantEvaluation()?.let { v ->
                transform(v)?.let { t ->
                    value = t
                }
                first = false
            }
        }
        return value
    }

    abstract fun transform(a: Any?): T?

    override fun update(ctx: BindingContext): Boolean {
        if (first || pattern.isDirty(ctx)) {
            first = false
            val res = pattern.evaluate(ctx)
            val tr = transform(res)
            return if (tr == null || tr != value) {
                value = tr ?: defaultValue
                true
            } else {
                ctx.forceUpdate
            }
        } else {
            return false
        }
    }
}


@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any?> typedPatternBindable(pattern: String, value: T): BasePatternBindable<T> {
    return TypedPatternBindable(BindablePatternEval.parse(pattern) { it }, value, typeOf<T>().jvmErasure.java as Class<T>)
}

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any?> typedPatternBindable(pattern: BindablePatternEval.Node, value: T): BasePatternBindable<T> {
    return TypedPatternBindable(pattern, value, typeOf<T>().jvmErasure.java as Class<T>)
}


class TypedPatternBindable<T : Any?>(pattern: BindablePatternEval.Node, value: T, val clazz: Class<T>) : BasePatternBindable<T>(pattern, value) {
    //    val clazz = value.javaClass
    override fun copyBindable(): Bindable<T> {
        return TypedPatternBindable(pattern, value, clazz)
    }

    @Suppress("UNCHECKED_CAST")
    override fun transform(a: Any?): T? {
        if (a == null) {
            return null
        }
        return if (clazz.isAssignableFrom(a.javaClass)) {
            a as? T?
        } else {
            null
        }
    }
}
//
//@Suppress("UNCHECKED_CAST")
//inline fun <reified T : Any> typedListPatternBindable(pattern: String): BasePatternBindable<List<T>> {
//    return TypedListPatternBindable(pattern, typeOf<T>().jvmErasure.java as Class<T>)
//}

@Suppress("UNCHECKED_CAST")
fun untypedListPatternBindable(pattern: String): BasePatternBindable<List<Any>> {
    return TypedListPatternBindable(BindablePatternEval.parse(pattern) { listOf(it) }, typeOf<Any>().jvmErasure.java as Class<Any>)
}

@Suppress("UNCHECKED_CAST")
fun untypedListBindable(cv: ConfigValue?): Bindable<List<Any>> {
    return if (cv.isStr()) {
        untypedListPatternBindable(cv.asStr() ?: "") as Bindable<List<Any>>
    } else if (cv.isList()) {
        ValueBindable(cv.asList().mapNotNull {
            if (it.isStr()) {
                it.asStr()
            } else if (it.isInt()) {
                it.asInt()
            } else if (it.isBool()) {
                it.asBool()
            } else if (it.isNum()) {
                it.asFloat()
            } else {
                Noto.err("untypedListBindable created from complex type that cannot be un-marshalled: $it")
                null
            }
        })
    } else {
        ValueBindable(emptyList())
    }
}

class TypedListPatternBindable<T : Any>(pattern: BindablePatternEval.Node, val clazz: Class<T>) : BasePatternBindable<List<T>>(pattern, emptyList()) {
    //    val clazz = value.javaClass
    override fun copyBindable(): Bindable<List<T>> {
        return TypedListPatternBindable(pattern, clazz)
    }

    @Suppress("UNCHECKED_CAST")
    override fun transform(a: Any?): List<T>? {
        if (a == null) {
            return null
        }

        return when (a) {
            is Array<*> -> {
                a.mapNotNull { tern(it != null && clazz.isAssignableFrom(it.javaClass), it as T, null) }.toList()
            }
            is List<*> -> {
                a.mapNotNull { tern(it != null && clazz.isAssignableFrom(it.javaClass), it as T, null) }
            }
            is Collection<*> -> {
                a.mapNotNull { tern(it != null && clazz.isAssignableFrom(it.javaClass), it as T, null) }.toList()
            }
            else -> {
                null
            }
        }
    }
}

enum class BooleanPatternOperator {
    Equals,
    NotEquals,
    GreaterThan,
    LessThan
}


class SimpleBooleanPatternBindable(pattern: BindablePatternEval.Node, value: Boolean = false) : BasePatternBindable<Boolean>(pattern, value) {
    override fun copyBindable(): Bindable<Boolean> {
        return SimpleBooleanPatternBindable(pattern, value)
    }

    override fun transform(a: Any?): Boolean? {
        return a as? Boolean
    }
}

@Deprecated("complex logic now implemented in BindablePatternEval, use SimpleBooleanPatternBindable instead")
class BooleanPatternBindable(pattern: BindablePatternEval.Node, val justCheckPresent: Boolean, val invert: Boolean, val operator: BooleanPatternOperator?, val argument: String?, value: Boolean = false) :
    BasePatternBindable<Boolean>(pattern, value) {
    override fun transform(a: Any?): Boolean? {
        return when (a) {
            is Boolean ->
                if (!invert) {
                    a
                } else {
                    !a
                }
            else -> {
                if (justCheckPresent) {
                    if (!invert) {
                        a != null
                    } else {
                        a == null
                    }
                } else if (argument != null) {
                    when (operator) {
                        BooleanPatternOperator.Equals -> argument.lowercase() == a.toString().lowercase()
                        BooleanPatternOperator.NotEquals -> argument.lowercase() != a.toString().lowercase()
                        BooleanPatternOperator.GreaterThan, BooleanPatternOperator.LessThan -> {
                            argument.toDoubleOrNull()?.let { argD ->
                                (a as? Number)?.toDouble()?.let { boundD ->
                                    if (operator == BooleanPatternOperator.GreaterThan) {
                                        boundD > argD
                                    } else {
                                        boundD < argD
                                    }
                                }
                            } ?: false
                        }
                        null -> {
                            Noto.warn("Boolean pattern bindable with argument but no operator: $pattern")
                            false
                        }
                    }
                } else {
                    null
                }
            }
        }
    }

    override fun copyBindable(): Bindable<Boolean> {
        return BooleanPatternBindable(pattern, justCheckPresent, invert, operator, argument, value)
    }
}

class IntPatternBindable(pattern: BindablePatternEval.Node, value: Int = 0) : BasePatternBindable<Int>(pattern, value) {
    override fun transform(a: Any?): Int? {
        return (a as? Number)?.toInt()
    }

    override fun copyBindable(): Bindable<Int> {
        return IntPatternBindable(pattern, value)
    }
}

class ImageRefPattern(pattern: BindablePatternEval.Node, value: ImageRef = SentinelImageRef) : BasePatternBindable<ImageRef>(pattern, value) {
    override fun transform(a: Any?): ImageRef? {
        return a as? ImageRef
    }

    override fun copyBindable(): Bindable<ImageRef> {
        return ImageRefPattern(pattern, value)
    }
}

class ImagePattern(pattern: BindablePatternEval.Node, value: Image = SentinelImageRef.toImage()) : BasePatternBindable<Image>(pattern, value) {
    override fun transform(a: Any?): Image? {
        return (a as? ImageRef)?.toImage() ?: (a as? Image)
    }

    override fun copyBindable(): Bindable<Image> {
        return ImagePattern(pattern, value)
    }
}

class ImageOptPattern(pattern: BindablePatternEval.Node, value: Image? = null) : BasePatternBindable<Image?>(pattern, value) {
    override fun transform(a: Any?): Image? {
        return (a as? ImageRef)?.toImage() ?: (a as? Image)
    }

    override fun copyBindable(): Bindable<Image?> {
        return ImageOptPattern(pattern, value)
    }
}


class RGBAPatternBindable(pattern: BindablePatternEval.Node, value: RGBA = White) : BasePatternBindable<RGBA>(pattern, value) {
    override fun transform(a: Any?): RGBA? {
        return if (a is String) {
            RGBA.fromString(a)
        } else {
            (a as? RGBA) ?: (a as? HSL)?.toRGBA()
        }
    }

    override fun copyBindable(): Bindable<RGBA> {
        return RGBAPatternBindable(pattern, value)
    }
}

class RGBAOptPatternBindable(pattern: BindablePatternEval.Node, value: RGBA? = null) : BasePatternBindable<RGBA?>(pattern, value) {
    override fun transform(a: Any?): RGBA? {
        return if (a is String) {
            RGBA.fromString(a)
        } else {
            (a as? RGBA) ?: (a as? HSL)?.toRGBA()
        }
    }

    override fun copyBindable(): Bindable<RGBA?> {
        return RGBAOptPatternBindable(pattern, value)
    }
}


class FloatPatternBindable(pattern: BindablePatternEval.Node, value: Float = 0.0f) : BasePatternBindable<Float>(pattern, value) {
    override fun transform(a: Any?): Float? {
        return (a as? Number)?.toFloat()
    }

    override fun copyBindable(): Bindable<Float> {
        return FloatPatternBindable(pattern, value)
    }
}

class ListOfStringPatternBindable(pattern: BindablePatternEval.Node, value: List<String> = emptyList()) : BasePatternBindable<List<String>>(pattern, value) {
    override fun transform(a: Any?): List<String>? {
        return (a as? List<*>)?.map { it.toString() }
    }

    override fun copyBindable(): Bindable<List<String>> {
        return ListOfStringPatternBindable(pattern, value)
    }
}

class StringPatternBindable(val rawPattern: String, var value: String = "") : Bindable<String> {
    val patterns = stringBindingPattern.match(rawPattern)?.raw
    override fun invoke(): String {
        return value
    }

    override fun update(ctx: BindingContext): Boolean {
        if (patterns != null) {
            var anyDirty = false
            for (p in patterns) {
                anyDirty = anyDirty || (p.groupValues.getOrNull(1)?.let { ctx.isDirty(it) } ?: false)
            }
            if (! anyDirty) {
                return false
            }


            var cursor = 0
            var computed = ""
            for (p in patterns) {
                if (p.range.first > cursor) {
                    computed += rawPattern.substring(cursor until p.range.first)
                }
                p.groupValues.getOrNull(1)?.let { ctx.resolve(it) }?.let {
                    computed += it
                }
                cursor = p.range.last + 1
            }
            if (cursor < rawPattern.length) {
                computed += rawPattern.substring(cursor until rawPattern.length)
            }

            if (computed != value) {
                value = computed
                return true
            }
        } else {
            Noto.err("Invalid string pattern : $rawPattern")
        }
        return ctx.forceUpdate
    }

    override fun copyBindable(): Bindable<String> {
        return StringPatternBindable(rawPattern, value)
    }
}


class RichTextPatternBindable(val rawPattern: String) : Bindable<RichText> {
    val patterns = stringBindingPattern.match(rawPattern)?.raw

    var value: RichText = computeRichText(EmptyBindingContext)
    override fun invoke(): RichText {
        return value
    }

    fun computeRichText(ctx: BindingContext): RichText {
        if (patterns == null) {
            return RichText(rawPattern)
        }

        var cursor = 0
        val computed = RichText()
        for (p in patterns) {
            if (p.range.first > cursor) {
                computed.add(SimpleTextSegment(rawPattern.substring(cursor until p.range.first)))
            }
            p.groupValues.getOrNull(1)?.let { ctx.resolve(it) }?.let {
                when (it) {
                    is RichText -> computed.add(it)
                    is RichTextSegment -> computed.add(it)
                    else -> computed.add(SimpleTextSegment(it.toString()))
                }
            }
            cursor = p.range.last + 1
        }
        if (cursor < rawPattern.length) {
            computed.add(SimpleTextSegment(rawPattern.substring(cursor until rawPattern.length)))
        }

        return computed
    }

    override fun update(ctx: BindingContext): Boolean {
        Noto.info("Todo: make RichTextPatternBindable only update when dirty, like ascii version")
        if (patterns != null) {
            val rt = computeRichText(ctx)
            if (rt != value) {
                value = rt
                return true
            }
        } else {
            Noto.err("Invalid rich text pattern : $rawPattern")
        }
        return ctx.forceUpdate
    }

    override fun copyBindable(): Bindable<RichText> {
        return RichTextPatternBindable(rawPattern)
    }
}

class AsciiRichTextPatternBindable(val sections: List<Section>) : Bindable<AsciiRichText> {
    constructor (rawPattern: String) : this(computeSections(rawPattern))

    companion object {
        sealed interface Section {
            data class Constant(val text: AsciiRichText) : Section
            data class Segment(val segment : AsciiRichTextSegment) : Section
            data class Binding(val subPattern : String, val default: String?) : Section
        }

        fun computeSections(rawPattern: String) : List<Section> {
            val patterns = stringBindingPatternWithDefault.match(rawPattern)?.raw
            val ret = mutableListOf<Section>()
            if (patterns == null) {
                ret.add(Section.Constant(AsciiRichText(rawPattern)))
            } else {
                var cursor = 0
                for (p in patterns) {
                    if (p.range.first > cursor) {
                        ret.add(Section.Segment(AsciiStyledTextSegment(replaceEscapeSequences(rawPattern.substring(cursor until p.range.first)))))
                    }

                    p.groupValues.getOrNull(1)?.let {
                        ret.add(Section.Binding(it, p.groupValues.getOrNull(2)?.ifEmpty { null }))
                    }

                    cursor = p.range.last + 1
                }
                if (cursor < rawPattern.length) {
                    ret.add(Section.Segment(AsciiStyledTextSegment(replaceEscapeSequences(rawPattern.substring(cursor until rawPattern.length)))))
                }
            }

            return ret
        }
    }

    var value: AsciiRichText = computeAsciiRichText(EmptyBindingContext)
    override fun invoke(): AsciiRichText {
        return value
    }

    fun computeAsciiRichText(ctx: BindingContext): AsciiRichText {
        var transformFunction: ((Any) -> Any)? = null

        (sections.firstOrNull() as? Section.Constant?)?.let { return it.text }

        val segments = mutableListOf<AsciiRichTextSegment>()
        for (s in sections) {
            when (s) {
                is Section.Constant -> return s.text
                is Section.Segment -> segments.add(s.segment)
                is Section.Binding -> {
                    val bound = ctx.resolve(s.subPattern)
                    val effBound = if (bound == null || (bound is AsciiRichText && bound.isEmpty()) || (bound is String && bound.isEmpty())) {
                        s.default
                    } else {
                        bound
                    }
                    effBound?.let {
                        when (it) {
                            is AsciiRichText -> segments.addAll(it.flattenedSegments())
                            is AsciiStyledTextSegment -> segments.add(it)
                            is Function1<*, *> -> {
                                transformFunction = it as ((Any) -> Any)
                            }
                            else -> {
                                transformFunction.ifLet { fn ->
                                    when (val res = fn(it)) {
                                        is AsciiRichText -> segments.addAll(res.flattenedSegments())
                                        is String -> segments.add(AsciiStyledTextSegment(replaceEscapeSequences(res)))
                                        is IntRange -> {
                                            val str = if (res.first == Int.MIN_VALUE) {
                                                "<= ${res.last}"
                                            } else if (res.last == Int.MAX_VALUE) {
                                                ">= ${res.first}"
                                            } else {
                                                "${res.first} .. ${res.last}"
                                            }
                                            segments.add(AsciiStyledTextSegment(str))
                                        }
                                        else -> {
                                            Noto.warn("Bounding rich text transform function resulted in non-rich text / non-string: $res")
                                            segments.add(AsciiStyledTextSegment(replaceEscapeSequences(res.toString())))
                                        }
                                    }
                                }.orElse {
                                    segments.add(AsciiStyledTextSegment(replaceEscapeSequences(it.toString())))
                                }
                            }
                        }
                    }
                }
            }
        }

        return AsciiRichText(segments)
    }

    override fun update(ctx: BindingContext): Boolean {
        if (sections.any { s -> s is Section.Binding && ctx.isDirty(s.subPattern) }) {
            val rt = computeAsciiRichText(ctx)
            if (rt != value) {
                value = rt
                return true
            }
            return ctx.forceUpdate
        }
        return false
    }

    override fun copyBindable(): Bindable<AsciiRichText> {
        return AsciiRichTextPatternBindable(sections)
    }
}


data class PropertyReference<T : Any>(val getter: () -> T, val setter: (T) -> Unit) {
    @OptIn(ExperimentalReflectionOnLambdas::class)
    val parameterType = setter.reflect()?.parameters?.getOrNull(0)?.type ?: typeOf<Unit>()
}

fun propertyBinding(cv: ConfigValue?, twoWay: Boolean): PropertyBinding? {
    return cv.asStr()
        ?.let { stringBindingPattern.match(it) }
        ?.let { (b) -> PropertyBinding(b, twoWay) }
}

class PropertyBinding(val patternString: String, val twoWayBinding: Boolean) {
    internal var lastBoundValue: Any? = null
    internal var lastFullResolution: Any? = null

    internal val mainPatternSection : String
    internal val finalSection : String
    init {
        val (m,f) = splitMainAndLast(patternString)
        mainPatternSection = m
        finalSection = f
    }


    internal var getter: () -> Any? = { null }
    internal var setter: (Any?) -> Unit = {}

    var type : KType = typeOf<Unit>()

    companion object {
        internal fun splitMainAndLast(str: String) : Pair<String, String> {
            val idx = str.indexOfLast { it == '.' || it == '[' }

            return if (idx == -1) {
                str to ""
            } else {
                if (str[idx] == '.') {
                    str.substring(0 until idx) to str.substring((idx + 1) until str.length)
                } else {
                    // we include the [, but not the .
                    str.substring(0 until idx) to str.substring(idx  until str.length)
                }
            }
        }
    }

    fun get(): Any? {
        return getter()
    }

    fun set(v: Any?) {
        setter(v)
    }

//    init {
//        if (twoWayBinding && ! pattern.contains('.')) {
//            Noto.warn("Two way bindings currently require a A.B style binding with a parent and field part")
//        }
//    }

    fun update(ctx: BindingContext) {
        update(ctx, { _, _ -> }, { _, _ -> }, { _, _ -> }, warnOnAbsence = false)
    }

    fun update(ctx: BindingContext, receiveFn: (KType, Any?) -> Unit, newSetterFn: (KType, (Any?) -> Unit) -> Unit, newGetterFn: (KType, () -> Any?) -> Unit = { _, _ -> }, warnOnAbsence: Boolean = true) {
        update(ctx, patternString, mainPatternSection, finalSection, receiveFn, newSetterFn, newGetterFn, warnOnAbsence)
    }

    fun update(
        ctx: BindingContext,
        pattern: String,
        mainPattern: String,
        finalPattern: String,
        receiveFn: (KType, (Any?)) -> Unit,
        newSetterFn: (KType, (Any?) -> Unit) -> Unit,
        newGetterFn: (KType, () -> Any?) -> Unit = { _, _ -> },
        warnOnAbsence: Boolean = true,
    ) {
        if (! ctx.isDirty(mainPattern)) {
            return
        }

        val fullResolution = ctx.resolve(pattern, followPointers = false)
        if (fullResolution == null) {
           val mainResolution = ctx.resolve(mainPattern, followPointers = false)
            if (mainResolution is BindingPointer) {
                return update(
                    ctx,
                    mainResolution.targetPattern + finalPattern,
                    mainResolution.targetPattern,
                    finalPattern,
                    receiveFn,
                    newSetterFn,
                    newGetterFn
                )
            }
        }

        if (fullResolution is BindingPointer) {
            val (m,f) = splitMainAndLast(fullResolution.targetPattern)
            return update(
                ctx,
                fullResolution.targetPattern,
                m,
                f,
                receiveFn,
                newSetterFn,
                newGetterFn
            )
        }

        if (twoWayBinding) {
            // two branches, one for the PropertyReference case where we've bound a helper that contains
            // the getter and setter for something explicitly. The other is for the A.B binding case where
            // there must be an object bound at A with property B that we can use reflection to determine
            // access for. This would all be much easier if we had value references as a native thing :\
            if (fullResolution is PropertyReference<*>) {
                val bound = fullResolution.getter()
                if (bound != lastBoundValue) {
                    getter = fullResolution.getter
                    @Suppress("UNCHECKED_CAST")
                    setter = fullResolution.setter as ((Any?) -> Unit)
                    type = fullResolution.parameterType
                    newSetterFn(fullResolution.parameterType, setter)
                    newGetterFn(fullResolution.parameterType, getter)
                }
            } else {
                val bound = ctx.resolve(mainPattern)
                if (bound !== lastBoundValue) {
                    lastBoundValue = bound
                    if (bound != null) {
                        if (finalPattern.endsWith(']')) {
                            if (! mainPattern.contains('.')) {
                                return Noto.warn("property bindings against lists requires at least one level of nesting (i.e. foo.bar[1]) to determine mutability of list, pattern is $pattern ($mainPattern, $finalPattern)")
                            }
                            val (mainM1Pattern, mainFinalPattern) = splitMainAndLast(mainPattern)
                            val mainM1Resolved = ctx.resolve(mainM1Pattern) ?: return Noto.warn("no binding found for main m1 $pattern, $mainM1Pattern, $mainFinalPattern")

                            val listProp = mainM1Resolved::class.declaredMemberProperties.find { it.name == mainFinalPattern }
                            if (listProp == null) {
                                Noto.warn("two way binding found no property at expected pattern location: $pattern, $mainM1Pattern, $mainFinalPattern")
                            } else {
                                val index = finalPattern.substring(1 until finalPattern.length - 1).toIntOrNull() ?: return Noto.warn("non-int in [] access for prop binding: $finalPattern")
                                if (listProp.returnType.isSubtypeOf(typeOf<List<*>>())) {
                                    val listPropGetter = listProp.getter
                                    val listPropGetterInstParam = listPropGetter.instanceParameter ?: return Noto.warn("no instance param for getter? $pattern")

                                    val listget = List<*>::get
                                    getter = {
                                        val raw = listPropGetter.callBy(mapOf(listPropGetterInstParam to mainM1Resolved))
                                        listget.callBy(mapOf(listget.instanceParameter!! to raw, listget.parameters[1] to index))
                                    }

                                    if (listProp.returnType.isSubtypeOf(typeOf<MutableList<*>>())) {
                                        val listset = MutableList<*>::set
                                        setter = { v ->
                                            val raw = listPropGetter.callBy(mapOf(listPropGetterInstParam to bound))
                                            listset.callBy(mapOf(listset.instanceParameter!! to raw, listset.parameters[1] to index, listset.parameters[2] to v))
                                        }
                                    } else {
                                        val listPropSetter = (listProp as? KMutableProperty<*>)?.setter ?: return Noto.warn("non-mutable property with non-mutable list in prop binding: $pattern")
                                        val listPropSetterInstParam = listPropSetter.instanceParameter ?: return Noto.warn("setter with no instance param? $pattern")

                                        setter = { v ->
                                            val raw = listPropGetter.callBy(mapOf(listPropGetterInstParam to mainM1Resolved)) as List<*>
                                            val replaced = raw.replaceIndex(index, v)
                                            listPropSetter.callBy(mapOf(listPropSetterInstParam to mainM1Resolved, listPropSetter.parameters[1] to replaced))
                                        }
                                    }

                                    type = listProp.returnType.arguments[0].type!!
                                    newSetterFn(type, setter)
                                    newGetterFn(type, getter)
                                } else {
                                    Noto.warn("two way binding hitting an index on a non-list")
                                }
                            }
                        } else {
                            val rawProp = bound::class.declaredMemberProperties.find { it.name == finalPattern }
                            val mutProp = rawProp as? KMutableProperty<*>
                            if (rawProp != null && mutProp == null) {
                                Noto.warn("two way binding found a non-mutable property: $finalPattern on base binding of $mainPattern")
                            } else if (mutProp != null) {
                                val access = extractGetterAndSetter(mutProp)
                                if (access != null) {
                                    val (propGetter, getterInstanceParam, propSetter, setterInstanceParam) = access
                                    setter = { v -> propSetter.callBy(mapOf(setterInstanceParam to bound, propSetter.parameters[1] to v)) }
                                    getter = { propGetter.callBy(mapOf(getterInstanceParam to bound)) }
                                    type = propGetter.returnType
                                    newSetterFn(propSetter.parameters[1].type, setter)
                                    newGetterFn(propGetter.returnType, getter)
                                } else {
                                    Noto.warn("two way binding could not set up access to property?")
                                }
                            } else {
                                if (warnOnAbsence) {
                                    Noto.warn("two way binding could not find appropriate property: $finalPattern on base binding of $mainPattern")
                                }
                            }
                        }
                    }
                }
            }
        }

        fullResolution.let { bound ->
            if (bound is PropertyReference<*>) {
                val v = bound.getter()
                receiveFn(type, v)
            } else {
                if (lastFullResolution != bound || ctx.forceUpdate) {
                    receiveFn(type, bound)
                    lastFullResolution = bound
                }
            }
        }

    }

    data class GettersAndSetters(val getter : KProperty.Getter<*>, val getterInstanceParam: KParameter, val setter : KMutableProperty.Setter<*>, val setterInstanceParam : KParameter)
    internal fun extractGetterAndSetter(prop: KProperty<*>) : GettersAndSetters? {
        val mutProp = (prop as? KMutableProperty<*>)
        if (mutProp == null) {
            Noto.warn("non-mutable property encountered when setting up two way binding : ${prop.name}")
            return null
        }
        return GettersAndSetters(
                getter = mutProp.getter,
                setter = mutProp.setter,
                getterInstanceParam = mutProp.getter.instanceParameter ?: return null,
                setterInstanceParam = mutProp.setter.instanceParameter ?: return null,
            )

    }

    fun copy(): PropertyBinding {
        return PropertyBinding(patternString, twoWayBinding)
    }

    fun currentValue(): Any? {
        return lastBoundValue
    }

    fun createMemberBinding(member: KCallable<*>) : PropertyBinding {
        return PropertyBinding(patternString + "." + member.name, twoWayBinding)
    }
}

//inline fun <reified T> patternBindable(pattern : String) : Bindable<T> {
//    return object : Bindable<T> {
//        override fun invoke(): T {
//
//            TODO("Not yet implemented")
//        }
//
//        override fun update(ctx: BindingContext): Boolean {
//            TODO("Not yet implemented")
//        }
//    }
//}


//data class PatternBindable<T>(val pattern: String, var value : T) : Bindable<T> {
//    override fun invoke(): T {
//        return value
//    }
//
//    override fun update(ctx: BindingContext) : Boolean {
//        val newValue = ctx.resolve(pattern)
//        if (value != newValue) {
//            value = newValue
//            return true
//        }
//        return false
//    }
//}




/*
                        if (finalPattern.startsWith('[') && finalPattern.endsWith(']')) {
                            val propGetter = bound::class.declaredMembers.find { it.name == "get" }
                            val propSetter = bound::class.declaredMembers.find { it.name == "set" }
                            if (propGetter == null || propSetter == null) {
                                Noto.warn("two way binding found a non-indexable property $finalPattern on base binding of $mainPattern")
                            } else {
                                val index = finalPattern.substring(1, finalPattern.length - 1).toIntOrNull()
                                if (index == null) {
                                    Noto.warn("two way binding using invalid indexing $finalPattern")
                                } else {
                                    val getterInstanceParam = propGetter.instanceParameter
                                    val setterInstanceParam = propSetter.instanceParameter

                                    if (getterInstanceParam == null || setterInstanceParam == null) {
                                        Noto.warn("invalid instance params?")
                                    } else {
                                        getter = { propGetter.callBy(mapOf(getterInstanceParam to bound, propSetter.parameters[1] to index)) }
                                        setter = { v -> propSetter.callBy()}
                                    }
                                }
                            }
                        }
 */