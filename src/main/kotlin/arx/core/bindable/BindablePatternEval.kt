package arx.core.bindable

import arx.core.BindingContext
import arx.core.Noto
import arx.core.pop
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.text.StringBuilder


object BindablePatternEval {


    interface Node {
        fun evaluate(ctx: BindingContext) : Any?
        fun isDirty(ctx: BindingContext): Boolean
        fun constantEvaluation(): Any? {
            return null
        }
    }

    data class Lookup(val pattern: String, val invert: Boolean = false, val checkPresence: Boolean = false) : Node {
        override fun evaluate(ctx: BindingContext): Any? {
            var result = ctx.resolve(pattern)
            if (checkPresence) {
                result = result != null
            }
            if (invert) {
                result = ! anyToTruthy(result)
            }
            return result
        }

        override fun isDirty(ctx: BindingContext): Boolean {
            return ctx.isDirty(pattern)
        }
    }

    data class OrElse(val test: Node, val fallback: Node) : Node {
        override fun evaluate(ctx: BindingContext): Any? {
            val first = test.evaluate(ctx)

            var useFallback = false
            if (first == null) {
                useFallback = true
            }
            (first as? Boolean)?.let { b ->
                if (! b) {
                    useFallback = true
                }
            }

            if (useFallback) {
                return fallback.evaluate(ctx)
            } else {
                return first
            }
        }

        override fun isDirty(ctx: BindingContext): Boolean {
            return test.isDirty(ctx) || fallback.isDirty(ctx)
        }
    }

    fun anyToTruthy(a: Any?) : Boolean {
        return when (a) {
            is Boolean -> a
            else -> a != null
        }
    }

    data class Ternary(val test: Node, val ifTrue: Node, val ifFalse: Node) : Node {
        override fun evaluate(ctx: BindingContext): Any? {
            val truthy = anyToTruthy(test.evaluate(ctx))

            return if (truthy) {
                ifTrue.evaluate(ctx)
            } else {
                ifFalse.evaluate(ctx)
            }
        }

        override fun isDirty(ctx: BindingContext): Boolean {
            return test.isDirty(ctx) || ifTrue.isDirty(ctx) || ifFalse.isDirty(ctx)
        }
    }


    enum class Operator {
        GTE,
        LTE,
        GT,
        LT,
        EQ,
        NEQ,
        AND,
        OR,
        CONS
    }

    data class BinOp(val left: Node, val right: Node, val operator: Operator) : Node {
        override fun evaluate(ctx: BindingContext): Any {
            val l = left.evaluate(ctx)
            val r = right.evaluate(ctx)

            return when (operator) {
                Operator.GTE, Operator.LTE, Operator.GT, Operator.LT -> {
                    val ln = (l as? Number)?.toDouble() ?: (l.toString().toDoubleOrNull())
                    val rn = (r as? Number)?.toDouble() ?: (r.toString().toDoubleOrNull())

                    if (ln == null || rn == null) {
                        false
                    } else {
                        when (operator) {
                            Operator.GTE -> ln >= rn
                            Operator.LTE -> ln <= rn
                            Operator.GT -> ln > rn
                            Operator.LT -> ln < rn
                            else -> throw IllegalStateException("Unreachable")
                        }
                    }
                }
                Operator.EQ, Operator.NEQ -> {
                    val invert = operator == Operator.NEQ

                    val raw = if (left is Lookup && right is Lookup) {
                        l == r
                    } else {
                        if (l is Number || r is Number) {
                            val ln = (l as? Number)?.toDouble() ?: (l.toString().toDoubleOrNull())
                            val rn = (r as? Number)?.toDouble() ?: (r.toString().toDoubleOrNull())

                            ln != null && rn != null && (ln - rn).absoluteValue < 0.000001
                        } else {
                            l.toString() == r.toString()
                        }
                    }

                    if (invert) {
                        !raw
                    } else {
                        raw
                    }
                }
                Operator.AND, Operator.OR -> {
                    val lb = (l as? Boolean) ?: (l != null)
                    val rb = (r as? Boolean) ?: (r != null)
                    if (operator == Operator.AND) {
                        lb && rb
                    } else {
                        lb || rb
                    }
                }
                Operator.CONS -> {
                    // if it's a string, concat them together that way
                    if (l is String || r is String) {
                        l.toString() + r.toString()
                    } else if (l is List<*> && r is List<*>) {
                        l + r
                    } else {
                        Noto.warn("BindablePatternEval used cons (::) with not obviously concatenable values: $l, $r")
                        l.toString() + r.toString()
                    }
                }
            }
        }

        override fun isDirty(ctx: BindingContext): Boolean {
            return left.isDirty(ctx) || right.isDirty(ctx)
        }
    }

    data class Value(val value: Any?) : Node {
        override fun evaluate(ctx: BindingContext): Any? {
            return value
        }

        override fun isDirty(ctx: BindingContext): Boolean {
            return false
        }

        override fun constantEvaluation(): Any? {
            return value
        }
    }

    data object Placeholder : Node {
        override fun evaluate(ctx: BindingContext): Any? {
            return null
        }

        override fun isDirty(ctx: BindingContext): Boolean {
            return false
        }
    }


    private val passthroughValueParser : (String) -> Any? = { it }

