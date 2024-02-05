package arx.core

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.time.Instant


@JvmInline
value class Attributes(val kv: IntArray) {

}

data class Error(val stackTrace: Array<StackTraceElement>, val attributes: Attributes) {

}

data class NotoEvent(val timestamp: Instant, val attributes: Attributes) {
    fun attributeValue(keyIndex: Int): Any? {
        var i = 0
        while (i < attributes.kv.size) {
            if (attributes.kv[i] == keyIndex) {
                return Noto.values[attributes.kv[i + 1]]
            }
            i += 2
        }
        return null
    }
}


interface EventCondition {
    fun test(event: NotoEvent): Boolean
}

class AttributeCondition(val keyIndex: Int, val condition: (Any?) -> Boolean) : EventCondition {
    override fun test(event: NotoEvent): Boolean {
        return condition(event.attributeValue(keyIndex))
    }
}

interface DataSelector {
    fun identifier() : String

    fun select(event: NotoEvent)

    fun result(): Any?
}

class AccumulatingAttributeSelector(val keyIndex: Int, val identifier: String, startValue: Any?, val merge: (Any, Any) -> Any, val finisher: ((Any?) -> Any?)? = null) : DataSelector {
    var accumulator: Any? = startValue

    override fun select(event: NotoEvent) {
        event.attributeValue(keyIndex)?.let {
            val tmp = accumulator
            accumulator = if (tmp == null) {
                it
            } else {
                merge(tmp, it)
            }
        }
    }

    override fun result(): Any? {
        return (finisher ?: { a -> a })(accumulator)
    }

    override fun identifier(): String {
        return identifier
    }
}

class TypedAccumulatingAttributeSelector<T>(val keyIndex: Int, val identifier: String, startValue: T, val merge: (T, Any) -> Unit, val finisher: (T) -> Any?) : DataSelector {
    var accumulator: T = startValue

    override fun select(event: NotoEvent) {
        event.attributeValue(keyIndex)?.let {
            merge(accumulator, it)
        }
    }

    override fun result(): Any? {
        return finisher(accumulator)
    }

    override fun identifier(): String {
        return identifier
    }
}

class RawAttributeSelector(val keyIndex: Int, val identifier: String) : DataSelector {
    val values: MutableList<Any> = mutableListOf()
    override fun select(event: NotoEvent) {
        event.attributeValue(keyIndex)?.let {
            values.add(it)
        }
    }

    override fun result(): Any {
        return values
    }

    override fun identifier(): String {
        return identifier
    }
}

data class QueryResults(val results: Map<String, Any?>, val matchedEvents: Int)

data class EventQuery(val type: String, var conditions: MutableList<EventCondition>, var selectors: MutableList<DataSelector>) {

    fun run() : QueryResults {
        val results = mutableMapOf<String, Any?>()
        var count = 0

        for (event in Noto.eventsByType[type]?:listOf()) {
            var breaker = false
            for (cond in conditions) {
                if (!cond.test(event)) {
                    breaker = true
                    break
                }
            }
            if (!breaker) {
                count += 1
                for (selector in selectors) {
                    selector.select(event)
                }
            }
        }

        for (selector in selectors) {
            var identifier = selector.identifier()
            var identifierCounter = 0
            while (results.contains(identifier))  {
                identifierCounter += 1
                identifier = "${selector.identifier()}_$identifierCounter"
            }
            results[identifier] = selector.result()
        }

        return QueryResults(results, count)
    }
}

class EventQueryBuilder(type: String) {
    var eventQuery = EventQuery(type, mutableListOf(), mutableListOf())

    fun keyIndex(str: String): Int {
        return Noto.keyIds.getInt(str)
    }

    fun select(attribute: String): EventQueryBuilder {
        eventQuery.selectors.add(RawAttributeSelector(keyIndex(attribute), attribute))
        return this
    }

    private fun toNumeric(a: Any): Double {
        when (a) {
            is Int -> return a.toDouble()
            is Short -> return a.toDouble()
            is Byte -> return a.toDouble()
            is Long -> return a.toDouble()
            is Boolean -> {
                return if (a) {
                    1.0
                } else {
                    0.0
                }
            }
            is Float -> return a.toDouble()
            is Double -> return a
            is Instant -> return a.toEpochMilli().toDouble()
            else -> return Double.NaN
        }
    }

    fun max(attribute: String, identifier: String? = null): EventQueryBuilder {
        eventQuery.selectors.add(AccumulatingAttributeSelector(keyIndex(attribute), identifier ?: "max($attribute)", null, { a, b ->
            if (toNumeric(a) > toNumeric(b)) {
                a
            } else {
                b
            }
        }))
        return this
    }

