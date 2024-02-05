package arx.core

import arx.engine.Entity
import arx.engine.EntityData
import arx.engine.WorldT
import com.typesafe.config.*
import io.github.config4k.*
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import kotlinx.serialization.hocon.encodeToConfig
import java.util.*


interface FromConfig {
    fun readFromConfig(cv: ConfigValue)
}

fun <T : FromConfig> T.readFromConfig(cv: ConfigValue?) {
    if (cv != null) {
        readFromConfig(cv)
    }
}

interface FromConfigCreator<T> {
    fun createFromConfig(cv: ConfigValue?): T?

    operator fun invoke(cv: ConfigValue?): T? {
        return createFromConfig(cv)
    }
}


operator fun Config?.get(path: String): ConfigValue? {
    if (this == null) {
        return null
    }

    return if (this.hasPath(path)) {
        this.getValue(path)
    } else {
        null
    }
}

operator fun ConfigValue?.get(path: String): ConfigValue? {
    if (this == null) {
        return null
    }

    return when (this.valueType()) {
        ConfigValueType.OBJECT -> {
            val raw = (this as ConfigObject).get(path)
            if (raw?.valueType() == ConfigValueType.NULL) {
                return null
            } else {
                return raw
            }
        }
        else -> null
    }
}

operator fun ConfigValue?.get(vararg paths: String) : ConfigValue? {
    for (path in paths) {
        val c = this[path]
        if (c != null) {
            return c
        }
    }
    return null
}

fun ConfigValue?.asInt(): Int? {
    return when (val v = this?.unwrapped()) {
        is Int -> v
        is Long -> {
            if (v > Int.MAX_VALUE || v < Int.MIN_VALUE) {
                Noto.err("Converting config long to int, truncating value: $v")
            }
            v.toInt()
        }
        is Double -> v.toInt()
        else -> null
    }
}

fun ConfigValue?.asFloat(): Float? {
    return when (val v = this?.unwrapped()) {
        is Int -> v.toFloat()
        is Long -> v.toFloat()
        is Double -> v.toFloat()
        else -> null
    }
}

fun ConfigValue?.asStr(): String? {
    return if (this != null && ! this.isObject() && ! this.isList()) {
        this.unwrapped().toString()
    } else {
        null
    }
}

fun ConfigValue?.asBool(): Boolean? {
    return when (val v = this?.unwrapped()) {
        is Boolean -> v
        else -> null
    }
}

fun ConfigValue?.asList(): List<ConfigValue> {
    return when (this?.valueType()) {
        ConfigValueType.LIST -> (this as ConfigList)
        null -> emptyList()
        else -> listOf(this)
    }
}

/**
 * Returns the config value as an unwrapped list, with each
 * value being its most specific type, if possible (i.e. int, then float, then bool, then str)
 * Object and list types are ignored and not dealt with recursively
 */
fun ConfigValue?.asPrimitiveList(): List<Any> {
    return this.asList().mapNotNull {
        if (it.isInt()) {
            it.asInt()
        } else if (it.isNum()) {
            it.asFloat()
        } else if (it.isBool()) {
            it.asBool()
        } else if (it.isStr()) {
            it.asStr()
        } else {
            null
        }
    }
}

fun ConfigValue?.asPrimitiveValue(): Any? {
    return if (isInt()) {
            asInt()
        } else if (isNum()) {
            asFloat()
        } else if (isBool()) {
            asBool()
        } else if (isStr()) {
            asStr()
        } else {
            null
        }
}

fun ConfigValue?.asVec3i() : Vec3i? {
    if (this.isList()) {
        val l = this.asList()
        if (l.size == 3) {
            val x = l.getOrNull(0).asInt()
            val y = l.getOrNull(1).asInt()
            val z = l.getOrNull(2).asInt()
            if (x != null && y != null && z != null) {
                return Vec3i(x,y,z)
            } else {
                Noto.warn("asVec3i(...) called with 3 element list but non-integer elements : $this")
            }
        }
    }
    return null
}

fun ConfigValue?.asVec2i() : Vec2i? {
    if (this.isList()) {
        val l = this.asList()
        if (l.size == 2) {
            val x = l.getOrNull(0).asInt()
            val y = l.getOrNull(1).asInt()
            if (x != null && y != null) {
                return Vec2i(x,y)
            } else {
                Noto.warn("asVec2i(...) called with 2 element list but non-integer elements : $this")
            }
        }
    }
    return null
}


fun ConfigValue?.asTaxonList(namespaceHint : String? = null): List<Taxon> {
    return this.asList().mapNotNull { it.asStr() }.map { t(it, namespaceHint) }
}

