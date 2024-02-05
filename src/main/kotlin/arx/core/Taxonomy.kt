package arx.core

import arx.core.Noto.err
import arx.core.Noto.warn
import arx.core.Taxonomy.UnknownThing
import arx.engine.DataType
import arx.engine.GameData
import com.typesafe.config.ConfigValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.lang.Integer.min
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

private val TaxonIDCounter = AtomicInteger(0)


interface Identifiable {
    var identity: Taxon
}

interface Named {
    var name : String
}

@Serializer(Taxon::class)
object TaxonSerializer : KSerializer<Taxon> {
    override fun deserialize(decoder: Decoder): Taxon {
        val str = decoder.decodeString()
        val sections = str.split('/')
        return t(str = sections[1], namespaceHint = sections[0])
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Taxon", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Taxon) {
        encoder.encodeString(value.namespace + "/" + value.name)
    }
}

@Serializer(TaxonRef::class)
object TaxonRefSerializer : KSerializer<TaxonRef> {
    override fun deserialize(decoder: Decoder): TaxonRef {
        val str = decoder.decodeString()
        val sections = str.split('/')
        return lt(str = sections[1], namespaceHint = sections[0])
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TaxonRef", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TaxonRef) {
        when (value) {
            is LazyTaxon -> encoder.encodeString(value.namespaceHint + "/" + value.name)
            is Taxon -> encoder.encodeString(value.namespace + "/" + value.name)
        }
    }
}

@Serializable(with = TaxonRefSerializer::class)
sealed interface TaxonRef {
    operator fun invoke() : Taxon

    fun isA(t : Taxon) : Boolean {
        return this().isA(t)
    }

    companion object {
        operator fun invoke(str: String) : TaxonRef {
            return LazyTaxon(null, str)
        }

        operator fun invoke(namespace: String, str: String) : TaxonRef {
            return LazyTaxon(namespace, str)
        }
    }
}


@Serializable(with = TaxonSerializer::class)
data class Taxon(val namespace : String, val name: String, val parents : List<Taxon> = emptyList()) : TaxonRef {
    val id = TaxonIDCounter.incrementAndGet()
    val normalizedNamespace = Taxonomy.normalizeString(namespace)
    val namespaceSegments = if (normalizedNamespace.isEmpty()) { emptyList() } else { normalizedNamespace.split('.') }

    override fun equals(other: Any?): Boolean {
        if (other is LazyTaxon) {
            return this == other.taxon
        }
        return this === other
    }

    override fun hashCode(): Int {
        return id
    }

    override fun toString(): String {
        return "Φ$name"
    }

    override fun isA(t : Taxon) : Boolean {
        if (t == this) {
            return true
        }
        for (p in parents) {
            if (p.isA(t)) {
                return true
            }
        }
        return false
    }

    fun isA(ts : String) : Boolean {
        return isA(t(ts))
    }

    override operator fun invoke(): Taxon {
        return this
    }

}

data class LazyTaxon(val namespaceHint: String?, val name: String) : TaxonRef {
    constructor(tx: Taxon) : this(tx.namespace, tx.name) {
        taxon = tx
    }

    var taxon: Taxon? = null

    override operator fun invoke() : Taxon {
        val tx = taxon
        return if (tx != null) {
            tx
        } else {
            val ret = t(name, namespaceHint = namespaceHint)
            taxon = ret
            ret
        }
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            is LazyTaxon -> {
                other() == this()
            }
            is Taxon -> {
                this() == other
            }
            else -> {
                false
            }
        }
    }

    override fun hashCode(): Int {
        return this().hashCode()
    }
}

data class ProtoTaxon(val namespace : String, val name: String, val parentIdentifiers : List<String> = emptyList())


private fun concatNamespaces(a: String, b: String) : String {
    return if (a.isEmpty()) {
        b
    } else {
        "$a.$b"
    }
}

private fun extractProtoTaxons(conf: ConfigValue, namespaceBase: String, isNestedTaxonomy: Boolean, defaultParents : List<String>, out : MutableList<ProtoTaxon>) {
    conf.forEach { k, v ->
        if (v.isObject()) {
            if (isNestedTaxonomy) {
                extractProtoTaxons(v, concatNamespaces(namespaceBase, k), isNestedTaxonomy, defaultParents, out)
            } else {
                val parents = v["isA"].asList().mapNotNull { it.asStr() }
                out.add(ProtoTaxon(namespaceBase, k, defaultParents + parents))
            }
        } else if (v.isList()) {
            out.add(ProtoTaxon(namespaceBase, k, defaultParents + v.asList().mapNotNull { it.asStr() }))
        } else {
            val parentStr = v.asStr()
            if (parentStr != null) {
                out.add(ProtoTaxon(namespaceBase, k, defaultParents + listOf(parentStr)))
            } else {
                Noto.recordError("Invalid type for taxon parent", mapOf("value" to v, "name" to k, "namespace" to namespaceBase))
            }
        }
    }
}

fun t(str: String, namespaceHint: String? = null) : Taxon {
    return Taxonomy.taxon(str, namespaceHint)
}