    fun min(attribute: String): EventQueryBuilder {
        eventQuery.selectors.add(AccumulatingAttributeSelector(keyIndex(attribute), "min($attribute)", null, { a, b ->
            if (toNumeric(a) < toNumeric(b)) {
                a
            } else {
                b
            }
        }))
        return this
    }

    fun sum(attribute: String): EventQueryBuilder {
        eventQuery.selectors.add(AccumulatingAttributeSelector(keyIndex(attribute), "sum($attribute)", null, { a, b -> toNumeric(a) + toNumeric(b) }))
        return this
    }

    private data class AverageAccum(var sum: Double, var count: Int)
    fun average(attribute: String): EventQueryBuilder {
        eventQuery.selectors.add(TypedAccumulatingAttributeSelector(keyIndex(attribute), "average($attribute)", AverageAccum(0.0, 0),
            { a, b -> a.sum += toNumeric(b); a.count += 1 },
            { a -> if (a.count == 0) { null } else { a.sum / a.count.toDouble() } }
        ))
        return this
    }

    fun median(attribute: String): EventQueryBuilder {
        eventQuery.selectors.add(TypedAccumulatingAttributeSelector(keyIndex(attribute), "median($attribute)", mutableListOf<Any?>(),
            { a, b -> a.add(b) },
            { a -> {
                if (a.size > 0) {
                    a.sortBy { i -> toNumeric(i ?: Double.MIN_VALUE) }
                    a[a.size / 2]
                } else {
                    null
                }
            } }
        ))
        return this
    }

    fun run() : QueryResults {
        return eventQuery.run()
    }
}

object Noto {

    val threadColors = arrayOf(
        "\u001B[38;5;231m",
        "\u001B[38;5;102m",
        "\u001B[38;5;105m",
        "\u001B[38;5;108m",
        "\u001B[38;5;117m",
        "\u001B[38;5;145m",
        "\u001B[38;5;77m",
        "\u001B[38;5;31m",
        "\u001B[38;5;194m",
        "\u001B[38;5;223m",
        "\u001B[38;5;101m",
        "\u001B[38;5;148m"
    )
    const val warnColor = "\u001B[38;5;184m"
    const val errColor = "\u001B[38;5;196m"

    val keyIds = Object2IntOpenHashMap<String>().apply {
        defaultReturnValue(-1)
    }
    val keys = mutableListOf<String>()
    val values = mutableListOf<Any?>()

    val eventsByType = mutableMapOf<String, MutableList<NotoEvent>>()
    val errors = mutableListOf<Error>()

    var tabbing = ThreadLocal.withInitial { 0 }


    fun query(type: String): EventQueryBuilder {
        return EventQueryBuilder(type)
    }

    private fun toAttributes(raw: Map<String, Any?>): Attributes {
        val attr = Attributes(IntArray(raw.size * 2))
        var ai = 0
        for ((k, v) in raw) {
            var ki = keyIds.getInt(k)
            if (ki == -1) {
                ki = keys.size
                keyIds.put(k, ki)
                keys.add(k)
            }
            val vi = values.size
            values.add(v)
            attr.kv[ai] = ki
            attr.kv[ai + 1] = vi
            ai += 2
        }
        return attr
    }

    @Synchronized
    fun recordEvent(type: String, attributes: Map<String, Any?>) {
        val events = eventsByType.getOrPut(type) { mutableListOf() }

        val attr = toAttributes(attributes)

        events.add(NotoEvent(Instant.now(), attr))
    }

    @Synchronized
    fun recordError(message: String, attributes: Map<String, Any?> = mapOf()) {
        val trace = Thread.currentThread().stackTrace
        val stack = trace.sliceArray(2 until trace.size)
        errors.add(Error(stack, toAttributes(attributes)))
        err("$message\n\t$attributes")
    }

    fun setColor(color : String) {
        print(color)
    }

    fun info(str: String) {
        setColor(threadColors[(Thread.currentThread().id % threadColors.size.toLong()).toInt()])
        for (i in 0 until tabbing.get()) {
            print("\t")
        }
        println(str)
    }

    fun indent() {
        tabbing.set(tabbing.get() + 1)
    }

    fun unindent() {
        tabbing.set((tabbing.get() - 1).max(0))
    }

    fun warn(str: String) {
        setColor(warnColor)
        println("[warn] $str")
    }

    fun err(str: String) {
        setColor(errColor)
        println("[error] $str")
    }

    fun <T> errAndReturn(str: String, t : T) : T {
        setColor(errColor)
        println("[error] $str")
        return t
    }
}