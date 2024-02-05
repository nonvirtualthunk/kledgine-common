package arx.game.core

import arx.core.*
import arx.core.Taxonomy.Sentinel
import arx.core.Taxonomy.UnknownThing
import arx.core.Taxonomy.taxon
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValue
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension


@JvmInline
value class LibraryKey(val i: Int) {

}

abstract class Library<T> {
    protected val values = mutableListOf<T>()
    protected val valuesByTaxon = mutableMapOf<Taxon, T>()


    protected fun add(k: Taxon, v: T) {
        values.add(v)
        valuesByTaxon[k] = v
        if (v is Identifiable) {
            v.identity = k
        }
    }

    operator fun get(t : Taxon, warnOnAbsence: Boolean = true) : T? {
        val raw = valuesByTaxon[t]
        if (raw == null && t != UnknownThing && t != Sentinel && warnOnAbsence) {
            Noto.warn("Attempt to access unknown value from library $this : $t")
        }
        return raw
    }

    fun contains(t: Taxon): Boolean {
        return valuesByTaxon.containsKey(t)
    }

    fun register(k: Taxon, v : T) : Taxon {
        this.add(k, v)
        return k
    }

    operator fun iterator() : Iterator<Map.Entry<Taxon, T>> {
        return valuesByTaxon.iterator()
    }

    fun keys() : Set<Taxon> {
        return valuesByTaxon.keys
    }

    fun values(): Collection<T> {
        return valuesByTaxon.values
    }

    fun toMap() : Map<Taxon, T> {
        return valuesByTaxon
    }

    fun clear() {
        values.clear()
        valuesByTaxon.clear()
    }

    inline fun <reified U : T> serialize() : String {
        val grouped = toMap().toList().groupBy { it.first.namespace }
        val final = grouped.mapValues { it.value.toMap().mapKeys { t -> t.key.name } }
        return serializeToConfig<Map<String, Map<String, U>>>(final as Map<String, Map<String, U>>)
    }

    inline fun <reified U : T> serializeToFile(writeToPath: String, copyToBackup: Boolean = true) {
        val path = Path.of(writeToPath)
        if (copyToBackup) {
            if (path.exists()) {
                val filename = path.fileName.nameWithoutExtension
                val relativePath = if (writeToPath.contains("src/main/resources/")) {
                    writeToPath.substringAfter("src/main/resources/").substringBefore(filename)
                } else {
                    writeToPath
                }
                val backupPath = Path.of("backup/$relativePath/${filename}_${System.currentTimeMillis() / 1000}.sml")
                Files.createDirectories(backupPath.parent)
                Files.copy(path, backupPath)
            } else {
                Noto.info("not copying on file serialization because no original exists: $writeToPath")
            }
        }

        if (! path.exists()) {
            path.toFile().parentFile.mkdirs()
        }

        Files.writeString(Path.of(writeToPath), serialize<U>())
    }

    inline fun <reified U : T> deserializeFromFile(readFromPath: String, clearBeforeLoad: Boolean = true) {
        if (clearBeforeLoad) {
            clear()
        }
        var path = Path.of(readFromPath)
        if (! path.exists()) {
            path = Path.of(Resources.pickPath(readFromPath))
            if (! path.exists()) {
                Noto.warn("${this.javaClass.simpleName} could not find file $path, skipping")
                return
            }
        }
        val str = Files.readString(path)
        val conf = ConfigFactory.parseString(str)
        val parsed = Hocon { useArrayPolymorphism = true }.decodeFromConfig<Map<String, Map<String, U>>>(conf)

        for ((n,m) in parsed) {
            for ((k,v) in m) {
                register(t(k, n), v)
            }
        }
    }
}


abstract class SimpleLibrary<T>(topLevelKey: String, confPaths: List<String>, instantiator : (ConfigValue) -> T?) : Library<T>() {

    init {
        for (path in confPaths) {
            for ((k,v) in Resources.config(path)[topLevelKey]) {
                instantiator(v)?.let { newV ->
                    add(taxon("$topLevelKey.$k"), newV)
                }
            }
        }
    }
}


abstract class SerializedLibrary<T>(confPaths: List<String>, deserialize: (Config) -> Map<String,Map<String, T>>) : Library<T>() {
    init {
        for (path in confPaths) {
            val conf = Resources.configOpt(path)
            if (conf == null) {
                Noto.warn("${this.javaClass.simpleName} could not find file $path, skipping")
                continue
            }

            val parsed = deserialize(conf)

            for ((n,m) in parsed) {
                for ((k,v) in m) {
                    register(t(k, n), v)
                }
            }
        }
    }
}