    fun tokenToNode(nodes: MutableList<Node>, tokens: List<String>, index: AtomicInteger, valueParser: (String) -> Any?): Node? {
        if (tokens.size <= index.get()) {
            return null
        }

        return when (val token = tokens[index.get()]) {
            "<","<=",">",">=","==","!=","&&","||","::" -> {
                val op = when (token) {
                    "<" -> Operator.LT
                    "<=" -> Operator.LTE
                    ">" -> Operator.GT
                    ">=" -> Operator.GTE
                    "==" -> Operator.EQ
                    "!=" -> Operator.NEQ
                    "&&" -> Operator.AND
                    "||" -> Operator.OR
                    "::" -> Operator.CONS
                    else -> throw IllegalStateException("Invalid op $token")
                }
                index.incrementAndGet()
                BinOp(nodes.pop(), tokenToNode(nodes, tokens, index, passthroughValueParser) ?: return null, op)
            }
            "?:" -> {
                index.incrementAndGet()
                OrElse(nodes.pop(), tokenToNode(nodes, tokens, index, passthroughValueParser) ?: return null)
            }
            "?" -> {
                if (index.get() + 2 >= tokens.size || tokens[index.get() + 2] != ":") {
                    Noto.err("Invalid ternary (index ${index.get()}: $tokens")
                    return null
                }
                index.incrementAndGet()
                val ifTrue = tokenToNode(nodes, tokens, index, passthroughValueParser) ?: return null

                index.addAndGet(2) // skip the :
                val ifFalse = tokenToNode(nodes, tokens, index, passthroughValueParser) ?: return null

                Ternary(nodes.pop(), ifTrue, ifFalse)
            }
            else -> {
                if (token.startsWith("%(")) {
                    var interior = token.substring(2, token.length - 1)
                    var invert = false
                    var checkPresence = false
                    while (interior.isNotEmpty() && (interior[0] == '!' || interior[0] == '?')) {
                        when(interior[0]) {
                            '!' -> invert = true
                            '?' -> checkPresence = true
                        }
                        interior = interior.substring(1)
                    }
                    Lookup(interior, invert = invert, checkPresence = checkPresence)
                } else {
                    if (token.contains('\\')) {
                        val ret = StringBuilder()
                        var i = 0
                        while (i < token.length) {
                            if (token[i] == '\\') {
                                i++
                            }
                            ret.append(token[i])
                            i++
                        }
                        Value(valueParser(ret.toString()))
                    } else {
                        Value(valueParser(token))
                    }
                }
            }
        }
    }

    fun parse(str: String, valueParser: (String) -> Any?) : Node {
        val ret = parseOpt(str, valueParser)
        if (ret == null) {
            throw IllegalArgumentException("Invalid bindable pattern: $str")
        }
        return ret
    }

    fun parseOpt(str: String, valueParser: (String) -> Any?) : Node? {
        var containsLookup = false
        for (i in 0 until str.length) {
            if (str[i] == '%' && (i == 0 || str[i - 1] != '\\') && (i != str.length - 1 && str[i + 1] == '(')) {
                containsLookup = true
                break
            }
        }

        if (! containsLookup) {
            return Value(valueParser(str))
        }

        val tokens = BindablePatternEvalTokenizer.tokenize(str)

        val ret = mutableListOf<Node>()

        val index = AtomicInteger(0)
        while (index.get() < tokens.size) {
            ret.add(tokenToNode(ret, tokens, index, valueParser) ?: return null)
            index.incrementAndGet()
        }

        if (ret.size != 1) {
            return null
        }

        return ret.first()
    }
}


internal object BindablePatternEvalTokenizer {

    fun tokenize(str: String): List<String> {
        var i = 0
        val ret = mutableListOf<String>()
        val accum = StringBuilder()


        fun flush() {
            if (accum.isNotEmpty()) {
                val tmpStr = accum.trim().toString()
                if (tmpStr.isNotEmpty()) {
                    ret.add(tmpStr)
                }
                accum.clear()
            }
        }

        fun take(t: (Char) -> Boolean, includeFirst: Boolean, includeLast: Boolean) {
            flush()

            var first = true
            var escaped = false
            while (i < str.length) {
                val c = str[i]

                if (c == '\\' && ! escaped) {
                    escaped = true
                    i++
                    continue
                } else if (escaped) {
                    accum.append(c)
                    escaped = false
                    i++
                    continue
                }

                if (!first && t(c)) {
                    if (includeLast) {
                        accum.append(c)
                    } else {
                        i--
                    }
                    break
                } else {
                    if (!first || includeFirst) {
                        accum.append(c)
                    }
                }

                first = false
                i++
            }

            flush()
        }

        fun takeOne() {
            flush()
            ret.add("${str[i]}")
        }

        fun takeTo(t: (Char) -> Boolean) {
            take(t, true, true)
        }

        fun takeUntil(t: (Char) -> Boolean) {
            take(t, true, false)
        }


        var escaped = false
        while (i < str.length) {
            val c = str[i]

            if (escaped) {
                accum.append(c)
                escaped = false
            } else {
                when (c) {
                    '%' -> takeTo { it == ')' }
                    '[' -> takeTo { it == ']' }
                    '?' -> takeUntil { it != ':' }
                    ':' -> takeUntil { it != ':' }
                    '!', '>', '<', '=' -> takeUntil { it != '=' }
                    '&' -> takeUntil { it != '&' }
                    '|' -> takeUntil { it != '|' }
                    '"' -> {
                        take({ it == '"' }, includeFirst = false, includeLast = false)
                        i++
                    }
                    '\\' -> {
                        escaped = true
                        accum.append('\\')
                    }
                    else -> accum.append(c)
                }
            }
            i++
        }

        flush()

        return ret
    }
}