fun lt(str: String, namespaceHint: String? = null) : LazyTaxon {
    return LazyTaxon(namespaceHint, str)
}

fun lt(tx: Taxon) : LazyTaxon {
    return LazyTaxon(tx)
}

fun String.toTaxon(namespaceHint: String? = null) : Taxon {
    return t(this, namespaceHint)
}

fun ConfigValue?.asTaxon(namespaceHint : String? = null) : Taxon {
    return asStr()?.toTaxon(namespaceHint) ?: UnknownThing
}


object Taxonomy {
    init {
        registerCustomTypeRender<Taxon>()
    }

    val RootNamespace = ""

    val UnknownThing = Taxon(RootNamespace, "UnknownThing")
    val Sentinel = Taxon(RootNamespace, "Sentinel")




    val taxonsByName = TreeMap<String, List<Taxon>>(java.lang.String.CASE_INSENSITIVE_ORDER)
    val taxonsByAllPossibleIdentifiers = TreeMap<String, List<Taxon>>(java.lang.String.CASE_INSENSITIVE_ORDER)
    var loaded = false


    fun normalizeString(str: String) : String {
        return str.lowercase()
    }

    fun taxon(str : String, namespaceHint: String? = null, warnOnAbsence: Boolean = true) : Taxon {
        val ts = taxonsByAllPossibleIdentifiers[str]
        return if (ts == null) {
            if (warnOnAbsence) {
                warn("Failed to look up taxon: $str")
            }
            UnknownThing
        } else if (ts.size == 1) {
            ts[0]
        } else {
            if (namespaceHint != null) {
                val resolved = ts.find { taxon -> taxon.normalizedNamespace.contains(normalizeString(namespaceHint)) }
                if (resolved == null) {
                    if (warnOnAbsence) {
                        warn("Taxon resolution with multiple possible results, namespace hint of $namespaceHint did not help, choosing arbitrarily $ts")
                    }
                    ts[0]
                } else {
                    resolved
                }
            } else {
                warn("Taxon resolution with multiple possible results, choosing arbitrarily among $ts")
                ts[0]
            }
        }
    }

    fun childrenOf(t: Taxon) : List<Taxon> {
        val ret = mutableListOf<Taxon>()
        for (values in taxonsByName.values) {
            for (taxon in values) {
                if (taxon.isA(t)) {
                    ret.add(taxon)
                }
            }
        }
        return ret
    }

    fun allTaxons() : List<Taxon> {
        val ret = mutableListOf<Taxon>()
        for (values in taxonsByName.values) {
            for (taxon in values) {
                ret.add(taxon)
            }
        }
        return ret
    }

    fun createTaxon(namespace: String, name: String, parents: List<Taxon>) : Taxon {
        taxon(name, namespace, warnOnAbsence = false).let { t ->
            if (t == UnknownThing || t.namespace != namespace) {
                return createTaxonUnnormalized(namespace, name, parents)
            } else {
                return t
            }
        }
    }

    fun deleteTaxon(t: Taxon) {
        var accumIdentifier = t.name
        for (s in t.namespaceSegments.reversed()) {
            accumIdentifier = "$s.$accumIdentifier"
            taxonsByAllPossibleIdentifiers[accumIdentifier] = (taxonsByAllPossibleIdentifiers[accumIdentifier] ?: emptyList()) - t
        }
        taxonsByName[t.name] = (taxonsByName[t.name] ?: emptyList()) - t
        taxonsByAllPossibleIdentifiers[t.name] = (taxonsByAllPossibleIdentifiers[t.name] ?: emptyList()) - t
    }

    fun updateTaxon(t: Taxon, namespace: String, name: String, parents: List<Taxon>) : Taxon {
        deleteTaxon(t)

        return createTaxon(namespace = namespace, name = name, parents = parents)
    }

    private fun createTaxonPreNormalized(namespace: String, name: String, parents: List<Taxon>) : Taxon {
        val newTaxon = Taxon(namespace, name, parents)
        var accumIdentifier = newTaxon.name
        for (s in newTaxon.namespaceSegments.reversed()) {
            accumIdentifier = "$s.$accumIdentifier"
            taxonsByAllPossibleIdentifiers[accumIdentifier] = (taxonsByAllPossibleIdentifiers[accumIdentifier] ?: emptyList()) + newTaxon
        }
        taxonsByName[newTaxon.name] = (taxonsByName[newTaxon.name] ?: emptyList()) + newTaxon
        taxonsByAllPossibleIdentifiers[newTaxon.name] = (taxonsByAllPossibleIdentifiers[newTaxon.name] ?: emptyList()) + newTaxon
        return newTaxon
    }