fun ConfigValue?.asTaxonMap(namespaceHint : String? = null): Map<Taxon, Int> {
    if (this == null) {
        return emptyMap()
    }

    val ret = mutableMapOf<Taxon, Int>()
    for ((k,v) in this) {
        v.asInt()?.expectLet {
            ret[t(k, namespaceHint)] = it
        }
    }
    return ret
}

fun ConfigValue?.isList(): Boolean {
    return when (this?.valueType()) {
        ConfigValueType.LIST -> true
        else -> false
    }
}

fun ConfigValue?.forEach(f: (String, ConfigValue) -> Unit) {
    if (this.isObject()) {
        (this as ConfigObject).forEach(f)
    }
}

fun <K, V> ConfigValue?.map(f: (String, ConfigValue) -> Pair<K, V>?): Map<K, V> {
    return if (this == null) {
        mapOf()
    } else if (this.isObject()) {
        val ret = mutableMapOf<K, V>()
        (this as ConfigObject).forEach { k, v ->
            val mapped = f(k, v)
            if (mapped != null) {
                ret[mapped.first] = mapped.second
            }
        }
        ret
    } else {
        mapOf()
    }
}

fun ConfigValue?.filterKeys(f: (String) -> Boolean) : ConfigValue? {
    if (this is ConfigObject) {
        val map = HashMap(this).filterKeys(f)
        return ConfigFactory.parseMap(map).root()
    } else {
        Noto.warn("filterKeys() on a non-object config doesn't make much sense")
        return null
    }
}

fun ConfigValue?.isObject(): Boolean {
    return when (this?.valueType()) {
        ConfigValueType.OBJECT -> true
        else -> false
    }
}

fun ConfigValue?.isStr(): Boolean {
    return this?.valueType() == ConfigValueType.STRING
}

fun ConfigValue?.isInt(): Boolean {
    if (this?.valueType() != ConfigValueType.NUMBER) { return false }
    return when (this.unwrapped()) {
        is Int, is Long -> true
        else -> false
    }
}

fun ConfigValue?.isNum(): Boolean {
    return this?.valueType() == ConfigValueType.NUMBER
}

fun ConfigValue?.isBool(): Boolean {
    return this?.valueType() == ConfigValueType.BOOLEAN
}

operator fun ConfigValue?.iterator(): Iterator<Map.Entry<String, ConfigValue>> {
    return if (isObject()) {
        (this as Map<String, ConfigValue>).iterator()
    } else {
        Collections.emptyIterator()
    }
}


inline fun <reified T> registerCustomTypeRender() {
    registerCustomType(object : CustomType {
        override fun parse(clazz: ClassContainer, config: Config, name: String): Any? {
            TODO("Not yet implemented")
        }

        override fun testParse(clazz: ClassContainer): Boolean {
            return false
        }

        override fun testToConfig(obj: Any): Boolean {
            return obj is T
        }

        override fun toConfig(obj: Any, name: String): Config {
            return ConfigFactory.parseMap(mapOf(name to obj.toString()))
        }
    })
}

inline fun <reified T> serializeToConfig(t: T) : String {
    return Hocon { useArrayPolymorphism = true }
        .encodeToConfig(t).root()
        .render(ConfigRenderOptions.defaults().setOriginComments(false).setJson(false))
}

inline fun <reified T> deserializeFromConfig(cv: Config) : T {
    return Hocon { useArrayPolymorphism = true }
        .decodeFromConfig<T>(cv)
}

object ConfigRegistration {
    var activeWorld: WorldT<EntityData>? = null

    init {
        registerCustomType(object : CustomType {
            override fun parse(clazz: ClassContainer, config: Config, name: String): Any? {
                TODO("Not yet implemented")
            }

            override fun testParse(clazz: ClassContainer): Boolean {
                return false
            }

            override fun testToConfig(obj: Any): Boolean {
                if (obj is Map<*, *>) {
                    if (obj.isNotEmpty()) {
                        return true
                    }
                }
                return false
            }

            fun Any.toConfigValue(): ConfigValue {
                val name = "dummy"
                return this.toConfig(name).root()[name]!!
            }


            override fun toConfig(obj: Any, name: String): Config {
                val thisMap = obj as Map<*, *>

                val map = thisMap
                    .mapKeys {
                        if (it.key is Entity) {
                            (it.key as Entity).toString(activeWorld)
                        } else {
                            it.key.toString()
                        }
                    }
                    .mapValues { it.value?.toConfigValue()?.unwrapped() }
                return ConfigFactory.parseMap(mapOf(name to map))
            }
        })
    }
}