    private fun createTaxonUnnormalized(namespace: String, name: String, parents: List<Taxon>) : Taxon {
        val newTaxon = Taxon(namespace, name, parents)
        val normalizedName = normalizeString(name)

        var accumIdentifier = normalizedName
        for (s in newTaxon.namespaceSegments.reversed()) {
            accumIdentifier = "$s.$accumIdentifier"
            taxonsByAllPossibleIdentifiers[accumIdentifier] = (taxonsByAllPossibleIdentifiers[accumIdentifier] ?: emptyList()) + newTaxon
        }
        taxonsByName[normalizedName] = (taxonsByName[normalizedName] ?: emptyList()) + newTaxon
        taxonsByAllPossibleIdentifiers[normalizedName] = (taxonsByAllPossibleIdentifiers[normalizedName] ?: emptyList()) + newTaxon
        return newTaxon
    }

    fun loadWithTaxonomySources(sources: List<String>) {
        val projectName = System.getProperty("projectName")
        assert(projectName != null)

        val conf = Resources.config("$projectName/taxonomy/taxonomy.sml")

        val taxonomySources = conf["TaxonomySources"].asList().map { it.asList().mapNotNull { s -> s.asStr() } } + sources.map { listOf(it) }

        val allTaxons = mutableListOf<ProtoTaxon>()
        conf["Taxonomy"]?.let { extractProtoTaxons(it, "", isNestedTaxonomy = true, emptyList(), allTaxons) }

        for (sections in taxonomySources) {
            if (sections.isEmpty()) { err("Empty list in taxonomy sources? $sections") }
            val srcConf = Resources.configOpt("$projectName/${sections[0]}")
            if (srcConf == null) {
                warn("taxon source ${sections[0]} does not exist, skipping")
                continue
            }
            srcConf.root().forEach { k,v ->
                if (sections.size == 1 || sections.any { s -> s == k }) {
                    val parentName = if (k.endsWith("ies")) {
                        k.substring(0 until k.length - 3) + "y"
                    } else if (k.endsWith("es")) {
                        k.substring(0 until k.length - 2) + "e"
                    } else if (k.endsWith("is")) {
                        k
                    } else {
                        k.substring(0 until k.length - 1)
                    }
                    allTaxons.add(ProtoTaxon(RootNamespace, parentName))
                    extractProtoTaxons(v, k, isNestedTaxonomy = false, listOf(parentName), allTaxons)
                }
            }
        }


        val protoTaxonsByName = TreeMap<String, List<ProtoTaxon>>(java.lang.String.CASE_INSENSITIVE_ORDER)
        for (t in allTaxons) {
            protoTaxonsByName[t.name] = (protoTaxonsByName[t.name] ?: emptyList()) + t
        }

        fun findTaxon(pt: ProtoTaxon) : Taxon? {
            val l : List<Taxon> = (taxonsByName[pt.name] ?: emptyList())
            return l.find { t -> t.normalizedNamespace == normalizeString(pt.namespace) }
        }

        fun namespaceSimilarity(a : String, b : String) : Int {
            val aSections = a.split('.')
            val bSections = b.split('.')
            var matchCount = 0
            for (i in 0 until min(aSections.size, bSections.size)) {
                if (normalizeString(aSections[i]) == normalizeString(bSections[i])) {
                    matchCount++
                } else {
                    break
                }
            }
            return matchCount
        }

        fun findProtoTaxonByIdentifier(str: String, baseNamespace: String): ProtoTaxon? {
            val lastDotIndex = str.lastIndexOf('.')
            val (namespace, name) = if (lastDotIndex == -1) {
                null to normalizeString(str)
            } else {
                normalizeString(str.substring(0, lastDotIndex)) to str.substring(lastDotIndex + 1, str.length)
            }

            val possibleMatches = protoTaxonsByName[name]?.filter { pt -> namespace == null || normalizeString(pt.namespace) == namespace } ?: emptyList()
            return if (possibleMatches.size > 1) {
                possibleMatches.maxBy { ppt -> namespaceSimilarity(ppt.namespace, baseNamespace) }
            } else if (possibleMatches.isNotEmpty()) {
                possibleMatches[0]
            } else {
                null
            }
        }

        fun processProtoTaxon(pt: ProtoTaxon) : Taxon {
            val existing = findTaxon(pt)
            return if (existing == null) {
                var parentTaxons : List<Taxon> = emptyList()
                for (pi in pt.parentIdentifiers) {
                    val parentPT = findProtoTaxonByIdentifier(str = pi, baseNamespace = pt.namespace)
                    if (parentPT == null) {
                        err("Taxon with unresolveable parent $pi")
                    } else {
                        parentTaxons = parentTaxons + processProtoTaxon(parentPT)
                    }
                }

                val newTaxon = createTaxonPreNormalized(pt.namespace, pt.name, parentTaxons)

                newTaxon
            } else {
                existing
            }
        }

        for (t in allTaxons) {
            processProtoTaxon(t)
        }
    }

    fun load() {
        if (loaded) {
            return
        }
        loaded = true

        loadWithTaxonomySources(emptyList())
    }

    init {
        load()
    }

}


data class Identity (val identity : Taxon, val name : String? = null) : GameData {
    companion object : DataType<Identity>( Identity(UnknownThing), sparse = false )
    override fun dataType() : DataType<*> { return Identity }

    fun nameOrKind() : String {
        return name ?: identity.toString()
    }
}



fun main() {
    Taxonomy.taxon("X")
}


